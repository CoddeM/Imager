package com.rahul.imager.printer.engine

import android.content.Context
import android.graphics.Bitmap
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.PrinterRouting
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.printer.raster.RasterCore
import com.rahul.imager.printer.raster.RasterJob
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's reliability contract, exercised against a fake driver.
 *
 * Every case here corresponds to a rule that exists because of a specific real-world failure:
 * a doubled print, a queue that never recovers, a printer stuck until the app is force-quit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrintEngineTest {

    private val target = SavedPrinter(
        id = "printer-1",
        displayName = "Test printer",
        brand = PrinterBrand.GENERIC_ESCPOS,
        transport = TransportType.LAN,
        identifier = "192.168.1.50",
        paperProfileId = PaperProfiles.STANDARD_80MM.id,
    )

    /** A job with enough bands to count as a photo (the 60 s timeout branch). */
    private fun photoJob(heightDots: Int = 1_000): RasterJob {
        val width = 576
        val bytesPerRow = RasterCore.bytesPerRow(width)
        return RasterJob(
            monoBitmap = mockk<Bitmap>(relaxed = true),
            packedRows = ByteArray(bytesPerRow * heightDots),
            widthDots = width,
            heightDots = heightDots,
            bytesPerRow = bytesPerRow,
            paper = PaperProfiles.STANDARD_80MM,
            options = PrintOptions(),
        )
    }

    /** A configurable stand-in for a real driver. */
    private class FakePrinter(
        var connectResult: Boolean = true,
        var errors: MutableList<PrintError?> = mutableListOf(null),
        var printDelayMs: Long = 0L,
        var status: PrinterStatus? = null,
    ) : ThermalPrinter {
        override val displayName = "Fake"
        override val brand = PrinterBrand.GENERIC_ESCPOS
        override val transport = TransportType.LAN

        var connectCalls = 0
        var printCalls = 0
        var forceCloseCalls = 0
        var disconnectCalls = 0

        override suspend fun isAvailable(context: Context?) = true

        override suspend fun connect(context: Context?): Boolean {
            connectCalls++
            return connectResult
        }

        override suspend fun printImage(
            context: Context?,
            job: RasterJob,
            onProgress: (Float) -> Unit,
        ): PrintError? {
            printCalls++
            if (printDelayMs > 0) delay(printDelayMs)
            onProgress(1f)
            return if (errors.isEmpty()) null else errors.removeAt(0)
        }

        override suspend fun disconnect(context: Context?): Boolean {
            disconnectCalls++
            return true
        }

        override fun forceClose() {
            forceCloseCalls++
        }

        override suspend fun queryStatus(context: Context?): PrinterStatus? = status
    }

    private fun engineFor(printer: ThermalPrinter, breaker: PrinterCircuitBreaker) = PrintEngine(
        resolver = { _, _ -> PrinterRouting.Resolved(printer) },
        breaker = breaker,
    )

    // ---------------------------------------------------------------------------------------

    @Test
    fun `a successful print reports success exactly once`() = runTest {
        val printer = FakePrinter()
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, PrinterCircuitBreaker()).print(
            job = photoJob(),
            target = target,
            context = null,
            onResult = { results += it },
        )

        assertEquals(1, results.size)
        assertTrue(results.single().success)
        assertEquals(1, printer.printCalls)
        assertEquals(1, printer.disconnectCalls)
        // The happy path must NOT force-close: the head may still be flushing the last band.
        assertEquals(0, printer.forceCloseCalls)
    }

    @Test
    fun `progress runs from connecting to finishing`() = runTest {
        val progress = mutableListOf<PrintProgress>()

        engineFor(FakePrinter(), PrinterCircuitBreaker()).print(
            job = photoJob(),
            target = target,
            context = null,
            onProgress = { progress += it },
        )

        assertTrue(progress.first() is PrintProgress.Resolving)
        assertTrue(progress.any { it is PrintProgress.Connecting })
        assertTrue(progress.any { it is PrintProgress.Sending })
        assertTrue(progress.last() is PrintProgress.Finishing)
    }

    @Test
    fun `an unroutable printer fails without ever connecting`() = runTest {
        val printer = FakePrinter()
        val engine = PrintEngine(
            resolver = { _, _ ->
                PrinterRouting.Unroutable(PrintCategory.SDK_NOT_BUNDLED, "no sdk")
            },
            breaker = PrinterCircuitBreaker(),
        )
        val results = mutableListOf<PrintAttemptResult>()

        engine.print(photoJob(), target, null, onResult = { results += it })

        assertEquals(1, results.size)
        assertEquals(PrintCategory.SDK_NOT_BUNDLED, results.single().error?.category)
        assertEquals(0, printer.connectCalls)
    }

    @Test
    fun `a refused connection fails and trips the breaker`() = runTest {
        val breaker = PrinterCircuitBreaker()
        val printer = FakePrinter(connectResult = false)
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, breaker).print(photoJob(), target, null, onResult = { results += it })

        assertEquals(PrintCategory.CONNECT_FAILED, results.single().error?.category)
        assertEquals(BreakerState.OPEN, breaker.state(target.identifier))
    }

    @Test
    fun `a clean failure is retried exactly once, in place`() = runTest {
        val printer = FakePrinter(
            errors = mutableListOf(
                PrintError(PrintCategory.SEND_FAILED, clean = true),
                null,
            ),
        )
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, PrinterCircuitBreaker())
            .print(photoJob(), target, null, onResult = { results += it })

        assertTrue("the retry must be reported as a success", results.single().success)
        assertEquals("exactly one retry, never two", 2, printer.printCalls)
        assertEquals("the dead handle must be discarded first", 1, printer.forceCloseCalls)
        assertEquals("a reconnect is needed before the retry", 2, printer.connectCalls)
    }

    @Test
    fun `a clean failure that fails again gives up and trips the breaker`() = runTest {
        val breaker = PrinterCircuitBreaker()
        val printer = FakePrinter(
            errors = mutableListOf(
                PrintError(PrintCategory.SEND_FAILED, clean = true),
                PrintError(PrintCategory.SEND_FAILED, clean = true),
            ),
        )
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, breaker).print(photoJob(), target, null, onResult = { results += it })

        assertFalse(results.single().success)
        assertEquals(2, printer.printCalls)
        assertEquals(BreakerState.OPEN, breaker.state(target.identifier))
    }

    @Test
    fun `a dirty failure is never retried and never trips the breaker`() = runTest {
        val breaker = PrinterCircuitBreaker()
        val printer = FakePrinter(
            errors = mutableListOf(PrintError(PrintCategory.PARTIAL_PRINT, clean = false)),
        )
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, breaker).print(photoJob(), target, null, onResult = { results += it })

        assertEquals(PrintCategory.PARTIAL_PRINT, results.single().error?.category)
        assertEquals("re-sending would double-print", 1, printer.printCalls)
        // The handle has already been discarded, so this says nothing about the hardware: the next
        // attempt must be allowed to try a fresh connection immediately.
        assertEquals(BreakerState.CLOSED, breaker.state(target.identifier))
    }

    @Test
    fun `a send timeout force-closes, reports SEND_TIMEOUT and trips the breaker`() = runTest {
        val breaker = PrinterCircuitBreaker()
        val printer = FakePrinter(printDelayMs = 10 * 60 * 1000L)
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, breaker).print(photoJob(), target, null, onResult = { results += it })

        assertEquals(PrintCategory.SEND_TIMEOUT, results.single().error?.category)
        assertTrue("the socket must be torn down to free the lock", printer.forceCloseCalls >= 1)
        assertEquals(BreakerState.OPEN, breaker.state(target.identifier))
    }

    @Test
    fun `cancellation re-throws and emits no result`() = runTest {
        val printer = FakePrinter(printDelayMs = 10_000L)
        val breaker = PrinterCircuitBreaker()
        val results = mutableListOf<PrintAttemptResult>()

        val job = launch {
            engineFor(printer, breaker)
                .print(photoJob(), target, null, onResult = { results += it })
        }
        runCurrent()
        advanceTimeBy(100)
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertTrue("a cancelled print reports nothing", results.isEmpty())
        assertTrue(printer.forceCloseCalls >= 1)
        // Cancelling is the user's choice, never a verdict on the printer.
        assertEquals(BreakerState.CLOSED, breaker.state(target.identifier))
    }

    @Test
    fun `an open breaker fast-fails without touching the printer`() = runTest {
        val breaker = PrinterCircuitBreaker()
        breaker.onFailure(target.identifier)

        val printer = FakePrinter()
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, breaker).print(photoJob(), target, null, onResult = { results += it })

        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertEquals("a fast-fail must not dial the printer", 0, printer.connectCalls)
    }

    @Test
    fun `a driver that throws is reported, not propagated, and leaves the breaker usable`() =
        runTest {
            val breaker = PrinterCircuitBreaker()
            val printer = object : ThermalPrinter {
                override val displayName = "Throwing"
                override val brand = PrinterBrand.GENERIC_ESCPOS
                override val transport = TransportType.LAN
                override suspend fun isAvailable(context: Context?) = true
                override suspend fun connect(context: Context?) = true
                override suspend fun printImage(
                    context: Context?,
                    job: RasterJob,
                    onProgress: (Float) -> Unit,
                ): PrintError? = throw IllegalStateException("driver bug")

                override suspend fun disconnect(context: Context?) = true
            }
            val results = mutableListOf<PrintAttemptResult>()

            engineFor(printer, breaker).print(photoJob(), target, null, onResult = { results += it })

            assertEquals(PrintCategory.UNKNOWN, results.single().error?.category)
            // A driver bug says nothing about the hardware; the next attempt must be admitted.
            assertEquals(BreakerState.CLOSED, breaker.state(target.identifier))
        }

    @Test
    fun `the status probe is recorded on the trace when a send fails`() = runTest {
        val printer = FakePrinter(
            errors = mutableListOf(PrintError(PrintCategory.SEND_FAILED)),
            status = PrinterStatus(online = true, paperOut = true, rawVendorCode = "0x60"),
        )
        val results = mutableListOf<PrintAttemptResult>()

        engineFor(printer, PrinterCircuitBreaker())
            .print(photoJob(), target, null, onResult = { results += it })

        val trace = results.single().trace
        assertTrue(trace?.statusTokens?.contains("paper_out") == true)
    }

    @Test
    fun `two jobs for one printer serialize`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var concurrent = 0
        var maxConcurrent = 0

        val printer = object : ThermalPrinter {
            override val displayName = "Serialized"
            override val brand = PrinterBrand.GENERIC_ESCPOS
            override val transport = TransportType.LAN
            override suspend fun isAvailable(context: Context?) = true
            override suspend fun connect(context: Context?) = true
            override suspend fun printImage(
                context: Context?,
                job: RasterJob,
                onProgress: (Float) -> Unit,
            ): PrintError? {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                gate.await()
                concurrent--
                return null
            }

            override suspend fun disconnect(context: Context?) = true
        }

        val engine = engineFor(printer, PrinterCircuitBreaker())
        val first = launch { engine.print(photoJob(), target, null) }
        val second = launch { engine.print(photoJob(), target, null) }
        runCurrent()
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals("one printer, one job at a time", 1, maxConcurrent)
    }

    @Test
    fun `an attempt that ends without a result still reports one`() = runTest {
        // Belt and braces: the engine promises exactly one result on every non-cancelled path.
        val results = mutableListOf<PrintAttemptResult>()
        engineFor(FakePrinter(), PrinterCircuitBreaker())
            .print(photoJob(heightDots = 50), target, null, onResult = { results += it })
        assertEquals(1, results.size)
        assertNull(results.single().error)
    }
}
