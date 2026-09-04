package com.rahul.imager.printer.escpos

import com.rahul.imager.printer.raster.Alignment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level checks on the ESC/POS vocabulary.
 *
 * The little-endian header fields in particular are easy to get backwards, and the symptom is not
 * an error but a printer that emits several metres of noise.
 */
class EscPosEncoderTest {

    @Test
    fun `GS v 0 header carries little-endian width and height`() {
        // 72 bytes per row (576 dots) and 300 rows.
        val header = EscPos.rasterHeader(bytesPerRow = 72, rows = 300)
        assertArrayEquals(
            byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,
                72, 0,               // xL, xH
                44, 1,               // yL, yH  (300 = 0x012C)
            ),
            header,
        )
    }

    @Test
    fun `header handles values above one byte on both axes`() {
        val header = EscPos.rasterHeader(bytesPerRow = 0x0102, rows = 0x0304)
        assertEquals(0x02.toByte(), header[4])
        assertEquals(0x01.toByte(), header[5])
        assertEquals(0x04.toByte(), header[6])
        assertEquals(0x03.toByte(), header[7])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-row band is rejected rather than sent`() {
        EscPos.rasterHeader(bytesPerRow = 48, rows = 0)
    }

    @Test
    fun `a band is the header followed by exactly the packed payload`() {
        val payload = ByteArray(4 * 2) { it.toByte() }
        val band = EscPos.rasterBand(bytesPerRow = 4, rows = 2, packed = payload)
        assertEquals(8 + payload.size, band.size)
        assertArrayEquals(EscPos.rasterHeader(4, 2), band.copyOfRange(0, 8))
        assertArrayEquals(payload, band.copyOfRange(8, band.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a payload that does not match the header is rejected`() {
        EscPos.rasterBand(bytesPerRow = 4, rows = 2, packed = ByteArray(7))
    }

    @Test
    fun `control sequences are the documented bytes`() {
        assertArrayEquals(byteArrayOf(0x1B, 0x40), EscPos.INITIALIZE)
        assertArrayEquals(byteArrayOf(0x1D, 0x56, 0x01), EscPos.PARTIAL_CUT)
        assertArrayEquals(byteArrayOf(0x1B, 0x61, 0), EscPos.align(Alignment.LEFT))
        assertArrayEquals(byteArrayOf(0x1B, 0x61, 1), EscPos.align(Alignment.CENTER))
        assertArrayEquals(byteArrayOf(0x1B, 0x61, 2), EscPos.align(Alignment.RIGHT))
        assertArrayEquals(byteArrayOf(0x1B, 0x64, 3), EscPos.feedLines(3))
        assertArrayEquals(byteArrayOf(0x10, 0x04, 1), EscPos.statusQuery(EscPos.STATUS_PRINTER))
        assertArrayEquals(byteArrayOf(0x10, 0x04, 4), EscPos.statusQuery(EscPos.STATUS_PAPER_SENSOR))
    }

    @Test
    fun `feed lines are clamped into a single byte`() {
        assertEquals(0.toByte(), EscPos.feedLines(-5)[2])
        assertEquals(255.toByte(), EscPos.feedLines(9_999)[2])
    }

    // ---- status replies -------------------------------------------------------------------

    @Test
    fun `printer status decodes the offline bit`() {
        assertTrue(EscPos.decodePrinterStatus(0x08).offline)
        assertTrue(!EscPos.decodePrinterStatus(0x12).offline)
        assertTrue(EscPos.decodePrinterStatus(0x40).feedButtonPressed)
    }

    @Test
    fun `paper sensor needs both bits of a pair to report a fault`() {
        assertTrue(EscPos.decodePaperStatus(0x60).paperOut)
        assertTrue(!EscPos.decodePaperStatus(0x20).paperOut)
        assertTrue(EscPos.decodePaperStatus(0x0C).paperNearEnd)
        assertTrue(!EscPos.decodePaperStatus(0x04).paperNearEnd)
    }

    @Test
    fun `offline cause decodes the cover and error bits`() {
        assertTrue(EscPos.decodeOfflineCause(0x04).coverOpen)
        assertTrue(EscPos.decodeOfflineCause(0x20).paperEndStop)
        assertTrue(EscPos.decodeOfflineCause(0x40).errorOccurred)
    }
}
