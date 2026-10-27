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

    // Walking state for the stroke being painted.
    private var active: Stroke? = null
    private var toNextDab = 0f
    private var random = Random(0)

    private fun configure(stroke: Stroke, clearPixels: Boolean) {
        val color = if (stroke.isEraser) Color.BLACK else stroke.color or 0xFF000000.toInt()
        if (filter == null || color != filterColor) {
            filterColor = color
            filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        paint.colorFilter = filter
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
        }
        val c = pts[index]
        if (index == 0) {
            dab(canvas, stroke, c.x, c.y, c.pressure, c.tilt)
            toNextDab = spacing(stroke, c.pressure)
            return
        }
        val b = pts[index - 1]
        val mx = (b.x + c.x) / 2f
        val my = (b.y + c.y) / 2f
        val mp = (b.pressure + c.pressure) / 2f
        val mt = (b.tilt + c.tilt) / 2f
        if (index == 1) {
            walk(canvas, stroke, b.x, b.y, b.pressure, b.tilt, mx, my, mp, mt)
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
            walk(canvas, stroke, px, py, pp, pt, qx, qy, qp, qt)
            px = qx; py = qy; pp = qp; pt = qt
        }
    }

    fun finish(canvas: Canvas, stroke: Stroke, clearPixels: Boolean) {
        val pts = stroke.points
        if (pts.size < 2 || stroke.image != null) return
        configure(stroke, clearPixels)
        val b = pts[pts.size - 2]
        val c = pts.last()
        walk(canvas, stroke, (b.x + c.x) / 2f, (b.y + c.y) / 2f, (b.pressure + c.pressure) / 2f, (b.tilt + c.tilt) / 2f, c.x, c.y, c.pressure, c.tilt)
    }

    /** Stamps dabs every "spacing" pixels along a straight piece, carrying the remainder over. */
    private fun walk(
        canvas: Canvas, stroke: Stroke,
        x0: Float, y0: Float, p0: Float, t0: Float,
        x1: Float, y1: Float, p1: Float, t1: Float,
    ) {
        val len = hypot(x1 - x0, y1 - y0)
        if (len <= 0f) return
        var d = toNextDab
        while (d <= len) {
            val f = d / len
            val p = p0 + (p1 - p0) * f
            dab(canvas, stroke, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, p, t0 + (t1 - t0) * f)
            d += spacing(stroke, p)
        }
        toNextDab = d - len
    }

    private fun diameter(stroke: Stroke, pressure: Float, tilt: Float): Float {
        val b = stroke.brush
        val byPressure = 1f + (max(0.06f, pressure.pow(0.7f)) - 1f) * b.pressureSize
        return stroke.size * byPressure * (1f + b.tiltSize * tilt * 1.5f)
    }

    private fun spacing(stroke: Stroke, pressure: Float) =
        max(0.5f, diameter(stroke, pressure, 0f) * stroke.brush.spacing)

    private fun dab(canvas: Canvas, stroke: Stroke, x: Float, y: Float, pressure: Float, tilt: Float) {
        val b = stroke.brush
        // Always draw the same four random numbers so replays stay in step.
        val jitter = random.nextFloat()
        val ox = random.nextFloat() * 2f - 1f
        val oy = random.nextFloat() * 2f - 1f
        val rotation = random.nextFloat() * 360f

        val dia = diameter(stroke, pressure, tilt) * (1f - b.sizeJitter * jitter * 0.8f)
        if (dia < 0.5f) return
        val alpha = b.flow * (1f + (pressure - 1f) * b.pressureOpacity) * (1f - b.tiltOpacity * tilt * 0.8f)
        val a = (alpha * 255).toInt()
        if (a <= 0) return
        paint.alpha = a.coerceAtMost(255)

        val mask = maskFor(b, dia)
        val half = mask.width / 2f
        matrix.reset()
        matrix.postTranslate(-half, -half)
        matrix.postRotate(rotation)
        matrix.postScale(dia / mask.width, dia / mask.width)
        matrix.postTranslate(x + ox * b.scatter * dia, y + oy * b.scatter * dia)
        canvas.drawBitmap(mask, matrix, paint)
    }

    /** Dab images at a few resolutions, so small dabs stay crisp instead of shimmering. */
    private fun maskFor(brush: Brush, diameter: Float): Bitmap {
        var size = 8
        while (size < diameter && size < 256) size *= 2
        val key = "${brush.softness}_${brush.grain}_$size"
        return masks.getOrPut(key) { makeMask(size, brush.softness, brush.grain) }
    }

    private fun makeMask(size: Int, softness: Float, grain: Float): Bitmap {
        val px = IntArray(size * size)
        val r = size / 2f
        val edge = max(1.5f / r, softness)
        val noise = Random(size * 31 + 7)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val d = hypot(x + 0.5f - r, y + 0.5f - r) / r
                var a = ((1f - d) / edge).coerceIn(0f, 1f)
                a = a * a * (3f - 2f * a)
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
class PaintSurface(val width: Int, val height: Int, private val scale: Float = 1f) {
    private class LayerPixels(val bitmap: Bitmap, val canvas: Canvas, val pixels: Canvas)

    private val layerPixels = HashMap<Int, LayerPixels>()
    private val wet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val wetCanvas = Canvas(wet).apply { scale(scale, scale) }
    private val painter = StrokePainter()
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
        } else {
            painter.drawSegment(pixelsFor(stroke.layerId).canvas, stroke, index, clearPixels = stroke.isEraser)
        }
    }

    /** Moves an in-progress stroke to the wet bitmap and redraws it from scratch (used by QuickShape). */
    fun redrawWet(stroke: Stroke) {
        startWet(stroke)
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
            dropWet()
        } else {
            painter.finish(pixelsFor(stroke.layerId).canvas, stroke, clearPixels = stroke.isEraser)
        }
    }

    fun drawStroke(stroke: Stroke) {
        for (i in stroke.points.indices) drawPoint(stroke, i)
        endStroke(stroke)
    }

    fun dropWet() {
        wetStroke = null
        wet.eraseColor(Color.TRANSPARENT)
    }

    fun clearLayer(layerId: Int) {
        layerPixels[layerId]?.bitmap?.eraseColor(Color.TRANSPARENT)
    }

    fun removeLayer(layerId: Int) {
        layerPixels.remove(layerId)?.bitmap?.recycle()
    }

    /** Redraws one layer from the artwork's history. */
    fun rebuildLayer(artwork: Artwork, layerId: Int) {
        clearLayer(layerId)
        for (s in artwork.history) if (s.layerId == layerId) drawStroke(s)
    }

    /** A transform's matrix in this surface's pixels (the surface may be scaled down). */
    private fun pixelMatrix(adj: Adjustment) = Matrix().apply {
        postScale(1f / scale, 1f / scale)
        postConcat(adj.matrix())
        postScale(scale, scale)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun applyAdjustment(layerId: Int, adj: Adjustment) {
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
            bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            return
        }
        if (bmp == null && wetHere == null) return
        // Isolate the layer so its opacity applies once, and a wet eraser cuts only this layer.
        val alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        wetHere?.let {
            configureBlend(it)
            canvas.drawBitmap(wet, 0f, 0f, blend)
        }
        canvas.restoreToCount(save)
    }
}
