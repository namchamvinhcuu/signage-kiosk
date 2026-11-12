package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteLogRateLimiterTest {

    @Test
    fun `defaults allow 20 then block the 21st inside the window`() {
        val l = RemoteLogRateLimiter()
        repeat(20) { assertTrue("send $it", l.tryAcquire(1_000L + it)) }
        assertFalse(l.tryAcquire(1_100L))
    }

    @Test
    fun `window boundary at exactly windowMs frees the oldest slot`() {
        val l = RemoteLogRateLimiter(maxPerWindow = 2, windowMs = 1_000L, backoffMs = 0)
        assertTrue(l.tryAcquire(0))
        assertTrue(l.tryAcquire(500))
        assertFalse(l.tryAcquire(999)) // oldest is 999 ms old: still inside
        assertTrue(l.tryAcquire(1_000)) // exactly windowMs: expired
        assertFalse(l.tryAcquire(1_000)) // 500 and 1000 are in the window
        assertTrue(l.tryAcquire(1_500)) // 500 expired exactly
    }

    @Test
    fun `a refused attempt is not recorded`() {
        val l = RemoteLogRateLimiter(maxPerWindow = 1, windowMs = 1_000L)
        assertTrue(l.tryAcquire(0))
        assertFalse(l.tryAcquire(500))
        assertFalse(l.tryAcquire(900))
        assertTrue(l.tryAcquire(1_000)) // would be false if refusals had been counted
    }

    @Test
    fun `onRateLimited blocks until backoff elapsed exactly`() {
        val l = RemoteLogRateLimiter(maxPerWindow = 5, windowMs = 1_000L, backoffMs = 10_000L)
        l.onRateLimited(100)
        assertFalse(l.tryAcquire(100))
        assertFalse(l.tryAcquire(10_099))
        assertTrue(l.tryAcquire(10_100))
    }

    @Test
    fun `zero backoff does not block`() {
        val l = RemoteLogRateLimiter(maxPerWindow = 5, windowMs = 1_000L, backoffMs = 0)
        l.onRateLimited(100)
        assertTrue(l.tryAcquire(100))
    }

    @Test
    fun `init rejects invalid arguments`() {
        for (f in listOf<() -> Any>(
            { RemoteLogRateLimiter(maxPerWindow = 0) },
            { RemoteLogRateLimiter(maxPerWindow = -1) },
            { RemoteLogRateLimiter(windowMs = 0) },
            { RemoteLogRateLimiter(windowMs = -5) },
            { RemoteLogRateLimiter(backoffMs = -1) },
        )) {
            try {
                f()
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `smallest valid configuration works`() {
        val l = RemoteLogRateLimiter(maxPerWindow = 1, windowMs = 1, backoffMs = 0)
        assertTrue(l.tryAcquire(0))
        assertFalse(l.tryAcquire(0))
        assertTrue(l.tryAcquire(1))
    }
}
