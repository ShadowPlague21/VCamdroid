package com.darusc.vcamdroid.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

class PowerSaveManager(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var screenStateReceiver: BroadcastReceiver? = null
    var onScreenStateChanged: ((isScreenOn: Boolean) -> Unit)? = null

    val isScreenOn: Boolean
        get() = powerManager?.isInteractive ?: true

    fun acquireLocks() {
        // 1. Partial WakeLock keeps CPU running when screen is turned off
        if (wakeLock == null) {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VCamdroid:StreamingWakeLock"
            )?.apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

        // 2. Low-Latency Wi-Fi Lock keeps Wi-Fi radio out of 802.11 power saving
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(mode, "VCamdroid:WifiLock")?.apply {
                setReferenceCounted(false)
            }
        }
        wifiLock?.takeIf { !it.isHeld }?.acquire()

        // 3. Multicast Lock for mDNS discovery (LAN auto-discovery)
        if (multicastLock == null) {
            multicastLock = wifiManager?.createMulticastLock("VCamdroid:MulticastLock")?.apply {
                setReferenceCounted(false)
            }
        }
        multicastLock?.takeIf { !it.isHeld }?.acquire()

        registerScreenReceiver()
    }

    fun releaseLocks() {
        unregisterScreenReceiver()

        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null

        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null

        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    private fun registerScreenReceiver() {
        if (screenStateReceiver != null) return

        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Logger.log("POWER", "Screen turned OFF -> transitioning to off-screen streaming")
                        onScreenStateChanged?.invoke(false)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Logger.log("POWER", "Screen turned ON -> resuming on-screen preview")
                        onScreenStateChanged?.invoke(true)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(screenStateReceiver, filter)
    }

    private fun unregisterScreenReceiver() {
        screenStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { }
            screenStateReceiver = null
        }
    }
}
