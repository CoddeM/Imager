package com.rahul.imager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json

/**
 * The app's three DataStores.
 *
 * They are separate files on purpose: printers, print options and the recents list have completely
 * different write frequencies, and a slider drag rewriting the printer list would be both wasteful
 * and a corruption risk.
 *
 * `preferencesDataStore` must be declared exactly once per file name per process — that is why
 * these live at the top level here rather than inside the repositories that use them.
 */
internal val Context.printerDataStore: DataStore<Preferences> by preferencesDataStore("printers")

internal val Context.printOptionsDataStore: DataStore<Preferences> by
    preferencesDataStore("print_options")

internal val Context.recentPrintsDataStore: DataStore<Preferences> by
    preferencesDataStore("recent_prints")

/**
 * The JSON codec used for every persisted value.
 *
 * `ignoreUnknownKeys` and `encodeDefaults` together mean a stored record written by an older or
 * newer build still loads: fields that have gone are ignored, fields that are new take their
 * default. Persisted user data must never be lost to a schema change.
 */
internal val PersistenceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}
