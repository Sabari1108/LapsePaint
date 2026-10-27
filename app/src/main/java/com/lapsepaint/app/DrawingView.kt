package com.lapsepaint.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Collections
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The canvas: pen/finger input, gestures, undo history and layer edits.
 * Pixels live in a [PaintSurface]; strokes and layers live in an [Artwork].
 *
 * Gestures, Procreate-style:
 * - two-finger tap = undo, three-finger tap = redo
 * - two-finger pinch / drag / twist = zoom, pan and rotate the canvas;
 *   a quick pinch inwards fits it back to the screen
 * - draw and hold still = QuickShape (snaps to a straight line or an ellipse;
 *   keep holding and move to adjust the line's end). Curves snap to a clean arc,
 *   or stay as drawn if they are not close to one.
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var brushColor = Color.BLACK
    var brushSizeDp = 8f
    var brushOpacity = 1f
    var brush = BrushLibrary.classic
    var eraser = false
    var smudge = false

    /** Fill tool: a tap fills the enclosed area with [brushColor]. */
    var fill = false

    /** When true, fingers never draw: only the pen does. */
    var penOnly = false

    var onHistoryChanged: (() -> Unit)? = null
    var onPenDetected: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var onColorUsed: ((Int) -> Unit)? = null

    var artwork = Artwork("unsaved")
        private set
    private val redoStack = ArrayList<Stroke>()

    private var surface: PaintSurface? = null
    private var current: Stroke? = null
    private var currentIsPen = false
    private var penSeen = false

    private var gestureStart = 0L
    private var maxPointers = 0
    private var gestureMoved = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private enum class Shape { NONE, LINE, ARC, ELLIPSE }

    private var shape = Shape.NONE
    private var lineStart: StrokePoint? = null
    private var holdX = 0f
    private var holdY = 0f
    private val holdCheck = Runnable { snapToShape() }

    val canvasWidth get() = artwork.width
    val canvasHeight get() = artwork.height
    val canUndo get() = artwork.history.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun setArtwork(art: Artwork) {
        artwork = art
        redoStack.clear()
        surface = null
        if (width > 0 && height > 0) createSurface(width, height)
        onHistoryChanged?.invoke()
    }

    private fun createSurface(w: Int, h: Int) {
        // A blank artwork takes the screen's size; a saved one keeps its own.
        if (artwork.width == 0 || artwork.history.isEmpty()) {
            artwork.width = w
            artwork.height = h
        }
        surface = PaintSurface(artwork.width, artwork.height, tiled = true).also { it.rebuild(artwork) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val s = surface
        if (s == null || (artwork.history.isEmpty() && (s.width != w || s.height != h))) createSurface(w, h)
    }

    // ---- Zoom, pan and rotate ----

    /** Canvas -> screen. Identity means the canvas fills the view, as it was created. */
    private val viewMatrix = Matrix()
    private val screenToCanvas = Matrix()
    private val navStart = Matrix()
    private var navigating = false
    private var navDist = 1f
    private var navAngle = 0f
    private var navMidX = 0f
    private var navMidY = 0f
    private var navStartTime = 0L
    private var navRatio = 1f
    private val matrixValues = FloatArray(9)

    /** How many screen pixels one canvas pixel covers. */
    private val zoom get() = viewMatrix.mapRadius(1f)

    private fun pinchDistance(e: MotionEvent) = max(1f, hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)))
    private fun pinchAngle(e: MotionEvent) =
        Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun beginNavigate(e: MotionEvent) {
        navStart.set(viewMatrix)
        navDist = pinchDistance(e)
        navAngle = pinchAngle(e)
        navMidX = (e.getX(0) + e.getX(1)) / 2f
        navMidY = (e.getY(0) + e.getY(1)) / 2f
        navStartTime = e.eventTime
        navRatio = 1f
        navigating = false
    }

    private fun navigate(e: MotionEvent) {
        val d = pinchDistance(e)
        var turn = pinchAngle(e) - navAngle
        while (turn > 180f) turn -= 360f
        while (turn < -180f) turn += 360f
        val mx = (e.getX(0) + e.getX(1)) / 2f
        val my = (e.getY(0) + e.getY(1)) / 2f
        if (!navigating) {
            // Small wobbles stay a tap (undo/redo); past this it is a zoom/pan/rotate.
            val still = kotlin.math.abs(d - navDist) < touchSlop && hypot(mx - navMidX, my - navMidY) < touchSlop && kotlin.math.abs(turn) < 4f
            if (still) return
            navigating = true
            gestureMoved = true
        }
        navRatio = d / navDist
        val startZoom = navStart.mapRadius(1f)
        val ratio = navRatio.coerceIn(MIN_ZOOM / startZoom, MAX_ZOOM / startZoom)
        viewMatrix.set(navStart)
        viewMatrix.postTranslate(-navMidX, -navMidY)
        viewMatrix.postScale(ratio, ratio)
        viewMatrix.postRotate(turn)
        viewMatrix.postTranslate(mx, my)
        viewMatrix.invert(screenToCanvas)
        invalidate()
    }

    private fun endNavigate(e: MotionEvent) {
        if (!navigating) return
        navigating = false
        if (e.eventTime - navStartTime < 350 && navRatio < 0.75f) {
            fitToScreen()
            return
        }
        // Snap to straight when the canvas is within a few degrees of upright or sideways.
        viewMatrix.getValues(matrixValues)
        val angle = Math.toDegrees(kotlin.math.atan2(matrixValues[Matrix.MSKEW_Y].toDouble(), matrixValues[Matrix.MSCALE_X].toDouble())).toFloat()
        val nearest = kotlin.math.round(angle / 90f) * 90f
        if (kotlin.math.abs(angle - nearest) < 5f) viewMatrix.postRotate(nearest - angle, width / 2f, height / 2f)
        viewMatrix.invert(screenToCanvas)
        invalidate()
        onMessage?.invoke("${kotlin.math.round(zoom * 100).toInt()}%")
    }

    fun fitToScreen() {
        viewMatrix.reset()
        screenToCanvas.reset()
        invalidate()
        onMessage?.invoke("Fit to screen")
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF2B2B2B.toInt())
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.clipRect(0f, 0f, artwork.width.toFloat(), artwork.height.toFloat())
        val anim = artwork.animation
        if (anim.enabled) {
            val index = if (isPlaying) playIndex else artwork.layers.indexOfFirst { it.id == artwork.activeLayerId }
            surface?.renderFrame(canvas, artwork.layers, index, if (isPlaying) 0 else anim.onionFrames, anim.onionOpacity, anim.background)
        } else {
            surface?.render(canvas, artwork.layers)
        }
        canvas.restore()
        transform?.let {
            canvas.save()
            canvas.concat(viewMatrix)
            drawTransformOverlay(canvas, it)
            canvas.restore()
        }
        if (selecting && lasso.size >= 4) {
            canvas.save()
            canvas.concat(viewMatrix)
            drawSelection(canvas)
            canvas.restore()
            // Keep the dashed outline moving.
            if (lassoClosed) postInvalidateDelayed(60)
        }
        if (picking) drawLoupe(canvas)
    }

    // ---- Selection ----

    var selecting = false
        private set
    private val lasso = ArrayList<Float>() // x, y pairs in canvas coordinates
    private var lassoClosed = false
    private var selectionInverted = false
    private val selectionPath = Path()
    private val dimPaint = Paint().apply { color = 0x40000000 }
    private val antsWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val antsBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }

    val hasSelection get() = lassoClosed && lasso.size >= 6

    fun startSelecting() {
        selecting = true
        clearSelection()
    }

    fun stopSelecting() {
        selecting = false
        clearSelection()
    }

    fun clearSelection() {
        lasso.clear()
        lassoClosed = false
        selectionInverted = false
        rebuildSelectionPath()
        invalidate()
    }

    fun invertSelection(): Boolean {
        if (!hasSelection) return false
        selectionInverted = !selectionInverted
        rebuildSelectionPath()
        invalidate()
        return true
    }

    private fun rebuildSelectionPath() {
        selectionPath.reset()
        if (lasso.size >= 4) {
            selectionPath.moveTo(lasso[0], lasso[1])
            var i = 2
            while (i + 1 < lasso.size) {
                selectionPath.lineTo(lasso[i], lasso[i + 1])
                i += 2
            }
            if (lassoClosed) selectionPath.close()
        }
        // Winding, so a lasso that crosses itself still selects everything inside it.
        selectionPath.fillType = if (selectionInverted) Path.FillType.INVERSE_WINDING else Path.FillType.WINDING
    }

    private fun selectionTouch(e: MotionEvent, ce: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureMoved = false
                lasso.clear()
                lassoClosed = false
                selectionInverted = false
                lasso.add(ce.x)
                lasso.add(ce.y)
                rebuildSelectionPath()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                // Two fingers zoom and pan instead; drop the lasso just started.
                if (!lassoClosed) lasso.clear()
                rebuildSelectionPath()
                beginNavigate(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    navigate(e)
                } else if (!lassoClosed && lasso.size >= 2) {
                    val lx = lasso[lasso.size - 2]
                    val ly = lasso[lasso.size - 1]
                    if (hypot(ce.x - lx, ce.y - ly) > 2f / zoom) {
                        lasso.add(ce.x)
                        lasso.add(ce.y)
                        rebuildSelectionPath()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> endNavigate(e)
            MotionEvent.ACTION_UP -> {
                if (navigating) {
                    endNavigate(e)
                } else if (!lassoClosed) {
                    // A tap or a scribble too small to be an area clears the selection.
                    var length = 0f
                    var i = 2
                    while (i + 1 < lasso.size) {
                        length += hypot(lasso[i] - lasso[i - 2], lasso[i + 1] - lasso[i - 1])
                        i += 2
                    }
                    if (lasso.size >= 6 && length * zoom > 30 * density) lassoClosed = true else lasso.clear()
                    rebuildSelectionPath()
                }
            }
        }
        invalidate()
    }

    /** Dims what is not selected and draws a moving black/white dashed outline. */
    private fun drawSelection(canvas: Canvas) {
        if (lassoClosed) {
            canvas.save()
            canvas.clipRect(0f, 0f, artwork.width.toFloat(), artwork.height.toFloat())
            val outside = Path(selectionPath).apply { toggleInverseFillType() }
            canvas.drawPath(outside, dimPaint)
            canvas.restore()
        }
        val outline = Path(selectionPath).apply { fillType = Path.FillType.WINDING }
        val w = 1.5f * density / zoom
        val dash = 6 * density / zoom
        antsWhite.strokeWidth = w
        antsBlack.strokeWidth = w
        antsBlack.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(dash, dash), (android.os.SystemClock.uptimeMillis() / 40 % 100) * dash / 50f,
        )
        canvas.drawPath(outline, antsWhite)
        canvas.drawPath(outline, antsBlack)
    }

    /** Erases the selected area on the current layer. Returns false with no selection. */
    fun clearSelectedArea(): Boolean {
        if (!hasSelection) return false
        applyAdjustment(Adjustment.clear(selectionInverted, lasso.toFloatArray()))
        return true
    }

    fun fillSelectedArea(color: Int): Boolean {
        if (!hasSelection) return false
        applyAdjustment(Adjustment.fill(selectionInverted, color, lasso.toFloatArray()))
        return true
    }

    /**
     * Copies (or cuts) the selected area of the current layer onto a new layer above it,
     * in the same place, and selects that layer. Returns a message if it couldn't.
     */
    fun pasteSelection(cut: Boolean): String? {
        if (!hasSelection) return "Draw around an area first"
        if (artwork.layers.size >= maxLayers) return "Layer limit reached ($maxLayers)"
        val s = surface ?: return "Canvas not ready"
        val source = artwork.activeLayer
        val bounds = RectF()
        if (selectionInverted) {
            bounds.set(0f, 0f, artwork.width.toFloat(), artwork.height.toFloat())
        } else {
            selectionPath.computeBounds(bounds, true)
            if (!bounds.intersect(0f, 0f, artwork.width.toFloat(), artwork.height.toFloat())) return "The selection is off the canvas"
        }
        bounds.set(kotlin.math.floor(bounds.left), kotlin.math.floor(bounds.top), kotlin.math.ceil(bounds.right), kotlin.math.ceil(bounds.bottom))
        val piece = s.copyRegion(source.id, selectionPath, bounds) ?: return "Nothing on ${source.name} to copy"
        val file = ArtworkStore.saveImage(context, artwork.id, piece)
        if (!insertImage(file, piece, RectF(bounds), if (cut) "Cut selection" else "Pasted selection")) return "Layer limit reached ($maxLayers)"
        if (cut) applyAdjustment(Adjustment.clear(selectionInverted, lasso.toFloatArray()), source.id, linked = true)
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Strokes and Transform work in canvas coordinates; zoom/pan works on the screen.
        val ce = MotionEvent.obtain(e)
        ce.transform(screenToCanvas)
        try {
            handleTouch(e, ce)
        } finally {
            ce.recycle()
        }
        return true
    }

    // ---- Animation playback ----

    var isPlaying = false
        private set
    var onPlayingChanged: ((Boolean) -> Unit)? = null
    private var playIndex = 0
    private var playPos = 0

    private val playTick = object : Runnable {
        override fun run() {
            // Rebuilt each tick so changes to holds or settings apply straight away.
            val order = artwork.animation.playOrder(artwork.layers)
            if (!isPlaying || order.isEmpty()) return
            playPos = (playPos + 1) % order.size
            playIndex = order[playPos]
            invalidate()
            postDelayed(this, 1000L / max(1, artwork.animation.fps))
        }
    }

    fun setPlaying(on: Boolean) {
        if (on == isPlaying) return
        isPlaying = on
        removeCallbacks(playTick)
        if (on) {
            // Start from the current frame (or the first one, if the background is selected).
            val order = artwork.animation.playOrder(artwork.layers)
            val active = artwork.layers.indexOfFirst { it.id == artwork.activeLayerId }
            playPos = max(0, order.indexOf(active))
            playIndex = order.getOrElse(playPos) { 0 }
            postDelayed(playTick, 1000L / max(1, artwork.animation.fps))
        }
        invalidate()
        onPlayingChanged?.invoke(on)
    }

    fun selectFrame(index: Int) {
        artwork.layers.getOrNull(index)?.let { artwork.activeLayerId = it.id }
        invalidate()
    }

    /** A copy of the current frame, placed right after it. */
    fun duplicateFrame(): String? {
        if (artwork.layers.size >= maxLayers) return "Frame limit reached ($maxLayers)"
        val source = surface?.layerBitmap(artwork.activeLayerId)
        if (source == null) {
            addLayer()
            return null
        }
        val copy = source.copy(Bitmap.Config.ARGB_8888, false)
        val file = ArtworkStore.saveImage(context, artwork.id, copy)
        insertImage(file, copy, RectF(0f, 0f, artwork.width.toFloat(), artwork.height.toFloat()), "Frame copy")
        return null
    }

    private fun handleTouch(e: MotionEvent, ce: MotionEvent) {
        if (isPlaying && e.actionMasked == MotionEvent.ACTION_DOWN) setPlaying(false)
        if (transform != null) {
            transformTouch(ce)
            return
        }
        if (selecting) {
            selectionTouch(e, ce)
            return
        }
        if (fill) {
            fillTouch(e, ce)
            return
        }
        if (picking || (eyedropperArmed && e.actionMasked == MotionEvent.ACTION_DOWN)) {
            pickTouch(e, ce)
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStart = e.eventTime
                maxPointers = 1
                gestureMoved = false
                downX = e.x
                downY = e.y
                val tool = e.getToolType(0)
                val isPen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                if (isPen && !penSeen) {
                    penSeen = true
                    onPenDetected?.invoke()
                }
                if (isPen || !penOnly) {
                    startStroke(ce, isPen, tool == MotionEvent.TOOL_TYPE_ERASER)
                }
                // Press-and-hold eyedropper only with the brush: while erasing or smudging,
                // a pause must never turn the tool into a brush that paints the picked colour.
                if (!isPen && !eraser && !smudge) {
                    holdCanvasX = ce.x
                    holdCanvasY = ce.y
                    postDelayed(pickHold, 450)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(pickHold)
                maxPointers = max(maxPointers, e.pointerCount)
                // A second finger means a gesture, not a finger stroke.
                if (current != null && !currentIsPen) cancelStroke()
                if (current == null && e.pointerCount == 2) beginNavigate(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > touchSlop) {
                    gestureMoved = true
                    removeCallbacks(pickHold)
                }
                if (current != null && e.pointerCount == 1) moveStroke(ce)
                else if (current == null && e.pointerCount >= 2) navigate(e)
            }
            MotionEvent.ACTION_POINTER_UP -> endNavigate(e)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(pickHold)
                if (current != null) {
                    if (shape == Shape.NONE) appendPoint(ce.x, ce.y, pressureOf(ce.pressure), tiltOf(ce.getAxisValue(MotionEvent.AXIS_TILT)), exact = true)
                    commitStroke()
                } else if (navigating) {
                    endNavigate(e)
                } else if (!gestureMoved && e.eventTime - gestureStart < 350) {
                    when (maxPointers) {
                        2 -> undo()
                        3 -> redo()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pickHold)
                cancelStroke()
            }
        }
    }

    // ---- Fill ----

    private fun fillTouch(e: MotionEvent, ce: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStart = e.eventTime
                gestureMoved = false
                maxPointers = 1
                downX = e.x
                downY = e.y
                holdCanvasX = ce.x
                holdCanvasY = ce.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                if (e.pointerCount == 2) beginNavigate(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > touchSlop) gestureMoved = true
                if (e.pointerCount >= 2) navigate(e)
            }
            MotionEvent.ACTION_POINTER_UP -> endNavigate(e)
            MotionEvent.ACTION_UP -> {
                if (navigating) {
                    endNavigate(e)
                } else if (!gestureMoved && maxPointers == 1) {
                    fillAt(holdCanvasX, holdCanvasY)?.let { onMessage?.invoke(it) }
                } else if (!gestureMoved && e.eventTime - gestureStart < 350) {
                    when (maxPointers) {
                        2 -> undo()
                        3 -> redo()
                    }
                }
            }
        }
    }

    /**
     * Fills the area around a canvas point with [brushColor], on the selected layer.
     * The edges come from everything visible (lines on any layer stop the fill). The
     * result is kept as an image placed on the layer, so undo, saving and the
     * time-lapse replay it exactly. Returns a message to show, or null.
     */
    fun fillAt(x: Float, y: Float): String? {
        val layer = artwork.activeLayer
        if (!layer.visible) return "${layer.name} is hidden"
        val w = artwork.width
        val h = artwork.height
        val ix = x.toInt()
        val iy = y.toInt()
        if (ix !in 0 until w || iy !in 0 until h) return null
        val seen = snapshot() ?: return null
        val px = IntArray(w * h)
        seen.getPixels(px, 0, w, 0, 0, w, h)
        seen.recycle()

        // Pixels close to the tapped colour belong to the area (tolerance per channel).
        val seed = px[iy * w + ix]
        val sr = (seed shr 16) and 0xFF
        val sg = (seed shr 8) and 0xFF
        val sb = seed and 0xFF
        val tol = 48
        fun matches(i: Int): Boolean {
            val c = px[i]
            return kotlin.math.abs(((c shr 16) and 0xFF) - sr) <= tol &&
                kotlin.math.abs(((c shr 8) and 0xFF) - sg) <= tol &&
                kotlin.math.abs((c and 0xFF) - sb) <= tol
        }

        // Scanline flood fill.
        val inside = BooleanArray(w * h)
        var stack = IntArray(1024)
        var top = 0
        fun push(i: Int) {
            if (top == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[top++] = i
        }
        push(iy * w + ix)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        while (top > 0) {
            val i = stack[--top]
            if (inside[i] || !matches(i)) continue
            val row = i / w
            var l = i % w
            var r = l
            while (l > 0 && !inside[row * w + l - 1] && matches(row * w + l - 1)) l--
            while (r < w - 1 && !inside[row * w + r + 1] && matches(row * w + r + 1)) r++
            for (cx in l..r) inside[row * w + cx] = true
            if (l < minX) minX = l
            if (r > maxX) maxX = r
            if (row < minY) minY = row
            if (row > maxY) maxY = row
            for (ny in intArrayOf(row - 1, row + 1)) {
                if (ny !in 0 until h) continue
                var cx = l
                while (cx <= r) {
                    val j = ny * w + cx
                    if (!inside[j] && matches(j)) {
                        push(j)
                        // Skip the rest of this run; the pushed seed will cover it.
                        while (cx <= r && !inside[ny * w + cx] && matches(ny * w + cx)) cx++
                    } else {
                        cx++
                    }
                }
            }
        }
        if (maxX < 0) return null

        // Grow the area by a pixel so it tucks under soft line edges (no white halo).
        val grow = 2
        minX = (minX - grow).coerceAtLeast(0); minY = (minY - grow).coerceAtLeast(0)
        maxX = (maxX + grow).coerceAtMost(w - 1); maxY = (maxY + grow).coerceAtMost(h - 1)
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val out = IntArray(bw * bh)
        val colour = brushColor or 0xFF000000.toInt()
        for (yy in 0 until bh) {
            for (xx in 0 until bw) {
                val gx = minX + xx
                val gy = minY + yy
                var hit = false
                loop@ for (dy in -grow..grow) for (dx in -grow..grow) {
                    val qx = gx + dx
                    val qy = gy + dy
                    if (qx in 0 until w && qy in 0 until h && inside[qy * w + qx]) { hit = true; break@loop }
                }
                if (hit) out[yy * bw + xx] = colour
            }
        }
        val bmp = Bitmap.createBitmap(out, bw, bh, Bitmap.Config.ARGB_8888)
        val file = ArtworkStore.saveImage(context, artwork.id, bmp)
        val stroke = Stroke(layer.id, colour, 1f, 1f, false, brush, 0)
        val rect = RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
        stroke.image = PlacedImage(file, bmp, rect)
        stroke.points.add(StrokePoint(rect.centerX(), rect.centerY(), 1f))
        surface?.drawStroke(stroke)
        artwork.history.add(stroke)
        redoStack.clear()
        invalidate()
        onHistoryChanged?.invoke()
        onColorUsed?.invoke(colour)
        return null
    }

    // ---- Eyedropper ----

    private var eyedropperArmed = false
    private var picking = false
    private var pickedColor: Int? = null
    private var loupeX = 0f
    private var loupeY = 0f
    private var holdCanvasX = 0f
    private var holdCanvasY = 0f
    private val loupeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val loupeOval = RectF()

    /** A finger held still on the canvas: switch to the eyedropper, like Procreate. */
    private val pickHold = Runnable {
        if (maxPointers != 1 || gestureMoved) return@Runnable
        if (current != null) cancelStroke()
        picking = true
        updatePick(downX, downY, holdCanvasX, holdCanvasY)
    }

    /** The next touch on the canvas picks a colour (the sidebar eyedropper button). */
    fun armEyedropper() {
        eyedropperArmed = true
    }

    private fun pickTouch(e: MotionEvent, ce: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                eyedropperArmed = false
                picking = true
                updatePick(e.x, e.y, ce.x, ce.y)
            }
            MotionEvent.ACTION_MOVE -> updatePick(e.x, e.y, ce.x, ce.y)
            MotionEvent.ACTION_UP -> finishPick(commit = true)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> finishPick(commit = false)
        }
    }

    private fun updatePick(screenX: Float, screenY: Float, canvasX: Float, canvasY: Float) {
        loupeX = screenX
        loupeY = screenY
        surface?.colorAt(canvasX, canvasY, artwork.layers)?.let { pickedColor = it }
        invalidate()
    }

    private fun finishPick(commit: Boolean) {
        picking = false
        val c = pickedColor
        pickedColor = null
        if (commit && c != null) onColorPicked?.invoke(c)
        invalidate()
    }

    /** Ring above the finger: new colour on top, current colour below. */
    private fun drawLoupe(canvas: Canvas) {
        val r = 46 * density
        val gap = 90 * density
        val cy = if (loupeY - gap - r > 0) loupeY - gap else loupeY + gap
        loupeOval.set(loupeX - r, cy - r, loupeX + r, cy + r)
        loupeFill.color = pickedColor ?: brushColor
        canvas.drawArc(loupeOval, 180f, 180f, true, loupeFill)
        loupeFill.color = brushColor
        canvas.drawArc(loupeOval, 0f, 180f, true, loupeFill)
        loupeRing.color = 0x66000000
        loupeRing.strokeWidth = 8 * density
        canvas.drawCircle(loupeX, cy, r + 4 * density, loupeRing)
        loupeRing.color = Color.WHITE
        loupeRing.strokeWidth = 4 * density
        canvas.drawCircle(loupeX, cy, r, loupeRing)
    }

    private fun pressureOf(raw: Float) = if (currentIsPen) raw.coerceIn(0.05f, 1f) else 1f

    /** AXIS_TILT is 0 when the pen is upright and pi/2 when it lies flat. Pens without tilt report 0. */
    private fun tiltOf(raw: Float) = if (currentIsPen) (raw / (Math.PI / 2).toFloat()).coerceIn(0f, 1f) else 0f

    private fun startStroke(e: MotionEvent, isPen: Boolean, penEraserEnd: Boolean) {
        val layer = artwork.activeLayer
        if (!layer.visible) {
            onMessage?.invoke("${layer.name} is hidden")
            return
        }
        currentIsPen = isPen
        shape = Shape.NONE
        val size = brushSizeDp * density
        val stroke = Stroke(layer.id, brushColor, size, brushOpacity, eraser || penEraserEnd, brush, kotlin.random.Random.nextInt())
        stroke.isSmudge = smudge && !penEraserEnd
        current = stroke
        stroke.points.add(StrokePoint(e.x, e.y, pressureOf(e.pressure), tiltOf(e.getAxisValue(MotionEvent.AXIS_TILT))))
        surface?.drawPoint(stroke, 0)
        holdX = e.x
        holdY = e.y
        scheduleHoldCheck()
        invalidate()
    }

    private fun moveStroke(e: MotionEvent) {
        val stroke = current ?: return
        when (shape) {
            Shape.LINE -> {
                // While still holding, the snapped line's end follows the pen.
                val start = lineStart ?: return
                setLine(stroke, start, StrokePoint(e.x, e.y, start.pressure))
                surface?.redrawWet(stroke)
                invalidate()
                return
            }
            Shape.ARC, Shape.ELLIPSE -> return
            Shape.NONE -> {}
        }
        // Historical samples arrive between frames; using them keeps fast lines smooth.
        for (h in 0 until e.historySize) {
            appendPoint(
                e.getHistoricalX(h), e.getHistoricalY(h),
                pressureOf(e.getHistoricalPressure(h)), tiltOf(e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h)),
            )
        }
        appendPoint(e.x, e.y, pressureOf(e.pressure), tiltOf(e.getAxisValue(MotionEvent.AXIS_TILT)))
        if (hypot(e.x - holdX, e.y - holdY) > 6 * density / zoom) {
            holdX = e.x
            holdY = e.y
            scheduleHoldCheck()
        }
        invalidate()
    }

    /**
     * Streamline: each new point moves only part of the way towards the pen,
     * which irons out hand jitter; the brush sets how much. [exact] places the
     * final point where the pen lifted.
     */
    private fun appendPoint(x: Float, y: Float, pressure: Float, tilt: Float, exact: Boolean = false) {
        val stroke = current ?: return
        val pts = stroke.points
        val last = pts.last()
        val p = last.pressure + (pressure - last.pressure) * 0.5f
        val t = last.tilt + (tilt - last.tilt) * 0.5f
        val follow = max(0.1f, 1f - stroke.brush.streamline * 0.85f)
        val pt = if (exact) StrokePoint(x, y, p, t) else StrokePoint(last.x + (x - last.x) * follow, last.y + (y - last.y) * follow, p, t)
        if (hypot(pt.x - last.x, pt.y - last.y) < if (exact) 0.5f else 1.5f) return
        pts.add(pt)
        surface?.drawPoint(stroke, pts.lastIndex)
    }

    private fun scheduleHoldCheck() {
        removeCallbacks(holdCheck)
        postDelayed(holdCheck, 550)
    }

    /** QuickShape: the pen was held still, so turn the stroke into a clean line or ellipse. */
    private fun snapToShape() {
        val stroke = current ?: return
        if (shape != Shape.NONE || stroke.points.size < 4 || stroke.isSmudge) return
        val pts = stroke.points
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var length = 0f
        for (i in pts.indices) {
            val p = pts[i]
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
            if (i > 0) length += hypot(p.x - pts[i - 1].x, p.y - pts[i - 1].y)
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 24 * density) return

        val first = pts.first()
        val last = pts.last()
        val pressure = pts.map { it.pressure }.average().toFloat()
        val chord = hypot(last.x - first.x, last.y - first.y)
        val closed = chord < diag * 0.3f && length > diag * 1.6f

        // How far the stroke bows away from the straight line between its ends.
        var bow = 0f
        if (!closed && chord > 0f) {
            for (p in pts) {
                val d = kotlin.math.abs((last.x - first.x) * (first.y - p.y) - (first.x - p.x) * (last.y - first.y)) / chord
                bow = max(bow, d)
            }
        }
        val straight = bow < max(chord * 0.08f, 10 * density)

        if (!closed && !straight) {
            // A curve: snap to a clean arc if it is close to one, otherwise leave the stroke as drawn.
            val arc = fitArc(pts, length, pressure) ?: return
            shape = Shape.ARC
            pts.clear()
            pts.addAll(arc)
            surface?.let {
                it.rebuildLayer(artwork, stroke.layerId)
                it.redrawWet(stroke)
            }
            onMessage?.invoke("Arc")
            invalidate()
            return
        }
        if (closed) {
            shape = Shape.ELLIPSE
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f
            val rx = (maxX - minX) / 2f
            val ry = (maxY - minY) / 2f
            pts.clear()
            for (k in 0..96) {
                val a = (2 * Math.PI * k / 96).toFloat()
                pts.add(StrokePoint(cx + rx * cos(a), cy + ry * sin(a), pressure))
            }
        } else {
            shape = Shape.LINE
            val start = StrokePoint(first.x, first.y, pressure)
            lineStart = start
            setLine(stroke, start, StrokePoint(last.x, last.y, pressure))
        }
        // Wipe the freehand version from the layer, then show the shape.
        surface?.let {
            it.rebuildLayer(artwork, stroke.layerId)
            it.redrawWet(stroke)
        }
        onMessage?.invoke(if (closed) "Ellipse" else "Line")
        invalidate()
    }

    /**
     * Fits a circle through the start, the halfway point (by length) and the end.
     * Returns the arc's points, or null when the stroke isn't close enough to an arc.
     */
    private fun fitArc(pts: List<StrokePoint>, length: Float, pressure: Float): List<StrokePoint>? {
        val a = pts.first()
        val c = pts.last()
        var walked = 0f
        var b = pts[pts.size / 2]
        for (i in 1 until pts.size) {
            walked += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            if (walked >= length / 2f) {
                b = pts[i]
                break
            }
        }

        val d = 2f * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (kotlin.math.abs(d) < 1e-3f) return null
        val a2 = a.x * a.x + a.y * a.y
        val b2 = b.x * b.x + b.y * b.y
        val c2 = c.x * c.x + c.y * c.y
        val cx = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
        val cy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
        val r = hypot(a.x - cx, a.y - cy)

        // Reject wobbly or S-shaped strokes: every point must sit near the circle.
        val error = pts.map { kotlin.math.abs(hypot(it.x - cx, it.y - cy) - r) }.average()
        if (error > r * 0.12f) return null

        val twoPi = (2 * Math.PI).toFloat()
        fun angle(p: StrokePoint) = kotlin.math.atan2(p.y - cy, p.x - cx)
        fun wrap(v: Float) = ((v % twoPi) + twoPi) % twoPi
        val start = angle(a)
        val toEnd = wrap(angle(c) - start)
        val toMid = wrap(angle(b) - start)
        // Go the way round that passes through the middle of the stroke.
        val sweep = if (toMid < toEnd) toEnd else toEnd - twoPi

        val steps = 64
        return List(steps + 1) { k ->
            val t = start + sweep * k / steps
            StrokePoint(cx + r * cos(t), cy + r * sin(t), pressure)
        }
    }

    private fun setLine(stroke: Stroke, a: StrokePoint, b: StrokePoint) {
        stroke.points.clear()
        for (k in 0..32) {
            val t = k / 32f
            stroke.points.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.pressure))
        }
    }

    private fun commitStroke() {
        removeCallbacks(holdCheck)
        current?.let {
            surface?.endStroke(it)
            artwork.history.add(it)
            redoStack.clear()
            if (!it.isEraser) onColorUsed?.invoke(it.color)
        }
        current = null
        shape = Shape.NONE
        invalidate()
        onHistoryChanged?.invoke()
    }

    private fun cancelStroke() {
        removeCallbacks(holdCheck)
        val stroke = current ?: return
        current = null
        shape = Shape.NONE
        surface?.let {
            it.dropWet()
            it.rebuildLayer(artwork, stroke.layerId)
        }
        invalidate()
    }

    fun undo() {
        if (artwork.history.isEmpty()) return
        val touched = HashSet<Int>()
        // Linked entries (e.g. Cut & Paste) come off together.
        do {
            val stroke = artwork.history.removeAt(artwork.history.lastIndex)
            redoStack.add(stroke)
            touched.add(stroke.layerId)
        } while (stroke.linkedToPrevious && artwork.history.isNotEmpty())
        for (id in touched) surface?.rebuildLayer(artwork, id)
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        do {
            val stroke = redoStack.removeAt(redoStack.lastIndex)
            if (artwork.layer(stroke.layerId) != null) {
                artwork.history.add(stroke)
                surface?.drawStroke(stroke)
            }
        } while (redoStack.isNotEmpty() && redoStack.last().linkedToPrevious)
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun clearAll() {
        artwork.history.clear()
        redoStack.clear()
        surface?.rebuild(artwork)
        invalidate()
        onHistoryChanged?.invoke()
    }

    // ---- Transform ----

    /** The selected layer's pending move/resize/rotate, shown live until Done or Cancel. */
    private class TransformState(val layerId: Int, val bounds: RectF) {
        var dx = 0f
        var dy = 0f
        var scale = 1f
        var rotation = 0f
        var flipX = false
        var flipY = false
    }

    private enum class Grab { NONE, MOVE, SCALE, ROTATE, PINCH }

    private var transform: TransformState? = null
    private var grab = Grab.NONE
    private var grabX = 0f
    private var grabY = 0f
    private var baseDx = 0f
    private var baseDy = 0f
    private var baseScale = 1f
    private var baseRotation = 0f
    private var pinchDist = 1f
    private var pinchAngle = 0f
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
        color = 0xFF4FA3F7.toInt()
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boxPath = Path()

    val isTransforming get() = transform != null

    /** Starts transforming the selected layer. Returns false if it has nothing on it. */
    fun startTransform(): Boolean {
        val s = surface ?: return false
        val layerId = artwork.activeLayerId
        val bounds = s.contentBounds(layerId) ?: return false
        transform = TransformState(layerId, bounds)
        updateTransformPreview()
        return true
    }

    private fun transformAdjustment(t: TransformState) = Adjustment(
        Adjustment.TRANSFORM,
        floatArrayOf(
            t.bounds.centerX(), t.bounds.centerY(), t.dx, t.dy,
            if (t.flipX) -t.scale else t.scale, if (t.flipY) -t.scale else t.scale, t.rotation,
        ),
    )

    private fun updateTransformPreview() {
        val t = transform ?: return
        surface?.setPreview(t.layerId, transformAdjustment(t))
        invalidate()
    }

    fun commitTransform() {
        val t = transform ?: return
        transform = null
        val untouched = t.dx == 0f && t.dy == 0f && t.scale == 1f && t.rotation == 0f && !t.flipX && !t.flipY
        if (untouched) {
            surface?.setPreview(-1, null)
            invalidate()
            return
        }
        val activeBefore = artwork.activeLayerId
        artwork.activeLayerId = t.layerId
        applyAdjustment(transformAdjustment(t))
        artwork.activeLayerId = activeBefore
    }

    fun cancelTransform() {
        if (transform == null) return
        transform = null
        surface?.setPreview(-1, null)
        invalidate()
    }

    fun flipTransform(horizontal: Boolean) {
        val t = transform ?: return
        if (horizontal) t.flipX = !t.flipX else t.flipY = !t.flipY
        updateTransformPreview()
    }

    fun rotateTransform90() {
        val t = transform ?: return
        t.rotation = (t.rotation + 90f) % 360f
        updateTransformPreview()
    }

    fun resetTransform() {
        val t = transform ?: return
        t.dx = 0f; t.dy = 0f; t.scale = 1f; t.rotation = 0f; t.flipX = false; t.flipY = false
        updateTransformPreview()
    }

    /** Box corners (4 points) then the top-middle point, after the pending transform. */
    private fun transformedBox(t: TransformState): FloatArray {
        val b = t.bounds
        val pts = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom, b.centerX(), b.top)
        transformAdjustment(t).matrix().mapPoints(pts)
        return pts
    }

    private fun rotateHandle(t: TransformState, box: FloatArray): Pair<Float, Float> {
        val cx = t.bounds.centerX() + t.dx
        val cy = t.bounds.centerY() + t.dy
        var ux = box[8] - cx
        var uy = box[9] - cy
        val len = hypot(ux, uy)
        if (len < 1f) { ux = 0f; uy = -1f } else { ux /= len; uy /= len }
        val off = 40 * density / zoom
        return (box[8] + ux * off) to (box[9] + uy * off)
    }

    private fun drawTransformOverlay(canvas: Canvas, t: TransformState) {
        val box = transformedBox(t)
        boxPath.reset()
        boxPath.moveTo(box[0], box[1])
        for (i in 1..3) boxPath.lineTo(box[i * 2], box[i * 2 + 1])
        boxPath.close()
        canvas.drawPath(boxPath, boxPaint)
        val (hx, hy) = rotateHandle(t, box)
        canvas.drawLine(box[8], box[9], hx, hy, boxPaint)
        boxPaint.strokeWidth = 2 * density / zoom
        val r = 9 * density / zoom
        for (i in 0..3) {
            canvas.drawCircle(box[i * 2], box[i * 2 + 1], r, handleFill)
            canvas.drawCircle(box[i * 2], box[i * 2 + 1], r, boxPaint)
        }
        canvas.drawCircle(hx, hy, r, handleFill)
        canvas.drawCircle(hx, hy, r, boxPaint)
    }

    private fun beginGrab(t: TransformState, mode: Grab, x: Float, y: Float) {
        grab = mode
        grabX = x
        grabY = y
        baseDx = t.dx
        baseDy = t.dy
        baseScale = t.scale
        baseRotation = t.rotation
    }

    private fun transformTouch(e: MotionEvent) {
        val t = transform ?: return
        val cx = t.bounds.centerX() + t.dx
        val cy = t.bounds.centerY() + t.dy
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val box = transformedBox(t)
                val (hx, hy) = rotateHandle(t, box)
                val hit = 28 * density / zoom
                val mode = when {
                    hypot(e.x - hx, e.y - hy) < hit -> Grab.ROTATE
                    (0..3).any { hypot(e.x - box[it * 2], e.y - box[it * 2 + 1]) < hit } -> Grab.SCALE
                    else -> Grab.MOVE
                }
                beginGrab(t, mode, e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                beginGrab(t, Grab.PINCH, 0f, 0f)
                pinchDist = max(1f, hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)))
                pinchAngle = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                pinchMidX = (e.getX(0) + e.getX(1)) / 2f
                pinchMidY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                when (grab) {
                    Grab.MOVE -> {
                        t.dx = baseDx + e.x - grabX
                        t.dy = baseDy + e.y - grabY
                    }
                    Grab.SCALE -> {
                        val d0 = max(1f, hypot(grabX - cx, grabY - cy))
                        t.scale = max(0.02f, baseScale * hypot(e.x - cx, e.y - cy) / d0)
                    }
                    Grab.ROTATE -> {
                        val a0 = Math.toDegrees(kotlin.math.atan2((grabY - cy).toDouble(), (grabX - cx).toDouble()))
                        val a1 = Math.toDegrees(kotlin.math.atan2((e.y - cy).toDouble(), (e.x - cx).toDouble()))
                        t.rotation = baseRotation + (a1 - a0).toFloat()
                    }
                    Grab.PINCH -> if (e.pointerCount >= 2) {
                        val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                        val a = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                        t.scale = max(0.02f, baseScale * d / pinchDist)
                        t.rotation = baseRotation + (a - pinchAngle)
                        t.dx = baseDx + (e.getX(0) + e.getX(1)) / 2f - pinchMidX
                        t.dy = baseDy + (e.getY(0) + e.getY(1)) / 2f - pinchMidY
                    }
                    Grab.NONE -> {}
                }
                updateTransformPreview()
            }
            // Lifting a finger mid-pinch ends that gesture; touch again to carry on.
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grab = Grab.NONE
        }
    }

    // ---- Layers ----

    /** Returns false when the layer limit is reached. */
    fun addLayer(): Boolean {
        if (artwork.layers.size >= maxLayers) return false
        artwork.addLayer()
        invalidate()
        return true
    }

    fun deleteLayer(id: Int) {
        if (artwork.layers.size <= 1) {
            clearLayer(id)
            return
        }
        val index = artwork.layers.indexOfFirst { it.id == id }
        if (index < 0) return
        artwork.layers.removeAt(index)
        artwork.history.removeAll { it.layerId == id }
        redoStack.removeAll { it.layerId == id }
        surface?.removeLayer(id)
        if (artwork.activeLayerId == id) artwork.activeLayerId = artwork.layers[max(0, index - 1)].id
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun clearLayer(id: Int) {
        artwork.history.removeAll { it.layerId == id }
        redoStack.removeAll { it.layerId == id }
        surface?.clearLayer(id)
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** [delta] +1 moves the layer up (towards the top), -1 down. */
    fun moveLayer(id: Int, delta: Int) {
        val index = artwork.layers.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in artwork.layers.indices) return
        Collections.swap(artwork.layers, index, target)
        invalidate()
    }

    /**
     * Puts a photo on a new layer above the current one, centred and shrunk to fit.
     * Returns false when the layer limit is reached.
     */
    fun insertImage(file: String, bitmap: Bitmap, at: RectF? = null, layerName: String = "Inserted image"): Boolean {
        if (artwork.layers.size >= maxLayers) return false
        artwork.addLayer().name = layerName
        val cw = artwork.width.toFloat()
        val ch = artwork.height.toFloat()
        val fit = min(1f, min(cw * 0.9f / bitmap.width, ch * 0.9f / bitmap.height))
        val w = bitmap.width * fit
        val h = bitmap.height * fit
        val rect = at ?: RectF((cw - w) / 2f, (ch - h) / 2f, (cw + w) / 2f, (ch + h) / 2f)
        val stroke = Stroke(artwork.activeLayerId, Color.BLACK, 1f, 1f, false, brush, 0)
        stroke.image = PlacedImage(file, bitmap, rect)
        stroke.points.add(StrokePoint(rect.centerX(), rect.centerY(), 1f))
        surface?.drawStroke(stroke)
        artwork.history.add(stroke)
        redoStack.clear()
        invalidate()
        onHistoryChanged?.invoke()
        return true
    }

    /** Shows an adjustment on the selected layer without applying it (null cancels). */
    fun previewAdjustment(adj: Adjustment?) {
        surface?.setPreview(artwork.activeLayerId, adj)
        invalidate()
    }

    /** Applies an adjustment to the selected layer as an undoable step. */
    fun applyAdjustment(adj: Adjustment, layerId: Int = artwork.activeLayerId, linked: Boolean = false) {
        surface?.setPreview(-1, null)
        val stroke = Stroke(layerId, Color.BLACK, 1f, 1f, false, brush, 0)
        stroke.adjustment = adj
        stroke.linkedToPrevious = linked
        stroke.points.add(StrokePoint(artwork.width / 2f, artwork.height / 2f, 1f))
        surface?.drawStroke(stroke)
        artwork.history.add(stroke)
        redoStack.clear()
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** Call after changing a layer's visibility or opacity. */
    fun layersChanged() = invalidate()

    fun layerThumbnail(id: Int, maxWidth: Int): Bitmap {
        val w = max(1, artwork.width)
        val h = max(1, artwork.height)
        val tw = maxWidth
        val th = max(1, maxWidth * h / w)
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        surface?.layerBitmap(id)?.let { c.drawBitmap(it, null, Rect(0, 0, tw, th), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return out
    }

    /** The finished artwork, flattened onto white. */
    fun snapshot(): Bitmap? {
        val s = surface ?: return null
        val out = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        s.render(Canvas(out), artwork.layers)
        return out
    }

    fun thumbnail(maxWidth: Int): Bitmap? {
        val full = snapshot() ?: return null
        val h = max(1, maxWidth * full.height / full.width)
        return Bitmap.createScaledBitmap(full, maxWidth, h, true).also { full.recycle() }
    }

    /** Layers are frames in animation, so allow more there (each one is a full-size bitmap). */
    val maxLayers get() = if (artwork.animation.enabled) MAX_FRAMES else MAX_LAYERS

    companion object {
        const val MAX_LAYERS = 10
        const val MAX_FRAMES = 30
        private const val MIN_ZOOM = 0.2f
        private const val MAX_ZOOM = 30f
    }
}
