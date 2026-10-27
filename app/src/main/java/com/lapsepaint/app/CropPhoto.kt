package com.lapsepaint.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Shows a photo with a crop box: drag a corner to resize it, drag inside to move it. */
class CropView(context: Context, private val bitmap: Bitmap) : View(context) {

    /** The crop, in the photo's own pixels. */
    val crop = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())

    /** Width / height to keep, or null for a free crop. */
    var aspect: Float? = null
        set(value) {
            field = value
            if (value != null) fitAspect(value)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val minSize = 32f
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val thirds = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = 0x88FFFFFF.toInt()
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    private var fit = 1f
    private var offX = 0f
    private var offY = 0f

    private var corner = -1 // 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left
    private var moving = false
    private var lastX = 0f
    private var lastY = 0f

    private fun layoutFit() {
        val pad = 24 * density
        fit = min((width - 2 * pad) / bitmap.width, (height - 2 * pad) / bitmap.height)
        offX = (width - bitmap.width * fit) / 2f
        offY = (height - bitmap.height * fit) / 2f
    }

    private fun sx(x: Float) = offX + x * fit
    private fun sy(y: Float) = offY + y * fit

    /** Largest box of the given ratio that fits the photo, centred on the current crop. */
    private fun fitAspect(a: Float) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        var w = bw
        var h = w / a
        if (h > bh) {
            h = bh
            w = h * a
        }
        val cx = crop.centerX().coerceIn(w / 2f, bw - w / 2f)
        val cy = crop.centerY().coerceIn(h / 2f, bh - h / 2f)
        crop.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        layoutFit()
        canvas.drawColor(Color.BLACK)
        dst.set(offX, offY, sx(bitmap.width.toFloat()), sy(bitmap.height.toFloat()))
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)

        val l = sx(crop.left)
        val t = sy(crop.top)
        val r = sx(crop.right)
        val b = sy(crop.bottom)
        // Darken everything outside the crop.
        canvas.drawRect(dst.left, dst.top, dst.right, t, shade)
        canvas.drawRect(dst.left, b, dst.right, dst.bottom, shade)
        canvas.drawRect(dst.left, t, l, b, shade)
        canvas.drawRect(r, t, dst.right, b, shade)

        for (i in 1..2) {
            val x = l + (r - l) * i / 3f
            val y = t + (b - t) * i / 3f
            canvas.drawLine(x, t, x, b, thirds)
            canvas.drawLine(l, y, r, y, thirds)
        }
        canvas.drawRect(l, t, r, b, border)
        val hr = 8 * density
        for ((x, y) in listOf(l to t, r to t, r to b, l to b)) canvas.drawCircle(x, y, hr, handle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corners = listOf(sx(crop.left) to sy(crop.top), sx(crop.right) to sy(crop.top),
                    sx(crop.right) to sy(crop.bottom), sx(crop.left) to sy(crop.bottom))
                corner = corners.indexOfFirst { (x, y) -> hypot(e.x - x, e.y - y) < 36 * density }
                moving = corner < 0 && e.x in sx(crop.left)..sx(crop.right) && e.y in sy(crop.top)..sy(crop.bottom)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                if (moving) {
                    val dx = ((e.x - lastX) / fit).coerceIn(-crop.left, bw - crop.right)
                    val dy = ((e.y - lastY) / fit).coerceIn(-crop.top, bh - crop.bottom)
                    crop.offset(dx, dy)
                } else if (corner >= 0) {
                    dragCorner(((e.x - offX) / fit).coerceIn(0f, bw), ((e.y - offY) / fit).coerceIn(0f, bh))
                }
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                corner = -1
                moving = false
            }
        }
        return true
    }

    private fun dragCorner(px: Float, py: Float) {
        // The opposite corner stays put.
        val ox = if (corner == 0 || corner == 3) crop.right else crop.left
        val oy = if (corner == 0 || corner == 1) crop.bottom else crop.top
        var w = max(minSize, abs(px - ox))
        var h = max(minSize, abs(py - oy))
        val a = aspect
        if (a != null) {
            // Keep the ratio, following whichever direction moved further, inside the photo.
            if (w / h > a) h = w / a else w = h * a
            val roomX = if (px < ox) ox else bitmap.width - ox
            val roomY = if (py < oy) oy else bitmap.height - oy
            val shrink = min(1f, min(roomX / w, roomY / h))
            w *= shrink
            h *= shrink
        }
        val left = if (px < ox) ox - w else ox
        val top = if (py < oy) oy - h else oy
        crop.set(left, top, left + w, top + h)
        // Which corner is held can change if the box flipped over.
        corner = when {
            px < ox && py < oy -> 0
            px >= ox && py < oy -> 1
            px >= ox && py >= oy -> 2
            else -> 3
        }
    }

    fun cropped(): Bitmap {
        val x = crop.left.toInt().coerceIn(0, bitmap.width - 1)
        val y = crop.top.toInt().coerceIn(0, bitmap.height - 1)
        val w = crop.width().toInt().coerceIn(1, bitmap.width - x)
        val h = crop.height().toInt().coerceIn(1, bitmap.height - y)
        if (x == 0 && y == 0 && w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }
}

/** Full-screen crop step shown after picking a photo. [onDone] gets the photo to insert. */
fun showCropPhoto(activity: Activity, bitmap: Bitmap, onDone: (Bitmap) -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val accent = 0xFF4FA3F7.toInt()

    val cropView = CropView(activity, bitmap)
    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)

    fun button(label: String, color: Int, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 16f
        setTextColor(color)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    val top = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        addView(button("Cancel", 0xFFBFBFBF.toInt()) { dialog.dismiss() })
        addView(TextView(activity).apply {
            text = "Crop photo"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(button("Use full photo", 0xFFE8E8E8.toInt()) {
            dialog.dismiss()
            onDone(bitmap)
        })
        addView(button("Done", accent) {
            dialog.dismiss()
            onDone(cropView.cropped())
        })
    }

    val chips = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(16))
    }
    val ratios = listOf<Pair<String, Float?>>("Free" to null, "Square" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f)
    val chipViews = ArrayList<TextView>()
    fun select(index: Int) {
        chipViews.forEachIndexed { i, chip ->
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (i == index) accent else 0xFF2C2C2C.toInt())
            }
        }
        cropView.aspect = ratios[index].second
    }
    ratios.forEachIndexed { i, (label, _) ->
        val chip = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener { select(i) }
        }
        chipViews.add(chip)
        chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
    }
    select(0)

    dialog.setContentView(LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(top)
        addView(cropView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(chips)
    })
    dialog.show()
}
