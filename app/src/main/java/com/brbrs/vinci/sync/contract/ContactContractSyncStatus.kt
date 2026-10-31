package com.brbrs.vinci.sync.contract

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Live state of an in-progress (or just-finished) ContactContract sync, for Settings to show
 *  something other than a static toggle that gives no sign anything is actually happening.
 *  Process-lifetime only (not persisted) -- [ContactContractPreferences.lastSyncedAt]/
 *  [ContactContractPreferences.lastSyncedCount] cover "what happened before this process
 *  started", this covers "what's happening right now". */
data class ContactContractSyncProgress(
    val isSyncing: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val lastSyncedAt: Long? = null,
    val lastSyncedCount: Int? = null,
)

@Singleton
class ContactContractSyncStatus @Inject constructor() {
    private val _progress = MutableStateFlow(ContactContractSyncProgress())
    val progress = _progress.asStateFlow()

    fun syncStarted() {
        _progress.value = _progress.value.copy(isSyncing = true, processed = 0, total = 0)
    }

    fun chunkProcessed(count: Int, total: Int) {
        val current = _progress.value
        _progress.value = current.copy(
            processed = (current.processed + count).coerceAtMost(total),
            total = total,
        )
    }

    fun syncFinished(syncedAtMillis: Long, count: Int) {
        _progress.value = _progress.value.copy(isSyncing = false, lastSyncedAt = syncedAtMillis, lastSyncedCount = count)
    }

    /** Called once at app startup to seed the "last synced" line from DataStore before this
     *  process has run a sync of its own. */
    fun restoreLast(syncedAtMillis: Long?, count: Int?) {
        if (syncedAtMillis == null) return
        _progress.value = _progress.value.copy(lastSyncedAt = syncedAtMillis, lastSyncedCount = count)
    }
}
