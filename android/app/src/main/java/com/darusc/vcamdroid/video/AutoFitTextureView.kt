package com.darusc.vcamdroid.video

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.roundToInt

/**
 * A [TextureView] that can be adjusted to a specified aspect ratio
 * and ensures the preview fits cleanly within the screen bounds without cropping or distortion.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (ratioWidth != width || ratioHeight != height) {
            ratioWidth = width
            ratioHeight = height
            post { requestLayout() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            // Aspect-fit (letterboxed): fit inside parent bounds without cropping
            if (width.toFloat() / height.toFloat() > ratioWidth.toFloat() / ratioHeight.toFloat()) {
                // Available width is looser than height -> constrain by height
                setMeasuredDimension(
                    (height.toFloat() * ratioWidth.toFloat() / ratioHeight.toFloat()).roundToInt(),
                    height
                )
            } else {
                // Available height is looser than width -> constrain by width
                setMeasuredDimension(
                    width,
                    (width.toFloat() * ratioHeight.toFloat() / ratioWidth.toFloat()).roundToInt()
                )
            }
        }
    }
}
