package com.darusc.vcamdroid.droidcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import com.darusc.vcamdroid.ai.AiTrackerEngine
import com.darusc.vcamdroid.ai.GestureDetectorHelper
import com.darusc.vcamdroid.util.Logger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class DroidCamStreamer(
    private val context: Context,
    private val droidCamServer: DroidCamServer
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val settings = DroidCamSettings(context)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var repeatingRequestBuilder: CaptureRequest.Builder? = null

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null

    var isStreaming = false
        private set

    var currentWidth = 1920
        private set
    var currentHeight = 1080
        private set
    var currentFormat = "avc"
        private set
    var isBackCamera = true
        private set

    var supportedResolutions: List<Pair<Int, Int>> = emptyList()
        private set

    // Live Camera Controls State
    var currentZoom: Float = 1.0f
        private set
    var isTorchOn: Boolean = false
        private set
    var currentExposureCompensation: Int = 0
        private set
    var isAeLocked: Boolean = false
        private set
    var currentAfMode: Int = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        private set
    var currentAwbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
        private set
    var currentAntiFlickerMode: Int = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        private set

    // ISO Manual & Auto state
    var isManualIso: Boolean = false
        private set
    var currentIso: Int = 100
        private set
    var minIso: Int = 100
        private set
    var maxIso: Int = 6400
        private set
    var actualSensorIso: Int = 100
        private set
    var lastSensorExposureTimeNs: Long = 16_666_666L
        private set

    // Focus Manual & Auto state
    var isManualFocus: Boolean = false
        private set
    var currentFocusDistance: Float = 0.0f
        private set
    var minFocusDistance: Float = 0.0f
        private set
    var maxFocusDistance: Float = 10.0f
        private set

    // Capabilities for current active camera
    var maxZoomFactor: Float = 8.0f
        private set
    var minZoomFactor: Float = 1.0f
        private set
    var hasFlash: Boolean = false
        private set
    var minExposureCompensation: Int = -4
        private set
    var maxExposureCompensation: Int = 4
        private set
    var exposureCompensationStep: Float = 0.5f
        private set

    var onCapabilitiesChanged: (() -> Unit)? = null

    // AI Tracking & Virtual Gimbal (ePTZ)
    var aiTrackerEngine: AiTrackerEngine? = null
        private set
    var gestureDetectorHelper: GestureDetectorHelper? = null
        private set

    var onAiTrackingStateChanged: ((Boolean) -> Unit)? = null
    var onAiFaceTrackingUpdate: ((Boolean, RectF?) -> Unit)? = null
    var onGestureFeedback: ((String) -> Unit)? = null
    var onFramingModeChanged: ((AiTrackerEngine.FramingMode) -> Unit)? = null

    private var aiImageReader: ImageReader? = null
    private var aiThread: HandlerThread? = null
    private var aiHandler: Handler? = null
    private var lastGestureProcessTimeMs = 0L

    init {
        currentWidth = settings.targetResolutionWidth
        currentHeight = settings.targetResolutionHeight
        currentZoom = settings.lastZoomFactor
        currentExposureCompensation = settings.lastExposureCompensation
        currentAwbMode = settings.lastAwbMode
        currentAfMode = settings.lastAfMode

        val framingMode = if (settings.aiFramingMode == "9:16") {
            AiTrackerEngine.FramingMode.PORTRAIT_9_16
        } else {
            AiTrackerEngine.FramingMode.LANDSCAPE_16_9
        }

        aiTrackerEngine = AiTrackerEngine(
            context,
            onCropRegionChanged = { cropRect ->
                applyCropRegion(cropRect)
            },
            onFaceDetected = { hasFace, bounds ->
                onAiFaceTrackingUpdate?.invoke(hasFace, bounds)
            }
        ).apply {
            setFramingMode(framingMode)
            setTrackingEnabled(settings.isAiTrackingEnabled)
        }

        if (settings.isGesturesEnabled) {
            gestureDetectorHelper = GestureDetectorHelper(
                context,
                onTrackingToggled = { active ->
                    settings.isAiTrackingEnabled = active
                    aiTrackerEngine?.setTrackingEnabled(active)
                    onAiTrackingStateChanged?.invoke(active)
                },
                onPinchZoom = { zoom ->
                    aiTrackerEngine?.setUserZoom(zoom)
                    setZoom(zoom)
                },
                onGestureFeedback = { msg ->
                    onGestureFeedback?.invoke(msg)
                }
            )
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (isStreaming) {
            updateCaptureSession()
        }
    }

    fun startStream(
        format: String, // "avc" or "hevc"
        width: Int,
        height: Int,
        fps: Int = 30,
        facingBack: Boolean = true
    ) {
        if (isStreaming) {
            stopStream()
        }

        currentWidth = width
        currentHeight = height
        currentFormat = format
        isBackCamera = facingBack

        startBackgroundThread()

        val mimeType = if (format.equals("hevc", ignoreCase = true)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }

        try {
            setupEncoder(mimeType, width, height, settings.targetFps)
            openCamera()
            isStreaming = true
            Logger.log("DROIDCAM_STREAMER", "Streaming started: $width x $height @ ${settings.targetFps} fps ($format)")
        } catch (e: Exception) {
            Logger.log("DROIDCAM_STREAMER", "Failed to start stream: ${e.message}")
            stopStream()
        }
    }

    fun stopStream() {
        isStreaming = false

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) { }
        captureSession = null
        repeatingRequestBuilder = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) { }
        cameraDevice = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) { }
        mediaCodec = null

        encoderSurface?.release()
        encoderSurface = null

        try {
            aiImageReader?.close()
        } catch (_: Exception) { }
        aiImageReader = null

        stopBackgroundThread()
        Logger.log("DROIDCAM_STREAMER", "Streaming stopped")
    }

    fun onScreenOff() {
        Logger.log("DROIDCAM_STREAMER", "Screen OFF -> maintaining camera session on hardware encoder surface only")
        previewSurface = null
        if (isStreaming && settings.keepVideoActiveScreenOff) {
            updateCaptureSession()
        }
    }

    fun onScreenOn(surface: Surface?) {
        Logger.log("DROIDCAM_STREAMER", "Screen ON -> attaching preview surface")
        previewSurface = surface
        if (isStreaming) {
            updateCaptureSession()
        }
    }

    // --- Dynamic In-Stream Controls ---

    fun setTorch(enable: Boolean) {
        if (!hasFlash) return
        isTorchOn = enable
        applyCaptureSettings()
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom.coerceIn(minZoomFactor, maxZoomFactor)
        settings.lastZoomFactor = currentZoom
        aiTrackerEngine?.setUserZoom(currentZoom)
        gestureDetectorHelper?.updateCurrentZoom(currentZoom)
        applyCaptureSettings()
    }

    // --- AI Tracking Controls ---

    fun setAiTrackingEnabled(enabled: Boolean) {
        settings.isAiTrackingEnabled = enabled
        aiTrackerEngine?.setTrackingEnabled(enabled)
        gestureDetectorHelper?.setTrackingActive(enabled)
        onAiTrackingStateChanged?.invoke(enabled)
        if (!enabled) {
            applyCaptureSettings()
        }
    }

    fun toggleAiTracking(): Boolean {
        val newState = !(aiTrackerEngine?.isTrackingEnabled() ?: false)
        setAiTrackingEnabled(newState)
        return newState
    }

    fun setFramingMode(mode: AiTrackerEngine.FramingMode) {
        settings.aiFramingMode = if (mode == AiTrackerEngine.FramingMode.PORTRAIT_9_16) "9:16" else "16:9"
        aiTrackerEngine?.setFramingMode(mode)
        onFramingModeChanged?.invoke(mode)
    }

    fun toggleFramingMode(): AiTrackerEngine.FramingMode {
        val current = aiTrackerEngine?.getFramingMode() ?: AiTrackerEngine.FramingMode.LANDSCAPE_16_9
        val next = if (current == AiTrackerEngine.FramingMode.LANDSCAPE_16_9) {
            AiTrackerEngine.FramingMode.PORTRAIT_9_16
        } else {
            AiTrackerEngine.FramingMode.LANDSCAPE_16_9
        }
        setFramingMode(next)
        return next
    }

    private var lastHalUpdateTimeMs = 0L
    private var lastAppliedCropRect: Rect? = null

    private fun applyCropRegion(cropRect: Rect) {
        val now = SystemClock.uptimeMillis()
        // Cap HAL updates to at most ~20 Hz (50ms) to ensure Qualcomm Spectra ISP AE convergence
        if (now - lastHalUpdateTimeMs < 50L) return
        if (lastAppliedCropRect == cropRect) return

        val session = captureSession ?: return
        val builder = repeatingRequestBuilder ?: return
        try {
            lastHalUpdateTimeMs = now
            lastAppliedCropRect = cropRect
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (_: Exception) { }
    }

    fun setExposureCompensation(value: Int) {
        currentExposureCompensation = value.coerceIn(minExposureCompensation, maxExposureCompensation)
        settings.lastExposureCompensation = currentExposureCompensation
        applyCaptureSettings()
    }

    fun setAeLock(lock: Boolean) {
        isAeLocked = lock
        applyCaptureSettings()
    }

    fun setAwbMode(mode: Int) {
        currentAwbMode = mode
        settings.lastAwbMode = mode
        applyCaptureSettings()
    }

    fun setIso(iso: Int) {
        isManualIso = true
        currentIso = iso.coerceIn(minIso, maxIso)
        applyCaptureSettings()
    }

    fun setAutoIso(auto: Boolean) {
        isManualIso = !auto
        applyCaptureSettings()
    }

    fun setFocusDistance(distance: Float) {
        isManualFocus = true
        currentFocusDistance = distance.coerceIn(minFocusDistance, maxFocusDistance)
        applyCaptureSettings()
    }

    fun setAutoAf(mode: Int = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
        isManualFocus = false
        currentAfMode = mode
        settings.lastAfMode = mode
        applyCaptureSettings()
    }

    fun setAfMode(mode: Int) {
        isManualFocus = false
        currentAfMode = mode
        settings.lastAfMode = mode
        applyCaptureSettings()
    }

    fun setAntiFlicker(mode: String) {
        currentAntiFlickerMode = when (mode.lowercase()) {
            "off" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            "50hz" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            "60hz" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        applyCaptureSettings()
    }

    fun triggerTapToFocus(touchX: Float, touchY: Float, viewWidth: Int, viewHeight: Int) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val builder = repeatingRequestBuilder ?: return

        try {
            val chars = cameraManager.getCameraCharacteristics(camera.id)
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            val normalizedX = (touchX / viewWidth.toFloat()).coerceIn(0.0f, 1.0f)
            val normalizedY = (touchY / viewHeight.toFloat()).coerceIn(0.0f, 1.0f)

            val focusAreaSize = 250
            val centerX = (normalizedX * sensorRect.width()).toInt()
            val centerY = (normalizedY * sensorRect.height()).toInt()

            val left = (centerX - focusAreaSize / 2).coerceIn(sensorRect.left, sensorRect.right - focusAreaSize)
            val top = (centerY - focusAreaSize / 2).coerceIn(sensorRect.top, sensorRect.bottom - focusAreaSize)
            val right = left + focusAreaSize
            val bottom = top + focusAreaSize

            val meteringRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)

            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), null, cameraHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            currentAfMode = CameraMetadata.CONTROL_AF_MODE_AUTO
            settings.lastAfMode = currentAfMode
        } catch (e: Exception) {
            Logger.log("DROIDCAM_STREAMER", "Tap to focus failed: ${e.message}")
        }
    }

    private fun applyCaptureSettings() {
        val session = captureSession ?: return
        val builder = repeatingRequestBuilder ?: return
        val camera = cameraDevice ?: return

        try {
            val chars = cameraManager.getCameraCharacteristics(camera.id)

            if (hasFlash) {
                if (isTorchOn) {
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
            } else {
                val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (sensorRect != null) {
                    val cropW = (sensorRect.width() / currentZoom).roundToInt()
                    val cropH = (sensorRect.height() / currentZoom).roundToInt()
                    val cropX = (sensorRect.width() - cropW) / 2
                    val cropY = (sensorRect.height() - cropH) / 2
                    builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }
            }

            if (isManualIso) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lastSensorExposureTimeNs)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureCompensation)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntiFlickerMode)
            }

            if (isManualFocus) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, currentAfMode)
            }

            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)

            // DroidCam-style Sensor Frame Duration for 60 FPS timing
            val targetFps = settings.targetFps
            val frameDurationNs = 1_000_000_000L / targetFps
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

            val fpsRange = getBestFpsRange(chars, targetFps, settings.matchTargetMinFps)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (e: Exception) {
            Logger.log("DROIDCAM_STREAMER", "applyCaptureSettings error: ${e.message}")
        }
    }

    // --- Encoder Setup ---

    private fun setupEncoder(mimeType: String, width: Int, height: Int, fps: Int) {
        val bitrate = settings.targetBitrateKbps * 1024
        val keyInterval = settings.keyFrameIntervalSec

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyInterval)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_LATENCY, 0) // Zero latency
            }
        }

        val codec = MediaCodec.createEncoderByType(mimeType)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                if (!isStreaming) return
                try {
                    val outputBuffer = codec.getOutputBuffer(index) ?: return

                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        val configBytes = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(configBytes)
                        droidCamServer.sendConfigPacket(configBytes)
                    } else if (info.size > 0) {
                        val ptsMs = info.presentationTimeUs / 1000L
                        droidCamServer.sendVideoPacket(ptsMs, outputBuffer, info.offset, info.size)
                    }

                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                } catch (e: Exception) {
                    Logger.log("DROIDCAM_STREAMER", "onOutputBufferAvailable error: ${e.message}")
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Logger.log("DROIDCAM_STREAMER", "MediaCodec error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Logger.log("DROIDCAM_STREAMER", "Encoder output format changed: $format")
            }
        }, cameraHandler)

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec
    }

    // --- Camera Lifecycle ---

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraId = getCameraId(isBackCamera)
        queryCameraCapabilities(cameraId)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                updateCaptureSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Logger.log("DROIDCAM_STREAMER", "CameraDevice error: $error")
                camera.close()
                cameraDevice = null
            }
        }, cameraHandler)
    }

    private fun queryCameraCapabilities(cameraId: String) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                minZoomFactor = zoomRange.lower
                maxZoomFactor = zoomRange.upper
            } else {
                minZoomFactor = 1.0f
                maxZoomFactor = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8.0f
            }
        } else {
            minZoomFactor = 1.0f
            maxZoomFactor = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8.0f
        }

        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (evRange != null) {
            minExposureCompensation = evRange.lower
            maxExposureCompensation = evRange.upper
        }
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (step != null && step.denominator != 0) {
            exposureCompensationStep = step.numerator.toFloat() / step.denominator.toFloat()
        }

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoRange != null) {
            minIso = isoRange.lower
            maxIso = isoRange.upper
            currentIso = currentIso.coerceIn(minIso, maxIso)
        }

        val minFd = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        if (minFd != null && minFd > 0f) {
            maxFocusDistance = minFd
            minFocusDistance = 0.0f
        }

        currentZoom = currentZoom.coerceIn(minZoomFactor, maxZoomFactor)
        currentExposureCompensation = currentExposureCompensation.coerceIn(minExposureCompensation, maxExposureCompensation)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val rawSizes = mutableSetOf<android.util.Size>()
            map.getOutputSizes(MediaCodec::class.java)?.let { rawSizes.addAll(it) }
            map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.let { rawSizes.addAll(it) }
            map.getOutputSizes(android.media.MediaRecorder::class.java)?.let { rawSizes.addAll(it) }
            map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.let { rawSizes.addAll(it) }

            supportedResolutions = rawSizes.map { size ->
                Pair(maxOf(size.width, size.height), minOf(size.width, size.height))
            }.distinct().sortedByDescending { it.first * it.second }
        }

        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (sensorRect != null) {
            aiTrackerEngine?.setSensorActiveArray(sensorRect)
        }

        onCapabilitiesChanged?.invoke()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (exp != null && exp > 0) {
                lastSensorExposureTimeNs = exp
            }
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            if (iso != null && iso > 0) {
                actualSensorIso = iso
            }
        }
    }

    private fun updateCaptureSession() {
        val camera = cameraDevice ?: return
        val encoderSurf = encoderSurface ?: return

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) { }

        val surfaces = mutableListOf<Surface>()
        surfaces.add(encoderSurf)
        previewSurface?.takeIf { it.isValid }?.let { surfaces.add(it) }

        // AI Tracking ImageReader (downscaled 320x240 for 3ms BlazeFace inference)
        try {
            aiImageReader?.close()
        } catch (_: Exception) { }

        val aiReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
        aiReader.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!isStreaming) {
                img.close()
                return@setOnImageAvailableListener
            }

            try {
                // Run gesture recognition at ~6 Hz (every 160ms) using zero-copy MediaImageBuilder
                val now = SystemClock.uptimeMillis()
                if (settings.isGesturesEnabled && now - lastGestureProcessTimeMs > 160L) {
                    lastGestureProcessTimeMs = now
                    gestureDetectorHelper?.processImage(img, 90)
                }

                // Run face tracking at ~14 Hz (handles closing img internally)
                val engine = aiTrackerEngine
                if (engine != null && engine.isTrackingEnabled()) {
                    engine.processImage(img, 90)
                } else {
                    img.close()
                }
            } catch (_: Exception) {
                try { img.close() } catch (_: Exception) {}
            }
        }, aiHandler)
        aiImageReader = aiReader
        surfaces.add(aiReader.surface)

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (surface in surfaces) {
                builder.addTarget(surface)
            }

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            if (isManualIso) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lastSensorExposureTimeNs)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureCompensation)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, currentAntiFlickerMode)
            }

            if (isManualFocus) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, currentAfMode)
            }

            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)

            setAntiFlicker(settings.antiFlickerMode)

            if (hasFlash && isTorchOn) {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
            }

            val targetFps = settings.targetFps
            val frameDurationNs = 1_000_000_000L / targetFps
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

            val chars = cameraManager.getCameraCharacteristics(camera.id)
            val fpsRange = getBestFpsRange(chars, targetFps, settings.matchTargetMinFps)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            Logger.log("DROIDCAM_STREAMER", "Target FPS: $targetFps -> AE FPS Range: $fpsRange, Frame Duration: ${frameDurationNs}ns")

            repeatingRequestBuilder = builder

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        if (aiTrackerEngine?.isTrackingEnabled() == true) {
                            builder.set(CaptureRequest.SCALER_CROP_REGION, aiTrackerEngine?.getCurrentCropRegion())
                        }
                        session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                        cameraHandler?.removeCallbacks(smoothingRunnable)
                        cameraHandler?.post(smoothingRunnable)
                    } catch (e: Exception) {
                        Logger.log("DROIDCAM_STREAMER", "setRepeatingRequest error: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Logger.log("DROIDCAM_STREAMER", "CaptureSession configuration failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Logger.log("DROIDCAM_STREAMER", "updateCaptureSession exception: ${e.message}")
        }
    }

    private val smoothingRunnable = object : Runnable {
        override fun run() {
            if (isStreaming && aiTrackerEngine?.isTrackingEnabled() == true) {
                aiTrackerEngine?.updateSmoothingStep(0.016f)
            }
            if (isStreaming) {
                cameraHandler?.postDelayed(this, 16) // 60 FPS motion damping
            }
        }
    }

    private fun getCameraId(facingBack: Boolean): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facingBack && facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (!facingBack && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    private fun getBestFpsRange(chars: CameraCharacteristics, targetFps: Int, matchMin: Boolean): Range<Int> {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(targetFps, targetFps)

        if (matchMin) {
            val exact = ranges.find { it.upper == targetFps && it.lower == targetFps }
            if (exact != null) return exact
        }

        val matchingUpper = ranges.filter { it.upper == targetFps }.maxByOrNull { it.lower }
        if (matchingUpper != null) return matchingUpper

        val upToTarget = ranges.filter { it.upper <= targetFps }.maxByOrNull { it.upper }
        if (upToTarget != null) return upToTarget

        return ranges.maxByOrNull { it.upper } ?: Range(30, 30)
    }

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("DroidCamCameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        if (aiThread == null) {
            aiThread = HandlerThread("DroidCamAiThread").apply { start() }
            aiHandler = Handler(aiThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        cameraHandler?.removeCallbacks(smoothingRunnable)
        cameraThread?.quitSafely()
        aiThread?.quitSafely()
        try {
            cameraThread?.join()
            aiThread?.join()
        } catch (_: Exception) { }
        cameraThread = null
        cameraHandler = null
        aiThread = null
        aiHandler = null
    }
}
