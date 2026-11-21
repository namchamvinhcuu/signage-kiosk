package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan kiosk-frontwatch step 1 (AC3, AC12): pure FrontWatch decisions. */
class FrontWatchPolicyTest {

    @Test
    fun `delay is five minutes`() {
        assertEquals(300_000L, FrontWatchPolicy.DELAY_MS)
    }

    // ---- shouldStart: the full 8-row truth table (interactive, overlayGranted, visible)

    @Test
    fun `shouldStart truth table`() {
        val table = listOf(
            Triple(false, false, false) to false,
            Triple(false, false, true) to false,
            Triple(false, true, false) to false,
            Triple(false, true, true) to false,
            Triple(true, false, false) to false,
            Triple(true, false, true) to false,
            Triple(true, true, false) to true,
            Triple(true, true, true) to false,
        )
        assertEquals(8, table.size)
        for ((input, expected) in table) {
            val (interactive, overlay, visible) = input
            assertEquals(
                "interactive=$interactive overlay=$overlay visible=$visible",
                expected,
                FrontWatchPolicy.shouldStart(interactive, overlay, visible),
            )
        }
    }

    @Test
    fun `shouldStart is true only for screen on, overlay granted and kiosk not visible`() {
        assertTrue(FrontWatchPolicy.shouldStart(interactive = true, overlayGranted = true, visible = false))
        // each single condition flipped on its own turns it off
        assertFalse(FrontWatchPolicy.shouldStart(interactive = false, overlayGranted = true, visible = false))
        assertFalse(FrontWatchPolicy.shouldStart(interactive = true, overlayGranted = false, visible = false))
        assertFalse(FrontWatchPolicy.shouldStart(interactive = true, overlayGranted = true, visible = true))
    }

    // ---- shouldRearm

    @Test
    fun `shouldRearm is true while not visible and false while visible`() {
        assertTrue(FrontWatchPolicy.shouldRearm(visible = false))
        assertFalse(FrontWatchPolicy.shouldRearm(visible = true))
    }

    // ---- sinceAwayMs

    @Test
    fun `sinceAwayMs is null when there is no away timestamp`() {
        assertNull(FrontWatchPolicy.sinceAwayMs(null, 123_456L))
        assertNull(FrontWatchPolicy.sinceAwayMs(null, 0L))
    }

    @Test
    fun `sinceAwayMs is null when the clock went backwards`() {
        assertNull(FrontWatchPolicy.sinceAwayMs(10_000L, 9_999L))
        assertNull(FrontWatchPolicy.sinceAwayMs(5L, 0L))
        assertNull(FrontWatchPolicy.sinceAwayMs(Long.MAX_VALUE, 0L))
    }

    @Test
    fun `sinceAwayMs is zero when now equals the away time`() {
        assertEquals(0L, FrontWatchPolicy.sinceAwayMs(10_000L, 10_000L))
        assertEquals(0L, FrontWatchPolicy.sinceAwayMs(0L, 0L))
    }

    @Test
    fun `sinceAwayMs is the positive elapsed time`() {
        assertEquals(1L, FrontWatchPolicy.sinceAwayMs(10_000L, 10_001L))
        assertEquals(300_000L, FrontWatchPolicy.sinceAwayMs(1_000L, 301_000L))
        assertEquals(540_000L, FrontWatchPolicy.sinceAwayMs(0L, 540_000L))
    }
}
