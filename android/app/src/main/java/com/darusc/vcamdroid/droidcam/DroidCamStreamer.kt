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
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
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
import kotlin.math.ln
import kotlin.math.pow
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
    var currentKelvin: Int = 5200
        private set
    var isManualWb: Boolean = false
        private set
    var flipHorizontal: Boolean = false
        private set
    var flipVertical: Boolean = false
        private set
    var sensorOrientation: Int = 90
        private set
    var rotateAndCrop180Active: Boolean = false
        private set
    private var supportsRotateAndCrop180 = false
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

    private var aiImageReader: ImageReader? = null
    private var aiThread: HandlerThread? = null
    private var aiHandler: Handler? = null
    private var lastGestureProcessTimeMs = 0L
    private var aiBitmap: Bitmap? = null
    private var aiRgbPixels: IntArray? = null

    init {
        currentWidth = settings.targetResolutionWidth
        currentHeight = settings.targetResolutionHeight
        currentZoom = settings.lastZoomFactor
        currentExposureCompensation = settings.lastExposureCompensation
        currentAwbMode = settings.lastAwbMode
        currentAfMode = settings.lastAfMode
        currentKelvin = settings.lastKelvin
        isManualWb = settings.isManualWb
        flipHorizontal = settings.flipHorizontal
        flipVertical = settings.flipVertical
        if (isManualWb) {
            currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_OFF
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
            setTrackingEnabled(settings.isAiTrackingEnabled)
            setStreamSize(currentWidth, currentHeight)
            setUserZoom(currentZoom)
        }

        if (settings.isGesturesEnabled) {
            ensureGestureDetector()
        }
    }

    private fun ensureGestureDetector() {
        if (gestureDetectorHelper != null) return
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
        ).apply {
            setTrackingActive(settings.isAiTrackingEnabled)
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
        isStreaming = false
        closeCameraSession(releaseThreads = false)

        currentWidth = width
        currentHeight = height
        currentFormat = format
        isBackCamera = facingBack
        aiTrackerEngine?.setStreamSize(width, height)

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
        val resumePreview = previewSurface?.isValid == true
        isStreaming = false
        closeCameraSession(releaseThreads = !resumePreview)
        Logger.log("DROIDCAM_STREAMER", "Streaming stopped")
        if (resumePreview) {
            startLocalPreview(isBackCamera)
        }
    }

    fun startLocalPreview(facingBack: Boolean = isBackCamera) {
        if (isStreaming) return
        isBackCamera = facingBack
        aiTrackerEngine?.setStreamSize(currentWidth, currentHeight)
        closeCameraSession(releaseThreads = false)
        startBackgroundThread()
        openCamera()
        Logger.log("DROIDCAM_STREAMER", "Local preview started")
    }

    fun switchLens(facingBack: Boolean) {
        isBackCamera = facingBack
        if (isStreaming) {
            startStream(currentFormat, currentWidth, currentHeight, settings.targetFps, facingBack)
        } else {
            startLocalPreview(facingBack)
        }
    }

    fun release() {
        isStreaming = false
        previewSurface = null
        closeCameraSession(releaseThreads = true)
    }

    private fun closeCameraSession(releaseThreads: Boolean) {
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

        if (releaseThreads) {
            stopBackgroundThread()
        }
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
        } else if (surface != null) {
            startLocalPreview(isBackCamera)
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

    fun setGesturesEnabled(enabled: Boolean) {
        settings.isGesturesEnabled = enabled
        if (enabled) {
            ensureGestureDetector()
        }
        if (isStreaming) {
            updateCaptureSession()
        }
    }

    fun setAiTrackingEnabled(enabled: Boolean) {
        settings.isAiTrackingEnabled = enabled
        aiTrackerEngine?.setTrackingEnabled(enabled)
        gestureDetectorHelper?.setTrackingActive(enabled)
        onAiTrackingStateChanged?.invoke(enabled)
        if (isStreaming) {
            updateCaptureSession()
        } else if (!enabled) {
            applyCaptureSettings()
        }
    }

    fun toggleAiTracking(): Boolean {
        val newState = !(aiTrackerEngine?.isTrackingEnabled() ?: false)
        setAiTrackingEnabled(newState)
        return newState
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
        isManualWb = false
        currentAwbMode = mode
        settings.isManualWb = false
        settings.lastAwbMode = mode
        applyCaptureSettings()
    }

    fun setWhiteBalanceKelvin(kelvin: Int) {
        isManualWb = true
        currentKelvin = kelvin.coerceIn(2000, 8000)
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_OFF
        settings.isManualWb = true
        settings.lastKelvin = currentKelvin
        settings.lastAwbMode = currentAwbMode
        applyCaptureSettings()
    }

    fun setAutoWhiteBalance() {
        isManualWb = false
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        settings.isManualWb = false
        settings.lastAwbMode = currentAwbMode
        applyCaptureSettings()
    }

    fun setPreviewFlips(horizontal: Boolean, vertical: Boolean) {
        flipHorizontal = horizontal
        flipVertical = vertical
        settings.flipHorizontal = horizontal
        settings.flipVertical = vertical
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

            applyWhiteBalance(builder)
            applyRotateAndCrop(builder, chars)

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

    private fun applyWhiteBalance(builder: CaptureRequest.Builder) {
        if (isManualWb) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_COLOR_TRANSFORM)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(currentKelvin))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
        }
    }

    private fun applyRotateAndCrop(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            rotateAndCrop180Active = false
            return
        }
        val modes = chars.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
        val want180 = flipHorizontal && flipVertical &&
            modes?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_180) == true
        rotateAndCrop180Active = want180
        builder.set(
            CaptureRequest.SCALER_ROTATE_AND_CROP,
            if (want180) CaptureRequest.SCALER_ROTATE_AND_CROP_180
            else CaptureRequest.SCALER_ROTATE_AND_CROP_NONE
        )
    }

    private fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val t = kelvin.coerceIn(2000, 8000) / 100.0
        val r: Double
        val g: Double
        val b: Double
        if (t <= 66.0) {
            r = 255.0
            g = (99.4708025861 * ln(t) - 161.1195681661).coerceIn(1.0, 255.0)
            b = if (t <= 19.0) 1.0 else (138.5177312231 * ln(t - 10.0) - 305.044792778).coerceIn(1.0, 255.0)
        } else {
            r = (329.698727446 * (t - 60.0).pow(-0.1332047592)).coerceIn(1.0, 255.0)
            g = (288.1221695283 * (t - 60.0).pow(-0.0755148492)).coerceIn(1.0, 255.0)
            b = 255.0
        }
        val maxC = maxOf(r, g, b)
        val rGain = (maxC / r).toFloat().coerceIn(0.3f, 4f)
        val gGain = (maxC / g).toFloat().coerceIn(0.3f, 4f)
        val bGain = (maxC / b).toFloat().coerceIn(0.3f, 4f)
        return RggbChannelVector(rGain, gGain, gGain, bGain)
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
        aiTrackerEngine?.setStreamSize(currentWidth, currentHeight)

        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        supportsRotateAndCrop180 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_180) == true
        } else {
            false
        }
        rotateAndCrop180Active = supportsRotateAndCrop180 && flipHorizontal && flipVertical

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

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) { }

        val surfaces = mutableListOf<Surface>()
        encoderSurface?.takeIf { it.isValid }?.let { surfaces.add(it) }
        previewSurface?.takeIf { it.isValid }?.let { surfaces.add(it) }
        if (surfaces.isEmpty()) return

        try {
            aiImageReader?.close()
        } catch (_: Exception) { }
        aiImageReader = null

        val useAiPipeline = isStreaming && (settings.isAiTrackingEnabled || settings.isGesturesEnabled)
        if (useAiPipeline) {
            val aiReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4)
            aiReader.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!isStreaming) {
                    img.close()
                    return@setOnImageAvailableListener
                }

                try {
                    val now = SystemClock.uptimeMillis()
                    val needFace = aiTrackerEngine?.isReadyForInference(now) == true
                    val needGesture = settings.isGesturesEnabled && gestureDetectorHelper?.isReadyForInference(now) == true

                    if (!needFace && !needGesture) {
                        img.close()
                        return@setOnImageAvailableListener
                    }

                    val w = img.width
                    val h = img.height
                    if (aiBitmap == null || aiBitmap?.width != w || aiBitmap?.height != h) {
                        aiBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        aiRgbPixels = IntArray(w * h)
                    }

                    val bitmap = aiBitmap
                    val pixels = aiRgbPixels
                    if (bitmap != null && pixels != null) {
                        fastYuv420ToRgb(img, pixels)
                        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                    }

                    img.close()

                    val inferenceRotation = (sensorOrientation + if (rotateAndCrop180Active) 180 else 0) % 360
                    if (bitmap != null) {
                        if (needGesture) {
                            gestureDetectorHelper?.processBitmap(bitmap, inferenceRotation)
                        }
                        if (needFace) {
                            aiTrackerEngine?.processBitmap(bitmap, inferenceRotation)
                        }
                    }
                } catch (_: Exception) {
                    try { img.close() } catch (_: Exception) {}
                }
            }, aiHandler)
            aiImageReader = aiReader
            surfaces.add(aiReader.surface)
        }

        try {
            val template = if (encoderSurface != null) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }
            val builder = camera.createCaptureRequest(template)
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

            applyWhiteBalance(builder)

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
            applyRotateAndCrop(builder, chars)
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
                        if (isStreaming) {
                            cameraHandler?.post(smoothingRunnable)
                        }
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

    private fun fastYuv420ToRgb(image: Image, outPixels: IntArray) {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        var outIndex = 0
        for (y in 0 until height) {
            val yRowStart = y * yRowStride
            val uRowStart = (y shr 1) * uRowStride
            val vRowStart = (y shr 1) * vRowStride
            for (x in 0 until width) {
                val yVal = (yBuffer.get(yRowStart + x * yPixelStride).toInt() and 0xFF) - 16
                val uvColOffset = (x shr 1) * uPixelStride
                val vColOffset = (x shr 1) * vPixelStride
                val uVal = (uBuffer.get(uRowStart + uvColOffset).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(vRowStart + vColOffset).toInt() and 0xFF) - 128

                val y298 = 298 * (if (yVal < 0) 0 else yVal)
                val r = ((y298 + 409 * vVal + 128) shr 8).coerceIn(0, 255)
                val g = ((y298 - 100 * uVal - 208 * vVal + 128) shr 8).coerceIn(0, 255)
                val b = ((y298 + 516 * uVal + 128) shr 8).coerceIn(0, 255)

                outPixels[outIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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

    companion object {
        private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1
            )
        )
    }
}
