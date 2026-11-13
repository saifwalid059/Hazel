package com.hazel.android.download.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hazel.android.util.MediaOpener

/**
 * Hazel (Saif): every picture of a post, one page each.
 *
 * Opened on a saved post (its files in Pictures/Hazel) and on a post's card before it is
 * saved (the addresses gallery-dl read). A video in the post shows as a play button that
 * hands it to the device's player, since this screen only shows pictures.
 */
class PostViewerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, items: List<String>, title: String = "") {
            if (items.isEmpty()) return
            val intent = Intent(context, PostViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_ITEMS, ArrayList(items))
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun intent(context: Context, items: List<String>, title: String = ""): Intent =
            Intent(context, PostViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_ITEMS, ArrayList(items))
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val items = intent.getStringArrayListExtra(EXTRA_ITEMS).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (items.isEmpty()) {
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PostViewer(items, title, onClose = { finish() })
            }
        }
    }

    private fun isVideo(item: String): Boolean {
        val recorded = if (item.startsWith("content:") || item.startsWith("file:")) {
            runCatching { contentResolver.getType(Uri.parse(item)) }.getOrNull()
        } else null
        if (recorded != null) return recorded.startsWith("video/")
        val ext = item.substringBefore('?').substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mov", "webm", "m4v", "mkv")
    }

    /** A file on the device, as opposed to an address read from the site. */
    private fun isLocal(item: String) = item.startsWith("content:") || item.startsWith("file:")

    @Composable
    private fun PostViewer(items: List<String>, title: String, onClose: () -> Unit) {
        val pager = rememberPagerState { items.size }
        var chromeShown by remember { mutableStateOf(true) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { chromeShown = !chromeShown },
                    contentAlignment = Alignment.Center
                ) {
                    if (isVideo(item)) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.9f))
                                .clickable { MediaOpener.playFile(this@PostViewerActivity, item, isVideo = true) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(40.dp))
                        }
                    } else {
                        var loading by remember(item) { mutableStateOf(true) }
                        AsyncImage(
                            model = item,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            onSuccess = { loading = false },
                            onError = { loading = false },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (loading) CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            if (chromeShown) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Text(
                        text = title.ifBlank { "Post" },
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${pager.currentPage + 1} / ${items.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                val current = items[pager.currentPage]
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .navigationBarsPadding()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(onClick = { share(current) }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White)
                    }
                    IconButton(onClick = { openElsewhere(current) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in another app", tint = Color.White)
                    }
                }
            }
        }
    }

    private fun share(item: String) {
        if (isLocal(item)) {
            MediaOpener.share(this, item, isVideo(item))
        } else {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item)
            runCatching { startActivity(Intent.createChooser(send, null)) }
        }
    }

    /** The file in the gallery or player; an address not yet saved, in the browser. */
    private fun openElsewhere(item: String) {
        if (isLocal(item)) {
            MediaOpener.openWith(this, item, isVideo(item))
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item))) }
                .onFailure { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
        }
    }
}
