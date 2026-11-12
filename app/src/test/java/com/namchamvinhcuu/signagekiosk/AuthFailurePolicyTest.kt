package com.namchamvinhcuu.signagekiosk

import com.namchamvinhcuu.signagekiosk.AuthFailurePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthFailurePolicyTest {

    private val retry = 30_000L // LOAD_RETRY_MS: the app reloads the stored URL every 30 s after a 403

    @Test
    fun `the third 403 inside the window clears the session, the first two retry`() {
        val p = AuthFailurePolicy()
        assertEquals(Decision.RETRY, p.onMainFrame403(0))
        assertEquals(Decision.RETRY, p.onMainFrame403(retry))
        assertEquals(Decision.CLEAR, p.onMainFrame403(2 * retry))
    }

    @Test
    fun `the count starts over after a clear`() {
        val p = AuthFailurePolicy()
        p.onMainFrame403(0)
        p.onMainFrame403(retry)
        assertEquals(Decision.CLEAR, p.onMainFrame403(2 * retry))
        assertEquals(Decision.RETRY, p.onMainFrame403(3 * retry))
        assertEquals(Decision.RETRY, p.onMainFrame403(4 * retry))
        assertEquals(Decision.CLEAR, p.onMainFrame403(5 * retry))
    }

    @Test
    fun `a transient 403 ages out of the window and never adds up with later ones`() {
        val p = AuthFailurePolicy(windowMs = 10 * 60_000L)
        assertEquals(Decision.RETRY, p.onMainFrame403(0))
        assertEquals(Decision.RETRY, p.onMainFrame403(retry))
        // 11 minutes later both are gone: this is failure number 1 again.
        assertEquals(Decision.RETRY, p.onMainFrame403(11 * 60_000L))
        assertEquals(Decision.RETRY, p.onMainFrame403(11 * 60_000L + retry))
        assertEquals(Decision.CLEAR, p.onMainFrame403(11 * 60_000L + 2 * retry))
    }

    @Test
    fun `a failure exactly one window old no longer counts`() {
        val p = AuthFailurePolicy(maxFailures = 2, windowMs = 60_000L, minGapMs = 0L)
        assertEquals(Decision.RETRY, p.onMainFrame403(0))
        assertEquals(Decision.RETRY, p.onMainFrame403(60_000L))
        assertEquals(Decision.CLEAR, p.onMainFrame403(60_000L + 1))
    }

    @Test
    fun `a failure just inside the window still counts`() {
        val p = AuthFailurePolicy(maxFailures = 2, windowMs = 60_000L, minGapMs = 0L)
        assertEquals(Decision.RETRY, p.onMainFrame403(0))
        assertEquals(Decision.CLEAR, p.onMainFrame403(59_999L))
    }

    @Test
    fun `one load reported twice counts once`() {
        val p = AuthFailurePolicy()
        assertEquals(Decision.RETRY, p.onMainFrame403(0))
        assertEquals(Decision.RETRY, p.onMainFrame403(1_000))
        assertEquals(Decision.RETRY, p.onMainFrame403(2_000))
        // Still only one failure on record: two more real loads are needed.
        assertEquals(Decision.RETRY, p.onMainFrame403(retry))
        assertEquals(Decision.CLEAR, p.onMainFrame403(2 * retry))
    }

    @Test
    fun `the minimum gap boundary`() {
        val p = AuthFailurePolicy(maxFailures = 2, minGapMs = 5_000L)
        assertEquals(Decision.RETRY, p.onMainFrame403(0))
        // 4999 ms later: the same load, not counted.
        assertEquals(Decision.RETRY, p.onMainFrame403(4_999L))
        // exactly 5000 ms after the counted one is a new load.
        assertEquals(Decision.CLEAR, p.onMainFrame403(5_000L))
    }

    @Test
    fun `reset forgets the failures`() {
        val p = AuthFailurePolicy()
        p.onMainFrame403(0)
        p.onMainFrame403(retry)
        p.reset()
        assertEquals(Decision.RETRY, p.onMainFrame403(2 * retry))
        assertEquals(Decision.RETRY, p.onMainFrame403(3 * retry))
        assertEquals(Decision.CLEAR, p.onMainFrame403(4 * retry))
    }

    @Test
    fun `a limit of one clears on the first 403`() {
        assertEquals(Decision.CLEAR, AuthFailurePolicy(maxFailures = 1).onMainFrame403(0))
    }

    @Test
    fun `a revoked TV is cleared within about a minute at the retry cadence`() {
        val p = AuthFailurePolicy()
        var t = 0L
        var decision = p.onMainFrame403(t)
        var loads = 1
        while (decision == Decision.RETRY && loads < 10) {
            t += retry
            decision = p.onMainFrame403(t)
            loads++
        }
        assertEquals(Decision.CLEAR, decision)
        assertEquals(3, loads)
        assertEquals(2 * retry, t)
    }

    @Test
    fun `invalid parameters are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AuthFailurePolicy(maxFailures = 0) }
        assertThrows(IllegalArgumentException::class.java) { AuthFailurePolicy(maxFailures = -1) }
        assertThrows(IllegalArgumentException::class.java) { AuthFailurePolicy(windowMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { AuthFailurePolicy(minGapMs = -1) }
    }
}
