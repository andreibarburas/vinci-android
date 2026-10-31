package com.brbrs.vinci.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CallMerge
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
import com.brbrs.vinci.ui.viewmodels.DuplicateGroup
import com.brbrs.vinci.ui.viewmodels.DuplicateMergeViewModel

@Composable
fun DuplicateMergeScreen(
    onBack: () -> Unit,
    viewModel: DuplicateMergeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = LocalIsDark.current
    val isOled = LocalIsOled.current

    LaunchedEffect(uiState.lastMergeMessage) {
        if (uiState.lastMergeMessage != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.dismissMessage()
        }
    }

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
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text("Duplicate contacts", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            }

            Text(
                "Contacts with the same name but a different Nextcloud identity -- usually from a contact " +
                    "being recreated on the server. Merging moves one's Vinci history onto the other.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            uiState.lastMergeMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GreenOk.copy(alpha = 0.15f))
                        .padding(12.dp),
                ) {
                    Text(msg, color = GreenOk, style = MaterialTheme.typography.bodyMedium)
                }
            }

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                uiState.groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No probable duplicates found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(uiState.groups, key = { it.name }) { group ->
                        DuplicateGroupCard(group = group, isDark = isDark, onMerge = { keep, merge -> viewModel.merge(keep, merge) })
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    isDark: Boolean,
    onMerge: (keep: ContactEntity, merge: ContactEntity) -> Unit,
) {
    // Default selection: most-recently-contacted row is "keep" -- list is already sorted that way.
    var keepId by remember(group.name) { mutableStateOf(group.contacts.first().id) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.055f) else LightSurface)
            .border(1.dp, if (isDark) Color.White.copy(alpha = 0.09f) else LightBorderMed, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(group.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        group.contacts.forEach { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { keepId = contact.id }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = contact.id == keepId, onClick = { keepId = contact.id })
                ContactAvatar(photoUri = contact.photoUri, displayName = contact.displayName, size = 32, shape = CircleShape)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Keep this one" + if (contact.id == keepId) " ✓" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (contact.id == keepId) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "uid ${contact.cardavUid.take(8)}... · last contact: ${if (contact.lastCallTimestamp > 0) com.brbrs.vinci.ui.components.relativeTime(contact.lastCallTimestamp) else "never"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val keep = group.contacts.first { it.id == keepId }
                group.contacts.filter { it.id != keepId }.forEach { onMerge(keep, it) }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Outlined.CallMerge, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Merge into the selected contact")
        }
    }
}
