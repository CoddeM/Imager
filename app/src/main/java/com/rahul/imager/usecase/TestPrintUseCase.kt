package com.rahul.imager.usecase

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.engine.PrintAttemptResult
import com.rahul.imager.printer.engine.PrintEngine
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.printer.raster.RasterOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prints the built-in test slip.
 *
 * The add-printer flow ends here for a reason: a printer that has been saved without ever printing
 * anything is a printer the user will discover is broken at the worst possible moment. Skipping
 * the test is allowed, but it is an explicit choice.
 */
@Singleton
class TestPrintUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val buildTestPrint: BuildTestPrintUseCase,
    private val buildRaster: BuildRasterUseCase,
    private val engine: PrintEngine,
) {

    suspend operator fun invoke(printer: SavedPrinter): PrintAttemptResult {
        val paper = PaperProfiles.resolve(printer.paperProfileId)
        if (!paper.supportsGraphics) {
            return PrintAttemptResult(
                success = false,
                error = PrintError(
                    PrintCategory.UNPRINTABLE,
                    detail = "${paper.label} has no graphics mode.",
                ),
            )
        }

        val bitmap = buildTestPrint(printer.displayName, paper)
        val options = PrintOptions(
            feedLinesAfter = TEST_FEED_LINES,
            cutAfter = paper.supportsCutter,
        )

        return when (val outcome = buildRaster(bitmap, paper, options)) {
            is RasterOutcome.Failure -> PrintAttemptResult(success = false, error = outcome.error)

            is RasterOutcome.Success -> {
                var result: PrintAttemptResult? = null
                engine.print(
                    job = outcome.job,
                    target = printer,
                    context = context,
                    onResult = { result = it },
                )
                result ?: PrintAttemptResult(
                    success = false,
                    error = PrintError(PrintCategory.UNKNOWN),
                )
            }
        }
    }

    private companion object {
        const val TEST_FEED_LINES = 4
    }
}

/**
 * Everything the app needs to do with a picked photo URI.
 *
 * The primary path is the system Photo Picker (`ActivityResultContracts.PickVisualMedia`), which
 * needs NO runtime permission on any API level — the user chooses the photo in a system UI and the
 * app receives a grant for exactly that item. The in-app browser is a secondary convenience and is
 * the only thing that needs `READ_MEDIA_IMAGES`.
 */
@Singleton
class PickPhotoUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Takes a persistable read grant, so a photo in the recents list still opens tomorrow.
     *
     * A one-shot grant from the photo picker dies with the process, which would turn every recents
     * entry into a broken thumbnail after a restart. Not every URI supports a persistable grant,
     * so a failure here is logged and ignored rather than surfaced.
     */
    fun persistAccess(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure {
            Log.d(TAG, "No persistable grant available for $uri: ${it.message}")
        }
    }

    /** True when the app can still read [uri]. */
    fun canRead(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    /** The content resolver, for callers that need to load a thumbnail. */
    val contentResolver: ContentResolver get() = context.contentResolver

    private companion object {
        const val TAG = "PickPhotoUseCase"
    }
}
