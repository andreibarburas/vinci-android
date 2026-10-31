package com.brbrs.vinci.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.brbrs.vinci.ui.theme.*

/** Companies tab, list layout. Styled to match ContactListCard. */
@Composable
fun CompanyListCard(
    name: String,
    subtitle: String,
    hasFeed: Boolean,
    isDark: Boolean,
    logoUrl: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .fillMaxWidth()
            .then(
                if (isDark)
                    Modifier
                        .clip(ExpressiveCardShape)
                        .background(Color.White.copy(alpha = 0.065f))
                        .border(1.dp, Color.White.copy(alpha = 0.11f), ExpressiveCardShape)
                else
                    Modifier
                        .shadow(2.dp, ExpressiveCardShape,
                            ambientColor = Color(0x289C27B0), spotColor = Color(0x149C27B0))
                        .clip(ExpressiveCardShape)
                        .background(LightSurface)
                        .border(1.dp, LightBorderMed, ExpressiveCardShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompanyLogo(name = name, logoUrl = logoUrl, size = 46, shape = ExpressiveAvatarShape)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hasFeed) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.RssFeed, "Has a news feed",
                    tint = CyanPrimary.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Companies tab, grid layout (3 per row). Styled to match ContactGridCell. */
@Composable
fun CompanyGridCell(
    name: String,
    subtitle: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .then(
                if (isDark)
                    Modifier
                        .clip(ExpressiveCardShape)
                        .background(Color.White.copy(alpha = 0.065f))
                        .border(1.dp, Color.White.copy(alpha = 0.11f), ExpressiveCardShape)
                else
                    Modifier
                        .shadow(2.dp, ExpressiveCardShape,
                            ambientColor = Color(0x229C27B0), spotColor = Color(0x0E9C27B0))
                        .clip(ExpressiveCardShape)
                        .background(LightSurface)
                        .border(1.dp, LightBorderMed, ExpressiveCardShape)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
    ) {
        CompanyLogo(name = name, logoUrl = logoUrl, size = 56, shape = ExpressiveAvatarShape)
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * A company's logo: the icon of its website, shown on a white tile (icons are designed for a
 * light background, and small ones scale up more gracefully with some padding around them).
 * Until the icon has loaded -- or if the company has no website, or the site has no usable
 * icon -- it falls back to the company's initials, exactly like a contact without a photo.
 */
@Composable
fun CompanyLogo(name: String, logoUrl: String?, size: Int, shape: Shape) {
    if (logoUrl.isNullOrBlank()) {
        ContactAvatar(photoUri = "", displayName = name, size = size, shape = shape)
        return
    }
    SubcomposeAsyncImage(
        model = logoUrl,
        contentDescription = name,
        modifier = Modifier.size(size.dp).clip(shape),
    ) {
        if (painter.state is AsyncImagePainter.State.Success) {
            // The white tile and padding go on the image itself: wrapping it in a Box would make
            // BoxScope the implicit receiver and hide the SubcomposeAsyncImageScope it needs.
            SubcomposeAsyncImageContent(
                modifier = Modifier.fillMaxSize().background(Color.White).padding((size / 8).dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            ContactAvatar(photoUri = "", displayName = name, size = size, shape = shape)
        }
    }
}
