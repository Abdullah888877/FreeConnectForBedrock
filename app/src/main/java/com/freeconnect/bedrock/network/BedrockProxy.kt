package com.freeconnect.bedrock.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
 * How it works:
 *   1. [LanBroadcaster] advertises this proxy's port ([PROXY_PORT]) in the
 *      LAN MOTD so Minecraft thinks the server is on the local network.
 *   2. Minecraft connects to <device-IP>:[PROXY_PORT].
 *   3. This proxy receives each UDP packet from Minecraft, forwards it to
 *      the real remote server, and relays the response back — transparently.
 *
 * Session management:
 *   Each distinct (clientAddress, clientPort) pair gets its own outbound
 *   [DatagramSocket] so packets are correctly demultiplexed. Sessions that
 *   are idle for [SESSION_TIMEOUT_MS] are cleaned up automatically.
 */
@Singleton
class BedrockProxy @Inject constructor() {

    companion object {
        /** Local port Minecraft will connect to after seeing the LAN advertisement. */
        const val PROXY_PORT = 19133

        private const val BUFFER_SIZE = 4096
        private const val SOCKET_TIMEOUT_MS = 500
        private const val SESSION_TIMEOUT_MS = 30_000L
    }

    /** Holds the outbound socket and last-activity timestamp for one client session. */
    private data class Session(
        val outSocket: DatagramSocket,
        @Volatile var lastActivity: Long = System.currentTimeMillis()
    )

    /**
     * Start the proxy, forwarding all traffic between local Minecraft clients and
     * the remote Bedrock server.
     *
     * This suspending function blocks until the coroutine scope is cancelled.
     *
     * @param remoteIp    IP or hostname of the real Bedrock server.
     * @param remotePort  Port of the real Bedrock server.
     * @param onError     Called with a message on unrecoverable startup failure.
     */
    suspend fun start(
        remoteIp: String,
        remotePort: Int,
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

            Log.i(TAG, "Proxy started on :$PROXY_PORT → $remoteIp:$remotePort")

            val buffer = ByteArray(BUFFER_SIZE)
            val recvPacket = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    recvPacket.length = buffer.size
                    listenSocket.receive(recvPacket)

                    val clientAddr = recvPacket.address
                    val clientPort = recvPacket.port
                    val clientKey  = "$clientAddr:$clientPort"
                    val payload    = recvPacket.data.copyOf(recvPacket.length)

                    // Get or create a session for this client
                    val session = sessions.getOrPut(clientKey) {
                        val outSock = DatagramSocket().apply { soTimeout = SOCKET_TIMEOUT_MS }
                        val newSession = Session(outSock)

                        // Coroutine: relay remote → client
                        launch(Dispatchers.IO) {
                            val respBuf    = ByteArray(BUFFER_SIZE)
                            val respPacket = DatagramPacket(respBuf, respBuf.size)
                            try {
                                while (isActive && sessions.containsKey(clientKey)) {
                                    try {
                                        respPacket.length = respBuf.size
                                        newSession.outSocket.receive(respPacket)
                                        newSession.lastActivity = System.currentTimeMillis()

                                        val fwd = DatagramPacket(
                                            respPacket.data.copyOf(respPacket.length),
                                            respPacket.length,
                                            clientAddr,
                                            clientPort
                                        )
                                        listenSocket?.send(fwd)
                                    } catch (e: SocketTimeoutException) {
                                        // Expire idle sessions
                                        if (System.currentTimeMillis() - newSession.lastActivity > SESSION_TIMEOUT_MS) {
                                            Log.d(TAG, "Session expired: $clientKey")
                                            sessions.remove(clientKey)?.outSocket?.close()
                                            break
                                        }
                                    } catch (e: Exception) {
                                        if (isActive) Log.w(TAG, "Remote→client error ($clientKey): ${e.message}")
                                        sessions.remove(clientKey)?.outSocket?.close()
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Session relay ended ($clientKey): ${e.message}")
                            }
                        }

                        Log.d(TAG, "New session: $clientKey")
                        newSession
                    }

                    session.lastActivity = System.currentTimeMillis()

                    // Forward client → remote
                    val fwd = DatagramPacket(payload, payload.size, remoteAddress, remotePort)
                    session.outSocket.send(fwd)

                } catch (e: SocketTimeoutException) {
                    // Expected — lets us check isActive
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
            Log.i(TAG, "Proxy stopped for $remoteIp:$remotePort")
        }
    }
}
