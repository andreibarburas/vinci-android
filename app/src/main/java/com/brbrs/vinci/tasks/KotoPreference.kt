package com.brbrs.vinci.tasks

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore name is kept at "vinci_tasks" so existing installs don't lose their toggle state
// when the integration was renamed from Tasks.org to Koto.
private val Context.kotoDataStore by preferencesDataStore(name = "vinci_tasks")

@Singleton
class KotoPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY = booleanPreferencesKey("tasks_enabled")

    val enabled: Flow<Boolean> = context.kotoDataStore.data.map { it[KEY] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        context.kotoDataStore.edit { it[KEY] = enabled }
    }
}
