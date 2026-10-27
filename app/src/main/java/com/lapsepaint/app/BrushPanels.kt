package com.lapsepaint.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

private const val ACCENT = 0xFF4FA3F7.toInt()
private const val TEXT = 0xFFE8E8E8.toInt()
private const val MUTED = 0xFF9A9A9A.toInt()
private const val ACCENT_FILL = 0xFF2F6FD0.toInt()

/** Paints a sample S-stroke with [brush], white on transparent, for the brush panels. */
fun brushPreview(brush: Brush, width: Int, height: Int): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val stroke = Stroke(0, Color.WHITE, height * 0.32f, 1f, false, brush, 1234)
    val n = 80
    for (i in 0..n) {
        val t = i / n.toFloat()
        val x = width * (0.08f + 0.84f * t)
        val y = height * (0.5f + 0.22f * sin(t * 2 * PI).toFloat())
        // Press in, then ease off, so pressure settings show.
        val pressure = (sin(t * PI).toFloat() * 0.9f + 0.1f)
        stroke.points.add(StrokePoint(x, y, pressure, 0.3f))
    }
    val canvas = Canvas(bmp)
    val painter = StrokePainter()
    for (i in stroke.points.indices) painter.drawSegment(canvas, stroke, i, clearPixels = false)
    painter.finish(canvas, stroke, clearPixels = false)
    return bmp
}

/**
 * Categories on the left, that category's brushes (name over a preview stroke) on
 * the right. Tap a brush to use it; tap the selected brush again for the Brush Studio.
 */
class BrushLibraryPanel(
    private val activity: Activity,
    private val onSelected: (Brush) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val categoryList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val brushList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var popup: PopupWindow? = null
    private var anchor: View? = null
    private var category = BrushLibrary.categories.first()

    fun show(anchor: View) {
        this.anchor = anchor
        val selected = BrushLibrary.selected(activity)
        category = BrushLibrary.categories.firstOrNull { selected.id in it.brushIds } ?: category

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ScrollView(activity).apply {
                setPadding(0, 0, dp(10), 0)
                addView(categoryList)
            }, LinearLayout.LayoutParams(dp(190), dp(470)))
            addView(ScrollView(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFF1B1B1B.toInt())
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(brushList)
            }, LinearLayout.LayoutParams(dp(300), dp(470)))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(activity).apply {
                text = "Brush Library"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
                setPadding(dp(4), 0, 0, dp(12))
            })
            addView(body)
            addView(TextView(activity).apply {
                text = "Tap the selected brush to edit it"
                textSize = 12f
                setTextColor(MUTED)
                setPadding(dp(4), dp(10), 0, 0)
            })
        }
        refresh()
        popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            showAsDropDown(anchor, -dp(470), dp(10))
        }
    }

    private fun pill(active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (active) ACCENT_FILL else 0xFF2C2C2C.toInt())
    }

    private fun refresh() {
        categoryList.removeAllViews()
        for (c in BrushLibrary.categories) {
            val active = c === category
            categoryList.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = pill(active)
                addView(ImageView(activity).apply { setImageResource(c.icon) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(TextView(activity).apply {
                    text = c.name
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0)
                })
                setOnClickListener {
                    category = c
                    refresh()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        brushList.removeAllViews()
        val selected = BrushLibrary.selected(activity)
        val brushes = BrushLibrary.all(activity).filter { it.id in category.brushIds }
        for (brush in brushes) {
            val active = brush.id == selected.id
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(4))
                background = if (active) pill(true) else null
                addView(TextView(activity).apply {
                    text = brush.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(ImageView(activity).apply {
                    setImageBitmap(brushPreview(brush, dp(260), dp(52)))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
                setOnClickListener {
                    if (active) {
                        popup?.dismiss()
                        BrushStudioPanel(activity, brush, onSelected).show(anchor ?: this)
                    } else {
                        BrushLibrary.select(activity, brush)
                        onSelected(brush)
                        refresh()
                    }
                }
            }
            brushList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }
}

/** Sliders for every brush setting, with a live preview, grouped like Procreate's Brush Studio. */
class BrushStudioPanel(
    private val activity: Activity,
    private var brush: Brush,
    private val onChanged: (Brush) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val preview = ImageView(activity)
    private val sliders = ArrayList<Pair<BrushSetting, SeekBar>>()

    fun show(anchor: View) {
        val title = TextView(activity).apply {
            text = brush.name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER
        }
        val reset = TextView(activity).apply {
            text = "Reset"
            textSize = 15f
            setTextColor(ACCENT)
            setPadding(dp(10), dp(6), dp(4), dp(6))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(activity), LinearLayout.LayoutParams(dp(56), 1))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reset, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(14), 0)
        }
        var section = ""
        for (setting in BrushSetting.all) {
            if (setting.section != section) {
                section = setting.section
                body.addView(TextView(activity).apply {
                    text = section
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(MUTED)
                    setPadding(0, dp(16), 0, dp(4))
                })
            }
            body.addView(sliderRow(setting))
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(header)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)).apply {
                topMargin = dp(8)
            })
            addView(View(activity).apply { setBackgroundColor(0x33FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) })
            addView(ScrollView(activity).apply { addView(body) },
                LinearLayout.LayoutParams(dp(400), dp(430)))
        }
        updatePreview()

        reset.setOnClickListener {
            brush = BrushLibrary.reset(activity, brush.id)
            for ((s, bar) in sliders) bar.progress = (s.get(brush) * 100).toInt()
            onChanged(brush)
            updatePreview()
        }

        PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            showAsDropDown(anchor, -dp(390), dp(10))
        }
    }

    private fun sliderRow(setting: BrushSetting): View {
        val value = TextView(activity).apply {
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.END
        }
        val bar = SeekBar(activity).apply {
            max = 100
            progress = (setting.get(brush) * 100).toInt()
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
        }
        value.text = "${bar.progress}%"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = "$progress%"
                if (!fromUser) return
                brush = setting.set(brush, progress / 100f)
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                BrushLibrary.update(activity, brush)
                onChanged(brush)
            }
        })
        sliders.add(setting to bar)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = setting.label
                textSize = 14f
                setTextColor(TEXT)
            }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(value, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun updatePreview() {
        preview.setImageBitmap(brushPreview(brush, dp(368), dp(90)))
    }
}
