package com.darusc.vcamdroid.video.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.darusc.vcamdroid.R

class ScannerOverlay(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private val widthPercentage = 0.7f
    private val heightPercentage = 0.35f
    val rect = Rect()
    var size: Size = Size(0, 0)

    private val cornerSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics
    )
    private val hintOffset = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics
    )

    private val paint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textView: TextView = TextView(context).apply {
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        context.theme.obtainStyledAttributes(attrs, R.styleable.ScannerOverlay, 0, 0).apply {
            try {
                textView.text = getString(R.styleable.ScannerOverlay_text) ?: ""
            } finally {
                recycle()
            }
        }
        addView(textView)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        size = Size(width, height)

        val left = (width - width * widthPercentage) / 2
        val top = (height - height * heightPercentage) / 2
        val right = width - left
        val bottom = height - top
        rect.set(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

        canvas.drawArc(left, top, left + cornerSize, top + cornerSize, -180f, 90f, false, paint)
        canvas.drawArc(right - cornerSize, top, right, top + cornerSize, 0f, -90f, false, paint)
        canvas.drawArc(right - cornerSize, bottom - cornerSize, right, bottom, 0f, 90f, false, paint)
        canvas.drawArc(left, bottom - cornerSize, left + cornerSize, bottom, -180f, -90f, false, paint)

        textView.y = (top - hintOffset).coerceAtLeast(0f)
        textView.maxWidth = (width * widthPercentage).toInt()
    }
}
