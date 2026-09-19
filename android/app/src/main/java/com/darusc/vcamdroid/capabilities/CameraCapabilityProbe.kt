package com.darusc.vcamdroid.capabilities

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Industry-standard capability probe - single source of truth
 * 
 * FAANG principles:
 * - Feature detection, not model detection
 * - Immutable data, no side effects
 * - Runs on IO dispatcher, never main thread
 * - Graceful degradation per API level
 * - Exhaustive StreamConfigurationMap parsing including high-speed
 */
object CameraCapabilityProbe {

    private const val TAG = "CapabilityProbe"

    suspend fun probe(context: Context): DeviceCapabilityReport = withContext(Dispatchers.IO) {
        probeSync(context)
    }

    fun probeSync(context: Context): DeviceCapabilityReport {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val model = Build.MODEL ?: "unknown"
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val sdk = Build.VERSION.SDK_INT

        if (cameraManager == null) {
            return DeviceCapabilityReport(model, manufacturer, sdk, emptyList())
        }

        val nodes = mutableListOf<CameraNode>()

        try {
            val idList = cameraManager.cameraIdList
            Log.d(TAG, "Found ${idList.size} camera IDs: ${idList.joinToString()}")

            for (id in idList) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val node = parseCameraNode(id, chars)
                    nodes.add(node)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse camera $id: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cameraIdList: ${e.message}", e)
        }

        return DeviceCapabilityReport(model, manufacturer, sdk, nodes)
    }

    private fun parseCameraNode(id: String, chars: CameraCharacteristics): CameraNode {
        // Facing
        val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
        val facing = Facing.fromInt(facingInt)

        // Hardware level
        val hwLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val hwLevel = HardwareLevel.fromInt(hwLevelInt)

        // Logical multi-camera
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val isLogical = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                chars.physicalCameraIds.toList()
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        // Sensor
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: android.graphics.Rect(0, 0, 0, 0)
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val preCorrection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        } else null
        val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val sensor = SensorInfo(
            activeArray = activeArray,
            pixelArray = activeArray, // keep for compat, actually activeArray is primary
            preCorrectionActiveArray = preCorrection,
            physicalSize = physicalSize,
            orientation = orientation,
            pixelArraySize = pixelArray
        )

        // Lens
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val focusCalib = chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)

        val lens = LensInfo(
            focalLengths = focalLengths,
            minFocusDistance = minFocus,
            focusCalibration = focusCalib
        )

        // Controls
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        val zoomRatioRange: Range<Float>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } catch (_: Exception) {
                null
            }
        } else null
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()

        val controls = ControlInfo(
            isoRange = isoRange,
            exposureTimeRange = exposureTimeRange,
            evRange = evRange,
            evStep = evStep,
            zoomRatioRange = zoomRatioRange,
            maxDigitalZoom = maxDigitalZoom,
            fpsRanges = fpsRanges
        )

        // Features
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList() ?: emptyList()
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList()

        val features = FeatureInfo(
            hasFlash = hasFlash,
            isLogical = isLogical,
            physicalIds = physicalIds,
            afModes = afModes,
            awbModes = awbModes,
            aeModes = aeModes,
            stabilizationModes = oisModes,
            capabilities = capabilities,
            hardwareLevel = hwLevel
        )

        // Streams - the most complex part
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val streams = if (map != null) parseStreamInfo(map) else StreamInfo(emptyList(), emptyMap(), emptyList(), emptyList())

        return CameraNode(
            id = id,
            facing = facing,
            hardwareLevel = hwLevel,
            sensor = sensor,
            lens = lens,
            controls = controls,
            features = features,
            streams = streams
        )
    }

    private fun parseStreamInfo(map: StreamConfigurationMap): StreamInfo {
        val byFormat = mutableMapOf<String, MutableSet<Size>>()
        val allSizes = mutableSetOf<Size>()
        val formatToLabel = mapOf(
            ImageFormat.YUV_420_888 to "YUV_420_888",
            ImageFormat.JPEG to "JPEG",
            ImageFormat.RAW_SENSOR to "RAW",
            ImageFormat.YV12 to "YV12"
        )

        // Query multiple classes - industry standard comprehensive discovery
        fun addSizes(label: String, sizes: Array<Size>?) {
            if (sizes == null) return
            val set = byFormat.getOrPut(label) { mutableSetOf() }
            set.addAll(sizes)
            allSizes.addAll(sizes)
        }

        // SurfaceTexture = preview
        addSizes("PRIVATE", map.getOutputSizes(SurfaceTexture::class.java))
        // MediaCodec = encoder (what we actually stream)
        addSizes("MediaCodec", map.getOutputSizes(MediaCodec::class.java))
        // MediaRecorder = recording
        addSizes("MediaRecorder", map.getOutputSizes(MediaRecorder::class.java))
        // YUV = image analysis
        addSizes("YUV_420_888", map.getOutputSizes(ImageFormat.YUV_420_888))
        // JPEG = photo
        addSizes("JPEG", map.getOutputSizes(ImageFormat.JPEG))
        // RAW if available
        try {
            addSizes("RAW_SENSOR", map.getOutputSizes(ImageFormat.RAW_SENSOR))
        } catch (_: Exception) {}

        // Build resolution profiles with real FPS data
        val profiles = mutableListOf<ResolutionProfile>()
        val seen = mutableSetOf<String>() // dedup by WxH

        for (size in allSizes) {
            val key = "${size.width}x${size.height}"
            if (seen.contains(key)) continue
            seen.add(key)

            // Determine which formats support this size
            val formats = mutableListOf<String>()
            byFormat.forEach { (fmt, set) ->
                if (set.contains(size)) formats.add(fmt)
            }

            // Get min frame duration for max FPS calculation
            var minDuration: Long? = null
            var maxFps: Int? = null
            try {
                // Try MediaCodec first (most relevant for streaming)
                minDuration = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
                if (minDuration != null && minDuration > 0) {
                    maxFps = (1_000_000_000L / minDuration).toInt()
                }
            } catch (_: Exception) {
                try {
                    minDuration = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                    if (minDuration != null && minDuration > 0) {
                        maxFps = (1_000_000_000L / minDuration).toInt()
                    }
                } catch (_: Exception) {}
            }

            var stall: Long? = null
            try {
                stall = map.getOutputStallDuration(ImageFormat.YUV_420_888, size)
            } catch (_: Exception) {}

            val w = maxOf(size.width, size.height)
            val h = minOf(size.width, size.height)
            val mp = (w * h) / 1_000_000f
            val label = aspectLabel(w, h)
            val ratioFloat = if (h != 0) w.toFloat() / h else 0f

            profiles.add(
                ResolutionProfile(
                    size = size,
                    width = w,
                    height = h,
                    aspectRatioLabel = label,
                    aspectRatioFloat = ratioFloat,
                    megapixels = mp,
                    minFrameDurationNs = minDuration,
                    maxFps = maxFps,
                    stallDurationNs = stall,
                    isHighSpeed = false,
                    formats = formats
                )
            )
        }

        // High-speed video profiles
        val highSpeedProfiles = mutableListOf<ResolutionProfile>()
        val highSpeedFpsRanges = mutableListOf<Range<Int>>()
        try {
            val hsSizes = map.highSpeedVideoSizes
            val hsFpsRanges = map.highSpeedVideoFpsRanges
            if (hsFpsRanges != null) highSpeedFpsRanges.addAll(hsFpsRanges)
            hsSizes?.forEach { size ->
                val w = maxOf(size.width, size.height)
                val h = minOf(size.width, size.height)
                val key = "${w}x${h}_HS"
                if (seen.contains(key)) return@forEach
                // Query size-specific high-speed FPS ranges rather than sensor-wide max
                val sizeFpsRanges = try {
                    map.getHighSpeedVideoFpsRangesFor(size)
                } catch (_: IllegalArgumentException) {
                    null
                }
                val maxFps = sizeFpsRanges?.maxOfOrNull { it.upper }
                    ?: hsFpsRanges?.maxOfOrNull { it.upper }
                    ?: 120
                highSpeedProfiles.add(
                    ResolutionProfile(
                        size = size,
                        width = w,
                        height = h,
                        aspectRatioLabel = aspectLabel(w, h),
                        aspectRatioFloat = if (h != 0) w.toFloat() / h else 0f,
                        megapixels = (w * h) / 1_000_000f,
                        minFrameDurationNs = if (maxFps > 0) 1_000_000_000L / maxFps else null,
                        maxFps = maxFps,
                        stallDurationNs = null,
                        isHighSpeed = true,
                        formats = listOf("HighSpeed")
                    )
                )
            }
        } catch (_: Exception) {
            // High speed not supported on this device/API
        }

        // Sort all profiles by megapixels descending, then by max FPS
        val sorted = profiles.sortedWith(compareByDescending<ResolutionProfile> { it.width * it.height }.thenByDescending { it.maxFps ?: 0 })
        val sortedHs = highSpeedProfiles.sortedByDescending { it.width * it.height }

        // Merge for display but keep separate list
        val allCombined = (sorted + sortedHs).distinctBy { "${it.width}x${it.height}" to it.isHighSpeed }

        return StreamInfo(
            allProfiles = sorted,
            byFormat = byFormat.mapValues { it.value.toList() },
            highSpeedProfiles = sortedHs,
            highSpeedFpsRanges = highSpeedFpsRanges
        )
    }

    // Legacy compatibility - returns Pair<back, front> as before but now from full probe
    fun queryDeviceResolutionsLegacy(context: Context): Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
        val report = probeSync(context)
        val back = report.backCameras.firstOrNull()?.streams?.allProfiles?.map { Pair(it.width, it.height) } ?: emptyList()
        val front = report.frontCameras.firstOrNull()?.streams?.allProfiles?.map { Pair(it.width, it.height) } ?: emptyList()
        return Pair(back, front)
    }
}
