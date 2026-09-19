package com.darusc.vcamdroid.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun formatBitrateKbps(kbps: Int): String {
    if (kbps <= 0) return "—"
    if (kbps < 1000) return "$kbps kbps"
    val mbps = kbps / 1000.0
    return if (kbps % 1000 == 0) {
        "${kbps / 1000} Mbps"
    } else {
        String.format("%.1f Mbps", mbps)
    }
}

fun View.applySystemBarInsets(top: Boolean = true, bottom: Boolean = true) {
    val startLeft = paddingLeft
    val startTop = paddingTop
    val startRight = paddingRight
    val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            startLeft + bars.left,
            if (top) startTop + bars.top else startTop,
            startRight + bars.right,
            if (bottom) startBottom + bars.bottom else startBottom
        )
        insets
    }
}
