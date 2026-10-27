package com.lapsepaint.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/**
 * Exports the animation as an MP4: each layer is one frame, played bottom to top
 * (or forwards then backwards for ping-pong) with its hold, over the background
 * frame if there is one, repeated to last at least 3 seconds.
 * [layers] and [strokes] should be copies: export runs on a background thread.
 */
fun exportAnimation(
    context: Context,
    layers: List<Layer>,
    strokes: List<Stroke>,
    canvasWidth: Int,
    canvasHeight: Int,
    settings: AnimationSettings,
    onProgress: (Float) -> Unit,
): Uri {
    val (w, h) = videoSize(canvasWidth, canvasHeight)
    val surface = PaintSurface(w, h, w.toFloat() / canvasWidth)
    for (s in strokes) surface.drawStroke(s)
    val order = settings.playOrder(layers)
    val fps = max(1, settings.fps)
    val loops = max(1, ceil(3f * fps / order.size).toInt())
    return writeVideo(context, "animation", w, h, fps, order.size * loops, { f, canvas ->
        surface.renderFrame(canvas, layers, order[f % order.size], 0, 0f, settings.background)
    }, onProgress)
}

/**
 * Exports the animation as a looping GIF in Pictures/LapsePaint, at most 800 px on
 * the long side. Consecutive repeats of a frame (holds) become one longer frame.
 */
fun exportAnimationGif(
    context: Context,
    layers: List<Layer>,
    strokes: List<Stroke>,
    canvasWidth: Int,
    canvasHeight: Int,
    settings: AnimationSettings,
    onProgress: (Float) -> Unit,
): Uri {
    val scale = minOf(1f, 800f / maxOf(canvasWidth, canvasHeight))
    val w = maxOf(1, (canvasWidth * scale).toInt())
    val h = maxOf(1, (canvasHeight * scale).toInt())
    val surface = PaintSurface(w, h, scale)
    for (s in strokes) surface.drawStroke(s)

    // Merge runs of the same frame: (layer index, ticks).
    val runs = ArrayList<Pair<Int, Int>>()
    for (i in settings.playOrder(layers)) {
        if (runs.isNotEmpty() && runs.last().first == i) runs[runs.size - 1] = i to runs.last().second + 1
        else runs.add(i to 1)
    }

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "animation_${System.currentTimeMillis()}.gif")
        put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LapsePaint")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not create GIF file")
    try {
        resolver.openOutputStream(uri)!!.buffered().use { stream ->
            val gif = GifWriter(stream, w, h)
            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val pixels = IntArray(w * h)
            val fps = max(1, settings.fps)
            var ticks = 0
            var shownCs = 0
            runs.forEachIndexed { n, (index, length) ->
                surface.renderFrame(canvas, layers, index, 0, 0f, settings.background)
                frame.getPixels(pixels, 0, w, 0, 0, w, h)
                // Round on the running total so the overall speed stays right.
                ticks += length
                val endCs = ticks * 100 / fps
                gif.addFrame(pixels, endCs - shownCs)
                shownCs = endCs
                onProgress((n + 1).toFloat() / runs.size)
            }
            gif.finish()
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

/**
 * The timeline along the bottom in animation mode: frame thumbnails plus
 * Play, + Frame, Settings and Export. Tap a frame to draw on it; tap the
 * selected frame for Duplicate / Delete.
 */
class AnimationTimeline(
    private val activity: Activity,
    private val drawing: DrawingView,
    private val flash: (String) -> Unit,
    private val onExport: (gif: Boolean) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val accent = 0xFF4FA3F7.toInt()

    private val frames = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, 0)
    }
    private val scroller = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(frames)
    }
    private val play = button("Play") { drawing.setPlaying(!drawing.isPlaying) }
    private val thumbs = ArrayList<ImageView>()

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        setPadding(dp(12), dp(6), dp(12), dp(10))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "Animation"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(play)
            addView(button("+ Frame") { addFrame() })
            addView(button("Settings") { showSettings() })
            addView(button("Export", accent) { chooseExport() })
        })
        addView(scroller)
    }

    init {
        drawing.onPlayingChanged = { playing -> play.text = if (playing) "Pause" else "Play" }
    }

    private fun button(label: String, color: Int = 0xFFE8E8E8.toInt(), onClick: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 15f
        setTextColor(color)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun addFrame() {
        drawing.setPlaying(false)
        if (!drawing.addLayer()) flash("Frame limit reached (${drawing.maxLayers})")
        refresh()
        scrollToActive()
    }

    /** Rebuilds every thumbnail. Call after frames are added, removed or reordered. */
    fun refresh() {
        frames.removeAllViews()
        thumbs.clear()
        val art = drawing.artwork
        val bg = art.animation.background
        art.layers.forEachIndexed { i, layer ->
            val active = layer.id == art.activeLayerId
            val isBackground = bg && i == 0
            val label = if (isBackground) "BG" else "${i + 1 - art.animation.firstFrame}"
            val image = ImageView(activity).apply {
                setImageBitmap(drawing.layerThumbnail(layer.id, dp(96)))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            thumbs.add(image)
            val cell = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xFF2C2C2C.toInt())
                    setStroke(dp(if (active) 3 else 1), if (active) accent else 0x44FFFFFF)
                }
                setPadding(dp(3), dp(3), dp(3), dp(3))
                addView(image, FrameLayout.LayoutParams(dp(96), dp(60)))
                addView(TextView(activity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if (isBackground) 0xCC2F6FD0.toInt() else 0x99000000.toInt())
                    setPadding(dp(4), 0, dp(4), 0)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
                if (!isBackground && layer.hold > 0) {
                    // How many ticks this frame lasts.
                    addView(TextView(activity).apply {
                        text = "×${layer.hold + 1}"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(0xCCE65100.toInt())
                        setPadding(dp(4), 0, dp(4), 0)
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
                }
                setOnClickListener {
                    drawing.setPlaying(false)
                    if (active) frameOptions(i) else {
                        drawing.selectFrame(i)
                        refresh()
                    }
                }
            }
            frames.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
    }

    /** Updates just the selected frame's thumbnail (cheap enough to call after every stroke). */
    fun refreshActive() {
        val art = drawing.artwork
        val i = art.layers.indexOfFirst { it.id == art.activeLayerId }
        if (i !in thumbs.indices || thumbs.size != art.layers.size) {
            refresh()
            return
        }
        thumbs[i].setImageBitmap(drawing.layerThumbnail(art.activeLayerId, dp(96)))
    }

    private fun scrollToActive() {
        scroller.post { scroller.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun frameOptions(index: Int) {
        val art = drawing.artwork
        val anim = art.animation
        val layer = art.layers.getOrNull(index) ?: return
        val isBackground = anim.background && index == 0
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (!isBackground) {
            options.add("Hold… (now ×${layer.hold + 1})" to { showHold(layer) })
        }
        if (index == 0) {
            options.add((if (anim.background) "Make it a normal frame" else "Set as background") to {
                anim.background = !anim.background
                drawing.invalidate()
                flash(if (anim.background) "Frame 1 is now the background" else "Background is a frame again")
            })
        }
        options.add("Duplicate" to { drawing.duplicateFrame()?.let(flash) })
        options.add("Delete" to { drawing.deleteLayer(layer.id) })
        activity.miuixMenu(
            if (isBackground) "Background" else "Frame ${index + 1 - anim.firstFrame}",
            options.map { (label, action) -> label to { action(); refresh() } },
        )
    }

    /** How long a frame stays on screen, in playback ticks. */
    private fun showHold(layer: Layer) {
        val fps = drawing.artwork.animation.fps
        activity.miuixSliders(
            "Hold frame",
            listOf(SliderRow(0, 12, layer.hold, { hold ->
                "Shown for ${hold + 1} tick${if (hold == 0) "" else "s"} (${(hold + 1) * 1000 / fps} ms at $fps fps)"
            }) { layer.hold = it }),
            onDone = { refresh() },
        )
    }

    private fun chooseExport() {
        drawing.setPlaying(false)
        activity.miuixMenu("Export animation", listOf(
            "MP4 video" to { onExport(false) },
            "Animated GIF" to { onExport(true) },
        ))
    }

    private fun showSettings() {
        val anim = drawing.artwork.animation
        fun changed(apply: (Int) -> Unit): (Int) -> Unit = { apply(it); drawing.invalidate() }
        activity.miuixSliders(
            "Animation settings",
            listOf(
                SliderRow(1, 30, anim.fps, { "Frames per second: $it" }, changed { anim.fps = it }),
                SliderRow(0, 5, anim.onionFrames, { "Onion skin frames: $it" }, changed { anim.onionFrames = it }),
                SliderRow(10, 90, (anim.onionOpacity * 100).toInt(), { "Onion skin opacity: $it%" }, changed { anim.onionOpacity = it / 100f }),
            ),
            listOf(SwitchRow("Ping-pong (play forwards, then backwards)", anim.pingPong) { anim.pingPong = it }),
        )
    }
}
