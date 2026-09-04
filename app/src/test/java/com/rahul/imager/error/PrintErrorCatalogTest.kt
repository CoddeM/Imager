package com.rahul.imager.error

import com.rahul.imager.printer.domain.PrintCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error catalog has to be TOTAL: every failure the print path can produce must reach the user
 * as words they can act on, not as a blank dialog or a raw enum name.
 */
class PrintErrorCatalogTest {

    @Test
    fun `every category has a title, a message and a recovery action`() {
        PrintCategory.entries.forEach { category ->
            val presentation = PrintErrorCatalog.presentationFor(category)
            assertNotEquals("$category has no title", 0, presentation.titleRes)
            assertNotEquals("$category has no message", 0, presentation.messageRes)
            assertNotEquals("$category has no recovery action", 0, presentation.actionRes)
        }
    }

    @Test
    fun `every category presents its own stable code`() {
        PrintCategory.entries.forEach { category ->
            assertEquals(category.code, PrintErrorCatalog.presentationFor(category).code)
        }
    }

    @Test
    fun `no two categories share a code`() {
        val codes = PrintCategory.entries.map { it.code }
        assertEquals(
            "duplicate error codes would make a support report ambiguous",
            codes.size,
            codes.toSet().size,
        )
    }

    @Test
    fun `every code follows the PRN prefix convention`() {
        PrintCategory.entries.forEach { category ->
            assertTrue(
                "${category.name} has code ${category.code}",
                category.code.startsWith("PRN-"),
            )
        }
    }

    @Test
    fun `no two categories share a title, message and action triple`() {
        // Distinct failures that read identically are worse than useless: the user cannot tell
        // which one they hit, and support cannot either.
        val triples = PrintCategory.entries.map { category ->
            val presentation = PrintErrorCatalog.presentationFor(category)
            Triple(presentation.titleRes, presentation.messageRes, presentation.actionRes)
        }
        assertEquals(triples.size, triples.toSet().size)
    }

    @Test
    fun `image and routing failures are not offered as retryable`() {
        // Retrying an undecodable file or an absent driver cannot possibly help; offering Retry
        // there would just teach the user that Retry does nothing.
        listOf(
            PrintCategory.IMAGE_DECODE_FAILED,
            PrintCategory.IMAGE_TOO_LARGE,
            PrintCategory.IMAGE_EMPTY,
            PrintCategory.NO_PRINTER_SELECTED,
            PrintCategory.NO_DRIVER_FOR_MODEL,
            PrintCategory.MISSING_ADDRESS,
            PrintCategory.SDK_NOT_BUNDLED,
        ).forEach { category ->
            assertTrue(
                "$category should not be retryable",
                !PrintErrorCatalog.presentationFor(category).retryable,
            )
        }
    }

    @Test
    fun `hardware faults the user can fix are retryable`() {
        listOf(
            PrintCategory.PAPER_OUT,
            PrintCategory.COVER_OPEN,
            PrintCategory.BT_OFF,
            PrintCategory.OVERHEAT,
            PrintCategory.CONNECT_FAILED,
        ).forEach { category ->
            assertTrue(
                "$category should be retryable",
                PrintErrorCatalog.presentationFor(category).retryable,
            )
        }
    }
}
