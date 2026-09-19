package com.darusc.vcamdroid.ai

import android.content.Context
import android.graphics.Bitmap
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
    private var trackingActive = true
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
                            val statusMsg = if (trackingActive) "🖐️ AI Tracking RESUMED" else "🖐️ AI Tracking PAUSED"
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
                    onGestureFeedback("👌 Pinch Zoom Engaged")
                } else if (isPinching) {
                    if (dist > PINCH_EXIT_THRESHOLD) {
                        isPinching = false
                        onGestureFeedback("👌 Pinch Zoom Released")
                    } else {
                        // Delta distance scales zoom: spreading fingers apart zooms in, squeezing zooms out
                        val delta = (dist - initialPinchDistance) * 8.0f
                        val newZoom = (baseZoomOnPinch + delta).coerceIn(1.0f, 4.0f)
                        onPinchZoom(newZoom)
                        val formattedZoom = String.format("%.1fx", newZoom)
                        onGestureFeedback("👌 Zoom: $formattedZoom")
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
