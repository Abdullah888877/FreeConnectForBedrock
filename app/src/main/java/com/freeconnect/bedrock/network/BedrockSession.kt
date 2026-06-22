package com.freeconnect.bedrock.network

import android.util.Base64
import android.util.Log
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "BedrockSession"

/** Max bytes per resource pack chunk (1 MiB). */
private const val MAX_CHUNK = 1_048_576L

/**
 * Bedrock application-layer session for a single connected console/client.
 *
 * ## Why this exists
 * Consoles (Xbox, PS5, Switch) cannot enter custom server IPs.  The only way
 * they can join third-party servers is via the LAN tab.  This class makes the
 * app behave as a real (but minimal) Bedrock server so that:
 *
 *   1. The console completes Login and the ECDH encryption handshake.
 *   2. The app serves resource packs directly from the device storage — the
 *      console downloads and caches them just like it would from a real server.
 *   3. After all packs are confirmed, the app sends a Transfer packet (0x55)
 *      that redirects the console to the real server address.  Because the
 *      console already has the packs cached, they are applied immediately.
 *
 * ## State machine
 *   AWAIT_LOGIN → AWAIT_ENCRYPTION → AWAIT_PACK_RESPONSE → SERVING_PACKS → DONE
 *
 * ## Encryption
 * Bedrock 1.20+ requires AES-256-CFB8 encryption established via ECDH (P-384).
 * The client's public key is extracted from the JWT chain inside the Login
 * packet.  The server generates its own keypair, computes the shared secret,
 * and derives the AES key with SHA-256(salt‖sharedSecret).
 *
 * @param sendBatch Callback that takes a raw batch payload (starting with 0xFE)
 *                  and delivers it to the client via the RakNet reliable layer.
 * @param enabledPacks Locally stored packs to serve before the Transfer.
 * @param serverIp   The real Bedrock server to redirect the console to.
 * @param serverPort The real server's port (usually 19132).
 */
class BedrockSession(
    private val sendBatch: (ByteArray) -> Unit,
    private val enabledPacks: List<LocalResourcePack>,
    private val serverIp: String,
    private val serverPort: Int
) {
    private enum class Phase {
        AWAIT_LOGIN,
        AWAIT_ENCRYPTION,
        AWAIT_PACK_RESPONSE,
        SERVING_PACKS,
        DONE
    }

    private var phase = Phase.AWAIT_LOGIN

    // ── Encryption state ──────────────────────────────────────────────────────
    private var encEnabled  = false
    private var encCipher: Cipher? = null  // encrypt sends
    private var decCipher: Cipher? = null  // decrypt receives
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

    // ── Pack-serving state ────────────────────────────────────────────────────
    private val requestedPacks = mutableListOf<LocalResourcePack>()
    /** How many chunks have been sent per pack UUID. */
    private val chunksSent = mutableMapOf<String, Int>()

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Feed a raw Bedrock batch packet (first byte == 0xFE) received from the
     * client into this session.
     */
    fun handleBatch(data: ByteArray) {
        if (data.isEmpty() || data[0] != 0xFE.toByte()) return
        try {
            val encrypted = data.copyOfRange(1, data.size)
            val compressed = if (encEnabled && decCipher != null) {
                decCipher!!.update(encrypted) ?: return
            } else {
                encrypted
            }
            val decompressed = zlibInflate(compressed)
            dispatchGamePackets(decompressed)
        } catch (e: Exception) {
            Log.w(TAG, "handleBatch error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private fun dispatchGamePackets(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.hasRemaining()) {
            val totalLen = readUVarInt(buf)
            if (totalLen <= 0 || buf.remaining() < totalLen) break
            val nextPos = buf.position() + totalLen
            val packetId = readUVarInt(buf)
            val bodyLen  = nextPos - buf.position()
            val body = ByteArray(maxOf(0, bodyLen)).also { buf.get(it) }
            buf.position(nextPos) // guard against partial parsing
            onGamePacket(packetId, body)
        }
    }

    private fun onGamePacket(id: Int, body: ByteArray) {
        Log.d(TAG, "← 0x${id.toString(16).uppercase()} (${body.size}B) phase=$phase")
        when (id) {
            0x01 -> onLogin(body)
            0x04 -> onClientHandshake()
            0x08 -> onPackResponse(body)
            0x54 -> onChunkRequest(body)
            else -> { /* ignore */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Game packet handlers
    // ─────────────────────────────────────────────────────────────────────────

    /** Login (0x01) — set up ECDH and send ServerToClientHandshake. */
    private fun onLogin(body: ByteArray) {
        if (phase != Phase.AWAIT_LOGIN) return
        try {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            buf.int // protocol version
            val chainLen   = buf.int
            val chainBytes = ByteArray(chainLen).also { buf.get(it) }

            val clientPubKey = extractClientPublicKey(chainBytes)
            if (clientPubKey == null) {
                Log.w(TAG, "No client public key — skipping encryption")
                beginResourcePacks()
                return
            }

            // Generate server EC-384 keypair
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp384r1"))
            val serverKp = kpg.generateKeyPair()

            // ECDH shared secret → AES key
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(serverKp.private)
            ka.doPhase(clientPubKey, true)
            val shared = ka.generateSecret()
            val encKey = MessageDigest.getInstance("SHA-256").run {
                update(salt); update(shared); digest()
            }

            // Init ciphers — IV = first 16 bytes of key
            val keySpec = SecretKeySpec(encKey, "AES")
            val ivSpec  = IvParameterSpec(encKey.copyOf(16))
            encCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            }
            decCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            }

            // Send unencrypted handshake JWT → encryption starts after client responds
            sendPlain(buildServerHandshake(serverKp.private, serverKp.public, salt))
            phase = Phase.AWAIT_ENCRYPTION
            Log.i(TAG, "ServerToClientHandshake sent")
        } catch (e: Exception) {
            Log.e(TAG, "Login error: ${e.message} — skipping encryption")
            beginResourcePacks()
        }
    }

    /** ClientToServerHandshake (0x04) — flip the encryption flag then send packs. */
    private fun onClientHandshake() {
        if (phase != Phase.AWAIT_ENCRYPTION) return
        encEnabled = true
        Log.i(TAG, "Encryption active")
        beginResourcePacks()
    }

    /**
     * ResourcePackClientResponse (0x08).
     *
     * Status codes:
     *   1 = REFUSED (no packs wanted)
     *   2 = SEND_PACKS (client wants these specific packs)
     *   3 = HAVE_ALL_PACKS (all packs cached → send Stack)
     *   4 = COMPLETED (stack acknowledged → Transfer)
     */
    private fun onPackResponse(body: ByteArray) {
        if (phase != Phase.AWAIT_PACK_RESPONSE && phase != Phase.SERVING_PACKS) return
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val status = buf.get().toInt() and 0xFF
        Log.d(TAG, "ResourcePackClientResponse status=$status")
        when (status) {
            1 -> sendTransfer() // refused — just redirect

            2 -> { // wants specific packs
                val count = buf.short.toInt() and 0xFFFF
                requestedPacks.clear()
                repeat(count) {
                    val uLen = buf.short.toInt() and 0xFFFF
                    val uuid = String(ByteArray(uLen).also { buf.get(it) })
                    enabledPacks.find { it.uuid == uuid }?.let { requestedPacks.add(it) }
                }
                requestedPacks.forEach { sendDataInfo(it) }
                phase = Phase.SERVING_PACKS
            }

            3 -> { // has all packs
                sendEncrypted(packStack())
            }

            4 -> sendTransfer() // fully done
        }
    }

    /** ResourcePackChunkRequest (0x54) — serve the requested chunk. */
    private fun onChunkRequest(body: ByteArray) {
        val buf  = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val uLen = buf.short.toInt() and 0xFFFF
        val uuid = String(ByteArray(uLen).also { buf.get(it) })
        val idx  = buf.int
        val pack = enabledPacks.find { it.uuid == uuid } ?: return
        sendChunk(pack, idx)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun beginResourcePacks() {
        sendEncrypted(playStatus(0))             // LOGIN_SUCCESS
        sendEncrypted(resourcePacksInfo())
        phase = Phase.AWAIT_PACK_RESPONSE
    }

    private fun sendDataInfo(pack: LocalResourcePack) {
        val file = File(pack.filePath)
        if (!file.exists()) return
        val size   = file.length()
        val chunks = ((size + MAX_CHUNK - 1) / MAX_CHUNK).toInt()
        val hash   = sha256Hex(file.readBytes())
        sendEncrypted(resourcePackDataInfo(pack.uuid, size, chunks, hash))
    }

    private fun sendChunk(pack: LocalResourcePack, idx: Int) {
        val file   = File(pack.filePath)
        if (!file.exists()) return
        val offset = idx * MAX_CHUNK
        val slice  = file.inputStream().use { s ->
            s.skip(offset)
            s.readNBytes(minOf(MAX_CHUNK, file.length() - offset).toInt())
        }
        sendEncrypted(resourcePackChunk(pack.uuid, idx, offset, slice))
        chunksSent[pack.uuid] = (chunksSent[pack.uuid] ?: 0) + 1

        // When every chunk of every requested pack is sent, send the stack
        val allDone = requestedPacks.all { p ->
            val totalChunks = ((File(p.filePath).length() + MAX_CHUNK - 1) / MAX_CHUNK).toInt()
            (chunksSent[p.uuid] ?: 0) >= totalChunks
        }
        if (allDone) sendEncrypted(packStack())
    }

    private fun sendTransfer() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        sendEncrypted(transferPayload(serverIp, serverPort))
        Log.i(TAG, "Transfer → $serverIp:$serverPort")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildServerHandshake(
        priv: java.security.PrivateKey,
        pub:  java.security.PublicKey,
        salt: ByteArray
    ): ByteArray {
        val pubB64  = Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)

        val h = b64url("""{"alg":"ES384","x5u":"$pubB64"}""")
        val p = b64url("""{"salt":"$saltB64"}""")
        val sigBytes = Signature.getInstance("SHA384withECDSA").run {
            initSign(priv); update("$h.$p".toByteArray()); sign()
        }
        val jwt = "$h.$p.${b64url(sigBytes)}"
        val jwtBytes = jwt.toByteArray()

        return ByteBuffer.allocate(1 + 4 + jwtBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0x03); putInt(jwtBytes.size); put(jwtBytes)
            }.rawBytes()
    }

    private fun playStatus(status: Int) =
        ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x02); putInt(status)
        }.rawBytes()

    private fun resourcePacksInfo(): ByteArray {
        val b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x06)
        b.put(0)           // mustAccept
        b.put(0)           // hasScripts
        b.put(0)           // forceServerPacks (1.20+)
        b.putShort(0)      // behaviour pack count
        b.putShort(enabledPacks.size.toShort())
        enabledPacks.forEach { p ->
            b.leString(p.uuid); b.leString(p.version)
            b.putLong(p.sizeBytes)
            b.leString(""); b.leString(""); b.leString("")
            b.put(0); b.put(0)  // hasScripts, isAddon
        }
        return b.rawBytes()
    }

    private fun resourcePackDataInfo(uuid: String, size: Long, chunks: Int, hash: String): ByteArray {
        val b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x52.toByte())
        b.leString(uuid)
        b.putLong(MAX_CHUNK)   // maxChunkSize
        b.putInt(chunks)
        b.putLong(size)
        b.leString(hash)
        b.put(0)               // isPremium
        b.put(1)               // packType 1 = resource
        return b.rawBytes()
    }

    private fun resourcePackChunk(uuid: String, idx: Int, offset: Long, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(1 + 2 + uuid.length + 4 + 8 + 4 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x53.toByte())
        b.leString(uuid)
        b.putInt(idx)
        b.putLong(offset)
        b.putInt(data.size)
        b.put(data)
        return b.rawBytes()
    }

    private fun packStack(): ByteArray {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x07)
        b.put(0)                          // mustAccept
        b.putInt(0)                       // behaviour pack count
        b.putInt(enabledPacks.size)
        enabledPacks.forEach { p ->
            b.leString(p.uuid); b.leString(p.version); b.leString("")
        }
        b.put(0)                          // experimental gameplay
        b.leString("*")                   // game version
        b.putInt(0)                       // experiments
        b.put(0)                          // experiments previously toggled
        return b.rawBytes()
    }

    private fun transferPayload(ip: String, port: Int): ByteArray {
        val ipBytes = ip.toByteArray()
        return ByteBuffer.allocate(1 + 2 + ipBytes.size + 2)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0x55.toByte())
                putShort(ipBytes.size.toShort())
                put(ipBytes)
                putShort(port.toShort())
            }.rawBytes()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sending wrappers
    // ─────────────────────────────────────────────────────────────────────────

    /** Send a game packet without encryption (used for the handshake JWT). */
    private fun sendPlain(packetBytes: ByteArray) {
        val inner = encodeUVarInt(packetBytes.size) + packetBytes
        val batch = byteArrayOf(0xFE.toByte()) + zlibDeflate(inner)
        sendBatch(batch)
    }

    /** Compress (and optionally encrypt), then hand off to the RakNet layer. */
    private fun sendEncrypted(packetBytes: ByteArray) {
        val inner      = encodeUVarInt(packetBytes.size) + packetBytes
        val compressed = zlibDeflate(inner)
        val payload    = if (encEnabled && encCipher != null) {
            encCipher!!.update(compressed) ?: compressed
        } else compressed
        sendBatch(byteArrayOf(0xFE.toByte()) + payload)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Crypto / JWT helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractClientPublicKey(chainBytes: ByteArray): PublicKey? = try {
        val arr  = JSONObject(String(chainBytes, Charsets.UTF_8)).getJSONArray("chain")
        val jwt  = arr.getString(arr.length() - 1)
        val pl   = jwt.split(".").getOrNull(1) ?: return null
        val json = JSONObject(String(Base64.decode(pl, Base64.URL_SAFE or Base64.NO_PADDING)))
        val b64  = json.optString("identityPublicKey").takeIf { it.isNotBlank() } ?: return null
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)))
    } catch (e: Exception) { Log.w(TAG, "extractClientPublicKey: ${e.message}"); null }

    private fun sha256Hex(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun b64url(s: String) = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // ─────────────────────────────────────────────────────────────────────────
    // Buffer / varint / compression helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** LE uint16-prefixed UTF-8 string (Bedrock pack entry format). */
    private fun ByteBuffer.leString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        putShort(bytes.size.toShort()); put(bytes)
    }

    private fun ByteBuffer.rawBytes() = array().copyOf(position())

    private fun readUVarInt(buf: ByteBuffer): Int {
        var r = 0; var s = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toInt() and 0xFF
            r = r or ((b and 0x7F) shl s)
            if (b and 0x80 == 0) break
            s += 7
        }
        return r
    }

    private fun encodeUVarInt(v: Int): ByteArray {
        var n = v; val out = mutableListOf<Byte>()
        do { var b = n and 0x7F; n = n ushr 7; if (n != 0) b = b or 0x80; out.add(b.toByte()) } while (n != 0)
        return out.toByteArray()
    }

    private fun zlibInflate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val inf = Inflater(); inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
        inf.end(); return out.toByteArray()
    }

    private fun zlibDeflate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val def = Deflater(Deflater.DEFAULT_COMPRESSION); def.setInput(data); def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) out.write(buf, 0, def.deflate(buf))
        def.end(); return out.toByteArray()
    }
}
