package com.darusc.vcamdroid.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import com.darusc.vcamdroid.util.Logger
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlin.math.hypot

class GestureDetectorHelper(
    private val context: Context,
    private val onTrackingToggled: (Boolean) -> Unit,
    private val onPinchZoom: (Float) -> Unit,
    private val onGestureFeedback: (String) -> Unit
) {

    private var gestureRecognizer: GestureRecognizer? = null
    private var isInitialized = false

    private var lastInferenceTimeMs = 0L
    private val INFERENCE_INTERVAL_MS = 100L // 10 Hz maximum for ultra-low CPU load

    // Pinch state memory
    private var isPinching = false
    private var initialPinchDistance = 0f
    private var baseZoomOnPinch = 1.0f
    private var currentZoom = 1.0f

    // Tracking state
    private var trackingActive = false
    private var lastToggleTimeMs = 0L
    private val TOGGLE_COOLDOWN_MS = 1200L // Prevent flickering toggle

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("gesture_recognizer.task")
                .setDelegate(Delegate.GPU) // Hardware GPU acceleration on Adreno 618
                .build()

            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.55f)
                .setMinHandPresenceConfidence(0.55f)
                .setMinTrackingConfidence(0.55f)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            gestureRecognizer = GestureRecognizer.createFromOptions(context, options)
            isInitialized = true
            Logger.log("GESTURE_AI", "MediaPipe GestureRecognizer initialized on GPU")
        } catch (e: Exception) {
            Logger.log("GESTURE_AI", "GPU init failed, falling back to CPU: ${e.message}")
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("gesture_recognizer.task")
                    .setDelegate(Delegate.CPU)
                    .build()

                val options = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.55f)
                    .setMinHandPresenceConfidence(0.55f)
                    .setMinTrackingConfidence(0.55f)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()

                gestureRecognizer = GestureRecognizer.createFromOptions(context, options)
                isInitialized = true
                Logger.log("GESTURE_AI", "MediaPipe GestureRecognizer initialized on CPU")
            } catch (ex: Exception) {
                Logger.log("GESTURE_AI", "GestureRecognizer init failed completely: ${ex.message}")
            }
        }
    }

    fun updateCurrentZoom(zoom: Float) {
        currentZoom = zoom
    }

    fun setTrackingActive(active: Boolean) {
        trackingActive = active
    }

    fun isTrackingActive(): Boolean = trackingActive

    private var isBusy = false

    fun isReadyForInference(now: Long): Boolean {
        return isInitialized && !isBusy && (now - lastInferenceTimeMs >= INFERENCE_INTERVAL_MS)
    }

    /**
     * Process already-converted in-memory bitmap - zero hardware buffer lock time!
     */
    fun processBitmap(bitmap: Bitmap, rotationDegrees: Int = 90) {
        if (!isInitialized || gestureRecognizer == null || isBusy) return
        isBusy = true
        val now = SystemClock.uptimeMillis()
        lastInferenceTimeMs = now

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val imageProcessingOptions = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()
            val result: GestureRecognizerResult = gestureRecognizer?.recognize(mpImage, imageProcessingOptions) ?: return

            handleGestureResult(result, now)
        } catch (e: Exception) {
            Logger.log("GESTURE_AI", "Recognition error: ${e.message}")
        } finally {
            isBusy = false
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

    /**
     * Process downscaled thumbnail bitmap (e.g. 256x256 or 320x240) asynchronously
     */
    fun processThumbnail(bitmap: Bitmap) {
        if (!isInitialized || gestureRecognizer == null) return

        val now = SystemClock.uptimeMillis()
        if (now - lastInferenceTimeMs < INFERENCE_INTERVAL_MS) {
            return
        }
        lastInferenceTimeMs = now

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: GestureRecognizerResult = gestureRecognizer?.recognize(mpImage) ?: return

            handleGestureResult(result, now)
        } catch (e: Exception) {
            Logger.log("GESTURE_AI", "Recognition error: ${e.message}")
        }
    }

    private fun handleGestureResult(result: GestureRecognizerResult, now: Long) {
        val gestures = result.gestures()
        val landmarks = result.landmarks()

        // 1. Check for Palm Gesture (🖐️) to toggle tracking
        if (gestures.isNotEmpty()) {
            for (gestureList in gestures) {
                for (category in gestureList) {
                    val name = category.categoryName()
                    val score = category.score()

                    if (name.equals("Open_Palm", ignoreCase = true) && score > 0.65f) {
                        if (now - lastToggleTimeMs > TOGGLE_COOLDOWN_MS) {
                            lastToggleTimeMs = now
                            trackingActive = !trackingActive
                            onTrackingToggled(trackingActive)
                            val statusMsg = if (trackingActive) "AI tracking on" else "AI tracking off"
                            onGestureFeedback(statusMsg)
                            Logger.log("GESTURE_AI", statusMsg)
                            return
                        }
                    }
                }
            }
        }

        // 2. Check for Pinch-to-Zoom Gesture (👌) using 3D Hand Landmarks
        if (landmarks.isNotEmpty()) {
            val hand = landmarks[0]
            if (hand.size >= 9) {
                val thumbTip = hand[4]
                val indexTip = hand[8]

                val dist = hypot(thumbTip.x() - indexTip.x(), thumbTip.y() - indexTip.y())

                // Normalized pinch distance threshold: < 0.07 means thumb and index are pinched
                val PINCH_ENTER_THRESHOLD = 0.065f
                val PINCH_EXIT_THRESHOLD = 0.12f

                if (!isPinching && dist < PINCH_ENTER_THRESHOLD) {
                    isPinching = true
                    initialPinchDistance = dist
                    baseZoomOnPinch = currentZoom
                    onGestureFeedback("Pinch zoom")
                } else if (isPinching) {
                    if (dist > PINCH_EXIT_THRESHOLD) {
                        isPinching = false
                        onGestureFeedback("Pinch released")
                    } else {
                        val delta = (dist - initialPinchDistance) * 8.0f
                        val newZoom = (baseZoomOnPinch + delta).coerceIn(1.0f, 4.0f)
                        onPinchZoom(newZoom)
                        val formattedZoom = String.format("%.1f×", newZoom)
                        onGestureFeedback("Zoom $formattedZoom")
                    }
                }
            }
        } else {
            if (isPinching) {
                isPinching = false
            }
        }
    }

    fun close() {
        gestureRecognizer?.close()
        gestureRecognizer = null
        isInitialized = false
    }
}
