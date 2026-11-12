package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskConfigTest {

    @Test
    fun `DEFAULT carries the blank url and the documented restart and memory defaults`() {
        assertEquals(KioskConfig("about:blank", 240, 0), KioskConfig.DEFAULT)
    }

    @Test
    fun `default numeric values survive normalization unchanged`() {
        assertEquals(KioskConfig.DEFAULT_RESTART_MIN, ConfigValidator.normalizeRestartMin(KioskConfig.DEFAULT_RESTART_MIN))
        assertEquals(KioskConfig.DEFAULT_LOW_MEM_MB, ConfigValidator.normalizeLowMemMb(KioskConfig.DEFAULT_LOW_MEM_MB))
    }

    @Test
    fun `AppStart empty extra still wins over a previous exit reason`() {
        assertEquals(AppStartInfo("", "crash"), AppStart.derive("", "crash"))
    }

    @Test
    fun `pairUrl appends the pair path to the base url`() {
        assertEquals("https://odoo.example.com/signage/pair", KioskConfig("https://odoo.example.com", 240, 0).pairUrl)
        assertEquals("http://h:8069/odoo/signage/pair", KioskConfig("http://h:8069/odoo", 240, 0).pairUrl)
    }

    @Test
    fun `pairUrl stays blank while no server is configured`() {
        assertEquals("about:blank", KioskConfig.DEFAULT.pairUrl)
    }

    @Test
    fun `pairUrl of a validated base url has no double slash`() {
        val base = ConfigValidator.validateBaseUrl("https://odoo.example.com//")!!
        assertEquals("https://odoo.example.com/signage/pair", KioskConfig(base, 240, 0).pairUrl)
    }

    @Test
    fun `a pasted pair url is reduced to its base so the pair path is not doubled`() {
        for (raw in listOf("https://h/signage/pair", "https://h/signage/pair/", "https://h/odoo/signage/pair")) {
            val base = ConfigValidator.validateBaseUrl(raw)!!
            assertEquals(raw.trimEnd('/'), KioskConfig(base, 240, 0).pairUrl)
        }
    }

    @Test
    fun `a pasted pair url on the bare host reduces to its origin`() {
        assertEquals("https://h", ConfigValidator.validateBaseUrl("https://h/signage/pair"))
    }

    // Regression: a host literally named `signage` with path `/pair` overlaps the suffix and was cut to `https:`.
    @Test
    fun `host named signage with path pair is not mistaken for the pair suffix`() {
        assertEquals("https://signage/pair", ConfigValidator.validateBaseUrl("https://signage/pair"))
        assertEquals("http://signage/pair", ConfigValidator.validateBaseUrl("http://signage/pair/"))
        assertEquals("https://signage", ConfigValidator.validateBaseUrl("https://signage/signage/pair"))
    }

    // The exported activity takes the base_url extra only while isConfigured is false.
    @Test
    fun `a config is unconfigured only while the base url is the blank default`() {
        assertFalse(KioskConfig.DEFAULT.isConfigured)
        assertTrue(KioskConfig("http://192.168.1.10:8069", 240, 0).isConfigured)
        assertTrue(KioskConfig("https://odoo.example.com", 240, 0).isConfigured)
    }

    @Test
    fun `isConfigured compares the exact default text only`() {
        for (v in listOf("", " ", "ABOUT:BLANK", "about:blank/", "about:blank ")) {
            assertTrue("expected configured for '$v'", KioskConfig(v, 240, 0).isConfigured)
        }
        assertFalse(KioskConfig(KioskConfig.DEFAULT_BASE_URL, 1, 1).isConfigured)
    }

    @Test
    fun `a LAN ip over http is a valid base url and gives a pair url`() {
        val base = ConfigValidator.validateBaseUrl("http://192.168.1.10:8069/")!!
        assertEquals("http://192.168.1.10:8069/signage/pair", KioskConfig(base, 240, 0).pairUrl)
    }
}
