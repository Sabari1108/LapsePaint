package com.lapsepaint.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min

/** Video size for a canvas: at most 1920 on the long side, both sides multiples of 16 (encoders like that). */
fun videoSize(canvasWidth: Int, canvasHeight: Int): Pair<Int, Int> {
    val scale = min(1f, 1920f / max(canvasWidth, canvasHeight))
    return ((canvasWidth * scale).toInt() / 16) * 16 to ((canvasHeight * scale).toInt() / 16) * 16
}

/**
 * Writes an H.264 MP4 to Movies/LapsePaint. [render] draws frame number f onto
 * a w x h canvas; the file only appears in the Gallery once it is complete.
 */
fun writeVideo(
    context: Context,
    namePrefix: String,
    w: Int,
    h: Int,
    fps: Int,
    frames: Int,
    render: (frame: Int, canvas: Canvas) -> Unit,
    onProgress: (Float) -> Unit,
): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "${namePrefix}_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LapsePaint")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not create video file")

    try {
        resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            val encoder = VideoEncoder(MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), w, h, fps)
            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val pixels = IntArray(w * h)
            for (f in 0 until frames) {
                render(f, canvas)
                frame.getPixels(pixels, 0, w, 0, 0, w, h)
                encoder.encodeFrame(pixels, f.toLong() * 1_000_000L / fps)
                onProgress((f + 1).toFloat() / frames)
            }
            encoder.finish()
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

/**
 * Replays the recorded strokes onto an offscreen canvas, frame by frame,
 * and encodes the frames into an H.264 MP4 saved to Movies/LapsePaint.
 *
 * Like Procreate, the video only shows the canvas (no UI), and its length is
 * fixed no matter how long the drawing took: the strokes are spread evenly
 * across the frames, then the finished piece is held for a moment.
 */
class TimelapseExporter(private val context: Context) {

    private val fps = 30
    private val holdSeconds = 2

    /** [layers] and [strokes] should be copies: export runs on a background thread. */
    fun export(
        layers: List<Layer>,
        strokes: List<Stroke>,
        canvasWidth: Int,
        canvasHeight: Int,
        durationSeconds: Int,
        onProgress: (Float) -> Unit,
    ): Uri {
        val (w, h) = videoSize(canvasWidth, canvasHeight)
        val totalPoints = strokes.sumOf { it.points.size }
        val contentFrames = max(1, min(fps * durationSeconds, totalPoints))
        val totalFrames = contentFrames + fps * holdSeconds

        val surface = PaintSurface(w, h, w.toFloat() / canvasWidth)
        var strokeIdx = 0
        var pointIdx = 0
        var drawn = 0

        return writeVideo(context, "timelapse", w, h, fps, totalFrames, { f, canvas ->
            // Advance the replay to this frame's share of the points.
            val target = if (f >= contentFrames) totalPoints
            else ((f + 1).toLong() * totalPoints / contentFrames).toInt()
            while (drawn < target && strokeIdx < strokes.size) {
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
            surface.render(canvas, layers)
        }, onProgress)
    }
}

class VideoEncoder(private val muxer: MediaMuxer, private val w: Int, private val h: Int, fps: Int) {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, max(1, fps))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encodeFrame(argb: IntArray, ptsUs: Long) {
        val index = dequeueInput()
        fillYuv(codec.getInputImage(index)!!, argb)
        codec.queueInputBuffer(index, 0, w * h * 3 / 2, ptsUs, 0)
        drain(endOfStream = false)
    }

    fun finish() {
        val index = dequeueInput()
        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(endOfStream = true)
        codec.stop()
        codec.release()
        if (muxing) muxer.stop()
        muxer.release()
    }

    private fun dequeueInput(): Int {
        var index = codec.dequeueInputBuffer(10_000)
        while (index < 0) {
            drain(endOfStream = false)
            index = codec.dequeueInputBuffer(10_000)
        }
        return index
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 10_000)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                out >= 0 -> {
                    val buf = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** ARGB -> YUV 4:2:0, respecting whatever plane layout the encoder hands us. */
    private fun fillYuv(image: Image, argb: IntArray) {
        val (yP, uP, vP) = image.planes
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        for (j in 0 until h) {
            val row = j * w
            val yRow = j * yP.rowStride
            val evenRow = j and 1 == 0
            for (i in 0 until w) {
                val c = argb[row + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                yBuf.put(yRow + i * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (evenRow && i and 1 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuf.put((j / 2) * uP.rowStride + (i / 2) * uP.pixelStride, u.toByte())
                    vBuf.put((j / 2) * vP.rowStride + (i / 2) * vP.pixelStride, v.toByte())
                }
            }
        }
    }
}
