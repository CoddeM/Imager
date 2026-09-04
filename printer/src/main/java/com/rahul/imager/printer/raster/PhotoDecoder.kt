package com.rahul.imager.printer.raster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlin.math.max

/**
 * Decodes a gallery photo into a software bitmap this app can actually read pixels from.
 *
 * This is deliberately stubborn. A photo the user picked is a photo the user expects to print, and
 * "that file may be damaged" is almost never true — what is true is that ONE decoder refused it.
 * `ImageDecoder`, in particular, throws `DecodeException: Failed to create image decoder with
 * message 'unimplemented'` on a whole class of perfectly good files: some HEIC/HEIF encodings, HDR
 * and gainmap JPEGs from recent phone cameras, and images served by providers that cannot supply
 * the seekable descriptor it wants. `BitmapFactory` decodes most of those without complaint.
 *
 * So instead of one attempt, this runs a LADDER: several independent decoders at a given sample
 * size, then the whole ladder again at double the sample size, until something works or the image
 * has been shrunk past any possible use. Because the output is dithered down to one bit per dot,
 * dropping to a smaller sample size or to RGB_565 costs the printed result essentially nothing —
 * which is exactly why trading quality for "it worked" is the right trade here.
 *
 * Nothing here throws. A caller gets a bitmap or `null`.
 */
object PhotoDecoder {

    private const val TAG = "PhotoDecoder"

    /** Never sample past this; beyond it the image is too small to be worth printing. */
    const val MAX_SAMPLE_SIZE = 64

    /** Assumed long edge when the bounds pass fails, chosen so the first try is a safe size. */
    private const val ASSUMED_LONG_EDGE_PX = 6_000

    /** What a decode produced, and how much had to be given up to get it. */
    data class Decoded(
        val bitmap: Bitmap,
        /** The sample size that finally worked. */
        val sampleSize: Int,
        /** Which rung of the ladder succeeded, for diagnostics. */
        val strategy: String,
        /** True when the ladder had to go beyond the requested size to get anything at all. */
        val downgraded: Boolean,
    )

    /**
     * Decodes [uri], downsampled so its long edge is roughly [maxLongEdge] pixels.
     *
     * @return the decoded photo, or `null` only when every decoder on every rung refused it.
     */
    fun decode(context: Context, uri: Uri, maxLongEdge: Int): Decoded? {
        val bounds = readBounds(context, uri)
        val requestedSample = initialSampleSize(bounds, maxLongEdge)
        var sample = requestedSample
        val failures = mutableListOf<String>()

        while (sample <= MAX_SAMPLE_SIZE) {
            for (strategy in strategies) {
                val bitmap = try {
                    strategy.decode(context, uri, sample)
                } catch (e: OutOfMemoryError) {
                    // Memory, not the file. The next rung is half the pixels, so keep going.
                    failures += "${strategy.name}@$sample: OOM"
                    null
                } catch (e: Exception) {
                    failures += "${strategy.name}@$sample: ${e.javaClass.simpleName}"
                    null
                }

                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                    val oriented = applyOrientationIfNeeded(context, uri, bitmap, strategy)
                    val readable = toReadableSoftwareBitmap(oriented)
                    if (readable != null) {
                        if (failures.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "Decoded $uri with ${strategy.name}@$sample after " +
                                    "${failures.size} refusals: ${failures.joinToString()}",
                            )
                        }
                        return Decoded(
                            bitmap = readable,
                            sampleSize = sample,
                            strategy = strategy.name,
                            downgraded = sample > requestedSample,
                        )
                    }
                    bitmap.recycle()
                }
            }
            sample *= 2
        }

        Log.w(TAG, "Every decoder refused $uri: ${failures.joinToString()}")
        return null
    }

    // -------------------------------------------------------------------------------------------
    // The ladder
    // -------------------------------------------------------------------------------------------

    /** One way of turning a `Uri` into a `Bitmap`. */
    private class Strategy(
        val name: String,
        /** True when this decoder applies EXIF orientation itself. */
        val handlesOrientation: Boolean,
        val decode: (Context, Uri, Int) -> Bitmap?,
    )

    /**
     * The rungs, in order of preference.
     *
     * `ImageDecoder` first because it is the most correct when it works — it applies EXIF
     * orientation and handles modern formats natively. Everything after it is a fallback that
     * trades some fidelity for the ability to decode files `ImageDecoder` rejects outright.
     */
    private val strategies: List<Strategy> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(
                Strategy("ImageDecoder", handlesOrientation = true) { context, uri, sample ->
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        // A HARDWARE bitmap cannot be read with getPixels(), and reading pixels is
                        // the entire point of this pipeline.
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                        // Broken or truncated files still yield their good rows this way instead
                        // of throwing the whole photo away.
                        decoder.setOnPartialImageListener { true }
                        decoder.setTargetSampleSize(sample)
                    }
                },
            )
        }

        // The workhorse fallback: BitmapFactory over a plain stream. Decodes most of what
        // ImageDecoder refuses, including a lot of vendor HEIC and HDR JPEG.
        add(
            Strategy("BitmapFactory.stream", handlesOrientation = false) { context, uri, sample ->
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions(sample, hq = true))
                }
            },
        )

        // A different platform code path: some content providers hand back a descriptor that
        // decodes when a stream does not, notably for cloud-backed and SAF-backed items.
        add(
            Strategy("BitmapFactory.fd", handlesOrientation = false) { context, uri, sample ->
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    BitmapFactory.decodeFileDescriptor(
                        descriptor.fileDescriptor,
                        null,
                        decodeOptions(sample, hq = true),
                    )
                }
            },
        )

        // Last resort: half the memory per pixel. The output is dithered to one bit per dot, so
        // 16-bit colour costs the printed photo nothing that anyone could see.
        add(
            Strategy("BitmapFactory.rgb565", handlesOrientation = false) { context, uri, sample ->
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions(sample, hq = false))
                }
            },
        )
    }

    private fun decodeOptions(sample: Int, hq: Boolean) = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (hq) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        // Decode what is there rather than refusing a file with a damaged tail.
        inJustDecodeBounds = false
    }

    // -------------------------------------------------------------------------------------------
    // Sizing
    // -------------------------------------------------------------------------------------------

    /** The pixel dimensions of [uri], or `null` when the bounds pass itself fails. */
    fun readBounds(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }.getOrNull()

    /**
     * The sample size to start the ladder at.
     *
     * When the bounds are unknown the image is ASSUMED to be large: starting too small merely
     * costs one wasted attempt, while starting too large on a 100 MP photo costs an OOM.
     */
    fun initialSampleSize(bounds: Pair<Int, Int>?, maxLongEdge: Int): Int {
        val longEdge = bounds?.let { max(it.first, it.second) } ?: ASSUMED_LONG_EDGE_PX
        return sampleSizeFor(longEdge, maxLongEdge)
    }

    /**
     * The largest power-of-two sample size that still leaves the long edge at or above
     * [maxLongEdge].
     *
     * Power of two because `BitmapFactory` rounds anything else down to one anyway, and matching
     * it keeps the two decoder families producing the same dimensions.
     */
    fun sampleSizeFor(longEdge: Int, maxLongEdge: Int): Int {
        if (longEdge <= 0 || maxLongEdge <= 0) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= maxLongEdge && sample < MAX_SAMPLE_SIZE) sample *= 2
        return sample
    }

    // -------------------------------------------------------------------------------------------
    // Post-processing
    // -------------------------------------------------------------------------------------------

    /**
     * Rotates a bitmap to match its EXIF orientation, for the decoders that do not do it.
     *
     * Orientation is read from the file's own EXIF first — `android.media.ExifInterface` accepts an
     * `InputStream` from API 24, so this needs no extra library — and falls back to the MediaStore
     * column, which is often absent for photo-picker and SAF URIs.
     */
    private fun applyOrientationIfNeeded(
        context: Context,
        uri: Uri,
        bitmap: Bitmap,
        strategy: Strategy,
    ): Bitmap {
        if (strategy.handlesOrientation) return bitmap
        val degrees = exifOrientationDegrees(context, uri)
            ?: mediaStoreOrientationDegrees(context, uri)
            ?: 0
        if (degrees == 0) return bitmap

        return runCatching {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }.getOrElse {
            // A rotation that fails is not worth losing the photo over; print it as decoded.
            Log.d(TAG, "Could not rotate $uri by $degrees degrees: ${it.message}")
            bitmap
        }
    }

    private fun exifOrientationDegrees(context: Context, uri: Uri): Int? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    }.getOrNull()

    private fun mediaStoreOrientationDegrees(context: Context, uri: Uri): Int? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.ORIENTATION),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        }
    }.getOrNull()

    /**
     * Guarantees a bitmap whose pixels can be read.
     *
     * `getPixels` throws on a HARDWARE bitmap, and returns surprises for exotic configurations, so
     * anything that is not plain software ARGB_8888 is copied. The copy is skipped when it is not
     * needed, which is the overwhelmingly common case.
     */
    fun toReadableSoftwareBitmap(bitmap: Bitmap): Bitmap? {
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        if (!isHardware && bitmap.config == Bitmap.Config.ARGB_8888) return bitmap

        return runCatching {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (copy != null && copy !== bitmap) bitmap.recycle()
            copy
        }.getOrElse {
            Log.w(TAG, "Could not convert a ${bitmap.config} bitmap to ARGB_8888", it)
            null
        }
    }
}
