package com.freeconnect.bedrock.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LanBroadcaster"

/**
 * Minecraft Bedrock LAN advertisement broadcaster — dual-mode.
 *
 * Mode A (ping/pong): listens on 19132 for ID_UNCONNECTED_PING from Minecraft
 *   and replies directly. Used by most modern Bedrock clients.
 *
 * Mode B (proactive broadcast): periodically sends ID_UNCONNECTED_PONG to
 *   255.255.255.255:19132. Catches older clients that don't send pings.
 *
 * Both run concurrently on the same socket so the server always appears in
 * the LAN tab. The MOTD advertises BedrockProxy.PROXY_PORT so Minecraft
 * connects through the local proxy to the real remote server.
 */
@Singleton
class LanBroadcaster @Inject constructor() {

    companion object {
        const val BEDROCK_PORT = 19132

        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        private const val PROTOCOL_VERSION       = 975
        private const val GAME_VERSION           = "1.26.20"
        private const val ID_UNCONNECTED_PING: Byte      = 0x01
        private const val ID_UNCONNECTED_PING_OPEN: Byte = 0x02
        private const val MIN_PING_SIZE          = 25
        private const val BUFFER_SIZE            = 2048
        private const val SOCKET_TIMEOUT_MS      = 500
        private const val BROADCAST_INTERVAL_MS  = 1500L
    }

    private fun buildPongPacket(serverName: String, serverId: Long, pingTime: Long, connectPort: Int): ByteArray {
        val timeBytes = ByteArray(8) { i -> ((pingTime shr ((7 - i) * 8)) and 0xFF).toByte() }
        val guidBytes = ByteArray(8) { i -> ((serverId shr ((7 - i) * 8)) and 0xFF).toByte() }
        val motd = "MCPE;${serverName};${PROTOCOL_VERSION};${GAME_VERSION};0;20;${serverId};FreeConnect;Survival;1;${connectPort};${connectPort};"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)
        val lenBytes  = byteArrayOf(((motdBytes.size shr 8) and 0xFF).toByte(), (motdBytes.size and 0xFF).toByte())
        return byteArrayOf(0x1C) + timeBytes + RAKNET_MAGIC + guidBytes + lenBytes + motdBytes
    }

    private fun extractPingTime(buf: ByteArray): Long {
        var t = 0L; for (i in 0..7) t = (t shl 8) or (buf[1 + i].toLong() and 0xFF); return t
    }

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
                broadcast   = true
                soTimeout   = SOCKET_TIMEOUT_MS
            }
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            Log.i(TAG, "LAN broadcaster started — server='${serverName}' connectPort=${connectPort}")

            coroutineScope {
                // Mode B — proactive broadcast
                launch {
                    while (isActive) {
                        try {
                            val pong = buildPongPacket(serverName, serverId, System.currentTimeMillis(), connectPort)
                            socket?.send(DatagramPacket(pong, pong.size, broadcastAddr, BEDROCK_PORT))
                        } catch (e: Exception) { if (isActive) Log.w(TAG, "Broadcast error: ${e.message}") }
                        delay(BROADCAST_INTERVAL_MS)
                    }
                }

                // Mode A — ping listener / pong responder
                val buf  = ByteArray(BUFFER_SIZE)
                val recv = DatagramPacket(buf, buf.size)
                while (isActive) {
                    try {
                        recv.length = buf.size
                        socket?.receive(recv)
                        val id = buf[0]
                        if ((id == ID_UNCONNECTED_PING || id == ID_UNCONNECTED_PING_OPEN) && recv.length >= MIN_PING_SIZE) {
                            val pong = buildPongPacket(serverName, serverId, extractPingTime(buf), connectPort)
                            socket?.send(DatagramPacket(pong, pong.size, recv.address, recv.port))
                            Log.d(TAG, "Pong → ${recv.address}:${recv.port}")
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // expected
                    } catch (e: Exception) { if (isActive) Log.w(TAG, "Recv error: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not bind :${BEDROCK_PORT}: ${e.message}", e)
            onError("Could not start LAN broadcaster: ${e.message}")
        } finally {
            socket?.close()
            Log.i(TAG, "LAN broadcaster stopped")
        }
    }
}