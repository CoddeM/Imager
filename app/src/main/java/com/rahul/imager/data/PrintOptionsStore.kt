package com.rahul.imager.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rahul.imager.printer.raster.PrintOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers how the user likes their photos printed.
 *
 * Two separate things live here:
 *
 *  * the LAST-USED [PrintOptions], so the next photo opens exactly where the previous one left
 *    off rather than resetting every slider;
 *  * per-printer paper-width OVERRIDES, because the automatic resolution is only ever a default
 *    and a user who has corrected it once should never have to correct it again.
 */
@Singleton
class PrintOptionsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val dataStore = context.printOptionsDataStore

    /** The options the last print used, or the defaults on a fresh install. */
    val lastUsedOptions: Flow<PrintOptions> = dataStore.data.map { preferences ->
        preferences[KEY_LAST_OPTIONS]?.let(::decodeOptions) ?: PrintOptions()
    }

    /** Paper profile overrides, keyed by printer id. */
    val paperOverrides: Flow<Map<String, String>> = dataStore.data.map { preferences ->
        preferences[KEY_PAPER_OVERRIDES]?.let(::decodeOverrides) ?: emptyMap()
    }

    suspend fun saveLastUsed(options: PrintOptions) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_OPTIONS] = PersistenceJson.encodeToString(options.sanitized())
        }
    }

    /** Records that this printer should use [paperProfileId] regardless of what was resolved. */
    suspend fun setPaperOverride(printerId: String, paperProfileId: String) {
        dataStore.edit { preferences ->
            val current = preferences[KEY_PAPER_OVERRIDES]?.let(::decodeOverrides) ?: emptyMap()
            preferences[KEY_PAPER_OVERRIDES] =
                PersistenceJson.encodeToString(current + (printerId to paperProfileId))
        }
    }

    /** Drops an override so the printer goes back to the automatically resolved width. */
    suspend fun clearPaperOverride(printerId: String) {
        dataStore.edit { preferences ->
            val current = preferences[KEY_PAPER_OVERRIDES]?.let(::decodeOverrides) ?: emptyMap()
            preferences[KEY_PAPER_OVERRIDES] =
                PersistenceJson.encodeToString(current - printerId)
        }
    }

    private fun decodeOptions(raw: String): PrintOptions = runCatching {
        PersistenceJson.decodeFromString<PrintOptions>(raw).sanitized()
    }.getOrElse {
        Log.w(TAG, "Stored print options could not be decoded; using defaults", it)
        PrintOptions()
    }

    private fun decodeOverrides(raw: String): Map<String, String> = runCatching {
        PersistenceJson.decodeFromString<Map<String, String>>(raw)
    }.getOrElse {
        Log.w(TAG, "Stored paper overrides could not be decoded; ignoring them", it)
        emptyMap()
    }

    private companion object {
        const val TAG = "PrintOptionsStore"
        val KEY_LAST_OPTIONS = stringPreferencesKey("last_options_json")
        val KEY_PAPER_OVERRIDES = stringPreferencesKey("paper_overrides_json")
    }
}
