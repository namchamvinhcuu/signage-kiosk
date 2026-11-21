package com.namchamvinhcuu.signagekiosk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plan kiosk-frontwatch step 1 (AC2, AC12). FrontStartMark is process-global: every test starts and ends clean. */
class FrontStartMarkTest {

    @Before
    fun setUp() = FrontStartMark.clear()

    @After
    fun tearDown() = FrontStartMark.clear()

    @Test
    fun `window is thirty seconds`() {
        assertEquals(30_000L, FrontStartMark.WINDOW_MS)
    }

    @Test
    fun `set then take is true once and then false`() {
        FrontStartMark.set(1_000L)
        assertTrue(FrontStartMark.take(1_500L))
        assertFalse(FrontStartMark.take(1_500L))
        assertFalse(FrontStartMark.take(1_501L))
    }

    @Test
    fun `take without set is false`() {
        assertFalse(FrontStartMark.take(1_000L))
        assertFalse(FrontStartMark.take(0L))
    }

    @Test
    fun `take at the same instant as set is valid`() {
        FrontStartMark.set(5_000L)
        assertTrue(FrontStartMark.take(5_000L))
    }

    @Test
    fun `take just inside the window is true`() {
        FrontStartMark.set(10_000L)
        assertTrue(FrontStartMark.take(10_000L + 29_999L))
    }

    @Test
    fun `take at exactly thirty seconds is expired`() {
        FrontStartMark.set(10_000L)
        assertFalse(FrontStartMark.take(10_000L + 30_000L))
    }

    @Test
    fun `take after the window is false`() {
        FrontStartMark.set(10_000L)
        assertFalse(FrontStartMark.take(10_000L + 30_001L))
        FrontStartMark.set(10_000L)
        assertFalse(FrontStartMark.take(10_000L + 3_600_000L))
    }

    @Test
    fun `clock going backwards is not valid`() {
        FrontStartMark.set(10_000L)
        assertFalse(FrontStartMark.take(9_999L))
    }

    @Test
    fun `a take after an expired mark still consumes it`() {
        FrontStartMark.set(10_000L)
        assertFalse(FrontStartMark.take(10_000L + 30_000L)) // expired
        assertFalse(FrontStartMark.take(10_001L)) // would be valid if the expired take had not consumed it
    }

    @Test
    fun `a take after a backwards clock still consumes it`() {
        FrontStartMark.set(10_000L)
        assertFalse(FrontStartMark.take(9_000L))
        assertFalse(FrontStartMark.take(10_001L))
    }

    @Test
    fun `set again after a consume works`() {
        FrontStartMark.set(1_000L)
        assertTrue(FrontStartMark.take(1_100L))
        FrontStartMark.set(2_000L)
        assertTrue(FrontStartMark.take(2_100L))
        assertFalse(FrontStartMark.take(2_100L))
    }

    @Test
    fun `a second set replaces the first one`() {
        FrontStartMark.set(1_000L)
        FrontStartMark.set(100_000L)
        // valid against the second set only; against the first it would be long expired
        assertTrue(FrontStartMark.take(100_500L))
    }

    @Test
    fun `clear drops a pending mark`() {
        FrontStartMark.set(1_000L)
        FrontStartMark.clear()
        assertFalse(FrontStartMark.take(1_100L))
    }

    @Test
    fun `clear without a mark is harmless`() {
        FrontStartMark.clear()
        FrontStartMark.clear()
        assertFalse(FrontStartMark.take(1L))
    }

    @Test
    fun `a mark set at time zero or negative time is a real mark`() {
        FrontStartMark.set(0L)
        assertTrue(FrontStartMark.take(0L))
        FrontStartMark.set(-5_000L)
        assertTrue(FrontStartMark.take(-4_000L))
    }
}
