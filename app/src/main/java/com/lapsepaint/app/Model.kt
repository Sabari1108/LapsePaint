package com.lapsepaint.app

import android.graphics.Bitmap
import android.graphics.RectF
import java.io.DataInputStream
import java.io.DataOutputStream

/** One sampled pen position, in canvas pixels. Pressure and tilt are 0..1 (tilt 1 = pen lying flat). */
data class StrokePoint(val x: Float, val y: Float, val pressure: Float, val tilt: Float = 0f)

/** A photo placed on a layer. [file] is its name in the artwork's images folder. */
class PlacedImage(val file: String, val bitmap: Bitmap?, val rect: RectF)

/**
 * A single brush stroke on one layer. [seed] makes the brush's randomness repeatable.
 * An inserted photo ([image]) or a layer adjustment ([adjustment]) is also kept as a
 * one-point "stroke", so it takes part in undo, saving and the time-lapse like
 * everything else.
 */
class Stroke(
    val layerId: Int,
    val color: Int,
    val size: Float,
    val opacity: Float,
    val isEraser: Boolean,
    val brush: Brush,
    val seed: Int,
) {
    val points = ArrayList<StrokePoint>()
    var image: PlacedImage? = null
    var adjustment: Adjustment? = null

    /** Undone and redone together with the entry before it (Cut & Paste is two entries). */
    var linkedToPrevious = false

    /** Smudge: drags the paint already on the layer instead of adding colour ([opacity] is the strength). */
    var isSmudge = false
}

/** Animation Assist: every layer is one frame, played bottom to top. */
class AnimationSettings {
    var enabled = false
    var fps = 8
    var onionFrames = 2
    var onionOpacity = 0.4f
    var pingPong = false

    /** The bottom layer is a background shown under every frame instead of being a frame. */
    var background = false

    val firstFrame get() = if (background) 1 else 0

    /**
     * The layer index to show at each tick of playback: each frame repeated for its
     * hold, then (for ping-pong) back down again without repeating the two ends.
     */
    fun playOrder(layers: List<Layer>): List<Int> {
        val frames = (firstFrame until layers.size).toList()
        if (frames.isEmpty()) return if (layers.isEmpty()) emptyList() else listOf(0)
        val path = if (pingPong && frames.size > 2) frames + frames.subList(1, frames.size - 1).reversed() else frames
        return path.flatMap { i -> List(1 + layers[i].hold) { i } }
    }

    fun copy() = AnimationSettings().also {
        it.enabled = enabled
        it.fps = fps
        it.onionFrames = onionFrames
        it.onionOpacity = onionOpacity
        it.pingPong = pingPong
        it.background = background
    }
}

class Layer(val id: Int, var name: String) {
    var visible = true
    var opacity = 1f

    /** Animation: extra ticks this frame stays on screen (0 = shown once). */
    var hold = 0

    fun copy() = Layer(id, name).also {
        it.visible = visible
        it.opacity = opacity
        it.hold = hold
    }
}

/**
 * One artwork in the Gallery. [history] holds every stroke in the order it
 * was drawn: replaying it rebuilds the layers, and it is also the time-lapse.
 */
class Artwork(val id: String) {
    var name = "Untitled Artwork"
    var width = 0
    var height = 0
    val layers = ArrayList<Layer>() // bottom -> top
    val history = ArrayList<Stroke>()
    val animation = AnimationSettings()
    var activeLayerId = 0
    private var nextLayerId = 1

    init {
        addLayer()
    }

    val activeLayer get() = layer(activeLayerId) ?: layers.last()

    /**
     * A cheap fingerprint of everything that gets saved. Comparing it before and after
     * a visit tells whether the artwork changed, so opening one doesn't re-save it
     * (re-saving moves it to the top of the Gallery).
     */
    fun fingerprint(): Int {
        var h = name.hashCode()
        h = h * 31 + activeLayerId
        for (l in layers) h = ((h * 31 + l.id) * 31 + l.name.hashCode()) * 31 + (l.opacity * 1000).toInt() +
            (if (l.visible) 1 else 0) * 7 + l.hold * 13
        h = h * 31 + history.size
        for (s in history.takeLast(4)) h = h * 31 + System.identityHashCode(s)
        val a = animation
        h = h * 31 + listOf(a.enabled, a.fps, a.onionFrames, a.onionOpacity, a.pingPong, a.background).hashCode()
        return h
    }

    fun layer(id: Int) = layers.firstOrNull { it.id == id }

    /** Adds a layer directly above the active one and selects it. */
    fun addLayer(): Layer {
        val layer = Layer(nextLayerId, "Layer $nextLayerId")
        nextLayerId++
        val active = layers.indexOfFirst { it.id == activeLayerId }
        layers.add(if (active >= 0) active + 1 else layers.size, layer)
        activeLayerId = layer.id
        return layer
    }

    fun write(out: DataOutputStream) {
        out.writeInt(FORMAT_VERSION)
        out.writeInt(width)
        out.writeInt(height)
        out.writeInt(activeLayerId)
        out.writeInt(nextLayerId)
        out.writeInt(layers.size)
        for (l in layers) {
            out.writeInt(l.id)
            out.writeUTF(l.name)
            out.writeBoolean(l.visible)
            out.writeFloat(l.opacity)
            out.writeInt(l.hold)
        }
        out.writeInt(history.size)
        for (s in history) {
            out.writeInt(s.layerId)
            out.writeInt(s.color)
            out.writeFloat(s.size)
            out.writeFloat(s.opacity)
            out.writeBoolean(s.isEraser)
            out.writeInt(s.seed)
            out.writeInt(BrushSetting.all.size)
            for (setting in BrushSetting.all) out.writeFloat(setting.get(s.brush))
            out.writeInt(s.points.size)
            for (p in s.points) {
                out.writeFloat(p.x)
                out.writeFloat(p.y)
                out.writeFloat(p.pressure)
                out.writeFloat(p.tilt)
            }
            val img = s.image
            out.writeBoolean(img != null)
            if (img != null) {
                out.writeUTF(img.file)
                out.writeFloat(img.rect.left)
                out.writeFloat(img.rect.top)
                out.writeFloat(img.rect.right)
                out.writeFloat(img.rect.bottom)
            }
            val adj = s.adjustment
            out.writeBoolean(adj != null)
            if (adj != null) {
                out.writeInt(adj.type)
                out.writeInt(adj.v.size)
                for (f in adj.v) out.writeFloat(f)
            }
            out.writeBoolean(s.linkedToPrevious)
            out.writeBoolean(s.isSmudge)
        }
        out.writeBoolean(animation.enabled)
        out.writeInt(animation.fps)
        out.writeInt(animation.onionFrames)
        out.writeFloat(animation.onionOpacity)
        out.writeBoolean(animation.pingPong)
        out.writeBoolean(animation.background)
    }

    companion object {
        // 1: line strokes. 2: adds brush settings, seed and tilt per stroke.
        // 3: adds inserted photos. 4: adds layer adjustments. 5: adjustments hold any number of values.
        // 6: adds linked entries. 7: adds animation settings at the end.
        // 8: adds frame hold per layer and the background frame flag.
        // 9: adds smudge strokes. 10: brush settings are counted, so new ones can be added.
        private const val FORMAT_VERSION = 10

        /** Brush settings stored per stroke before format 10. */
        private const val OLD_BRUSH_SETTINGS = 11

        fun read(id: String, input: DataInputStream, loadImage: (String) -> Bitmap?): Artwork {
            val version = input.readInt()
            check(version in 1..FORMAT_VERSION) { "Unknown artwork format" }
            val art = Artwork(id)
            art.layers.clear()
            art.width = input.readInt()
            art.height = input.readInt()
            art.activeLayerId = input.readInt()
            art.nextLayerId = input.readInt()
            repeat(input.readInt()) {
                val layer = Layer(input.readInt(), input.readUTF())
                layer.visible = input.readBoolean()
                layer.opacity = input.readFloat()
                if (version >= 8) layer.hold = input.readInt()
                art.layers.add(layer)
            }
            repeat(input.readInt()) {
                val layerId = input.readInt()
                val color = input.readInt()
                val size = input.readFloat()
                val opacity = input.readFloat()
                val isEraser = input.readBoolean()
                var seed = 0
                var brush = BrushLibrary.classic
                if (version >= 2) {
                    seed = input.readInt()
                    val count = if (version >= 10) input.readInt() else OLD_BRUSH_SETTINGS
                    for (i in 0 until count) {
                        val v = input.readFloat()
                        BrushSetting.all.getOrNull(i)?.let { brush = it.set(brush, v) }
                    }
                }
                val stroke = Stroke(layerId, color, size, opacity, isEraser, brush, seed)
                repeat(input.readInt()) {
                    val x = input.readFloat()
                    val y = input.readFloat()
                    val pressure = input.readFloat()
                    val tilt = if (version >= 2) input.readFloat() else 0f
                    stroke.points.add(StrokePoint(x, y, pressure, tilt))
                }
                if (version >= 3 && input.readBoolean()) {
                    val file = input.readUTF()
                    val rect = RectF(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                    stroke.image = PlacedImage(file, loadImage(file), rect)
                }
                if (version >= 4 && input.readBoolean()) {
                    val type = input.readInt()
                    val count = if (version >= 5) input.readInt() else 4
                    stroke.adjustment = Adjustment(type, FloatArray(count) { input.readFloat() })
                }
                if (version >= 6) stroke.linkedToPrevious = input.readBoolean()
                if (version >= 9) stroke.isSmudge = input.readBoolean()
                art.history.add(stroke)
            }
            if (version >= 7) {
                art.animation.enabled = input.readBoolean()
                art.animation.fps = input.readInt()
                art.animation.onionFrames = input.readInt()
                art.animation.onionOpacity = input.readFloat()
                art.animation.pingPong = input.readBoolean()
                if (version >= 8) art.animation.background = input.readBoolean()
            }
            return art
        }
    }
}
