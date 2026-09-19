package com.darusc.vcamdroid.video

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (ratioWidth != width || ratioHeight != height) {
            ratioWidth = width
            ratioHeight = height
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                requestLayout()
            } else {
                post { requestLayout() }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            // Fit inside available bounds preserving aspect ratio (no stretch or crop)
            if (width.toLong() * ratioHeight < height.toLong() * ratioWidth) {
                setMeasuredDimension(width, (width.toLong() * ratioHeight / ratioWidth).toInt())
            } else {
                setMeasuredDimension((height.toLong() * ratioWidth / ratioHeight).toInt(), height)
            }
        }
    }
}
