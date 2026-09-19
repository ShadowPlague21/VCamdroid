package com.darusc.vcamdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.RectF
import android.media.MediaFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.darusc.vcamdroid.DroidCamActivity
import com.darusc.vcamdroid.MainActivity
import com.darusc.vcamdroid.R
import com.darusc.vcamdroid.capabilities.ResolutionFpsMatrix
import com.darusc.vcamdroid.capabilities.VideoCapabilityValidator
import com.darusc.vcamdroid.droidcam.DroidCamServer
import com.darusc.vcamdroid.droidcam.DroidCamSettings
import com.darusc.vcamdroid.droidcam.DroidCamStreamer
import com.darusc.vcamdroid.droidcam.MdnsAdvertiser
import com.darusc.vcamdroid.util.Logger
import com.darusc.vcamdroid.util.PowerSaveManager
import java.util.concurrent.CopyOnWriteArrayList

class StreamingService : Service(), DroidCamServer.Listener {

    companion object {
        const val CHANNEL_ID = "vcamdroid_streaming_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.darusc.vcamdroid.action.START_STREAMING"
        const val ACTION_START_DROIDCAM = "com.darusc.vcamdroid.action.START_DROIDCAM"
        const val ACTION_STOP = "com.darusc.vcamdroid.action.STOP_STREAMING"

        const val EXTRA_STATUS = "status"

        @Volatile
        var instance: StreamingService? = null
            private set

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

        fun startDroidCamService(context: Context, status: String = "Waiting for OBS") {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START_DROIDCAM
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

        fun buildNotification(
            context: Context,
            contentText: String,
            isDroidCamSession: Boolean = false
        ): Notification {
            val targetClass = if (isDroidCamSession) DroidCamActivity::class.java else MainActivity::class.java
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, targetClass),
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

    interface SessionObserver {
        fun onSessionStateChanged() {}
        fun onTallyChanged(tallyState: String) {}
        fun onClientConnectionChanged(connected: Boolean, remoteAddress: String, isUsb: Boolean) {}
        fun onAiTrackingStateChanged(enabled: Boolean) {}
        fun onAiFaceTrackingUpdate(hasFace: Boolean, bounds: RectF?) {}
        fun onGestureFeedback(message: String) {}
        fun onZoomChanged(zoom: Float) {}
        fun onCapabilitiesChanged() {}
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<SessionObserver>()

    private lateinit var powerSaveManager: PowerSaveManager
    lateinit var settings: DroidCamSettings
        private set

    var server: DroidCamServer? = null
        private set
    var mdnsAdvertiser: MdnsAdvertiser? = null
        private set
    var streamer: DroidCamStreamer? = null
        private set

    var isDroidCamActive: Boolean = false
        private set
    var currentTallyState: String = "idle"
        private set
    var connectedClientAddress: String? = null
        private set
    var isUsbClient: Boolean = false
        private set

    private var activePreviewSurface: Surface? = null
    var onScreenStateChanged: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = DroidCamSettings(this)
        powerSaveManager = PowerSaveManager(this)
        powerSaveManager.onScreenStateChanged = { isScreenOn ->
            onScreenStateChanged?.invoke(isScreenOn)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreamingSession()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_DROIDCAM -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Waiting for OBS"
                promoteToForeground(status, isDroidCam = true)
                ensureDroidCamSession()
            }
            else -> {
                val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Camera ready"
                promoteToForeground(status, isDroidCam = isDroidCamActive)
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(status: String, isDroidCam: Boolean) {
        val notification = buildNotification(this, status, isDroidCamSession = isDroidCam)
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
        Logger.log("SERVICE", "Streaming Foreground Service running with camera locks")
    }

    fun ensureDroidCamSession() {
        if (isDroidCamActive && server != null && streamer != null) return

        isDroidCamActive = true
        val s = DroidCamServer(this, settings.port, this)
        server = s
        val adv = MdnsAdvertiser(this)
        mdnsAdvertiser = adv
        val st = DroidCamStreamer(this, s)
        streamer = st
        setupStreamerCallbacks(st)

        s.start()
        if (settings.allowWifiDiscovery) {
            adv.startAdvertising(settings.port)
        }
        Logger.log("SERVICE", "DroidCam server session initialized on port ${settings.port}")

        activePreviewSurface?.let { surf ->
            st.setPreviewSurface(surf)
            st.onScreenOn(surf)
        }
        notifySessionStateChanged()
    }

    fun stopStreamingSession() {
        if (!isDroidCamActive && server == null && streamer == null) return

        Logger.log("SERVICE", "Stopping DroidCam server session")
        mdnsAdvertiser?.stopAdvertising()
        server?.stop()
        streamer?.release()
        server = null
        mdnsAdvertiser = null
        streamer = null
        isDroidCamActive = false
        currentTallyState = "idle"
        connectedClientAddress = null
        isUsbClient = false
        notifySessionStateChanged()
    }

    private fun setupStreamerCallbacks(s: DroidCamStreamer) {
        s.onCapabilitiesChanged = {
            mainHandler.post {
                for (obs in observers) obs.onCapabilitiesChanged()
            }
        }
        s.onAiTrackingStateChanged = { active ->
            mainHandler.post {
                for (obs in observers) obs.onAiTrackingStateChanged(active)
            }
        }
        s.onAiFaceTrackingUpdate = { hasFace, bounds ->
            mainHandler.post {
                for (obs in observers) obs.onAiFaceTrackingUpdate(hasFace, bounds)
            }
        }
        s.onGestureFeedback = { msg ->
            mainHandler.post {
                for (obs in observers) obs.onGestureFeedback(msg)
            }
        }
        s.onZoomChanged = { z ->
            mainHandler.post {
                for (obs in observers) obs.onZoomChanged(z)
            }
        }
    }

    fun registerObserver(observer: SessionObserver) {
        if (!observers.contains(observer)) {
            observers.add(observer)
        }
    }

    fun unregisterObserver(observer: SessionObserver) {
        observers.remove(observer)
    }

    fun notifySessionStateChanged() {
        mainHandler.post {
            for (obs in observers) obs.onSessionStateChanged()
        }
    }

    fun notifyTallyChanged(tally: String) {
        mainHandler.post {
            for (obs in observers) obs.onTallyChanged(tally)
        }
    }

    fun notifyClientConnectionChanged(connected: Boolean, remoteAddress: String, isUsb: Boolean) {
        mainHandler.post {
            for (obs in observers) obs.onClientConnectionChanged(connected, remoteAddress, isUsb)
        }
    }

    fun attachPreview(surface: Surface) {
        activePreviewSurface = surface
        streamer?.let { s ->
            s.setPreviewSurface(surface)
            s.onScreenOn(surface)
        }
    }

    fun detachPreview() {
        activePreviewSurface = null
        streamer?.let { s ->
            s.onScreenOff()
        }
    }

    fun applyResolutionChange(
        width: Int,
        height: Int,
        format: String? = null,
        forceStartStream: Boolean = false
    ) {
        val s = streamer ?: return
        val targetFmt = format ?: s.currentFormat
        val mimeType = if (targetFmt.equals("hevc", ignoreCase = true)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val (validW, validH) = VideoCapabilityValidator.validateAndClamp(width, height, mimeType)

        val maxFps = ResolutionFpsMatrix.getMaxFpsForResolution(this, validW, validH, s.isBackCamera)
        if (settings.targetFps > maxFps) {
            settings.targetFps = maxFps
        }

        settings.targetResolutionWidth = validW
        settings.targetResolutionHeight = validH

        if (forceStartStream || server?.isStreaming == true) {
            s.startStream(targetFmt, validW, validH, settings.targetFps, s.isBackCamera)
        } else if (activePreviewSurface != null) {
            s.startLocalPreview(s.isBackCamera)
        }
        notifySessionStateChanged()
    }

    fun applyLensSwitch(isBack: Boolean) {
        val s = streamer ?: return
        s.switchLens(isBack)
        notifySessionStateChanged()
    }

    // --- DroidCamServer.Listener Implementation ---

    override fun getSessionState(): DroidCamServer.SessionState {
        val s = streamer
        return DroidCamServer.SessionState(
            status = if (server?.isStreaming == true) "streaming" else "idle",
            isStreaming = server?.isStreaming == true,
            width = settings.targetResolutionWidth,
            height = settings.targetResolutionHeight,
            fps = settings.targetFps,
            format = s?.currentFormat ?: "h264",
            lens = if (s?.isBackCamera == true) "back" else "front",
            tally = currentTallyState
        )
    }

    override fun onSessionUpdateRequested(
        width: Int?,
        height: Int?,
        fps: Int?,
        format: String?,
        lens: String?
    ): Boolean {
        mainHandler.post {
            val s = streamer ?: return@post
            var changed = false
            if (lens != null) {
                val wantBack = lens.equals("back", ignoreCase = true) || lens.equals("rear", ignoreCase = true)
                if (wantBack != s.isBackCamera) {
                    s.switchLens(wantBack)
                    changed = true
                }
            }
            if (fps != null) {
                val curW = width ?: settings.targetResolutionWidth
                val curH = height ?: settings.targetResolutionHeight
                val maxFps = ResolutionFpsMatrix.getMaxFpsForResolution(this@StreamingService, curW, curH, s.isBackCamera)
                val clampedFps = fps.coerceIn(1, maxFps)
                if (settings.targetFps != clampedFps) {
                    settings.targetFps = clampedFps
                    changed = true
                }
            }
            if (width != null && height != null) {
                val targetFmt = format ?: s.currentFormat
                applyResolutionChange(width, height, targetFmt, forceStartStream = server?.isStreaming == true)
                changed = true
            } else if (format != null && !format.equals(s.currentFormat, ignoreCase = true)) {
                applyResolutionChange(settings.targetResolutionWidth, settings.targetResolutionHeight, format, forceStartStream = server?.isStreaming == true)
                changed = true
            }

            if (changed) {
                notifySessionStateChanged()
            }
        }
        return true
    }

    override fun onVideoStreamStarted(format: String, width: Int, height: Int) {
        mainHandler.post {
            applyResolutionChange(width, height, format, forceStartStream = true)
            currentTallyState = "program"
            updateNotificationText("On air in OBS (${width}×${height} @ ${settings.targetFps}fps)")
            notifyTallyChanged("program")
            notifySessionStateChanged()
        }
    }

    override fun onVideoStreamStopped() {
        mainHandler.post {
            currentTallyState = "idle"
            updateNotificationText("Waiting for OBS")
            streamer?.stopStream()
            notifyTallyChanged("idle")
            notifySessionStateChanged()
        }
    }

    override fun onTallyChanged(tallyState: String) {
        mainHandler.post {
            currentTallyState = tallyState
            notifyTallyChanged(tallyState)
        }
    }

    override fun onClientConnected(remoteAddress: String) {
        val isUsb = remoteAddress.contains("127.0.0.1") || remoteAddress.contains("localhost")
        mainHandler.post {
            connectedClientAddress = remoteAddress
            isUsbClient = isUsb
            updateNotificationText(if (isUsb) "Connected via USB" else "Connected from $remoteAddress")
            notifyClientConnectionChanged(true, remoteAddress, isUsb)
        }
    }

    override fun onClientDisconnected() {
        mainHandler.post {
            connectedClientAddress = null
            isUsbClient = false
            updateNotificationText("Waiting for OBS")
            notifyClientConnectionChanged(false, "", false)
        }
    }

    fun updateNotificationText(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(this, text, isDroidCamSession = isDroidCamActive))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreamingSession()
        powerSaveManager.releaseLocks()
        if (instance === this) {
            instance = null
        }
        Logger.log("SERVICE", "Streaming Foreground Service destroyed, locks released")
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
