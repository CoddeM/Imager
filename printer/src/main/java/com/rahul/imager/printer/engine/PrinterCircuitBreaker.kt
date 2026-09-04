package com.rahul.imager.printer.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The three states one printer's breaker can be in. */
enum class BreakerState {
    /** Everything through. */
    CLOSED,

    /** Fast-fail: the printer failed recently and the cooldown has not expired. */
    OPEN,

    /** The cooldown has expired; exactly one probe is allowed through to test the water. */
    HALF_OPEN,
}

/**
 * A per-printer circuit breaker.
 *
 * Its whole purpose is to keep a broken printer from costing the user a 60-second timeout on every
 * tap. After a failure the printer is fast-failed for a cooldown; once that expires exactly ONE
 * attempt is let through to find out whether the situation has improved.
 *
 * The bookkeeping has to be TOTAL: every admitted attempt must resolve exactly once, through
 * [onSuccess], [onFailure] or [releaseProbe]. [releaseProbe] is the one that is easy to forget and
 * the one that matters most — an attempt that ends without a verdict (a dirty send failure, an
 * exception mid-print, external cancellation) still holds the half-open probe token. A leaked
 * token means the printer NEVER gets another probe and fast-fails for ever, until the app is
 * restarted. That is exactly the "printer stuck until I force-quit" bug this class exists to stop.
 *
 * @param clock injectable so the cooldown behaviour can be tested without waiting for it.
 */
class PrinterCircuitBreaker(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Entry {
        /** Epoch millis until which the breaker is open. Zero means closed. */
        val trippedUntil = AtomicLong(0L)

        /** True while a half-open probe is in flight. */
        val probing = AtomicBoolean(false)
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun entry(key: String): Entry = entries.getOrPut(key) { Entry() }

    /** The current state for [key]. */
    fun state(key: String): BreakerState {
        val trippedUntil = entry(key).trippedUntil.get()
        return when {
            trippedUntil == 0L -> BreakerState.CLOSED
            clock() < trippedUntil -> BreakerState.OPEN
            else -> BreakerState.HALF_OPEN
        }
    }

    /** True when [key] is fast-failing right now. */
    fun isOpen(key: String): Boolean = state(key) == BreakerState.OPEN

    /**
     * Asks permission to attempt a print.
     *
     * @return true when the attempt may proceed. A `true` from the HALF_OPEN state also claims the
     *   single probe token, which the caller MUST hand back through one of [onSuccess],
     *   [onFailure] or [releaseProbe].
     */
    fun tryAcquire(key: String): Boolean {
        val entry = entry(key)
        return when (state(key)) {
            BreakerState.CLOSED -> true
            BreakerState.OPEN -> false
            // Exactly one probe: whoever wins the CAS goes, everyone else fast-fails.
            BreakerState.HALF_OPEN -> entry.probing.compareAndSet(false, true)
        }
    }

    /** The printer worked: close the breaker and release any probe token. */
    fun onSuccess(key: String) {
        val entry = entry(key)
        entry.trippedUntil.set(0L)
        entry.probing.set(false)
    }

    /** The printer failed in a way that says it is broken: open the breaker for the cooldown. */
    fun onFailure(key: String) {
        val entry = entry(key)
        entry.trippedUntil.set(clock() + cooldownMs)
        entry.probing.set(false)
    }

    /**
     * Hands the probe token back WITHOUT passing a verdict on the printer.
     *
     * Used for outcomes that say nothing about whether the hardware is healthy: a dirty send
     * failure (the connection has already been discarded), an exception mid-print, or the user
     * cancelling. The trip state is deliberately left exactly as it was.
     */
    fun releaseProbe(key: String) {
        entry(key).probing.set(false)
    }

    /** Forgets everything about [key]. Used when a printer is deleted or re-added. */
    fun reset(key: String) {
        entries.remove(key)
    }

    /** Forgets every printer. */
    fun resetAll() {
        entries.clear()
    }

    /** Millis until [key] leaves the OPEN state, or zero when it is not open. */
    fun remainingCooldownMs(key: String): Long {
        val trippedUntil = entry(key).trippedUntil.get()
        if (trippedUntil == 0L) return 0L
        return (trippedUntil - clock()).coerceAtLeast(0L)
    }

    companion object {
        /** Long enough to stop a retry storm, short enough that a fixed printer recovers fast. */
        const val DEFAULT_COOLDOWN_MS = 15_000L
    }
}
