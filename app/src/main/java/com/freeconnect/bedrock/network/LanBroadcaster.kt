package com.freeconnect.bedrock.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BedrockLanServer"

// ─────────────────────────────────────────────────────────────────────────────
// RakNet packet ID constants
// ─────────────────────────────────────────────────────────────────────────────
private const val ID_UNCONNECTED_PING: Byte        = 0x01
private const val ID_UNCONNECTED_PONG: Byte        = 0x1C
private const val ID_OPEN_CONNECTION_REQUEST_1: Byte = 0x05
private const val ID_OPEN_CONNECTION_REPLY_1: Byte   = 0x06
private const val ID_OPEN_CONNECTION_REQUEST_2: Byte = 0x07
private const val ID_OPEN_CONNECTION_REPLY_2: Byte   = 0x08
private const val ID_ACK: Byte                     = 0xC0.toByte()
private const val ID_NAK: Byte                     = 0xA0.toByte()

// Bedrock game-layer IDs
private const val BEDROCK_PACKET_LOGIN: Byte            = 0x01
private const val BEDROCK_PACKET_PLAY_STATUS: Byte      = 0x02
private const val BEDROCK_PACKET_RESOURCE_PACKS_INFO: Byte = 0x06
private const val BEDROCK_PACKET_RESOURCE_PACK_STACK: Byte = 0x07
private const val BEDROCK_PACKET_RESOURCE_PACK_RESPONSE: Byte = 0x08
private const val BEDROCK_PACKET_TRANSFER: Byte         = 0x55

// RakNet reliability types
private const val RELIABILITY_UNRELIABLE      = 0
private const val RELIABILITY_RELIABLE        = 2
private const val RELIABILITY_RELIABLE_ORDERED = 3

/**
 * Bedrock LAN Server — correct implementation.
 *
 * ## How LAN discovery actually works
 * The Minecraft Bedrock client does NOT passively listen for broadcast packets.
 * Instead it sends an **UnconnectedPing** (0x01) to the LAN broadcast address and
 * waits for an **UnconnectedPong** (0x1C) response addressed back to it.
 * The original implementation only sent pong packets into the void which is why
 * the server never appeared in the LAN tab.
 *
 * ## Flow for consoles (Xbox / PS5 / Switch)
 * Consoles cannot enter custom server IPs, so this server handles the full
 * RakNet handshake and then sends a Bedrock **Transfer** packet (0x55) that
 * redirects the console to the real server address stored in the app.
 *
 *   1. Console sends UnconnectedPing → we send UnconnectedPong → server appears
 *   2. Console sends OpenConnectionRequest1 → we reply → OpenConnectionReply1
 *   3. Console sends OpenConnectionRequest2 → we reply → OpenConnectionReply2
 *   4. Console sends ConnectionRequest (inside RakNet frame) → ConnectionRequestAccepted
 *   5. Console sends NewIncomingConnection → we send Transfer → console redirects
 *
 * ## Resource packs via LAN
 * After the Transfer the console connects directly to the real server, so packs
 * must be configured on that server. Injecting packs without a full MITM proxy
 * (which requires Bedrock encryption) is out of scope here.
 *
 * Protocol: 1026 ≈ Bedrock 1.26.30
 * (update PROTOCOL_VERSION when a new release ships — check wiki.vg/Bedrock_Protocol)
 */
@Singleton
class LanBroadcaster @Inject constructor() {

    companion object {
        /** Port Minecraft Bedrock uses for LAN discovery and connection. */
        const val BEDROCK_PORT = 19132

        /**
         * RakNet "offline message ID" — must appear in ping/pong/open-connection
         * packets. Acts as a magic cookie to distinguish RakNet traffic.
         */
        val RAKNET_MAGIC: ByteArray = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        /**
         * Bedrock protocol version for 1.26.30 (estimated).
         * Known: 748 = 1.21.50 | 980 = 1.26.0 est.
         * Update this when a new Minecraft Bedrock release ships.
         */
        private const val PROTOCOL_VERSION = 1026
        private const val GAME_VERSION     = "1.26.30"

        private const val MAX_PACKET = 65536
    }

    // A stable GUID that identifies this fake server across the session.
    private val serverGuid: Long = System.nanoTime()

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start the LAN server. Binds to UDP port 19132 and handles all incoming
     * RakNet packets. Suspends until the coroutine is cancelled.
     *
     * @param serverName Display name shown in the Minecraft LAN tab.
     * @param serverIp   Real server IP to redirect consoles to.
     * @param serverPort Real server port (usually 19132).
     * @param onError    Called with a message on unrecoverable error.
     */
    suspend fun startBroadcasting(
        serverName: String,
        serverIp: String,
        serverPort: Int,
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        // Per-client state (sequence numbers etc.)
        val clients = HashMap<String, ClientState>()

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(BEDROCK_PORT))
                soTimeout = 150
            }
            Log.i(TAG, "LAN server up on :$BEDROCK_PORT — '$serverName' → $serverIp:$serverPort")

            val buf = ByteArray(MAX_PACKET)

            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)

                    val data = pkt.data.copyOf(pkt.length)
                    val from = InetSocketAddress(pkt.address, pkt.port)
                    val key  = "${pkt.address.hostAddress}:${pkt.port}"

                    dispatch(data, from, key, socket, serverName, serverIp, serverPort, clients)
                } catch (_: SocketTimeoutException) { /* normal — no packet this cycle */ }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Fatal: ${e.message}", e)
            onError("LAN server error: ${e.message}")
        } finally {
            socket?.close()
            Log.i(TAG, "LAN server stopped")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet dispatcher
    // ─────────────────────────────────────────────────────────────────────────

    private fun dispatch(
        data: ByteArray,
        from: InetSocketAddress,
        key: String,
        sock: DatagramSocket,
        serverName: String,
        serverIp: String,
        serverPort: Int,
        clients: HashMap<String, ClientState>
    ) {
        if (data.isEmpty()) return

        when (data[0]) {

            // ── LAN discovery ping ───────────────────────────────────────────
            ID_UNCONNECTED_PING -> {
                if (data.size < 17 || !magicAt(data, 9)) return
                val pingTime = data.bigLong(1)
                send(sock, from, pong(pingTime, serverName))
                Log.d(TAG, "Pong → $key")
            }

            // ── RakNet open connection handshake ─────────────────────────────
            ID_OPEN_CONNECTION_REQUEST_1 -> {
                if (!magicAt(data, 1)) return
                // MTU = total UDP payload + 28 bytes overhead (IP+UDP headers)
                val mtu = data.size + 28
                send(sock, from, openReply1(mtu))
                Log.d(TAG, "OpenReply1 → $key, mtu=$mtu")
            }

            ID_OPEN_CONNECTION_REQUEST_2 -> {
                if (!magicAt(data, 1)) return
                // MTU is at offset 1+16 (magic) + serverAddress (7 bytes for IPv4) = 24
                val mtu = if (data.size >= 26) data.bigShort(24).toInt() and 0xFFFF else 1400
                send(sock, from, openReply2(from, mtu))
                clients[key] = ClientState()
                Log.d(TAG, "OpenReply2 → $key, mtu=$mtu")
            }

            // ── ACK / NAK — acknowledge to keep RakNet happy but don't parse ─
            ID_ACK, ID_NAK -> { /* no-op */ }

            else -> {
                // RakNet data datagrams have the IS_VALID bit set (0x80)
                if (data[0].toInt() and 0x80 != 0 && data.size > 4) {
                    val state = clients.getOrPut(key) { ClientState() }
                    handleDataDatagram(data, from, sock, serverIp, serverPort, state, key)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RakNet data datagram handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse RakNet reliability frames out of a data datagram and act on
     * the inner Bedrock packet IDs.
     *
     * Datagram layout:
     *   [1 byte] flags (0x80–0x8F for data)
     *   [3 bytes LE] sequence number
     *   [frames…]
     *
     * Each frame:
     *   [1 byte]  reliability flags: (reliability << 5) | (hasSplit << 4)
     *   [2 bytes BE] length of frame payload in bits
     *   [3 bytes LE] reliable message index (if reliability ≥ 2)
     *   [3 bytes LE] order index (if reliability == 1 | 3 | 4)
     *   [1 byte]     order channel (if reliability == 1 | 3 | 4)
     *   [10 bytes]   split info: count(4 BE)+id(2 BE)+index(4 BE) (if hasSplit)
     *   [payload]
     */
    private fun handleDataDatagram(
        data: ByteArray,
        from: InetSocketAddress,
        sock: DatagramSocket,
        serverIp: String,
        serverPort: Int,
        state: ClientState,
        key: String
    ) {
        // Send ACK for this datagram's sequence number so the client is happy
        val seqNum = data.tripleLE(1)
        send(sock, from, buildAck(seqNum))

        var pos = 4 // skip flags(1) + seqnum(3)

        while (pos < data.size) {
            if (pos + 3 > data.size) break

            val relByte    = data[pos].toInt() and 0xFF
            val reliability = relByte ushr 5
            val hasSplit    = (relByte ushr 4) and 1 == 1
            pos += 1

            val lengthBits  = data.bigShort(pos).toInt() and 0xFFFF
            val lengthBytes = (lengthBits + 7) / 8
            pos += 2

            // Skip per-reliability extra header bytes
            if (reliability == RELIABILITY_RELIABLE || reliability == RELIABILITY_RELIABLE_ORDERED) {
                pos += 3 // reliable message index
            }
            if (reliability == RELIABILITY_RELIABLE_ORDERED) {
                pos += 3 + 1 // order index + channel
            }
            if (hasSplit) pos += 10 // split count(4) + split id(2) + split index(4)

            if (pos + lengthBytes > data.size || lengthBytes <= 0) break
            val payload = data.copyOfRange(pos, pos + lengthBytes)
            pos += lengthBytes

            if (payload.isEmpty()) continue

            when (payload[0]) {

                // ── ConnectionRequest (0x09) ─────────────────────────────────
                0x09.toByte() -> {
                    val requestTime = if (payload.size >= 9) payload.bigLong(1) else 0L
                    val accepted    = connectionRequestAccepted(from, requestTime, state)
                    send(sock, from, accepted)
                    Log.d(TAG, "ConnectionRequestAccepted → $key")
                }

                // ── NewIncomingConnection (0x13) ─────────────────────────────
                // Client is now fully connected — send Transfer to redirect
                0x13.toByte() -> {
                    val transfer = transferPacket(serverIp, serverPort, state)
                    send(sock, from, transfer)
                    Log.i(TAG, "Transfer sent → $key redirecting to $serverIp:$serverPort")
                }

                // ── ConnectedPing (0x00) — keep-alive reply ──────────────────
                0x00.toByte() -> {
                    val pingTime   = if (payload.size >= 9) payload.bigLong(1) else 0L
                    val pongPayload = ByteArray(17).also { b ->
                        b[0] = 0x03 // ConnectedPong
                        putLong(b, 1, pingTime)
                        putLong(b, 9, System.currentTimeMillis())
                    }
                    send(sock, from, wrapReliable(pongPayload, state))
                }

                // ── Bedrock batch packet (0xFE) ──────────────────────────────
                // Sent when the client starts its Bedrock Login flow.
                // We respond immediately with Transfer (before encryption).
                0xFE.toByte() -> {
                    val transfer = transferPacket(serverIp, serverPort, state)
                    send(sock, from, transfer)
                    Log.i(TAG, "Batch received — Transfer sent → $key to $serverIp:$serverPort")
                }

                // ── Disconnect (0x15) ────────────────────────────────────────
                0x15.toByte() -> Log.d(TAG, "Client disconnected: $key")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet builders
    // ─────────────────────────────────────────────────────────────────────────

    /** UnconnectedPong (0x1C) — response to a LAN discovery ping. */
    private fun pong(pingTime: Long, serverName: String): ByteArray {
        val motd = "MCPE;$serverName;$PROTOCOL_VERSION;$GAME_VERSION;0;20;$serverGuid;FreeConnect;Survival;1;$BEDROCK_PORT;$BEDROCK_PORT;"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 8 + 8 + 16 + 2 + motdBytes.size).apply {
            put(ID_UNCONNECTED_PONG)
            putLong(pingTime)
            putLong(serverGuid)
            put(RAKNET_MAGIC)
            putShort(motdBytes.size.toShort())
            put(motdBytes)
        }.array()
    }

    /** OpenConnectionReply1 (0x06) */
    private fun openReply1(mtu: Int): ByteArray =
        ByteBuffer.allocate(1 + 16 + 8 + 1 + 2).apply {
            put(ID_OPEN_CONNECTION_REPLY_1)
            put(RAKNET_MAGIC)
            putLong(serverGuid)
            put(0x00)              // no security
            putShort(mtu.toShort())
        }.array()

    /** OpenConnectionReply2 (0x08) */
    private fun openReply2(client: InetSocketAddress, mtu: Int): ByteArray =
        ByteBuffer.allocate(1 + 16 + 8 + 7 + 2 + 1).apply {
            put(ID_OPEN_CONNECTION_REPLY_2)
            put(RAKNET_MAGIC)
            putLong(serverGuid)
            putRakNetAddress(client)
            putShort(mtu.toShort())
            put(0x00)              // no encryption
        }.array()

    /**
     * ConnectionRequestAccepted (0x10) wrapped in a RakNet reliable frame.
     *
     * Payload layout:
     *   [1]  packet ID 0x10
     *   [7]  client IPv4 address
     *   [2]  system index (0)
     *   [70] 10 × placeholder system addresses
     *   [8]  ping request time
     *   [8]  current time
     */
    private fun connectionRequestAccepted(
        client: InetSocketAddress,
        requestTime: Long,
        state: ClientState
    ): ByteArray {
        val payload = ByteBuffer.allocate(1 + 7 + 2 + 70 + 8 + 8).apply {
            put(0x10)
            putRakNetAddress(client)
            putShort(0)
            repeat(10) { putRakNetAddress(InetSocketAddress("0.0.0.0", 0)) }
            putLong(requestTime)
            putLong(System.currentTimeMillis())
        }.rawBytes()
        return wrapReliable(payload, state)
    }

    /**
     * Build a Bedrock Transfer packet wrapped in an unencrypted batch,
     * then wrapped in a RakNet reliable datagram.
     *
     * Bedrock Batch (0xFE):
     *   [1 byte]  0xFE
     *   [zlib of: varint(length) | 0x55 | LE-short(ip len) | ip bytes | LE-short(port)]
     */
    private fun transferPacket(ip: String, port: Int, state: ClientState): ByteArray {
        val ipBytes = ip.toByteArray(Charsets.UTF_8)

        // Build raw game packet bytes for Transfer (0x55)
        val gamePacket = ByteBuffer.allocate(1 + 2 + ipBytes.size + 2).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(BEDROCK_PACKET_TRANSFER)
            putShort(ipBytes.size.toShort())
            put(ipBytes)
            putShort(port.toShort())
        }.rawBytes()

        // Prefix with varint length (Bedrock batch inner format)
        val lenVarint = encodeUnsignedVarInt(gamePacket.size)
        val batchInner = lenVarint + gamePacket

        // Compress with zlib (deflate, level 6)
        val compressed = zlibDeflate(batchInner)

        // Build batch outer: 0xFE + compressed
        val batchOuter = ByteArray(1 + compressed.size)
        batchOuter[0] = 0xFE.toByte()
        compressed.copyInto(batchOuter, 1)

        return wrapReliable(batchOuter, state)
    }

    /** ACK packet for a given sequence number. */
    private fun buildAck(seqNum: Int): ByteArray =
        ByteBuffer.allocate(6).apply {
            put(ID_ACK)
            putShort(1)            // record count = 1
            put(0x01)              // is single
            put((seqNum and 0xFF).toByte())
            put(((seqNum shr 8) and 0xFF).toByte())
            put(((seqNum shr 16) and 0xFF).toByte())
        }.array()

    // ─────────────────────────────────────────────────────────────────────────
    // RakNet framing helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wrap [payload] in a RakNet RELIABLE_ORDERED data datagram.
     *
     * Datagram: [0x84][seqNum 3 LE][frame…]
     * Frame:    [reliability byte][length 2 BE][reliableIdx 3 LE][orderIdx 3 LE][channel 1][payload]
     */
    private fun wrapReliable(payload: ByteArray, state: ClientState): ByteArray {
        val reliableIdx = state.reliableIdx++
        val orderIdx    = state.orderIdx++
        val seqNum      = state.seqNum++

        val frameFlagsByte = (RELIABILITY_RELIABLE_ORDERED shl 5).toByte() // 0x60
        val lengthBits     = (payload.size * 8).toShort()

        val frame = ByteBuffer.allocate(1 + 2 + 3 + 3 + 1 + payload.size).apply {
            put(frameFlagsByte)
            putShort(lengthBits)
            put3LE(reliableIdx)
            put3LE(orderIdx)
            put(0x00)              // order channel 0
            put(payload)
        }.rawBytes()

        return ByteBuffer.allocate(1 + 3 + frame.size).apply {
            put(0x84.toByte())     // IS_VALID | IS_CONTINUOUS_SEND
            put3LE(seqNum)
            put(frame)
        }.rawBytes()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Byte / buffer helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Write a RakNet IPv4 address: [0x04][~ip bytes][port 2 BE]. */
    private fun ByteBuffer.putRakNetAddress(addr: InetSocketAddress) {
        put(0x04)
        val ip = addr.address?.address ?: byteArrayOf(0, 0, 0, 0)
        if (ip.size == 4) ip.forEach { put((it.toInt() xor 0xFF).toByte()) }
        else repeat(4) { put(0x00) }
        putShort(addr.port.toShort())
    }

    private fun ByteBuffer.put3LE(v: Int) {
        put((v and 0xFF).toByte())
        put(((v shr 8) and 0xFF).toByte())
        put(((v shr 16) and 0xFF).toByte())
    }

    /** Return a copy of the buffer's content up to the current position. */
    private fun ByteBuffer.rawBytes(): ByteArray = array().copyOf(position())

    /** Check whether [RAKNET_MAGIC] appears at [offset] in [data]. */
    private fun magicAt(data: ByteArray, offset: Int): Boolean {
        if (data.size < offset + 16) return false
        return RAKNET_MAGIC.indices.all { data[offset + it] == RAKNET_MAGIC[it] }
    }

    /** Read 8 bytes big-endian as Long starting at [offset]. */
    private fun ByteArray.bigLong(offset: Int) =
        ByteBuffer.wrap(this, offset, 8).order(ByteOrder.BIG_ENDIAN).long

    /** Read 2 bytes big-endian as Short starting at [offset]. */
    private fun ByteArray.bigShort(offset: Int) =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.BIG_ENDIAN).short

    /** Read 3 bytes little-endian as Int starting at [offset]. */
    private fun ByteArray.tripleLE(offset: Int) =
        (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

    private fun putLong(buf: ByteArray, offset: Int, v: Long) {
        for (i in 0..7) buf[offset + i] = ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
    }

    /** Encode an unsigned varint (LEB128). Used in Bedrock batch packets. */
    private fun encodeUnsignedVarInt(value: Int): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0)
        return out.toByteArray()
    }

    /** Deflate (zlib) compress [data] at level 6. */
    private fun zlibDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(6)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(tmp)
            out.write(tmp, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Send a UDP datagram and swallow any IO error (logged only). */
    private fun send(sock: DatagramSocket, to: InetSocketAddress, data: ByteArray) {
        try {
            sock.send(DatagramPacket(data, data.size, to.address, to.port))
        } catch (e: Exception) {
            Log.w(TAG, "send error to $to: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-client state
    // ─────────────────────────────────────────────────────────────────────────

    /** Tracks outgoing sequence numbers for one connected client. */
    private data class ClientState(
        var seqNum:      Int = 0,
        var reliableIdx: Int = 0,
        var orderIdx:    Int = 0
    )
}
