package com.freeconnect.bedrock.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BedrockProxy"

/**
 * UDP proxy that makes a remote Minecraft Bedrock server reachable as a
 * local LAN endpoint.
 *
 * Key fixes vs naïve blind-forward approach:
 *  1. Intercepts RakNet ID_UNCONNECTED_PING / PING_OPEN on 19133 and
 *     responds locally with the same serverId/GUID used in the LAN
 *     advertisement.  Forwarding pings to the real server returns the
 *     server's own GUID, which mismatches the LAN discovery GUID and
 *     causes Minecraft to show "Your internet is experiencing…".
 *  2. Uses ConcurrentHashMap.computeIfAbsent (atomic) instead of
 *     getOrPut (not atomic) to prevent duplicate sessions under burst traffic.
 *  3. BUFFER_SIZE bumped to 65535 — max UDP datagram, avoids truncation of
 *     large RakNet fragments during map downloads.
 */
@Singleton
class BedrockProxy @Inject constructor() {

    companion object {
        /** Local port Minecraft connects to after seeing the LAN advertisement. */
        const val PROXY_PORT = 19133

        private const val BUFFER_SIZE       = 65535
        private const val SOCKET_TIMEOUT_MS = 500
        private const val SESSION_TIMEOUT_MS = 30_000L

        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )
        private const val ID_UNCONNECTED_PING: Byte      = 0x01
        private const val ID_UNCONNECTED_PING_OPEN: Byte = 0x02
        private const val MIN_PING_SIZE                  = 25
        private const val PROTOCOL_VERSION               = 975
        private const val GAME_VERSION                   = "1.26.30"
    }

    private data class Session(
        val outSocket: DatagramSocket,
        @Volatile var lastActivity: Long = System.currentTimeMillis()
    )

    private fun buildPongPacket(serverName: String, serverId: Long, pingTime: Long): ByteArray {
        val timeBytes = ByteArray(8) { i -> ((pingTime shr ((7 - i) * 8)) and 0xFF).toByte() }
        val guidBytes = ByteArray(8) { i -> ((serverId shr ((7 - i) * 8)) and 0xFF).toByte() }
        val motd      = "MCPE;${serverName};${PROTOCOL_VERSION};${GAME_VERSION};0;20;${serverId};FreeConnect;Survival;1;${PROXY_PORT};${PROXY_PORT};"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)
        val lenBytes  = byteArrayOf(((motdBytes.size shr 8) and 0xFF).toByte(), (motdBytes.size and 0xFF).toByte())
        return byteArrayOf(0x1C) + timeBytes + RAKNET_MAGIC + guidBytes + lenBytes + motdBytes
    }

    private fun extractPingTime(buf: ByteArray): Long {
        var t = 0L; for (i in 0..7) t = (t shl 8) or (buf[1 + i].toLong() and 0xFF); return t
    }

    /**
     * Start the proxy, forwarding traffic between local Minecraft clients and
     * the remote Bedrock server.
     *
     * @param remoteIp    IP or hostname of the real Bedrock server.
     * @param remotePort  Port of the real Bedrock server.
     * @param serverName  Display name — used in ping responses to match LAN advertisement.
     * @param serverId    GUID — MUST match the value used in [LanBroadcaster] so pings
     *                    on this port return the same identity Minecraft saw during discovery.
     * @param onError     Called on unrecoverable startup failure.
     */
    suspend fun start(
        remoteIp: String,
        remotePort: Int,
        serverName: String = "Minecraft Server",
        serverId: Long = System.currentTimeMillis(),
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val sessions = ConcurrentHashMap<String, Session>()
        var listenSocket: DatagramSocket? = null

        try {
            listenSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PROXY_PORT))
                soTimeout = SOCKET_TIMEOUT_MS
            }
            val remoteAddress = InetAddress.getByName(remoteIp)
            Log.i(TAG, "Proxy started on :$PROXY_PORT → $remoteIp:$remotePort  guid=$serverId")

            val buffer     = ByteArray(BUFFER_SIZE)
            val recvPacket = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    recvPacket.length = buffer.size
                    listenSocket.receive(recvPacket)

                    val clientAddr = recvPacket.address
                    val clientPort = recvPacket.port
                    val clientKey  = "$clientAddr:$clientPort"
                    val len        = recvPacket.length
                    val id         = buffer[0]

                    // ── Ping interception ────────────────────────────────────────────────
                    // Reply locally so Minecraft sees the same GUID as the LAN advertisement.
                    // If we forwarded to the real server its GUID would differ → "internet" error.
                    if ((id == ID_UNCONNECTED_PING || id == ID_UNCONNECTED_PING_OPEN) && len >= MIN_PING_SIZE) {
                        val pong = buildPongPacket(serverName, serverId, extractPingTime(buffer))
                        listenSocket?.send(DatagramPacket(pong, pong.size, clientAddr, clientPort))
                        Log.d(TAG, "Pong → ${clientAddr.hostAddress}:$clientPort")
                        continue
                    }

                    val payload = buffer.copyOf(len)

                    // ── Atomic session creation ──────────────────────────────────────────
                    // computeIfAbsent is atomic; getOrPut on ConcurrentHashMap is NOT.
                    var isNew = false
                    val session = sessions.computeIfAbsent(clientKey) {
                        isNew = true
                        Session(DatagramSocket().apply { soTimeout = SOCKET_TIMEOUT_MS })
                    }

                    if (isNew) {
                        launch(Dispatchers.IO) {
                            val respBuf    = ByteArray(BUFFER_SIZE)
                            val respPacket = DatagramPacket(respBuf, respBuf.size)
                            try {
                                while (isActive && sessions.containsKey(clientKey)) {
                                    try {
                                        respPacket.length = respBuf.size
                                        session.outSocket.receive(respPacket)
                                        session.lastActivity = System.currentTimeMillis()
                                        listenSocket?.send(DatagramPacket(
                                            respPacket.data.copyOf(respPacket.length),
                                            respPacket.length, clientAddr, clientPort
                                        ))
                                    } catch (e: SocketTimeoutException) {
                                        if (System.currentTimeMillis() - session.lastActivity > SESSION_TIMEOUT_MS) {
                                            Log.d(TAG, "Session expired: $clientKey")
                                            sessions.remove(clientKey)?.outSocket?.close()
                                            break
                                        }
                                    } catch (e: Exception) {
                                        if (isActive) Log.w(TAG, "Remote→client ($clientKey): ${e.message}")
                                        sessions.remove(clientKey)?.outSocket?.close()
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Relay ended ($clientKey): ${e.message}")
                            }
                        }
                        Log.d(TAG, "New session: $clientKey")
                    }

                    session.lastActivity = System.currentTimeMillis()
                    session.outSocket.send(DatagramPacket(payload, payload.size, remoteAddress, remotePort))

                } catch (e: SocketTimeoutException) {
                    // Expected — lets isActive be checked between receives
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy could not bind on port $PROXY_PORT: ${e.message}", e)
            onError("Proxy could not start on port $PROXY_PORT: ${e.message}")
        } finally {
            listenSocket?.close()
            sessions.values.forEach { runCatching { it.outSocket.close() } }
            sessions.clear()
            Log.i(TAG, "Proxy stopped")
        }
    }
}
