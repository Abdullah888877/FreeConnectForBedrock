package com.freeconnect.bedrock.network

import android.util.Log
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RakNetServer"

/**
 * Minimal RakNet server that handles the Minecraft Bedrock connection handshake
 * and passes Bedrock application-layer batches to [BedrockSession].
 *
 * Full flow:
 *   1. [LanBroadcaster] (port 19132) advertises THIS server on [SERVER_PORT].
 *   2. Minecraft sends OCR1/OCR2 → we reply with OCReply1/OCReply2.
 *   3. Minecraft sends ConnectionRequest → we reply with ConnectionRequestAccepted.
 *   4. Minecraft sends game packets (0xFE batches) → [BedrockSession] handles
 *      login, encryption, resource-pack negotiation and chunk delivery.
 *   5. [BedrockSession] sends a Transfer (0x55) packet → client connects
 *      DIRECTLY to the real server.  No proxy needed after transfer.
 */
@Singleton
class RakNetServer @Inject constructor() {

    companion object {
        /** Port advertised in the LAN MOTD — matches BedrockProxy.PROXY_PORT. */
        const val SERVER_PORT = 19133

        private const val BUFFER_SIZE        = 65535
        private const val SOCKET_TIMEOUT_MS  = 500
        private const val SESSION_TIMEOUT_MS = 60_000L

        private val MAGIC = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        // Offline packet IDs
        private const val PING       = 0x01.toByte()
        private const val PING_OPEN  = 0x02.toByte()
        private const val PONG       = 0x1C.toByte()
        private const val OCR1       = 0x05.toByte()  // OpenConnectionRequest1
        private const val OCR2       = 0x07.toByte()  // OpenConnectionRequest2
        private const val OCREPLY1   = 0x06.toByte()  // OpenConnectionReply1
        private const val OCREPLY2   = 0x08.toByte()  // OpenConnectionReply2

        // Online packet IDs (inside RakNet datagrams)
        private const val CONN_REQ    = 0x09.toByte()
        private const val CONN_ACCEPT = 0x10.toByte()
        private const val CONN_PING   = 0x00.toByte()
        private const val CONN_PONG   = 0x03.toByte()
        private const val DISCONNECT  = 0x15.toByte()
        private const val NEW_CONN    = 0x13.toByte()
        private const val GAME_PKT    = 0xFE.toByte()  // Bedrock batch
        private const val ACK         = 0xC0.toByte()
        private const val NACK        = 0xA0.toByte()

        // Reliability types (upper 3 bits of encapsulated flags byte)
        private const val RELIABLE         = 2
        private const val RELIABLE_ORDERED = 3

        // 127.0.0.1:0  (dummy address, 7 bytes: ver=4, ip×4, port×2)
        private val DUMMY_ADDR = byteArrayOf(4, 127, 0, 0, 1, 0, 0)
    }

    // ── Per-client session ────────────────────────────────────────────────────

    private inner class RakSession(
        val addr: InetSocketAddress,
        val mtu: Int,
        val clientGuid: Long,
        enabledPacks: List<LocalResourcePack>,
        remoteIp: String,
        remotePort: Int,
        private val socket: DatagramSocket
    ) {
        @Volatile var lastSeen = System.currentTimeMillis()

        // Outbound counters — guarded by sendLock
        private val sendLock = Any()
        private var sendSeq  = 0
        private var relIdx   = 0
        private var ordIdx   = 0
        private var splitId  = 0

        // Inbound ACK queue — guarded by ackLock
        private val ackLock   = Any()
        val pendingAcks = mutableListOf<Int>()

        // Fragment reassembly: splitId → (totalCount, fragmentIndex → data)
        val frags = ConcurrentHashMap<Int, Pair<Int, ConcurrentHashMap<Int, ByteArray>>>()

        // Bedrock application session wired to our reliable-send path
        val bedrock = BedrockSession(
            sendBatch    = { batch -> sendPayload(batch) },
            enabledPacks = enabledPacks,
            serverIp     = remoteIp,
            serverPort   = remotePort
        )

        fun queueAck(seqNum: Int) { synchronized(ackLock) { pendingAcks.add(seqNum) } }

        fun flushAcks() {
            val acks: List<Int>
            synchronized(ackLock) {
                if (pendingAcks.isEmpty()) return
                acks = pendingAcks.toList(); pendingAcks.clear()
            }
            sendRaw(buildAck(acks))
        }

        /** Wrap [payload] in RakNet reliable-ordered framing and send, fragmenting if needed. */
        fun sendPayload(payload: ByteArray) {
            // Conservative max chunk: MTU − IP(20) − UDP(8) − RakNet datagram hdr(4) − enc hdr with split(20)
            val maxChunk = (mtu - 52).coerceAtLeast(512)
            synchronized(sendLock) {
                if (payload.size <= maxChunk) {
                    sendRaw(wrapDatagram(encRelOrdered(payload, relIdx++, ordIdx++)))
                } else {
                    val sid        = splitId++
                    val myOrd      = ordIdx++          // all fragments share one order slot
                    val totalFrags = (payload.size + maxChunk - 1) / maxChunk
                    var offset     = 0
                    var fragIdx    = 0
                    while (offset < payload.size) {
                        val end   = minOf(offset + maxChunk, payload.size)
                        val chunk = payload.copyOfRange(offset, end)
                        val enc   = encRelOrdered(chunk, relIdx++, myOrd,
                                        hasSplit    = true,
                                        splitCount  = totalFrags,
                                        splitId     = sid,
                                        splitIndex  = fragIdx)
                        sendRaw(wrapDatagram(enc))
                        offset = end; fragIdx++
                    }
                }
            }
        }

        fun sendRaw(dgram: ByteArray) {
            try { socket.send(DatagramPacket(dgram, dgram.size, addr.address, addr.port)) }
            catch (e: Exception) { Log.w(TAG, "send: ${e.message}") }
        }

        private fun wrapDatagram(encapsulated: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(4 + encapsulated.size)
            out.write(0x84)                             // DATA_PACKET flags
            write3LE(out, sendSeq++)                    // sequence number (LE)
            out.write(encapsulated)
            return out.toByteArray()
        }

        /** Build a reliable-ordered encapsulated packet. */
        private fun encRelOrdered(
            payload: ByteArray, relIdx: Int, ordIdx: Int,
            hasSplit: Boolean = false, splitCount: Int = 0, splitId: Int = 0, splitIndex: Int = 0
        ): ByteArray {
            val out = ByteArrayOutputStream(1 + 2 + 3 + 3 + 1 + (if (hasSplit) 10 else 0) + payload.size)
            val flags = (RELIABLE_ORDERED shl 5) or (if (hasSplit) 0x10 else 0)
            out.write(flags)
            val lenBits = payload.size * 8
            out.write((lenBits shr 8) and 0xFF)         // length (bits, BE)
            out.write(lenBits and 0xFF)
            write3LE(out, relIdx)                       // reliable index (LE)
            write3LE(out, ordIdx)                       // order index (LE)
            out.write(0)                                // order channel = 0
            if (hasSplit) {
                write4BE(out, splitCount)
                write2BE(out, splitId)
                write4BE(out, splitIndex)
            }
            out.write(payload)
            return out.toByteArray()
        }
    }

    // ── Main server loop ──────────────────────────────────────────────────────

    /**
     * Run the server until the coroutine is cancelled.
     *
     * @param serverId     GUID — must match the value used in [LanBroadcaster].
     * @param serverName   Display name for ping responses.
     * @param enabledPacks Packs to serve before transferring the client.
     * @param remoteIp     Real Bedrock server IP.
     * @param remotePort   Real Bedrock server port.
     * @param onError      Called on unrecoverable bind failure.
     */
    suspend fun start(
        serverId: Long,
        serverName: String,
        enabledPacks: List<LocalResourcePack>,
        remoteIp: String,
        remotePort: Int,
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val sessions = ConcurrentHashMap<String, RakSession>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SERVER_PORT))
                soTimeout = SOCKET_TIMEOUT_MS
            }
            Log.i(TAG, "Started :$SERVER_PORT guid=$serverId packs=${enabledPacks.size} → $remoteIp:$remotePort")

            val buf = ByteArray(BUFFER_SIZE)
            val pkt = DatagramPacket(buf, buf.size)

            while (isActive) {
                try {
                    pkt.length = buf.size
                    socket.receive(pkt)
                    val key  = "${pkt.address.hostAddress}:${pkt.port}"
                    val id   = buf[0]
                    val len  = pkt.length
                    val data = buf.copyOf(len)

                    when (id) {
                        // ── Offline ──────────────────────────────────────────
                        PING, PING_OPEN -> {
                            val pt = if (len >= 9) readLong(data, 1) else System.currentTimeMillis()
                            val p  = buildPong(serverName, serverId, pt)
                            socket.send(DatagramPacket(p, p.size, pkt.address, pkt.port))
                        }
                        OCR1 -> {
                            val mtu   = (len + 28).coerceIn(576, 1500)
                            val reply = buildOCReply1(serverId, mtu)
                            socket.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                        }
                        OCR2 -> {
                            if (len < 34) continue
                            // [0x07][MAGIC 16][server addr][MTU 2 BE][client guid 8 BE]
                            var pos = 1 + 16
                            pos += addressSize(data, pos) // skip server address
                            val mtu        = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
                            val clientGuid = readLong(data, pos)
                            val safeMtu    = mtu.coerceIn(576, 1500)
                            val sa         = InetSocketAddress(pkt.address, pkt.port)
                            val sess       = RakSession(sa, safeMtu, clientGuid, enabledPacks, remoteIp, remotePort, socket!!)
                            sessions[key]  = sess
                            val reply = buildOCReply2(serverId, sa, safeMtu)
                            socket.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                            Log.d(TAG, "New session $key mtu=$safeMtu guid=$clientGuid")
                        }
                        // ── Online ───────────────────────────────────────────
                        ACK, NACK -> { /* ignore for now */ }
                        DISCONNECT -> sessions.remove(key)
                        else -> {
                            val flags = id.toInt() and 0xFF
                            if (flags in 0x80..0x8F) {
                                val sess = sessions[key] ?: continue
                                sess.lastSeen = System.currentTimeMillis()
                                handleDatagram(sess, data)
                                sess.flushAcks()
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    val now = System.currentTimeMillis()
                    sessions.entries.removeIf { (_, s) -> now - s.lastSeen > SESSION_TIMEOUT_MS }
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "recv: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: ${e.message}", e)
            onError("RakNet server failed: ${e.message}")
        } finally {
            socket?.close()
            sessions.clear()
            Log.i(TAG, "Stopped")
        }
    }

    // ── Datagram parsing ──────────────────────────────────────────────────────

    private fun handleDatagram(sess: RakSession, data: ByteArray) {
        var pos = 1 // skip flags byte
        // sequence number (3 bytes LE)
        val seqNum = (data[pos].toInt() and 0xFF) or ((data[pos+1].toInt() and 0xFF) shl 8) or ((data[pos+2].toInt() and 0xFF) shl 16)
        pos += 3
        sess.queueAck(seqNum)

        while (pos < data.size) {
            try {
                val eFlags      = data[pos++].toInt() and 0xFF
                val reliability = (eFlags shr 5) and 0x07
                val hasSplit    = (eFlags and 0x10) != 0
                val lenBits     = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos+1].toInt() and 0xFF); pos += 2
                val lenBytes    = (lenBits + 7) / 8

                // Skip reliability headers
                if (reliability in intArrayOf(RELIABLE, RELIABLE_ORDERED, 4, 6, 7)) pos += 3 // reliableIndex
                if (reliability in intArrayOf(1, 4))                                 pos += 3 // sequenceIndex
                if (reliability in intArrayOf(RELIABLE_ORDERED, 7)) { pos += 3; pos++ }       // orderIndex + channel

                var splitCount = 0; var splitId = 0; var splitIndex = 0
                if (hasSplit) {
                    splitCount = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos+1].toInt() and 0xFF) shl 16) or ((data[pos+2].toInt() and 0xFF) shl 8) or (data[pos+3].toInt() and 0xFF); pos += 4
                    splitId    = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos+1].toInt() and 0xFF); pos += 2
                    splitIndex = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos+1].toInt() and 0xFF) shl 16) or ((data[pos+2].toInt() and 0xFF) shl 8) or (data[pos+3].toInt() and 0xFF); pos += 4
                }

                if (lenBytes <= 0 || pos + lenBytes > data.size) break
                val payload = data.copyOfRange(pos, pos + lenBytes); pos += lenBytes

                if (hasSplit) {
                    val rec = sess.frags.getOrPut(splitId) { Pair(splitCount, ConcurrentHashMap()) }
                    rec.second[splitIndex] = payload
                    if (rec.second.size == splitCount) {
                        sess.frags.remove(splitId)
                        val assembled = ByteArray(rec.second.values.sumOf { it.size })
                        var ap = 0
                        for (i in 0 until splitCount) { val c = rec.second[i] ?: break; c.copyInto(assembled, ap); ap += c.size }
                        dispatch(sess, assembled)
                    }
                } else {
                    dispatch(sess, payload)
                }
            } catch (e: Exception) { Log.d(TAG, "parse: ${e.message}"); break }
        }
    }

    private fun dispatch(sess: RakSession, payload: ByteArray) {
        if (payload.isEmpty()) return
        val id = payload[0]
        Log.d(TAG, "← 0x${(id.toInt() and 0xFF).toString(16).uppercase()} ${payload.size}B")
        when (id) {
            CONN_REQ -> {
                // [0x09][guid 8][pingTime 8][doSecurity 1]
                val pingTime = if (payload.size >= 17) readLong(payload, 9) else System.currentTimeMillis()
                sess.sendPayload(buildConnAccepted(sess.addr, pingTime))
                Log.i(TAG, "ConnectionRequestAccepted → ${sess.addr}")
            }
            CONN_PING -> {
                val t    = if (payload.size >= 9) readLong(payload, 1) else 0L
                val pong = ByteArray(17).also {
                    it[0] = CONN_PONG
                    writeLong(it, 1, t)
                    writeLong(it, 9, System.currentTimeMillis())
                }
                sess.sendPayload(pong)
            }
            NEW_CONN -> Log.i(TAG, "NewIncomingConnection — Bedrock login begins for ${sess.addr}")
            DISCONNECT -> Log.d(TAG, "Disconnect from ${sess.addr}")
            GAME_PKT   -> sess.bedrock.handleBatch(payload)
        }
    }

    // ── Packet builders ───────────────────────────────────────────────────────

    private fun buildPong(name: String, guid: Long, pingTime: Long): ByteArray {
        val out   = ByteArrayOutputStream()
        val motd  = "MCPE;$name;975;1.26.30;0;20;$guid;FreeConnect;Survival;1;$SERVER_PORT;$SERVER_PORT;"
        val motdB = motd.toByteArray(Charsets.UTF_8)
        out.write(PONG.toInt())
        write8BE(out, pingTime)
        out.write(MAGIC)
        write8BE(out, guid)
        write2BE(out, motdB.size)
        out.write(motdB)
        return out.toByteArray()
    }

    private fun buildOCReply1(guid: Long, mtu: Int): ByteArray {
        val out = ByteArrayOutputStream(28)
        out.write(OCREPLY1.toInt())
        out.write(MAGIC)
        write8BE(out, guid)
        out.write(0x00)           // no security
        write2BE(out, mtu)
        return out.toByteArray()
    }

    private fun buildOCReply2(guid: Long, clientAddr: InetSocketAddress, mtu: Int): ByteArray {
        val out = ByteArrayOutputStream(35)
        out.write(OCREPLY2.toInt())
        out.write(MAGIC)
        write8BE(out, guid)
        writeAddress(out, clientAddr)  // 7 bytes
        write2BE(out, mtu)
        out.write(0x00)                // no encryption
        return out.toByteArray()
    }

    private fun buildConnAccepted(clientAddr: InetSocketAddress, pingTime: Long): ByteArray {
        // 1 + 7 + 2 + 10×7 + 8 + 8 = 96 bytes
        val out = ByteArrayOutputStream(96)
        out.write(CONN_ACCEPT.toInt())
        writeAddress(out, clientAddr)         // 7 bytes — client address
        write2BE(out, 0)                      // systemIndex
        repeat(10) { out.write(DUMMY_ADDR) } // 10 dummy system addresses
        write8BE(out, pingTime)
        write8BE(out, System.currentTimeMillis())
        return out.toByteArray()
    }

    private fun buildAck(seqNums: List<Int>): ByteArray {
        val out = ByteArrayOutputStream(3 + seqNums.size * 4)
        out.write(ACK.toInt())
        write2BE(out, seqNums.size)
        for (s in seqNums) { out.write(0x01); write3LE(out, s) }
        return out.toByteArray()
    }

    // ── Byte utilities ────────────────────────────────────────────────────────

    /** Write 3-byte little-endian int. */
    private fun write3LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF)
    }

    /** Write 2-byte big-endian int. */
    private fun write2BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    /** Write 4-byte big-endian int. */
    private fun write4BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    /** Write 8-byte big-endian long. */
    private fun write8BE(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v shr (i * 8)) and 0xFF).toInt())
    }

    /** Read 8-byte big-endian long from byte array. */
    private fun readLong(data: ByteArray, offset: Int): Long {
        var v = 0L; for (i in 0..7) v = (v shl 8) or (data[offset + i].toLong() and 0xFF); return v
    }

    /** Write 8-byte big-endian long into byte array. */
    private fun writeLong(data: ByteArray, offset: Int, v: Long) {
        for (i in 0..7) data[offset + i] = ((v shr ((7 - i) * 8)) and 0xFF).toByte()
    }

    /** Write IPv4 address as [4][ip0..ip3][portBE 2]. */
    private fun writeAddress(out: ByteArrayOutputStream, addr: InetSocketAddress) {
        val ip = (addr.address as? Inet4Address)?.address ?: byteArrayOf(127, 0, 0, 1)
        out.write(4); out.write(ip); write2BE(out, addr.port)
    }

    /** Return the byte length of the address field starting at [pos] in [data]. */
    private fun addressSize(data: ByteArray, pos: Int): Int {
        if (pos >= data.size) return 7
        return when (data[pos].toInt() and 0xFF) {
            4 -> 7   // 1 + 4 + 2
            6 -> 29  // 1 + 2 + 2 + 4 + 16 + 4
            else -> 7
        }
    }
}
