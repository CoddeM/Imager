package com.rahul.imager.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Which colour scheme the app should use. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The handful of app-wide preferences that are not about printers or print options. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val keepRecents: Boolean = true,
)

/**
 * App-wide preferences.
 *
 * Deliberately tiny, and stored alongside the print options rather than in a fourth file: these
 * change about as often, and one fewer DataStore is one fewer thing to keep consistent.
 */
@Singleton
class AppSettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val dataStore = context.printOptionsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            themeMode = preferences[KEY_THEME]
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: ThemeMode.SYSTEM,
            keepRecents = preferences[KEY_KEEP_RECENTS] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setKeepRecents(enabled: Boolean) {
        dataStore.edit { it[KEY_KEEP_RECENTS] = enabled }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_KEEP_RECENTS = booleanPreferencesKey("keep_recents")
    }
}
