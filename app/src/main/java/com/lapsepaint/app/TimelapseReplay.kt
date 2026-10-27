package com.lapsepaint.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the artwork as it looked after the first [seekTo] points of its
 * history, using the same brush engine as the canvas and the exported video.
 */
class TimelapseReplayView(
    context: Context,
    private val layers: List<Layer>,
    private val strokes: List<Stroke>,
    artWidth: Int,
    artHeight: Int,
) : View(context) {

    // Replay at reduced resolution so scrubbing stays quick.
    private val scale = min(1f, 1600f / max(artWidth, artHeight))
    private val surface = PaintSurface(max(1, (artWidth * scale).toInt()), max(1, (artHeight * scale).toInt()), scale)

    val totalPoints = strokes.sumOf { it.points.size }
    var drawn = 0
        private set
    private var strokeIdx = 0
    private var pointIdx = 0

    fun seekTo(target: Int) {
        val t = target.coerceIn(0, totalPoints)
        if (t < drawn) {
            // Going backwards: start again from a blank canvas.
            surface.clearAll()
            strokeIdx = 0
            pointIdx = 0
            drawn = 0
        }
        while (drawn < t && strokeIdx < strokes.size) {
            val stroke = strokes[strokeIdx]
            surface.drawPoint(stroke, pointIdx)
            drawn++
            pointIdx++
            if (pointIdx >= stroke.points.size) {
                surface.endStroke(stroke)
                strokeIdx++
                pointIdx = 0
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val sw = surface.width.toFloat()
        val sh = surface.height.toFloat()
        val fit = min(width / sw, height / sh)
        canvas.save()
        canvas.translate((width - sw * fit) / 2f, (height - sh * fit) / 2f)
        canvas.scale(fit, fit)
        canvas.clipRect(0f, 0f, sw, sh)
        surface.render(canvas, layers)
        canvas.restore()
    }
}

/**
 * Full-screen time-lapse replay: plays the drawing back from a blank canvas,
 * with play/pause, a scrubber and a shortcut to export the video.
 */
fun showTimelapseReplay(activity: Activity, artwork: Artwork, onExport: () -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val replay = TimelapseReplayView(
        activity, artwork.layers.map { it.copy() }, artwork.history.toList(), artwork.width, artwork.height,
    )
    val total = max(1, replay.totalPoints)
    // Longer drawings play longer, between 5 and 30 seconds.
    val durationMs = (total / 400f * 1000f).coerceIn(5_000f, 30_000f)
    val pointsPerMs = total / durationMs

    fun button(label: String, color: Int = Color.WHITE) = TextView(activity).apply {
        text = label
        textSize = 16f
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    val play = button("Pause")
    val time = TextView(activity).apply {
        textSize = 14f
        setTextColor(0xFFBFBFBF.toInt())
        setPadding(dp(12), 0, dp(12), 0)
    }
    val scrubber = SeekBar(activity).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(0xFF4FA3F7.toInt())
        thumbTintList = ColorStateList.valueOf(0xFF4FA3F7.toInt())
    }
    val export = button("Export video", 0xFF4FA3F7.toInt())
    val done = button("Done", 0xFF4FA3F7.toInt())

    var playing = false
    var position = 0f // in points
    var lastFrame = 0L
    var userScrubbing = false

    fun seconds(ms: Float) = (ms / 1000f).toInt().let { "%d:%02d".format(it / 60, it % 60) }

    fun showPosition() {
        replay.seekTo(position.toInt())
        if (!userScrubbing) scrubber.progress = (position / total * 1000).toInt()
        time.text = "${seconds(position / pointsPerMs)} / ${seconds(durationMs)}"
    }

    val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            position += (now - lastFrame) * pointsPerMs
            lastFrame = now
            if (position >= total) {
                position = total.toFloat()
                playing = false
                play.text = "Replay"
            }
            showPosition()
            if (playing) replay.postOnAnimation(this)
        }
    }

    fun setPlaying(on: Boolean) {
        if (on && position >= total) position = 0f
        playing = on
        play.text = if (on) "Pause" else "Play"
        if (on) {
            lastFrame = SystemClock.uptimeMillis()
            replay.postOnAnimation(tick)
        }
    }

    play.setOnClickListener { setPlaying(!playing) }
    scrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            position = progress / 1000f * total
            showPosition()
        }
        override fun onStartTrackingTouch(bar: SeekBar) {
            userScrubbing = true
            setPlaying(false)
        }
        override fun onStopTrackingTouch(bar: SeekBar) {
            userScrubbing = false
        }
    })

    val top = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(8), dp(8))
        addView(TextView(activity).apply {
            text = "Time-lapse Replay"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(done)
    }
    val bottom = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundResource(R.drawable.panel_bg)
        addView(play, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scrubber, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(time)
        addView(export)
    }
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(top)
        addView(replay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(FrameLayout(activity).apply {
            setPadding(dp(24), dp(12), dp(24), dp(20))
            addView(bottom)
        })
    }

    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    dialog.setContentView(root)
    dialog.setOnDismissListener { playing = false }
    done.setOnClickListener { dialog.dismiss() }
    export.setOnClickListener {
        dialog.dismiss()
        onExport()
    }
    dialog.show()
    showPosition()
    setPlaying(true)
}
