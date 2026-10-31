package com.brbrs.vinci.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.brbrs.vinci.network.FeedItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One company news post: featured image (when the post has one) beside title, date and teaser. */
@Composable
fun FeedPostRow(post: FeedItem) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = post.link.isNotBlank()) { openWebUrl(context, post.link) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (post.imageUrl.isNotBlank()) {
            AsyncImage(
                model = post.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(post.title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (post.publishedAt > 0) {
                Text(
                    SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(post.publishedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (post.summary.isNotBlank()) {
                Text(post.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (post.imageUrl.isNotBlank()) 3 else 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Opens a web address in the browser; anything that isn't http(s) is ignored. */
fun openWebUrl(context: Context, raw: String) {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return
    val url = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    if (!(url.startsWith("https://") || url.startsWith("http://"))) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
