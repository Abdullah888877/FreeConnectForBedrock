package com.freeconnect.bedrock.network

import android.util.Log
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import com.freeconnect.bedrock.data.resourcepack.ResourcePackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BedrockLanServer"

// ── RakNet offline packet IDs ────────────────────────────────────────────────
private const val ID_UNCONNECTED_PING: Byte          = 0x01
private const val ID_UNCONNECTED_PONG: Byte          = 0x1C
private const val ID_OPEN_CONNECTION_REQUEST_1: Byte = 0x05
private const val ID_OPEN_CONNECTION_REPLY_1: Byte   = 0x06
private const val ID_OPEN_CONNECTION_REQUEST_2: Byte = 0x07
private const val ID_OPEN_CONNECTION_REPLY_2: Byte   = 0x08
private const val ID_ACK: Byte                       = 0xC0.toByte()
private const val ID_NAK: Byte                       = 0xA0.toByte()

// ── RakNet reliability types ─────────────────────────────────────────────────
private const val RELIABILITY_RELIABLE         = 2
private const val RELIABILITY_RELIABLE_ORDERED = 3

/**
 * Bedrock LAN server — acts as a real (minimal) Bedrock server.
 *
 * ## How it works for consoles (Xbox / PS5 / Switch)
 * Consoles cannot type custom server IPs. They discover servers only through
 * the LAN tab. This class:
 *
 *   1. Listens on UDP port 19132. When the console sends an UnconnectedPing it
 *      replies directly (old code only broadcast blindly — that's why nothing
 *      showed up in the LAN tab).
 *   2. Completes the full RakNet handshake so the console can "connect".
 *   3. Hands each connected client to a [BedrockSession] which:
 *        • Performs ECDH encryption (Bedrock 1.20+ requires it).
 *        • Serves locally stored resource packs over the session so the
 *          console downloads and caches them.
 *        • Sends a Bedrock Transfer packet (0x55) to redirect the console to
 *          the real server address. Because the packs are already cached,
 *          they apply immediately on the real server.
 *
 * ## Protocol version
 * PROTOCOL_VERSION / GAME_VERSION control what is shown in the LAN tab.
 * Update them when a new Bedrock release ships.
 * Reference: wiki.vg/Bedrock_Protocol
 */
@Singleton
class LanBroadcaster @Inject constructor(
    private val packRepository: ResourcePackRepository
) {

    companion object {
        /** Bedrock default port. */
        const val BEDROCK_PORT = 19132

        /**
         * RakNet "offline message ID" — magic cookie that appears in all
         * pre-connection packets (ping, pong, open-connection).
         */
        val RAKNET_MAGIC: ByteArray = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        /**
         * Bedrock protocol number for 1.26.30 (estimated).
         * Known anchors: 748 = 1.21.50.
         * Update when the exact number for 1.26.30 is confirmed on wiki.vg.
         */
        private const val PROTOCOL_VERSION = 1026
        private const val GAME_VERSION     = "1.26.30"

        private const val MAX_PACKET = 65536
    }

    /** Stable server GUID for this session. */
    private val serverGuid = System.nanoTime()

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start the LAN server. Binds UDP port 19132 and handles all RakNet and
     * Bedrock traffic until the coroutine is cancelled.
     *
     * @param serverName Display name in the Minecraft LAN tab.
     * @param serverIp   Real server to redirect consoles to after pack download.
     * @param serverPort Real server port (usually 19132).
     * @param onError    Called with a message on unrecoverable errors.
     */
    suspend fun startBroadcasting(
        serverName: String,
        serverIp: String,
        serverPort: Int,
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        // Load the currently enabled packs once at session start.
        // Each console that connects will get a BedrockSession with this list.
        val enabledPacks: List<LocalResourcePack> =
            try { packRepository.enabledPacks.first() }
            catch (e: Exception) { emptyList() }

        Log.i(TAG, "Loaded ${enabledPacks.size} enabled pack(s) for LAN serving")

        var socket: DatagramSocket? = null
        val clients = HashMap<String, ClientState>()

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(BEDROCK_PORT))
                soTimeout = 150
            }
            Log.i(TAG, "LAN server up on :$BEDROCK_PORT '$serverName' → $serverIp:$serverPort")

            val buf = ByteArray(MAX_PACKET)

            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)

                    val data = pkt.data.copyOf(pkt.length)
                    val from = InetSocketAddress(pkt.address, pkt.port)
                    val key  = "${pkt.address.hostAddress}:${pkt.port}"

                    dispatch(data, from, key, socket,
                        serverName, serverIp, serverPort,
                        enabledPacks, clients)

                } catch (_: SocketTimeoutException) { /* normal */ }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Fatal socket error: ${e.message}", e)
            onError("LAN server error: ${e.message}")
        } finally {
            socket?.close()
            Log.i(TAG, "LAN server stopped")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RakNet packet dispatcher
    // ─────────────────────────────────────────────────────────────────────────

    private fun dispatch(
        data: ByteArray,
        from: InetSocketAddress,
        key:  String,
        sock: DatagramSocket,
        serverName: String,
        serverIp:   String,
        serverPort: Int,
        enabledPacks: List<LocalResourcePack>,
        clients: HashMap<String, ClientState>
    ) {
        if (data.isEmpty()) return

        when (data[0]) {

            // ── LAN discovery — the critical fix ────────────────────────────
            // Minecraft pings and waits for a direct reply; we were only
            // broadcasting blindly which is why nothing showed in the LAN tab.
            ID_UNCONNECTED_PING -> {
                if (data.size < 9) return
                val pingTime = data.bigLong(1)
                send(sock, from, unconnectedPong(pingTime, serverName))
                Log.d(TAG, "Pong → $key")
            }

            // ── RakNet open-connection handshake ─────────────────────────────
            ID_OPEN_CONNECTION_REQUEST_1 -> {
                if (!magicAt(data, 1)) return
                val mtu = data.size + 28 // +28 for IP+UDP overhead
                send(sock, from, openReply1(mtu))
                Log.d(TAG, "OpenReply1 → $key mtu=$mtu")
            }

            ID_OPEN_CONNECTION_REQUEST_2 -> {
                if (!magicAt(data, 1)) return
                // MTU offset: 1(id) + 16(magic) + 7(server addr IPv4) = 24
                val mtu = if (data.size >= 26) data.bigShort(24).toInt() and 0xFFFF else 1400
                send(sock, from, openReply2(from, mtu))

                // Create per-client state and a full Bedrock session
                val state = ClientState()
                state.session = BedrockSession(
                    sendBatch    = { batch -> send(sock, from, wrapReliable(batch, state)) },
                    enabledPacks = enabledPacks,
                    serverIp     = serverIp,
                    serverPort   = serverPort
                )
                clients[key] = state
                Log.d(TAG, "OpenReply2 → $key (session created, packs=${enabledPacks.size})")
            }

            // ── RakNet ACK / NAK — keep the client happy ─────────────────────
            ID_ACK, ID_NAK -> { /* acknowledged, nothing to do */ }

            // ── RakNet data datagrams (IS_VALID bit set) ─────────────────────
            else -> {
                if (data[0].toInt() and 0x80 != 0 && data.size > 4) {
                    val state = clients.getOrPut(key) { ClientState() }
                    handleDataDatagram(data, from, sock, serverIp, serverPort, state, key, clients)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RakNet data datagram handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse RakNet reliability frames from a data datagram and act on the
     * inner Bedrock packet IDs.
     *
     * Datagram layout:
     *   [1 byte] IS_VALID flags
     *   [3 bytes LE] sequence number
     *   [frames …]
     *
     * Frame header (RELIABLE_ORDERED):
     *   [1 byte] (reliability << 5) | (hasSplit << 4)
     *   [2 bytes BE] payload length in bits
     *   [3 bytes LE] reliable message index
     *   [3 bytes LE] order index
     *   [1 byte] order channel
     *   [opt 10 bytes] split info
     *   [payload]
     */
    private fun handleDataDatagram(
        data:       ByteArray,
        from:       InetSocketAddress,
        sock:       DatagramSocket,
        serverIp:   String,
        serverPort: Int,
        state:      ClientState,
        key:        String,
        clients:    HashMap<String, ClientState>
    ) {
        // ACK the incoming sequence number
        val seqNum = data.tripleLE(1)
        send(sock, from, buildAck(seqNum))

        var pos = 4 // skip flags(1) + seqnum(3)

        while (pos < data.size) {
            if (pos + 3 > data.size) break

            val relByte     = data[pos].toInt() and 0xFF
            val reliability = relByte ushr 5
            val hasSplit    = (relByte ushr 4) and 1 == 1
            pos += 1

            if (pos + 2 > data.size) break
            val lengthBits  = data.bigShort(pos).toInt() and 0xFFFF
            val lengthBytes = (lengthBits + 7) / 8
            pos += 2

            // Skip reliability-specific extra header bytes
            if (reliability == RELIABILITY_RELIABLE ||
                reliability == RELIABILITY_RELIABLE_ORDERED) {
                pos += 3 // reliable message index
            }
            if (reliability == RELIABILITY_RELIABLE_ORDERED) {
                pos += 4 // order index (3) + channel (1)
            }
            if (hasSplit) pos += 10 // count(4) + id(2) + index(4)

            if (pos + lengthBytes > data.size || lengthBytes <= 0) break
            val payload = data.copyOfRange(pos, pos + lengthBytes)
            pos += lengthBytes
            if (payload.isEmpty()) continue

            onFrame(payload, from, sock, serverIp, serverPort, state, key, clients)
        }
    }

    private fun onFrame(
        payload:   ByteArray,
        from:      InetSocketAddress,
        sock:      DatagramSocket,
        serverIp:  String,
        serverPort:Int,
        state:     ClientState,
        key:       String,
        clients:   HashMap<String, ClientState>
    ) {
        when (payload[0]) {

            // ── ConnectionRequest (0x09) ─────────────────────────────────────
            0x09.toByte() -> {
                val requestTime = if (payload.size >= 9) payload.bigLong(1) else 0L
                send(sock, from, connectionRequestAccepted(from, requestTime, state))
                Log.d(TAG, "ConnectionRequestAccepted → $key")
            }

            // ── NewIncomingConnection (0x13) ─────────────────────────────────
            // RakNet session is fully open. The BedrockSession now drives
            // everything: Login → encryption → packs → Transfer.
            0x13.toByte() -> {
                Log.d(TAG, "NewIncomingConnection from $key — Bedrock session active")
                // Nothing to send here; we wait for the client's Login batch.
            }

            // ── Bedrock batch packet (0xFE) ──────────────────────────────────
            // Route to the BedrockSession which handles Login, encryption,
            // resource pack exchange, and finally the Transfer.
            0xFE.toByte() -> {
                state.session?.handleBatch(payload) ?: run {
                    // Session not yet created (e.g. connection raced) — just
                    // create a minimal session and handle the batch now.
                    val session = BedrockSession(
                        sendBatch    = { batch -> send(sock, from, wrapReliable(batch, state)) },
                        enabledPacks = emptyList(),
                        serverIp     = serverIp,
                        serverPort   = serverPort
                    )
                    state.session = session
                    clients[key] = state
                    session.handleBatch(payload)
                }
            }

            // ── ConnectedPing (0x00) ─────────────────────────────────────────
            0x00.toByte() -> {
                if (payload.size < 9) return
                val pingTime = payload.bigLong(1)
                val pong = ByteArray(17).also { b ->
                    b[0] = 0x03
                    putLong(b, 1, pingTime)
                    putLong(b, 9, System.currentTimeMillis())
                }
                send(sock, from, wrapReliable(pong, state))
            }

            // ── Disconnect (0x15) ────────────────────────────────────────────
            0x15.toByte() -> Log.d(TAG, "Client disconnected: $key")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RakNet packet builders
    // ─────────────────────────────────────────────────────────────────────────

    /** UnconnectedPong (0x1C) — sent directly to the pinging client. */
    private fun unconnectedPong(pingTime: Long, serverName: String): ByteArray {
        val motd = "MCPE;$serverName;$PROTOCOL_VERSION;$GAME_VERSION;" +
                   "0;20;$serverGuid;FreeConnect;Survival;1;$BEDROCK_PORT;$BEDROCK_PORT;"
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

    /** OpenConnectionReply1 (0x06). */
    private fun openReply1(mtu: Int): ByteArray =
        ByteBuffer.allocate(1 + 16 + 8 + 1 + 2).apply {
            put(ID_OPEN_CONNECTION_REPLY_1)
            put(RAKNET_MAGIC)
            putLong(serverGuid)
            put(0x00)               // no security
            putShort(mtu.toShort())
        }.array()

    /** OpenConnectionReply2 (0x08). */
    private fun openReply2(client: InetSocketAddress, mtu: Int): ByteArray =
        ByteBuffer.allocate(1 + 16 + 8 + 7 + 2 + 1).apply {
            put(ID_OPEN_CONNECTION_REPLY_2)
            put(RAKNET_MAGIC)
            putLong(serverGuid)
            putRakNetAddr(client)
            putShort(mtu.toShort())
            put(0x00)               // no encryption at RakNet layer
        }.array()

    /**
     * ConnectionRequestAccepted (0x10) wrapped in a RakNet reliable datagram.
     *
     * Payload:
     *   [1]  0x10
     *   [7]  client IPv4 address
     *   [2]  system index (0)
     *   [70] 10 × placeholder addresses
     *   [8]  ping request time
     *   [8]  current time
     */
    private fun connectionRequestAccepted(
        client:      InetSocketAddress,
        requestTime: Long,
        state:       ClientState
    ): ByteArray {
        val payload = ByteBuffer.allocate(1 + 7 + 2 + 70 + 8 + 8).apply {
            put(0x10)
            putRakNetAddr(client)
            putShort(0)
            repeat(10) { putRakNetAddr(InetSocketAddress("0.0.0.0", 0)) }
            putLong(requestTime)
            putLong(System.currentTimeMillis())
        }.rawBytes()
        return wrapReliable(payload, state)
    }

    /** ACK for a given sequence number. */
    private fun buildAck(seqNum: Int): ByteArray =
        ByteBuffer.allocate(7).apply {
            put(ID_ACK)
            putShort(1)                      // record count
            put(0x01)                        // is single record
            put3LE(seqNum)
        }.array()

    // ─────────────────────────────────────────────────────────────────────────
    // RakNet framing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wrap [payload] in a RakNet RELIABLE_ORDERED data datagram.
     *
     * Datagram: [0x84][seqNum 3LE][frame…]
     * Frame:    [0x60][len 2BE][reliableIdx 3LE][orderIdx 3LE][channel][payload]
     */
    fun wrapReliable(payload: ByteArray, state: ClientState): ByteArray {
        val rIdx  = state.reliableIdx++
        val oIdx  = state.orderIdx++
        val seq   = state.seqNum++
        val lenBits = (payload.size * 8).toShort()

        val frame = ByteBuffer.allocate(1 + 2 + 3 + 3 + 1 + payload.size).apply {
            put((RELIABILITY_RELIABLE_ORDERED shl 5).toByte()) // 0x60
            putShort(lenBits)
            put3LE(rIdx)
            put3LE(oIdx)
            put(0x00) // channel 0
            put(payload)
        }.rawBytes()

        return ByteBuffer.allocate(1 + 3 + frame.size).apply {
            put(0x84.toByte()) // IS_VALID | IS_CONTINUOUS_SEND
            put3LE(seq)
            put(frame)
        }.rawBytes()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Byte / buffer helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** RakNet IPv4 address: [0x04][~ip bytes (XOR 0xFF)][port 2BE]. */
    private fun ByteBuffer.putRakNetAddr(addr: InetSocketAddress) {
        put(0x04)
        val ip = addr.address?.address ?: ByteArray(4)
        if (ip.size == 4) ip.forEach { put((it.toInt() xor 0xFF).toByte()) }
        else repeat(4) { put(0x00) }
        putShort(addr.port.toShort())
    }

    private fun ByteBuffer.put3LE(v: Int) {
        put((v and 0xFF).toByte())
        put(((v shr 8) and 0xFF).toByte())
        put(((v shr 16) and 0xFF).toByte())
    }

    private fun ByteBuffer.rawBytes() = array().copyOf(position())

    private fun magicAt(data: ByteArray, offset: Int): Boolean {
        if (data.size < offset + 16) return false
        return RAKNET_MAGIC.indices.all { data[offset + it] == RAKNET_MAGIC[it] }
    }

    private fun ByteArray.bigLong(offset: Int) =
        ByteBuffer.wrap(this, offset, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun ByteArray.bigShort(offset: Int) =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.BIG_ENDIAN).short

    private fun ByteArray.tripleLE(offset: Int) =
        (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

    private fun putLong(buf: ByteArray, offset: Int, v: Long) {
        for (i in 0..7) buf[offset + i] = ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
    }

    private fun send(sock: DatagramSocket, to: InetSocketAddress, data: ByteArray) {
        try { sock.send(DatagramPacket(data, data.size, to.address, to.port)) }
        catch (e: Exception) { Log.w(TAG, "send error: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-client state
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tracks RakNet sequence numbers and the Bedrock session for one client.
     * All fields are mutated on the single IO-thread receive loop — no sync needed.
     */
    data class ClientState(
        var seqNum:      Int = 0,
        var reliableIdx: Int = 0,
        var orderIdx:    Int = 0,
        var session:     BedrockSession? = null
    )
}
