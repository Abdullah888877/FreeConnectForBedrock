package com.freeconnect.bedrock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.freeconnect.bedrock.MainActivity
import com.freeconnect.bedrock.R
import com.freeconnect.bedrock.data.resourcepack.ResourcePackRepository
import com.freeconnect.bedrock.network.LanBroadcaster
import com.freeconnect.bedrock.network.RakNetServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LanBroadcastService"

/**
 * Foreground service that:
 *   1. Acquires a WifiManager.MulticastLock so Android delivers incoming UDP
 *      broadcast packets (critical on Samsung/Xiaomi/Oppo etc.).
 *   2. Runs [LanBroadcaster] — dual-mode LAN discovery on port 19132.
 *   3. Runs [RakNetServer] — full RakNet handshake + [BedrockSession] for
 *      resource-pack delivery and Transfer on port 19133.
 *
 * Resource-pack flow (no proxy needed):
 *   Client → [RakNetServer] (19133) → login + pack download → Transfer →
 *   Client connects DIRECTLY to the real Bedrock server.
 */
@AndroidEntryPoint
class LanBroadcastService : Service() {

    @Inject lateinit var lanBroadcaster: LanBroadcaster
    @Inject lateinit var rakNetServer: RakNetServer
    @Inject lateinit var resourcePackRepository: ResourcePackRepository

    private val serviceScope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcasterJob: Job? = null
    private var serverJob: Job?      = null

    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val CHANNEL_ID      = "lan_broadcast_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MULTICAST_TAG   = "FreeConnect:LAN"

        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_IP   = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"
        const val ACTION_STOP       = "com.freeconnect.bedrock.STOP_BROADCAST"

        fun startBroadcast(context: Context, name: String, ip: String, port: Int) {
            context.startForegroundService(
                Intent(context, LanBroadcastService::class.java).apply {
                    putExtra(EXTRA_SERVER_NAME, name)
                    putExtra(EXTRA_SERVER_IP,   ip)
                    putExtra(EXTRA_SERVER_PORT, port)
                }
            )
        }

        fun stopBroadcast(context: Context) {
            context.startService(
                Intent(context, LanBroadcastService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock(MULTICAST_TAG).also {
            it.setReferenceCounted(true)
            it.acquire()
            Log.i(TAG, "MulticastLock acquired")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "Minecraft Server"
        val serverIp   = intent?.getStringExtra(EXTRA_SERVER_IP)   ?: ""
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 19132) ?: 19132

        startForeground(NOTIFICATION_ID, buildNotification(serverName, serverIp, serverPort))

        broadcasterJob?.cancel()
        serverJob?.cancel()

        // Single shared GUID — LanBroadcaster and RakNetServer MUST advertise and
        // respond with the same serverId so Minecraft sees a consistent identity.
        val serverId = System.currentTimeMillis()

        broadcasterJob = serviceScope.launch {
            lanBroadcaster.startBroadcasting(
                serverName  = serverName,
                serverId    = serverId,
                connectPort = RakNetServer.SERVER_PORT,
                onError     = { err -> Log.e(TAG, "Broadcaster: $err") }
            )
        }

        serverJob = serviceScope.launch {
            // Load currently-enabled packs (suspend, waits for one DB emission)
            val enabledPacks = resourcePackRepository.enabledPacks.first()
            Log.i(TAG, "Starting RakNet server with ${enabledPacks.size} enabled pack(s)")
            rakNetServer.start(
                serverId     = serverId,
                serverName   = serverName,
                enabledPacks = enabledPacks,
                remoteIp     = serverIp,
                remotePort   = serverPort,
                onError      = { err -> Log.e(TAG, "RakNet: $err") }
            )
        }

        Log.i(TAG, "Service started — '${serverName}' ${serverIp}:${serverPort}")
        return START_STICKY
    }

    override fun onDestroy() {
        broadcasterJob?.cancel()
        serverJob?.cancel()
        serviceScope.cancel()
        multicastLock?.let { if (it.isHeld) { it.release(); Log.i(TAG, "MulticastLock released") } }
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "LAN Broadcast", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Active while FreeConnect is relaying a Bedrock server"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(name: String, ip: String, port: Int): Notification {
        val openI = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopI = PendingIntent.getService(this, 1,
            Intent(this, LanBroadcastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Broadcasting: ${name}")
            .setContentText("Packs → LAN:${RakNetServer.SERVER_PORT} → ${ip}:${port}")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openI)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopI)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
