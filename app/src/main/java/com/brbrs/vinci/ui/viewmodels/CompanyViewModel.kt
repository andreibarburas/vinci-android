package com.brbrs.vinci.ui.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.data.CallLogEntity
import com.brbrs.vinci.data.CompanyDao
import com.brbrs.vinci.data.CompanyEntity
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.data.ContactEntity
import com.brbrs.vinci.di.ApplicationScope
import com.brbrs.vinci.network.FeedItem
import com.brbrs.vinci.network.FaviconRepository
import com.brbrs.vinci.network.FeedRepository
import com.brbrs.vinci.network.WebDavRepository
import com.brbrs.vinci.util.companyKey
import com.brbrs.vinci.util.parseParticipants
import com.brbrs.vinci.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

data class CompanyUiState(
    val companyName: String = "",
    val company: CompanyEntity? = null,
    val people: List<ContactEntity> = emptyList(),
    val interactions: List<CallLogEntity> = emptyList(),
    /** When set, this contact's photo/phone/email become the company's own header details, and
     *  the contact is hidden from the "People" list on its own page. */
    val primaryContact: ContactEntity? = null,
)

data class CompanyFeedState(
    val items: List<FeedItem> = emptyList(),
    val isLoading: Boolean = false,
    val isDiscovering: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class CompanyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val companyDao: CompanyDao,
    private val contactDao: ContactDao,
    private val callLogDao: CallLogDao,
    private val webDavRepository: WebDavRepository,
    private val feedRepository: FeedRepository,
    private val faviconRepository: FaviconRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    // The organization name exactly as written on the contact that was tapped; the page's
    // identity is companyKey(name), so spelling/legal-suffix variants land on the same page.
    private val displayName: String = URLDecoder.decode(savedStateHandle["companyName"] ?: "", "UTF-8")
    private val key: String = companyKey(displayName)

    private val _feed = MutableStateFlow(CompanyFeedState())
    val feed: StateFlow<CompanyFeedState> = _feed.asStateFlow()

    // The logo is the favicon of the company's *website* (not its feed); follows the saved website
    val logoUrl: StateFlow<String?> = companyDao.observe(key)
        .map { it?.website.orEmpty() }
        .distinctUntilChanged()
        .map { site -> if (site.isBlank()) null else faviconRepository.logoFor(site) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val uiState: StateFlow<CompanyUiState> = combine(
        companyDao.observe(key),
        contactDao.getAllContacts(),
        callLogDao.getAllLogs(),
    ) { company, contacts, logs ->
        val primaryUid = company?.primaryContactUid.orEmpty()
        val primary    = if (primaryUid.isNotBlank()) contacts.firstOrNull { it.cardavUid == primaryUid } else null
        val peopleByOrg = contacts.filter { it.organization.isNotBlank() && companyKey(it.organization) == key }
        // The primary contact is already shown in the header; keep them out of the People list
        // to avoid duplication, but still count their interactions for the timeline below.
        val people = peopleByOrg.filter { primary == null || it.cardavUid != primary.cardavUid }
        val timelineContacts = (peopleByOrg + listOfNotNull(primary)).distinctBy { it.id }
        val ids  = timelineContacts.map { it.id }.toSet()
        val uids = timelineContacts.map { it.cardavUid }.filter { it.isNotBlank() }.toSet()
        // Interactions logged against someone at the company, plus group interactions one of
        // them took part in as an extra participant.
        val interactions = logs.filter { log ->
            val cid = log.contactId
            (cid != null && cid in ids) ||
                (uids.isNotEmpty() && parseParticipants(log.participants).any { it.uid in uids })
        }
        CompanyUiState(
            companyName = company?.displayName ?: displayName,
            company = company,
            people = people,
            interactions = interactions,
            primaryContact = primary,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompanyUiState(companyName = displayName))

    private val _pickingPrimary = MutableStateFlow(false)
    val isPickingPrimary: StateFlow<Boolean> = _pickingPrimary.asStateFlow()
    private val _primaryQuery = MutableStateFlow("")
    val primaryQuery: StateFlow<String> = _primaryQuery.asStateFlow()

    val primaryCandidates: StateFlow<List<ContactEntity>> =
        combine(contactDao.getAllContacts(), _primaryQuery) { contacts, q ->
            if (q.isBlank()) contacts.take(50)
            else contacts.filter { it.displayName.contains(q, ignoreCase = true) }.take(50)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun openPrimaryPicker()  { _pickingPrimary.value = true; _primaryQuery.value = "" }
    fun closePrimaryPicker() { _pickingPrimary.value = false }
    fun onPrimaryQueryChanged(q: String) { _primaryQuery.value = q }

    /** Sets or clears the Company's primary record. The link is Vinci-only -- the vCard is
     *  not modified. Writes `primary_contact_uid:` into the Company's index.md on Nextcloud. */
    fun setPrimaryContact(uid: String?) {
        viewModelScope.launch {
            persist { it.copy(primaryContactUid = uid.orEmpty()) }
            _pickingPrimary.value = false
        }
    }

    init {
        if (key.isNotBlank()) {
            viewModelScope.launch {
                // Placeholder row so the page has something to observe/edit. It starts out
                // "synced" and empty, so merely opening a company never creates files.
                if (companyDao.get(key) == null) {
                    companyDao.upsert(CompanyEntity(companyKey = key, displayName = displayName))
                }
                refreshFeed(force = false)
            }
        }
    }

    fun saveDetails(website: String, notes: String) {
        viewModelScope.launch { persist { it.copy(website = website.trim(), notes = notes) } }
    }

    fun saveFeedUrl(url: String) {
        viewModelScope.launch {
            persist { it.copy(feedUrl = url.trim()) }
            refreshFeed(force = true)
        }
    }

    fun refreshFeed(force: Boolean = true) {
        viewModelScope.launch {
            val url = companyDao.get(key)?.feedUrl.orEmpty()
            if (url.isBlank()) {
                _feed.update { CompanyFeedState() }
                return@launch
            }
            _feed.update { it.copy(isLoading = true, error = null) }
            feedRepository.load(url, force).fold(
                onSuccess = { items -> _feed.update { it.copy(items = items, isLoading = false) } },
                onFailure = { e ->
                    _feed.update {
                        it.copy(isLoading = false, items = emptyList(),
                            error = "Couldn't load the feed: ${e.message ?: "unknown error"}")
                    }
                },
            )
        }
    }

    /** Looks for a feed on the company's website and, if found, saves and loads it. */
    fun discoverFeed(website: String) {
        viewModelScope.launch {
            if (website.isBlank()) {
                _feed.update { it.copy(message = "Add the company's website first, then Vinci can look for its feed.") }
                return@launch
            }
            _feed.update { it.copy(isDiscovering = true, message = null, error = null) }
            val found = feedRepository.discover(website)
            if (found != null) {
                persist { it.copy(website = website.trim(), feedUrl = found) }
                _feed.update { it.copy(isDiscovering = false, message = "Found a feed: $found") }
                refreshFeed(force = true)
            } else {
                _feed.update {
                    it.copy(isDiscovering = false,
                        message = "No feed found on that site. If you know the feed address, paste it in manually.")
                }
            }
        }
    }

    fun dismissMessage() { _feed.update { it.copy(message = null) } }

    /**
     * Saves locally first, then uploads in the app-wide scope (so it survives leaving the screen).
     * If there's no connection right now the company stays marked unsynced and the regular
     * sync -- or the background retry -- picks it up later, same as interactions.
     */
    private suspend fun persist(transform: (CompanyEntity) -> CompanyEntity) {
        val current = companyDao.get(key) ?: CompanyEntity(companyKey = key, displayName = displayName)
        val updated = transform(current).copy(updatedAt = System.currentTimeMillis(), isSynced = false)
        companyDao.upsert(updated)
        appScope.launch {
            val uploaded = runCatching { webDavRepository.uploadCompany(updated) }.getOrDefault(false)
            if (uploaded) companyDao.markSynced(updated.companyKey, updated.updatedAt)
            else SyncScheduler.scheduleRetry(context)
        }
    }
}
