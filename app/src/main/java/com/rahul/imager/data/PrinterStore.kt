package com.rahul.imager.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rahul.imager.printer.domain.SavedPrinter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The printers the user has added, and which of them is the default.
 *
 * The whole list is stored under ONE key as a JSON array. That is deliberate: reordering, deleting
 * and changing the default all touch several entries at once, and a single value makes every one
 * of those an atomic write instead of a sequence that can be interrupted halfway.
 */
@Singleton
class PrinterStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val dataStore = context.printerDataStore

    /** Every saved printer, in the user's chosen order. */
    val printers: Flow<List<SavedPrinter>> = dataStore.data.map { preferences ->
        preferences[KEY_PRINTERS]?.let(::decode) ?: emptyList()
    }

    /** The printer the one-tap print path uses, or null when none has been added yet. */
    val defaultPrinter: Flow<SavedPrinter?> = printers.map { list ->
        list.firstOrNull { it.isDefault } ?: list.firstOrNull()
    }

    /** Adds a printer, or replaces the existing one with the same id. */
    suspend fun upsert(printer: SavedPrinter) = update { current ->
        val existing = current.indexOfFirst { it.id == printer.id }
        val next = if (existing >= 0) {
            current.toMutableList().apply { set(existing, printer) }
        } else {
            current + printer
        }
        // The first printer ever added becomes the default; nobody should have to opt in to that.
        if (next.none { it.isDefault }) {
            next.map { it.copy(isDefault = it.id == printer.id) }
        } else {
            next
        }
    }

    /** Removes a printer, promoting another to default if the removed one held that role. */
    suspend fun delete(id: String) = update { current ->
        val next = current.filterNot { it.id == id }
        if (next.isNotEmpty() && next.none { it.isDefault }) {
            next.mapIndexed { index, printer -> printer.copy(isDefault = index == 0) }
        } else {
            next
        }
    }

    /** Makes one printer the default, clearing the flag everywhere else. */
    suspend fun setDefault(id: String) = update { current ->
        current.map { it.copy(isDefault = it.id == id) }
    }

    /** Renames a printer. The display name never affects driver routing. */
    suspend fun rename(id: String, displayName: String) = update { current ->
        current.map { if (it.id == id) it.copy(displayName = displayName.trim()) else it }
    }

    /** Overrides the paper profile resolved when the printer was added. */
    suspend fun setPaperProfile(id: String, paperProfileId: String) = update { current ->
        current.map { if (it.id == id) it.copy(paperProfileId = paperProfileId) else it }
    }

    /** Applies a user-chosen order, keeping any printer the caller forgot to mention. */
    suspend fun reorder(orderedIds: List<String>) = update { current ->
        val byId = current.associateBy { it.id }
        orderedIds.mapNotNull { byId[it] } + current.filter { it.id !in orderedIds }
    }

    /** Reads the current list once, without collecting. */
    suspend fun snapshot(): List<SavedPrinter> = read()

    private suspend fun read(): List<SavedPrinter> {
        var result: List<SavedPrinter> = emptyList()
        dataStore.edit { preferences ->
            result = preferences[KEY_PRINTERS]?.let(::decode) ?: emptyList()
        }
        return result
    }

    private suspend fun update(transform: (List<SavedPrinter>) -> List<SavedPrinter>) {
        dataStore.edit { preferences ->
            val current = preferences[KEY_PRINTERS]?.let(::decode) ?: emptyList()
            preferences[KEY_PRINTERS] = PersistenceJson.encodeToString(transform(current))
        }
    }

    /**
     * Decodes the stored list, surviving corruption.
     *
     * A user losing their printer list to one bad byte would be far worse than starting over, but
     * a crash loop on every launch would be worse still, so a decode failure logs and yields an
     * empty list.
     */
    private fun decode(raw: String): List<SavedPrinter> = runCatching {
        PersistenceJson.decodeFromString<List<SavedPrinter>>(raw)
    }.getOrElse {
        Log.w(TAG, "Stored printer list could not be decoded; starting empty", it)
        emptyList()
    }

    private companion object {
        const val TAG = "PrinterStore"
        val KEY_PRINTERS = stringPreferencesKey("printers_json")
    }
}
