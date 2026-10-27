package com.lapsepaint.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** Saturation/brightness square with a hue bar underneath. */
class ColorPickerView(context: Context) : View(context) {

    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 0f, 0f)
    private val density = resources.displayMetrics.density
    private val gap = 14 * density
    private val barH = 26 * density
    private val radius = 10 * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }
    private val square = RectF()
    private val bar = RectF()
    private var draggingHue = false

    private val hueColors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }

    val color get() = Color.HSVToColor(hsv)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w + gap + barH).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        square.set(0f, 0f, w.toFloat(), w.toFloat())
        bar.set(0f, w + gap, w.toFloat(), w + gap + barH)
    }

    override fun onDraw(canvas: Canvas) {
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        fill.shader = LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(square, radius, radius, fill)
        fill.shader = LinearGradient(0f, square.top, 0f, square.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(square, radius, radius, fill)

        fill.shader = LinearGradient(bar.left, 0f, bar.right, 0f, hueColors, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bar, barH / 2, barH / 2, fill)
        fill.shader = null

        val sx = square.left + hsv[1] * square.width()
        val sy = square.top + (1f - hsv[2]) * square.height()
        canvas.drawCircle(sx, sy, 9 * density, ring)
        val hx = bar.left + hsv[0] / 360f * bar.width()
        canvas.drawCircle(hx, bar.centerY(), barH / 2 - 1 * density, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) draggingHue = e.y > square.bottom
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            if (draggingHue) {
                hsv[0] = ((e.x - bar.left) / bar.width()).coerceIn(0f, 1f) * 359.9f
            } else {
                hsv[1] = ((e.x - square.left) / square.width()).coerceIn(0f, 1f)
                hsv[2] = 1f - ((e.y - square.top) / square.height()).coerceIn(0f, 1f)
            }
            invalidate()
            onColorChanged?.invoke(color)
        }
        return true
    }
}

/** The last colours painted with, newest first, kept on the device. */
object RecentColors {
    private const val MAX = 10

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("colors", Context.MODE_PRIVATE)

    fun get(ctx: Context): List<Int> =
        prefs(ctx).getString("recent", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }

    fun add(ctx: Context, color: Int) {
        val list = (listOf(color) + get(ctx).filter { it != color }).take(MAX)
        prefs(ctx).edit().putString("recent", list.joinToString(",")).apply()
    }
}
