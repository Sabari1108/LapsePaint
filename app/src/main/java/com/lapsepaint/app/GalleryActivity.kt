package com.lapsepaint.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.text.DateFormat
import java.util.Date

/** The home screen: a grid of saved artworks, plus a button to start a new canvas. */
class GalleryActivity : ComponentActivity() {

    private var items by mutableStateOf<List<ArtworkInfo>>(emptyList())
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LapseTheme { Gallery() } }
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
    }

    private fun open(id: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ARTWORK_ID, id))
    }

    @Composable
    private fun Gallery() {
        val scroll = MiuixScrollBehavior()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "Gallery",
                    scrollBehavior = scroll,
                    actions = {
                        TextButton(text = "+  New canvas", onClick = { open(ArtworkStore.newId()) })
                    },
                )
            },
        ) { padding ->
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        "No artworks yet.\nTap  + New canvas  to start drawing.",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(300.dp),
                    modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp, bottom = 32.dp, top = padding.calculateTopPadding() + 8.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Keyed by id, so a tap always opens the artwork the card shows.
                    items(items, key = { it.id }) { info -> ArtworkCard(info) }
                }
            }
        }
    }

    @Composable
    private fun ArtworkCard(info: ArtworkInfo) {
        val thumb = remember(info.id, info.modified) {
            if (info.thumb.exists()) BitmapFactory.decodeFile(info.thumb.path)?.asImageBitmap() else null
        }
        Card(
            pressFeedbackType = PressFeedbackType.Sink,
            insideMargin = PaddingValues(0.dp),
            onClick = { open(info.id) },
            onLongPress = { showOptions(info) },
        ) {
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color.White)) {
                if (thumb != null) {
                    Image(thumb, contentDescription = info.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                info.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            Text(
                dateFormat.format(Date(info.modified)),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 14.dp),
            )
        }
    }

    private fun showOptions(info: ArtworkInfo) = miuixMenu(info.name, listOf(
        "Rename" to { rename(info) },
        "Delete" to { confirmDelete(info) },
    ))

    private fun rename(info: ArtworkInfo) = miuixInput("Rename artwork", info.name) { name ->
        ArtworkStore.rename(this, info.id, name)
        refresh()
    }

    private fun confirmDelete(info: ArtworkInfo) =
        miuixConfirm("Delete artwork", "Delete \"${info.name}\"? This can't be undone.", "Delete") {
            ArtworkStore.delete(this, info.id)
            refresh()
        }
}
