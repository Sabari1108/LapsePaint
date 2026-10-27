package com.lapsepaint.app

import android.content.Context
import org.json.JSONObject

/**
 * A brush is a recipe for stamping "dabs" along a stroke. Every value is 0..1.
 * Brushes are immutable: editing one in the Brush Studio makes a copy, so
 * strokes already drawn keep the brush they were drawn with.
 */
data class Brush(
    val id: String,
    val name: String,
    // Stroke
    val spacing: Float,      // gap between dabs, as a fraction of the dab size
    val streamline: Float,   // smoothing of hand jitter
    val scatter: Float,      // random sideways offset of each dab
    val sizeJitter: Float,   // random size variation of each dab
    // Shape & grain
    val softness: Float,     // 0 = hard edge, 1 = airbrush falloff
    val grain: Float,        // paper-like texture inside each dab
    val flow: Float,         // how much paint each dab lays down
    // Pen pressure
    val pressureSize: Float,
    val pressureOpacity: Float,
    // Pen tilt
    val tiltSize: Float,
    val tiltOpacity: Float,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        for (s in BrushSetting.all) put(s.key, s.get(this@Brush).toDouble())
    }

    fun withJson(json: JSONObject): Brush {
        var b = this
        for (s in BrushSetting.all) if (json.has(s.key)) b = s.set(b, json.getDouble(s.key).toFloat())
        return b
    }
}

/** One slider in the Brush Studio. */
class BrushSetting(
    val key: String,
    val section: String,
    val label: String,
    val get: (Brush) -> Float,
    val set: (Brush, Float) -> Brush,
) {
    companion object {
        val all = listOf(
            BrushSetting("spacing", "Stroke", "Spacing", { it.spacing }, { b, v -> b.copy(spacing = v.coerceAtLeast(0.02f)) }),
            BrushSetting("streamline", "Stroke", "StreamLine", { it.streamline }, { b, v -> b.copy(streamline = v) }),
            BrushSetting("scatter", "Stroke", "Scatter", { it.scatter }, { b, v -> b.copy(scatter = v) }),
            BrushSetting("sizeJitter", "Stroke", "Size jitter", { it.sizeJitter }, { b, v -> b.copy(sizeJitter = v) }),
            BrushSetting("softness", "Shape & Grain", "Softness", { it.softness }, { b, v -> b.copy(softness = v) }),
            BrushSetting("grain", "Shape & Grain", "Grain", { it.grain }, { b, v -> b.copy(grain = v) }),
            BrushSetting("flow", "Shape & Grain", "Flow", { it.flow }, { b, v -> b.copy(flow = v.coerceAtLeast(0.01f)) }),
            BrushSetting("pressureSize", "Pen Pressure", "Size", { it.pressureSize }, { b, v -> b.copy(pressureSize = v) }),
            BrushSetting("pressureOpacity", "Pen Pressure", "Opacity", { it.pressureOpacity }, { b, v -> b.copy(pressureOpacity = v) }),
            BrushSetting("tiltSize", "Pen Tilt", "Size", { it.tiltSize }, { b, v -> b.copy(tiltSize = v) }),
            BrushSetting("tiltOpacity", "Pen Tilt", "Opacity", { it.tiltOpacity }, { b, v -> b.copy(tiltOpacity = v) }),
        )
    }
}

/** The built-in brushes, plus any edits made in the Brush Studio (saved on the device). */
object BrushLibrary {
    val defaults = listOf(
        Brush("studio_pen", "Studio Pen", 0.05f, 0.5f, 0f, 0f, 0f, 0f, 1f, 0.75f, 0f, 0f, 0f),
        Brush("technical_pen", "Technical Pen", 0.05f, 0.6f, 0f, 0f, 0f, 0f, 1f, 0.1f, 0f, 0f, 0f),
        Brush("ink_brush", "Ink Brush", 0.04f, 0.45f, 0f, 0f, 0.05f, 0f, 1f, 1f, 0.1f, 0f, 0f),
        Brush("pencil", "6B Pencil", 0.12f, 0.2f, 0.05f, 0.1f, 0.35f, 0.85f, 0.55f, 0.4f, 0.8f, 0.7f, 0.3f),
        Brush("soft_airbrush", "Soft Airbrush", 0.08f, 0.3f, 0f, 0f, 1f, 0f, 0.12f, 0.2f, 0.8f, 0.3f, 0f),
        Brush("marker", "Marker", 0.06f, 0.4f, 0f, 0f, 0.15f, 0.1f, 0.35f, 0.15f, 0.3f, 0.2f, 0f),
        Brush("wet_acrylic", "Wet Acrylic", 0.1f, 0.3f, 0.08f, 0.15f, 0.25f, 0.5f, 0.6f, 0.4f, 0.47f, 0.2f, 0.37f),
        Brush("chalk", "Chalk", 0.2f, 0.25f, 0.15f, 0.25f, 0.2f, 1f, 0.7f, 0.3f, 0.5f, 0.5f, 0.2f),
    )

    class Category(val name: String, val icon: Int, val brushIds: List<String>)

    val categories = listOf(
        Category("Sketching", R.drawable.ic_cat_sketch, listOf("pencil", "chalk")),
        Category("Inking", R.drawable.ic_cat_ink, listOf("studio_pen", "technical_pen", "ink_brush")),
        Category("Painting", R.drawable.ic_cat_paint, listOf("wet_acrylic", "marker")),
        Category("Airbrushing", R.drawable.ic_cat_air, listOf("soft_airbrush")),
    )

    /** Strokes saved before brushes existed are replayed with this. */
    val classic get() = defaults[0]

    private const val PREFS = "brushes"
    private var brushes: MutableList<Brush>? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Brush> {
        brushes?.let { return it }
        val p = prefs(ctx)
        val loaded = defaults.map { d ->
            p.getString("brush_${d.id}", null)?.let { runCatching { d.withJson(JSONObject(it)) }.getOrNull() } ?: d
        }.toMutableList()
        brushes = loaded
        return loaded
    }

    fun selected(ctx: Context): Brush {
        val id = prefs(ctx).getString("selected", null)
        return all(ctx).firstOrNull { it.id == id } ?: all(ctx).first()
    }

    fun select(ctx: Context, brush: Brush) {
        prefs(ctx).edit().putString("selected", brush.id).apply()
    }

    fun update(ctx: Context, brush: Brush) {
        val list = all(ctx) as MutableList<Brush>
        val i = list.indexOfFirst { it.id == brush.id }
        if (i >= 0) list[i] = brush
        prefs(ctx).edit().putString("brush_${brush.id}", brush.toJson().toString()).apply()
    }

    fun reset(ctx: Context, id: String): Brush {
        val d = defaults.first { it.id == id }
        val list = all(ctx) as MutableList<Brush>
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = d
        prefs(ctx).edit().remove("brush_$id").apply()
        return d
    }
}
