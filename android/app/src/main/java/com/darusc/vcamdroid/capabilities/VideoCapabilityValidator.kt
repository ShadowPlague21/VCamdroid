package com.darusc.vcamdroid.capabilities

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.darusc.vcamdroid.util.Logger
import kotlin.math.roundToInt

/**
 * Validates and clamps arbitrary / oddball video resolutions against
 * hardware video encoder (MediaCodec) capabilities.
 * 
 * Prevents MediaCodec 0xfffffff4 crashes and stream connection drops
 * when custom or oddball aspect ratios (e.g. 3000x3000, 3648x2736, 1080x1920)
 * are requested by OBS or UI.
 */
object VideoCapabilityValidator {

    private val codecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    fun validateAndClamp(
        reqWidth: Int,
        reqHeight: Int,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_HEVC
    ): Pair<Int, Int> {
        if (reqWidth <= 0 || reqHeight <= 0) {
            return Pair(1920, 1080)
        }

        val videoCaps = getVideoCapabilities(mimeType)
            ?: getVideoCapabilities(MediaFormat.MIMETYPE_VIDEO_AVC)

        if (videoCaps == null) {
            val alignedW = alignDimension(reqWidth, 16).coerceIn(320, 3840)
            val alignedH = alignDimension(reqHeight, 16).coerceIn(240, 2160)
            return Pair(alignedW, alignedH)
        }

        val widthAlignment = videoCaps.widthAlignment.coerceAtLeast(2)
        val heightAlignment = videoCaps.heightAlignment.coerceAtLeast(2)
        val supportedWidths = videoCaps.supportedWidths
        val supportedHeights = videoCaps.supportedHeights

        // Initial alignment and bounds check
        var w = alignDimension(reqWidth, widthAlignment).coerceIn(supportedWidths.lower, supportedWidths.upper)
        var h = alignDimension(reqHeight, heightAlignment).coerceIn(supportedHeights.lower, supportedHeights.upper)

        // If directly supported by the hardware encoder, return as-is
        if (videoCaps.isSizeSupported(w, h)) {
            return Pair(w, h)
        }

        // Dimensions exceed hardware limits (e.g. 3000x3000 where max height is 2160)
        // Proportionally scale down while preserving the exact aspect ratio
        val aspectRatio = reqWidth.toDouble() / reqHeight.toDouble()
        val maxW = supportedWidths.upper
        val maxH = supportedHeights.upper

        if (h > maxH) {
            h = alignDimension(maxH, heightAlignment)
            w = alignDimension((h * aspectRatio).roundToInt(), widthAlignment)
        }

        if (w > maxW) {
            w = alignDimension(maxW, widthAlignment)
            h = alignDimension((w / aspectRatio).roundToInt(), heightAlignment)
        }

        // Iterative reduction in case total macroblocks or bitrate points exceed hardware capacity
        var scale = 1.0
        while (!videoCaps.isSizeSupported(w, h) && scale > 0.3) {
            scale -= 0.05
            val nextH = alignDimension((h * scale).roundToInt(), heightAlignment)
            val nextW = alignDimension((nextH * aspectRatio).roundToInt(), widthAlignment)
            if (nextW == w && nextH == h) break
            w = nextW
            h = nextH
        }

        // Final fallback if custom sizing fails
        if (!videoCaps.isSizeSupported(w, h)) {
            w = 1920
            h = 1080
        }

        if (w != reqWidth || h != reqHeight) {
            Logger.log(
                "CAPABILITY_VALIDATOR",
                "Clamped resolution for $mimeType: ${reqWidth}x$reqHeight -> ${w}x$h (supported: ${videoCaps.isSizeSupported(w, h)})"
            )
        }

        return Pair(w, h)
    }

    private fun getVideoCapabilities(mimeType: String): MediaCodecInfo.VideoCapabilities? {
        try {
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                val caps = try {
                    info.getCapabilitiesForType(mimeType)
                } catch (_: Exception) {
                    null
                } ?: continue
                return caps.videoCapabilities ?: continue
            }
        } catch (e: Exception) {
            Logger.log("CAPABILITY_VALIDATOR", "Error reading VideoCapabilities: ${e.message}")
        }
        return null
    }

    private fun alignDimension(dim: Int, alignment: Int): Int {
        val rem = dim % alignment
        return if (rem == 0) dim else dim - rem
    }
}
