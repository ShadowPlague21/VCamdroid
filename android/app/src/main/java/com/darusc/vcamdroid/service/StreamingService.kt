package com.darusc.vcamdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.darusc.vcamdroid.MainActivity
import com.darusc.vcamdroid.R
import com.darusc.vcamdroid.util.Logger
import com.darusc.vcamdroid.util.PowerSaveManager

class StreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "vcamdroid_streaming_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.darusc.vcamdroid.action.START_STREAMING"
        const val ACTION_STOP = "com.darusc.vcamdroid.action.STOP_STREAMING"

        const val EXTRA_STATUS = "status"

        fun startService(context: Context, status: String = "Camera ready") {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateStatus(context: Context, text: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(context, text))
        }

        fun buildNotification(context: Context, contentText: String): Notification {
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val stopIntent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("VCamdroid")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.log_white)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }

    private val binder = LocalBinder()
    private lateinit var powerSaveManager: PowerSaveManager
    var onScreenStateChanged: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        powerSaveManager = PowerSaveManager(this)
        powerSaveManager.onScreenStateChanged = { isScreenOn ->
            onScreenStateChanged?.invoke(isScreenOn)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Camera ready"
                val notification = buildNotification(this, status)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        } else {
                            0
                        }
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                powerSaveManager.acquireLocks()
                Logger.log("SERVICE", "Streaming Foreground Service started with power locks")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        powerSaveManager.releaseLocks()
        Logger.log("SERVICE", "Streaming Foreground Service destroyed, locks released")
    }

    fun updateNotificationText(text: String) {
        updateStatus(this, text)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VCamdroid Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps camera stream running when screen is turned off"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
