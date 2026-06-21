package com.freeconnect.bedrock.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LanBroadcaster"

/**
 * Minecraft Bedrock LAN advertisement broadcaster.
 *
 * The Bedrock Edition client scans port 19132 on the local network for
 * unconnected ping packets (RakNet protocol). This class sends correctly
 * formatted "Unconnected Pong" style advertisements so that the
 * Minecraft client shows the server in its LAN game list.
 *
 * Packet format (simplified MOTD advertisement):
 *   MCPE;<name>;<protocol>;<version>;<players>;<maxPlayers>;<serverUID>;<subname>;<gamemode>;
 *
 * References:
 *   - wiki.vg/Bedrock_Protocol
 *   - RakNet Unconnected Ping/Pong specification
 */
@Singleton
class LanBroadcaster @Inject constructor() {

    companion object {
        /** Bedrock Edition default port */
        const val BEDROCK_PORT = 19132

        /** RakNet offline message ID bytes — identifies the packet as RakNet unconnected pong */
        private val RAKNET_MAGIC = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )

        /** Broadcast interval in milliseconds */
        private const val BROADCAST_INTERVAL_MS = 1500L

        /**
         * Bedrock protocol version.
         * Update this constant whenever a new Bedrock release ships.
         * 748 = 1.21.50 | 729 = 1.21.40 | 712 = 1.21.30 | 685 = 1.21.20
         * Check wiki.vg/Bedrock_Protocol for the latest mapping.
         */
        private const val PROTOCOL_VERSION = 748
        private const val GAME_VERSION = "1.21.50"

        /** Maximum broadcast attempts before giving up on socket errors */
        private const val MAX_SOCKET_ERRORS = 5
    }

    /**
     * Build the MOTD advertisement string for a given server entry.
     *
     * @param serverName    Display name shown in the LAN tab.
     * @param serverId      Unique 64-bit server identifier.
     */
    private fun buildMotdPacket(serverName: String, serverId: Long): ByteArray {
        // Unconnected Pong packet ID
        val packetId = byteArrayOf(0x1C)

        // Current time (8 bytes, big-endian)
        val currentTime = System.currentTimeMillis()
        val timeBytes = ByteArray(8) { i -> ((currentTime shr ((7 - i) * 8)) and 0xFF).toByte() }

        // Server GUID (8 bytes, big-endian)
        val guidBytes = ByteArray(8) { i -> ((serverId shr ((7 - i) * 8)) and 0xFF).toByte() }

        // MOTD payload string
        val motd = "MCPE;$serverName;$PROTOCOL_VERSION;$GAME_VERSION;0;20;$serverId;FreeConnect;Survival;1;$BEDROCK_PORT;$BEDROCK_PORT;"
        val motdBytes = motd.toByteArray(Charsets.UTF_8)

        // Payload length (2 bytes, big-endian)
        val lengthBytes = byteArrayOf(
            ((motdBytes.size shr 8) and 0xFF).toByte(),
            (motdBytes.size and 0xFF).toByte()
        )

        // Assemble: PacketID + Time + Magic + GUID + Length + MOTD
        return packetId + timeBytes + RAKNET_MAGIC + guidBytes + lengthBytes + motdBytes
    }

    /**
     * Continuously broadcast a server advertisement over UDP until the
     * coroutine is cancelled.
     *
     * Call this inside a [kotlinx.coroutines.CoroutineScope] that you
     * control; cancelling the scope stops the broadcast.
     *
     * @param serverName    Display name of the server.
     * @param serverIp      IP address of the target server (informational).
     * @param serverPort    Port of the target server (informational).
     * @param onError       Called with a descriptive message on unrecoverable error.
     */
    suspend fun startBroadcasting(
        serverName: String,
        serverIp: String,
        serverPort: Int,
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val serverId = System.currentTimeMillis() // unique enough for LAN use
        var socket: DatagramSocket? = null
        var errorCount = 0

        try {
            // Create a UDP socket bound to the broadcast port
            socket = DatagramSocket(BEDROCK_PORT).apply {
                broadcast = true
                reuseAddress = true
            }
            val broadcastAddress = InetAddress.getByName("255.255.255.255")

            Log.i(TAG, "LAN broadcast started for '$serverName' ($serverIp:$serverPort)")

            while (isActive && errorCount < MAX_SOCKET_ERRORS) {
                try {
                    val payload = buildMotdPacket(serverName, serverId)
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        broadcastAddress,
                        BEDROCK_PORT
                    )
                    socket.send(packet)
                    errorCount = 0 // reset on success
                } catch (e: Exception) {
                    errorCount++
                    Log.w(TAG, "Broadcast send error ($errorCount/$MAX_SOCKET_ERRORS): ${e.message}")
                    if (errorCount >= MAX_SOCKET_ERRORS) {
                        onError("Broadcast failed after $MAX_SOCKET_ERRORS errors: ${e.message}")
                    }
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open broadcast socket: ${e.message}", e)
            onError("Could not open broadcast socket: ${e.message}")
        } finally {
            socket?.close()
            Log.i(TAG, "LAN broadcast stopped for '$serverName'")
        }
    }
}
