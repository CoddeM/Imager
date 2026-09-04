package com.rahul.imager.printer.engine

import android.util.Log
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.SavedPrinter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** The stages a print attempt moves through, in order. */
enum class PrintStage {
    RESOLVE,
    BREAKER,
    LOCK,
    CONNECT,
    SEND,
    FINALIZE,
}

/**
 * A recording of one print attempt.
 *
 * Failures on real hardware are rarely reproducible on a desk, so a trace is flushed as ONE
 * structured log line and kept in memory for the diagnostics screen. One line rather than a dozen
 * because interleaved logs from several printers are unreadable when it matters.
 */
class PrintJobTrace(
    val printerId: String,
    val printerName: String,
    val brand: String,
    val transport: String,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    private val clock: () -> Long = System::nanoTime,
) {

    /** One stage transition. */
    data class Mark(val stage: PrintStage, val atMs: Long, val detail: String?)

    private val startNanos = clock()
    private val marks = CopyOnWriteArrayList<Mark>()

    /** Millis spent inside [PrintStage.CONNECT], when the attempt got that far. */
    var connectLatencyMs: Long? = null
        private set

    /** Hardware status tokens observed on failure, e.g. `paper_out`. */
    var statusTokens: List<String> = emptyList()
        private set

    var vendorErrorCode: String? = null
        private set

    /** The lock / breaker key the attempt actually used. */
    var lockKey: String? = null

    var outcome: String = "in_progress"
        private set

    var errorCode: String? = null
        private set

    /** The stage the attempt is currently in. */
    var currentStage: PrintStage = PrintStage.RESOLVE
        private set

    private fun elapsedMs(): Long = (clock() - startNanos) / 1_000_000

    /** Records entering [stage]. */
    fun mark(stage: PrintStage, detail: String? = null) {
        currentStage = stage
        marks += Mark(stage, elapsedMs(), detail)
    }

    /** Records how long the connect took. */
    fun recordConnectLatency(millis: Long) {
        connectLatencyMs = millis
    }

    /** Records a hardware status snapshot. */
    fun recordStatus(status: PrinterStatus?) {
        status ?: return
        statusTokens = buildList {
            if (!status.online) add("offline")
            if (status.paperOut) add("paper_out")
            if (status.paperLow) add("paper_low")
            if (status.coverOpen) add("cover_open")
            if (status.overheat) add("overheat")
            if (status.busy) add("busy")
        }
        vendorErrorCode = status.rawVendorCode ?: vendorErrorCode
    }

    /** Marks the attempt successful and flushes the trace. */
    fun finishSuccess() {
        outcome = "success"
        flush(Log.INFO)
    }

    /** Marks the attempt failed and flushes the trace. */
    fun finishFailure(error: PrintError) {
        outcome = "failure"
        errorCode = error.code
        vendorErrorCode = error.context.vendorErrorCode ?: vendorErrorCode
        flush(Log.WARN)
    }

    /** Marks the attempt cancelled by the user and flushes the trace. */
    fun finishCancelled() {
        outcome = "cancelled"
        flush(Log.INFO)
    }

    /** The single structured line written to logcat and shown in diagnostics. */
    fun toLogLine(): String = buildString {
        append("printJob outcome=").append(outcome)
        append(" printer=").append(printerName)
        append(" brand=").append(brand)
        append(" transport=").append(transport)
        lockKey?.let { append(" lockKey=").append(it) }
        errorCode?.let { append(" error=").append(it) }
        vendorErrorCode?.let { append(" vendor=").append(it) }
        connectLatencyMs?.let { append(" connectMs=").append(it) }
        if (statusTokens.isNotEmpty()) append(" status=").append(statusTokens.joinToString("|"))
        append(" stages=[")
        append(marks.joinToString(" -> ") { mark ->
            "${mark.stage}@${mark.atMs}ms" + (mark.detail?.let { "($it)" } ?: "")
        })
        append(']')
    }

    private fun flush(priority: Int) {
        val line = toLogLine()
        Log.println(priority, TAG, line)
        PrintTraceRecorder.record(this)
    }

    private companion object {
        const val TAG = "PrintJobTrace"
    }
}

/**
 * Keeps the most recent traces in memory for the hidden diagnostics screen.
 *
 * Bounded and in-memory only: diagnostics are for the session the user is currently frustrated by,
 * and nothing here is worth writing to disk or sending anywhere.
 */
object PrintTraceRecorder {

    /** How many attempts are kept. */
    const val CAPACITY = 50

    private val _traces = MutableStateFlow<List<PrintJobTrace>>(emptyList())

    /** Most recent first. */
    val traces: StateFlow<List<PrintJobTrace>> = _traces.asStateFlow()

    fun record(trace: PrintJobTrace) {
        _traces.value = (listOf(trace) + _traces.value).take(CAPACITY)
    }

    fun clear() {
        _traces.value = emptyList()
    }

    /** The whole buffer as text, for the copy-to-clipboard action. */
    fun dump(): String = _traces.value.joinToString("\n") { it.toLogLine() }
}

/** Convenience for building a trace from the printer a job is aimed at. */
fun PrintJobTrace(target: SavedPrinter): PrintJobTrace = PrintJobTrace(
    printerId = target.id,
    printerName = target.displayName,
    brand = target.brand.name,
    transport = target.transport.name,
)
