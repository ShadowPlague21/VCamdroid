package com.darusc.vcamdroid

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.darusc.vcamdroid.databinding.ActivityStreamBinding
import com.darusc.vcamdroid.networking.ConnectionManager
import com.darusc.vcamdroid.networking.PacketType
import com.darusc.vcamdroid.rtsp.Streamer
import com.darusc.vcamdroid.rtsp.StreamOptions
import com.darusc.vcamdroid.service.StreamingService
import com.darusc.vcamdroid.util.Logger
import com.darusc.vcamdroid.util.applySystemBarInsets

class StreamActivity : AppCompatActivity(), SurfaceHolder.Callback, ConnectionManager.ConnectionStateCallback {

    private lateinit var viewBinding: ActivityStreamBinding

    private val connectionManager = ConnectionManager.getInstance(this)
    private lateinit var streamer: Streamer
    private var isDimMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewBinding = ActivityStreamBinding.inflate(layoutInflater)
        viewBinding.surfaceView.holder.addCallback(this)
        setContentView(viewBinding.root)
        viewBinding.topBar.applySystemBarInsets(bottom = false)

        StreamingService.startService(this, "Desktop webcam connected")

        connectionManager.setOnBytesReceivedCallback(::onBytesReceived)
        streamer = Streamer(StreamOptions(), this, viewBinding.surfaceView)

        viewBinding.btnBack.setOnClickListener { finish() }
        viewBinding.btnSwitchCamera.setOnClickListener { streamer.switchCamera() }
        viewBinding.dimButton.setOnClickListener { toggleDimMode(true) }
        viewBinding.dimOverlay.setOnClickListener { toggleDimMode(false) }
    }

    private fun toggleDimMode(enable: Boolean) {
        isDimMode = enable
        val layoutParams = window.attributes
        if (enable) {
            viewBinding.dimOverlay.visibility = View.VISIBLE
            viewBinding.topBar.visibility = View.GONE
            layoutParams.screenBrightness = 0.01f
        } else {
            viewBinding.dimOverlay.visibility = View.GONE
            viewBinding.topBar.visibility = View.VISIBLE
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = layoutParams
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (holder.surface != null && holder.surface.isValid) {
            streamer.onScreenOn(viewBinding.surfaceView)
            streamer.startPreview()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streamer.onScreenOff()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            StreamingService.stopService(this)
            streamer.stop()
        }
    }

    override fun onDisconnected() {
        Toast.makeText(this, "Desktop app disconnected", Toast.LENGTH_LONG).show()
        Logger.log("STREAM", "TCP server disconnected")
        StreamingService.stopService(this)
        streamer.stop()
        finish()
    }

    private fun onBytesReceived(buffer: ByteArray, bytes: Int) {
        if (bytes <= 0) return
        val type = buffer[0]

        when (type) {
            PacketType.ACTIVATION -> {
                val options = StreamOptions.deserialize(buffer)
                streamer.startStream(options)
                runOnUiThread {
                    viewBinding.txtSubStatus.text = "${options.width}×${options.height}"
                }
            }
            PacketType.CAMERA -> {
                streamer.switchCamera()
            }
            PacketType.RESOLUTION -> {
                val width = (buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0xFF) shl 8)
                val height = (buffer[3].toInt() and 0xFF) or ((buffer[4].toInt() and 0xFF) shl 8)
                streamer.setResolution(width, height)
                runOnUiThread {
                    viewBinding.txtSubStatus.text = "${width}×${height}"
                }
            }
            PacketType.ROTATION -> {
                val degrees = buffer[1].toInt()
                streamer.rotate(degrees)
            }
            PacketType.EFFECT_FILTER -> {
                val filterName = String(buffer, 2, buffer[1].toInt(), Charsets.UTF_8)
                streamer.applyEffectFilter(filterName)
            }
            PacketType.CORRECTION_FILTER -> {
                val filterName = String(buffer, 2, buffer[1].toInt(), Charsets.UTF_8)
                val value = buffer[2 + buffer[1].toInt()].toInt()
                streamer.applyCorrectionFilter(filterName, value)
            }
            PacketType.BITRATE -> {
                val bitrate = (buffer[1].toInt() and 0xFF) or (buffer[2].toInt() shl 8)
                streamer.setBitrate(bitrate)
            }
            PacketType.ADAPTIVE_BITRATE ->  {
                val min = (buffer[1].toInt() and 0xFF) or (buffer[2].toInt() shl 8)
                val max = (buffer[3].toInt() and 0xFF) or (buffer[4].toInt() shl 8)
                streamer.setAdaptiveBitrate(min, max)
            }
            PacketType.STABILIZATION -> {
                streamer.setStabilization(buffer[1].toInt() == 1)
            }
            PacketType.FLASH -> {
                streamer.setFlash(buffer[1].toInt() == 1)
            }
            PacketType.FOCUS -> {
                streamer.setFocus(buffer[1].toInt())
            }
            PacketType.CODEC -> {
                streamer.setH265Codec(buffer[1].toInt() == 1)
            }
            PacketType.FPS -> {
                streamer.setFps(buffer[1].toInt())
            }
            PacketType.ZOOM -> {
                val factor = (buffer[1].toInt() and 0xFF) or
                        ((buffer[2].toInt() and 0xFF) shl 8) or
                        ((buffer[3].toInt() and 0xFF) shl 16) or
                        ((buffer[4].toInt() and 0xFF) shl 24)
                streamer.setZoom(Float.fromBits(factor))
            }
            PacketType.FLIP -> {
                val axis = buffer[1].toInt()
                streamer.flip(if (axis == 0) StreamOptions.FlipAxis.VERTICAL else StreamOptions.FlipAxis.HORIZONTAL)
            }
        }
    }
}
