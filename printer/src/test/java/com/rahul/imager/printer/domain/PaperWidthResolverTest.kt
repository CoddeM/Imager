package com.rahul.imager.printer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers every row of the paper-width table, and — more importantly — the ordering traps where a
 * specific rule only works because it shadows a broader one below it.
 */
class PaperWidthResolverTest {

    private fun resolve(model: String, transport: TransportType = TransportType.LAN) =
        PaperWidthResolver.resolve(model = model, transport = transport, hostDevice = "")

    // ---- wide format ---------------------------------------------------------------------

    @Test
    fun `wide format falls back to 80mm with a warning`() {
        val tup900 = resolve("Star TUP900")
        assertEquals(PaperProfiles.STANDARD_80MM, tup900.profile)
        assertNotNull("a wide-format downgrade must warn the user", tup900.warning)

        val t400i = resolve("SM-T400i")
        assertEquals(PaperProfiles.STANDARD_80MM, t400i.profile)
        assertNotNull(t400i.warning)
    }

    // ---- impact --------------------------------------------------------------------------

    @Test
    fun `impact heads resolve to the graphics-incapable profile`() {
        assertEquals(PaperProfiles.IMPACT_76MM, resolve("Star SP700").profile)
        assertEquals(PaperProfiles.IMPACT_76MM, resolve("EPSON TM-U220B").profile)
        assertEquals(false, resolve("Star SP700").profile.supportsGraphics)
    }

    // ---- Dejavoo -------------------------------------------------------------------------

    @Test
    fun `Dejavoo D1 Register is 80mm and must beat the generic Dejavoo rule`() {
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("D1 Register").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("Dejavoo D1").profile)
    }

    @Test
    fun `other Dejavoo and Kozen units use the built-in profile`() {
        assertEquals(PaperProfiles.DEJAVOO_PAT, resolve("Dejavoo P8").profile)
        assertEquals(PaperProfiles.DEJAVOO_PAT, resolve("KOZEN P18").profile)
        assertEquals(PaperProfiles.DEJAVOO_PAT, resolve("QD2 terminal").profile)
        assertEquals(false, resolve("Dejavoo P8").profile.supportsCutter)
    }

    // ---- Star ----------------------------------------------------------------------------

    @Test
    fun `Star 58mm models beat the generic Star catch-all`() {
        assertEquals(PaperProfiles.NARROW_58MM, resolve("mC-Print2").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("mPOP").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SM-S230i").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SM-L200").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SK1-211").profile)
    }

    @Test
    fun `Star 80mm models resolve to 80mm`() {
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("TSP100III").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("mC-Print3").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("TUP500").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("SK1-31").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("SK5").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("SM-T300i").profile)
    }

    @Test
    fun `an unknown Star still resolves through the generic rule`() {
        val resolution = resolve("Star Something New")
        assertEquals(PaperProfiles.STANDARD_80MM, resolution.profile)
        assertEquals("star-generic", resolution.matchedRule)
    }

    // ---- Epson ---------------------------------------------------------------------------

    @Test
    fun `Epson mobile units are 58mm and desktop units are 80mm`() {
        assertEquals(PaperProfiles.NARROW_58MM, resolve("TM-m10").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("TM-P20").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("TM-T88VI").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("TM-m30II").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("TM-P80").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("TM-L90").profile)
    }

    // ---- Sunmi ordering traps ------------------------------------------------------------

    @Test
    fun `T2 Mini is 58mm while plain T2 is 80mm`() {
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SUNMI T2 Mini").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("SUNMI T2").profile)
    }

    @Test
    fun `V2s Plus is 80mm while V2s is 58mm`() {
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("SUNMI V2s Plus").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SUNMI V2s").profile)
    }

    @Test
    fun `P3 Mix defaults to the narrower profile`() {
        // Paper-configurable hardware: narrow content prints fine on a wide head, wide content
        // gets clipped on a narrow one, so the safe default is 58 mm.
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SUNMI P3 MIX").profile)
    }

    @Test
    fun `other Sunmi models resolve as documented`() {
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SUNMI D2 Mini").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("SUNMI T3 Pro").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("SUNMI D2s").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("NT311").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SUNMI V1s").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("SUNMI P2 Pro").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("Sunmi Cloud Printer").profile)
    }

    // ---- Landi ---------------------------------------------------------------------------

    @Test
    fun `Landi models resolve by size class`() {
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("LANDI C10").profile)
        assertEquals(PaperProfiles.STANDARD_80MM, resolve("C20 Pro").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("LANDI A8").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("A10F").profile)
        assertEquals(PaperProfiles.NARROW_58MM, resolve("E550").profile)
    }

    // ---- fallbacks -----------------------------------------------------------------------

    @Test
    fun `an unmatched model falls back and says so`() {
        val resolution = resolve("Completely Unknown Printer 9000")
        assertEquals(PaperProfiles.STANDARD_80MM, resolution.profile)
        assertNull(resolution.matchedRule)
        assertEquals(PaperWidthResolution.MatchSource.FALLBACK, resolution.matchedOn)
        assertNotNull(resolution.warning)
    }

    @Test
    fun `a built-in printer with no model falls back to the host device`() {
        val resolution = PaperWidthResolver.resolve(
            model = null,
            displayName = "Built-in printer",
            transport = TransportType.INNER,
            hostDevice = "SUNMI V2 PRO",
        )
        assertEquals(PaperProfiles.NARROW_58MM, resolution.profile)
        assertEquals(PaperWidthResolution.MatchSource.HOST_DEVICE, resolution.matchedOn)
    }

    @Test
    fun `the host device is NOT consulted for network printers`() {
        // A LAN printer that failed to report its model is not the phone it is being printed from.
        val resolution = PaperWidthResolver.resolve(
            model = null,
            displayName = "Office printer",
            transport = TransportType.LAN,
            hostDevice = "SUNMI V2 PRO",
        )
        assertEquals(PaperWidthResolution.MatchSource.FALLBACK, resolution.matchedOn)
    }

    @Test
    fun `matching is case insensitive and spans model plus display name`() {
        val resolution = PaperWidthResolver.resolve(
            model = null,
            displayName = "kitchen tsp143",
            transport = TransportType.LAN,
            hostDevice = "",
        )
        assertEquals(PaperProfiles.STANDARD_80MM, resolution.profile)
        assertEquals("star-80", resolution.matchedRule)
    }

    @Test
    fun `a custom width is floored to a multiple of eight`() {
        assertEquals(504, PaperProfiles.custom(511).widthDots)
        assertEquals(PaperProfiles.MIN_CUSTOM_WIDTH_DOTS, PaperProfiles.custom(1).widthDots)
        assertEquals(PaperProfiles.MAX_CUSTOM_WIDTH_DOTS, PaperProfiles.custom(99_999).widthDots)
    }

    @Test
    fun `custom profile ids round-trip`() {
        val custom = PaperProfiles.custom(320)
        assertEquals(320, PaperProfiles.resolve(custom.id).widthDots)
        assertEquals(PaperProfiles.NARROW_58MM, PaperProfiles.resolve("58mm"))
        assertEquals(PaperProfiles.FALLBACK, PaperProfiles.resolve("nonsense"))
    }
}
