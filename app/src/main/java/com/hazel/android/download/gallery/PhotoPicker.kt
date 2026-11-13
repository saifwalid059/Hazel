package com.hazel.android.download.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Hazel (Saif): the files of a post, three to a row, each one tapped in or out of the
 * download. [chosen] holds 1-based positions, as gallery-dl numbers them.
 */
@Composable
fun PhotoPicker(
    items: List<String>,
    chosen: Set<Int>,
    onChange: (Set<Int>) -> Unit
) {
    val all = (1..items.size).toSet()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${chosen.size} of ${items.size} chosen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onChange(if (chosen.size == items.size) emptySet() else all) }) {
                Text(if (chosen.size == items.size) "Clear" else "Select all")
            }
        }
        items.chunked(3).forEachIndexed { row, slice ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                slice.forEachIndexed { col, item ->
                    val position = row * 3 + col + 1
                    Tile(
                        item = item,
                        number = position,
                        picked = position in chosen,
                        onClick = {
                            onChange(if (position in chosen) chosen - position else chosen + position)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Keeps the last row's tiles the size of the others.
                repeat(3 - slice.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(item: String, number: Int, picked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (picked) Modifier.border(3.dp, accent, shape) else Modifier)
            .clickable(role = Role.Checkbox, onClick = onClick)
    ) {
        val ext = item.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (ext in setOf("mp4", "mov", "webm", "m4v")) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video $number",
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AsyncImage(
                model = item,
                contentDescription = "Photo $number",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = if (picked) 1f else 0.45f
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (picked) accent else Color.Black.copy(alpha = 0.45f))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (picked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text("$number", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
