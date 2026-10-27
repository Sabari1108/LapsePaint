package com.lapsepaint.app

import java.io.OutputStream

/**
 * A small animated-GIF encoder. Each frame gets its own 256-colour palette
 * (median cut over a sample of its pixels) and is LZW-compressed. The GIF
 * loops forever. Frames must be opaque, w * h ARGB.
 */
class GifWriter(private val out: OutputStream, private val w: Int, private val h: Int) {

    init {
        out.write("GIF89a".toByteArray())
        short(w)
        short(h)
        out.write(0) // no global colour table
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        // NETSCAPE2.0 application extension: loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }

    private fun short(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    /** [delayCs] is how long the frame shows, in hundredths of a second. */
    fun addFrame(argb: IntArray, delayCs: Int) {
        val palette = medianCut(argb)
        val indices = mapToPalette(argb, palette)

        // Graphic control extension: delay, no transparency.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
        short(delayCs.coerceIn(2, 65535))
        out.write(byteArrayOf(0x00, 0x00))

        // Image descriptor with a 256-entry local colour table.
        out.write(0x2C)
        short(0); short(0); short(w); short(h)
        out.write(0x87)
        for (i in 0 until 256) {
            val c = palette.getOrElse(i) { 0 }
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        lzw(indices)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    // ---- Colour reduction ----

    private fun medianCut(argb: IntArray): IntArray {
        // A sample is plenty to choose the palette from.
        val step = maxOf(1, argb.size / 60_000)
        val sample = IntArray((argb.size + step - 1) / step) { argb[it * step] and 0xFFFFFF }

        class Box(val from: Int, val to: Int)
        val boxes = ArrayList<Box>().apply { add(Box(0, sample.size)) }

        fun range(b: Box, shift: Int): Int {
            var lo = 255
            var hi = 0
            for (i in b.from until b.to) {
                val v = (sample[i] shr shift) and 0xFF
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return hi - lo
        }

        while (boxes.size < 256) {
            // Split the box with the widest spread of colour.
            var best = -1
            var bestRange = 0
            var bestShift = 0
            for ((i, b) in boxes.withIndex()) {
                if (b.to - b.from < 2) continue
                for (shift in intArrayOf(16, 8, 0)) {
                    val r = range(b, shift)
                    if (r > bestRange) { bestRange = r; best = i; bestShift = shift }
                }
            }
            if (best < 0) break
            val b = boxes.removeAt(best)
            val keys = LongArray(b.to - b.from) { k ->
                val c = sample[b.from + k]
                (((c shr bestShift) and 0xFF).toLong() shl 32) or c.toLong()
            }
            keys.sort()
            for (k in keys.indices) sample[b.from + k] = (keys[k] and 0xFFFFFF).toInt()
            val mid = b.from + (b.to - b.from) / 2
            boxes.add(Box(b.from, mid))
            boxes.add(Box(mid, b.to))
        }

        return IntArray(boxes.size) { i ->
            val b = boxes[i]
            var r = 0L; var g = 0L; var bl = 0L
            for (k in b.from until b.to) {
                val c = sample[k]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF
            }
            val n = maxOf(1, b.to - b.from)
            ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
        }
    }

    /** Nearest palette entry per pixel, cached on a 15-bit colour key. */
    private fun mapToPalette(argb: IntArray, palette: IntArray): ByteArray {
        val cache = IntArray(32768) { -1 }
        val out = ByteArray(argb.size)
        for (i in argb.indices) {
            val c = argb[i]
            val key = ((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)
            var idx = cache[key]
            if (idx < 0) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                var bestD = Int.MAX_VALUE
                idx = 0
                for (p in palette.indices) {
                    val q = palette[p]
                    val dr = r - ((q shr 16) and 0xFF)
                    val dg = g - ((q shr 8) and 0xFF)
                    val db = b - (q and 0xFF)
                    val d = dr * dr * 2 + dg * dg * 4 + db * db * 3
                    if (d < bestD) { bestD = d; idx = p }
                }
                cache[key] = idx
            }
            out[i] = idx.toByte()
        }
        return out
    }

    // ---- LZW compression, written as GIF data sub-blocks ----

    private fun lzw(indices: ByteArray) {
        val minCodeSize = 8
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1

        val block = ByteArray(255)
        var blockLen = 0
        var bitBuf = 0
        var bitCount = 0
        fun flushBlock() {
            if (blockLen == 0) return
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
        var codeSize = minCodeSize + 1
        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                block[blockLen++] = (bitBuf and 0xFF).toByte()
                if (blockLen == 255) flushBlock()
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        val table = HashMap<Int, Int>(8192)
        var next = eoi + 1
        emit(clear)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) {
                prefix = found
                continue
            }
            emit(prefix)
            if (next < 4096) {
                table[key] = next++
                // The decoder widens one code later than us, hence the strict check.
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear)
                table.clear()
                next = eoi + 1
                codeSize = minCodeSize + 1
            }
            prefix = k
        }
        emit(prefix)
        emit(eoi)
        if (bitCount > 0) {
            block[blockLen++] = (bitBuf and 0xFF).toByte()
            if (blockLen == 255) flushBlock()
        }
        flushBlock()
        out.write(0) // block terminator
    }
}
