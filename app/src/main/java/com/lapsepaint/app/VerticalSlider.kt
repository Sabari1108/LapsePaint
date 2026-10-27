package com.lapsepaint.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** The sidebar slider: a wide rounded track with a pill thumb. Value is 0..1, top = 1. */
class VerticalSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var value = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onChange: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val thumbH = 22 * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D3D3D.toInt() }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D9D9.toInt() }
    private val rect = RectF()

    private val range get() = height - thumbH

    override fun onDraw(canvas: Canvas) {
        val inset = 3 * density
        rect.set(inset, 0f, width - inset, height.toFloat())
        val r = (width - 2 * inset) / 2f
        canvas.drawRoundRect(rect, r, r, track)

        val top = (1f - value) * range
        val pad = 7 * density
        rect.set(pad, top + 3 * density, width - pad, top + thumbH - 3 * density)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = 1f - (e.y - thumbH / 2) / range
                onChange?.invoke(value)
            }
        }
        return true
    }
}
