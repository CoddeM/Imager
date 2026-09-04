package com.rahul.imager.printer.domain

/**
 * The printable geometry of one kind of receipt paper on one kind of head.
 *
 * Thermal heads in this class of hardware are 203 dpi, i.e. exactly 8 dots per millimetre, so the
 * dot counts below are simply the printable width in millimetres times eight.
 *
 * @param id stable identifier persisted on [SavedPrinter.paperProfileId].
 * @param label short English label. The UI resolves a localized string from [id] and only falls
 *   back to this when a profile has no translation yet.
 * @param widthDots printable raster width in dots. Zero means "this head cannot raster at all".
 * @param supportsGraphics false for impact / dot-matrix heads, which physically cannot print an
 *   image. The Print button is disabled for those.
 * @param supportsCutter whether a cut command is worth emitting at the end of a job.
 */
data class PaperProfile(
    val id: String,
    val label: String,
    val widthDots: Int,
    val supportsGraphics: Boolean,
    val supportsCutter: Boolean,
) {
    /** Printable width in millimetres at 203 dpi (8 dots/mm), for display only. */
    val widthMm: Int get() = widthDots / 8
}

/**
 * The paper profiles this app supports.
 *
 * 112 mm wide-format paper is deliberately absent: those heads need a different raster path than
 * the one implemented here, so [PaperWidthResolver] warns and falls back to 80 mm rather than
 * pretending to support them.
 */
object PaperProfiles {

    /** 58 mm roll — 384 dots. The common small/portable head. */
    val NARROW_58MM = PaperProfile(
        id = "58mm",
        label = "58 mm",
        widthDots = 384,
        supportsGraphics = true,
        supportsCutter = true,
    )

    /** 80 mm roll — 576 dots. The standard desktop receipt head, and the global fallback. */
    val STANDARD_80MM = PaperProfile(
        id = "80mm",
        label = "80 mm",
        widthDots = 576,
        supportsGraphics = true,
        supportsCutter = true,
    )

    /**
     * Dejavoo / Kozen built-in head: 384 dots over a ~48 mm printable width, and no cutter — the
     * user tears the slip off by hand.
     */
    val DEJAVOO_PAT = PaperProfile(
        id = "pat",
        label = "Dejavoo built-in",
        widthDots = 384,
        supportsGraphics = true,
        supportsCutter = false,
    )

    /**
     * 76 mm impact / dot-matrix head (Star SP700, Epson TM-U220). These print by hammering a
     * ribbon and have NO graphics mode at all, hence [PaperProfile.widthDots] `= 0` and
     * [PaperProfile.supportsGraphics] `= false`.
     */
    val IMPACT_76MM = PaperProfile(
        id = "76mm",
        label = "76 mm impact",
        widthDots = 0,
        supportsGraphics = false,
        supportsCutter = true,
    )

    /** Every profile the user may choose between. */
    val ALL: List<PaperProfile> = listOf(NARROW_58MM, STANDARD_80MM, DEJAVOO_PAT, IMPACT_76MM)

    /** Profiles that can actually print a photo, i.e. the ones offered as a manual override. */
    val GRAPHICS_CAPABLE: List<PaperProfile> = ALL.filter { it.supportsGraphics }

    /** The profile used when nothing else matches. */
    val FALLBACK: PaperProfile = STANDARD_80MM

    /** Resolves a persisted [PaperProfile.id] back to a profile, falling back to 80 mm. */
    fun byId(id: String?): PaperProfile = ALL.firstOrNull { it.id == id } ?: FALLBACK

    /**
     * Builds an ad-hoc profile for a user-entered custom dot width.
     *
     * The width is floored to a multiple of 8 because the raster commands are byte-packed per row
     * (see EscPosEncoder), and clamped to a range real hardware can accept.
     */
    fun custom(widthDots: Int): PaperProfile {
        val floored = (widthDots / 8 * 8).coerceIn(MIN_CUSTOM_WIDTH_DOTS, MAX_CUSTOM_WIDTH_DOTS)
        return PaperProfile(
            id = "custom-$floored",
            label = "$floored dots",
            widthDots = floored,
            supportsGraphics = true,
            supportsCutter = true,
        )
    }

    /** Narrowest custom width that still produces a recognisable photo. */
    const val MIN_CUSTOM_WIDTH_DOTS = 128

    /** Widest custom width; beyond this the head is a wide-format unit this app does not drive. */
    const val MAX_CUSTOM_WIDTH_DOTS = 832

    /** True when [id] denotes a user-entered custom width rather than one of [ALL]. */
    fun isCustomId(id: String?): Boolean = id != null && id.startsWith("custom-")

    /** Resolves any persisted id, including the `custom-<dots>` form. */
    fun resolve(id: String?): PaperProfile = when {
        isCustomId(id) -> id!!.removePrefix("custom-").toIntOrNull()?.let { custom(it) } ?: FALLBACK
        else -> byId(id)
    }
}
