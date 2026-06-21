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
 * Foreground service that runs the Minecraft Bedrock LAN broadcaster.
 *
 * Start via [startBroadcast] and stop via [stopBroadcast] companion helpers.
 * The notification keeps the process alive while broadcasting.
 */
@AndroidEntryPoint
class LanBroadcastService : Service() {

    @Inject
    lateinit var lanBroadcaster: LanBroadcaster

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcastJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "lan_broadcast_channel"
        private const val NOTIFICATION_ID = 1001

        // Intent extras
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"
        const val ACTION_STOP = "com.freeconnect.bedrock.STOP_BROADCAST"

        /** Convenience helper to start broadcasting a specific server. */
        fun startBroadcast(context: Context, name: String, ip: String, port: Int) {
            val intent = Intent(context, LanBroadcastService::class.java).apply {
                putExtra(EXTRA_SERVER_NAME, name)
                putExtra(EXTRA_SERVER_IP, ip)
                putExtra(EXTRA_SERVER_PORT, port)
            }
            context.startForegroundService(intent)
        }

        /** Convenience helper to stop the foreground service. */
        fun stopBroadcast(context: Context) {
            val intent = Intent(context, LanBroadcastService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
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
        val serverIp = intent?.getStringExtra(EXTRA_SERVER_IP) ?: ""
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 19132) ?: 19132

        // Move to foreground immediately so the system doesn't kill us
        startForeground(NOTIFICATION_ID, buildNotification(serverName, serverIp, serverPort))

        // Cancel any previous broadcast and start a new one
        broadcastJob?.cancel()
        broadcastJob = serviceScope.launch {
            lanBroadcaster.startBroadcasting(
                serverName = serverName,
                serverIp = serverIp,
                serverPort = serverPort,
                onError = { error ->
                    Log.e(TAG, "Broadcast error: $error")
                    // Could update notification here to show error state
                }
            )
        }

        Log.i(TAG, "Broadcast service started for '$serverName' ($serverIp:$serverPort)")
        return START_STICKY // Restart if the system kills the service
    }

    override fun onDestroy() {
        broadcastJob?.cancel()
        super.onDestroy()
        Log.i(TAG, "Broadcast service destroyed")
    }

    // Not a bound service
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
            description = "Shows while FreeConnect is broadcasting a server on your LAN"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(name: String, ip: String, port: Int): Notification {
        // Tap notification to open the app
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action button to stop broadcasting
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LanBroadcastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Broadcasting: $name")
            .setContentText("$ip:$port — visible on local network")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
