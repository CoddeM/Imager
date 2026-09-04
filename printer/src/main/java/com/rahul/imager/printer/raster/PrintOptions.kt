package com.rahul.imager.printer.raster

import kotlinx.serialization.Serializable

/** How the source photo is fitted onto the paper width. */
@Serializable
enum class FitMode {
    /** Scale so the image spans the full printable width; height follows the aspect ratio. */
    FIT_WIDTH,

    /** Scale so the whole image fits inside a paper-width square-ish box; never crops. */
    FIT_WHOLE,

    /** Centre-crop to the target aspect, then scale. Fills the paper edge to edge. */
    FILL_CROP,

    /** Keep the source pixels 1:1, capped at the paper width. Never upscales. */
    ORIGINAL_CAPPED,
}

/** How colour is collapsed to a single 8-bit grey plane. */
@Serializable
enum class GrayscaleMode {
    /** `0.299R + 0.587G + 0.114B` — perceptually correct, the default for photos. */
    LUMINANCE,

    /** `(R + G + B) / 3` — flat average; matches the output of legacy POS firmware. */
    AVERAGE,
}

/** How the 8-bit grey plane is reduced to the 1 bit per dot the head actually prints. */
@Serializable
enum class DitherMode {
    /** Classic error diffusion. Best general-purpose choice for photographs. */
    FLOYD_STEINBERG,

    /** Diffuses only 6/8 of the error; lighter and punchier, flattering on faces. */
    ATKINSON,

    /** 8x8 ordered Bayer matrix; fast, uniform texture, good for flat graphics. */
    ORDERED_BAYER_8,

    /** Hard cut at the threshold. For line art, logos and screenshots of text. */
    THRESHOLD,

    /** No reduction at all. PREVIEW ONLY — never sent to a printer. */
    NONE_GRAYSCALE,
}

/** Horizontal placement of the raster within the printable width. */
@Serializable
enum class Alignment { LEFT, CENTER, RIGHT }

/**
 * A crop expressed in NORMALIZED source coordinates, so it survives the photo being decoded at a
 * different sample size than the one the user dragged the handles on.
 *
 * All four values are in `0f..1f` with `left < right` and `top < bottom`.
 */
@Serializable
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    /** True when this crop keeps the whole image, i.e. there is nothing to apply. */
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** Clamps into range and guarantees a non-empty rectangle. */
    fun sanitized(): CropRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        return CropRect(
            left = minOf(l, r),
            top = minOf(t, b),
            right = maxOf(l, r).coerceAtLeast(minOf(l, r) + MIN_SIZE),
            bottom = maxOf(t, b).coerceAtLeast(minOf(t, b) + MIN_SIZE),
        )
    }

    companion object {
        /** Smallest crop the UI allows, so a stray gesture cannot produce a zero-pixel image. */
        const val MIN_SIZE = 0.02f

        val FULL = CropRect()
    }
}

/**
 * Everything the user can tune about how a photo is turned into dots.
 *
 * The defaults are the "Photo" preset: fit the width, Floyd-Steinberg, no tone changes.
 */
@Serializable
data class PrintOptions(
    val fitMode: FitMode = FitMode.FIT_WIDTH,
    /** 0 / 90 / 180 / 270, applied before cropping. */
    val rotationDegrees: Int = 0,
    /** Offer (never silently apply) a 90 degree turn when a landscape photo would print tiny. */
    val autoRotateWideImages: Boolean = true,
    /** 10..100, applied after the fit so the user can deliberately print smaller than the paper. */
    val scalePercent: Int = 100,
    /** -100..+100, additive on the 0..255 grey plane. */
    val brightness: Float = 0f,
    /** -100..+100, multiplicative around a pivot of 128. */
    val contrast: Float = 0f,
    /**
     * 0.4..2.5. This is the honest "Darkness" control: it reshapes the tone curve before
     * dithering. It is NOT an ESC/POS density command, because no such standard command exists.
     *
     * The curve is `255 * (v/255)^(1/gamma)`, so a SMALLER gamma prints darker and a larger one
     * prints lighter. The Darkness slider in the UI is presented the other way round (right is
     * darker) and inverts on the way in.
     */
    val gamma: Float = 1.0f,
    val grayscaleMode: GrayscaleMode = GrayscaleMode.LUMINANCE,
    val ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    /** 1..254. The hard cut for THRESHOLD mode, and the pivot for every diffusion mode. */
    val threshold: Int = 128,
    val invert: Boolean = false,
    val alignment: Alignment = Alignment.CENTER,
    /** Blank dots kept on each side of the raster. */
    val marginDots: Int = 0,
    val feedLinesAfter: Int = 3,
    val cutAfter: Boolean = true,
    val copies: Int = 1,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** Interactive crop, in normalized source coordinates. `null` means "keep everything". */
    val crop: CropRect? = null,
) {
    /** Clamps every numeric field into its documented range. */
    fun sanitized(): PrintOptions = copy(
        rotationDegrees = ((rotationDegrees % 360) + 360) % 360 / 90 * 90,
        scalePercent = scalePercent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT),
        brightness = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        contrast = contrast.coerceIn(MIN_CONTRAST, MAX_CONTRAST),
        gamma = gamma.coerceIn(MIN_GAMMA, MAX_GAMMA),
        threshold = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
        marginDots = marginDots.coerceAtLeast(0),
        feedLinesAfter = feedLinesAfter.coerceIn(0, MAX_FEED_LINES),
        copies = copies.coerceIn(1, MAX_COPIES),
        crop = crop?.sanitized()?.takeIf { !it.isFull },
    )

    companion object {
        const val MIN_SCALE_PERCENT = 10
        const val MAX_SCALE_PERCENT = 100
        const val MIN_BRIGHTNESS = -100f
        const val MAX_BRIGHTNESS = 100f
        const val MIN_CONTRAST = -100f
        const val MAX_CONTRAST = 100f
        const val MIN_GAMMA = 0.4f
        const val MAX_GAMMA = 2.5f
        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 254
        const val MAX_FEED_LINES = 20
        const val MAX_COPIES = 10
    }
}

/**
 * The coherent option bundles offered as one-tap presets in the preview screen.
 *
 * [CUSTOM] is not a bundle; it is what the UI shows once the user has moved a slider away from
 * whichever preset they last picked.
 */
enum class PrintPreset {
    PHOTO,
    HIGH_CONTRAST,
    LINE_ART,
    DOCUMENT,
    CUSTOM;

    /** Applies this preset on top of [base], leaving geometry (fit, rotation, copies) alone. */
    fun applyTo(base: PrintOptions): PrintOptions = when (this) {
        PHOTO -> base.copy(
            ditherMode = DitherMode.FLOYD_STEINBERG,
            grayscaleMode = GrayscaleMode.LUMINANCE,
            brightness = 0f,
            contrast = 0f,
            gamma = 1.0f,
            threshold = 128,
            invert = false,
        )

        HIGH_CONTRAST -> base.copy(
            ditherMode = DitherMode.ATKINSON,
            grayscaleMode = GrayscaleMode.LUMINANCE,
            brightness = 4f,
            contrast = 28f,
            gamma = 0.9f,
            threshold = 128,
            invert = false,
        )

        LINE_ART -> base.copy(
            ditherMode = DitherMode.THRESHOLD,
            grayscaleMode = GrayscaleMode.LUMINANCE,
            brightness = 0f,
            contrast = 45f,
            gamma = 1.0f,
            threshold = 150,
            invert = false,
        )

        DOCUMENT -> base.copy(
            ditherMode = DitherMode.ORDERED_BAYER_8,
            grayscaleMode = GrayscaleMode.AVERAGE,
            brightness = 10f,
            contrast = 35f,
            gamma = 1.2f,
            threshold = 140,
            invert = false,
        )

        CUSTOM -> base
    }

    companion object {
        /** Reports which preset [options] currently corresponds to, or [CUSTOM] if none. */
        fun of(options: PrintOptions): PrintPreset =
            entries.firstOrNull { it != CUSTOM && it.applyTo(options) == options } ?: CUSTOM
    }
}
