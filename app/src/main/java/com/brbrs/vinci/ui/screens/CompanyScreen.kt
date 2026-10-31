package com.brbrs.vinci.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.brbrs.vinci.ui.components.CompanyLogo
import com.brbrs.vinci.ui.components.ContactAvatar
import com.brbrs.vinci.ui.components.FeedPostRow
import com.brbrs.vinci.ui.components.openWebUrl
import com.brbrs.vinci.ui.components.RecentInteractionCard
import com.brbrs.vinci.ui.components.SectionHeader
import com.brbrs.vinci.ui.theme.*
import com.brbrs.vinci.ui.viewmodels.CompanyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A company's own page: its notes and website, the people who work there, its blog/news feed,
 * and one combined timeline of every interaction with anyone at the company.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CompanyScreen(
    onBack: () -> Unit,
    onContactClick: (Long) -> Unit,
    onEditInteraction: (Long) -> Unit,
    onLogInteraction: (Long, String) -> Unit,
    viewModel: CompanyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val feed by viewModel.feed.collectAsState()
    val logoUrl by viewModel.logoUrl.collectAsState()
    val isDark = LocalIsDark.current
    val isOled = LocalIsOled.current
    val context = LocalContext.current
    val company = uiState.company

    // Fix for the feed URL field being hidden behind the IME on companies with many employees:
    // the field sits far down the LazyColumn, so when focused it needs to be actively scrolled
    // into view above the keyboard. imePadding() on the column makes room, bringIntoView()
    // scrolls to it.
    val feedUrlBringIntoView = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Primary-contact picker: pulls up every contact so the user can mark one as the
    // "this contact *is* this company" record (reverse flow of the toggle in ContactDetail).
    val isPicking by viewModel.isPickingPrimary.collectAsState()
    val primaryQuery by viewModel.primaryQuery.collectAsState()
    val primaryCandidates by viewModel.primaryCandidates.collectAsState()
    if (isPicking) {
        AlertDialog(
            onDismissRequest = { viewModel.closePrimaryPicker() },
            title = { Text("Link a contact as this company") },
            text = {
                Column {
                    OutlinedTextField(
                        value = primaryQuery,
                        onValueChange = { viewModel.onPrimaryQueryChanged(it) },
                        placeholder = { Text("Search contacts") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(primaryCandidates, key = { it.id }) { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setPrimaryContact(c.cardavUid) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(photoUri = c.photoUri, displayName = c.displayName, size = 36, shape = CircleShape)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(c.displayName, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    if (c.organization.isNotBlank()) {
                                        Text(c.organization, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.closePrimaryPicker() }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    // Edit buffers: reset only when the stored value changes (after a save or a restore)
    var website by remember(company?.website) { mutableStateOf(company?.website.orEmpty()) }
    var notes by remember(company?.notes) { mutableStateOf(company?.notes.orEmpty()) }
    var feedUrl by remember(company?.feedUrl) { mutableStateOf(company?.feedUrl.orEmpty()) }
    val detailsDirty = website.trim() != company?.website.orEmpty() || notes != company?.notes.orEmpty()

    // Interactions are always recorded against a person; for a company with several people,
    // ask which one the conversation was with.
    var showPersonPicker by remember { mutableStateOf(false) }
    if (showPersonPicker) {
        AlertDialog(
            onDismissRequest = { showPersonPicker = false },
            title = { Text("Who was it with?") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(uiState.people, key = { it.id }) { person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPersonPicker = false
                                    onLogInteraction(person.id, person.phoneNumber)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ContactAvatar(photoUri = person.photoUri, displayName = person.displayName, size = 36, shape = CircleShape)
                            Spacer(Modifier.width(12.dp))
                            Text(person.displayName, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPersonPicker = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
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
        PullToRefreshBox(
            isRefreshing = feed.isLoading,
            onRefresh    = { viewModel.refreshFeed(force = true) },
            modifier     = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 40.dp),
                modifier = Modifier.fillMaxSize().imePadding(),
            ) {

                // Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
                        }
                        CompanyLogo(name = uiState.companyName, logoUrl = logoUrl, size = 52, shape = ExpressiveAvatarShape)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                uiState.companyName,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${uiState.people.size} ${if (uiState.people.size == 1) "person" else "people"}" +
                                    " · ${uiState.interactions.size} ${if (uiState.interactions.size == 1) "interaction" else "interactions"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Log an interaction with this company: it is recorded against a person who works
                // there, and then shows up in the company's timeline below.
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val only = uiState.people.singleOrNull()
                                if (only != null) onLogInteraction(only.id, only.phoneNumber) else showPersonPicker = true
                            },
                            enabled = uiState.people.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.EditNote, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Log interaction")
                        }
                        Text(
                            if (uiState.people.isEmpty()) "Add a contact with this organization to log interactions here."
                            else "Recorded against the person you choose, and collected in the timeline below.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                // About: website + notes (plain text for now)
                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .vinciCard(isDark = isDark)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        OutlinedTextField(
                            value = website,
                            onValueChange = { website = it },
                            label = { Text("Website") },
                            placeholder = { Text("https://example.com") },
                            supportingText = { Text("Its icon becomes the company logo, fetched directly from the site.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Notes about this company") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { viewModel.saveDetails(website, notes) }, enabled = detailsDirty) {
                                Text("Save")
                            }
                            if (company?.website.orEmpty().isNotBlank()) {
                                TextButton(onClick = { openWebUrl(context, company?.website.orEmpty()) }) {
                                    Text("Open website", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                // Primary record: the one contact that *is* the company (a vCard named
                // "Barburas" for a Barburas company page). Shown as a prominent card above the
                // People list; the contact is hidden from People to avoid showing them twice.
                item {
                    val primary = uiState.primaryContact
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "PRIMARY RECORD",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                        if (primary != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .vinciCard(isDark = isDark)
                                    .clickable { onContactClick(primary.id) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(photoUri = primary.photoUri, displayName = primary.displayName, size = 48, shape = CircleShape)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(primary.displayName, style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = listOf(primary.phoneNumber, primary.email)
                                        .filter { it.isNotBlank() }
                                        .joinToString("  ·  ")
                                    if (sub.isNotBlank()) {
                                        Text(sub, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                TextButton(onClick = { viewModel.setPrimaryContact(null) }) {
                                    Text("Unlink", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.openPrimaryPicker() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Outlined.Business, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Link a contact as this company")
                            }
                        }
                    }
                }

                // People
                item { SectionHeader(text = "People", badge = uiState.people.size, color = CyanPrimary) }
                items(uiState.people, key = { it.id }) { person ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onContactClick(person.id) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ContactAvatar(photoUri = person.photoUri, displayName = person.displayName, size = 40, shape = CircleShape)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(person.displayName, style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (person.jobTitle.isNotBlank()) {
                                Text(person.jobTitle, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // Feed: where it comes from, then the latest posts
                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth()
                            .vinciCard(isDark = isDark)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("News feed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        OutlinedTextField(
                            value = feedUrl,
                            onValueChange = { feedUrl = it },
                            label = { Text("RSS / Atom feed address") },
                            placeholder = { Text("https://example.com/feed") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(feedUrlBringIntoView)
                                .onFocusEvent { focus ->
                                    if (focus.isFocused) {
                                        coroutineScope.launch { feedUrlBringIntoView.bringIntoView() }
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { viewModel.saveFeedUrl(feedUrl) },
                                enabled = feedUrl.trim() != company?.feedUrl.orEmpty(),
                            ) { Text("Save") }
                            OutlinedButton(
                                onClick = { viewModel.discoverFeed(website) },
                                enabled = !feed.isDiscovering,
                            ) { Text(if (feed.isDiscovering) "Looking..." else "Find feed") }
                        }
                        feed.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        feed.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            "Posts and their images are fetched directly by your phone from the company's site; only the feed address is synced to Nextcloud.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (feed.items.isNotEmpty()) {
                    item { SectionHeader(text = "Latest posts", badge = feed.items.size, color = CyanPrimary) }
                    items(feed.items) { post -> FeedPostRow(post = post) }
                }

                // Combined timeline
                item { SectionHeader(text = "Timeline", badge = uiState.interactions.size, color = CyanPrimary) }
                if (uiState.interactions.isEmpty()) {
                    item {
                        Text(
                            "No interactions with anyone at this company yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(uiState.interactions, key = { it.id }) { log ->
                    RecentInteractionCard(log = log, isDark = isDark, showDate = true, onClick = { onEditInteraction(log.id) })
                }
            }
        }
    }
}
