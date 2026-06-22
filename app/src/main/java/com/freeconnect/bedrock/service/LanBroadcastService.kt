package com.freeconnect.bedrock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.freeconnect.bedrock.MainActivity
import com.freeconnect.bedrock.R
import com.freeconnect.bedrock.network.BedrockProxy
import com.freeconnect.bedrock.network.LanBroadcaster
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LanBroadcastService"

/**
 * Foreground service that runs the Minecraft Bedrock LAN broadcaster AND the
 * connection proxy side-by-side.
 *
 * Flow:
 *   1. [LanBroadcaster] listens on port 19132 for Unconnected Ping packets
 *      from Minecraft and responds with pongs that advertise port 19133.
 *   2. [BedrockProxy] listens on port 19133 and transparently forwards all
 *      UDP traffic between the Minecraft client and the real remote server.
 *
 * Start via [startBroadcast] and stop via [stopBroadcast].
 */
@AndroidEntryPoint
class LanBroadcastService : Service() {

    @Inject lateinit var lanBroadcaster: LanBroadcaster
    @Inject lateinit var bedrockProxy: BedrockProxy

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcasterJob: Job? = null
    private var proxyJob: Job? = null

    companion object {
        private const val CHANNEL_ID      = "lan_broadcast_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_IP   = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"
        const val ACTION_STOP       = "com.freeconnect.bedrock.STOP_BROADCAST"

        fun startBroadcast(context: Context, name: String, ip: String, port: Int) {
            val intent = Intent(context, LanBroadcastService::class.java).apply {
                putExtra(EXTRA_SERVER_NAME, name)
                putExtra(EXTRA_SERVER_IP,   ip)
                putExtra(EXTRA_SERVER_PORT, port)
            }
            context.startForegroundService(intent)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "Minecraft Server"
        val serverIp   = intent?.getStringExtra(EXTRA_SERVER_IP)   ?: ""
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 19132) ?: 19132

        startForeground(NOTIFICATION_ID, buildNotification(serverName, serverIp, serverPort))

        // Cancel any previous jobs before starting new ones
        broadcasterJob?.cancel()
        proxyJob?.cancel()

        // 1. Ping listener → advertises BedrockProxy.PROXY_PORT in the MOTD
        broadcasterJob = serviceScope.launch {
            lanBroadcaster.startBroadcasting(
                serverName  = serverName,
                connectPort = BedrockProxy.PROXY_PORT,
                onError     = { err -> Log.e(TAG, "Broadcaster error: $err") }
            )
        }

        // 2. UDP proxy → forwards Minecraft ↔ remote server traffic
        proxyJob = serviceScope.launch {
            bedrockProxy.start(
                remoteIp   = serverIp,
                remotePort = serverPort,
                onError    = { err -> Log.e(TAG, "Proxy error: $err") }
            )
        }

        Log.i(TAG, "Service started — broadcasting '$serverName', proxying → $serverIp:$serverPort")
        return START_STICKY
    }

    override fun onDestroy() {
        broadcasterJob?.cancel()
        proxyJob?.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LAN Broadcast",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active while FreeConnect is relaying a server on your LAN"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(name: String, ip: String, port: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LanBroadcastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Relaying: $name")
            .setContentText("$ip:$port → LAN port ${BedrockProxy.PROXY_PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
