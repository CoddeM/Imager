package com.rahul.imager.ui.screens.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One photo in the in-app browser. */
data class GalleryItem(val uri: Uri, val album: String, val dateAddedSeconds: Long)

/** What kind of access to the photo library the app currently has. */
enum class MediaAccess {
    /** Full access: every photo is visible. */
    FULL,

    /**
     * Android 14+ partial access: the user shared a selection. The app can only see those, and
     * must offer a way to change the selection rather than silently showing a short list.
     */
    PARTIAL,

    /** No access; the rationale screen is shown. */
    DENIED,
}

data class GalleryUiState(
    val access: MediaAccess = MediaAccess.DENIED,
    val albums: List<String> = emptyList(),
    val selectedAlbum: String? = null,
    val items: List<GalleryItem> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
)

/**
 * The optional in-app photo browser.
 *
 * This exists ALONGSIDE the system photo picker, never instead of it: the picker needs no
 * permission at all and is the primary path. This screen is for the user who wants to browse by
 * album, and it is the only reason the app declares a media permission.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private var offset = 0

    init {
        refreshAccess()
    }

    /** The permissions this device needs for the in-app browser. */
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Re-reads what access the app has and reloads if it improved. */
    fun refreshAccess() {
        val access = currentAccess()
        val previous = _uiState.value.access
        _uiState.value = _uiState.value.copy(access = access)
        if (access != MediaAccess.DENIED && (previous == MediaAccess.DENIED || _uiState.value.items.isEmpty())) {
            reload()
        }
    }

    /** Starts again from the first page. */
    fun reload() {
        offset = 0
        _uiState.value = _uiState.value.copy(items = emptyList(), endReached = false)
        loadNextPage()
    }

    fun selectAlbum(album: String?) {
        _uiState.value = _uiState.value.copy(selectedAlbum = album)
        reload()
    }

    /**
     * Loads the next page.
     *
     * Paged because a phone with 40 000 photos would otherwise spend several seconds and a lot of
     * memory building a list the user scrolls past the top of.
     */
    fun loadNextPage() {
        val state = _uiState.value
        if (state.loading || state.endReached || state.access == MediaAccess.DENIED) return
        _uiState.value = state.copy(loading = true)

        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { queryPage(state.selectedAlbum, offset) }
            offset += page.size
            _uiState.value = _uiState.value.copy(
                items = _uiState.value.items + page,
                loading = false,
                endReached = page.size < PAGE_SIZE,
                albums = (_uiState.value.albums + page.map { it.album }).distinct().sorted(),
            )
        }
    }

    private fun currentAccess(): MediaAccess {
        val granted = { permission: String ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.FULL
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
                else -> MediaAccess.DENIED
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                if (granted(Manifest.permission.READ_MEDIA_IMAGES)) {
                    MediaAccess.FULL
                } else {
                    MediaAccess.DENIED
                }

            else -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                MediaAccess.FULL
            } else {
                MediaAccess.DENIED
            }
        }
    }

    private fun queryPage(album: String?, offset: Int): List<GalleryItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection = album?.let { "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" }
        val selectionArgs = album?.let { arrayOf(it) }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $PAGE_SIZE OFFSET $offset"

        val items = mutableListOf<GalleryItem>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    items += GalleryItem(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                        album = cursor.getString(bucketColumn) ?: UNKNOWN_ALBUM,
                        dateAddedSeconds = cursor.getLong(dateColumn),
                    )
                }
            }
        }
        return items
    }

    private companion object {
        const val PAGE_SIZE = 120
        const val UNKNOWN_ALBUM = "Photos"
    }
}
