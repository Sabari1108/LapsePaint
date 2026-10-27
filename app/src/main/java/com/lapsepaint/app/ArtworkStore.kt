package com.lapsepaint.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class ArtworkInfo(val id: String, val name: String, val modified: Long, val thumb: File)

/**
 * Artworks live in the app's private storage, one folder each:
 * art.bin (layers + every stroke), thumb.png (Gallery preview), name.txt,
 * and images/ for inserted photos.
 */
object ArtworkStore {
    private const val DEFAULT_NAME = "Untitled Artwork"

    private fun root(ctx: Context) = File(ctx.filesDir, "artworks").apply { mkdirs() }
    private fun dir(ctx: Context, id: String) = File(root(ctx), id)
    private fun imageDir(ctx: Context, id: String) = File(dir(ctx, id), "images")

    fun newId() = System.currentTimeMillis().toString()

    fun list(ctx: Context): List<ArtworkInfo> =
        root(ctx).listFiles().orEmpty()
            .filter { File(it, "art.bin").exists() }
            .map { ArtworkInfo(it.name, readName(it), File(it, "art.bin").lastModified(), File(it, "thumb.png")) }
            .sortedByDescending { it.modified }

    private fun readName(dir: File): String {
        val f = File(dir, "name.txt")
        return (if (f.exists()) f.readText().trim() else "").ifEmpty { DEFAULT_NAME }
    }

    fun load(ctx: Context, id: String): Artwork? {
        val d = dir(ctx, id)
        val f = File(d, "art.bin")
        if (!f.exists()) return null
        return runCatching {
            val images = imageDir(ctx, id)
            val options = BitmapFactory.Options().apply { inMutable = false }
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { input ->
                Artwork.read(id, input) { name -> BitmapFactory.decodeFile(File(images, name).path, options) }
            }
        }.getOrNull()?.also { it.name = readName(d) }
    }

    fun save(ctx: Context, artwork: Artwork, thumbnail: Bitmap?) {
        val d = dir(ctx, artwork.id).apply { mkdirs() }
        // Write to a temp file first so a crash mid-save can't corrupt the artwork.
        val tmp = File(d, "art.bin.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { artwork.write(it) }
        tmp.renameTo(File(d, "art.bin"))
        File(d, "name.txt").writeText(artwork.name)
        thumbnail?.let { bmp ->
            FileOutputStream(File(d, "thumb.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Copies an inserted photo into the artwork's folder and returns its file name. */
    fun saveImage(ctx: Context, artworkId: String, bitmap: Bitmap): String {
        val d = imageDir(ctx, artworkId).apply { mkdirs() }
        val png = bitmap.hasAlpha()
        val name = UUID.randomUUID().toString() + if (png) ".png" else ".jpg"
        FileOutputStream(File(d, name)).use {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
        }
        return name
    }

    fun rename(ctx: Context, id: String, name: String) {
        File(dir(ctx, id), "name.txt").writeText(name.ifBlank { DEFAULT_NAME })
    }

    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }
}
