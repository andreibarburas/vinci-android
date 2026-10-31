package com.brbrs.vinci.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.vinci.data.ContactEntity
import com.brbrs.vinci.ui.components.ContactAvatar
import com.brbrs.vinci.ui.theme.*
import com.brbrs.vinci.ui.viewmodels.ShareReceiverViewModel

@Composable
fun ShareReceiverScreen(
    sharedText: String?,
    sharedImageUri: Uri?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ShareReceiverViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = LocalIsDark.current
    val isOled = LocalIsOled.current

    LaunchedEffect(uiState.done) { if (uiState.done) onDone() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isOled) Brush.verticalGradient(listOf(Color.Black, Color.Black))
                else if (isDark) Brush.verticalGradient(0f to NavyDeep, 0.4f to NavyMid, 1f to NavyDeep)
                else Brush.verticalGradient(0f to LightBg, 0.4f to Color(0xFFEDE6F8), 1f to LightSurface2)
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Save to Vinci", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (sharedImageUri != null) "Pick who this image is about" else "Pick who this note is about",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, "Cancel", tint = MaterialTheme.colorScheme.onBackground)
                }
            }

            if (!sharedText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDark) Color.White.copy(alpha = 0.06f) else LightSurface)
                        .padding(12.dp),
                ) {
                    Text(sharedText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isDark) Color.White.copy(alpha = 0.07f) else LightSurface)
                    .border(1.dp, if (isDark) Color.White.copy(alpha = 0.12f) else LightBorderMed, RoundedCornerShape(22.dp)),
            ) {
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::onSearchChanged,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (uiState.searchQuery.isEmpty()) {
                                Text("Search contacts...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                            inner()
                        },
                    )
                }
            }

            if (uiState.isSaving) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.contacts, key = { it.id }) { contact ->
                        ContactRow(contact = contact, onClick = { viewModel.saveToContact(contact, sharedText, sharedImageUri) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(photoUri = contact.photoUri, displayName = contact.displayName, size = 40, shape = CircleShape)
        Spacer(Modifier.width(12.dp))
        Text(contact.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
