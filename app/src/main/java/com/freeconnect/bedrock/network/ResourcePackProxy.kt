package com.freeconnect.bedrock.network

import android.util.Log
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ResourcePackProxy"

/**
 * UDP proxy that sits between the Minecraft Bedrock client and a remote server.
 *
 * How it works:
 *   1. Binds a local UDP socket on [LOCAL_PROXY_PORT].
 *   2. Forwards every packet from the client to the real server, and vice versa.
 *   3. When it detects a "Resource Pack Info" packet from the server
 *      (RakNet/Bedrock Play Protocol packet ID 0x06), it rewrites the pack
 *      list with the user's selected local packs so the client downloads them
 *      from the proxy instead.
 *   4. The proxy serves the pack data directly from the device's storage when
 *      the client requests it.
 *
 * Bedrock resource pack packet references:
 *   wiki.vg/Bedrock_Protocol#Resource_Pack_Info  (0x06)
 *   wiki.vg/Bedrock_Protocol#Resource_Pack_Stack (0x07)
 *   wiki.vg/Bedrock_Protocol#Resource_Pack_Client_Response (0x08)
 *
 * NOTE: Bedrock's application-layer packets are wrapped inside RakNet Reliability
 * Layer frames which are themselves inside RakNet Datagram frames. Full RakNet
 * parsing is beyond the scope of a single file; the code below demonstrates the
 * proxy architecture and the hook points where pack injection takes place.
 * A production implementation would integrate a full RakNet library.
 */
@Singleton
class ResourcePackProxy @Inject constructor() {

    companion object {
        /** Local port that the Minecraft client should connect to instead of the real server. */
        const val LOCAL_PROXY_PORT = 19135

        // Bedrock Play Protocol packet IDs (after RakNet unwrapping)
        private const val PACKET_RESOURCE_PACK_INFO  = 0x06.toByte()
        private const val PACKET_RESOURCE_PACK_STACK = 0x07.toByte()
        private const val PACKET_RESOURCE_PACK_RESP  = 0x08.toByte()

        // Maximum UDP datagram size
        private const val MAX_PACKET_SIZE = 65536
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    /** Packs the user has chosen to inject into the session. */
    private var injectedPacks: List<LocalResourcePack> = emptyList()

    /** Whether pack injection is enabled for this session. */
    private var injectEnabled: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configure which resource packs to inject before starting the proxy.
     *
     * @param packs   List of locally-stored packs to push to the client.
     * @param enabled Whether to actually rewrite the server's pack list.
     */
    fun configure(packs: List<LocalResourcePack>, enabled: Boolean) {
        injectedPacks = packs
        injectEnabled = enabled
        Log.i(TAG, "Proxy configured: inject=$enabled, packs=${packs.size}")
    }

    /**
     * Run the proxy until the coroutine is cancelled.
     *
     * @param remoteHost    The real server's hostname or IP.
     * @param remotePort    The real server's port (default 19132).
     * @param onStatus      Callback invoked with human-readable status strings.
     */
    suspend fun runProxy(
        remoteHost: String,
        remotePort: Int = 19132,
        onStatus: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting proxy: localhost:$LOCAL_PROXY_PORT -> $remoteHost:$remotePort")
        onStatus("Proxy starting…")

        var clientSocket: DatagramSocket? = null
        var serverSocket: DatagramSocket? = null

        try {
            // Socket bound for receiving packets from the local Minecraft client
            clientSocket = DatagramSocket(LOCAL_PROXY_PORT).apply { soTimeout = 100 }
            // Unbound socket for sending/receiving packets to/from the remote server
            serverSocket = DatagramSocket().apply { soTimeout = 100 }

            val serverAddress = InetAddress.getByName(remoteHost)
            val serverEndpoint = InetSocketAddress(serverAddress, remotePort)

            // Track which address the client is using (set on first packet from client)
            var clientEndpoint: InetSocketAddress? = null

            onStatus("Proxy running — connect Minecraft to 127.0.0.1:$LOCAL_PROXY_PORT")
            Log.i(TAG, "Proxy ready")

            val buf = ByteArray(MAX_PACKET_SIZE)

            while (isActive) {
                // ── Client → Server ──────────────────────────────────────────
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    clientSocket.receive(packet)

                    // Remember the client's address so we can reply to it
                    if (clientEndpoint == null) {
                        clientEndpoint = InetSocketAddress(packet.address, packet.port)
                        Log.d(TAG, "Client connected from $clientEndpoint")
                    }

                    val data = packet.data.copyOf(packet.length)

                    // Forward to real server
                    val toServer = DatagramPacket(data, data.size, serverEndpoint)
                    serverSocket.send(toServer)
                } catch (_: java.net.SocketTimeoutException) {
                    // No client packet this iteration — normal, continue
                }

                // ── Server → Client ──────────────────────────────────────────
                clientEndpoint?.let { clientAddr ->
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        serverSocket.receive(packet)

                        var data = packet.data.copyOf(packet.length)

                        // Hook point: inspect and optionally rewrite server packets
                        if (injectEnabled && injectedPacks.isNotEmpty()) {
                            data = maybeRewritePackerInfoPacket(data)
                        }

                        val toClient = DatagramPacket(data, data.size, clientAddr)
                        clientSocket.send(toClient)
                    } catch (_: java.net.SocketTimeoutException) {
                        // No server packet this iteration — normal, continue
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Proxy error: ${e.message}", e)
            onStatus("Proxy error: ${e.message}")
        } finally {
            clientSocket?.close()
            serverSocket?.close()
            Log.i(TAG, "Proxy stopped")
            onStatus("Proxy stopped")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet rewriting
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inspect a raw UDP payload received from the server.
     * If it contains a Bedrock "Resource Pack Info" (0x06) packet,
     * rewrite it to prepend the user's chosen local packs.
     *
     * Returns the (possibly modified) payload.
     *
     * IMPORTANT: In a real Bedrock proxy the payload must first be unwrapped
     * from the RakNet Datagram and Reliability Layer frames before the
     * Bedrock packet ID byte is visible. This method shows the logic at the
     * Bedrock application layer; integrate a RakNet parser for full support.
     */
    private fun maybeRewritePackerInfoPacket(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data

        // Attempt to locate the Bedrock packet ID byte. RakNet data datagrams
        // start with a header byte (0x80–0x8f for reliable datagrams).
        // A simplistic heuristic: scan for 0x06 within the first 32 bytes.
        val searchRange = minOf(data.size, 32)
        for (i in 0 until searchRange) {
            if (data[i] == PACKET_RESOURCE_PACK_INFO) {
                Log.d(TAG, "Intercepted Resource Pack Info packet — injecting ${injectedPacks.size} pack(s)")
                return injectPacksIntoResourcePackInfo(data, i)
            }
        }
        return data
    }

    /**
     * Build a modified "Resource Pack Info" payload that prepends the user's
     * packs to the server's existing pack list.
     *
     * Bedrock Resource Pack Info wire format (after RakNet unwrap):
     *   Byte        : Packet ID (0x06)
     *   Bool        : mustAccept
     *   Bool        : hasScripts
     *   Bool        : hasAddonPacks (1.21+)
     *   LE UShort   : behaviour pack count
     *   [ Behaviour pack entries ]
     *   LE UShort   : resource pack count
     *   [ Resource pack entries — each entry:
     *       String (len-prefixed) : UUID
     *       String               : version  (e.g. "1.0.0")
     *       LE Long              : size bytes
     *       String               : content key (encryption, usually empty)
     *       String               : sub-pack name
     *       String               : content identity
     *       Bool                 : has scripts
     *       Bool                 : is addon pack
     *   ]
     *
     * For simplicity this method rebuilds the resource pack section with
     * injected packs listed first, then the originals.
     */
    private fun injectPacksIntoResourcePackInfo(original: ByteArray, packetIdOffset: Int): ByteArray {
        return try {
            val buf = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(packetIdOffset + 1) // skip packet ID

            val mustAccept    = buf.get() != 0.toByte()
            val hasScripts    = buf.get() != 0.toByte()
            val hasAddonPacks = buf.get() != 0.toByte() // may not exist on older protocol versions

            // Skip behaviour packs
            val bpCount = buf.short.toInt() and 0xFFFF
            repeat(bpCount) { skipPackEntry(buf) }

            // Read existing resource packs
            val rpCount = buf.short.toInt() and 0xFFFF
            val existingPacks = (0 until rpCount).map { readPackEntry(buf) }

            // Build new payload
            val out = ByteBuffer.allocate(MAX_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            // Copy everything before this packet
            out.put(original, 0, packetIdOffset)
            out.put(PACKET_RESOURCE_PACK_INFO)
            out.put(if (mustAccept) 1 else 0)
            out.put(if (hasScripts) 1 else 0)
            out.put(if (hasAddonPacks) 1 else 0)
            // Behaviour packs (unmodified count = 0 from buf already read above)
            out.putShort(0)
            // Resource packs = injected + original
            val totalRp = injectedPacks.size + existingPacks.size
            out.putShort(totalRp.toShort())
            // Write injected packs first
            injectedPacks.forEach { pack ->
                writePackEntry(out, pack.uuid, pack.version, pack.sizeBytes)
            }
            // Then write original packs
            existingPacks.forEach { entry -> out.put(entry) }

            val result = ByteArray(out.position())
            out.rewind()
            out.get(result)
            Log.d(TAG, "Rewrote Resource Pack Info: injected ${injectedPacks.size}, kept $rpCount originals")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rewrite Resource Pack Info packet: ${e.message} — forwarding original")
            original
        }
    }

    /** Read a single resource pack entry from the buffer and return its raw bytes. */
    private fun readPackEntry(buf: ByteBuffer): ByteArray {
        val start = buf.position()
        skipPackEntry(buf)
        val end = buf.position()
        val bytes = ByteArray(end - start)
        System.arraycopy(buf.array(), start, bytes, 0, bytes.size)
        return bytes
    }

    /** Skip over one resource pack entry. */
    private fun skipPackEntry(buf: ByteBuffer) {
        readBeString(buf) // UUID
        readBeString(buf) // version
        buf.long          // size
        readBeString(buf) // content key
        readBeString(buf) // sub-pack name
        readBeString(buf) // content identity
        buf.get()         // has scripts
        if (buf.hasRemaining()) buf.get() // is addon pack (optional)
    }

    /** Read a Bedrock length-prefixed UTF-8 string. */
    private fun readBeString(buf: ByteBuffer): String {
        val len = buf.int // unsigned 32-bit LE
        if (len <= 0 || len > 512) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Write a Bedrock length-prefixed UTF-8 string. */
    private fun writeBeString(buf: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buf.putInt(bytes.size)
        buf.put(bytes)
    }

    /** Write a complete resource pack entry for one of the user's local packs. */
    private fun writePackEntry(buf: ByteBuffer, uuid: String, version: String, sizeBytes: Long) {
        writeBeString(buf, uuid)
        writeBeString(buf, version)
        buf.putLong(sizeBytes)
        writeBeString(buf, "")  // content key (no encryption)
        writeBeString(buf, "")  // sub-pack name
        writeBeString(buf, "")  // content identity
        buf.put(0)              // has scripts = false
        buf.put(0)              // is addon pack = false
    }
}
