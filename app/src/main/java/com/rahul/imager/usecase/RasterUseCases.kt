package com.rahul.imager.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.printer.raster.RasterOutcome
import com.rahul.imager.printer.raster.RasterPipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a picked photo into printable dots.
 *
 * The preview screen and the print path both go through here, which is what makes the preview a
 * true WYSIWYG. Cancellation is the caller's job: the preview cancels the previous raster on every
 * slider change, and the pipeline is cancellable mid-dither.
 */
@Singleton
class BuildRasterUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pipeline: RasterPipeline,
) {
    /**
     * @param resolutionScale `1f` for the real thing, smaller for a fast preview while dragging.
     */
    suspend operator fun invoke(
        uri: Uri,
        paper: PaperProfile,
        options: PrintOptions,
        resolutionScale: Float = 1f,
    ): RasterOutcome = pipeline.build(context, uri, paper, options, resolutionScale)

    /** Rasterizes a bitmap the caller already holds, used for the built-in test print. */
    suspend operator fun invoke(
        bitmap: Bitmap,
        paper: PaperProfile,
        options: PrintOptions,
    ): RasterOutcome = pipeline.buildFromBitmap(bitmap, paper, options)
}

/**
 * Draws the built-in test slip.
 *
 * It is deliberately more than a black rectangle: it carries a gradient bar (so the user can see
 * how this printer renders midtones), a checkerboard (which exposes a sheared raster instantly)
 * and the printer name and paper width in plain text.
 */
@Singleton
class BuildTestPrintUseCase @Inject constructor() {

    operator fun invoke(printerName: String, paper: PaperProfile): Bitmap {
        val width = paper.widthDots.coerceAtLeast(MIN_WIDTH)
        val bitmap = Bitmap.createBitmap(width, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        // Title.
        paint.textSize = 34f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("ThermalPhoto", MARGIN, 48f, paint)

        // Printer identity and geometry.
        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(printerName.take(MAX_NAME_CHARS), MARGIN, 86f, paint)
        canvas.drawText("${paper.label} · ${paper.widthDots} dots", MARGIN, 118f, paint)

        // A full-width rule: if this prints short or slanted, the raster width is wrong.
        canvas.drawRect(MARGIN, 134f, width - MARGIN, 140f, paint)

        // Grey ramp: shows how this head renders midtones after dithering.
        val rampTop = 156f
        val rampBottom = 216f
        val steps = 16
        val stepWidth = (width - 2 * MARGIN) / steps
        for (step in 0 until steps) {
            val level = 255 - step * 255 / (steps - 1)
            paint.color = Color.rgb(level, level, level)
            canvas.drawRect(
                MARGIN + step * stepWidth,
                rampTop,
                MARGIN + (step + 1) * stepWidth,
                rampBottom,
                paint,
            )
        }

        // Checkerboard: any shear or off-by-one in the packing shows up as diagonal banding.
        paint.color = Color.BLACK
        val boardTop = 232f
        val cell = 8
        var y = boardTop.toInt()
        var row = 0
        while (y < boardTop + 64) {
            var x = MARGIN.toInt()
            var column = 0
            while (x < width - MARGIN) {
                if ((row + column) % 2 == 0) {
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + cell).toFloat(),
                        (y + cell).toFloat(),
                        paint,
                    )
                }
                x += cell
                column++
            }
            y += cell
            row++
        }

        paint.textSize = 22f
        canvas.drawText("Test print", MARGIN, 330f, paint)

        return bitmap
    }

    private companion object {
        const val HEIGHT = 352
        const val MIN_WIDTH = 384
        const val MARGIN = 16f
        const val MAX_NAME_CHARS = 28
    }
}
