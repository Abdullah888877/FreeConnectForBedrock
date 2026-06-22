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
 * ## Protocol flow (Bedrock 1.19.10 / protocol 527+)
 *
 * Bedrock 1.19.10 added TWO important changes that older code misses:
 *
 * ### 1. Compression-type byte in every batch (protocol 527+)
 * Every batch packet now looks like:
 *   [0xFE] [comp_type] [payload]
 * where comp_type:
 *   0xFF = no compression  (used before NetworkSettings is negotiated)
 *   0x00 = zlib            (used after NetworkSettings)
 *
 * ### 2. RequestNetworkSettings before Login
 *   Client → RequestNetworkSettings (0xC1 / 193)  [0xFF / no compression]
 *   Server → NetworkSettings        (0x8F / 143)  [0xFF / no compression]
 *   -- compression now active, both sides use 0x00+zlib from here --
 *   Client → Login                               [0x00 / zlib]
 *   Server → ServerToClientHandshake             [0x00 / zlib, NOT encrypted]
 *   -- decryption must start HERE on the server (client sends enc from next pkt) --
 *   Client → ClientToServerHandshake             [0x00 / zlib + AES-256-CFB8]
 *   Server → PlayStatus + ResourcePacksInfo      [0x00 / zlib + AES-256-CFB8]
 *   ... pack chunk exchange ...
 *   Server → Transfer (0x55)                     [0x00 / zlib + AES-256-CFB8]
 *
 * @param sendBatch Sends a raw batch (starts 0xFE) over the RakNet reliable layer.
 * @param enabledPacks Locally stored packs to serve before Transfer.
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
        AWAIT_NETWORK_SETTINGS, // waiting for RequestNetworkSettings (or Login on old clients)
        AWAIT_LOGIN,            // NetworkSettings sent, waiting for Login
        AWAIT_ENCRYPTION,       // ServerToClientHandshake sent, waiting for client ack
        AWAIT_PACK_RESPONSE,    // PlayStatus + ResourcePacksInfo sent
        SERVING_PACKS,          // actively sending chunks
        DONE
    }

    private var phase = Phase.AWAIT_NETWORK_SETTINGS

    // ── Compression ───────────────────────────────────────────────────────────
    /** True once NetworkSettings has been exchanged — both sides use zlib. */
    private var compressionActive = false

    // ── Encryption ────────────────────────────────────────────────────────────
    /** True once we've sent ServerToClientHandshake (client encrypts from next pkt). */
    private var encEnabled  = false
    private var encCipher: Cipher? = null
    private var decCipher: Cipher? = null
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

    // ── Pack-serving state ────────────────────────────────────────────────────
    private val requestedPacks = mutableListOf<LocalResourcePack>()
    private val chunksSent     = mutableMapOf<String, Int>()

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Feed a raw batch packet (first byte == 0xFE) received from the client
     * into this session.
     */
    fun handleBatch(data: ByteArray) {
        if (data.isEmpty() || data[0] != 0xFE.toByte()) return
        try {
            // Strip 0xFE → [compType][payload]  (protocol 527+)
            val afterHeader = data.copyOfRange(1, data.size)

            val inner: ByteArray = if (encEnabled && decCipher != null) {
                // Decrypt first; result is [compType][payload]
                val dec = decCipher!!.update(afterHeader) ?: return
                decompress(dec)
            } else {
                decompress(afterHeader)
            }

            dispatchGamePackets(inner)
        } catch (e: Exception) {
            Log.w(TAG, "handleBatch error (phase=$phase): ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compression helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decompress a batch payload that may or may not carry a compression-type byte.
     *
     * Modern Bedrock (protocol 527+) format:
     *   [0xFF][raw]   → no compression
     *   [0x00][zlib]  → zlib
     *
     * Older Bedrock sent raw zlib without the leading type byte. We detect this
     * by checking for zlib magic 0x78 as the first byte.
     */
    private fun decompress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        return when (val b = data[0].toInt() and 0xFF) {
            0xFF -> data.copyOfRange(1, data.size)             // no compression
            0x00 -> zlibInflate(data.copyOfRange(1, data.size)) // zlib with type byte
            0x78 -> zlibInflate(data)                           // old-style zlib (no type byte)
            else -> {
                Log.d(TAG, "Unknown comp type 0x${b.toString(16)}, trying raw")
                try { zlibInflate(data) } catch (_: Exception) { data }
            }
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
            val nextPos  = buf.position() + totalLen
            val packetId = readUVarInt(buf)
            val bodyLen  = nextPos - buf.position()
            val body     = ByteArray(maxOf(0, bodyLen)).also { buf.get(it) }
            buf.position(nextPos)
            onGamePacket(packetId, body)
        }
    }

    private fun onGamePacket(id: Int, body: ByteArray) {
        Log.d(TAG, "← 0x${id.toString(16).uppercase()} (${body.size}B) phase=$phase")
        when (id) {
            192, 193 -> onRequestNetworkSettings(body) // RequestNetworkSettings
            0x01     -> onLogin(body)
            0x04     -> onClientHandshake()
            0x08     -> onPackResponse(body)
            0x54     -> onChunkRequest(body)
            else     -> { /* ignore */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RequestNetworkSettings (0xC1 / 193) — Bedrock 1.19.10+.
     *
     * The client sends this before Login to negotiate compression.  We reply
     * with NetworkSettings (zlib, threshold=0) and then both sides switch to
     * 0x00-prefixed zlib batches.
     *
     * We also accept Login directly on old clients that skip this step.
     */
    private fun onRequestNetworkSettings(body: ByteArray) {
        Log.i(TAG, "RequestNetworkSettings received")
        sendNoComp(buildNetworkSettings())
        compressionActive = true
        phase = Phase.AWAIT_LOGIN
    }

    /**
     * Login (0x01).
     *
     * Extract client's EC-P384 public key from the JWT chain, perform ECDH,
     * derive the AES-256-CFB8 key, and send ServerToClientHandshake.
     *
     * IMPORTANT: We enable decryption (encEnabled = true) right after sending
     * the handshake because the client starts encrypting its NEXT packet
     * (ClientToServerHandshake).  If we wait until we receive that packet to
     * enable decryption, we've already missed the chance.
     */
    private fun onLogin(body: ByteArray) {
        if (phase != Phase.AWAIT_NETWORK_SETTINGS && phase != Phase.AWAIT_LOGIN) return
        // Old clients skip RequestNetworkSettings — enable compression now.
        if (!compressionActive) compressionActive = true

        try {
            val buf      = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            buf.int      // protocol version
            val chainLen = buf.int
            val chainBytes = ByteArray(chainLen).also { buf.get(it) }

            val clientPubKey = extractClientPublicKey(chainBytes)
            if (clientPubKey == null) {
                Log.w(TAG, "No client pub key — skipping encryption")
                beginResourcePacks()
                return
            }

            // Generate server EC-384 keypair
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp384r1"))
            val serverKp = kpg.generateKeyPair()

            // ECDH → AES-256-CFB8 key = SHA-256(salt‖sharedSecret)
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(serverKp.private)
            ka.doPhase(clientPubKey, true)
            val shared = ka.generateSecret()
            val encKey = MessageDigest.getInstance("SHA-256").run {
                update(salt); update(shared); digest()
            }

            val keySpec = SecretKeySpec(encKey, "AES")
            val ivSpec  = IvParameterSpec(encKey.copyOf(16))
            encCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            }
            decCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            }

            // Send handshake unencrypted (it IS compressed with 0x00 prefix)
            sendCompressed(buildServerHandshake(serverKp.private, serverKp.public, salt))

            // ★ Enable decryption NOW — the client encrypts from its very next packet.
            encEnabled = true
            phase = Phase.AWAIT_ENCRYPTION
            Log.i(TAG, "ServerToClientHandshake sent, decryption armed")

        } catch (e: Exception) {
            Log.e(TAG, "Login error: ${e.message} — falling back (no encryption)")
            beginResourcePacks()
        }
    }

    /** ClientToServerHandshake (0x04) — flip the send-encrypt flag and send packs. */
    private fun onClientHandshake() {
        if (phase != Phase.AWAIT_ENCRYPTION) return
        // encEnabled already true (set in onLogin after sending handshake).
        // From this point our sends must also be encrypted.
        Log.i(TAG, "ClientToServerHandshake received — encryption fully active")
        beginResourcePacks()
    }

    /**
     * ResourcePackClientResponse (0x08).
     *
     * Status codes:
     *   1 = REFUSED
     *   2 = SEND_PACKS  (client wants these specific pack UUIDs)
     *   3 = HAVE_ALL_PACKS
     *   4 = COMPLETED
     */
    private fun onPackResponse(body: ByteArray) {
        if (phase != Phase.AWAIT_PACK_RESPONSE && phase != Phase.SERVING_PACKS) return
        val buf    = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val status = buf.get().toInt() and 0xFF
        Log.d(TAG, "ResourcePackClientResponse status=$status")
        when (status) {
            1 -> sendTransfer()
            2 -> {
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
            3 -> sendEncrypted(packStack())
            4 -> sendTransfer()
        }
    }

    /** ResourcePackChunkRequest (0x54). */
    private fun onChunkRequest(body: ByteArray) {
        val buf  = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val uLen = buf.short.toInt() and 0xFFFF
        val uuid = String(ByteArray(uLen).also { buf.get(it) })
        val idx  = buf.int
        enabledPacks.find { it.uuid == uuid }?.let { sendChunk(it, idx) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun beginResourcePacks() {
        sendEncrypted(playStatus(0))
        sendEncrypted(resourcePacksInfo())
        phase = Phase.AWAIT_PACK_RESPONSE
    }

    private fun sendDataInfo(pack: LocalResourcePack) {
        val file = File(pack.filePath); if (!file.exists()) return
        val size   = file.length()
        val chunks = ((size + MAX_CHUNK - 1) / MAX_CHUNK).toInt()
        sendEncrypted(resourcePackDataInfo(pack.uuid, size, chunks, sha256Hex(file.readBytes())))
    }

    private fun sendChunk(pack: LocalResourcePack, idx: Int) {
        val file   = File(pack.filePath); if (!file.exists()) return
        val offset = idx * MAX_CHUNK
        val slice  = file.inputStream().use { s ->
            s.skip(offset)
            s.readNBytes(minOf(MAX_CHUNK, file.length() - offset).toInt())
        }
        sendEncrypted(resourcePackChunk(pack.uuid, idx, offset, slice))
        chunksSent[pack.uuid] = (chunksSent[pack.uuid] ?: 0) + 1

        val allDone = requestedPacks.all { p ->
            val total = ((File(p.filePath).length() + MAX_CHUNK - 1) / MAX_CHUNK).toInt()
            (chunksSent[p.uuid] ?: 0) >= total
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

    /**
     * NetworkSettings (packet ID 143 / 0x8F).
     * Note: 143 > 127 so its varint encoding is 2 bytes: [0x8F, 0x01].
     */
    private fun buildNetworkSettings(): ByteArray {
        val idVarInt = encodeUVarInt(143)
        val buf = ByteBuffer.allocate(idVarInt.size + 2 + 2 + 1 + 1 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(idVarInt)
        buf.putShort(0)    // compression_threshold = 0 (always compress)
        buf.putShort(0)    // compression_algorithm = 0 (zlib)
        buf.put(0)         // client_throttle_enabled
        buf.put(0)         // client_throttle_threshold
        buf.putFloat(0f)   // client_throttle_scalar
        return buf.rawBytes()
    }

    private fun buildServerHandshake(
        priv: java.security.PrivateKey,
        pub:  java.security.PublicKey,
        salt: ByteArray
    ): ByteArray {
        val pubB64  = Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)

        val h = b64url("""{"alg":"ES384","x5u":"$pubB64"}""")
        val p = b64url("""{"salt":"$saltB64"}""")
        val rawSig = derToRawEcSig(
            Signature.getInstance("SHA384withECDSA").run {
                initSign(priv); update("$h.$p".toByteArray()); sign()
            }, 48
        )
        val jwt      = "$h.$p.${b64url(rawSig)}"
        val jwtBytes = jwt.toByteArray()
        return ByteBuffer.allocate(1 + 4 + jwtBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
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
        b.put(0)  // mustAccept
        b.put(0)  // hasScripts
        b.put(0)  // forceServerPacks (1.20+)
        b.putShort(0)  // behaviour pack count
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
        b.leString(uuid); b.putLong(MAX_CHUNK)
        b.putInt(chunks); b.putLong(size)
        b.leString(hash); b.put(0); b.put(1)
        return b.rawBytes()
    }

    private fun resourcePackChunk(uuid: String, idx: Int, offset: Long, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(1 + 2 + uuid.length + 4 + 8 + 4 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x53.toByte())
        b.leString(uuid); b.putInt(idx); b.putLong(offset)
        b.putInt(data.size); b.put(data)
        return b.rawBytes()
    }

    private fun packStack(): ByteArray {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x07); b.put(0); b.putInt(0)
        b.putInt(enabledPacks.size)
        enabledPacks.forEach { p -> b.leString(p.uuid); b.leString(p.version); b.leString("") }
        b.put(0); b.leString("*"); b.putInt(0); b.put(0)
        return b.rawBytes()
    }

    private fun transferPayload(ip: String, port: Int): ByteArray {
        val ipBytes = ip.toByteArray()
        return ByteBuffer.allocate(1 + 2 + ipBytes.size + 2)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0x55.toByte()); putShort(ipBytes.size.toShort()); put(ipBytes)
                putShort(port.toShort())
            }.rawBytes()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send wrappers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Send with NO compression (0xFF type byte) — used for NetworkSettings.
     * [0xFE][0xFF][varint_len][packet_bytes]
     */
    private fun sendNoComp(packetBytes: ByteArray) {
        val inner = encodeUVarInt(packetBytes.size) + packetBytes
        sendBatch(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + inner)
    }

    /**
     * Send zlib-compressed but NOT encrypted — used for ServerToClientHandshake
     * (sent before the client responds to start encryption).
     * [0xFE][0x00][zlib(varint_len + packet_bytes)]
     */
    private fun sendCompressed(packetBytes: ByteArray) {
        val inner = encodeUVarInt(packetBytes.size) + packetBytes
        sendBatch(byteArrayOf(0xFE.toByte(), 0x00.toByte()) + zlibDeflate(inner))
    }

    /**
     * Send zlib-compressed and optionally AES-256-CFB8 encrypted.
     * Before encryption: [0xFE][0x00][zlib(varint_len + packet_bytes)]
     * After encryption:  [0xFE][encrypt(0x00 + zlib(varint_len + packet_bytes))]
     */
    private fun sendEncrypted(packetBytes: ByteArray) {
        val inner      = encodeUVarInt(packetBytes.size) + packetBytes
        val compressed = zlibDeflate(inner)
        val withType   = byteArrayOf(0x00.toByte()) + compressed

        val payload = if (encEnabled && encCipher != null) {
            encCipher!!.update(withType) ?: withType
        } else withType

        sendBatch(byteArrayOf(0xFE.toByte()) + payload)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Crypto helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractClientPublicKey(chainBytes: ByteArray): PublicKey? = try {
        val arr  = JSONObject(String(chainBytes, Charsets.UTF_8)).getJSONArray("chain")
        val jwt  = arr.getString(arr.length() - 1)
        val pl   = jwt.split(".").getOrNull(1) ?: return null
        val json = JSONObject(String(Base64.decode(pl, Base64.URL_SAFE or Base64.NO_PADDING)))
        val b64  = json.optString("identityPublicKey").takeIf { it.isNotBlank() } ?: return null
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)))
    } catch (e: Exception) { Log.w(TAG, "extractClientPublicKey: ${e.message}"); null }

    /**
     * Convert Java's DER-encoded ECDSA signature to the raw (r‖s) format that
     * Bedrock's JWT handler expects.
     *
     * [pointBytes] is the fixed byte-length for each coordinate (48 for P-384).
     */
    private fun derToRawEcSig(der: ByteArray, pointBytes: Int): ByteArray {
        var pos = 2 // skip SEQUENCE tag (0x30) and total length
        val rLen = der[pos + 1].toInt() and 0xFF; pos += 2
        val r = der.copyOfRange(pos, pos + rLen); pos += rLen
        val sLen = der[pos + 1].toInt() and 0xFF; pos += 2
        val s = der.copyOfRange(pos, pos + sLen)

        fun pad(b: ByteArray): ByteArray {
            val out = ByteArray(pointBytes)
            val src = if (b.size > pointBytes) b.copyOfRange(b.size - pointBytes, b.size) else b
            src.copyInto(out, pointBytes - src.size)
            return out
        }
        return pad(r) + pad(s)
    }

    private fun sha256Hex(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun b64url(s: String) = Base64.encodeToString(
        s.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun b64url(b: ByteArray) = Base64.encodeToString(
        b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // ─────────────────────────────────────────────────────────────────────────
    // Buffer / varint / compression helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** LE uint16-prefixed UTF-8 string (Bedrock pack entry format). */
    private fun ByteBuffer.leString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8); putShort(bytes.size.toShort()); put(bytes)
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
        do {
            var b = n and 0x7F; n = n ushr 7
            if (n != 0) b = b or 0x80
            out.add(b.toByte())
        } while (n != 0)
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
