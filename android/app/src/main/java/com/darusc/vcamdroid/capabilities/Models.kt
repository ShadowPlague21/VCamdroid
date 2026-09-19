package com.darusc.vcamdroid.capabilities

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF

/**
 * FAANG-grade capability models - immutable, typed, no primitives leaking
 */

enum class Facing {
    BACK, FRONT, EXTERNAL, UNKNOWN;

    companion object {
        fun fromInt(v: Int?): Facing = when (v) {
            CameraCharacteristics.LENS_FACING_BACK -> BACK
            CameraCharacteristics.LENS_FACING_FRONT -> FRONT
            CameraCharacteristics.LENS_FACING_EXTERNAL -> EXTERNAL
            else -> UNKNOWN
        }
    }
}

enum class HardwareLevel {
    LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL, UNKNOWN;

    companion object {
        fun fromInt(v: Int?): HardwareLevel = when (v) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> LEGACY
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> LIMITED
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> FULL
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> LEVEL_3
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> EXTERNAL
            else -> UNKNOWN
        }
    }
}

data class SensorInfo(
    val activeArray: Rect,
    val pixelArray: Rect?,
    val preCorrectionActiveArray: Rect?,
    val physicalSize: SizeF?,
    val orientation: Int,
    val pixelArraySize: Size? // SENSOR_INFO_PIXEL_ARRAY_SIZE
)

data class LensInfo(
    val focalLengths: List<Float>,
    val minFocusDistance: Float?, // diopters, 0 = fixed focus, >0 = min distance
    val focusCalibration: Int?,
    val hyperfocalDistance: Float? = null
)

data class ControlInfo(
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?, // ns
    val evRange: Range<Int>?,
    val evStep: Rational?,
    val zoomRatioRange: Range<Float>?, // API 30+
    val maxDigitalZoom: Float,
    val fpsRanges: List<Range<Int>>, // AE target FPS ranges
    val maxFpsForPreview: Int? = null
)

data class FeatureInfo(
    val hasFlash: Boolean,
    val isLogical: Boolean,
    val physicalIds: List<String>,
    val afModes: List<Int>,
    val awbModes: List<Int>,
    val aeModes: List<Int>,
    val stabilizationModes: List<Int>?,
    val capabilities: List<Int>, // REQUEST_AVAILABLE_CAPABILITIES
    val hardwareLevel: HardwareLevel
)

data class ResolutionProfile(
    val size: Size,
    val width: Int,
    val height: Int,
    val aspectRatioLabel: String, // e.g. "16:9 (1.78:1)"
    val aspectRatioFloat: Float,
    val megapixels: Float,
    val minFrameDurationNs: Long?, // from map.getOutputMinFrameDuration
    val maxFps: Int?, // derived = 1e9 / minDuration
    val stallDurationNs: Long?,
    val isHighSpeed: Boolean,
    val formats: List<String> // e.g. ["PRIVATE", "YUV_420_888", "MediaCodec"]
) {
    fun toDisplayString(): String = "${width}×${height}"
    fun toDetailedString(): String = "${width}×${height} • ${aspectRatioLabel} • ${"%.1f".format(megapixels)}MP • ${maxFps ?: "?"}fps max"
}

data class StreamInfo(
    val allProfiles: List<ResolutionProfile>, // deduped, sorted by MP desc
    val byFormat: Map<String, List<Size>>,
    val highSpeedProfiles: List<ResolutionProfile>,
    val highSpeedFpsRanges: List<Range<Int>>
)

data class CameraNode(
    val id: String,
    val facing: Facing,
    val hardwareLevel: HardwareLevel,
    val sensor: SensorInfo,
    val lens: LensInfo,
    val controls: ControlInfo,
    val features: FeatureInfo,
    val streams: StreamInfo
) {
    val displayName: String
        get() = when (facing) {
            Facing.BACK -> if (features.isLogical) "Rear Logical ($id)" else "Rear ($id)"
            Facing.FRONT -> if (features.isLogical) "Front Logical ($id)" else "Front ($id)"
            Facing.EXTERNAL -> "External ($id)"
            else -> "Camera $id"
        }

    fun supportsManualIso(): Boolean {
        val lvl = hardwareLevel
        return (lvl == HardwareLevel.FULL || lvl == HardwareLevel.LEVEL_3) &&
                controls.isoRange != null && controls.isoRange.upper > controls.isoRange.lower
    }

    fun supportsManualFocus(): Boolean {
        return lens.minFocusDistance != null && lens.minFocusDistance > 0f
    }
}

data class DeviceCapabilityReport(
    val model: String,
    val manufacturer: String,
    val sdk: Int,
    val cameras: List<CameraNode>,
    val timestamp: Long = System.currentTimeMillis()
) {
    val backCameras: List<CameraNode> get() = cameras.filter { it.facing == Facing.BACK }
    val frontCameras: List<CameraNode> get() = cameras.filter { it.facing == Facing.FRONT }
    val primaryBack: CameraNode? get() = backCameras.firstOrNull { it.features.isLogical } ?: backCameras.firstOrNull()
    val primaryFront: CameraNode? get() = frontCameras.firstOrNull { it.features.isLogical } ?: frontCameras.firstOrNull()

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"device\": \"${manufacturer} ${model} (SDK $sdk)\",\n")
        sb.append("  \"timestamp\": $timestamp,\n")
        sb.append("  \"cameras\": [\n")
        cameras.forEachIndexed { idx, cam ->
            sb.append("    {\n")
            sb.append("      \"id\": \"${cam.id}\",\n")
            sb.append("      \"facing\": \"${cam.facing}\",\n")
            sb.append("      \"hardwareLevel\": \"${cam.hardwareLevel}\",\n")
            sb.append("      \"isLogical\": ${cam.features.isLogical},\n")
            sb.append("      \"physicalIds\": [${cam.features.physicalIds.joinToString(",") { "\"$it\"" }}],\n")
            sb.append("      \"sensorActiveArray\": \"${cam.sensor.activeArray.width()}x${cam.sensor.activeArray.height()}\",\n")
            sb.append("      \"sensorOrientation\": ${cam.sensor.orientation},\n")
            sb.append("      \"focalLengths\": [${cam.lens.focalLengths.joinToString(",")}],\n")
            sb.append("      \"isoRange\": \"${cam.controls.isoRange}\",\n")
            sb.append("      \"evRange\": \"${cam.controls.evRange} step ${cam.controls.evStep}\",\n")
            sb.append("      \"exposureTimeNs\": \"${cam.controls.exposureTimeRange}\",\n")
            sb.append("      \"zoomRatio\": \"${cam.controls.zoomRatioRange}\" maxDigitalZoom ${cam.controls.maxDigitalZoom},\n")
            sb.append("      \"hasFlash\": ${cam.features.hasFlash},\n")
            sb.append("      \"afModes\": [${cam.features.afModes.joinToString(",")}],\n")
            sb.append("      \"awbModes\": [${cam.features.awbModes.joinToString(",")}],\n")
            sb.append("      \"fpsRanges\": [${cam.controls.fpsRanges.joinToString(",") { "\"$it\"" }}],\n")
            sb.append("      \"resolutions\": [${cam.streams.allProfiles.joinToString(",") { "\"${it.width}x${it.height}@${it.maxFps ?: "?"}fps\"" }}]\n")
            sb.append("    }${if (idx < cameras.size - 1) "," else ""}\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun toHumanReadable(): String {
        val sb = StringBuilder()
        sb.append("=== VCamdroid Device Capability Report ===\n")
        sb.append("Device: $manufacturer $model (SDK $sdk)\n")
        sb.append("Timestamp: ${java.util.Date(timestamp)}\n\n")
        cameras.forEach { cam ->
            sb.append("--- ${cam.displayName} ---\n")
            sb.append("HW Level: ${cam.hardwareLevel} | Logical: ${cam.features.isLogical} | Physical: ${cam.features.physicalIds}\n")
            sb.append("Sensor: Active ${cam.sensor.activeArray.width()}x${cam.sensor.activeArray.height()} @ ${cam.sensor.activeArray} | Pixel ${cam.sensor.pixelArraySize} | Orient ${cam.sensor.orientation}°\n")
            sb.append("Lens: f=${cam.lens.focalLengths}mm | MinFocus ${cam.lens.minFocusDistance ?: "fixed"} diopters\n")
            sb.append("Controls: ISO ${cam.controls.isoRange} | EV ${cam.controls.evRange} step ${cam.controls.evStep} | Exposure ${cam.controls.exposureTimeRange}ns | ZoomRatio ${cam.controls.zoomRatioRange} | MaxDigitalZoom ${cam.controls.maxDigitalZoom}x\n")
            sb.append("Features: Flash ${cam.features.hasFlash} | AF ${cam.features.afModes} | AWB ${cam.features.awbModes} | FPS ${cam.controls.fpsRanges}\n")
            sb.append("Resolutions (${cam.streams.allProfiles.size}):\n")
            cam.streams.allProfiles.take(20).forEach { p ->
                sb.append("  • ${p.width}×${p.height} ${p.aspectRatioLabel} ${"%.1f".format(p.megapixels)}MP max ${p.maxFps ?: "?"}fps ${if (p.isHighSpeed) "[HS]" else ""} formats=${p.formats}\n")
            }
            if (cam.streams.allProfiles.size > 20) sb.append("  ... +${cam.streams.allProfiles.size - 20} more\n")
            sb.append("\n")
        }
        return sb.toString()
    }
}

// Helper extensions
internal fun gcd(a: Int, b: Int): Int {
    var x = kotlin.math.abs(a)
    var y = kotlin.math.abs(b)
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return if (x == 0) 1 else x
}

internal fun aspectLabel(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return "?:?"
    val g = gcd(w, h)
    val rw = w / g
    val rh = h / g
    val f = w.toFloat() / h.toFloat()
    return when {
        rw == 16 && rh == 9 -> "16:9 (1.78:1)"
        rw == 4 && rh == 3 -> "4:3 (1.33:1)"
        rw == 18 && rh == 9 -> "18:9 (2.00:1)"
        rw == 20 && rh == 9 -> "20:9 (2.22:1)"
        rw == 21 && rh == 9 -> "21:9 (2.33:1)"
        else -> "$rw:$rh (${"%.2f".format(f)}:1)"
    }
}
