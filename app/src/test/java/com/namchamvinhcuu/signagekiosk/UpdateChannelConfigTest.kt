package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateChannelConfigTest {

    private val unconfigured = KioskConfig.DEFAULT
    private val configuredIp = KioskConfig("http://192.168.1.10:8069", 240, 0)
    private val configuredHost = KioskConfig("https://odoo.example.com", 240, 0)

    // ---- normalizeUpdateChannel ----

    @Test
    fun `normalize accepts the three wire values`() {
        assertEquals(UpdateChannel.OFF, ConfigValidator.normalizeUpdateChannel("off"))
        assertEquals(UpdateChannel.STABLE, ConfigValidator.normalizeUpdateChannel("stable"))
        assertEquals(UpdateChannel.CANARY, ConfigValidator.normalizeUpdateChannel("canary"))
    }

    @Test
    fun `normalize ignores case and surrounding whitespace`() {
        assertEquals(UpdateChannel.STABLE, ConfigValidator.normalizeUpdateChannel("STABLE"))
        assertEquals(UpdateChannel.CANARY, ConfigValidator.normalizeUpdateChannel("CaNaRy"))
        assertEquals(UpdateChannel.OFF, ConfigValidator.normalizeUpdateChannel("off "))
        assertEquals(UpdateChannel.STABLE, ConfigValidator.normalizeUpdateChannel("  stable\n"))
    }

    @Test
    fun `normalize rejects empty blank null and unknown values`() {
        for (v in listOf("", "   ", null, "beta", "stable,canary", "stable canary", "on", "latest")) {
            assertNull("[$v]", ConfigValidator.normalizeUpdateChannel(v))
        }
    }

    // ---- acceptIntentUpdateChannel ----

    @Test
    fun `unconfigured TV accepts valid intent values`() {
        assertEquals(UpdateChannel.STABLE, ConfigValidator.acceptIntentUpdateChannel(unconfigured, "stable"))
        assertEquals(UpdateChannel.CANARY, ConfigValidator.acceptIntentUpdateChannel(unconfigured, " Canary "))
        assertEquals(UpdateChannel.OFF, ConfigValidator.acceptIntentUpdateChannel(unconfigured, "off"))
    }

    @Test
    fun `unconfigured TV rejects invalid intent values`() {
        for (v in listOf("", " ", null, "beta", "stable,canary")) {
            assertNull("[$v]", ConfigValidator.acceptIntentUpdateChannel(unconfigured, v))
        }
    }

    @Test
    fun `configured TV ignores the intent extra even for a valid value`() {
        // Exported-activity gate: any app can send the extra, so a configured TV must not change release line this way.
        for (cfg in listOf(configuredIp, configuredHost)) {
            for (v in listOf("canary", "stable", "off")) {
                assertNull("${cfg.baseUrl} $v", ConfigValidator.acceptIntentUpdateChannel(cfg, v))
            }
        }
    }

    @Test
    fun `an unconfigured config built via copy still accepts and a configured copy does not`() {
        val stillUnconfigured = configuredIp.copy(baseUrl = KioskConfig.DEFAULT_BASE_URL)
        assertFalse(stillUnconfigured.isConfigured)
        assertEquals(UpdateChannel.CANARY, ConfigValidator.acceptIntentUpdateChannel(stillUnconfigured, "canary"))
        assertNull(ConfigValidator.acceptIntentUpdateChannel(unconfigured.copy(baseUrl = "https://h.example"), "canary"))
    }

    // ---- KioskConfig ----

    @Test
    fun `default update channel is off and DEFAULT equals the 3-arg config`() {
        assertEquals(UpdateChannel.OFF, KioskConfig.DEFAULT.updateChannel)
        assertEquals(KioskConfig("about:blank", 240, 0), KioskConfig.DEFAULT)
        assertEquals(KioskConfig("about:blank", 240, 0, UpdateChannel.OFF), KioskConfig.DEFAULT)
    }

    @Test
    fun `copy of updateChannel keeps the other fields and equality sees the channel`() {
        val base = KioskConfig("https://odoo.example.com", 60, 300)
        val c = base.copy(updateChannel = UpdateChannel.CANARY)
        assertEquals("https://odoo.example.com", c.baseUrl)
        assertEquals(60, c.restartMin)
        assertEquals(300, c.lowMemMb)
        assertEquals(UpdateChannel.CANARY, c.updateChannel)
        assertFalse(base == c)
        assertEquals(base, c.copy(updateChannel = UpdateChannel.OFF))
    }
}
