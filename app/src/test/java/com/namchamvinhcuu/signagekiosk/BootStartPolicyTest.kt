package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision of KioskStartService: reopen the kiosk only on a connect shortly after DEVICE boot while the kiosk
 * is not in front. A reconnect after a process death (operator exit, periodic restart, OS kill, self-update) happens
 * at a large device uptime and must NOT reopen it.
 */
class BootStartPolicyTest {

    private val window = BootStartPolicy.BOOT_WINDOW_MS

    @Test
    fun `boot window is five minutes`() {
        assertEquals(300_000L, BootStartPolicy.BOOT_WINDOW_MS)
    }

    // ---- boundaries of the uptime window (kiosk not in front)

    @Test
    fun `starts at uptime 0, 1 and one millisecond below the window`() {
        assertTrue(BootStartPolicy.shouldStart(0L, false))
        assertTrue(BootStartPolicy.shouldStart(1L, false))
        assertTrue(BootStartPolicy.shouldStart(window - 1, false))
    }

    @Test
    fun `does not start at the window, just above it, or after a normal 6 h uptime`() {
        assertFalse(BootStartPolicy.shouldStart(window, false))
        assertFalse(BootStartPolicy.shouldStart(window + 1, false))
        assertFalse(BootStartPolicy.shouldStart(6 * 60 * 60_000L, false))
        assertFalse(BootStartPolicy.shouldStart(Long.MAX_VALUE, false))
    }

    @Test
    fun `negative uptime never starts`() {
        assertFalse(BootStartPolicy.shouldStart(-1L, false))
        assertFalse(BootStartPolicy.shouldStart(-window, false))
        assertFalse(BootStartPolicy.shouldStart(Long.MIN_VALUE, false))
    }

    // ---- truth table: (uptime inside window?, kioskInFront) -> start?

    @Test
    fun `shouldStart truth table`() {
        val table = listOf(
            Triple(true, false, true),    // just booted, kiosk not in front -> start
            Triple(true, true, false),    // just booted, already in front -> leave alone
            Triple(false, false, false),  // process-death reconnect, not in front (operator exit) -> stay exited
            Triple(false, true, false),   // process-death reconnect, in front -> leave alone
        )
        assertEquals(4, table.size)
        for ((insideWindow, inFront, expected) in table) {
            val uptime = if (insideWindow) window / 2 else window * 2
            assertEquals(
                "insideWindow=$insideWindow kioskInFront=$inFront",
                expected,
                BootStartPolicy.shouldStart(uptime, inFront),
            )
        }
    }

    @Test
    fun `kiosk in front blocks the start at every boundary uptime`() {
        for (uptime in listOf(0L, 1L, window - 1)) {
            assertFalse("uptime=$uptime", BootStartPolicy.shouldStart(uptime, true))
        }
    }

    // ---- contract: the caller supplies DEVICE uptime (documented here, NOT guarded: the wiring is in KioskStartService)

    @Test
    fun `documents that the caller must pass device uptime not process uptime`() {
        // A process that died and restarted seconds ago on a TV up for 2 days: the caller must pass the DEVICE
        // uptime (SystemClock.elapsedRealtime), not the process uptime. Passing a process uptime of a few seconds
        // here would wrongly start; the policy cannot tell the difference, so this documents the contract.
        val deviceUptimeTwoDays = 48 * 60 * 60_000L
        val processUptimeFewSeconds = 5_000L
        assertFalse(BootStartPolicy.shouldStart(deviceUptimeTwoDays, false))
        assertTrue(BootStartPolicy.shouldStart(processUptimeFewSeconds, false))
    }
}
