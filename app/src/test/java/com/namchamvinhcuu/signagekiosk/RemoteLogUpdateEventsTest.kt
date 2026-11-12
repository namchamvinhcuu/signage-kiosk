package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-update events through RemoteLogPolicy, and the pinned UpdatePolicy timings (plan kiosk-selfupdate 8.4b). */
class RemoteLogUpdateEventsTest {

    private val stages = listOf(
        "app_update_skip", "app_update_download", "app_update_commit", "app_update_install_failed",
        "app_update_install_stalled", "app_update_result", "app_update_fetch_failed", "app_update_verify_failed",
    )

    @Test
    fun `every app_update stage maps to watchdog`() {
        for (e in stages) assertEquals(e, "watchdog", RemoteLogPolicy.serverEvent(e))
    }

    @Test
    fun `app_update without a stage and app_updates_x do not map`() {
        assertNull(RemoteLogPolicy.serverEvent("app_update"))
        assertNull(RemoteLogPolicy.serverEvent("app_updates_x"))
        assertNull(RemoteLogPolicy.serverEvent("xapp_update_skip"))
    }

    @Test
    fun `existing mappings are unchanged`() {
        val expected = mapOf(
            "app_start" to "boot", "app_status" to "heartbeat", "app_load_error" to "http_error",
            "app_render_gone" to "watchdog", "app_recreate" to "watchdog", "app_hang" to "watchdog",
            "app_restart" to "watchdog", "app_trim" to "watchdog", "app_refront" to "watchdog", "app_error" to "watchdog",
        )
        for ((k, v) in expected) assertEquals(k, v, RemoteLogPolicy.serverEvent(k))
        assertNull(RemoteLogPolicy.serverEvent("app_auth_403"))
        assertNull(RemoteLogPolicy.serverEvent("app_auth_revoked"))
    }

    @Test
    fun `watchdog body for app_update_result keeps fields and scrubs the token`() {
        val bytes = RemoteLogPolicy.body(
            "watchdog",
            mapOf("kind" to "app_update_result", "status" to "failure", "code" to 3, "message" to "bad token=abc123 here"),
            "SECRETTOKEN123",
        )
        assertNotNull(bytes)
        val s = String(bytes!!, Charsets.UTF_8)
        assertTrue(s.startsWith("{\"event\":\"watchdog\""))
        assertTrue(s.contains("\"kind\":\"app_update_result\""))
        assertTrue(s.contains("\"code\":3"))
        assertFalse(s, s.contains("abc123"))
        assertTrue(s, s.contains("token=<redacted>"))
    }

    @Test
    fun `watchdog body stays within 2048 bytes and 20 keys`() {
        val fields = LinkedHashMap<String, Any?>()
        fields["kind"] = "app_update_result"
        for (i in 1..40) fields["k$i"] = "v".repeat(200)
        val bytes = RemoteLogPolicy.body("watchdog", fields, "SECRETTOKEN123")!!
        assertTrue(bytes.size <= RemoteLogPolicy.MAX_BODY_BYTES)
        val data = String(bytes, Charsets.UTF_8).substringAfter("\"data\":")
        assertTrue(Regex("\"[^\"]+\":").findAll(data).count() <= RemoteLogPolicy.MAX_KEYS)
    }

    @Test
    fun `update policy timings are pinned`() {
        assertEquals(10 * 60_000L, UpdatePolicy.INSTALL_FAILSAFE_MS)
        assertEquals(2 * 60_000L, UpdatePolicy.CHECK_START_DELAY_MS)
        assertEquals(30 * 60_000L, UpdatePolicy.CHECK_INTERVAL_MS)
    }
}
