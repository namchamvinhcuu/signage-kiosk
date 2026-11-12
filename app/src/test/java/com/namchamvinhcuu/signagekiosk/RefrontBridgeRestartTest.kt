package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: a restart asked by the page through the JS bridge must be brought back to the front like our own restarts. */
class RefrontBridgeRestartTest {

    private val window = RefrontPolicy.WINDOW_MS

    private fun refront(reason: String?, since: Long = 1_000L, tried: Boolean = false, restarting: Boolean = false) =
        RefrontPolicy.shouldRefront(reason, since, tried, restarting)

    @Test
    fun `bridge restart reason is refronted inside the window`() {
        assertEquals("bridge_restart", BridgePolicy.RESTART_REASON)
        assertTrue(refront(BridgePolicy.RESTART_REASON, since = 0))
        assertTrue(refront(BridgePolicy.RESTART_REASON, since = window - 1))
    }

    @Test
    fun `bridge restart reason is not refronted outside the window`() {
        assertFalse(refront(BridgePolicy.RESTART_REASON, since = window))
        assertFalse(refront(BridgePolicy.RESTART_REASON, since = -1))
    }

    @Test
    fun `bridge restart reason is not refronted when already tried or restarting`() {
        assertFalse(refront(BridgePolicy.RESTART_REASON, tried = true))
        assertFalse(refront(BridgePolicy.RESTART_REASON, restarting = true))
    }

    @Test
    fun `other reasons stay not refronted`() {
        assertFalse(refront("exit_by_user"))
        assertFalse(refront(null))
        assertFalse(refront("crash_X"))
        assertFalse(refront("bridge_restart_x"))
        assertFalse(refront(""))
    }

    @Test
    fun `existing own restart reasons still refront`() {
        assertTrue(refront(RestartPolicy.Reason.PERIODIC.wire))
        assertTrue(refront(RestartPolicy.Reason.LOW_MEMORY.wire))
    }
}
