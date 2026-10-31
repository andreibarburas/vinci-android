package com.brbrs.vinci.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.brbrs.vinci.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.vinci.ui.components.SectionLabel
import com.brbrs.vinci.ui.theme.*
import com.brbrs.vinci.ui.viewmodels.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onConnectNextcloud: () -> Unit = {},
    onDuplicates: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context  = LocalContext.current
    val isDark   = LocalIsDark.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState.loggedOut) {
        if (uiState.loggedOut) onLoggedOut()
    }

    LaunchedEffect(uiState.connectNextcloud) {
        if (uiState.connectNextcloud) {
            viewModel.consumeConnectNextcloud()
            onConnectNextcloud()
        }
    }

    LaunchedEffect(uiState.restoreResult) {
        uiState.restoreResult?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // Local backup result snackbar
    LaunchedEffect(uiState.localBackupResult) {
        uiState.localBackupResult?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
            viewModel.clearLocalBackupResult()
        }
    }

    // Launcher for picking a Vinci backup ZIP to import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { viewModel.importLocalBackup(it) }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Vinci folder") },
            text = {
                Column {
                    Text(
                        "The folder name Vinci uses on Nextcloud for syncing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = folderInput,
                        onValueChange = { folderInput = it },
                        placeholder = { Text("Vinci") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setVinciFolder(folderInput)
                    showFolderDialog = false
                }) {
                    Text("Save", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
                }
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(Modifier.height(24.dp))

            // Account — different UI for local mode vs Nextcloud mode
            if (uiState.isLocalMode) {
                SettingsSectionLabel("Storage")
                Spacer(Modifier.height(8.dp))
                SettingsCard(isDark) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Local mode", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "Your data is stored only on this device. No account required.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Outlined.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        TextButton(
                            onClick = viewModel::connectToNextcloud,
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                        ) {
                            Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect to Nextcloud", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                SettingsSectionLabel("Account")
                Spacer(Modifier.height(8.dp))
                SettingsCard(isDark) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Connected to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(uiState.serverUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(uiState.username, style = MaterialTheme.typography.bodyMedium, color = CyanPrimary)
                            }
                            Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    folderInput = uiState.vinciFolder
                                    showFolderDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Vinci folder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(uiState.vinciFolder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        TextButton(
                            onClick = viewModel::logout,
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                        ) {
                            Icon(Icons.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign out", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Feature #ContactContract -- mirror Vinci's contacts into Android's own
            // ContactsContract, so the system Contacts app/dialer see them as real device
            // contacts and edits made there push back out to Nextcloud over Vinci's own CardDAV
            // path. Vinci already holds READ_CONTACTS/WRITE_CONTACTS from onboarding, so this is
            // a plain toggle -- no extra runtime permission request needed.
            val contactContractEnabled by viewModel.contactContractEnabled.collectAsState()
            val contactContractSyncProgress by viewModel.contactContractSyncProgress.collectAsState()
            SettingsSectionLabel("Sync")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column {
                    SettingsToggleRow(
                        icon     = Icons.Outlined.Sync,
                        title    = "Sync with Android Contacts",
                        subtitle = "Show Vinci's contacts in the system Contacts app, and push edits made there back to Nextcloud",
                        checked  = contactContractEnabled,
                        onCheckedChange = viewModel::setContactContractEnabled,
                    )
                    // Deliberately separate from the toggle above: turning the toggle off and
                    // back on to force a retry removes Vinci's Android account, which
                    // cascade-deletes every contact it already synced -- this just kicks an
                    // immediate sync without touching the account or any already-synced data.
                    if (contactContractEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.syncContactContractNow() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sync now", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                // Live status -- replaces the old static subtitle so a sync in
                                // progress is actually visible instead of looking inert while it
                                // runs (which, for a ~1300-contact address book, can take a
                                // few minutes).
                                val statusText = when {
                                    contactContractSyncProgress.isSyncing && contactContractSyncProgress.total > 0 ->
                                        "Syncing… ${contactContractSyncProgress.processed}/${contactContractSyncProgress.total}"
                                    contactContractSyncProgress.isSyncing -> "Syncing…"
                                    contactContractSyncProgress.lastSyncedAt != null -> {
                                        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(contactContractSyncProgress.lastSyncedAt!!))
                                        val count = contactContractSyncProgress.lastSyncedCount
                                        if (count != null) "Last synced $time · $count contacts" else "Last synced $time"
                                    }
                                    else -> "Retries without resetting progress"
                                }
                                Text(statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (contactContractSyncProgress.isSyncing) {
                                    Spacer(Modifier.height(6.dp))
                                    if (contactContractSyncProgress.total > 0) {
                                        LinearProgressIndicator(
                                            progress = { contactContractSyncProgress.processed.toFloat() / contactContractSyncProgress.total },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        val contactContractNotifyEnabled by viewModel.contactContractNotifyEnabled.collectAsState()
                        SettingsToggleRow(
                            icon     = Icons.Outlined.Notifications,
                            title    = "Sync notifications",
                            subtitle = "Also show a system notification while syncing (off by default -- the row above already shows progress)",
                            checked  = contactContractNotifyEnabled,
                            onCheckedChange = viewModel::setContactContractNotifyEnabled,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Security
            SettingsSectionLabel("Security")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                SettingsToggleRow(
                    icon     = Icons.Outlined.Fingerprint,
                    title    = "App lock",
                    subtitle = "Require biometrics when opening Vinci",
                    checked  = uiState.appLockEnabled,
                    onCheckedChange = viewModel::setAppLock,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Reminders -- daily follow-up summary at a user-chosen time, cleared by logging
            // an interaction or by tapping "Mark done" on the notification itself.
            SettingsSectionLabel("Reminders")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column {
                    SettingsToggleRow(
                        icon     = Icons.Outlined.Notifications,
                        title    = "Daily follow-up reminder",
                        subtitle = "Posts a single summary notification when any follow-ups are due",
                        checked  = uiState.followUpReminderEnabled,
                        onCheckedChange = viewModel::setFollowUpReminderEnabled,
                    )
                    if (uiState.followUpReminderEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        val timeText = String.format("%02d:%02d", uiState.followUpReminderHour, uiState.followUpReminderMinute)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    TimePickerDialog(
                                        context,
                                        { _, h, m -> viewModel.setFollowUpReminderTime(h, m) },
                                        uiState.followUpReminderHour,
                                        uiState.followUpReminderMinute,
                                        true,
                                    ).show()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Reminder time", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Device-local time (default 09:00)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(timeText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Calendar nudges -- cross-references device calendar events with Vinci contacts
            // via READ_CALENDAR; the permission is only requested when the user flips this on.
            // Proton Calendar is a known miss (its events don't reach CalendarContract).
            val calendarPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) viewModel.setCalendarNudgesEnabled(true)
            }
            SettingsSectionLabel("Calendar nudges")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column {
                    SettingsToggleRow(
                        icon     = Icons.Outlined.CalendarMonth,
                        title    = "Nudge after a meeting",
                        subtitle = "Prompts you to log a meeting about 2 min after it ends, when an attendee matches a Vinci contact by email.",
                        checked  = uiState.calendarNudgesEnabled,
                        onCheckedChange = { enable ->
                            if (!enable) {
                                viewModel.setCalendarNudgesEnabled(false)
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.READ_CALENDAR
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) viewModel.setCalendarNudgesEnabled(true)
                                else calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        "Reads calendars that sync to Android (Samsung Calendar, Nextcloud via DAVx5, Google, Outlook). Proton Calendar is not supported -- its events stay inside Proton.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Caller preview -- how the on-RINGING "who is this?" card appears.
            SettingsSectionLabel("Caller preview")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneCallback, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("On incoming calls", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Shows the last 2-3 recorded interactions so you can refresh your memory.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val options = listOf(
                        "context"    to "More context",
                        "prominence" to "More prominence",
                        "off"        to "Off",
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { (value, label) ->
                            val selected = uiState.callerPreviewMode == value
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .vinciChip(isDark = isDark, selected = selected)
                                    .clickable { viewModel.setCallerPreviewMode(value) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val caption = when (uiState.callerPreviewMode) {
                        "prominence" -> "Prominent heads-up card over the ringing screen."
                        "off"        -> "No preview shown."
                        else         -> "Expanded notification with the last 2-3 interactions."
                    }
                    Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Integrations
            SettingsSectionLabel("Integrations")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                SettingsToggleRow(
                    icon     = Icons.Outlined.Alarm,
                    title    = "Koto",
                    subtitle = if (uiState.tasksInstalled)
                        "Create follow-up tasks in your Nextcloud via Koto"
                    else
                        "Install Koto to turn follow-ups into Nextcloud tasks",
                    checked  = uiState.tasksEnabled,
                    enabled  = uiState.tasksInstalled,
                    onCheckedChange = viewModel::toggleTasks,
                )
            }

            Spacer(Modifier.height(24.dp))

            // SIM identities -- lets the user label each SIM (e.g. "Personal", "Business") so
            // that call interactions capture which line the call came in on. Dual-SIM only
            // populates here if READ_PHONE_STATE has been granted (it already is for the call
            // receiver), so there's no extra permission prompt.
            SettingsSectionLabel("Phones")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.SimCard, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SIM labels", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Name each line so call interactions remember which phone the call reached (e.g. Personal / Business).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (uiState.sims.isEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Text(
                            "No SIM detected on this device.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    } else {
                        uiState.sims.forEach { sim ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            var localLabel by remember(sim.subscriptionId, uiState.simLabels[sim.subscriptionId]) {
                                mutableStateOf(uiState.simLabels[sim.subscriptionId].orEmpty())
                            }
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                val headline = buildString {
                                    append("SIM ")
                                    append(sim.slotIndex + 1)
                                    if (sim.carrierName.isNotBlank()) append(" · ").append(sim.carrierName)
                                }
                                Text(headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                if (sim.displayNumber.isNotBlank()) {
                                    Text(sim.displayNumber, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = localLabel,
                                    onValueChange = {
                                        localLabel = it.take(32)
                                        viewModel.setSimLabel(sim.subscriptionId, localLabel)
                                    },
                                    placeholder = { Text("Label this line, e.g. Personal") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Display
            SettingsSectionLabel("Display")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DarkMode, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Theme", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(10.dp))
                    val themeOptions = listOf("system" to "System", "light" to "Light", "dark" to "Dark", "oled" to "OLED Black")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        themeOptions.forEach { (value, label) ->
                            val isSelected = uiState.themeMode == value
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .vinciChip(isDark = isDark, selected = isSelected)
                                    .clickable { viewModel.setThemeMode(value) }
                                    .padding(vertical = 10.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.TextFields, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Text size", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(10.dp))
                    val options = listOf("small" to "Small", "default" to "Default", "large" to "Large", "extra_large" to "Extra Large")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { (value, label) ->
                            val isSelected = uiState.textSize == value
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .vinciChip(isDark = isDark, selected = isSelected)
                                    .clickable { viewModel.setTextSize(value) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.FontDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("DM Serif + Inter Tight", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Use custom font pairing instead of system fonts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(checked = uiState.useCustomFont, onCheckedChange = viewModel::setUseCustomFont)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Messaging
            SettingsSectionLabel("Messaging")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Default country code", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Used for WhatsApp/Signal when a phone number has no country code, e.g. 31 for the Netherlands",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    var countryCode by remember(uiState.defaultCountryCode) { mutableStateOf(uiState.defaultCountryCode) }
                    OutlinedTextField(
                        value = countryCode,
                        onValueChange = {
                            countryCode = it.filter { c -> c.isDigit() }.take(4)
                            viewModel.setDefaultCountryCode(countryCode)
                        },
                        placeholder = { Text("e.g. 31") },
                        leadingIcon = { Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Attachments
            SettingsSectionLabel("Attachments")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep attachments on device", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Off: attachments are stored on Nextcloud only and downloaded when opened", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = uiState.attachmentsKeepLocal, onCheckedChange = viewModel::setAttachmentsKeepLocal)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CleaningServices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Clear cached attachments", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                val sizeText = when {
                                    uiState.cachedAttachmentsSize >= 1024 * 1024 -> String.format("%.1f MB", uiState.cachedAttachmentsSize / (1024.0 * 1024.0))
                                    uiState.cachedAttachmentsSize >= 1024        -> String.format("%.0f KB", uiState.cachedAttachmentsSize / 1024.0)
                                    else                                          -> "${uiState.cachedAttachmentsSize} B"
                                }
                                Text("${uiState.cachedAttachmentsCount} files, $sizeText -- Nextcloud copies are kept", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = viewModel::clearAttachmentCache, enabled = uiState.cachedAttachmentsCount > 0) {
                            Text("Clear", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Export file share-sheet handler (shared by both local and Nextcloud modes)
            val exportFile by viewModel.exportFile.collectAsState()
            LaunchedEffect(exportFile) {
                exportFile?.let { file ->
                    val isZip = file.extension == "zip"
                    val intent = if (isZip) {
                        viewModel.localBackupShareIntentFor(context, file)
                    } else {
                        viewModel.shareIntentFor(context, file)
                    }
                    context.startActivity(Intent.createChooser(intent, if (isZip) "Save Vinci backup" else "Export interactions"))
                    viewModel.clearExportFile()
                }
            }

            // Data section — different content for local vs Nextcloud mode
            SettingsSectionLabel("Data")
            Spacer(Modifier.height(8.dp))

            if (uiState.isLocalMode) {
                // Local mode: phone migration backup instead of cloud sync/restore
                SettingsCard(isDark) {
                    Column {
                        // Export backup ZIP
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.FileUpload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Back up to file", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        "Export all interactions and settings as a Vinci ZIP file for phone migration",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (uiState.isLocalExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                TextButton(onClick = viewModel::exportLocalBackup) {
                                    Text("Export", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                        // Import backup ZIP
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.FileDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Restore from backup", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        "Import a Vinci backup ZIP from a previous device",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (uiState.isLocalImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                TextButton(onClick = { importLauncher.launch("application/zip") }) {
                                    Text("Import", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                        // Duplicate contacts (available in both modes)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.CallMerge, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Duplicate contacts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Find and merge split contact histories", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = onDuplicates) {
                                Text("Review", color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                        // Export as Markdown / CSV (available in both modes)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Export interactions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Save all logged interactions as Markdown or CSV", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (uiState.exportError != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(uiState.exportError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (uiState.isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Row {
                                    TextButton(onClick = viewModel::exportAsMarkdown) { Text("MD", color = MaterialTheme.colorScheme.primary) }
                                    TextButton(onClick = viewModel::exportAsCsv) { Text("CSV", color = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                    }
                }
            } else {
                // Nextcloud mode: sync, restore, duplicates, export
                SettingsCard(isDark) {
                    Column {
                        // Sync starred contacts to Nextcloud
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CloudUpload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Sync starred to Nextcloud", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Push starred contacts and follow-ups to Nextcloud", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (uiState.isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                TextButton(onClick = viewModel::syncStarredNow) {
                                    Text("Sync", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                        // Restore from Nextcloud
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Restore from Nextcloud", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Reimport starred contacts, follow-ups and interactions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (uiState.isRestoring) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                TextButton(onClick = viewModel::restoreNow) {
                                    Text("Restore", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.CallMerge, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Duplicate contacts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Find and merge split contact histories", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = onDuplicates) {
                                Text("Review", color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                        // Export interactions -- Markdown / CSV
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.FileDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Export interactions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Save all logged interactions as Markdown or CSV", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (uiState.exportError != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(uiState.exportError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (uiState.isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Row {
                                    TextButton(onClick = viewModel::exportAsMarkdown) {
                                        Text("Markdown", color = MaterialTheme.colorScheme.primary)
                                    }
                                    TextButton(onClick = viewModel::exportAsCsv) {
                                        Text("CSV", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Support
            SettingsSectionLabel("Support")
            Spacer(Modifier.height(8.dp))
            SettingsCard(isDark) {
                Column {
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bunq.me/barburasdonations?description=Donation%20from%20Vinci"))) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Buy me a coffee", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            Text("If Vinci saves you time, a small tip means a lot", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://barburas.com"))) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("More by andrei BARBURAS", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            Text("See what else I'm building at barburas.com", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://barburas.com/privacy-policy/"))) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Privacy Policy", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            Text("How Vinci handles your data", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andreibarburas/android-apps"))) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("View on GitHub", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            Text("Feedback, feature requests and source code", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/r/BarburasLab/"))) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.si_reddit),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Join r/BarburasLab", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            Text("Discuss Vinci and my other apps on Reddit", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                // Reads the real build version instead of a hardcoded string that's drifted
                // out of date at every release since 2.3.0 -- this is what showed "2.3.0" in
                // Settings for several versions in a row despite build.gradle.kts being bumped.
                "Vinci ${com.brbrs.vinci.BuildConfig.VERSION_NAME} · by andrei BARBURAS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SettingsCard(isDark: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(cornerRadius = 16.dp, tint = if (isDark) Color.White else Color.Black),
    ) { content() }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor  = MaterialTheme.colorScheme.primary,
                checkedTrackColor  = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                uncheckedThumbColor= MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor= MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}
