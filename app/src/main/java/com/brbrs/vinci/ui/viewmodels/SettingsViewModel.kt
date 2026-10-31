package com.brbrs.vinci.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.auth.AuthRepository
import com.brbrs.vinci.network.RestoreResult
import com.brbrs.vinci.network.WebDavRepository
import com.brbrs.vinci.sync.contract.ContactContractPreferences
import com.brbrs.vinci.sync.contract.ContactContractSyncWorker
import com.brbrs.vinci.sync.contract.VinciAccount
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import com.brbrs.vinci.tasks.KotoHelper
import com.brbrs.vinci.tasks.KotoPreference
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.util.LocalBackupRepository
import com.brbrs.vinci.work.CalendarNudgeScheduler
import com.brbrs.vinci.work.FollowUpScheduler
import androidx.core.content.FileProvider
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val vinciFolder: String = "Vinci",
    val appLockEnabled: Boolean = false,
    val tasksEnabled: Boolean = false,
    val tasksInstalled: Boolean = false,
    val loggedOut: Boolean = false,
    val isRestoring: Boolean = false,
    val isSyncing: Boolean = false,
    val restoreResult: String? = null,
    val isExporting: Boolean = false,
    val exportError: String? = null,
    val defaultCountryCode: String = "",
    val attachmentsKeepLocal: Boolean = false,
    val cachedAttachmentsSize: Long = 0,
    val cachedAttachmentsCount: Int = 0,
    val textSize: String = "default",
    val useCustomFont: Boolean = false,
    val themeMode: String = "dark",
    /** Active SIM subscriptions on this device, discovered via SubscriptionManager, so each one
     *  can be given a name ("Personal", "Business"...) that then flows into Call interactions. */
    val sims: List<SimInfo> = emptyList(),
    /** Current label the user has assigned to each SIM, keyed by subscription id. */
    val simLabels: Map<String, String> = emptyMap(),
    val followUpReminderEnabled: Boolean = true,
    val followUpReminderHour: Int = 9,
    val followUpReminderMinute: Int = 0,
    val calendarNudgesEnabled: Boolean = false,
    val callerPreviewMode: String = "context",
    /** true when the user chose "Use locally" — hides Nextcloud sync UI, shows local backup UI. */
    val isLocalMode: Boolean = false,
    /** Result message for the local backup export/import operation. */
    val localBackupResult: String? = null,
    val isLocalExporting: Boolean = false,
    val isLocalImporting: Boolean = false,
    /** Signals that the user just switched from local → Nextcloud and should go to Login. */
    val connectNextcloud: Boolean = false,
)

/** One SIM slot on the device, from `SubscriptionManager.getActiveSubscriptionInfoList()`. */
data class SimInfo(
    val subscriptionId: String,
    val carrierName: String,
    val displayNumber: String,
    val slotIndex: Int,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val tasksPref: KotoPreference,
    private val webDavRepository: WebDavRepository,
    private val displayPrefs: DisplayPreferencesRepository,
    private val callLogDao: CallLogDao,
    private val contactDao: com.brbrs.vinci.data.ContactDao,
    private val attachmentDao: com.brbrs.vinci.data.AttachmentDao,
    private val themeRepository: com.brbrs.vinci.ui.theme.ThemeRepository,
    private val localBackupRepository: LocalBackupRepository,
    private val contactContractPreferences: ContactContractPreferences,
    private val contactContractSyncStatus: com.brbrs.vinci.sync.contract.ContactContractSyncStatus,
) : ViewModel() {

    // Feature #ContactContract -- kept as its own StateFlow rather than folded into the big
    // combine() below, which is already at the 5-source limit it works around with nested
    // combines (see its own comment).
    val contactContractEnabled: StateFlow<Boolean> =
        contactContractPreferences.enabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Live sync progress for the Settings row -- see ContactContractSyncStatus's own doc for
     *  why this exists: a bare toggle with no feedback looked like it wasn't doing anything for
     *  a sync that can take several minutes on a ~1300-contact address book. */
    val contactContractSyncProgress = contactContractSyncStatus.progress

    /** Separate from the above: whether routine syncing also posts a SYSTEM notification.
     *  Defaults off, since the Settings row already shows progress without one -- this only
     *  matters to someone who wants a heads-up outside the app too. */
    val contactContractNotifyEnabled: StateFlow<Boolean> =
        contactContractPreferences.notifyOnSync.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setContactContractNotifyEnabled(enabled: Boolean) {
        viewModelScope.launch { contactContractPreferences.setNotifyOnSync(enabled) }
    }

    /** Called only after the READ_CONTACTS/WRITE_CONTACTS permission request (already granted
     *  at first launch for Vinci's own ContactsContract reads) is confirmed in Compose --
     *  creates/removes the stub account, flips the preference, and arms/disarms the periodic
     *  mirror. Mirrors Zaman's SyncSettingsViewModel.setCalendarContractEnabled. */
    fun setContactContractEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                VinciAccount.get(context)
                contactContractPreferences.setEnabled(true)
                ContactContractSyncWorker.enqueuePeriodic(context)
                ContactContractSyncWorker.enqueue(context)
            } else {
                contactContractPreferences.setEnabled(false)
                ContactContractSyncWorker.cancelPeriodic(context)
                VinciAccount.remove(context)
            }
        }
    }

    /** Non-destructive "sync now": just fires an immediate one-shot sync, same as what
     *  setContactContractEnabled(true) enqueues. Deliberately does NOT touch the account or the
     *  enabled preference -- unlike toggling the setting off and back on, which was the
     *  mistaken way to force a retry: disabling calls VinciAccount.remove(), and removing an
     *  Android account cascade-deletes every RawContacts row under it, wiping all of Vinci's
     *  already-synced contacts back to zero. This is the safe equivalent. */
    fun syncContactContractNow() {
        ContactContractSyncWorker.enqueue(context)
    }

    private val _loggedOut      = MutableStateFlow(false)
    private val _exportFile     = MutableStateFlow<File?>(null)
    private val _cacheInfo      = MutableStateFlow(0L to 0)
    val exportFile: StateFlow<File?> = _exportFile.asStateFlow()
    fun clearExportFile() { _exportFile.value = null }

    private val _sims = MutableStateFlow<List<SimInfo>>(emptyList())

    init {
        refreshCacheInfo()
        loadSims()
    }

    /**
     * Reads the active SIM subscriptions once at startup. Guarded in a try/catch because
     * SubscriptionManager requires READ_PHONE_STATE at runtime -- if the user hasn't granted
     * it yet, we just show an empty list and the UI explains how to enable it.
     */
    private fun loadSims() {
        viewModelScope.launch {
            val list = try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? android.telephony.SubscriptionManager
                sm?.activeSubscriptionInfoList?.map { info ->
                    SimInfo(
                        subscriptionId = info.subscriptionId.toString(),
                        carrierName    = info.carrierName?.toString().orEmpty(),
                        displayNumber  = info.number.orEmpty(),
                        slotIndex      = info.simSlotIndex,
                    )
                } ?: emptyList()
            } catch (e: SecurityException) { emptyList() }
            _sims.value = list
        }
    }

    fun setSimLabel(subscriptionId: String, label: String) {
        viewModelScope.launch { displayPrefs.setSimLabel(subscriptionId, label) }
    }

    private fun refreshCacheInfo() {
        viewModelScope.launch {
            val cached = attachmentDao.getCachedAttachments()
            val totalSize = cached.sumOf { it.sizeBytes }
            _cacheInfo.value = totalSize to cached.size
        }
    }

    /** Deletes all locally-cached attachment files (Nextcloud copies are untouched). */
    fun clearAttachmentCache() {
        viewModelScope.launch {
            val cached = attachmentDao.getCachedAttachments()
            cached.forEach { att ->
                if (att.localPath.isNotBlank()) runCatching { File(att.localPath).delete() }
                attachmentDao.clearLocalCache(att.id)
            }
            refreshCacheInfo()
        }
    }

    fun setAttachmentsKeepLocal(enabled: Boolean) {
        viewModelScope.launch { displayPrefs.setAttachmentsKeepLocal(enabled) }
    }

    // Bundle transient operation state into one flow so combine() stays under the 5-flow limit
    private data class OpState(
        val isRestoring: Boolean = false,
        val isSyncing: Boolean = false,
        val restoreResult: String? = null,
        val isExporting: Boolean = false,
        val exportError: String? = null,
        val localBackupResult: String? = null,
        val isLocalExporting: Boolean = false,
        val isLocalImporting: Boolean = false,
        val connectNextcloud: Boolean = false,
    )
    private val _opState = MutableStateFlow(OpState())

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.session,
        authRepository.appLockEnabled,
        tasksPref.enabled,
        combine(_loggedOut, _cacheInfo, _sims, authRepository.storageMode) { loggedOut, cache, sims, mode ->
            object { val loggedOut = loggedOut; val cache = cache; val sims = sims; val mode = mode }
        },
        combine(displayPrefs.preferences, _opState, themeRepository.themeMode) { display, op, theme -> Triple(display, op, theme) },
    ) { session, appLock, tasks, extra, displayOpTheme ->
        val (display, op, theme) = displayOpTheme
        SettingsUiState(
            serverUrl      = session?.serverUrl ?: "",
            username       = session?.username  ?: "",
            vinciFolder    = session?.vinciFolder ?: "Vinci",
            appLockEnabled = appLock,
            tasksEnabled   = tasks,
            tasksInstalled = KotoHelper.isInstalled(context),
            loggedOut      = extra.loggedOut,
            isRestoring    = op.isRestoring,
            isSyncing      = op.isSyncing,
            restoreResult  = op.restoreResult,
            isExporting    = op.isExporting,
            exportError    = op.exportError,
            defaultCountryCode      = display.defaultCountryCode,
            attachmentsKeepLocal    = display.attachmentsKeepLocal,
            cachedAttachmentsSize   = extra.cache.first,
            cachedAttachmentsCount  = extra.cache.second,
            textSize                = display.textSize,
            useCustomFont           = display.useCustomFont,
            themeMode               = theme,
            sims                    = extra.sims,
            simLabels               = display.simLabels,
            followUpReminderEnabled = display.followUpReminderEnabled,
            followUpReminderHour    = display.followUpReminderHour,
            followUpReminderMinute  = display.followUpReminderMinute,
            calendarNudgesEnabled   = display.calendarNudgesEnabled,
            callerPreviewMode       = display.callerPreviewMode,
            isLocalMode             = extra.mode == "local",
            localBackupResult       = op.localBackupResult,
            isLocalExporting        = op.isLocalExporting,
            isLocalImporting        = op.isLocalImporting,
            connectNextcloud        = op.connectNextcloud,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setFollowUpReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            displayPrefs.setFollowUpReminderEnabled(enabled)
            if (enabled) {
                val p = displayPrefs.preferences.first()
                FollowUpScheduler.schedule(context, p.followUpReminderHour, p.followUpReminderMinute)
            } else {
                FollowUpScheduler.cancel(context)
            }
        }
    }

    fun setFollowUpReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            displayPrefs.setFollowUpReminderTime(hour, minute)
            if (displayPrefs.preferences.first().followUpReminderEnabled) {
                FollowUpScheduler.schedule(context, hour, minute)
            }
        }
    }

    fun setCalendarNudgesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            displayPrefs.setCalendarNudgesEnabled(enabled)
            if (enabled) CalendarNudgeScheduler.schedule(context)
            else         CalendarNudgeScheduler.cancel(context)
        }
    }

    fun setCallerPreviewMode(mode: String) {
        viewModelScope.launch { displayPrefs.setCallerPreviewMode(mode) }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { themeRepository.setThemeMode(mode) }
    }

    fun setTextSize(size: String) {
        viewModelScope.launch { displayPrefs.setTextSize(size) }
    }

    fun setUseCustomFont(enabled: Boolean) {
        viewModelScope.launch { displayPrefs.setUseCustomFont(enabled) }
    }

    fun setDefaultCountryCode(code: String) {
        viewModelScope.launch { displayPrefs.setDefaultCountryCode(code) }
    }

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { authRepository.setAppLockEnabled(enabled) }
    }

    fun setVinciFolder(folder: String) {
        viewModelScope.launch { authRepository.saveVinciFolder(folder.trim().ifBlank { "Vinci" }) }
    }

    fun toggleTasks(enabled: Boolean) {
        viewModelScope.launch { tasksPref.setEnabled(enabled) }
    }

    fun syncStarredNow() {
        viewModelScope.launch {
            _opState.update { it.copy(isSyncing = true) }
            _opState.update { it.copy(restoreResult = "Syncing starred contacts…") }
            val starred = contactDao.getStarredContactsOnce()
            var synced = 0
            starred.forEach { contact ->
                runCatching {
                    webDavRepository.writeIndexMd(
                        contactUid  = contact.cardavUid,
                        contactName = contact.displayName,
                        isStarred   = true,
                        followUpDue = contact.followUpDue,
                    )
                    synced++
                }
            }
            _opState.update { it.copy(isSyncing = false) }
            _opState.update { it.copy(restoreResult = "Synced $synced starred contacts to Nextcloud.") }
        }
    }

    fun restoreNow() {
        viewModelScope.launch {
            _opState.update { it.copy(isRestoring = true) }
            _opState.update { it.copy(restoreResult = "Starting restore…") }
            val result = webDavRepository.restoreFromNextcloud()
            _opState.update { it.copy(isRestoring = false) }
            _opState.update { it.copy(restoreResult = if (result.error != null)
                "Restore failed: ${result.error}"
            else
                "Restored ${result.contactsRestored} contacts and ${result.logsImported} interactions."
            ) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.clearSession()
            _loggedOut.value = true
        }
    }

    // -- Export / backup -------------------------------------------------------

    fun exportAsMarkdown() {
        viewModelScope.launch {
            _opState.update { it.copy(isExporting = true) }
            _opState.update { it.copy(exportError = null) }
            try {
                val logs = callLogDao.getAllLogs().first()
                val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder()
                sb.append("# Vinci interactions export\n\n")
                sb.append("Exported ${dateFmt.format(Date())}  -- ${logs.size} interactions\n\n")

                val grouped = logs.groupBy { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it.callTimestamp)) }
                grouped.forEach { (month, monthLogs) ->
                    sb.append("## $month\n\n")
                    monthLogs.forEach { log ->
                        sb.append("### ${log.contactName}\n")
                        sb.append("- Date: ${dateFmt.format(Date(log.callTimestamp))}\n")
                        sb.append("- Type: ${log.interactionType}\n")
                        if (log.reason.isNotBlank())  sb.append("- Reason: ${log.reason}\n")
                        if (log.outcome.isNotBlank()) sb.append("- Outcome: ${log.outcome}\n")
                        if (log.phoneNumber.isNotBlank()) sb.append("- Phone: ${log.phoneNumber}\n")
                        val tags = parseTagsForExport(log.tags)
                        if (tags.isNotEmpty()) sb.append("- Tags: ${tags.joinToString(", ")}\n")
                        if (log.notes.isNotBlank()) {
                            sb.append("\n${log.notes}\n")
                        }
                        sb.append("\n")
                    }
                }

                val file = writeExportFile("vinci-interactions.md", sb.toString())
                _exportFile.value = file
            } catch (e: Exception) {
                _opState.update { it.copy(exportError = "Export failed: ${e.message}") }
            } finally {
                _opState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun exportAsCsv() {
        viewModelScope.launch {
            _opState.update { it.copy(isExporting = true) }
            _opState.update { it.copy(exportError = null) }
            try {
                val logs = callLogDao.getAllLogs().first()
                val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder()
                sb.append("date,contact,phone,type,reason,outcome,tags,notes\n")
                logs.forEach { log ->
                    val tags = parseTagsForExport(log.tags).joinToString("; ")
                    sb.append(csvEscape(dateFmt.format(Date(log.callTimestamp)))).append(",")
                    sb.append(csvEscape(log.contactName)).append(",")
                    sb.append(csvEscape(log.phoneNumber)).append(",")
                    sb.append(csvEscape(log.interactionType)).append(",")
                    sb.append(csvEscape(log.reason)).append(",")
                    sb.append(csvEscape(log.outcome)).append(",")
                    sb.append(csvEscape(tags)).append(",")
                    sb.append(csvEscape(log.notes)).append("\n")
                }

                val file = writeExportFile("vinci-interactions.csv", sb.toString())
                _exportFile.value = file
            } catch (e: Exception) {
                _opState.update { it.copy(exportError = "Export failed: ${e.message}") }
            } finally {
                _opState.update { it.copy(isExporting = false) }
            }
        }
    }

    private fun csvEscape(value: String): String {
        val needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n")
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun parseTagsForExport(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeExportFile(name: String, content: String): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.writeText(content)
        return file
    }

    /** Returns a sharable content:// URI for the given export file via FileProvider. */
    fun shareIntentFor(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "com.brbrs.vinci.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "csv") "text/csv" else "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Local backup (phone migration) ────────────────────────────────────────

    /**
     * Exports all interactions + settings to a Vinci ZIP archive and opens the system
     * share sheet so the user can send it to another device, cloud drive, email, etc.
     */
    fun exportLocalBackup() {
        viewModelScope.launch {
            _opState.update { it.copy(isLocalExporting = true, localBackupResult = null) }
            localBackupRepository.export()
                .onSuccess { file ->
                    _exportFile.value = file
                    _opState.update { it.copy(isLocalExporting = false, localBackupResult = "Backup created — tap Share to save it.") }
                }
                .onFailure { e ->
                    _opState.update { it.copy(isLocalExporting = false, localBackupResult = "Export failed: ${e.message}") }
                }
        }
    }

    fun localBackupShareIntentFor(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "com.brbrs.vinci.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Vinci backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Imports a Vinci backup ZIP chosen by the user. The URI is resolved to a temp file
     * in cacheDir before passing to [LocalBackupRepository] so it can be read normally.
     */
    fun importLocalBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            _opState.update { it.copy(isLocalImporting = true, localBackupResult = null) }
            try {
                val tmp = File(context.cacheDir, "import_backup.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open selected file")

                localBackupRepository.import(tmp)
                    .onSuccess { result ->
                        val msg = buildString {
                            append("Imported ${result.imported} interactions.")
                            if (result.orphaned > 0) append(" ${result.orphaned} flagged as orphaned (contact not on this phone).")
                            if (result.skipped > 0)  append(" ${result.skipped} already existed.")
                        }
                        _opState.update { it.copy(isLocalImporting = false, localBackupResult = msg) }
                    }
                    .onFailure { e ->
                        _opState.update { it.copy(isLocalImporting = false, localBackupResult = "Import failed: ${e.message}") }
                    }
            } catch (e: Exception) {
                _opState.update { it.copy(isLocalImporting = false, localBackupResult = "Import failed: ${e.message}") }
            }
        }
    }

    fun clearLocalBackupResult() {
        _opState.update { it.copy(localBackupResult = null) }
    }

    // ── Switch modes ─────────────────────────────────────────────────────────

    /**
     * Called when a local-mode user decides to connect to Nextcloud.
     * Sets storage mode to "nextcloud" and signals the nav to route to Login.
     */
    fun connectToNextcloud() {
        viewModelScope.launch {
            authRepository.setStorageMode("nextcloud")
            _opState.update { it.copy(connectNextcloud = true) }
        }
    }

    fun consumeConnectNextcloud() {
        _opState.update { it.copy(connectNextcloud = false) }
    }
}
