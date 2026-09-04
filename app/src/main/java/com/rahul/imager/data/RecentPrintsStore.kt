package com.rahul.imager.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rahul.imager.printer.raster.PrintOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One entry in the recent-prints row on the home screen.
 *
 * The [options] are stored alongside the image so "print it again" really is one tap: the same
 * photo comes back with the same crop, tone and dither the user chose last time.
 */
@Serializable
data class RecentPrint(
    val id: String,
    val imageUri: String,
    val thumbnailPath: String?,
    val printerName: String,
    val printerId: String?,
    val printedAtEpochMs: Long,
    val success: Boolean,
    val errorCode: String? = null,
    val options: PrintOptions = PrintOptions(),
)

/**
 * The last few prints, so the home screen can offer a one-tap reprint.
 *
 * Thumbnails are written into the app cache rather than stored inline: a base64 bitmap inside a
 * preferences value would bloat every read of the list, and the cache is exactly the right place
 * for something the system may reclaim.
 */
@Singleton
class RecentPrintsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val dataStore = context.recentPrintsDataStore

    /** Most recent first. */
    val recents: Flow<List<RecentPrint>> = dataStore.data.map { preferences ->
        preferences[KEY_RECENTS]?.let(::decode) ?: emptyList()
    }

    /**
     * Records a print attempt.
     *
     * @param thumbnail a small preview of what was printed. Written to the cache directory; when
     *   writing fails the entry is still recorded, just without a picture.
     */
    suspend fun record(
        imageUri: String,
        printerName: String,
        printerId: String?,
        success: Boolean,
        errorCode: String?,
        options: PrintOptions,
        thumbnail: Bitmap?,
    ) {
        val thumbnailPath = thumbnail?.let { writeThumbnail(it) }
        val entry = RecentPrint(
            id = UUID.randomUUID().toString(),
            imageUri = imageUri,
            thumbnailPath = thumbnailPath,
            printerName = printerName,
            printerId = printerId,
            printedAtEpochMs = System.currentTimeMillis(),
            success = success,
            errorCode = errorCode,
            options = options.sanitized(),
        )

        dataStore.edit { preferences ->
            val current = preferences[KEY_RECENTS]?.let(::decode) ?: emptyList()
            val next = (listOf(entry) + current).take(CAPACITY)
            // Anything that fell off the end takes its thumbnail file with it.
            (current - next.toSet()).forEach { dropped ->
                dropped.thumbnailPath?.let { runCatching { File(it).delete() } }
            }
            preferences[KEY_RECENTS] = PersistenceJson.encodeToString(next)
        }
    }

    /** Forgets everything, including the cached thumbnails. */
    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences[KEY_RECENTS]?.let(::decode)?.forEach { entry ->
                entry.thumbnailPath?.let { runCatching { File(it).delete() } }
            }
            preferences.remove(KEY_RECENTS)
        }
    }

    /** Removes one entry. */
    suspend fun remove(id: String) {
        dataStore.edit { preferences ->
            val current = preferences[KEY_RECENTS]?.let(::decode) ?: emptyList()
            current.firstOrNull { it.id == id }?.thumbnailPath
                ?.let { runCatching { File(it).delete() } }
            preferences[KEY_RECENTS] =
                PersistenceJson.encodeToString(current.filterNot { it.id == id })
        }
    }

    private suspend fun writeThumbnail(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, THUMBNAIL_DIR).apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.png")
            file.outputStream().use { stream ->
                val scaled = scaleForThumbnail(bitmap)
                scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
            }
            file.absolutePath
        }.onFailure { Log.w(TAG, "Could not write a recents thumbnail", it) }.getOrNull()
    }

    /** Thumbnails are display-sized; keeping a full raster per entry would be pointless. */
    private fun scaleForThumbnail(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= THUMBNAIL_WIDTH_PX) return bitmap
        val height = (bitmap.height.toLong() * THUMBNAIL_WIDTH_PX / bitmap.width)
            .toInt()
            .coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, THUMBNAIL_WIDTH_PX, height, true)
    }

    private fun decode(raw: String): List<RecentPrint> = runCatching {
        PersistenceJson.decodeFromString<List<RecentPrint>>(raw)
    }.getOrElse {
        Log.w(TAG, "Recent prints could not be decoded; starting empty", it)
        emptyList()
    }

    private companion object {
        const val TAG = "RecentPrintsStore"
        const val CAPACITY = 20
        const val THUMBNAIL_DIR = "recent_thumbnails"
        const val THUMBNAIL_WIDTH_PX = 256
        val KEY_RECENTS = stringPreferencesKey("recent_prints_json")
    }
}
