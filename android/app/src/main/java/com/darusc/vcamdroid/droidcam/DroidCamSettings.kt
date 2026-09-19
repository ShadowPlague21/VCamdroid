package com.darusc.vcamdroid.droidcam

import android.content.Context
import android.content.SharedPreferences

class DroidCamSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "droidcam_preferences"

        const val KEY_TARGET_BITRATE = "target_bitrate_kbps"
        const val KEY_TARGET_RES_WIDTH = "target_resolution_width"
        const val KEY_TARGET_RES_HEIGHT = "target_resolution_height"
        const val KEY_KEYFRAME_INTERVAL = "keyframe_interval_sec"
        const val KEY_TARGET_FPS = "target_fps"
        const val KEY_MIN_FPS_MATCH = "min_fps_match_target"
        const val KEY_ANTI_FLICKER = "anti_flicker_mode" // "auto", "off", "50hz", "60hz"
        const val KEY_DOUBLE_TAP_ACTION = "double_tap_action" // "camera", "zoom", "off"
        const val KEY_KEEP_AWAKE = "keep_device_awake"
        const val KEY_BACKGROUND_STREAMING = "background_streaming"
        const val KEY_SCREEN_OFF_ACTIVE = "screen_off_video_active"
        const val KEY_WIFI_DISCOVERY = "allow_wifi_discovery"
        const val KEY_CONFIRM_STOP = "confirm_stop"
        const val KEY_SHOW_TALLY = "show_tally_indicator"
        const val KEY_PORT = "droidcam_port"

        // AI Tracking and Gesture Preferences
        const val KEY_AI_TRACKING = "ai_tracking_enabled"
        const val KEY_AI_FRAMING_MODE = "ai_framing_mode" // "16:9" or "9:16"
        const val KEY_GESTURES_ENABLED = "ai_gestures_enabled"

        const val KEY_ROTATE_PREVIEW_180 = "rotate_preview_180"

        // Last saved camera parameters
        const val KEY_LAST_ZOOM = "last_zoom_factor"
        const val KEY_LAST_EV = "last_exposure_compensation"
        const val KEY_LAST_AWB = "last_awb_mode"
        const val KEY_LAST_AF = "last_af_mode"
    }

    var isRotatePreview180: Boolean
        get() = prefs.getBoolean(KEY_ROTATE_PREVIEW_180, false)
        set(value) = prefs.edit().putBoolean(KEY_ROTATE_PREVIEW_180, value).apply()

    // AI Tracking Options
    var isAiTrackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_TRACKING, true)
        set(value) = prefs.edit().putBoolean(KEY_AI_TRACKING, value).apply()

    var aiFramingMode: String
        get() = prefs.getString(KEY_AI_FRAMING_MODE, "16:9") ?: "16:9"
        set(value) = prefs.edit().putString(KEY_AI_FRAMING_MODE, value).apply()

    var isGesturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURES_ENABLED, value).apply()

    // Video Options
    var targetResolutionWidth: Int
        get() = prefs.getInt(KEY_TARGET_RES_WIDTH, 1920)
        set(value) = prefs.edit().putInt(KEY_TARGET_RES_WIDTH, value).apply()

    var targetResolutionHeight: Int
        get() = prefs.getInt(KEY_TARGET_RES_HEIGHT, 1080)
        set(value) = prefs.edit().putInt(KEY_TARGET_RES_HEIGHT, value).apply()

    fun getResolutionLabel(): String {
        val w = targetResolutionWidth
        val h = targetResolutionHeight
        return when {
            w >= 3840 || h >= 2160 -> "4K UHD (${w}x${h})"
            w >= 2560 || h >= 1440 -> "1440p QHD (${w}x${h})"
            w >= 1920 || h >= 1080 -> "1080p FHD (${w}x${h})"
            w >= 1280 || h >= 720  -> "720p HD (${w}x${h})"
            w >= 640 && h >= 480   -> "480p SD (${w}x${h})"
            else -> "${w}x${h}"
        }
    }

    var targetBitrateKbps: Int
        get() = prefs.getInt(KEY_TARGET_BITRATE, 4000)
        set(value) = prefs.edit().putInt(KEY_TARGET_BITRATE, value).apply()

    var keyFrameIntervalSec: Int
        get() = prefs.getInt(KEY_KEYFRAME_INTERVAL, 1)
        set(value) = prefs.edit().putInt(KEY_KEYFRAME_INTERVAL, value).apply()

    // Camera Options
    var targetFps: Int
        get() = prefs.getInt(KEY_TARGET_FPS, 30)
        set(value) = prefs.edit().putInt(KEY_TARGET_FPS, value).apply()

    var matchTargetMinFps: Boolean
        get() = prefs.getBoolean(KEY_MIN_FPS_MATCH, false)
        set(value) = prefs.edit().putBoolean(KEY_MIN_FPS_MATCH, value).apply()

    var antiFlickerMode: String
        get() = prefs.getString(KEY_ANTI_FLICKER, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_ANTI_FLICKER, value).apply()

    var doubleTapAction: String
        get() = prefs.getString(KEY_DOUBLE_TAP_ACTION, "camera") ?: "camera"
        set(value) = prefs.edit().putString(KEY_DOUBLE_TAP_ACTION, value).apply()

    // Power and Screen
    var keepDeviceAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    var backgroundStreaming: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_STREAMING, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_STREAMING, value).apply()

    var keepVideoActiveScreenOff: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_OFF_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_OFF_ACTIVE, value).apply()

    // Other Options
    var allowWifiDiscovery: Boolean
        get() = prefs.getBoolean(KEY_WIFI_DISCOVERY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_DISCOVERY, value).apply()

    var confirmStop: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_STOP, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_STOP, value).apply()

    var showTallyIndicator: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TALLY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TALLY, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 4747)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    // Parameter Memory
    var lastZoomFactor: Float
        get() = prefs.getFloat(KEY_LAST_ZOOM, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_LAST_ZOOM, value).apply()

    var lastExposureCompensation: Int
        get() = prefs.getInt(KEY_LAST_EV, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_EV, value).apply()

    var lastAwbMode: Int
        get() = prefs.getInt(KEY_LAST_AWB, 1) // 1 = CONTROL_AWB_MODE_AUTO
        set(value) = prefs.edit().putInt(KEY_LAST_AWB, value).apply()

    var lastAfMode: Int
        get() = prefs.getInt(KEY_LAST_AF, 3) // 3 = CONTROL_AF_MODE_CONTINUOUS_VIDEO
        set(value) = prefs.edit().putInt(KEY_LAST_AF, value).apply()

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}
