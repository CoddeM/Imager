package com.rahul.imager.printer.domain

import android.os.Build

/**
 * The outcome of resolving a paper profile for a printer.
 *
 * @param profile the profile to use. Always usable — even the unsupported-hardware branches
 *   return the fallback rather than nothing, so the caller never has to handle a null.
 * @param matchedRule the id of the table row that matched, for logs and the diagnostics screen.
 * @param matchedOn which piece of evidence the rule matched against.
 * @param warning a human-readable warning when the resolution is a compromise (wide-format head
 *   downgraded to 80 mm, or nothing matched at all).
 */
data class PaperWidthResolution(
    val profile: PaperProfile,
    val matchedRule: String?,
    val matchedOn: MatchSource,
    val warning: String? = null,
) {
    /** Where the evidence that produced this resolution came from. */
    enum class MatchSource { MODEL, HOST_DEVICE, FALLBACK }
}

private typealias MatchSource = PaperWidthResolution.MatchSource

/**
 * Resolves the paper geometry of a printer from its model / product string.
 *
 * This is an ORDERED table of explicit patterns, first match wins — never a loose substring
 * heuristic. The ordering carries real meaning and several rows exist only to shadow a later,
 * broader row:
 *
 *  * `T2 Mini` (58 mm) must be tested before the generic Sunmi `T2` row (80 mm).
 *  * `V2s Plus` (80 mm) must be tested before `V2s` (58 mm).
 *  * The specific Star 58 mm models must be tested before the catch-all `star` row.
 *  * `P3 Mix` ships with either paper width; it defaults to the NARROWER 58 mm, because narrow
 *    content prints perfectly well on a wide head while wide content gets clipped on a narrow one.
 *
 * The result is only ever a DEFAULT. The user can override the paper width per printer, and per
 * job in the preview screen, and that override is what gets persisted.
 */
object PaperWidthResolver {

    /** What a matching rule resolves to. */
    private sealed interface Outcome {
        /** Use this profile. */
        data class Use(val profile: PaperProfile) : Outcome

        /**
         * The model is a wide-format (112 mm class) head this app explicitly does not drive.
         * Fall back to 80 mm and warn, rather than silently producing a clipped print.
         */
        data object UnsupportedWideFormat : Outcome
    }

    private class Rule(val id: String, pattern: String, val outcome: Outcome) {
        val regex: Regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private val use58 = Outcome.Use(PaperProfiles.NARROW_58MM)
    private val use80 = Outcome.Use(PaperProfiles.STANDARD_80MM)
    private val useImpact = Outcome.Use(PaperProfiles.IMPACT_76MM)
    private val usePat = Outcome.Use(PaperProfiles.DEJAVOO_PAT)

    /**
     * The rule table. ORDER IS PART OF THE CONTRACT — see the class KDoc before reordering.
     */
    private val RULES: List<Rule> = listOf(
        // Wide-format heads this app does not support at all.
        Rule("wide-format", """tup900|sm-?t400i""", Outcome.UnsupportedWideFormat),

        // Impact / dot-matrix heads: no graphics mode whatsoever.
        Rule("impact", """sp700|tm-?u220""", useImpact),

        // Dejavoo D1 Register is a full 80 mm desktop unit, unlike the rest of the Dejavoo range,
        // so it has to be matched before the generic Dejavoo/Kozen row below.
        Rule("dejavoo-d1", """d1\s*register|dejavoo.*\bd1\b""", use80),
        Rule("dejavoo-builtin", """dejavoo|kozen|\bqd[1-4]\b""", usePat),

        // Star 58 mm models, ahead of the generic Star rows.
        Rule("star-58", """mc-?print2|\bmpop\b|sm-?s\d|sm-?l\d|sk1-?211""", use58),
        Rule("star-80", """tsp\d|mc-?print3|tup500|sk1-?3|\bsk5\b|sm-?t300i""", use80),

        // Epson: the two 58 mm mobile units first, then the 80 mm desktop range.
        Rule("epson-58", """tm-?m10|tm-?p20""", use58),
        Rule(
            "epson-80",
            """tm-?t(88|20|82|70)|tm-?m(30|50)|tm-?p(80|60)|tm-?h6000|tm-?l90""",
            use80,
        ),

        // Sunmi: the "mini" variants are 58 mm and MUST precede their 80 mm base models.
        // P3 Mix is paper-configurable and deliberately defaults to the narrower profile.
        Rule("sunmi-mini-58", """t2[\s_-]*mini|d2[\s_-]*mini|p3[\s_-]*mix""", use58),
        Rule(
            "sunmi-80",
            """v2s[\s_-]*plus|\bt1\b|t1[\s_-]*mini|\bt2s?\b|\bt3\b|t3[\s_-]*pro""" +
                """|d2s|d3[\s_-]*mini|d3[\s_-]*pro|\bs2\b|s2[\s_-]*lite""" +
                """|\bk2\b|k2[\s_-]*mini|\bnt\d{3}\b""",
            use80,
        ),
        Rule(
            "sunmi-58",
            """\bv1s?\b|\bv2s?\b|v2[\s_-]*pro|v3[\s_-]*mix|\bv3h\b""" +
                """|\bp1\b|\bp2\b|p2[\s_-]*pro|p2[\s_-]*smartpad|\bp3\b""",
            use58,
        ),
        Rule("sunmi-cloud", """sunmi cloud|sunmicloud|cloudprint""", use80),

        // Landi.
        Rule("landi-80", """\bc10\b|\bc20\b|c20 pro""", use80),
        Rule(
            "landi-58",
            """\ba8s?\b|\ba9\b|\ba10f?\b|\bm20\b|e550|e750|\bv5s\b|\bv6\b|\bk9\b""",
            use58,
        ),

        // Broad brand catches, last so every specific row above wins.
        Rule("star-generic", """star|tsp|mc-?print""", use80),
        Rule("epson-generic", """epson|\btm-""", use80),
    )

    /**
     * Resolves the paper profile for a printer.
     *
     * @param model the raw model / product string captured when the printer was added.
     * @param displayName the printer's name; matched alongside the model because discovery on some
     *   transports only ever yields a name.
     * @param transport used to decide whether the HOST device is a sensible fallback subject: a
     *   built-in ([TransportType.INNER]) head with no model of its own IS the host device.
     * @param hostDevice manufacturer + model of the phone/terminal the app runs on. Injectable so
     *   this object stays testable on a host JVM, where [Build] fields are null.
     * @param fallback profile used when nothing matches, and when a wide-format head is detected.
     */
    fun resolve(
        model: String?,
        displayName: String? = null,
        transport: TransportType = TransportType.LAN,
        hostDevice: String = defaultHostDevice(),
        fallback: PaperProfile = PaperProfiles.FALLBACK,
    ): PaperWidthResolution {
        val subject = listOfNotNull(model, displayName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        match(subject, MatchSource.MODEL, fallback)?.let { return it }

        // A built-in printer often has no model string of its own — the terminal it is welded into
        // is the model. Only fall back to the host device for INNER, never for a network printer
        // that merely failed to answer its model query.
        if (transport == TransportType.INNER) {
            match(hostDevice, MatchSource.HOST_DEVICE, fallback)?.let { return it }
        }

        return PaperWidthResolution(
            profile = fallback,
            matchedRule = null,
            matchedOn = MatchSource.FALLBACK,
            warning = "No paper-width rule matched \"$subject\"; defaulting to ${fallback.label}.",
        )
    }

    private fun match(
        subject: String,
        source: MatchSource,
        fallback: PaperProfile,
    ): PaperWidthResolution? {
        if (subject.isBlank()) return null
        val rule = RULES.firstOrNull { it.regex.containsMatchIn(subject) } ?: return null
        return when (val outcome = rule.outcome) {
            is Outcome.Use -> PaperWidthResolution(outcome.profile, rule.id, source)
            Outcome.UnsupportedWideFormat -> PaperWidthResolution(
                profile = fallback,
                matchedRule = rule.id,
                matchedOn = source,
                warning = "\"$subject\" is a wide-format (112 mm) printer, which this app does " +
                    "not support; falling back to ${fallback.label}. Prints will be narrower " +
                    "than the paper.",
            )
        }
    }

    /** Manufacturer + model of the device the app is running on. Empty on a host JVM. */
    fun defaultHostDevice(): String =
        listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim()
}
