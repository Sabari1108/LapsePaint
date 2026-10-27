package com.lapsepaint.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** The home screen: a grid of saved artworks, plus a button to start a new canvas. */
class GalleryActivity : Activity() {

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private var items: List<ArtworkInfo> = emptyList()
    private val thumbs = HashMap<String, Bitmap?>()
    private val adapter = ArtworkAdapter()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(8))
            addView(TextView(context).apply {
                text = "Gallery"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "+  New canvas"
                textSize = 17f
                setTextColor(0xFF4FA3F7.toInt())
                setPadding(dp(16), dp(10), dp(4), dp(10))
                setOnClickListener { open(ArtworkStore.newId()) }
            })
        }

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(300)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(24)
            verticalSpacing = dp(28)
            setPadding(dp(32), dp(16), dp(32), dp(32))
            clipToPadding = false
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            adapter = this@GalleryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> open(items[pos].id) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showOptions(items[pos])
                true
            }
        }

        empty = TextView(this).apply {
            text = "No artworks yet.\nTap  + New canvas  to start drawing."
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFF9A9A9A.toInt())
        }

        val body = FrameLayout(this).apply {
            addView(grid)
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            addView(header)
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        enterFullscreen()
        UsageStats.askOnce(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun refresh() {
        items = ArtworkStore.list(this)
        thumbs.clear()
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(id: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ARTWORK_ID, id))
    }

    private fun showOptions(info: ArtworkInfo) {
        AlertDialog.Builder(this)
            .setTitle(info.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> rename(info)
                    1 -> confirmDelete(info)
                }
            }
            .show()
    }

    private fun rename(info: ArtworkInfo) {
        val input = EditText(this).apply {
            setText(info.name)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename artwork")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                ArtworkStore.rename(this, info.id, input.text.toString().trim())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(info: ArtworkInfo) {
        AlertDialog.Builder(this)
            .setMessage("Delete \"${info.name}\"? This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                ArtworkStore.delete(this, info.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class ArtworkAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = convertView as? LinearLayout ?: newCard()
            val info = items[position]
            val image = card.getChildAt(0) as ImageView
            val name = card.getChildAt(1) as TextView
            val date = card.getChildAt(2) as TextView
            val bmp = thumbs.getOrPut(info.id) {
                if (info.thumb.exists()) BitmapFactory.decodeFile(info.thumb.path) else null
            }
            image.setImageBitmap(bmp)
            name.text = info.name
            date.text = dateFormat.format(Date(info.modified))
            return card
        }

        private fun newCard() = LinearLayout(this@GalleryActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.WHITE)
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
            addView(TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                setPadding(dp(2), dp(10), 0, 0)
            })
            addView(TextView(context).apply {
                setTextColor(0xFF9A9A9A.toInt())
                textSize = 12f
                setPadding(dp(2), dp(2), 0, 0)
            })
        }
    }
}
