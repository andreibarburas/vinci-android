package com.brbrs.vinci.sync.contract

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.contactContractDataStore by preferencesDataStore(name = "vinci_contact_contract")

/** Feature #ContactContract -- whether the user has turned on "Sync with Android Contacts" in
 *  Settings. Own DataStore, same one-concern-per-store convention as AuthRepository/
 *  DisplayPreferencesRepository: toggling it must never touch any other preference. */
@Singleton
class ContactContractPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.contactContractDataStore
    private val ENABLED_KEY = booleanPreferencesKey("enabled")
    private val LAST_SYNCED_AT_KEY = longPreferencesKey("last_synced_at")
    private val LAST_SYNCED_COUNT_KEY = intPreferencesKey("last_synced_count")
    private val NOTIFY_ON_SYNC_KEY = booleanPreferencesKey("notify_on_sync")

    val enabled: Flow<Boolean> = store.data.map { it[ENABLED_KEY] ?: false }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[ENABLED_KEY] = value }
    }

    /** Whether to show the system "Syncing contacts" notification. Default OFF: Settings already
     *  shows live progress silently (see ContactContractSyncStatus), so the notification was
     *  pure noise for the routine hourly/periodic syncs it fired on every time. */
    val notifyOnSync: Flow<Boolean> = store.data.map { it[NOTIFY_ON_SYNC_KEY] ?: false }

    suspend fun setNotifyOnSync(value: Boolean) {
        store.edit { it[NOTIFY_ON_SYNC_KEY] = value }
    }

    /** Persisted so Settings has something to show ("Last synced ...") immediately after the
     *  app restarts, before any new sync has run in this process -- the live in-memory progress
     *  in [ContactContractSyncStatus] only covers what's happened since this process started. */
    val lastSyncedAt: Flow<Long?> = store.data.map { it[LAST_SYNCED_AT_KEY] }
    val lastSyncedCount: Flow<Int?> = store.data.map { it[LAST_SYNCED_COUNT_KEY] }

    suspend fun recordSync(syncedAtMillis: Long, count: Int) {
        store.edit {
            it[LAST_SYNCED_AT_KEY] = syncedAtMillis
            it[LAST_SYNCED_COUNT_KEY] = count
        }
    }
}
