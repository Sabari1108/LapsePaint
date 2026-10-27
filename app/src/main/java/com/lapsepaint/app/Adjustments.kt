package com.lapsepaint.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A layer adjustment, kept in the artwork's history like a stroke so undo,
 * reopening and the time-lapse all apply it at the same point.
 *
 * HSB: a = hue shift (degrees, -180..180), b = saturation (0..2, 1 = unchanged),
 *      c = brightness (-1..1), d = contrast (0..2, 1 = unchanged).
 * BLUR: a = radius in canvas pixels.
 * INVERT: no values.
 * TRANSFORM: [v] = centre x, centre y, move x, move y, scale x, scale y, rotation
 *      (degrees). Scale and rotation pivot on the centre; a negative scale flips.
 * CLEAR: [v] = inverted (0/1), then the selection outline as x, y pairs.
 * FILL: [v] = inverted, colour high 16 bits, colour low 16 bits, then the outline.
 *      (The colour is split so every value is a plain number that saves exactly.)
 */
class Adjustment(val type: Int, val v: FloatArray) {
    constructor(type: Int, a: Float = 0f, b: Float = 0f, c: Float = 0f, d: Float = 0f) : this(type, floatArrayOf(a, b, c, d))

    val a get() = v.getOrElse(0) { 0f }
    val b get() = v.getOrElse(1) { 0f }
    val c get() = v.getOrElse(2) { 0f }
    val d get() = v.getOrElse(3) { 0f }

    /** For CLEAR and FILL: the selection, in canvas coordinates. */
    fun selectionPath(): android.graphics.Path {
        val start = if (type == FILL) 3 else 1
        return android.graphics.Path().apply {
            if (v.size >= start + 6) {
                moveTo(v[start], v[start + 1])
                var i = start + 2
                while (i + 1 < v.size) {
                    lineTo(v[i], v[i + 1])
                    i += 2
                }
                close()
            }
            fillType = if (v[0] != 0f) android.graphics.Path.FillType.INVERSE_WINDING else android.graphics.Path.FillType.WINDING
        }
    }

    val fillColor get() = (v[1].toInt() shl 16) or v[2].toInt()

    /** For TRANSFORM: maps old canvas positions to new ones. */
    fun matrix() = Matrix().apply {
        postTranslate(-v[0], -v[1])
        postScale(v[4], v[5])
        postRotate(v[6])
        postTranslate(v[0] + v[2], v[1] + v[3])
    }

    fun colorFilter(): ColorMatrixColorFilter? = when (type) {
        HSB -> ColorMatrixColorFilter(hsbMatrix())
        INVERT -> ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)),
        )
        else -> null
    }

    private fun hsbMatrix(): ColorMatrix {
        // Hue rotation that keeps brightness steady (luminance weights 0.213 / 0.715 / 0.072).
        val rad = Math.toRadians(a.toDouble())
        val cs = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        val m = ColorMatrix(floatArrayOf(
            lr + cs * (1 - lr) + sn * -lr, lg + cs * -lg + sn * -lg, lb + cs * -lb + sn * (1 - lb), 0f, 0f,
            lr + cs * -lr + sn * 0.143f, lg + cs * (1 - lg) + sn * 0.140f, lb + cs * -lb + sn * -0.283f, 0f, 0f,
            lr + cs * -lr + sn * -(1 - lr), lg + cs * -lg + sn * lg, lb + cs * (1 - lb) + sn * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        m.postConcat(ColorMatrix().apply { setSaturation(b) })
        val t = 128f * (1f - d) + c * 255f
        m.postConcat(ColorMatrix(floatArrayOf(d, 0f, 0f, 0f, t, 0f, d, 0f, 0f, t, 0f, 0f, d, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    companion object {
        const val HSB = 1
        const val BLUR = 2
        const val INVERT = 3
        const val TRANSFORM = 4
        const val CLEAR = 5
        const val FILL = 6

        fun clear(inverted: Boolean, outline: FloatArray) =
            Adjustment(CLEAR, floatArrayOf(if (inverted) 1f else 0f) + outline)

        fun fill(inverted: Boolean, color: Int, outline: FloatArray) = Adjustment(
            FILL,
            floatArrayOf(if (inverted) 1f else 0f, (color ushr 16).toFloat(), (color and 0xFFFF).toFloat()) + outline,
        )
    }
}

/**
 * Blurs [bmp] in place: three box-blur passes each way, which looks like a
 * Gaussian blur. Works on premultiplied colour so transparent edges don't go dark.
 */
fun blurBitmap(bmp: Bitmap, radius: Int) {
    if (radius < 1) return
    val w = bmp.width
    val h = bmp.height
    var src = IntArray(w * h)
    bmp.getPixels(src, 0, w, 0, 0, w, h)
    for (i in src.indices) {
        val p = src[i]
        val a = p ushr 24
        src[i] = (a shl 24) or
            ((((p shr 16) and 0xFF) * a / 255) shl 16) or
            ((((p shr 8) and 0xFF) * a / 255) shl 8) or
            ((p and 0xFF) * a / 255)
    }
    var dst = IntArray(w * h)
    val box = max(1, radius / 2)
    repeat(3) {
        boxPass(src, dst, w, h, box, horizontal = true)
        boxPass(dst, src, w, h, box, horizontal = false)
    }
    for (i in src.indices) {
        val p = src[i]
        val a = p ushr 24
        src[i] = if (a == 0) 0 else (a shl 24) or
            (min(255, ((p shr 16) and 0xFF) * 255 / a) shl 16) or
            (min(255, ((p shr 8) and 0xFF) * 255 / a) shl 8) or
            min(255, (p and 0xFF) * 255 / a)
    }
    bmp.setPixels(src, 0, w, 0, 0, w, h)
}

private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val len = if (horizontal) w else h
    val lines = if (horizontal) h else w
    val step = if (horizontal) 1 else w
    val div = 2 * r + 1
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        fun at(i: Int) = src[base + i.coerceIn(0, len - 1) * step]
        var sa = 0
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val p = at(i)
            sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
        }
        for (i in 0 until len) {
            dst[base + i * step] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val out = at(i - r)
            val inn = at(i + r + 1)
            sa += (inn ushr 24) - (out ushr 24)
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/** The Adjustments menu and its slider panel, acting on the selected layer. */
class AdjustmentsPanel(
    private val activity: Activity,
    private val drawing: DrawingView,
    private val flash: (String) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun showMenu() {
        val layer = drawing.artwork.activeLayer
        // Open the slider panel once the menu has finished closing; opened
        // while the menu is still on screen, it gets closed along with it.
        val later = { show: () -> Unit -> activity.window.decorView.postDelayed({ show() }, 250) }
        activity.miuixMenu("Adjustments · ${layer.name}", listOf(
            "Hue, Saturation, Brightness" to { later { showHsb() } },
            "Gaussian Blur" to { later { showBlur() } },
            "Invert colours" to {
                drawing.applyAdjustment(Adjustment(Adjustment.INVERT))
                flash("Inverted ${layer.name}")
            },
        ))
    }

    private class Slider(val label: String, val min: Int, val max: Int, val start: Int, val unit: String)

    private fun showHsb() = showSliders(
        "Hue, Saturation, Brightness",
        listOf(
            Slider("Hue", -180, 180, 0, "°"),
            Slider("Saturation", 0, 200, 100, "%"),
            Slider("Brightness", -100, 100, 0, "%"),
            Slider("Contrast", 0, 200, 100, "%"),
        ),
    ) { v -> Adjustment(Adjustment.HSB, v[0].toFloat(), v[1] / 100f, v[2] / 100f, v[3] / 100f) }

    private fun showBlur() = showSliders(
        "Gaussian Blur",
        listOf(Slider("Radius", 0, 150, 0, " px")),
    ) { v -> Adjustment(Adjustment.BLUR, v[0].toFloat()) }

    /** Slider panel along the bottom: previews live, then Apply or Cancel. */
    private fun showSliders(title: String, sliders: List<Slider>, build: (IntArray) -> Adjustment) {
        val values = IntArray(sliders.size) { sliders[it].start }
        var applied = false

        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sliders.forEachIndexed { i, s ->
            val value = TextView(activity).apply {
                textSize = 13f
                setTextColor(0xFFE8E8E8.toInt())
                gravity = Gravity.END
                text = "${s.start}${s.unit}"
            }
            val bar = SeekBar(activity).apply {
                max = s.max - s.min
                progress = s.start - s.min
                progressTintList = ColorStateList.valueOf(0xFF4FA3F7.toInt())
                thumbTintList = ColorStateList.valueOf(0xFF4FA3F7.toInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        values[i] = progress + s.min
                        value.text = "${values[i]}${s.unit}"
                        drawing.previewAdjustment(build(values))
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
            rows.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = s.label
                    textSize = 14f
                    setTextColor(0xFFE8E8E8.toInt())
                }, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(value, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }

        fun action(label: String, color: Int) = TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(color)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val cancel = action("Cancel", 0xFFBFBFBF.toInt())
        val apply = action("Apply", 0xFF4FA3F7.toInt())

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(18), dp(12), dp(10), dp(8))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = "$title · ${drawing.artwork.activeLayer.name}"
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(cancel)
                addView(apply)
            })
            addView(rows)
        }

        val popup = PopupWindow(content, dp(620), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            setOnDismissListener { if (!applied) drawing.previewAdjustment(null) }
        }
        cancel.setOnClickListener { popup.dismiss() }
        apply.setOnClickListener {
            applied = true
            drawing.applyAdjustment(build(values))
            popup.dismiss()
        }
        popup.showAtLocation(activity.window.decorView, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(24))
    }
}
