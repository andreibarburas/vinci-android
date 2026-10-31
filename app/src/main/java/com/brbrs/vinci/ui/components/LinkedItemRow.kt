package com.brbrs.vinci.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brbrs.vinci.R
import com.brbrs.vinci.network.LinkedItem
import com.brbrs.vinci.network.LinkedItemSource
import com.brbrs.vinci.ui.theme.LocalIsDark
import com.brbrs.vinci.ui.theme.vinciChip
import com.brbrs.vinci.util.LinkedAppLauncher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Each suite app's brand accent, used for its letter badge in "Linked items" rows. */
val TavlaBrand = Color(0xFF3BB8C8) // matches Tavla's LagoonPrimary
val KotoBrand  = Color(0xFF6FA8C0) // matches Koto's SteelPrimary
val NotaBrand  = Color(0xFF4CAF78) // matches Nóta's suite green

fun brandColorFor(source: LinkedItemSource): Color = when (source) {
    LinkedItemSource.TAVLA -> TavlaBrand
    LinkedItemSource.KOTO  -> KotoBrand
    LinkedItemSource.NOTA  -> NotaBrand
}

fun shortLabelFor(source: LinkedItemSource): String = when (source) {
    LinkedItemSource.TAVLA -> "Tavla"
    LinkedItemSource.KOTO  -> "Koto"
    LinkedItemSource.NOTA  -> "Nóta"
}

/** The app's own handwritten-logo icon (its launcher icon crop), as supplied for this suite. */
private fun iconResFor(source: LinkedItemSource): Int = when (source) {
    LinkedItemSource.TAVLA -> R.drawable.app_icon_tavla
    LinkedItemSource.KOTO  -> R.drawable.app_icon_koto
    LinkedItemSource.NOTA  -> R.drawable.app_icon_nota
}

/** Badge showing each app's own handwritten-logo icon (N / K / T), with a thin ring in that
 * app's brand color, so a linked item reads at a glance as "from Nóta/Koto/Tavla" the same way
 * the launcher icons do. */
@Composable
fun AppLetterBadge(source: LinkedItemSource, size: androidx.compose.ui.unit.Dp = 28.dp) {
    Image(
        painter = painterResource(id = iconResFor(source)),
        contentDescription = shortLabelFor(source),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
    )
}

/**
 * One row in a contact's "Linked items" section -- a note, card or task from another
 * BarburasLab app that references this contact. Styled to match the Phone/Email InfoRow boxes
 * elsewhere on this screen. Tapping opens the item directly in its owning app.
 */
@Composable
fun LinkedItemRow(item: LinkedItem) {
    val context = LocalContext.current
    val label = shortLabelFor(item.source)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { LinkedAppLauncher.open(context, item) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AppLetterBadge(item.source)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { label },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.snippet.isNotBlank()) {
                Text(
                    item.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
                if (item.updatedAt > 0) {
                    Text(
                        "· " + SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(item.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Icon(
            Icons.Outlined.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Toggle chip for picking which of a contact's already-linked items (see "Linked items" on
 * their profile) a specific interaction is about -- used in the Log/Edit interaction forms.
 * Selected state is tinted in the item's own app color so several apps' items stay easy to
 * tell apart at a glance.
 */
@Composable
fun LinkedItemChip(item: LinkedItem, selected: Boolean, onClick: () -> Unit) {
    val color = brandColorFor(item.source)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .vinciChip(isDark = LocalIsDark.current, selected = selected, selectedColor = color)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
    ) {
        AppLetterBadge(item.source, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            item.title.ifBlank { shortLabelFor(item.source) },
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
