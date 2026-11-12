package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyTest {

    private val base = "https://odoo.example.com"
    private val paired = "https://odoo.example.com/signage/tv/tv-01?token=abc"
    private val player = "https://odoo.example.com/signage/tv/tv-01?token=abc"

    @Test
    fun `allowed for the player url when paired`() {
        assertTrue(BridgePolicy.isAllowed(base, paired, player))
    }

    @Test
    fun `not allowed when nothing is paired`() {
        assertFalse(BridgePolicy.isAllowed(base, null, player))
    }

    @Test
    fun `not allowed when main frame url is null`() {
        assertFalse(BridgePolicy.isAllowed(base, paired, null))
    }

    @Test
    fun `not allowed for a foreign origin`() {
        assertFalse(BridgePolicy.isAllowed(base, paired, "https://evil.example.com/signage/tv/tv-01?token=abc"))
    }

    @Test
    fun `not allowed for the same host on another port`() {
        assertFalse(BridgePolicy.isAllowed(base, paired, "https://odoo.example.com:8443/signage/tv/tv-01?token=abc"))
        assertFalse(BridgePolicy.isAllowed("http://10.0.0.5:8069", "http://10.0.0.5:8069/signage/tv/c?token=t", "http://10.0.0.5:9000/signage/tv/c?token=t"))
    }

    @Test
    fun `not allowed on the wrong path or without a token`() {
        assertFalse(BridgePolicy.isAllowed(base, paired, "https://odoo.example.com/web/login?token=abc"))
        assertFalse(BridgePolicy.isAllowed(base, paired, "https://odoo.example.com/signage/pair"))
        assertFalse(BridgePolicy.isAllowed(base, paired, "https://odoo.example.com/signage/tv/tv-01"))
        assertFalse(BridgePolicy.isAllowed(base, paired, "https://odoo.example.com/signage/tv/tv-01?token="))
        assertFalse(BridgePolicy.isAllowed(base, paired, "about:blank"))
        assertFalse(BridgePolicy.isAllowed(base, paired, ""))
    }

    @Test
    fun `restart boundary at exactly five minutes`() {
        assertFalse(BridgePolicy.restartAllowed(0))
        assertFalse(BridgePolicy.restartAllowed(299_999))
        assertTrue(BridgePolicy.restartAllowed(300_000))
        assertTrue(BridgePolicy.restartAllowed(300_001))
        assertEquals(300_000L, BridgePolicy.MIN_UPTIME_FOR_RESTART_MS)
    }

    @Test
    fun `recheck allowed when never done and at exactly 10 minutes`() {
        val gap = BridgePolicy.MIN_RECHECK_GAP_MS
        assertEquals(10 * 60_000L, gap)
        assertTrue(BridgePolicy.recheckAllowed(0, null))
        assertFalse(BridgePolicy.recheckAllowed(100_000, 100_000))
        assertFalse(BridgePolicy.recheckAllowed(100_000 + gap - 1, 100_000))
        assertTrue(BridgePolicy.recheckAllowed(100_000 + gap, 100_000))
        assertFalse(BridgePolicy.recheckAllowed(100_000 + 30_000, 100_000)) // the old 30 s gap no longer passes
    }

    @Test
    fun `restart cooldown boundaries`() {
        val gap = BridgePolicy.MIN_RESTART_GAP_MS
        assertEquals(60 * 60_000L, gap)
        val last = 5_000_000L
        assertTrue(BridgePolicy.restartCooldownPassed(last, null))
        assertFalse(BridgePolicy.restartCooldownPassed(last, last)) // now == last
        assertFalse(BridgePolicy.restartCooldownPassed(last + 1, last))
        assertFalse(BridgePolicy.restartCooldownPassed(last + gap - 1, last))
        assertTrue(BridgePolicy.restartCooldownPassed(last + gap, last))
        assertTrue(BridgePolicy.restartCooldownPassed(last + gap + 1, last))
        assertTrue(BridgePolicy.restartCooldownPassed(last + 100 * gap, last)) // far past
        assertTrue(BridgePolicy.restartCooldownPassed(last - 1, last)) // clock went backwards
        assertTrue(BridgePolicy.restartCooldownPassed(0L, last))
    }

    @Test
    fun `memory json is flat and exact`() {
        assertEquals(
            """{"availMb":512,"totalMb":2048,"lowMem":false,"pssMb":300,"upMin":42}""",
            BridgePolicy.memoryJson(512, 2048, false, 300, 42),
        )
        assertTrue(BridgePolicy.memoryJson(1, 2, true, 3, 4).contains("\"lowMem\":true"))
    }

    @Test
    fun `info json is flat and exact and keeps the webview version`() {
        assertEquals(
            """{"app":"SignageKiosk","version":"1.0.0","versionCode":3,"widthPx":1920,"heightPx":1080,"densityDpi":320,"density":2.0,"rotation":0,"webview":"120.0.1"}""",
            BridgePolicy.infoJson("1.0.0", 3, 1920, 1080, 320, 2.0, 0, "120.0.1"),
        )
    }

    @Test
    fun `info json omits a null webview`() {
        val s = BridgePolicy.infoJson("1.0.0", 3, 1920, 1080, 320, 2.0, 0, null)
        assertFalse(s.contains("webview"))
        assertTrue(s.endsWith("\"rotation\":0}"))
    }

    @Test
    fun `info json escapes a version name with quotes`() {
        val s = BridgePolicy.infoJson("1\"0\\", 1, 1, 1, 1, 1.0, 0, "w\n")
        assertTrue(s, s.contains("\"version\":\"1\\\"0\\\\\""))
        assertTrue(s, s.contains("\"webview\":\"w\\u000a\""))
    }
}
