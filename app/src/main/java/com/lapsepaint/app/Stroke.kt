package com.lapsepaint.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The brush engine. A stroke is painted by stamping "dabs" (small round
 * textured images) along it. The path between pen samples is a smooth
 * quadratic curve: from the midpoint of one pair of samples to the midpoint
 * of the next, with the sample between them as the control point.
 *
 * Dab size and strength follow pressure and tilt as the [Brush] says. Random
 * scatter/jitter is seeded per stroke, so a replay (undo, reopen, time-lapse)
 * paints exactly the same pixels. Call [finish] once the stroke ends to paint
 * the last half-segment. Each thread should use its own instance.
 */
class StrokePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val erase = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val matrix = Matrix()
    private val masks = HashMap<String, Bitmap>()
    private var filterColor = 0
    private var filter: PorterDuffColorFilter? = null
    private val hsv = FloatArray(3)

    /** Reads the colour already on a layer at a canvas position (for wet mixing); set by the surface. */
    var sample: ((layerId: Int, x: Float, y: Float) -> Int)? = null

    // Walking state for the stroke being painted.
    private var active: Stroke? = null
    private var toNextDab = 0f
    private var travelled = 0f
    private var random = Random(0)

    private fun baseColor(stroke: Stroke) = if (stroke.isEraser) Color.BLACK else stroke.color or 0xFF000000.toInt()

    private fun setColor(color: Int) {
        if (filter == null || color != filterColor) {
            filterColor = color
            filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        paint.colorFilter = filter
    }

    private fun configure(stroke: Stroke, clearPixels: Boolean) {
        setColor(baseColor(stroke))
        paint.xfermode = if (clearPixels) erase else null
    }

    /** [clearPixels] makes eraser dabs cut straight through what is underneath. */
    fun drawSegment(canvas: Canvas, stroke: Stroke, index: Int, clearPixels: Boolean) {
        stroke.image?.let { img ->
            if (index == 0) img.bitmap?.let { canvas.drawBitmap(it, null, img.rect, imagePaint) }
            return
        }
        configure(stroke, clearPixels)
        val pts = stroke.points
        if (index == 0 || active !== stroke) {
            active = stroke
            random = Random(stroke.seed)
            toNextDab = 0f
            travelled = 0f
        }
        val c = pts[index]
        if (index == 0) {
            dab(canvas, stroke, c.x, c.y, c.pressure, c.tilt, 0f, 0f, 0f)
            toNextDab = spacing(stroke, c.pressure)
            return
        }
        val b = pts[index - 1]
        // Distance between pen samples stands in for speed (samples arrive at a steady rate).
        val speed = hypot(c.x - b.x, c.y - b.y)
        val mx = (b.x + c.x) / 2f
        val my = (b.y + c.y) / 2f
        val mp = (b.pressure + c.pressure) / 2f
        val mt = (b.tilt + c.tilt) / 2f
        if (index == 1) {
            walk(canvas, stroke, b.x, b.y, b.pressure, b.tilt, mx, my, mp, mt, speed)
            return
        }
        val a = pts[index - 2]
        val sx = (a.x + b.x) / 2f
        val sy = (a.y + b.y) / 2f
        val sp = (a.pressure + b.pressure) / 2f
        val st = (a.tilt + b.tilt) / 2f
        // Flatten the curve into short straight pieces and walk along them.
        val approx = hypot(b.x - sx, b.y - sy) + hypot(mx - b.x, my - b.y)
        val pieces = ceil(approx / 4f).toInt().coerceIn(1, 64)
        var px = sx
        var py = sy
        var pp = sp
        var pt = st
        for (k in 1..pieces) {
            val t = k / pieces.toFloat()
            val u = 1f - t
            val qx = u * u * sx + 2 * u * t * b.x + t * t * mx
            val qy = u * u * sy + 2 * u * t * b.y + t * t * my
            val qp = sp + (mp - sp) * t
            val qt = st + (mt - st) * t
            walk(canvas, stroke, px, py, pp, pt, qx, qy, qp, qt, speed)
            px = qx; py = qy; pp = qp; pt = qt
        }
    }

    fun finish(canvas: Canvas, stroke: Stroke, clearPixels: Boolean) {
        val pts = stroke.points
        if (pts.size < 2 || stroke.image != null) return
        configure(stroke, clearPixels)
        val b = pts[pts.size - 2]
        val c = pts.last()
        walk(canvas, stroke, (b.x + c.x) / 2f, (b.y + c.y) / 2f, (b.pressure + c.pressure) / 2f, (b.tilt + c.tilt) / 2f,
            c.x, c.y, c.pressure, c.tilt, hypot(c.x - b.x, c.y - b.y))
    }

    /** Stamps dabs every "spacing" pixels along a straight piece, carrying the remainder over. */
    private fun walk(
        canvas: Canvas, stroke: Stroke,
        x0: Float, y0: Float, p0: Float, t0: Float,
        x1: Float, y1: Float, p1: Float, t1: Float,
        speed: Float,
    ) {
        val len = hypot(x1 - x0, y1 - y0)
        if (len <= 0f) return
        val direction = Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        var d = toNextDab
        while (d <= len) {
            val f = d / len
            val p = p0 + (p1 - p0) * f
            dab(canvas, stroke, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, p, t0 + (t1 - t0) * f, speed, direction, travelled + d)
            d += spacing(stroke, p)
        }
        toNextDab = d - len
        travelled += len
    }

    private fun diameter(stroke: Stroke, pressure: Float, tilt: Float): Float {
        val b = stroke.brush
        val byPressure = 1f + (max(0.06f, pressure.pow(0.7f)) - 1f) * b.pressureSize
        return stroke.size * byPressure * (1f + b.tiltSize * tilt * 1.5f)
    }

    private fun spacing(stroke: Stroke, pressure: Float) =
        max(0.5f, diameter(stroke, pressure, 0f) * stroke.brush.spacing)

    private fun dab(
        canvas: Canvas, stroke: Stroke, x: Float, y: Float, pressure: Float, tilt: Float,
        speed: Float, direction: Float, distance: Float,
    ) {
        val b = stroke.brush
        // Always draw the same random numbers for a given brush so replays stay in step.
        val jitter = random.nextFloat()
        val ox = random.nextFloat() * 2f - 1f
        val oy = random.nextFloat() * 2f - 1f
        val spin = random.nextFloat() * 360f
        val colourJitter = b.hueJitter > 0f || b.brightnessJitter > 0f
        val hueShift = if (colourJitter) random.nextFloat() * 2f - 1f else 0f
        val lightShift = if (colourJitter) random.nextFloat() * 2f - 1f else 0f

        var dia = diameter(stroke, pressure, tilt) * (1f - b.sizeJitter * jitter * 0.8f)
        // Taper: the stroke swells in from a point over its first stretch.
        if (b.taper > 0f) {
            val taperLength = b.taper * stroke.size * 8f
            val t = (distance / taperLength).coerceIn(0f, 1f)
            dia *= 0.12f + 0.88f * (t * t * (3f - 2f * t))
        }
        // Speed: quick flicks thin the stroke.
        if (b.velocitySize > 0f) {
            dia *= 1f - b.velocitySize * (speed / (stroke.size * 1.5f)).coerceIn(0f, 0.75f)
        }
        if (dia < 0.5f) return
        val alpha = b.flow * (1f + (pressure - 1f) * b.pressureOpacity) * (1f - b.tiltOpacity * tilt * 0.8f)
        val a = (alpha * 255).toInt()
        if (a <= 0) return
        paint.alpha = a.coerceAtMost(255)

        if (!stroke.isEraser && (colourJitter || b.wetMix > 0f)) {
            var c = stroke.color or 0xFF000000.toInt()
            if (b.wetMix > 0f) {
                // Wet paint: blend towards whatever colour is already under the dab.
                val under = sample?.invoke(stroke.layerId, x, y) ?: 0
                val ua = (under ushr 24) / 255f
                if (ua > 0f) c = mix(c, under, b.wetMix * ua)
            }
            if (colourJitter) {
                Color.colorToHSV(c, hsv)
                hsv[0] = ((hsv[0] + hueShift * b.hueJitter * 90f) % 360f + 360f) % 360f
                hsv[2] = (hsv[2] + lightShift * b.brightnessJitter * 0.45f).coerceIn(0f, 1f)
                c = Color.HSVToColor(hsv)
            }
            setColor(c)
        } else {
            setColor(baseColor(stroke))
        }

        val rotation = when (b.angleIndex) {
            1 -> direction
            2 -> 45f
            else -> spin
        }
        val mask = maskFor(b, dia)
        val half = mask.width / 2f
        matrix.reset()
        matrix.postTranslate(-half, -half)
        matrix.postRotate(rotation)
        matrix.postScale(dia / mask.width, dia / mask.width)
        matrix.postTranslate(x + ox * b.scatter * dia, y + oy * b.scatter * dia)
        canvas.drawBitmap(mask, matrix, paint)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1f - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Dab images at a few resolutions, so small dabs stay crisp instead of shimmering. */
    private var lastMask: Bitmap? = null
    private var lastTip = -1
    private var lastSoftness = -1f
    private var lastGrain = -1f
    private var lastSize = -1

    private fun maskFor(brush: Brush, diameter: Float): Bitmap {
        var size = 8
        while (size < diameter && size < 256) size *= 2
        val tip = brush.tipIndex
        lastMask?.let {
            if (size == lastSize && tip == lastTip && brush.softness == lastSoftness && brush.grain == lastGrain) return it
        }
        val key = "${tip}_${brush.softness}_${brush.grain}_$size"
        val mask = masks.getOrPut(key) { makeMask(size, brush.softness, brush.grain, tip) }
        lastMask = mask; lastTip = tip; lastSoftness = brush.softness; lastGrain = brush.grain; lastSize = size
        return mask
    }

    /**
     * Builds one dab: white with the shape in the alpha channel. Each tip works out how
     * far inside its shape a point is (in units of the dab's radius); the edge fades
     * over [softness], then [grain] speckles it.
     */
    private fun makeMask(size: Int, softness: Float, grain: Float, tip: Int): Bitmap {
        val px = IntArray(size * size)
        val r = size / 2f
        val edge = max(1.5f / r, softness)
        val noise = Random(size * 31 + 7)
        fun smooth(v: Float): Float {
            val a = v.coerceIn(0f, 1f)
            return a * a * (3f - 2f * a)
        }
        // Bristle and splatter tips are clusters of small round blobs at fixed spots.
        class Blob(val x: Float, val y: Float, val r: Float, val a: Float)
        val blobs = ArrayList<Blob>()
        if (tip == 2 || tip == 3) {
            val rnd = Random(if (tip == 2) 99 else 177)
            val count = if (tip == 2) 26 else 12
            repeat(count) {
                val ang = rnd.nextFloat() * 2f * Math.PI.toFloat()
                val dist = kotlin.math.sqrt(rnd.nextFloat()) * if (tip == 2) 0.78f else 0.85f
                val br = if (tip == 2) 0.12f + rnd.nextFloat() * 0.1f else 0.05f + rnd.nextFloat() * 0.22f
                blobs.add(Blob(kotlin.math.cos(ang) * dist, kotlin.math.sin(ang) * dist, br, 0.55f + rnd.nextFloat() * 0.45f))
            }
        }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val u = (x + 0.5f - r) / r
                val v = (y + 0.5f - r) / r
                var a = when (tip) {
                    1 -> { // Flat: a thin ellipse, like a chisel or calligraphy nib.
                        val d = kotlin.math.sqrt(u * u + (v / 0.28f) * (v / 0.28f))
                        smooth((1f - d) * 0.28f / max(edge * 0.28f, 1.5f / r))
                    }
                    2, 3 -> { // Bristle / splatter: the strongest blob covering this point.
                        var best = 0f
                        for (bl in blobs) {
                            val inside = bl.r - hypot(u - bl.x, v - bl.y)
                            val bEdge = max(1.5f / r, edge * bl.r)
                            best = max(best, smooth(inside / bEdge) * bl.a)
                        }
                        best
                    }
                    4 -> { // Leaf: where two offset circles overlap, pointed at both ends.
                        val inside = kotlin.math.min(1.15f - hypot(u, v - 0.6f), 1.15f - hypot(u, v + 0.6f))
                        smooth(inside / max(edge * 0.5f, 1.5f / r))
                    }
                    else -> smooth((1f - hypot(u, v)) / edge) // Round
                }
                if (grain > 0f) a *= 1f - grain * noise.nextFloat() * 0.9f
                px[y * size + x] = ((a * 255).toInt() shl 24) or 0xFFFFFF
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

/**
 * The painted pixels: one transparent bitmap per layer, plus a "wet" bitmap
 * for the stroke in progress. Strokes with less than full opacity are drawn
 * wet at full strength and blended in once, so overlapping segments of the
 * same stroke don't build up darker spots.
 *
 * Used both by the live canvas (scale 1) and the timelapse exporter
 * (scaled down to video size), so the video matches what you drew.
 */
class PaintSurface(
    val width: Int,
    val height: Int,
    private val scale: Float = 1f,
    /** True for the on-screen canvas: draw changing bitmaps through [TileMirror]s. */
    private val tiled: Boolean = false,
) {
    private class LayerPixels(val bitmap: Bitmap, val canvas: Canvas, val pixels: Canvas)

    private val layerPixels = HashMap<Int, LayerPixels>()
    private val wet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val wetCanvas = Canvas(wet).apply { scale(scale, scale) }
    private val wetMirror = if (tiled) TileMirror(wet) else null

    // The layer being painted on gets a tile mirror; switching layers moves it.
    private var hotLayerId = -1
    private var hotMirror: TileMirror? = null

    private fun mirrorFor(layerId: Int): TileMirror? {
        if (!tiled) return null
        if (hotLayerId != layerId || hotMirror == null) {
            val bmp = layerPixels[layerId]?.bitmap ?: return null
            hotLayerId = layerId
            hotMirror = TileMirror(bmp)
        }
        return hotMirror
    }

    /** Something other than a dab changed this layer: refresh all of its tiles. */
    private fun touchedAll(layerId: Int) {
        if (layerId == hotLayerId) hotMirror?.markAll()
    }

    /** A dab of [stroke] near point [index] changed the layer (or the wet bitmap). */
    private fun touched(stroke: Stroke, index: Int, wetToo: Boolean) {
        if (!tiled || stroke.points.isEmpty()) return
        val p = stroke.points[index.coerceIn(0, stroke.points.lastIndex)]
        val b = stroke.brush
        val reach = (stroke.size * (1f + b.scatter) * (1f + b.tiltSize * 1.5f) * 1.2f + 4f) * scale
        val x = p.x * scale
        val y = p.y * scale
        if (wetToo) wetMirror?.mark(x - reach, y - reach, x + reach, y + reach)
        else mirrorFor(stroke.layerId)?.mark(x - reach, y - reach, x + reach, y + reach)
    }
    private val painter = StrokePainter().also { p ->
        p.sample = { layerId, x, y ->
            val bmp = layerPixels[layerId]?.bitmap
            val px = (x * scale).toInt()
            val py = (y * scale).toInt()
            if (bmp == null || px < 0 || py < 0 || px >= bmp.width || py >= bmp.height) 0 else bmp.getPixel(px, py)
        }
    }
    private val blend = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private var wetStroke: Stroke? = null
    private val adjustPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // An adjustment being previewed on one layer, before it is applied.
    private var previewLayerId = -1
    private var previewFilter: android.graphics.ColorFilter? = null
    private var previewBitmap: Bitmap? = null
    private var previewMatrix: Matrix? = null
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun pixelsFor(layerId: Int) = layerPixels.getOrPut(layerId) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        LayerPixels(bmp, Canvas(bmp).apply { scale(scale, scale) }, Canvas(bmp))
    }

    fun layerBitmap(layerId: Int): Bitmap? = layerPixels[layerId]?.bitmap

    private fun startWet(stroke: Stroke) {
        wet.eraseColor(Color.TRANSPARENT)
        wetStroke = stroke
    }

    fun drawPoint(stroke: Stroke, index: Int) {
        stroke.adjustment?.let {
            if (index == 0) applyAdjustment(stroke.layerId, it)
            return
        }
        if (stroke.isSmudge) {
            smudgePoint(stroke, index)
            return
        }
        if (index == 0 && stroke.opacity < 1f) startWet(stroke)
        if (wetStroke === stroke) {
            painter.drawSegment(wetCanvas, stroke, index, clearPixels = false)
            touched(stroke, index, wetToo = true)
        } else {
            painter.drawSegment(pixelsFor(stroke.layerId).canvas, stroke, index, clearPixels = stroke.isEraser)
            touched(stroke, index, wetToo = false)
        }
    }

    /** Moves an in-progress stroke to the wet bitmap and redraws it from scratch (used by QuickShape). */
    fun redrawWet(stroke: Stroke) {
        startWet(stroke)
        wetMirror?.markAll()
        for (i in stroke.points.indices) painter.drawSegment(wetCanvas, stroke, i, clearPixels = false)
    }

    // ---- Smudge ----
    // A small "carry" bitmap holds the paint picked up so far. Each dab lays the carry
    // down (through a soft round mask) and then picks up a little of what is there,
    // so colour gets dragged along the stroke and fades out.

    private var carry: Bitmap? = null
    private var carryCanvas: Canvas? = null
    private var carryMask: Bitmap? = null
    private var carryTemp: Bitmap? = null
    private var carryTempCanvas: Canvas? = null
    private var smudgeX = 0f
    private var smudgeY = 0f
    private var smudgeToNext = 0f
    private val smudgePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pickPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 90 }
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    private fun smudgePoint(stroke: Stroke, index: Int) {
        val px = pixelsFor(stroke.layerId)
        val p = stroke.points[index]
        val x = p.x * scale
        val y = p.y * scale
        if (index == 0) {
            val d = max(4, (stroke.size * scale).toInt())
            carry = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888).also { carryCanvas = Canvas(it) }
            carryTemp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888).also { carryTempCanvas = Canvas(it) }
            carryMask = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888).also { m ->
                val r = d / 2f
                Canvas(m).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.RadialGradient(r, r, r, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
                })
            }
            pickUp(px.bitmap, x, y, first = true)
            smudgeX = x
            smudgeY = y
            smudgeToNext = max(1f, d * 0.12f)
            return
        }
        val c = carry ?: return
        val spacing = max(1f, c.width * 0.12f)
        val len = kotlin.math.hypot(x - smudgeX, y - smudgeY)
        var d = smudgeToNext
        while (d <= len) {
            val f = d / len
            val cx = smudgeX + (x - smudgeX) * f
            val cy = smudgeY + (y - smudgeY) * f
            // Lighter pressure smudges more gently.
            smudgeDab(px.pixels, cx, cy, stroke.opacity * (0.35f + 0.65f * p.pressure))
            if (tiled) {
                val reach = carry!!.width.toFloat()
                mirrorFor(stroke.layerId)?.mark(cx - reach, cy - reach, cx + reach, cy + reach)
            }
            pickUp(px.bitmap, cx, cy, first = false)
            d += spacing
        }
        smudgeToNext = d - len
        smudgeX = x
        smudgeY = y
    }

    private fun smudgeDab(target: Canvas, cx: Float, cy: Float, strength: Float) {
        val c = carry ?: return
        val tc = carryTempCanvas ?: return
        val temp = carryTemp ?: return
        temp.eraseColor(Color.TRANSPARENT)
        tc.drawBitmap(c, 0f, 0f, null)
        tc.drawBitmap(carryMask!!, 0f, 0f, maskPaint)
        smudgePaint.alpha = (strength * 255).toInt().coerceIn(0, 255)
        target.drawBitmap(temp, cx - c.width / 2f, cy - c.height / 2f, smudgePaint)
    }

    /** Mixes the paint under (cx, cy) into the carry; the first pick-up replaces it. */
    private fun pickUp(layer: Bitmap, cx: Float, cy: Float, first: Boolean) {
        val c = carry ?: return
        val cc = carryCanvas ?: return
        val h = c.width / 2f
        val src = android.graphics.Rect((cx - h).toInt(), (cy - h).toInt(), (cx - h).toInt() + c.width, (cy - h).toInt() + c.height)
        val dst = android.graphics.Rect(0, 0, c.width, c.height)
        if (first) c.eraseColor(Color.TRANSPARENT)
        cc.drawBitmap(layer, src, dst, if (first) null else pickPaint)
    }

    fun endStroke(stroke: Stroke) {
        if (stroke.isSmudge) return
        if (wetStroke === stroke) {
            painter.finish(wetCanvas, stroke, clearPixels = false)
            configureBlend(stroke)
            pixelsFor(stroke.layerId).pixels.drawBitmap(wet, 0f, 0f, blend)
            touchedAll(stroke.layerId)
            dropWet()
        } else {
            painter.finish(pixelsFor(stroke.layerId).canvas, stroke, clearPixels = stroke.isEraser)
            touched(stroke, stroke.points.lastIndex, wetToo = false)
        }
    }

    fun drawStroke(stroke: Stroke) {
        for (i in stroke.points.indices) drawPoint(stroke, i)
        endStroke(stroke)
    }

    fun dropWet() {
        wetStroke = null
        wet.eraseColor(Color.TRANSPARENT)
        wetMirror?.markAll()
    }

    fun clearLayer(layerId: Int) {
        layerPixels[layerId]?.bitmap?.eraseColor(Color.TRANSPARENT)
        touchedAll(layerId)
    }

    fun removeLayer(layerId: Int) {
        if (layerId == hotLayerId) {
            hotLayerId = -1
            hotMirror = null
        }
        layerPixels.remove(layerId)?.bitmap?.recycle()
    }

    /** Redraws one layer from the artwork's history. */
    fun rebuildLayer(artwork: Artwork, layerId: Int) {
        clearLayer(layerId)
        for (s in artwork.history) if (s.layerId == layerId) drawStroke(s)
        touchedAll(layerId)
    }

    /** A transform's matrix in this surface's pixels (the surface may be scaled down). */
    private fun pixelMatrix(adj: Adjustment) = Matrix().apply {
        postScale(1f / scale, 1f / scale)
        postConcat(adj.matrix())
        postScale(scale, scale)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun applyAdjustment(layerId: Int, adj: Adjustment) {
        applyAdjustmentPixels(layerId, adj)
        touchedAll(layerId)
    }

    private fun applyAdjustmentPixels(layerId: Int, adj: Adjustment) {
        if (adj.type == Adjustment.FILL) {
            fillPaint.color = adj.fillColor
            pixelsFor(layerId).canvas.drawPath(adj.selectionPath(), fillPaint)
            return
        }
        val px = layerPixels[layerId] ?: return
        if (adj.type == Adjustment.CLEAR) {
            px.canvas.save()
            px.canvas.clipPath(adj.selectionPath())
            px.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            px.canvas.restore()
            return
        }
        if (adj.type == Adjustment.TRANSFORM) {
            val copy = px.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            px.bitmap.eraseColor(Color.TRANSPARENT)
            adjustPaint.colorFilter = null
            px.pixels.drawBitmap(copy, pixelMatrix(adj), adjustPaint)
            copy.recycle()
            return
        }
        if (adj.type == Adjustment.BLUR) {
            blurBitmap(px.bitmap, (adj.a * scale).roundToInt())
            return
        }
        val copy = px.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        px.bitmap.eraseColor(Color.TRANSPARENT)
        adjustPaint.colorFilter = adj.colorFilter()
        px.pixels.drawBitmap(copy, 0f, 0f, adjustPaint)
        copy.recycle()
    }

    /**
     * Shows [adj] on one layer without changing its pixels (null clears it). Colour
     * changes preview at full size; blur previews on a smaller copy so it stays quick.
     */
    fun setPreview(layerId: Int, adj: Adjustment?) {
        previewBitmap?.recycle()
        previewBitmap = null
        previewFilter = null
        previewMatrix = null
        previewLayerId = -1
        if (adj == null) return
        val bmp = layerPixels[layerId]?.bitmap ?: return
        previewLayerId = layerId
        if (adj.type == Adjustment.TRANSFORM) {
            previewMatrix = pixelMatrix(adj)
        } else if (adj.type == Adjustment.BLUR) {
            val k = maxOf(1f, maxOf(width, height) / 900f)
            val small = Bitmap.createScaledBitmap(bmp, maxOf(1, (width / k).toInt()), maxOf(1, (height / k).toInt()), true)
            blurBitmap(small, (adj.a * scale / k).roundToInt())
            previewBitmap = small
        } else {
            previewFilter = adj.colorFilter()
        }
    }

    private val sample = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val sampleCanvas = Canvas(sample)

    /** The colour you see at a canvas position (all visible layers on white), or null outside the canvas. */
    fun colorAt(x: Float, y: Float, layers: List<Layer>): Int? {
        val px = x * scale
        val py = y * scale
        if (px < 0f || py < 0f || px >= width || py >= height) return null
        sampleCanvas.save()
        sampleCanvas.translate(-px.toInt().toFloat(), -py.toInt().toFloat())
        render(sampleCanvas, layers)
        sampleCanvas.restore()
        return sample.getPixel(0, 0) or 0xFF000000.toInt()
    }

    /**
     * A copy of the part of a layer inside [path] (canvas coordinates), cropped to
     * [bounds]. Only for the live canvas, where one canvas pixel is one bitmap pixel.
     */
    fun copyRegion(layerId: Int, path: android.graphics.Path, bounds: android.graphics.RectF): Bitmap? {
        val src = layerPixels[layerId]?.bitmap ?: return null
        val w = kotlin.math.ceil(bounds.width()).toInt()
        val h = kotlin.math.ceil(bounds.height()).toInt()
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-bounds.left, -bounds.top)
        c.clipPath(path)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    /** The area of a layer that has paint on it, in canvas coordinates, or null if it is empty. */
    fun contentBounds(layerId: Int): android.graphics.RectF? {
        val bmp = layerPixels[layerId]?.bitmap ?: return null
        val w = bmp.width
        val row = IntArray(w)
        var minX = w
        var minY = -1
        var maxX = -1
        var maxY = -1
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var first = -1
            var last = -1
            for (x in 0 until w) {
                if (row[x] ushr 24 != 0) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0) {
                if (minY < 0) minY = y
                maxY = y
                minX = minOf(minX, first)
                maxX = maxOf(maxX, last)
            }
        }
        if (minY < 0) return null
        return android.graphics.RectF(minX / scale, minY / scale, (maxX + 1) / scale, (maxY + 1) / scale)
    }

    fun clearAll() {
        hotMirror?.markAll()
        for (p in layerPixels.values) p.bitmap.eraseColor(Color.TRANSPARENT)
        dropWet()
    }

    fun rebuild(artwork: Artwork) {
        clearAll()
        for (s in artwork.history) drawStroke(s)
    }

    private fun configureBlend(stroke: Stroke) {
        blend.alpha = (stroke.opacity * 255).toInt().coerceIn(0, 255)
        blend.xfermode = if (stroke.isEraser) dstOut else null
    }

    /** Paper + visible layers (bottom to top) + the wet stroke, flattened onto [canvas]. */
    fun render(canvas: Canvas, layers: List<Layer>) {
        canvas.drawColor(Color.WHITE)
        for (layer in layers) if (layer.visible) drawLayer(canvas, layer)
    }

    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previousTint = PorterDuffColorFilter(0xFFE53935.toInt(), PorterDuff.Mode.SRC_IN)
    private val nextTint = PorterDuffColorFilter(0xFF43A047.toInt(), PorterDuff.Mode.SRC_IN)

    /**
     * Animation: frame [index] (one layer) on paper, with up to [onion] frames either
     * side shown faintly - earlier ones red, later ones green, nearer ones stronger.
     * With [background], layer 0 sits under every frame and is never a ghost.
     */
    fun renderFrame(canvas: Canvas, layers: List<Layer>, index: Int, onion: Int, onionOpacity: Float, background: Boolean = false) {
        canvas.drawColor(Color.WHITE)
        val first = if (background) 1 else 0
        if (background) layers.getOrNull(0)?.let { drawLayer(canvas, it) }
        if (index < first) return
        for (k in onion downTo 1) {
            val alpha = (onionOpacity * (onion - k + 1) / onion * 255).toInt().coerceIn(0, 255)
            if (index - k >= first) drawGhost(canvas, layers.getOrNull(index - k), previousTint, alpha)
            drawGhost(canvas, layers.getOrNull(index + k), nextTint, alpha)
        }
        layers.getOrNull(index)?.let { drawLayer(canvas, it) }
    }

    private fun drawGhost(canvas: Canvas, layer: Layer?, tint: PorterDuffColorFilter, alpha: Int) {
        val bmp = layer?.let { layerPixels[it.id]?.bitmap } ?: return
        ghostPaint.colorFilter = tint
        ghostPaint.alpha = alpha
        canvas.drawBitmap(bmp, 0f, 0f, ghostPaint)
    }

    private fun drawLayerBitmap(canvas: Canvas, layerId: Int, bmp: Bitmap, paint: Paint?) {
        val m = if (layerId == hotLayerId) hotMirror else null
        if (m != null) m.draw(canvas, paint) else canvas.drawBitmap(bmp, 0f, 0f, paint)
    }

    private fun drawLayer(canvas: Canvas, layer: Layer) {
        val bmp = layerPixels[layer.id]?.bitmap
        val wetHere = wetStroke?.takeIf { it.layerId == layer.id }
        if (layer.id == previewLayerId && bmp != null) {
            val alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            previewPaint.alpha = alpha
            val small = previewBitmap
            val m = previewMatrix
            if (m != null) {
                canvas.save()
                canvas.concat(m)
                canvas.drawBitmap(bmp, 0f, 0f, previewPaint)
                canvas.restore()
            } else if (small != null) {
                canvas.drawBitmap(small, null, android.graphics.Rect(0, 0, width, height), previewPaint)
            } else {
                previewPaint.colorFilter = previewFilter
                canvas.drawBitmap(bmp, 0f, 0f, previewPaint)
                previewPaint.colorFilter = null
            }
            return
        }
        if (wetHere == null && layer.opacity >= 1f) {
            bmp?.let { drawLayerBitmap(canvas, layer.id, it, null) }
            return
        }
        if (bmp == null && wetHere == null) return
        // Isolate the layer so its opacity applies once, and a wet eraser cuts only this layer.
        val alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        bmp?.let { drawLayerBitmap(canvas, layer.id, it, null) }
        wetHere?.let {
            configureBlend(it)
            val wm = wetMirror
            if (wm != null) wm.draw(canvas, blend) else canvas.drawBitmap(wet, 0f, 0f, blend)
        }
        canvas.restoreToCount(save)
    }
}

/**
 * Keeps a tiled copy of a bitmap that is being painted on, for drawing to the screen.
 * The GPU re-uploads a whole bitmap whenever it changes; a full-screen layer is ~25 MB,
 * which made drawing lag. Here only the tiles a dab touched are re-copied and re-uploaded.
 */
class TileMirror(private val source: Bitmap, private val tile: Int = 256) {
    private val cols = (source.width + tile - 1) / tile
    private val rows = (source.height + tile - 1) / tile
    private val tiles = arrayOfNulls<Bitmap>(cols * rows)
    private val dirty = BooleanArray(cols * rows) { true }
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val src = android.graphics.Rect()
    private val dst = android.graphics.Rect()

    fun markAll() = dirty.fill(true)

    /** Marks the tiles under a rectangle in the source's pixels. */
    fun mark(left: Float, top: Float, right: Float, bottom: Float) {
        val c0 = (left.toInt() / tile).coerceIn(0, cols - 1)
        val c1 = (right.toInt() / tile).coerceIn(0, cols - 1)
        val r0 = (top.toInt() / tile).coerceIn(0, rows - 1)
        val r1 = (bottom.toInt() / tile).coerceIn(0, rows - 1)
        for (r in r0..r1) for (c in c0..c1) dirty[r * cols + c] = true
    }

    fun draw(canvas: Canvas, paint: Paint?) {
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = r * cols + c
            val x = c * tile
            val y = r * tile
            val w = minOf(tile, source.width - x)
            val h = minOf(tile, source.height - y)
            var t = tiles[i]
            if (t == null) {
                t = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                tiles[i] = t
                dirty[i] = true
            }
            if (dirty[i]) {
                src.set(x, y, x + w, y + h)
                dst.set(0, 0, w, h)
                Canvas(t).drawBitmap(source, src, dst, copyPaint)
                dirty[i] = false
            }
            canvas.drawBitmap(t, x.toFloat(), y.toFloat(), paint)
        }
    }
}
