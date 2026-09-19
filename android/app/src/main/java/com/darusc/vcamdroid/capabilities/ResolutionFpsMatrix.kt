package com.darusc.vcamdroid.capabilities

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.util.Size
import com.darusc.vcamdroid.util.Logger

/**
 * Intelligent Resolution <-> FPS Capability Matrix.
 * 
 * Computes exact FPS ceilings per resolution using Camera2 StreamConfigurationMap,
 * AE FPS ranges, and hardware video encoder constraints.
 * 
 * Implements intelligent downshifting (e.g. 4K -> 1440p/1080p) when unsupported
 * FPS options are selected.
 */
object ResolutionFpsMatrix {

    data class ResolutionInfo(
        val width: Int,
        val height: Int,
        val label: String
    )

    val STANDARD_RESOLUTIONS = listOf(
        ResolutionInfo(3840, 2160, "4K (UHD)"),
        ResolutionInfo(2560, 1440, "1440p (QHD)"),
        ResolutionInfo(1920, 1080, "1080p (FHD)"),
        ResolutionInfo(1280, 720, "720p (HD)"),
        ResolutionInfo(640, 480, "480p (SD)")
    )

    private val maxFpsCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun getMaxFpsForResolution(
        context: Context,
        width: Int,
        height: Int,
        isBack: Boolean
    ): Int {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return 30

        val cameraId = getCameraId(cameraManager, isBack)
        val w = maxOf(width, height)
        val h = minOf(width, height)
        val cacheKey = "${cameraId}_${w}x${h}"
        maxFpsCache[cacheKey]?.let { return it }

        val chars = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (_: Exception) { return 30 }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val maxDeviceRangeFps = fpsRanges.maxOfOrNull { it.upper } ?: 30

        var calculatedFps = 30

        if (map != null) {
            val targetSize = Size(w, h)
            val minDuration = try {
                map.getOutputMinFrameDuration(MediaCodec::class.java, targetSize).takeIf { it > 0 }
                    ?: map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, targetSize)
            } catch (_: Exception) {
                0L
            }

            if (minDuration > 0) {
                val rawMax = (1_000_000_000L / minDuration).toInt()
                calculatedFps = minOf(rawMax, maxDeviceRangeFps)
            } else {
                // Check if probe has data for this camera
                val report = CameraCapabilityProbe.probeSync(context)
                val node = if (isBack) report.primaryBack else report.primaryFront
                val profile = node?.streams?.allProfiles?.find {
                    (it.width == w && it.height == h) || (it.width == h && it.height == w)
                }
                calculatedFps = profile?.maxFps ?: if (w >= 3840) 30 else maxDeviceRangeFps
            }
        }

        // Bound to valid video rates
        val finalFps = calculatedFps.coerceIn(15, 60)
        maxFpsCache[cacheKey] = finalFps
        Logger.log("FPS_MATRIX", "Max FPS for $w x $h (${if (isBack) "Back" else "Front"}): $finalFps")
        return finalFps
    }

    fun isFpsSupported(
        context: Context,
        targetFps: Int,
        width: Int,
        height: Int,
        isBack: Boolean
    ): Boolean {
        val maxFps = getMaxFpsForResolution(context, width, height, isBack)
        return targetFps <= maxFps
    }

    /**
     * Finds the highest standard resolution that supports [targetFps] and is
     * smaller than or equal to current dimensions.
     */
    fun findHighestResolutionForFps(
        context: Context,
        targetFps: Int,
        currentWidth: Int,
        currentHeight: Int,
        isBack: Boolean
    ): ResolutionInfo? {
        val candidates = STANDARD_RESOLUTIONS.filter { res ->
            val maxFps = getMaxFpsForResolution(context, res.width, res.height, isBack)
            maxFps >= targetFps
        }

        if (candidates.isEmpty()) return null

        val currentPixels = maxOf(currentWidth, 1) * maxOf(currentHeight, 1)
        val downshift = candidates.firstOrNull { it.width * it.height < currentPixels }
        return downshift ?: candidates.first()
    }

    fun getResolutionLabel(width: Int, height: Int): String {
        val w = maxOf(width, height)
        val h = minOf(width, height)
        val std = STANDARD_RESOLUTIONS.find { it.width == w && it.height == h }
        if (std != null) return std.label
        return "${w}x${h}"
    }

    fun clearCache() {
        maxFpsCache.clear()
    }

    private fun getCameraId(cameraManager: CameraManager, facingBack: Boolean): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facingBack && facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (!facingBack && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }
}
