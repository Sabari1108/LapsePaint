package com.lapsepaint.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min

/**
 * The Layers popup: top layer first. Tap a layer to select it, tap the
 * selected layer for its options, tick the box to show or hide it.
 */
class LayersPanel(
    private val activity: Activity,
    private val drawing: DrawingView,
    private val flash: (String) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(activity).apply { addView(list) }
    private val rowHeight get() = dp(64)
    private val rowGap get() = dp(6)

    fun show(anchor: View) {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(TextView(activity).apply {
                text = "Layers"
                textSize = 18f
                setTextColor(0xFFE8E8E8.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "+"
                textSize = 28f
                setTextColor(0xFF4FA3F7.toInt())
                setPadding(dp(14), 0, dp(6), 0)
                contentDescription = "Add layer"
                setOnClickListener {
                    if (!drawing.addLayer()) flash("Layer limit reached (${drawing.maxLayers})")
                    refresh()
                }
            })
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refresh()

        PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            showAsDropDown(anchor, -dp(310), dp(10))
        }
    }

    private fun refresh() {
        list.removeAllViews()
        val layers = drawing.artwork.layers
        for (layer in layers.asReversed()) {
            list.addView(row(layer), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight).apply {
                bottomMargin = rowGap
            })
        }
        scroll.layoutParams?.let {
            it.height = min(layers.size * (rowHeight + rowGap), dp(500))
            scroll.layoutParams = it
        }
    }

    private fun row(layer: Layer): View {
        val art = drawing.artwork
        val active = layer.id == art.activeLayerId
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (active) 0xFF2F6FD0.toInt() else 0xFF2C2C2C.toInt())
            }

            addView(ImageView(activity).apply {
                setImageBitmap(drawing.layerThumbnail(layer.id, dp(72)))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(72), dp(46)))

            addView(TextView(activity).apply {
                val pct = (layer.opacity * 100).toInt()
                text = if (pct < 100) "${layer.name}  ·  $pct%" else layer.name
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(12), 0, dp(8), 0)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(CheckBox(activity).apply {
                isChecked = layer.visible
                contentDescription = "Visible"
                setOnCheckedChangeListener { _, checked ->
                    layer.visible = checked
                    drawing.layersChanged()
                }
            })

            setOnClickListener {
                if (active) {
                    showOptions(layer)
                } else {
                    art.activeLayerId = layer.id
                    refresh()
                }
            }
        }
    }

    private fun showOptions(layer: Layer) {
        fun then(action: () -> Unit): () -> Unit = { action(); refresh() }
        activity.miuixMenu(layer.name, listOf(
            "Rename" to { rename(layer) },
            "Opacity" to { opacity(layer) },
            "Move up" to then { drawing.moveLayer(layer.id, +1) },
            "Move down" to then { drawing.moveLayer(layer.id, -1) },
            "Clear" to then { drawing.clearLayer(layer.id) },
            "Delete" to then { drawing.deleteLayer(layer.id) },
        ))
    }

    private fun rename(layer: Layer) = activity.miuixInput("Rename layer", layer.name) { name ->
        layer.name = name.ifBlank { layer.name }
        refresh()
    }

    private fun opacity(layer: Layer) = activity.miuixSliders(
        "${layer.name} opacity",
        listOf(SliderRow(0, 100, (layer.opacity * 100).toInt(), { "$it%" }) {
            layer.opacity = it / 100f
            drawing.layersChanged()
        }),
        onDone = { refresh() },
    )
}
