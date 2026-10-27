package com.lapsepaint.app

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

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
    // Added later; the defaults keep older brushes and saved strokes exactly as they were.
    val taper: Float = 0f,             // how long the stroke takes to swell to full size
    val tip: Float = 0f,               // dab shape, one of [TIPS]
    val angle: Float = 0f,             // dab rotation, one of [ANGLES]
    val velocitySize: Float = 0f,      // faster strokes get thinner
    val hueJitter: Float = 0f,         // random hue shift per dab
    val brightnessJitter: Float = 0f,  // random lightness shift per dab
    val wetMix: Float = 0f,            // how much colour each dab picks up from the canvas
) {
    val tipIndex get() = (tip * (TIPS.size - 1)).roundToInt()
    val angleIndex get() = (angle * (ANGLES.size - 1)).roundToInt()

    fun toJson(): JSONObject = JSONObject().apply {
        for (s in BrushSetting.all) put(s.key, s.get(this@Brush).toDouble())
    }

    fun withJson(json: JSONObject): Brush {
        var b = this
        for (s in BrushSetting.all) if (json.has(s.key)) b = s.set(b, json.getDouble(s.key).toFloat())
        return b
    }

    companion object {
        val TIPS = listOf("Round", "Flat", "Bristle", "Splatter", "Leaf")
        val ANGLES = listOf("Random", "Follow stroke", "Fixed 45°")
        const val TIP_FLAT = 0.25f
        const val TIP_BRISTLE = 0.5f
        const val TIP_SPLATTER = 0.75f
        const val TIP_LEAF = 1f
        const val ANGLE_FOLLOW = 0.5f
        const val ANGLE_FIXED = 1f
    }
}

/**
 * One control in the Brush Studio: a slider, or a row of choices when [choices] is set
 * (stored as index / (choices - 1)). The order of [all] is also the save-file order,
 * so new settings are only ever added at the end.
 */
class BrushSetting(
    val key: String,
    val section: String,
    val label: String,
    val get: (Brush) -> Float,
    val set: (Brush, Float) -> Brush,
    val choices: List<String>? = null,
) {
    companion object {
        val sections = listOf("Shape & Grain", "Stroke", "Dynamics", "Colour", "Pen Pressure", "Pen Tilt")

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
            // Added in save format 10.
            BrushSetting("taper", "Stroke", "Taper", { it.taper }, { b, v -> b.copy(taper = v) }),
            BrushSetting("tip", "Shape & Grain", "Tip", { it.tip }, { b, v -> b.copy(tip = v) }, Brush.TIPS),
            BrushSetting("angle", "Shape & Grain", "Angle", { it.angle }, { b, v -> b.copy(angle = v) }, Brush.ANGLES),
            BrushSetting("velocitySize", "Dynamics", "Speed → thin", { it.velocitySize }, { b, v -> b.copy(velocitySize = v) }),
            BrushSetting("hueJitter", "Colour", "Hue jitter", { it.hueJitter }, { b, v -> b.copy(hueJitter = v) }),
            BrushSetting("brightnessJitter", "Colour", "Light jitter", { it.brightnessJitter }, { b, v -> b.copy(brightnessJitter = v) }),
            BrushSetting("wetMix", "Colour", "Wet mix", { it.wetMix }, { b, v -> b.copy(wetMix = v) }),
        )
    }
}

/** The built-in brushes, plus any edits made in the Brush Studio (saved on the device). */
object BrushLibrary {
    val defaults = listOf(
        // Inking
        Brush("studio_pen", "Studio Pen", 0.05f, 0.5f, 0f, 0f, 0f, 0f, 1f, 0.75f, 0f, 0f, 0f, taper = 0.25f),
        Brush("technical_pen", "Technical Pen", 0.05f, 0.6f, 0f, 0f, 0f, 0f, 1f, 0.1f, 0f, 0f, 0f),
        Brush("ink_brush", "Ink Brush", 0.04f, 0.45f, 0f, 0f, 0.05f, 0f, 1f, 1f, 0.1f, 0f, 0f, taper = 0.4f, velocitySize = 0.3f),
        Brush("dry_ink", "Dry Ink", 0.05f, 0.4f, 0f, 0.05f, 0.05f, 0.55f, 0.95f, 0.9f, 0.2f, 0f, 0f, taper = 0.5f, velocitySize = 0.6f),
        Brush("calligraphy", "Calligraphy", 0.03f, 0.55f, 0f, 0f, 0.02f, 0f, 1f, 0.5f, 0f, 0f, 0f,
            tip = Brush.TIP_FLAT, angle = Brush.ANGLE_FIXED),
        // Sketching
        Brush("pencil", "6B Pencil", 0.12f, 0.2f, 0.05f, 0.1f, 0.35f, 0.85f, 0.55f, 0.4f, 0.8f, 0.7f, 0.3f),
        Brush("hb_pencil", "HB Pencil", 0.1f, 0.3f, 0.02f, 0.05f, 0.2f, 0.7f, 0.7f, 0.25f, 0.7f, 0.5f, 0.2f),
        Brush("charcoal", "Charcoal", 0.12f, 0.2f, 0.06f, 0.2f, 0.35f, 0.9f, 0.5f, 0.3f, 0.8f, 0.6f, 0.3f,
            tip = Brush.TIP_BRISTLE, angle = Brush.ANGLE_FOLLOW),
        Brush("chalk", "Chalk", 0.2f, 0.25f, 0.15f, 0.25f, 0.2f, 1f, 0.7f, 0.3f, 0.5f, 0.5f, 0.2f),
        // Painting
        Brush("oil_paint", "Oil Paint", 0.06f, 0.3f, 0f, 0.1f, 0.2f, 0.35f, 0.85f, 0.5f, 0.3f, 0.3f, 0f,
            tip = Brush.TIP_BRISTLE, angle = Brush.ANGLE_FOLLOW, wetMix = 0.5f),
        Brush("wet_acrylic", "Wet Acrylic", 0.1f, 0.3f, 0.08f, 0.15f, 0.25f, 0.5f, 0.6f, 0.4f, 0.47f, 0.2f, 0.37f),
        Brush("gouache", "Gouache", 0.07f, 0.35f, 0f, 0.05f, 0.15f, 0.25f, 0.9f, 0.4f, 0.2f, 0f, 0f,
            tip = Brush.TIP_FLAT, angle = Brush.ANGLE_FOLLOW, wetMix = 0.3f),
        Brush("watercolor", "Watercolour", 0.08f, 0.35f, 0.05f, 0.2f, 0.8f, 0.3f, 0.18f, 0.4f, 0.7f, 0.2f, 0f,
            hueJitter = 0.04f, wetMix = 0.6f),
        Brush("marker", "Marker", 0.06f, 0.4f, 0f, 0f, 0.15f, 0.1f, 0.35f, 0.15f, 0.3f, 0.2f, 0f),
        // Airbrushing
        Brush("soft_airbrush", "Soft Airbrush", 0.08f, 0.3f, 0f, 0f, 1f, 0f, 0.12f, 0.2f, 0.8f, 0.3f, 0f),
        Brush("hard_airbrush", "Hard Airbrush", 0.06f, 0.3f, 0f, 0f, 0.35f, 0f, 0.3f, 0.2f, 0.7f, 0f, 0f),
        Brush("spray", "Spray Paint", 0.15f, 0.2f, 0.9f, 0.7f, 0.3f, 0.2f, 0.5f, 0.3f, 0.6f, 0.4f, 0f,
            tip = Brush.TIP_SPLATTER),
        // Textures
        Brush("splatter", "Splatter", 0.6f, 0.1f, 1f, 0.8f, 0.05f, 0f, 0.9f, 0.4f, 0.2f, 0f, 0f,
            tip = Brush.TIP_SPLATTER, hueJitter = 0.08f, brightnessJitter = 0.2f),
        Brush("foliage", "Foliage", 0.45f, 0.2f, 0.7f, 0.6f, 0.05f, 0.1f, 0.95f, 0.4f, 0.1f, 0f, 0f,
            tip = Brush.TIP_LEAF, hueJitter = 0.12f, brightnessJitter = 0.3f),
    )

    class Category(val name: String, val icon: Int, val brushIds: List<String>)

    val categories = listOf(
        Category("Sketching", R.drawable.ic_cat_sketch, listOf("pencil", "hb_pencil", "charcoal", "chalk")),
        Category("Inking", R.drawable.ic_cat_ink, listOf("studio_pen", "technical_pen", "ink_brush", "dry_ink", "calligraphy")),
        Category("Painting", R.drawable.ic_cat_paint, listOf("oil_paint", "wet_acrylic", "gouache", "watercolor", "marker")),
        Category("Airbrushing", R.drawable.ic_cat_air, listOf("soft_airbrush", "hard_airbrush", "spray")),
        Category("Textures", R.drawable.ic_cat_texture, listOf("splatter", "foliage")),
    )

    /**
     * Strokes saved before the brush engine existed are replayed with this, and older
     * saves fill in their missing settings from it - so it must never change.
     */
    val classic = Brush("studio_pen", "Studio Pen", 0.05f, 0.5f, 0f, 0f, 0f, 0f, 1f, 0.75f, 0f, 0f, 0f)

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
