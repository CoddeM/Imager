package com.rahul.imager.printer.raster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a gallery photo into a [RasterJob].
 *
 * The stages run in a fixed order — decode, rotate, flip, crop, resize, tone, dither, pack — and
 * the heavy per-pixel work is delegated to the pure [RasterCore] so it can be tested without a
 * device. This class owns only the parts that genuinely need Android: decoding a `Uri` and the
 * `Bitmap`/`Matrix` geometry.
 *
 * Everything runs on [Dispatchers.Default] (CPU-bound, not I/O-bound once the bytes are read) and
 * is cancellable: a slider drag in the preview screen cancels the previous raster mid-dither.
 */
class RasterPipeline(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Builds a job from a gallery photo.
     *
     * @param resolutionScale `1f` for the real thing; a smaller value renders a coarser preview
     *   while the user is dragging a slider. The output is still a valid, printable job — it is
     *   simply narrower, so the on-screen dots are honest at any scale.
     */
    suspend fun build(
        context: Context,
        uri: Uri,
        paper: PaperProfile,
        options: PrintOptions,
        resolutionScale: Float = 1f,
    ): RasterOutcome = withContext(dispatcher) {
        val opts = options.sanitized()
        if (!paper.supportsGraphics || paper.widthDots <= 0) {
            return@withContext RasterOutcome.Failure(
                PrintError(
                    PrintCategory.UNPRINTABLE,
                    detail = "Paper profile ${paper.id} has no graphics mode.",
                )
            )
        }

        val decoded = try {
            decode(context, uri, maxLongEdge = paper.widthDots * DECODE_LONG_EDGE_FACTOR)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory decoding $uri", e)
            return@withContext RasterOutcome.Failure(
                PrintError(PrintCategory.IMAGE_TOO_LARGE, detail = e.message)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode $uri", e)
            return@withContext RasterOutcome.Failure(
                PrintError(PrintCategory.IMAGE_DECODE_FAILED, cause = e, detail = e.message)
            )
        } ?: return@withContext RasterOutcome.Failure(
            PrintError(PrintCategory.IMAGE_DECODE_FAILED, detail = "Decoder returned no bitmap.")
        )

        buildFromBitmap(decoded, paper, opts, resolutionScale, recycleSource = true)
    }

    /**
     * Builds a job from a bitmap the caller already has.
     *
     * Used for the built-in test print, and for re-rasterizing an in-memory preview.
     *
     * @param recycleSource whether intermediate bitmaps derived from [source] may recycle it. The
     *   caller keeps ownership of [source] itself when this is false.
     */
    suspend fun buildFromBitmap(
        source: Bitmap,
        paper: PaperProfile,
        options: PrintOptions,
        resolutionScale: Float = 1f,
        recycleSource: Boolean = false,
    ): RasterOutcome = withContext(dispatcher) {
        val opts = options.sanitized()
        val ctx = currentCoroutineContext()
        val warnings = mutableListOf<RasterWarning>()

        var working = source
        var ownsWorking = recycleSource

        try {
            if (working.width <= 0 || working.height <= 0) {
                return@withContext RasterOutcome.Failure(PrintError(PrintCategory.IMAGE_EMPTY))
            }

            // --- 2. rotate / flip -----------------------------------------------------------
            val autoRotate = opts.autoRotateWideImages && shouldAutoRotate(working, paper)
            if (autoRotate) warnings += RasterWarning.ROTATION_SUGGESTED
            val totalRotation = (opts.rotationDegrees + if (autoRotate) 90 else 0) % 360
            if (totalRotation != 0 || opts.flipHorizontal || opts.flipVertical) {
                val next = transform(working, totalRotation, opts.flipHorizontal, opts.flipVertical)
                working = swap(working, next, ownsWorking).also { ownsWorking = true }
            }
            ctx.ensureActive()

            // --- 3. crop --------------------------------------------------------------------
            val cropped = applyCrop(working, opts, paper)
            if (cropped !== working) {
                working = swap(working, cropped, ownsWorking).also { ownsWorking = true }
            }
            ctx.ensureActive()

            // --- 4. resize ------------------------------------------------------------------
            val effectivePaperWidth = scaledPaperWidth(paper.widthDots, resolutionScale)
            var targetWidth = RasterCore.targetWidthDots(
                paperWidthDots = effectivePaperWidth,
                marginDots = (opts.marginDots * resolutionScale).roundToInt(),
                scalePercent = opts.scalePercent,
                sourceWidthDots = working.width,
            )
            if (working.width < effectivePaperWidth && opts.scalePercent == 100) {
                warnings += RasterWarning.SOURCE_NARROWER_THAN_PAPER
            }
            // FIT_WHOLE keeps the entire photo inside one paper-width box instead of running it
            // down the roll: if the fitted height would overflow the box, give up width instead.
            if (opts.fitMode == FitMode.FIT_WHOLE) {
                val boxHeight = (effectivePaperWidth * FIT_BOX_HEIGHT_FACTOR).roundToInt()
                val fittedHeight = (working.height.toLong() * targetWidth) / working.width
                if (fittedHeight > boxHeight) {
                    val shrunk = (targetWidth * boxHeight / fittedHeight).toInt()
                    targetWidth = (shrunk / 8 * 8).coerceAtLeast(8)
                }
            }
            val targetHeight = ((working.height.toLong() * targetWidth) / working.width)
                .toInt()
                .coerceAtLeast(1)
            if (targetHeight > MAX_HEIGHT_DOTS) {
                return@withContext RasterOutcome.Failure(
                    PrintError(
                        PrintCategory.IMAGE_TOO_LARGE,
                        detail = "$targetHeight rows exceeds the $MAX_HEIGHT_DOTS row limit.",
                    )
                )
            }
            if (targetHeight > LONG_PRINT_WARNING_DOTS) warnings += RasterWarning.VERY_LONG_PRINT

            val scaled = if (working.width == targetWidth && working.height == targetHeight) {
                working
            } else {
                Bitmap.createScaledBitmap(working, targetWidth, targetHeight, true)
            }
            if (scaled !== working) {
                working = swap(working, scaled, ownsWorking).also { ownsWorking = true }
            }
            ctx.ensureActive()

            // --- 5..7. tone, dither, pack ---------------------------------------------------
            val argb = IntArray(targetWidth * targetHeight)
            working.getPixels(argb, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            if (ownsWorking && working !== source) working.recycle()
            ownsWorking = false

            val gray = RasterCore.toGrayscale(argb, opts.grayscaleMode)
            ctx.ensureActive()
            val toned = RasterCore.applyTone(
                gray = gray,
                brightness = opts.brightness,
                contrast = opts.contrast,
                gamma = opts.gamma,
                invert = opts.invert,
            )
            ctx.ensureActive()

            val mono = RasterCore.dither(
                gray = toned,
                width = targetWidth,
                height = targetHeight,
                mode = opts.ditherMode,
                threshold = opts.threshold,
                onRow = { ctx.ensureActive() },
            )
            val packed = RasterCore.packRows(mono, targetWidth, targetHeight)
            ctx.ensureActive()

            // The preview honours NONE_GRAYSCALE by showing the un-dithered plane; the packed
            // bytes always describe real 1-bit dots so the job stays printable either way.
            val previewPixels = if (opts.ditherMode == DitherMode.NONE_GRAYSCALE) {
                RasterCore.grayToArgb(toned)
            } else {
                RasterCore.monoToArgb(mono, targetWidth, targetHeight)
            }
            val monoBitmap = Bitmap.createBitmap(
                previewPixels,
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )

            RasterOutcome.Success(
                job = RasterJob(
                    monoBitmap = monoBitmap,
                    packedRows = packed,
                    widthDots = targetWidth,
                    heightDots = targetHeight,
                    bytesPerRow = RasterCore.bytesPerRow(targetWidth),
                    paper = paper,
                    options = opts,
                ),
                warnings = warnings.distinct(),
            )
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory rasterizing", e)
            RasterOutcome.Failure(PrintError(PrintCategory.IMAGE_TOO_LARGE, detail = e.message))
        } finally {
            if (ownsWorking && working !== source) working.recycle()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------------------------

    /**
     * Decodes [uri] downsampled so a 50 MP gallery photo never allocates at full size.
     *
     * On API 28+ `ImageDecoder` both downsamples and applies EXIF orientation for us. Below that
     * we downsample with `inSampleSize` and read the orientation from `MediaStore`, which is the
     * only orientation source available without pulling in another library.
     */
    private fun decode(context: Context, uri: Uri, maxLongEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software allocation is required: a HARDWARE bitmap cannot be read with
                // getPixels(), and reading pixels is the entire point of this pipeline.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                decoder.setTargetSampleSize(
                    sampleSizeFor(max(info.size.width, info.size.height), maxLongEdge)
                )
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(longEdge, maxLongEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?: return null

            val degrees = mediaStoreOrientation(context, uri)
            if (degrees == 0) bitmap else transform(bitmap, degrees, false, false)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
    }

    /** Largest power-of-two sample size that keeps the long edge at or above [maxLongEdge]. */
    private fun sampleSizeFor(longEdge: Int, maxLongEdge: Int): Int {
        var sample = 1
        while (longEdge / (sample * 2) >= maxLongEdge && sample < MAX_SAMPLE_SIZE) sample *= 2
        return sample
    }

    /** EXIF orientation for API 24..27, via the MediaStore column. Returns degrees, or 0. */
    private fun mediaStoreOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.ORIENTATION),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0
        } ?: 0
    }.getOrElse {
        Log.d(TAG, "No MediaStore orientation for $uri: ${it.message}")
        0
    }

    // -------------------------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------------------------

    /** Applies rotation and mirroring in one matrix pass. */
    private fun transform(src: Bitmap, degrees: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        if (degrees == 0 && !flipH && !flipV) return src
        val matrix = Matrix().apply {
            if (degrees != 0) postRotate(degrees.toFloat())
            if (flipH || flipV) postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Applies the user crop and then the fit mode.
     *
     * [FitMode.FILL_CROP] centre-crops to the paper aspect; [FitMode.FIT_WHOLE] does not crop at
     * all (it is handled by the resize step limiting the height); the remaining modes crop only
     * what the user explicitly asked for.
     */
    private fun applyCrop(src: Bitmap, options: PrintOptions, paper: PaperProfile): Bitmap {
        var current = src
        options.crop?.sanitized()?.takeIf { !it.isFull }?.let { crop ->
            val x = (crop.left * current.width).roundToInt().coerceIn(0, current.width - 1)
            val y = (crop.top * current.height).roundToInt().coerceIn(0, current.height - 1)
            val w = ((crop.right - crop.left) * current.width).roundToInt()
                .coerceIn(1, current.width - x)
            val h = ((crop.bottom - crop.top) * current.height).roundToInt()
                .coerceIn(1, current.height - y)
            current = Bitmap.createBitmap(current, x, y, w, h)
        }

        if (options.fitMode == FitMode.FILL_CROP) {
            val targetAspect = 1f / FIT_BOX_HEIGHT_FACTOR
            val currentAspect = current.width.toFloat() / current.height
            val cropped = if (currentAspect > targetAspect) {
                val w = (current.height * targetAspect).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(current, (current.width - w) / 2, 0, w, current.height)
            } else {
                val h = (current.width / targetAspect).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(current, 0, (current.height - h) / 2, current.width, h)
            }
            if (cropped !== current && current !== src) current.recycle()
            current = cropped
        }
        return current
    }

    /**
     * The paper width a FIT_WHOLE job may use so the whole photo stays inside one screen-sized
     * box of paper. FIT_WHOLE trades width for a shorter print; every other mode uses the full
     * printable width.
     */
    private fun scaledPaperWidth(paperWidthDots: Int, resolutionScale: Float): Int =
        (paperWidthDots * resolutionScale.coerceIn(MIN_RESOLUTION_SCALE, 1f))
            .roundToInt()
            .coerceAtLeast(64)

    /**
     * True when the photo is landscape enough that printing it across a narrow roll would waste
     * most of the paper, so turning it 90 degrees is worth offering.
     *
     * This is only ever a SUGGESTION: the pipeline applies it because the user left the toggle on,
     * and the preview screen shows the toggle so it is never a silent surprise.
     */
    private fun shouldAutoRotate(bitmap: Bitmap, paper: PaperProfile): Boolean {
        if (bitmap.width <= bitmap.height) return false
        val aspect = bitmap.width.toFloat() / bitmap.height
        return aspect >= AUTO_ROTATE_MIN_ASPECT && paper.widthDots <= AUTO_ROTATE_MAX_PAPER_DOTS
    }

    /** Recycles [old] when this pipeline owns it, and returns [new]. */
    private fun swap(old: Bitmap, new: Bitmap, ownsOld: Boolean): Bitmap {
        if (ownsOld && old !== new && !old.isRecycled) old.recycle()
        return new
    }

    private companion object {
        const val TAG = "RasterPipeline"

        /** Decode no larger than this many times the paper width on the long edge. */
        const val DECODE_LONG_EDGE_FACTOR = 4

        const val MAX_SAMPLE_SIZE = 32

        /** Refuse anything longer than roughly 2.5 m of paper. */
        const val MAX_HEIGHT_DOTS = 20_000

        /** Warn beyond roughly 60 cm of paper. */
        const val LONG_PRINT_WARNING_DOTS = 4_800

        /** FIT_WHOLE / FILL_CROP treat the paper as a 2:3 portrait box. */
        const val FIT_BOX_HEIGHT_FACTOR = 1.5f

        const val MIN_RESOLUTION_SCALE = 0.1f

        const val AUTO_ROTATE_MIN_ASPECT = 1.3f
        const val AUTO_ROTATE_MAX_PAPER_DOTS = 576
    }
}
