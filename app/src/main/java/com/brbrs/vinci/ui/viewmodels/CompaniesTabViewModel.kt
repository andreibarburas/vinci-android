package com.brbrs.vinci.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.data.CompanyDao
import com.brbrs.vinci.data.CompanyEntity
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.di.ApplicationScope
import com.brbrs.vinci.network.FaviconRepository
import com.brbrs.vinci.network.WebDavRepository
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import com.brbrs.vinci.util.companyKey
import com.brbrs.vinci.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CompanyListItem(
    val key: String,
    val name: String,
    val peopleCount: Int,
    val hasFeed: Boolean,
)

data class CompaniesTabUiState(
    val searchQuery: String = "",
    val isGridView: Boolean = false,
    // Companies grouped by first letter, in display order. "#" for non-alphabetic.
    val groups: List<Pair<String, List<CompanyListItem>>> = emptyList(),
    val searchResults: List<CompanyListItem> = emptyList(),
    val availableLetters: List<String> = emptyList(),
)

/**
 * The Companies tab. A company is listed when anyone's organization field points at it, or
 * when it has been touched on its own (notes, website, feed, or created from this tab) --
 * merely opening an untouched placeholder page doesn't make it a list entry by itself.
 */
@HiltViewModel
class CompaniesTabViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val companyDao: CompanyDao,
    private val contactDao: ContactDao,
    private val displayPrefs: DisplayPreferencesRepository,
    private val webDavRepository: WebDavRepository,
    private val faviconRepository: FaviconRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _searchQuery  = MutableStateFlow("")
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Company key -> logo address (the favicon of the company's website)
    private val _logos = MutableStateFlow<Map<String, String>>(emptyMap())
    val logos: StateFlow<Map<String, String>> = _logos.asStateFlow()

    init {
        // Resolve logos in the background, a few at a time. Results are cached on the device,
        // so only the very first lookup for a website touches the network.
        viewModelScope.launch {
            companyDao.observeAll()
                .map { rows -> rows.filter { it.website.isNotBlank() }.associate { it.companyKey to it.website } }
                .distinctUntilChanged()
                .collectLatest { sites ->
                    _logos.update { current -> current.filterKeys { it in sites } }
                    sites.entries.chunked(4).forEach { chunk ->
                        coroutineScope {
                            chunk.map { (key, site) ->
                                async {
                                    val url = faviconRepository.logoFor(site)
                                    _logos.update { if (url != null) it + (key to url) else it - key }
                                }
                            }.awaitAll()
                        }
                    }
                }
        }
    }

    fun onSearchChanged(query: String) { _searchQuery.value = query }

    // Shares the People tab's grid/list setting, so both tabs always look the same
    fun toggleGridView() {
        viewModelScope.launch {
            val current = displayPrefs.preferences.first().isGridView
            displayPrefs.setGridView(!current)
            runCatching { webDavRepository.uploadPreferences(displayPrefs.toMap()) }
        }
    }

    /** Pull-to-refresh: same catch-up-with-Nextcloud work as the regular Sync (includes company pages). */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { webDavRepository.syncPendingLogs() }
            runCatching { webDavRepository.restoreFromNextcloud() }
            runCatching { webDavRepository.dedupeCallLogs() }
            _isRefreshing.value = false
        }
    }

    /** Creates (or re-saves) a company by name, then calls [onDone] so the screen can open its page. */
    fun createCompany(name: String, onDone: () -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val key = companyKey(trimmed)
            val updated = (companyDao.get(key) ?: CompanyEntity(companyKey = key, displayName = trimmed))
                .copy(updatedAt = System.currentTimeMillis(), isSynced = false)
            companyDao.upsert(updated)
            appScope.launch {
                val uploaded = runCatching { webDavRepository.uploadCompany(updated) }.getOrDefault(false)
                if (uploaded) companyDao.markSynced(updated.companyKey, updated.updatedAt)
                else SyncScheduler.scheduleRetry(context)
            }
            onDone()
        }
    }

    val uiState: StateFlow<CompaniesTabUiState> = combine(
        companyDao.observeAll(),
        contactDao.getAllContacts(),
        _searchQuery,
        displayPrefs.preferences,
    ) { companies, contacts, query, prefs ->
        val peopleByKey = contacts
            .filter { it.organization.isNotBlank() }
            .groupBy { companyKey(it.organization) }
        val stored = companies.associateBy { it.companyKey }
        val keys = peopleByKey.keys + companies.filter { it.updatedAt > 0 }.map { it.companyKey }

        val items = keys.map { key ->
            val people = peopleByKey[key].orEmpty()
            val row = stored[key]
            // Prefer the saved name; otherwise the spelling most contacts use for it
            val name = row?.displayName?.takeIf { it.isNotBlank() }
                ?: people.groupingBy { it.organization.trim() }.eachCount().maxByOrNull { it.value }?.key
                ?: key
            CompanyListItem(key = key, name = name, peopleCount = people.size, hasFeed = row?.feedUrl?.isNotBlank() == true)
        }.sortedBy { it.name.lowercase() }

        val groups = if (query.isBlank()) {
            items
                .groupBy { letterFor(it.name) }
                .toSortedMap(compareBy { if (it == "#") "\u0000" else it }) // "#" first
                .map { (letter, list) -> letter to list }
        } else {
            emptyList()
        }
        CompaniesTabUiState(
            searchQuery      = query,
            isGridView       = prefs.isGridView,
            groups           = groups,
            searchResults    = if (query.isBlank()) emptyList() else items.filter { it.name.contains(query.trim(), ignoreCase = true) },
            availableLetters = groups.map { it.first },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompaniesTabUiState())

    private fun letterFor(name: String): String {
        val first = name.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (first in 'A'..'Z') first.toString() else "#"
    }
}
