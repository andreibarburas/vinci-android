package com.brbrs.vinci.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(name = "vinci_theme")

/** "system", "light", "dark", or "oled". */
@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val THEME_MODE = stringPreferencesKey("theme_mode")

    val themeMode: Flow<String> = context.themeDataStore.data
        .map { prefs -> prefs[THEME_MODE] ?: "dark" }

    /** Back-compat convenience for call sites that only care light vs dark (not "system"/"oled"). */
    val isDark: Flow<Boolean> = themeMode.map { it != "light" }

    suspend fun setThemeMode(mode: String) {
        context.themeDataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setDark(dark: Boolean) {
        setThemeMode(if (dark) "dark" else "light")
    }
}
