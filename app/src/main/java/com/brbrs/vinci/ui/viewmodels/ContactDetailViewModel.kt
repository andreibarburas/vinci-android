package com.brbrs.vinci.ui.viewmodels

import android.content.Context
import android.provider.CallLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.data.AttachmentDao
import com.brbrs.vinci.data.AttachmentEntity
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.data.CallLogEntity
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.data.ContactEntity
import com.brbrs.vinci.network.WebDavRepository
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import com.brbrs.vinci.util.ContactPdfExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Threshold in ms — system call and Vinci log within 5 minutes = same interaction
private const val MATCH_THRESHOLD_MS = 5 * 60 * 1000L

data class RecentInteraction(
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val durationSeconds: Int,
    val source: String,         // "system"
    val reason: String,
    val notes: String,
    val matchedLogId: Long?,    // non-null if a Vinci log exists for this call
)

data class ContactDetailUiState(
    val contact: ContactEntity? = null,
    val callLogs: List<CallLogEntity> = emptyList(),
    val showAllLogs: Boolean = false,
    val recentInteractions: List<RecentInteraction> = emptyList(),
    val showAllInteractions: Boolean = false,
    val defaultCountryCode: String = "",
    /** Social links fetched fresh from Nextcloud vCard (null = loading, "" = none found) */
    val remoteSocialLinks: String? = null,
    /** True when this contact is the primary record of a Vinci Company page. */
    val isCompanyPrimary: Boolean = false,
    /** The company key where this contact is primary, blank otherwise. */
    val companyKey: String = "",
    /**
     * One-line conversation starter assembled from the contact's standing context
     * (notes on the contact card) and the most recent interaction note.
     * Blank when there is not enough context to build one.
     * Examples:
     *   "Last time: the OMRK exam. Ask how it went."
     *   "Recently discussed: project deadline. Catch up on progress."
     */
    val conversationStarter: String = "",
)

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val callLogDao: CallLogDao,
    private val webDavRepository: WebDavRepository,
    private val cardDavRepository: com.brbrs.vinci.network.CardDavRepository,
    private val displayPrefs: DisplayPreferencesRepository,
    private val companyDao: com.brbrs.vinci.data.CompanyDao,
    private val feedRepository: com.brbrs.vinci.network.FeedRepository,
    private val contactLinksRepository: com.brbrs.vinci.network.ContactLinksRepository,
    private val attachmentDao: AttachmentDao,
) : ViewModel() {

    private val contactId: Long = checkNotNull(savedStateHandle["contactId"])

    // Newest posts (max 5) from this contact's company feed, shown at the bottom of the profile
    // and in the pre-call briefing. Follows the company's saved feed address, so adding or
    // changing the feed on the company page updates this when you come back to the profile.
    private val _companyPosts = MutableStateFlow<List<com.brbrs.vinci.network.FeedItem>>(emptyList())
    val companyPosts: StateFlow<List<com.brbrs.vinci.network.FeedItem>> = _companyPosts.asStateFlow()

    // Items from Tavla/Koto/Nóta linked to this contact. Loading = null, so the section can
    // stay hidden instead of flashing an empty state before the first fetch returns. Only
    // attempted when a Nextcloud session exists -- local-only installs just keep this null/empty
    // and the section never appears (see ContactLinksRepository).
    private val _linkedItems =
        MutableStateFlow<List<com.brbrs.vinci.network.LinkedItem>?>(null)
    val linkedItems: StateFlow<List<com.brbrs.vinci.network.LinkedItem>?> = _linkedItems.asStateFlow()

    init {
        viewModelScope.launch {
            val contact = contactDao.getContactById(contactId) ?: return@launch
            if (contact.organization.isBlank()) return@launch
            companyDao.observe(com.brbrs.vinci.util.companyKey(contact.organization))
                .map { it?.feedUrl.orEmpty() }
                .distinctUntilChanged()
                .collectLatest { url ->
                    _companyPosts.value =
                        if (url.isBlank()) emptyList()
                        else feedRepository.load(url).getOrNull()?.take(5).orEmpty()
                }
        }
        viewModelScope.launch {
            val contact = contactDao.getContactById(contactId) ?: return@launch
            _linkedItems.value = contactLinksRepository.linkedItemsFor(contact.cardavUid)
        }
    }

    /** Pull-to-refresh / manual retry for the linked-items section. */
    fun refreshLinkedItems() {
        viewModelScope.launch {
            val contact = contactDao.getContactById(contactId) ?: return@launch
            _linkedItems.value = contactLinksRepository.linkedItemsFor(contact.cardavUid)
        }
    }
    private val _showAllLogs         = MutableStateFlow(false)
    private val _showAllInteractions = MutableStateFlow(false)
    private val _remoteSocialLinks   = MutableStateFlow<String?>(null) // null = not yet fetched
    private val _recentInteractions  = MutableStateFlow<List<RecentInteraction>>(emptyList())
    private val _companyPrimary     = MutableStateFlow<com.brbrs.vinci.data.CompanyEntity?>(null)

    private val contactFlow = flow { emit(contactDao.getContactById(contactId)) }

    /**
     * Interactions logged against this contact directly, plus interactions where this contact
     * was added as an extra participant (e.g. a group call logged from someone else's profile).
     */
    private val mergedLogsFlow: Flow<List<CallLogEntity>> = contactFlow.flatMapLatest { contact ->
        val primaryFlow = callLogDao.getLogsForContact(contactId)
        val participantFlow = if (contact != null && contact.cardavUid.isNotBlank()) {
            callLogDao.getLogsAsParticipant(contact.cardavUid)
        } else {
            flowOf(emptyList())
        }
        combine(primaryFlow, participantFlow) { primary, asParticipant ->
            (primary + asParticipant).distinctBy { it.id }.sortedByDescending { it.callTimestamp }
        }
    }

    val uiState: StateFlow<ContactDetailUiState> = combine(
        contactFlow,
        mergedLogsFlow,
        combine(_showAllLogs, _showAllInteractions) { a, b -> a to b },
        combine(_recentInteractions, _remoteSocialLinks, _companyPrimary) { i, s, cp -> Triple(i, s, cp) },
        displayPrefs.preferences,
    ) { contact, logs, showFlags, interactionsLinksCompany, prefs ->
        val (showAll, showAllInt) = showFlags
        val (interactions, remoteSocialLinks, companyPrimary) = interactionsLinksCompany
        // Re-match interactions whenever Vinci logs change
        val matched = interactions.map { interaction ->
            val matchedLog = logs.firstOrNull { log ->
                kotlin.math.abs(log.callTimestamp - interaction.timestampMs) <= MATCH_THRESHOLD_MS
            }
            interaction.copy(matchedLogId = matchedLog?.id)
        }
        ContactDetailUiState(
            contact             = contact,
            callLogs            = logs,
            showAllLogs         = showAll,
            recentInteractions  = matched,
            showAllInteractions = showAllInt,
            defaultCountryCode  = prefs.defaultCountryCode,
            remoteSocialLinks   = remoteSocialLinks,
            isCompanyPrimary    = companyPrimary != null,
            companyKey          = companyPrimary?.companyKey.orEmpty(),
            conversationStarter = buildConversationStarter(contact, logs),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContactDetailUiState())

    init {
        viewModelScope.launch {
            val contact = contactDao.getContactById(contactId) ?: run {
                return@launch
            }
            if (contact.cardavUid.isBlank()) {
                _remoteSocialLinks.value = ""
                return@launch
            }
            val links = runCatching { cardDavRepository.fetchSocialLinks(contact.cardavUid) }
                .onFailure {
 }
                .getOrNull()
            _remoteSocialLinks.value = links ?: contact.socialLinks.ifBlank { "" }
        }
    }

    init {
        viewModelScope.launch { loadRecentInteractions() }
    }

    init {
        viewModelScope.launch {
            val contact = contactDao.getContactById(contactId) ?: return@launch
            if (contact.cardavUid.isBlank()) return@launch
            _companyPrimary.value = companyDao.getByPrimaryContactUid(contact.cardavUid)
        }
    }

    /**
     * Toggles whether this contact *is* a Vinci company (the primary record of its own
     * Company page). The link is Vinci-only: no vCard mutation. If a Company page already
     * exists under the contact's display-name key (because other people have that string
     * in their organization field), it attaches to it; otherwise a new one is created.
     */
    fun toggleContactIsCompany() {
        viewModelScope.launch {
            val contact = contactDao.getContactById(contactId) ?: return@launch
            if (contact.cardavUid.isBlank()) return@launch
            val existing = companyDao.getByPrimaryContactUid(contact.cardavUid)
            val now = System.currentTimeMillis()
            if (existing != null) {
                // Unlink: clear the primary and bump updatedAt so the change syncs.
                val updated = existing.copy(primaryContactUid = "", updatedAt = now, isSynced = false)
                companyDao.upsert(updated)
                _companyPrimary.value = null
                pushCompanySync(updated)
            } else {
                val key    = com.brbrs.vinci.util.companyKey(contact.displayName)
                val stored = companyDao.get(key)
                val merged = (stored ?: com.brbrs.vinci.data.CompanyEntity(companyKey = key, displayName = contact.displayName))
                    .copy(primaryContactUid = contact.cardavUid, updatedAt = now, isSynced = false)
                companyDao.upsert(merged)
                _companyPrimary.value = merged
                pushCompanySync(merged)
            }
        }
    }

    private fun pushCompanySync(company: com.brbrs.vinci.data.CompanyEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val uploaded = runCatching { webDavRepository.uploadCompany(company) }.getOrDefault(false)
            if (uploaded) companyDao.markSynced(company.companyKey, company.updatedAt)
            else com.brbrs.vinci.work.SyncScheduler.scheduleRetry(context)
        }
    }

    fun toggleShowAllLogs()         { _showAllLogs.value = !_showAllLogs.value }
    fun toggleShowAllInteractions() { _showAllInteractions.value = !_showAllInteractions.value }

    fun toggleStarred() {
        viewModelScope.launch {
            val contact = contactDao.getContactById(contactId) ?: return@launch
            val newStarred = !contact.isStarred
            contactDao.setStarred(contactId, newStarred)
            runCatching {
                webDavRepository.writeIndexMd(
                    contactUid   = contact.cardavUid,
                    contactName  = contact.displayName,
                    isStarred    = newStarred,
                    followUpDue  = contact.followUpDue,
                )
            }
        }
    }

    private suspend fun loadRecentInteractions() = withContext(Dispatchers.IO) {
        val contact = contactDao.getContactById(contactId) ?: return@withContext
        val interactions = mutableListOf<RecentInteraction>()

        // No phone number → nothing to match against the system call log. Without this guard,
        // takeLast(7) on a blank string returns "" and LIKE "%" matches every call on the device.
        if (contact.phoneNumber.isBlank()) {
            _recentInteractions.value = emptyList()
            return@withContext
        }

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                "${CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%${contact.phoneNumber.takeLast(7)}"),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val dateCol = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durCol  = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val typeCol = cursor.getColumnIndex(CallLog.Calls.TYPE)
                while (cursor.moveToNext()) {
                    val type = cursor.getInt(typeCol)
                    interactions.add(
                        RecentInteraction(
                            timestampMs     = cursor.getLong(dateCol),
                            isOutgoing      = type == CallLog.Calls.OUTGOING_TYPE,
                            durationSeconds = cursor.getInt(durCol),
                            source          = "system",
                            reason          = "",
                            notes           = "",
                            matchedLogId    = null, // matched reactively in combine above
                        )
                    )
                }
            }
        } catch (e: SecurityException) { /* READ_CALL_LOG not granted */ }

        _recentInteractions.value = interactions
    }

    // -- Conversation starter --------------------------------------------

    /**
     * Assembles a one-line prompt from standing context (contact notes) and the
     * most recent interaction note.  No cloud AI — purely local text rearrangement.
     *
     * Strategy:
     *  1. Pull the first meaningful sentence from the most recent interaction's notes.
     *  2. Pull the first meaningful sentence from the contact's standing notes.
     *  3. Combine: "Last time: <topic>. Ask how it went." or
     *     "Recently discussed: <topic>. Catch up." or
     *     "<Contact note>. Last talked about <topic>." etc.
     *
     * Returns blank when there is not enough context (no notes anywhere).
     */
    private fun buildConversationStarter(contact: ContactEntity?, logs: List<CallLogEntity>): String {
        contact ?: return ""

        val lastNote = logs.firstOrNull { it.notes.isNotBlank() }?.notes
            ?.lines()?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(120)

        val contactNote = contact.notes
            .lines().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(120)

        return when {
            lastNote != null && contactNote != null -> {
                // Both available — lead with standing context, suffix with last topic
                val topic = lastNote.trimEnd('.', '!', '?')
                "💬 ${contactNote.trimEnd('.', '!', '?')}. Last time: $topic."
            }
            lastNote != null -> {
                val topic = lastNote.trimEnd('.', '!', '?')
                "💬 Last time: $topic. Ask how it went."
            }
            contactNote != null -> {
                "💬 ${contactNote.trimEnd('.', '!', '?')}."
            }
            else -> ""
        }
    }

    // -- Export this contact's history -----------------------------------

    private val _exportFile = MutableStateFlow<java.io.File?>(null)
    val exportFile: StateFlow<java.io.File?> = _exportFile.asStateFlow()
    fun clearExportFile() { _exportFile.value = null }

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    /**
     * Builds a shareable PDF with this contact's full history (primary + as-participant
     * interactions, SIM labels, notes, image attachments inlined, non-image attachments
     * listed by name). Downloads image attachments to the cache first so the PDF generator
     * doesn't do network. Non-image attachments are kept Nextcloud-only; their names appear
     * at the end of each interaction block so the reader knows they exist.
     */
    fun exportContactHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _isExporting.value = true
            try {
                val contact = contactDao.getContactById(contactId) ?: return@launch
                val primary = callLogDao.getLogsForContact(contactId).first()
                val asParticipant = if (contact.cardavUid.isNotBlank()) {
                    callLogDao.getLogsAsParticipant(contact.cardavUid).first()
                } else emptyList()
                val logs = (primary + asParticipant).distinctBy { it.id }.sortedByDescending { it.callTimestamp }

                // Attachments per log, with image files pre-downloaded for inlining.
                val attachmentsByLogId = mutableMapOf<Long, List<AttachmentEntity>>()
                val imagesByAttachmentId = mutableMapOf<Long, java.io.File>()
                val exportImagesDir = java.io.File(context.cacheDir, "export-images").apply {
                    if (!exists()) mkdirs()
                }
                for (log in logs) {
                    val list = attachmentDao.getAttachmentsForLog(log.id).first()
                    attachmentsByLogId[log.id] = list
                    for (att in list) {
                        if (!att.mimeType.startsWith("image/")) continue
                        // Prefer the locally cached copy; fall back to a download for this export.
                        val local = if (att.cachedLocally && att.localPath.isNotBlank())
                            java.io.File(att.localPath).takeIf { it.exists() } else null
                        val file = local ?: run {
                            val dest = java.io.File(exportImagesDir, "${att.id}-${att.fileName}")
                            val ok = runCatching { webDavRepository.downloadAttachment(att.nextcloudPath, dest) }.getOrDefault(false)
                            if (ok) dest else null
                        }
                        if (file != null) imagesByAttachmentId[att.id] = file
                    }
                }

                val file = ContactPdfExporter.writePdf(
                    context,
                    ContactPdfExporter.Input(
                        contact              = contact,
                        interactions         = logs,
                        attachmentsByLogId   = attachmentsByLogId,
                        imagesByAttachmentId = imagesByAttachmentId,
                    ),
                )
                _exportFile.value = file
            } finally {
                _isExporting.value = false
            }
        }
    }

    /** Returns a sharable content:// URI intent for the exported PDF. */
    fun shareIntentFor(file: java.io.File): android.content.Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.brbrs.vinci.fileprovider", file)
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
