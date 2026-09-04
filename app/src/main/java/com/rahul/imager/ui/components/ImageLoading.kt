package com.rahul.imager.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Loads a thumbnail for a content URI.
 *
 * Deliberately hand-rolled rather than pulled from an image library: the app needs exactly one
 * thing — a downsampled bitmap for a `Uri` — and that is thirty lines of `BitmapFactory`. Adding
 * an image loader would be a large dependency, a memory cache the app does not need, and another
 * thing to keep in step with the Compose version.
 *
 * The load runs on IO and the composable renders a placeholder until it lands.
 */
@Composable
fun rememberThumbnail(uri: Uri?, targetPx: Int = 512): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, uri, targetPx) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadThumbnail(context, uri, targetPx)?.asImageBitmap()
            }
        }
    }
}

/** Loads a cached thumbnail file written by [com.rahul.imager.data.RecentPrintsStore]. */
@Composable
fun rememberCachedThumbnail(path: String?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    if (!file.exists()) return@runCatching null
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }

/**
 * Decodes [uri] downsampled to roughly [targetPx] on the long edge.
 *
 * API 29+ gets `loadThumbnail`, which uses the pre-generated MediaStore thumbnail when one exists
 * and is dramatically faster on a large library. Older releases fall back to a sampled decode.
 */
fun loadThumbnail(context: Context, uri: Uri, targetPx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            return context.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null)
        }.onFailure {
            Log.d(TAG, "loadThumbnail failed for $uri, falling back: ${it.message}")
        }
    }

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return@runCatching null

        var sample = 1
        while (longEdge / (sample * 2) >= targetPx) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.onFailure {
        Log.d(TAG, "Could not decode a thumbnail for $uri: ${it.message}")
    }.getOrNull()
}

private const val TAG = "ImageLoading"
