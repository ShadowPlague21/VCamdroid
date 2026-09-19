package com.darusc.vcamdroid.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import android.os.SystemClock
import com.darusc.vcamdroid.util.Logger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs
import kotlin.math.roundToInt

class AiTrackerEngine(
    private val context: Context,
    private val onCropRegionChanged: (Rect) -> Unit,
    private val onFaceDetected: (hasFace: Boolean, faceBounds: RectF?) -> Unit
) {

    private var faceDetector: FaceDetector? = null
    private var isFaceDetectorBusy = false
    private var lastInferenceTimeMs = 0L
    private val FACE_INFERENCE_INTERVAL_MS = 80L // ~12 Hz inference is plenty and saves CPU

    // Sensor Geometry - FAANG: no hardcoded device values, real HAL value injected via setSensorActiveArray()
    // Default is generic 4:3 placeholder, immediately overwritten by CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
    private var sensorRect: Rect = Rect(0, 0, 4032, 3024) // generic 12MP placeholder, NOT Realme-specific

    // Dynamic Gimbal State
    private var isTrackingEnabled = false
    private var isHardwareZoomActive = false
    // Default 1.0x zoom follows the HUD; crop aspect matches the stream
    private var userZoomFactor = 1.0f
    private var streamWidth = 1920
    private var streamHeight = 1080

    // Target and Smoothed Crop coordinates - centered on generic placeholder, reset when real sensor arrives
    private var targetCenterX = 2016f
    private var targetCenterY = 1512f
    private var currentCenterX = 2016f
    private var currentCenterY = 1512f

    // SmoothDamp physics state for Bezier-like ease-in and ease-out
    private var velocityX = 0f
    private var velocityY = 0f
    // 0.65s smooth time keeps servoing calm so it doesn't fight the crop
    private val SMOOTH_TIME = 0.65f
    private val MAX_SPEED = 2200f // Max pixels per second

    // Filtered face positions to remove ML detection jitter
    private var filteredFaceX = 0.5f
    private var filteredFaceY = 0.5f
    private var hasValidFaceFilter = false

    // Deadzone (normalized 0.0 - 1.0 fraction of crop window)
    // While the face is inside this inner box, the camera is 100% stationary
    private val DEADZONE_X_FRACTION = 0.20f
    private val DEADZONE_Y_FRACTION = 0.18f

    private var consecutiveNoFaceFrames = 0
    private val MAX_NO_FACE_HOLD_FRAMES = 40

    // Spatial change threshold to avoid micro-updates that cause AE flicker
    private var lastEmittedCropRect = Rect()
    private val MIN_PIXEL_CHANGE_THRESHOLD = 28

    init {
        initFaceDetector()
        resetToDefaultCrop()
    }

    private fun initFaceDetector() {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.12f)
                .build()

            faceDetector = FaceDetection.getClient(options)
            Logger.log("AI_TRACKER", "BlazeFace FaceDetector initialized (SmoothDamp mode)")
        } catch (e: Exception) {
            Logger.log("AI_TRACKER", "Failed to initialize FaceDetector: ${e.message}")
        }
    }

    fun setSensorActiveArray(rect: Rect) {
        sensorRect = Rect(rect)
        resetToDefaultCrop()
    }

    fun setStreamSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (streamWidth == width && streamHeight == height) return
        streamWidth = width
        streamHeight = height
        resetToDefaultCrop()
    }

    fun setTrackingEnabled(enabled: Boolean) {
        isTrackingEnabled = enabled
        if (!enabled) {
            resetToDefaultCrop()
        }
        Logger.log("AI_TRACKER", "AI Tracking enabled: $enabled")
    }

    fun isTrackingEnabled(): Boolean = isTrackingEnabled

    fun setUserZoom(zoom: Float) {
        userZoomFactor = zoom.coerceIn(1.0f, 8.0f)
    }

    fun setHardwareZoomActive(active: Boolean) {
        isHardwareZoomActive = active
    }

    fun getUserZoom(): Float = userZoomFactor

    fun resetToDefaultCrop() {
        targetCenterX = sensorRect.exactCenterX()
        targetCenterY = sensorRect.exactCenterY()
        currentCenterX = targetCenterX
        currentCenterY = targetCenterY
        velocityX = 0f
        velocityY = 0f
        hasValidFaceFilter = false

        val rect = computeCropRect(currentCenterX, currentCenterY)
        lastEmittedCropRect = rect
        onCropRegionChanged(rect)
    }

    fun isReadyForInference(now: Long): Boolean {
        return isTrackingEnabled && !isFaceDetectorBusy && (now - lastInferenceTimeMs >= FACE_INFERENCE_INTERVAL_MS)
    }

    /**
     * Process already-converted in-memory bitmap - zero hardware camera buffer lock time!
     */
    fun processBitmap(bitmap: Bitmap, rotationDegrees: Int) {
        if (!isTrackingEnabled || isFaceDetectorBusy) return

        val now = SystemClock.uptimeMillis()
        lastInferenceTimeMs = now
        isFaceDetectorBusy = true

        try {
            val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
            val imgW = inputImage.width.toFloat()
            val imgH = inputImage.height.toFloat()

            faceDetector?.process(inputImage)
                ?.addOnSuccessListener { faces ->
                    handleFaceResults(faces, imgW, imgH, rotationDegrees)
                }
                ?.addOnFailureListener { e ->
                    Logger.log("AI_TRACKER", "Face detection error: ${e.message}")
                }
                ?.addOnCompleteListener {
                    isFaceDetectorBusy = false
                }
        } catch (e: Exception) {
            isFaceDetectorBusy = false
            Logger.log("AI_TRACKER", "processBitmap error: ${e.message}")
        }
    }

    /**
     * Called on each incoming camera analysis frame
     */
    fun processImage(image: Image, rotationDegrees: Int) {
        if (!isTrackingEnabled || isFaceDetectorBusy) {
            image.close()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInferenceTimeMs < FACE_INFERENCE_INTERVAL_MS) {
            image.close()
            return
        }
        lastInferenceTimeMs = now
        isFaceDetectorBusy = true

        try {
            val inputImage = InputImage.fromMediaImage(image, rotationDegrees)
            val imgW = inputImage.width.toFloat()
            val imgH = inputImage.height.toFloat()

            faceDetector?.process(inputImage)
                ?.addOnSuccessListener { faces ->
                    handleFaceResults(faces, imgW, imgH, rotationDegrees)
                }
                ?.addOnFailureListener { e ->
                    Logger.log("AI_TRACKER", "Face detection error: ${e.message}")
                }
                ?.addOnCompleteListener {
                    isFaceDetectorBusy = false
                    try {
                        image.close()
                    } catch (_: Exception) { }
                }
        } catch (e: Exception) {
            isFaceDetectorBusy = false
            try {
                image.close()
            } catch (_: Exception) { }
        }
    }

    /**
     * Compute new target crop coordinates from detected face using visual servoing
     */
    private fun handleFaceResults(faces: List<Face>, imgW: Float, imgH: Float, rotationDegrees: Int) {
        if (faces.isEmpty()) {
            consecutiveNoFaceFrames++
            if (consecutiveNoFaceFrames > MAX_NO_FACE_HOLD_FRAMES) {
                // Smoothly drift back to center position when no one is in frame
                targetCenterX = sensorRect.exactCenterX()
                targetCenterY = sensorRect.exactCenterY()
                hasValidFaceFilter = false
            }
            onFaceDetected(false, null)
            return
        }

        consecutiveNoFaceFrames = 0

        // Select the primary face (largest bounding box)
        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        val box = primaryFace.boundingBox

        val rawNormX = (box.centerX().toFloat() / imgW).coerceIn(0f, 1f)
        val rawNormY = (box.centerY().toFloat() / imgH).coerceIn(0f, 1f)
        val rawNormW = box.width().toFloat() / imgW
        val rawNormH = box.height().toFloat() / imgH

        // Low-pass filter to strip raw bounding box jitter
        if (!hasValidFaceFilter) {
            filteredFaceX = rawNormX
            filteredFaceY = rawNormY
            hasValidFaceFilter = true
        } else {
            val filterAlpha = 0.30f // Temporal smoothing on raw face coordinates
            filteredFaceX += (rawNormX - filteredFaceX) * filterAlpha
            filteredFaceY += (rawNormY - filteredFaceY) * filterAlpha
        }

        // Notify UI preview with filtered face position in display coordinates
        onFaceDetected(true, RectF(
            filteredFaceX - rawNormW / 2,
            filteredFaceY - rawNormH / 2,
            filteredFaceX + rawNormW / 2,
            filteredFaceY + rawNormH / 2
        ))

        // Convert face normalized coordinates from ML Kit upright orientation back to sensor space
        val (normXSensor, normYSensor) = when (rotationDegrees) {
            90 -> Pair(filteredFaceY, 1f - filteredFaceX)
            180 -> Pair(1f - filteredFaceX, 1f - filteredFaceY)
            270 -> Pair(1f - filteredFaceY, filteredFaceX)
            else -> Pair(filteredFaceX, filteredFaceY)
        }

        val (cropW, cropH) = getCropDimensions()

        // Desired position of face inside the crop:
        // Horizontal: 50% (dead center)
        // Vertical: 40% (cinematic rule-of-thirds headroom)
        val desiredNormX = 0.50f
        val desiredNormY = 0.40f

        val errorX = normXSensor - desiredNormX
        val errorY = normYSensor - desiredNormY

        // Deadzone check: If face is within the inner deadzone, don't move the camera
        if (abs(errorX) > DEADZONE_X_FRACTION) {
            val deltaX = if (errorX > 0) errorX - DEADZONE_X_FRACTION else errorX + DEADZONE_X_FRACTION
            targetCenterX = currentCenterX + deltaX * cropW
        }
        if (abs(errorY) > DEADZONE_Y_FRACTION) {
            val deltaY = if (errorY > 0) errorY - DEADZONE_Y_FRACTION else errorY + DEADZONE_Y_FRACTION
            targetCenterY = currentCenterY + deltaY * cropH
        }

        // Clamp target center to ensure crop box remains within physical sensor
        val halfW = cropW / 2f
        val halfH = cropH / 2f
        targetCenterX = targetCenterX.coerceIn(sensorRect.left + halfW, sensorRect.right - halfW)
        targetCenterY = targetCenterY.coerceIn(sensorRect.top + halfH, sensorRect.bottom - halfH)
    }

    /**
     * Executes the SmoothDamp (Critically Damped Harmonic Oscillator) Bezier curve step
     * Called at 60 FPS / render loop (every 16ms)
     * Returns true if position changed significantly enough to warrant updating camera HAL
     */
    fun updateSmoothingStep(deltaTimeSec: Float = 0.016f): Boolean {
        // SmoothDamp equation: critically damped spring with smooth ease-in & ease-out
        currentCenterX = smoothDamp(currentCenterX, targetCenterX, ::velocityX, SMOOTH_TIME, MAX_SPEED, deltaTimeSec)
        currentCenterY = smoothDamp(currentCenterY, targetCenterY, ::velocityY, SMOOTH_TIME, MAX_SPEED, deltaTimeSec)

        val newRect = computeCropRect(currentCenterX, currentCenterY)

        val deltaL = abs(newRect.left - lastEmittedCropRect.left)
        val deltaT = abs(newRect.top - lastEmittedCropRect.top)
        val deltaR = abs(newRect.right - lastEmittedCropRect.right)
        val deltaB = abs(newRect.bottom - lastEmittedCropRect.bottom)

        // Only update camera HAL when crop moved by more than MIN_PIXEL_CHANGE_THRESHOLD
        // This stops the constant setRepeatingRequest() spam that resets AE and causes flickering!
        val significantChange = deltaL >= MIN_PIXEL_CHANGE_THRESHOLD ||
                                deltaT >= MIN_PIXEL_CHANGE_THRESHOLD ||
                                deltaR >= MIN_PIXEL_CHANGE_THRESHOLD ||
                                deltaB >= MIN_PIXEL_CHANGE_THRESHOLD

        if (significantChange) {
            lastEmittedCropRect = newRect
            onCropRegionChanged(newRect)
            return true
        }

        return false
    }

    private fun getCropDimensions(): Pair<Float, Float> {
        val sW = sensorRect.width().toFloat()
        val sH = sensorRect.height().toFloat()
        // When hardware CONTROL_ZOOM_RATIO is active, do not compound digital zoom into crop rect
        val zoom = if (isHardwareZoomActive) 1.0f else userZoomFactor.coerceAtLeast(1.0f)
        val targetAspect = streamWidth.toFloat() / streamHeight.toFloat()
        val sensorAspect = sW / sH
        val cropW: Float
        val cropH: Float
        if (sensorAspect >= targetAspect) {
            cropH = (sH / zoom).coerceAtMost(sH)
            cropW = (cropH * targetAspect).coerceAtMost(sW)
        } else {
            cropW = (sW / zoom).coerceAtMost(sW)
            cropH = (cropW / targetAspect).coerceAtMost(sH)
        }
        return Pair(cropW.coerceAtLeast(1f), cropH.coerceAtLeast(1f))
    }

    private fun computeCropRect(centerX: Float, centerY: Float): Rect {
        val (cropW, cropH) = getCropDimensions()
        val halfW = cropW / 2f
        val halfH = cropH / 2f

        val cl = (centerX - halfW).coerceIn(sensorRect.left.toFloat(), (sensorRect.right - cropW).toFloat())
        val ct = (centerY - halfH).coerceIn(sensorRect.top.toFloat(), (sensorRect.bottom - cropH).toFloat())

        return Rect(cl.roundToInt(), ct.roundToInt(), (cl + cropW).roundToInt(), (ct + cropH).roundToInt())
    }

    fun getCurrentCropRegion(): Rect = computeCropRect(currentCenterX, currentCenterY)

    /**
     * Unity / Game Programming Gems SmoothDamp implementation
     * Produces buttery-smooth Bezier S-curve acceleration and deceleration with zero overshoot
     */
    private fun smoothDamp(
        current: Float,
        target: Float,
        velocityRef: kotlin.reflect.KMutableProperty0<Float>,
        smoothTime: Float,
        maxSpeed: Float,
        deltaTime: Float
    ): Float {
        val st = smoothTime.coerceAtLeast(0.0001f)
        val omega = 2f / st
        val x = omega * deltaTime
        val exp = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        var change = current - target
        val originalTo = target

        val maxChange = maxSpeed * st
        change = change.coerceIn(-maxChange, maxChange)
        val clampedTarget = current - change

        var vel = velocityRef.get()
        val temp = (vel + omega * change) * deltaTime
        vel = (vel - omega * temp) * exp
        var output = clampedTarget + (change + temp) * exp

        // Prevent overshooting
        if ((originalTo - current > 0f) == (output > originalTo)) {
            output = originalTo
            vel = 0f
        }

        velocityRef.set(vel)
        return output
    }

    fun close() {
        faceDetector?.close()
        faceDetector = null
    }
}
