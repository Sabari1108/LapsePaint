package com.lapsepaint.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var drawing: DrawingView
    private lateinit var status: TextView
    private lateinit var btnBrush: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnSmudge: ImageButton

    private enum class Tool { BRUSH, SMUDGE, ERASER }
    private var tool = Tool.BRUSH
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnColor: View

    private val accent = 0xFF4FA3F7.toInt()
    private val iconIdle = 0xFFE8E8E8.toInt()
    private var exporting = false
    private val clearStatus = Runnable { if (!exporting) status.text = "" }

    private val palette = intArrayOf(
        Color.BLACK, Color.WHITE, 0xFF9E9E9E.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
        0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF6D4C41.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enterFullscreen()

        drawing = findViewById(R.id.drawing)
        status = findViewById(R.id.status)
        btnBrush = findViewById(R.id.btnBrush)
        btnEraser = findViewById(R.id.btnEraser)
        btnSmudge = findViewById(R.id.btnSmudge)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnColor = findViewById(R.id.btnColor)

        val id = intent.getStringExtra(EXTRA_ARTWORK_ID) ?: ArtworkStore.newId()
        val saved = ArtworkStore.load(this, id)
        drawing.setArtwork(saved ?: Artwork(id))
        if (saved == null) UsageStats.log("artwork_created")

        for (id in intArrayOf(R.id.btnActions, R.id.btnAdjust, R.id.btnSelect, R.id.btnTransform, R.id.btnLayers)) {
            findViewById<ImageButton>(id).setColorFilter(iconIdle)
        }
        btnUndo.setColorFilter(iconIdle)
        btnRedo.setColorFilter(iconIdle)

        findViewById<View>(R.id.btnGallery).setOnClickListener { openGallery() }
        findViewById<View>(R.id.btnActions).setOnClickListener { showActions() }
        findViewById<View>(R.id.btnAdjust).setOnClickListener {
            UsageStats.log("feature_used", "name" to "adjustments")
            AdjustmentsPanel(this, drawing, ::flash).showMenu()
        }
        findViewById<View>(R.id.btnSelect).setOnClickListener { if (drawing.selecting) endSelection() else startSelection() }
        findViewById<View>(R.id.btnTransform).setOnClickListener { startTransform() }
        findViewById<View>(R.id.btnLayers).setOnClickListener {
            UsageStats.log("feature_used", "name" to "layers")
            LayersPanel(this, drawing, ::flash).show(it)
        }
        // Like Procreate: tap a tool to pick it, tap it again to open the Brush Library.
        btnBrush.setOnClickListener { if (tool != Tool.BRUSH) setTool(Tool.BRUSH) else showBrushLibrary(it) }
        btnSmudge.setOnClickListener { if (tool != Tool.SMUDGE) setTool(Tool.SMUDGE) else flash("Smudge: drag through paint; opacity slider sets the strength") }
        btnEraser.setOnClickListener { if (tool != Tool.ERASER) setTool(Tool.ERASER) else showBrushLibrary(it) }
        btnColor.setOnClickListener { showColorPicker(it) }
        btnColor.setOnLongClickListener {
            armEyedropper()
            true
        }
        findViewById<View>(R.id.btnEyedropper).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setStroke(dp(2), 0xFFBFBFBF.toInt())
            }
            setOnClickListener { armEyedropper() }
        }
        btnUndo.setOnClickListener { drawing.undo() }
        btnRedo.setOnClickListener { drawing.redo() }

        val sizeSlider = findViewById<VerticalSlider>(R.id.sizeSlider)
        sizeSlider.onChange = { v ->
            // Squared so the lower half of the slider gives fine control over small brushes.
            drawing.brushSizeDp = 1f + v * v * 99f
            flash("Size ${(v * 100).toInt()}%")
        }
        sizeSlider.value = 0.28f
        drawing.brushSizeDp = 1f + 0.28f * 0.28f * 99f

        val opacitySlider = findViewById<VerticalSlider>(R.id.opacitySlider)
        opacitySlider.onChange = { v ->
            drawing.brushOpacity = max(0.05f, v)
            flash("Opacity ${(drawing.brushOpacity * 100).toInt()}%")
        }
        opacitySlider.value = 1f

        // Palm rejection: the first time the pen touches the screen, stop fingers drawing.
        drawing.onPenDetected = {
            drawing.penOnly = true
            flash("Pen detected: fingers won't draw")
        }
        drawing.onHistoryChanged = {
            refreshHistoryButtons()
            if (::timeline.isInitialized && drawing.artwork.animation.enabled) timeline.refreshActive()
        }
        drawing.onMessage = { flash(it) }
        drawing.onColorPicked = { color ->
            UsageStats.log("feature_used", "name" to "eyedropper")
            drawing.brushColor = color
            updateColorButton()
            setTool(Tool.BRUSH)
        }
        drawing.onColorUsed = { RecentColors.add(this, it) }
        drawing.brush = BrushLibrary.selected(this)

        setTool(Tool.BRUSH)
        updateColorButton()
        refreshHistoryButtons()

        timeline = AnimationTimeline(this, drawing, ::flash) { gif -> exportAnimationVideo(gif) }
        (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.FrameLayout).addView(
            timeline.view,
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM).apply {
                setMargins(dp(80), 0, dp(16), dp(16))
            },
        )
        showTimeline(drawing.artwork.animation.enabled)
    }

    // Screen time: how long each visit to the canvas lasts and how much was drawn.
    private var sessionStart = 0L
    private var strokesAtStart = 0

    override fun onResume() {
        super.onResume()
        sessionStart = android.os.SystemClock.elapsedRealtime()
        strokesAtStart = drawing.artwork.history.size
    }

    override fun onPause() {
        super.onPause()
        val seconds = (android.os.SystemClock.elapsedRealtime() - sessionStart) / 1000
        UsageStats.log(
            "drawing_session",
            "seconds" to seconds,
            "strokes_added" to (drawing.artwork.history.size - strokesAtStart).coerceAtLeast(0),
            "layers" to drawing.artwork.layers.size,
            "animation" to drawing.artwork.animation.enabled,
        )
        drawing.setPlaying(false)
        // Leaving mid-transform cancels it, so a half-finished move is never saved.
        if (drawing.isTransforming) endTransform(apply = false)
        if (drawing.selecting) endSelection()
        // Auto-save whenever you leave the canvas, like Procreate.
        ArtworkStore.save(this, drawing.artwork, drawing.thumbnail(480))
    }

    private fun openGallery() {
        if (isTaskRoot) startActivity(Intent(this, GalleryActivity::class.java))
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun flash(text: String) {
        if (exporting) return
        status.text = text
        status.removeCallbacks(clearStatus)
        status.postDelayed(clearStatus, 1200)
    }

    private fun comingSoon(name: String) = flash("$name: coming soon")

    private fun showBrushLibrary(anchor: View) {
        BrushLibraryPanel(this) { brush ->
            drawing.brush = brush
            UsageStats.log("brush_selected", "brush" to brush.id)
            flash(brush.name)
        }.show(anchor)
    }

    private fun setTool(t: Tool) {
        if (t != tool) UsageStats.log("tool_selected", "tool" to t.name.lowercase())
        tool = t
        drawing.eraser = t == Tool.ERASER
        drawing.smudge = t == Tool.SMUDGE
        btnBrush.setColorFilter(if (t == Tool.BRUSH) accent else iconIdle)
        btnSmudge.setColorFilter(if (t == Tool.SMUDGE) accent else iconIdle)
        btnEraser.setColorFilter(if (t == Tool.ERASER) accent else iconIdle)
    }

    private fun refreshHistoryButtons() {
        btnUndo.alpha = if (drawing.canUndo) 1f else 0.35f
        btnRedo.alpha = if (drawing.canRedo) 1f else 0.35f
    }

    private fun updateColorButton() {
        btnColor.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(drawing.brushColor)
            setStroke(dp(2), 0xFFE8E8E8.toInt())
        }
    }

    private fun armEyedropper() {
        drawing.armEyedropper()
        flash("Touch the canvas to pick a colour")
    }

    private fun showColorPicker(anchor: View) {
        val picker = ColorPickerView(this).apply { setColor(drawing.brushColor) }
        val apply = { color: Int ->
            drawing.brushColor = color
            updateColorButton()
            setTool(Tool.BRUSH)
        }
        picker.onColorChanged = apply

        fun swatchRow(colors: List<Int>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (color in colors) {
                val swatch = View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(color)
                        setStroke(dp(1), 0x55FFFFFF)
                    }
                    setOnClickListener {
                        picker.setColor(color)
                        apply(color)
                    }
                }
                addView(swatch, LinearLayout.LayoutParams(dp(24), dp(26)).apply { marginEnd = dp(4) })
            }
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(0, dp(12), 0, dp(6))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(context).apply {
                text = "Colour"
                setTextColor(0xFFE8E8E8.toInt())
                textSize = 16f
                setPadding(0, 0, 0, dp(10))
            })
            addView(picker, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT))
            val recent = RecentColors.get(this@MainActivity)
            if (recent.isNotEmpty()) {
                addView(label("Recent"))
                addView(swatchRow(recent))
            }
            addView(label("Palette"))
            addView(swatchRow(palette.toList()))
        }

        PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            showAsDropDown(anchor, -dp(290), dp(10))
        }
    }

    private fun showActions() {
        val anim = drawing.artwork.animation
        val actions = listOf<Pair<String, () -> Unit>>(
            (if (anim.enabled) "Turn off Animation" else "Animation (flipbook)") to { toggleAnimation() },
            "Time-lapse replay" to { replayTimelapse() },
            "Export time-lapse video" to { chooseTimelapseLength() },
            "Insert a photo" to { pickImage() },
            "Save as PNG" to { savePng() },
            (if (drawing.penOnly) "Let fingers draw" else "Pen only (palm rejection)") to {
                drawing.penOnly = !drawing.penOnly
                flash(if (drawing.penOnly) "Pen only" else "Fingers can draw")
            },
            "Clear canvas" to { confirmClear() },
        ).let { list ->
            if (!UsageStats.isConfigured) list
            else list + ((if (UsageStats.isEnabled) "Stop sharing usage data" else "Share anonymous usage data") to {
                UsageStats.setEnabled(!UsageStats.isEnabled)
                flash(if (UsageStats.isEnabled) "Sharing anonymous usage data" else "Usage data off")
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Actions")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private lateinit var timeline: AnimationTimeline

    private fun showTimeline(show: Boolean) {
        timeline.view.visibility = if (show) View.VISIBLE else View.GONE
        if (show) timeline.refresh()
    }

    private fun toggleAnimation() {
        UsageStats.log("feature_used", "name" to "animation")
        val anim = drawing.artwork.animation
        drawing.setPlaying(false)
        anim.enabled = !anim.enabled
        showTimeline(anim.enabled)
        drawing.invalidate()
        flash(if (anim.enabled) "Animation on: each layer is a frame" else "Animation off")
    }

    private fun exportAnimationVideo(gif: Boolean) {
        val art = drawing.artwork
        if (art.history.isEmpty()) {
            toast("Draw something first")
            return
        }
        drawing.setPlaying(false)
        val layers = art.layers.map { it.copy() }
        val strokes = art.history.toList()
        val settings = art.animation.copy()
        val w = drawing.canvasWidth
        val h = drawing.canvasHeight
        if (gif) {
            runVideoExport("GIF", "GIF saved to Pictures/LapsePaint") { progress ->
                exportAnimationGif(this, layers, strokes, w, h, settings, progress)
            }
        } else {
            runVideoExport("animation", "Animation saved to Movies/LapsePaint") { progress ->
                exportAnimation(this, layers, strokes, w, h, settings, progress)
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Clear every layer? This also clears the time-lapse recording.")
            .setPositiveButton("Clear") { _, _ -> drawing.clearAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePng() {
        val bmp = drawing.snapshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "artwork_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LapsePaint")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("Could not save image")
            return
        }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast("Saved to Pictures/LapsePaint")
        UsageStats.log("export", "type" to "png")
    }

    private fun pickImage() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK) data?.data?.let { insertImage(it) }
    }

    private fun insertImage(uri: Uri) {
        val maxW = drawing.canvasWidth
        val maxH = drawing.canvasHeight
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                // Software pixels so the canvas can draw it; no bigger than the canvas.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = info.size
                val fit = minOf(1f, maxW.toFloat() / s.width, maxH.toFloat() / s.height)
                if (fit < 1f) decoder.setTargetSize(maxOf(1, (s.width * fit).toInt()), maxOf(1, (s.height * fit).toInt()))
            }
        }.getOrNull()
        if (bitmap == null) {
            toast("Couldn't open that image")
            return
        }
        // Crop first, then drop straight into Transform to place it, like Procreate.
        showCropPhoto(this, bitmap) { photo ->
            val file = ArtworkStore.saveImage(this, drawing.artwork.id, photo)
            UsageStats.log("feature_used", "name" to "insert_photo")
            if (!drawing.insertImage(file, photo)) {
                toast("Layer limit reached (${drawing.maxLayers})")
                return@showCropPhoto
            }
            // Wait for the crop screen to finish closing before showing the Transform bar.
            window.decorView.postDelayed({ startTransform() }, 250)
        }
    }

    private var transformBar: PopupWindow? = null

    private var selectionBar: PopupWindow? = null

    private fun startSelection() {
        UsageStats.log("feature_used", "name" to "selection")
        if (drawing.isTransforming) endTransform(apply = false)
        drawing.startSelecting()
        findViewById<ImageButton>(R.id.btnSelect).setColorFilter(accent)
        flash("Draw around an area to select it")
        val needArea = { done: Boolean -> if (!done) flash("Draw around an area first") }
        selectionBar = toolBar(
            "Invert" to { needArea(drawing.invertSelection()) },
            "Clear" to { needArea(drawing.clearSelectedArea()) },
            "Fill" to { needArea(drawing.fillSelectedArea(drawing.brushColor)) },
            "Copy & Paste" to { paste(cut = false) },
            "Cut & Paste" to { paste(cut = true) },
            "Deselect" to { drawing.clearSelection() },
            "" to {},
            "Done" to { endSelection() },
        )
    }

    /** Puts the selected area on a new layer, then lets you move it with Transform. */
    private fun paste(cut: Boolean) {
        val problem = drawing.pasteSelection(cut)
        if (problem != null) {
            flash(problem)
            return
        }
        endSelection()
        window.decorView.postDelayed({ startTransform() }, 250)
    }

    private fun endSelection() {
        drawing.stopSelecting()
        selectionBar?.dismiss()
        selectionBar = null
        findViewById<ImageButton>(R.id.btnSelect).setColorFilter(iconIdle)
    }

    /**
     * A row of text buttons pinned to the bottom of the screen. An empty label is a
     * divider; the last button is highlighted. Not focusable, so the canvas still gets touches.
     */
    private fun toolBar(vararg buttons: Pair<String, () -> Unit>): PopupWindow {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(8), 0, dp(8), 0)
            buttons.forEachIndexed { i, (label, onClick) ->
                if (label.isEmpty()) {
                    addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(28)))
                } else {
                    addView(TextView(context).apply {
                        text = label
                        textSize = 15f
                        maxLines = 1
                        setTextColor(if (i == buttons.lastIndex) accent else 0xFFE8E8E8.toInt())
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        setOnClickListener { onClick() }
                    })
                }
            }
        }
        return PopupWindow(bar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = dp(12).toFloat()
            showAtLocation(window.decorView, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, dp(24))
        }
    }

    private fun startTransform() {
        UsageStats.log("feature_used", "name" to "transform")
        if (drawing.selecting) endSelection()
        if (drawing.isTransforming) return
        if (!drawing.startTransform()) {
            flash("Nothing to transform on ${drawing.artwork.activeLayer.name}")
            return
        }
        findViewById<ImageButton>(R.id.btnTransform).setColorFilter(accent)
        flash("Drag to move · drag a corner or pinch to resize · drag the top handle to rotate")

        fun button(label: String, color: Int = 0xFFE8E8E8.toInt(), onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            maxLines = 1
            setTextColor(color)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(8), 0, dp(8), 0)
            addView(button("Flip ↔") { drawing.flipTransform(horizontal = true) })
            addView(button("Flip ↕") { drawing.flipTransform(horizontal = false) })
            addView(button("Rotate 90°") { drawing.rotateTransform90() })
            addView(button("Reset") { drawing.resetTransform() })
            addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(28)))
            addView(button("Cancel", 0xFFBFBFBF.toInt()) { endTransform(apply = false) })
            addView(button("Done", accent) { endTransform(apply = true) })
        }
        // Not focusable, so touches on the canvas still reach the transform handles.
        transformBar = PopupWindow(bar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = dp(12).toFloat()
            showAtLocation(window.decorView, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, dp(24))
        }
    }

    private fun endTransform(apply: Boolean) {
        if (apply) drawing.commitTransform() else drawing.cancelTransform()
        transformBar?.dismiss()
        transformBar = null
        findViewById<ImageButton>(R.id.btnTransform).setColorFilter(iconIdle)
    }

    private fun replayTimelapse() {
        UsageStats.log("feature_used", "name" to "timelapse_replay")
        if (drawing.artwork.history.isEmpty()) {
            toast("Draw something first")
            return
        }
        showTimelapseReplay(this, drawing.artwork) { chooseTimelapseLength() }
    }

    private fun chooseTimelapseLength() {
        if (drawing.artwork.history.isEmpty()) {
            toast("Draw something first")
            return
        }
        if (exporting) return
        val lengths = intArrayOf(15, 30, 60)
        AlertDialog.Builder(this)
            .setTitle("Time-lapse length")
            .setItems(lengths.map { "$it seconds" }.toTypedArray()) { _, which -> exportTimelapse(lengths[which]) }
            .show()
    }

    private fun exportTimelapse(seconds: Int) {
        val layers = drawing.artwork.layers.map { it.copy() }
        val strokes = drawing.artwork.history.toList()
        val w = drawing.canvasWidth
        val h = drawing.canvasHeight
        runVideoExport("time-lapse", "Time-lapse saved to Movies/LapsePaint") { progress ->
            TimelapseExporter(this).export(layers, strokes, w, h, seconds, progress)
        }
    }

    /** Runs a video export in the background, showing progress in the top bar. */
    private fun runVideoExport(what: String, done: String, job: ((Float) -> Unit) -> android.net.Uri) {
        if (exporting) return
        exporting = true
        status.removeCallbacks(clearStatus)
        status.text = "Exporting $what 0%"
        thread(name = "video-export") {
            var lastPct = -1
            val result = runCatching {
                job { p ->
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        runOnUiThread { status.text = "Exporting $what $pct%" }
                    }
                }
            }
            runOnUiThread {
                exporting = false
                status.text = ""
                result
                    .onSuccess {
                        toast(done)
                        UsageStats.log("export", "type" to what)
                    }
                    .onFailure { toast("Export failed: ${it.message}") }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ARTWORK_ID = "artwork_id"
        private const val REQUEST_IMAGE = 1
    }
}
