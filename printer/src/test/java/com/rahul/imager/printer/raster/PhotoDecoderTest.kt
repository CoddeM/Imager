package com.rahul.imager.printer.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizing decisions behind the decode ladder.
 *
 * The decoders themselves need a device, but the arithmetic that decides how far to downsample —
 * and, crucially, what to do when the image's size is unknown — is pure and is where a bad choice
 * turns into either an out-of-memory crash or a needlessly soft print.
 */
class PhotoDecoderTest {

    @Test
    fun `sample size keeps the long edge at or above the target`() {
        // A 12 MP phone photo onto an 80 mm head: 4032 down to 2016 still clears 2304? No — so it
        // stays at 1, because sampling further would drop below the requested size.
        assertEquals(1, PhotoDecoder.sampleSizeFor(longEdge = 4032, maxLongEdge = 2304))
        assertEquals(2, PhotoDecoder.sampleSizeFor(longEdge = 4608, maxLongEdge = 2304))
        assertEquals(4, PhotoDecoder.sampleSizeFor(longEdge = 9216, maxLongEdge = 2304))
    }

    @Test
    fun `sample size is always a power of two`() {
        for (longEdge in listOf(640, 1080, 4032, 8000, 12000, 30000, 100000)) {
            val sample = PhotoDecoder.sampleSizeFor(longEdge, maxLongEdge = 2304)
            assertTrue(
                "sample $sample for long edge $longEdge is not a power of two",
                sample > 0 && (sample and (sample - 1)) == 0,
            )
        }
    }

    @Test
    fun `sample size never exceeds the ladder ceiling`() {
        val sample = PhotoDecoder.sampleSizeFor(longEdge = 10_000_000, maxLongEdge = 8)
        assertTrue(sample <= PhotoDecoder.MAX_SAMPLE_SIZE)
    }

    @Test
    fun `a small photo is never sampled down`() {
        assertEquals(1, PhotoDecoder.sampleSizeFor(longEdge = 800, maxLongEdge = 2304))
        assertEquals(1, PhotoDecoder.sampleSizeFor(longEdge = 1, maxLongEdge = 2304))
    }

    @Test
    fun `nonsense dimensions degrade to no sampling rather than dividing by zero`() {
        assertEquals(1, PhotoDecoder.sampleSizeFor(longEdge = 0, maxLongEdge = 2304))
        assertEquals(1, PhotoDecoder.sampleSizeFor(longEdge = -5, maxLongEdge = 2304))
        assertEquals(1, PhotoDecoder.sampleSizeFor(longEdge = 4032, maxLongEdge = 0))
    }

    @Test
    fun `unknown bounds assume a large photo`() {
        // The bounds pass fails for some providers. Guessing "small" there would try to decode a
        // 100 MP file at full size and take the app down with it, so the guess is deliberately
        // conservative: assume big, and let the ladder climb back down if it was wrong.
        val unknown = PhotoDecoder.initialSampleSize(bounds = null, maxLongEdge = 2304)
        assertTrue("unknown bounds must not decode at full size", unknown > 1)
    }

    @Test
    fun `known bounds use the real long edge, whichever way round the photo is`() {
        val landscape = PhotoDecoder.initialSampleSize(8000 to 6000, maxLongEdge = 2304)
        val portrait = PhotoDecoder.initialSampleSize(6000 to 8000, maxLongEdge = 2304)
        assertEquals("orientation must not change the sample size", landscape, portrait)
        assertEquals(PhotoDecoder.sampleSizeFor(8000, 2304), landscape)
    }
}
