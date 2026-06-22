package com.freeconnect.bedrock.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LanBroadcaster"

/**
 * Minecraft Bedrock LAN advertisement broadcaster.
 *
 * Bedrock Edition LAN discovery uses the RakNet protocol:
 *   1. The Minecraft client broadcasts an ID_UNCONNECTED_PING (0x01) packet
 *      to 255.255.255.255:19132.
 *   2. Any server listening on port 19132 replies directly to the client's
 *      address with an ID_UNCONNECTED_PONG (0x1C) packet containing the MOTD.
 *   3. The client shows the server in its LAN games list and connects to the
 *      port advertised in the MOTD — which is [BedrockProxy.PROXY_PORT].
 *
 * The pong MOTD advertises [BedrockProxy.PROXY_PORT] so that when Minecraft
 * taps "Connect", it connects to the local [BedrockProxy] rather than
 * directly to the remote server (which would fail on most internet servers).
 *
 * References:
 *   - wiki.vg/Bedrock_Protocol
 *   - RakNet Unconnected Ping/Pong specification
 */
@Singleton
class LanBroadcaster @Inject constructor() {

    companion object {
        /** Bedrock Edition default discovery port — we listen here for pings. */
        const val BEDROCK_PORT = 19132

        /** RakNet offline message ID bytes */
        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        /** Bedrock protocol version (1.26.x) */
        private const val PROTOCOL_VERSION = 975
        private const val GAME_VERSION = "1.26.20"

        /** RakNet packet IDs */
        private const val ID_UNCONNECTED_PING: Byte      = 0x01
        private const val ID_UNCONNECTED_PING_OPEN: Byte = 0x02

        /** Minimum valid ping packet size: 1 (id) + 8 (time) + 16 (magic) = 25 */
        private const val MIN_PING_SIZE = 25

        private const val BUFFER_SIZE       = 2048
        private const val SOCKET_TIMEOUT_MS = 500
    }

    /**
     * Build an ID_UNCONNECTED_PONG (0x1C) response.
     *
     * @param serverName  Display name shown in the LAN tab.
     * @param serverId    Unique 64-bit server GUID for this session.
     * @param pingTime    The ping_time echoed from the client's ping — Minecraft
     *                    validates this and drops the pong if it doesn't match.
     * @param connectPort The local port Minecraft should connect to (the proxy port).
     */
    private fun buildPongPacket(
        serverName: String,
        serverId: Long,
        pingTime: Long,
        connectPort: Int
    ): ByteArray {
        val packetId   = byteArrayOf(0x1C)
        val timeBytes  = ByteArray(8) { i -> ((pingTime  shr ((7 - i) * 8)) and 0xFF).toByte() }
        val guidBytes  = ByteArray(8) { i -> ((serverId  shr ((7 - i) * 8)) and 0xFF).toByte() }

        val motd = "MCPE;$serverName;$PROTOCOL_VERSION;$GAME_VERSION;0;20;$serverId;FreeConnect;Survival;1;$connectPort;$connectPort;"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)

        val lengthBytes = byteArrayOf(
            ((motdBytes.size shr 8) and 0xFF).toByte(),
            (motdBytes.size and 0xFF).toByte()
        )

        return packetId + timeBytes + RAKNET_MAGIC + guidBytes + lengthBytes + motdBytes
    }

    /** Extract the 8-byte ping_time field starting at byte index 1. */
    private fun extractPingTime(buf: ByteArray): Long {
        var t = 0L
        for (i in 0..7) t = (t shl 8) or (buf[1 + i].toLong() and 0xFF)
        return t
    }

    /**
     * Listen on port [BEDROCK_PORT] for Bedrock LAN pings and respond with pongs
     * that advertise [connectPort] as the connection endpoint.
     *
     * Suspends until the coroutine is cancelled.
     *
     * @param serverName   Display name of the server shown in the LAN tab.
     * @param connectPort  Port to advertise in the MOTD (should be [BedrockProxy.PROXY_PORT]).
     * @param onError      Called with a message on unrecoverable socket error.
     */
    suspend fun startBroadcasting(
        serverName: String,
        connectPort: Int = BedrockProxy.PROXY_PORT,
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val serverId = System.currentTimeMillis()
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(BEDROCK_PORT))
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
            }

            Log.i(TAG, "Ping listener started on :$BEDROCK_PORT → advertising connect port $connectPort as '$serverName'")

            val buffer     = ByteArray(BUFFER_SIZE)
            val recvPacket = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    recvPacket.length = buffer.size
                    socket.receive(recvPacket)

                    val id = buffer[0]
                    if ((id == ID_UNCONNECTED_PING || id == ID_UNCONNECTED_PING_OPEN)
                        && recvPacket.length >= MIN_PING_SIZE
                    ) {
                        val pingTime = extractPingTime(buffer)
                        val pong     = buildPongPacket(serverName, serverId, pingTime, connectPort)
                        socket.send(DatagramPacket(pong, pong.size, recvPacket.address, recvPacket.port))
                        Log.d(TAG, "Pong → ${recvPacket.address}:${recvPacket.port}")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Expected — lets us check isActive
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "Error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not bind on port $BEDROCK_PORT: ${e.message}", e)
            onError("Could not start LAN listener: ${e.message}")
        } finally {
            socket?.close()
            Log.i(TAG, "Ping listener stopped for '$serverName'")
        }
    }
}
