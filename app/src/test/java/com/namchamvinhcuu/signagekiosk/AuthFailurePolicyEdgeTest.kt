package com.namchamvinhcuu.signagekiosk

import com.namchamvinhcuu.signagekiosk.AuthFailurePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Timing edge cases of the sliding-window [AuthFailurePolicy] beyond AuthFailurePolicyTest (round 4 audit). */
class AuthFailurePolicyEdgeTest {

    private val retry = 30_000L

    @Test
    fun `a deduped report does not move the gap reference`() {
        val p = AuthFailurePolicy(maxFailures = 2, minGapMs = 5_000L)
        assertEquals(Decision.RETRY, p.onMainFrame403(0))
        assertEquals(Decision.RETRY, p.onMainFrame403(3_000L)) // same load, ignored
        // 6000 is 6 s after the COUNTED report (not 3 s after the ignored one): a new load.
        assertEquals(Decision.CLEAR, p.onMainFrame403(6_000L))
    }

    @Test
    fun `right after a clear the next 403 is failure number one again`() {
        val p = AuthFailurePolicy()
        p.onMainFrame403(0)
        p.onMainFrame403(retry)
        assertEquals(Decision.CLEAR, p.onMainFrame403(2 * retry))
        assertEquals(Decision.RETRY, p.onMainFrame403(2 * retry + 1))
        assertEquals(Decision.RETRY, p.onMainFrame403(3 * retry))
        assertEquals(Decision.CLEAR, p.onMainFrame403(4 * retry))
    }

    @Test
    fun `nine loads at the retry cadence clear on the 3rd 6th and 9th`() {
        val p = AuthFailurePolicy()
        val r = Decision.RETRY
        val c = Decision.CLEAR
        val decisions = List(9) { p.onMainFrame403(it * retry) }
        assertEquals(listOf(r, r, c, r, r, c, r, r, c), decisions)
    }

    @Test
    fun `a slow trickle of 403s one every 6 minutes never clears`() {
        val p = AuthFailurePolicy()
        repeat(40) { assertEquals(Decision.RETRY, p.onMainFrame403(it * 6 * 60_000L)) }
    }

    @Test
    fun `a gap of zero counts equal timestamps as separate failures`() {
        val p = AuthFailurePolicy(maxFailures = 3, minGapMs = 0L)
        assertEquals(Decision.RETRY, p.onMainFrame403(100))
        assertEquals(Decision.RETRY, p.onMainFrame403(100))
        assertEquals(Decision.CLEAR, p.onMainFrame403(100))
    }

    @Test
    fun `equal timestamps with the default gap count once`() {
        val p = AuthFailurePolicy()
        repeat(10) { assertEquals(Decision.RETRY, p.onMainFrame403(500)) }
    }

    @Test
    fun `large and negative clock readings behave like small ones`() {
        for (base in listOf(Long.MAX_VALUE / 2, -10_000_000L, 0L)) {
            val p = AuthFailurePolicy()
            assertEquals(Decision.RETRY, p.onMainFrame403(base))
            assertEquals(Decision.RETRY, p.onMainFrame403(base + retry))
            assertEquals(Decision.CLEAR, p.onMainFrame403(base + 2 * retry))
        }
    }

    @Test
    fun `a huge jump forward ages every old failure out`() {
        val p = AuthFailurePolicy()
        p.onMainFrame403(0)
        p.onMainFrame403(retry)
        assertEquals(Decision.RETRY, p.onMainFrame403(Long.MAX_VALUE / 2))
    }

    @Test
    fun `a report with an earlier timestamp does not throw and does not clear`() {
        val p = AuthFailurePolicy()
        p.onMainFrame403(100_000L)
        assertEquals(Decision.RETRY, p.onMainFrame403(50_000L))
        assertEquals(Decision.RETRY, p.onMainFrame403(0L))
    }

    @Test
    fun `a limit that the window can hold is reachable and 100 failures clear at the cadence`() {
        val p = AuthFailurePolicy(maxFailures = 100, windowMs = 10 * 60_000L, minGapMs = 5_000L)
        // 30 s cadence: only 20 fit into 10 minutes, so it can never reach 100.
        repeat(300) { assertEquals(Decision.RETRY, p.onMainFrame403(it * retry)) }
        val q = AuthFailurePolicy(maxFailures = 100, windowMs = 10 * 60_000L, minGapMs = 5_000L)
        // 5 s cadence: 120 fit, the 100th clears.
        var last = Decision.RETRY
        var n = 0
        while (last == Decision.RETRY && n < 200) last = q.onMainFrame403(5_000L * n++)
        assertEquals(Decision.CLEAR, last)
        assertEquals(100, n)
    }
}
