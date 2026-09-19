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
    private var currentZoom = 1.0f
    private var targetZoom = 1.0f
    private var minZoom = 1.0f
    private var maxZoom = 10.0f

    // Dynamic Silent Auto-Calibration (adapts behind the scenes to any hand size)
    private var minCalibratedSpan = 0.22f
    private var maxCalibratedSpan = 0.70f

    // Proportional Finger Tracking Anchors
    private var anchorSpan = 0.40f
    private var anchorZoom = 1.0f
    private var lastSpan = 0.40f
    private var smoothedSpan = 0.40f
    private var stationaryHoldTicks = 0

    private var lastFeedbackTimeMs = 0L
    private var lastPinchReleaseTimeMs = 0L

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

    fun setZoomLimits(min: Float, max: Float) {
        minZoom = min
        maxZoom = max
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

        var isOpenPalmGesture = false
        if (gestures.isNotEmpty()) {
            for (gestureList in gestures) {
                for (category in gestureList) {
                    if (category.categoryName().equals("Open_Palm", ignoreCase = true) && category.score() > 0.65f) {
                        isOpenPalmGesture = true
                        break
                    }
                }
                if (isOpenPalmGesture) break
            }
        }

        if (landmarks.isNotEmpty()) {
            val hand = landmarks[0]
            if (hand.size >= 18) {
                val wrist = hand[0]
                val indexMcp = hand[5]
                val middleMcp = hand[9]
                val pinkyMcp = hand[17]
                val thumbTip = hand[4]
                val indexTip = hand[8]

                // Anatomical Palm-Scale Invariant Vector
                val palmLength = hypot(wrist.x() - middleMcp.x(), wrist.y() - middleMcp.y())
                val palmWidth = hypot(indexMcp.x() - pinkyMcp.x(), indexMcp.y() - pinkyMcp.y())
                val palmSize = maxOf(palmLength, palmWidth).coerceAtLeast(0.04f)

                val rawSpan = hypot(thumbTip.x() - indexTip.x(), thumbTip.y() - indexTip.y()) / palmSize

                // 1. Silent Behind-The-Scenes Auto-Calibration:
                // Silently adapt to user's individual finger length and pinch style
                if (rawSpan < minCalibratedSpan && rawSpan > 0.05f) {
                    minCalibratedSpan = minCalibratedSpan * 0.85f + rawSpan * 0.15f
                }
                if (rawSpan > maxCalibratedSpan && rawSpan < 1.10f && !isOpenPalmGesture) {
                    maxCalibratedSpan = maxCalibratedSpan * 0.85f + rawSpan * 0.15f
                }

                // Smooth incoming landmark data with responsive low-pass filter (anti-jitter)
                smoothedSpan = if (smoothedSpan == 0f) rawSpan else (smoothedSpan * 0.60f + rawSpan * 0.40f)

                // 2. Pinch Release Condition:
                // Cleanly lock zoom when open palm is presented or fingers spread wide past calibrated envelope
                val exitThreshold = (maxCalibratedSpan * 0.90f).coerceAtLeast(0.75f)
                if (isPinching && (isOpenPalmGesture || smoothedSpan > exitThreshold)) {
                    isPinching = false
                    lastPinchReleaseTimeMs = now
                    val lockMsg = String.format("Zoom %.1f× (locked)", currentZoom)
                    onGestureFeedback(lockMsg)
                    Logger.log("GESTURE_AI", "Pinch released. Zoom locked at ${String.format("%.1f", currentZoom)}x")
                    return
                }

                // 3. Pinch Engagement Condition:
                // Engage when fingers come into proximity (< 65% of calibrated range)
                val entryThreshold = minCalibratedSpan + (maxCalibratedSpan - minCalibratedSpan) * 0.65f
                if (!isPinching && !isOpenPalmGesture && smoothedSpan < entryThreshold) {
                    if (now - lastPinchReleaseTimeMs > 350L) {
                        isPinching = true
                        anchorSpan = smoothedSpan
                        lastSpan = smoothedSpan
                        anchorZoom = currentZoom
                        targetZoom = currentZoom
                        stationaryHoldTicks = 0
                        lastFeedbackTimeMs = now
                        onGestureFeedback("Pinch zoom active")
                        Logger.log("GESTURE_AI", "Pinch engaged: anchorSpan=${String.format("%.2f", anchorSpan)}, zoom=${String.format("%.1f", anchorZoom)}x")
                    }
                }

                // 4. Proportional Direct Finger-Paced Tracking:
                if (isPinching) {
                    // Motion velocity relative to last frame
                    val frameVelocity = lastSpan - smoothedSpan
                    lastSpan = smoothedSpan

                    // Displacement from the pinch anchor point
                    val displacement = anchorSpan - smoothedSpan

                    // Dynamic calibration range for linear mapping
                    val usableRange = (maxCalibratedSpan - minCalibratedSpan).coerceAtLeast(0.25f)

                    // Sensitivity: A comfortable finger stroke produces ~1.5x zoom
                    val sensitivity = 3.0f

                    // Target zoom directly follows the finger displacement:
                    // Fingers moving together (displacement > 0) -> Zoom IN
                    // Fingers moving apart (displacement < 0) -> Zoom OUT
                    val desiredZoom = (anchorZoom + (displacement / usableRange) * sensitivity).coerceIn(minZoom, maxZoom)

                    // Moving away from touching check:
                    // If user is actively spreading fingers (frameVelocity < -0.008f), reset hold
                    if (frameVelocity < -0.008f) {
                        stationaryHoldTicks = 0
                    } else if (kotlin.math.abs(frameVelocity) < 0.008f) {
                        // Fingers held stationary
                        if (smoothedSpan <= minCalibratedSpan + 0.04f) {
                            // Only if fingers are held firmly touching for > 400ms: gentle continuous cruise in
                            stationaryHoldTicks++
                            if (stationaryHoldTicks >= 4) {
                                val creepZoom = (desiredZoom + 0.02f).coerceIn(minZoom, maxZoom)
                                if (creepZoom != desiredZoom) {
                                    anchorZoom = creepZoom
                                    anchorSpan = smoothedSpan
                                }
                            }
                        } else {
                            stationaryHoldTicks = 0
                        }
                    } else {
                        stationaryHoldTicks = 0
                    }

                    // Smooth exponential transition towards desired zoom (buttery smooth glide)
                    val zoomStep = (desiredZoom - currentZoom) * 0.45f
                    if (kotlin.math.abs(zoomStep) > 0.006f) {
                        currentZoom = (currentZoom + zoomStep).coerceIn(minZoom, maxZoom)
                        onPinchZoom(currentZoom)

                        val label = if (frameVelocity > 0.008f) {
                            String.format("Pinching in • Zoom %.1f×", currentZoom)
                        } else if (frameVelocity < -0.008f) {
                            String.format("Pinching out • Zoom %.1f×", currentZoom)
                        } else {
                            String.format("Zoom %.1f×", currentZoom)
                        }

                        if (now - lastFeedbackTimeMs > 180L) {
                            lastFeedbackTimeMs = now
                            onGestureFeedback(label)
                        }
                    }
                    return
                }

                // 5. Open Palm Tracking Toggle:
                // Only allowed when NOT pinching, outside release cooldown, and clearly open
                if (isOpenPalmGesture && !isPinching && (now - lastPinchReleaseTimeMs > 1000L)) {
                    if (now - lastToggleTimeMs > TOGGLE_COOLDOWN_MS && smoothedSpan > 0.70f) {
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
        } else {
            // Hand left camera frame
            if (isPinching) {
                isPinching = false
                lastPinchReleaseTimeMs = now
                val lockMsg = String.format("Zoom %.1f× (locked)", currentZoom)
                onGestureFeedback(lockMsg)
                Logger.log("GESTURE_AI", "Hand lost: Zoom locked at ${String.format("%.1f", currentZoom)}x")
            }
        }
    }

    fun close() {
        gestureRecognizer?.close()
        gestureRecognizer = null
        isInitialized = false
    }
}
