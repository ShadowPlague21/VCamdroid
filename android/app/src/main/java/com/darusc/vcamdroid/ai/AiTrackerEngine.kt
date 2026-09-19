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

    enum class FramingMode {
        LANDSCAPE_16_9,
        PORTRAIT_9_16
    }

    private var framingMode = FramingMode.LANDSCAPE_16_9

    private var faceDetector: FaceDetector? = null
    private var isFaceDetectorBusy = false
    private var lastInferenceTimeMs = 0L
    private val FACE_INFERENCE_INTERVAL_MS = 66L // ~15 Hz background inference

    // Sensor Geometry
    private var sensorRect: Rect = Rect(0, 0, 4608, 3456)

    // Dynamic Gimbal State
    private var isTrackingEnabled = true
    private var userZoomFactor = 1.25f // Default 1.25x gives ideal headroom & panning margin

    private val targetCropRect = RectF()
    private val currentCropRect = RectF()

    // Smooth Damping Parameters
    private val SMOOTHING_ALPHA = 0.12f // Critically damped motion (motorized gimbal feel)
    private val DEADBAND_PIXELS = 45f   // Filter out small breathing micro-jitters

    private var consecutiveNoFaceFrames = 0
    private val MAX_NO_FACE_HOLD_FRAMES = 30

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
                .setMinFaceSize(0.10f)
                .build()

            faceDetector = FaceDetection.getClient(options)
            Logger.log("AI_TRACKER", "BlazeFace FaceDetector initialized (Fast mode)")
        } catch (e: Exception) {
            Logger.log("AI_TRACKER", "Failed to initialize FaceDetector: ${e.message}")
        }
    }

    fun setSensorActiveArray(rect: Rect) {
        sensorRect = Rect(rect)
        resetToDefaultCrop()
    }

    fun setFramingMode(mode: FramingMode) {
        framingMode = mode
        resetToDefaultCrop()
        Logger.log("AI_TRACKER", "Framing mode switched to: $mode")
    }

    fun getFramingMode(): FramingMode = framingMode

    fun setTrackingEnabled(enabled: Boolean) {
        isTrackingEnabled = enabled
        if (!enabled) {
            resetToDefaultCrop()
        }
        Logger.log("AI_TRACKER", "AI Tracking enabled: $enabled")
    }

    fun isTrackingEnabled(): Boolean = isTrackingEnabled

    fun setUserZoom(zoom: Float) {
        userZoomFactor = zoom.coerceIn(1.0f, 4.0f)
    }

    fun getUserZoom(): Float = userZoomFactor

    fun resetToDefaultCrop() {
        val sW = sensorRect.width().toFloat()
        val sH = sensorRect.height().toFloat()

        val cropW: Float
        val cropH: Float

        when (framingMode) {
            FramingMode.LANDSCAPE_16_9 -> {
                cropW = sW / userZoomFactor
                cropH = cropW * (9f / 16f)
            }
            FramingMode.PORTRAIT_9_16 -> {
                cropH = sH / userZoomFactor
                cropW = cropH * (9f / 16f)
            }
        }

        val left = sensorRect.left + (sW - cropW) / 2f
        val top = sensorRect.top + (sH - cropH) / 2f
        targetCropRect.set(left, top, left + cropW, top + cropH)
        currentCropRect.set(targetCropRect)

        onCropRegionChanged(toSensorRect(currentCropRect))
    }

    /**
     * Called on each incoming camera analysis frame (asynchronous background thread)
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
            val imgW = if (rotationDegrees == 90 || rotationDegrees == 270) inputImage.height else inputImage.width
            val imgH = if (rotationDegrees == 90 || rotationDegrees == 270) inputImage.width else inputImage.height

            faceDetector?.process(inputImage)
                ?.addOnSuccessListener { faces ->
                    handleFaceResults(faces, imgW, imgH)
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
     * Compute new target crop coordinates from detected face
     */
    private fun handleFaceResults(faces: List<Face>, imgW: Int, imgH: Int) {
        if (faces.isEmpty()) {
            consecutiveNoFaceFrames++
            if (consecutiveNoFaceFrames > MAX_NO_FACE_HOLD_FRAMES) {
                // Return to centered view when subject leaves the frame
                val sW = sensorRect.width().toFloat()
                val sH = sensorRect.height().toFloat()
                val cropW = currentCropRect.width()
                val cropH = currentCropRect.height()
                targetCropRect.set(
                    sensorRect.left + (sW - cropW) / 2f,
                    sensorRect.top + (sH - cropH) / 2f,
                    sensorRect.left + (sW + cropW) / 2f,
                    sensorRect.top + (sH + cropH) / 2f
                )
            }
            onFaceDetected(false, null)
            return
        }

        consecutiveNoFaceFrames = 0

        // Select the primary face (highest area)
        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        val box = primaryFace.boundingBox

        val normFaceX = (box.centerX().toFloat() / imgW).coerceIn(0f, 1f)
        val normFaceY = (box.centerY().toFloat() / imgH).coerceIn(0f, 1f)
        val normFaceW = box.width().toFloat() / imgW
        val normFaceH = box.height().toFloat() / imgH

        onFaceDetected(true, RectF(normFaceX - normFaceW/2, normFaceY - normFaceH/2, normFaceX + normFaceW/2, normFaceY + normFaceH/2))

        // Map normalized face center to physical active sensor coordinate
        val sensorFaceX = sensorRect.left + normFaceX * sensorRect.width()
        val sensorFaceY = sensorRect.top + normFaceY * sensorRect.height()

        val sW = sensorRect.width().toFloat()
        val sH = sensorRect.height().toFloat()

        // Calculate crop size based on user zoom and aspect ratio
        val cropW: Float
        val cropH: Float

        when (framingMode) {
            FramingMode.LANDSCAPE_16_9 -> {
                cropW = (sW / userZoomFactor).coerceIn(1920f, sW)
                cropH = cropW * (9f / 16f)
            }
            FramingMode.PORTRAIT_9_16 -> {
                cropH = (sH / userZoomFactor).coerceIn(1920f, sH)
                cropW = cropH * (9f / 16f)
            }
        }

        // Rule-of-Thirds Headroom: position face at 38% from the top of the cropped frame
        var targetLeft = sensorFaceX - cropW / 2f
        var targetTop = sensorFaceY - cropH * 0.38f

        // Clamp to sensor bounds
        if (targetLeft < sensorRect.left) targetLeft = sensorRect.left.toFloat()
        if (targetLeft + cropW > sensorRect.right) targetLeft = sensorRect.right - cropW
        if (targetTop < sensorRect.top) targetTop = sensorRect.top.toFloat()
        if (targetTop + cropH > sensorRect.bottom) targetTop = sensorRect.bottom - cropH

        // Apply deadband
        val dx = abs(targetLeft - currentCropRect.left)
        val dy = abs(targetTop - currentCropRect.top)

        if (dx > DEADBAND_PIXELS || dy > DEADBAND_PIXELS) {
            targetCropRect.set(targetLeft, targetTop, targetLeft + cropW, targetTop + cropH)
        }
    }

    /**
     * Executes the smooth damping interpolation filter (called at 60 FPS / render loop)
     */
    fun updateSmoothingStep(): Boolean {
        val prevLeft = currentCropRect.left
        val prevTop = currentCropRect.top
        val prevW = currentCropRect.width()
        val prevH = currentCropRect.height()

        currentCropRect.left += (targetCropRect.left - currentCropRect.left) * SMOOTHING_ALPHA
        currentCropRect.top += (targetCropRect.top - currentCropRect.top) * SMOOTHING_ALPHA
        currentCropRect.right += (targetCropRect.right - currentCropRect.right) * SMOOTHING_ALPHA
        currentCropRect.bottom += (targetCropRect.bottom - currentCropRect.bottom) * SMOOTHING_ALPHA

        val changed = abs(currentCropRect.left - prevLeft) > 1.0f ||
                      abs(currentCropRect.top - prevTop) > 1.0f ||
                      abs(currentCropRect.width() - prevW) > 1.0f ||
                      abs(currentCropRect.height() - prevH) > 1.0f

        if (changed) {
            onCropRegionChanged(toSensorRect(currentCropRect))
        }

        return changed
    }

    fun getCurrentCropRegion(): Rect = toSensorRect(currentCropRect)

    private fun toSensorRect(rf: RectF): Rect {
        val l = rf.left.roundToInt().coerceIn(sensorRect.left, sensorRect.right)
        val t = rf.top.roundToInt().coerceIn(sensorRect.top, sensorRect.bottom)
        val r = rf.right.roundToInt().coerceIn(l, sensorRect.right)
        val b = rf.bottom.roundToInt().coerceIn(t, sensorRect.bottom)
        return Rect(l, t, r, b)
    }

    fun close() {
        faceDetector?.close()
        faceDetector = null
    }
}
