package com.darusc.vcamdroid.video

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaRecorder
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

val DEFAULT_STANDARD_RESOLUTIONS = listOf(
    Pair(3840, 2160), // 4K UHD
    Pair(2560, 1440), // 1440p QHD
    Pair(1920, 1080), // 1080p FHD
    Pair(1280, 720),  // 720p HD
    Pair(960, 540),   // qHD
    Pair(640, 480),   // 480p SD (4:3)
    Pair(640, 360)    // 360p nHD (16:9)
)

/**
 * Synchronously query supported resolutions for back and front cameras directly via CameraManager.
 */
fun queryDeviceResolutions(context: Context): Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
    return try {
        val back = getResolutionsForFacing(context, facingBack = true)
        val front = getResolutionsForFacing(context, facingBack = false)
        Pair(back, front)
    } catch (e: Exception) {
        e.printStackTrace()
        Pair(DEFAULT_STANDARD_RESOLUTIONS, DEFAULT_STANDARD_RESOLUTIONS)
    }
}

/**
 * Synchronously query resolutions for a specific camera facing (back vs front).
 */
fun getResolutionsForFacing(context: Context, facingBack: Boolean): List<Pair<Int, Int>> {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        ?: return DEFAULT_STANDARD_RESOLUTIONS

    try {
        val targetFacing = if (facingBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        var selectedId: String? = null

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                selectedId = id
                break
            }
        }

        if (selectedId == null) {
            selectedId = cameraManager.cameraIdList.firstOrNull()
        }

        if (selectedId != null) {
            val chars = cameraManager.getCameraCharacteristics(selectedId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val res = extractResolutionsFromMap(map)
                if (res.isNotEmpty()) return res
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return DEFAULT_STANDARD_RESOLUTIONS
}

private fun extractResolutionsFromMap(map: StreamConfigurationMap): List<Pair<Int, Int>> {
    val rawSizes = mutableSetOf<Size>()

    // Query multiple target classes and formats for comprehensive resolution discovery
    map.getOutputSizes(MediaCodec::class.java)?.let { rawSizes.addAll(it) }
    map.getOutputSizes(SurfaceTexture::class.java)?.let { rawSizes.addAll(it) }
    map.getOutputSizes(MediaRecorder::class.java)?.let { rawSizes.addAll(it) }
    map.getOutputSizes(ImageFormat.YUV_420_888)?.let { rawSizes.addAll(it) }

    if (rawSizes.isEmpty()) return emptyList()

    val normalized = rawSizes.map { size ->
        val w = maxOf(size.width, size.height)
        val h = minOf(size.width, size.height)
        Pair(w, h)
    }.distinct()

    val filtered = normalized.filter { (w, h) ->
        val ratio = w.toFloat() / h.toFloat()
        val is16by9 = ratio in 1.70f..1.85f
        val is4by3 = ratio in 1.30f..1.36f
        val isWidescreen = ratio in 1.60f..2.40f
        is16by9 || is4by3 || isWidescreen
    }

    val result = if (filtered.isNotEmpty()) filtered else normalized
    return result.sortedByDescending { it.first * it.second }
}

/**
 * Asynchronous resolution discovery maintaining backwards compatibility with CameraX caller.
 */
fun getSupportedResolutions(
    context: Context,
    onResult: (back: List<Pair<Int, Int>>, front: List<Pair<Int, Int>>) -> Unit
) {
    // Try fast synchronous query via CameraManager first
    val (backSync, frontSync) = queryDeviceResolutions(context)
    if (backSync.isNotEmpty() && frontSync.isNotEmpty()) {
        onResult(backSync, frontSync)
        return
    }

    // Fallback to CameraX ProcessCameraProvider if needed
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()
            val backResolutions = getResolutionsForCameraX(cameraProvider, CameraSelector.DEFAULT_BACK_CAMERA)
            val frontResolutions = getResolutionsForCameraX(cameraProvider, CameraSelector.DEFAULT_FRONT_CAMERA)

            val finalBack = backResolutions.ifEmpty { DEFAULT_STANDARD_RESOLUTIONS }
            val finalFront = frontResolutions.ifEmpty { DEFAULT_STANDARD_RESOLUTIONS }
            onResult(finalBack, finalFront)
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(DEFAULT_STANDARD_RESOLUTIONS, DEFAULT_STANDARD_RESOLUTIONS)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun getResolutionsForCameraX(
    provider: ProcessCameraProvider,
    selector: CameraSelector
): List<Pair<Int, Int>> {
    try {
        if (!provider.hasCamera(selector)) return emptyList()
        val cameraInfo = provider.availableCameraInfos.firstOrNull {
            selector.filter(listOf(it)).isNotEmpty()
        } ?: return emptyList()

        val map = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return emptyList()

        return extractResolutionsFromMap(map)
    } catch (e: Exception) {
        return emptyList()
    }
}