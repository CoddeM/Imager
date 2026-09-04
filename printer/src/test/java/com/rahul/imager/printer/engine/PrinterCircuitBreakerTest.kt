package com.rahul.imager.printer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The breaker's state machine, with an injected clock so the cooldown does not cost real seconds.
 *
 * The last test is the important one: it reproduces the bug this class exists to prevent, where a
 * probe token is never handed back and the printer fast-fails for the rest of the app's life.
 */
class PrinterCircuitBreakerTest {

    private var now = 1_000L
    private val breaker = PrinterCircuitBreaker(cooldownMs = 15_000L, clock = { now })
    private val key = "192.168.1.50"

    @Test
    fun `a fresh breaker is closed and admits everything`() {
        assertEquals(BreakerState.CLOSED, breaker.state(key))
        assertTrue(breaker.tryAcquire(key))
        assertTrue(breaker.tryAcquire(key))
    }

    @Test
    fun `a failure opens the breaker and fast-fails until the cooldown expires`() {
        breaker.tryAcquire(key)
        breaker.onFailure(key)

        assertEquals(BreakerState.OPEN, breaker.state(key))
        assertFalse(breaker.tryAcquire(key))
        assertEquals(15_000L, breaker.remainingCooldownMs(key))

        now += 14_999
        assertEquals(BreakerState.OPEN, breaker.state(key))
        assertFalse(breaker.tryAcquire(key))

        now += 1
        assertEquals(BreakerState.HALF_OPEN, breaker.state(key))
    }

    @Test
    fun `half open admits exactly one probe`() {
        breaker.onFailure(key)
        now += 15_000

        assertTrue("the first caller gets the probe", breaker.tryAcquire(key))
        assertFalse("a second concurrent caller must not", breaker.tryAcquire(key))
        assertFalse("nor a third", breaker.tryAcquire(key))
    }

    @Test
    fun `a successful probe closes the breaker`() {
        breaker.onFailure(key)
        now += 15_000
        assertTrue(breaker.tryAcquire(key))

        breaker.onSuccess(key)

        assertEquals(BreakerState.CLOSED, breaker.state(key))
        assertTrue(breaker.tryAcquire(key))
        assertEquals(0L, breaker.remainingCooldownMs(key))
    }

    @Test
    fun `a failed probe re-opens the breaker for another cooldown`() {
        breaker.onFailure(key)
        now += 15_000
        assertTrue(breaker.tryAcquire(key))

        breaker.onFailure(key)

        assertEquals(BreakerState.OPEN, breaker.state(key))
        assertEquals(15_000L, breaker.remainingCooldownMs(key))
    }

    @Test
    fun `releaseProbe returns the token without changing the trip state`() {
        breaker.onFailure(key)
        now += 15_000
        assertEquals(BreakerState.HALF_OPEN, breaker.state(key))
        assertTrue(breaker.tryAcquire(key))

        breaker.releaseProbe(key)

        // Still half-open: the attempt reached no verdict, so nothing about the printer changed.
        assertEquals(BreakerState.HALF_OPEN, breaker.state(key))
        assertTrue("the next attempt must be able to take the probe", breaker.tryAcquire(key))
    }

    @Test
    fun `a leaked probe token would fast-fail for ever, releaseProbe prevents it`() {
        breaker.onFailure(key)
        now += 15_000
        assertTrue(breaker.tryAcquire(key))

        // Simulate a verdict-less outcome: a dirty send failure, or the user cancelling.
        // WITHOUT the release, the token stays taken and every later attempt is refused, even
        // hours later — the printer is dead until the app restarts.
        assertFalse(breaker.tryAcquire(key))
        now += 10 * 60 * 1000
        assertFalse("this is the bug: still refused ten minutes later", breaker.tryAcquire(key))

        breaker.releaseProbe(key)
        assertTrue("after the release the printer gets another chance", breaker.tryAcquire(key))
    }

    @Test
    fun `breakers are independent per printer`() {
        val other = "AA:BB:CC:DD:EE:FF"
        breaker.onFailure(key)

        assertEquals(BreakerState.OPEN, breaker.state(key))
        assertEquals(BreakerState.CLOSED, breaker.state(other))
        assertTrue(breaker.tryAcquire(other))
    }

    @Test
    fun `reset forgets a printer entirely`() {
        breaker.onFailure(key)
        assertEquals(BreakerState.OPEN, breaker.state(key))

        breaker.reset(key)

        assertEquals(BreakerState.CLOSED, breaker.state(key))
        assertTrue(breaker.tryAcquire(key))
    }
}
