package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigValidatorTest {

    // --- validateUrl ---

    @Test
    fun `http url with host is accepted and trimmed`() {
        assertEquals(
            "http://signage.example.com:8069/",
            ConfigValidator.validateUrl("  http://signage.example.com:8069/ \n"),
        )
    }

    @Test
    fun `https url with path query and fragment is accepted unchanged`() {
        val url = "https://signage.example.com/viewer?x=1#checkin"
        assertEquals(url, ConfigValidator.validateUrl(url))
    }

    @Test
    fun `scheme check is case insensitive and keeps the original text`() {
        assertEquals("HTTP://Host:8080/", ConfigValidator.validateUrl("HTTP://Host:8080/"))
    }

    @Test
    fun `default base url is blank and is not a valid server url`() {
        assertEquals("about:blank", KioskConfig.DEFAULT_BASE_URL)
        assertNull(ConfigValidator.validateUrl(KioskConfig.DEFAULT_BASE_URL))
        assertNull(ConfigValidator.validateBaseUrl(KioskConfig.DEFAULT_BASE_URL))
    }

    @Test
    fun `null empty and blank are rejected`() {
        assertNull(ConfigValidator.validateUrl(null))
        assertNull(ConfigValidator.validateUrl(""))
        assertNull(ConfigValidator.validateUrl("   \t"))
    }

    @Test
    fun `non http schemes are rejected`() {
        for (raw in listOf(
            "ftp://host/file",
            "javascript:alert(1)",
            "file:///sdcard/index.html",
            "content://com.example/x",
            "data:text/html,<b>x</b>",
            "host:13000",
        )) {
            assertNull("expected rejection of $raw", ConfigValidator.validateUrl(raw))
        }
    }

    @Test
    fun `http without a host is rejected`() {
        for (raw in listOf("http://", "http:///path", "http:/path", "https://:8080/", "http:")) {
            assertNull("expected rejection of $raw", ConfigValidator.validateUrl(raw))
        }
    }

    @Test
    fun `garbage and relative input is rejected`() {
        for (raw in listOf("not a url", "example.com:8069", "/checkin", "http://exa mple.com/", "%%%")) {
            assertNull("expected rejection of $raw", ConfigValidator.validateUrl(raw))
        }
    }

    // --- normalizeRestartMin (0 = off, negative = rejected, else 15..1440) ---

    @Test
    fun `restartMin zero means off`() {
        assertEquals(0, ConfigValidator.normalizeRestartMin(0))
    }

    @Test
    fun `restartMin negative is rejected`() {
        assertNull(ConfigValidator.normalizeRestartMin(-1))
        assertNull(ConfigValidator.normalizeRestartMin(Int.MIN_VALUE))
    }

    @Test
    fun `restartMin is clamped at the low edge`() {
        assertEquals(15, ConfigValidator.normalizeRestartMin(1))
        assertEquals(15, ConfigValidator.normalizeRestartMin(14))
        assertEquals(15, ConfigValidator.normalizeRestartMin(15))
        assertEquals(16, ConfigValidator.normalizeRestartMin(16))
    }

    @Test
    fun `restartMin is clamped at the high edge`() {
        assertEquals(240, ConfigValidator.normalizeRestartMin(240))
        assertEquals(1439, ConfigValidator.normalizeRestartMin(1439))
        assertEquals(1440, ConfigValidator.normalizeRestartMin(1440))
        assertEquals(1440, ConfigValidator.normalizeRestartMin(1441))
        assertEquals(1440, ConfigValidator.normalizeRestartMin(Int.MAX_VALUE))
    }

    // --- normalizeLowMemMb (0 = off, negative = rejected, else 50..4096) ---

    @Test
    fun `lowMemMb zero means off`() {
        assertEquals(0, ConfigValidator.normalizeLowMemMb(0))
    }

    @Test
    fun `lowMemMb negative is rejected`() {
        assertNull(ConfigValidator.normalizeLowMemMb(-1))
        assertNull(ConfigValidator.normalizeLowMemMb(Int.MIN_VALUE))
    }

    @Test
    fun `lowMemMb is clamped at the low edge`() {
        assertEquals(50, ConfigValidator.normalizeLowMemMb(1))
        assertEquals(50, ConfigValidator.normalizeLowMemMb(49))
        assertEquals(50, ConfigValidator.normalizeLowMemMb(50))
        assertEquals(51, ConfigValidator.normalizeLowMemMb(51))
    }

    @Test
    fun `lowMemMb is clamped at the high edge`() {
        assertEquals(4095, ConfigValidator.normalizeLowMemMb(4095))
        assertEquals(4096, ConfigValidator.normalizeLowMemMb(4096))
        assertEquals(4096, ConfigValidator.normalizeLowMemMb(4097))
        assertEquals(4096, ConfigValidator.normalizeLowMemMb(Int.MAX_VALUE))
    }

    // --- validateBaseUrl (server base: no userinfo/query/fragment, trailing slashes dropped) ---

    @Test
    fun `base url is trimmed and loses trailing slashes`() {
        assertEquals("https://odoo.example.com", ConfigValidator.validateBaseUrl(" https://odoo.example.com/ \n"))
        assertEquals("http://h:8069", ConfigValidator.validateBaseUrl("http://h:8069///"))
    }

    @Test
    fun `base url keeps a path prefix`() {
        assertEquals("https://example.com/odoo", ConfigValidator.validateBaseUrl("https://example.com/odoo/"))
    }

    @Test
    fun `base url with query fragment or userinfo is rejected`() {
        for (raw in listOf(
            "https://h/?token=secret",
            "https://h/#frag",
            "https://h/signage/pair?x=1",
            "https://user:pass@h/",
            "https://user@h/",
        )) {
            assertNull("expected rejection of $raw", ConfigValidator.validateBaseUrl(raw))
        }
    }

    @Test
    fun `base url without a path and with a port or ipv6 host is accepted`() {
        assertEquals("https://h", ConfigValidator.validateBaseUrl("https://h"))
        assertEquals("http://10.0.0.5:8069", ConfigValidator.validateBaseUrl("http://10.0.0.5:8069/"))
        assertEquals("http://[::1]:8069", ConfigValidator.validateBaseUrl("http://[::1]:8069/"))
    }

    @Test
    fun `base url keeps uppercase scheme text and percent-encoded path`() {
        assertEquals("HTTPS://Host:8080/x", ConfigValidator.validateBaseUrl("HTTPS://Host:8080/x/"))
        assertEquals("https://h/a%20b", ConfigValidator.validateBaseUrl("https://h/a%20b/"))
    }

    @Test
    fun `base url with inner whitespace or only a slash path is rejected`() {
        for (raw in listOf("https://h/a b", "https://h /", "/", "///", "https:///")) {
            assertNull("expected rejection of $raw", ConfigValidator.validateBaseUrl(raw))
        }
    }

    @Test
    fun `base url with an empty query or empty fragment marker is rejected`() {
        for (raw in listOf("https://h?", "https://h/#", "https://h?x=1")) {
            assertNull("expected rejection of $raw", ConfigValidator.validateBaseUrl(raw))
        }
    }

    @Test
    fun `pairUrl of ipv6 port and encoded-path bases`() {
        assertEquals(
            "http://[::1]:8069/signage/pair",
            KioskConfig(ConfigValidator.validateBaseUrl("http://[::1]:8069/")!!, 240, 0).pairUrl,
        )
        assertEquals(
            "https://h/a%20b/signage/pair",
            KioskConfig(ConfigValidator.validateBaseUrl("https://h/a%20b/")!!, 240, 0).pairUrl,
        )
    }

    @Test
    fun `pair path suffix is stripped only as a whole trailing segment`() {
        assertEquals("https://h/signage/pairx", ConfigValidator.validateBaseUrl("https://h/signage/pairx"))
        assertEquals("https://h/signage/pair/x", ConfigValidator.validateBaseUrl("https://h/signage/pair/x/"))
        assertEquals("https://h/x", ConfigValidator.validateBaseUrl("https://h/x/signage/pair///"))
    }

    @Test
    fun `host named signage keeps its path and a doubled pair paste is stripped once`() {
        assertEquals("http://signage/pair/x", ConfigValidator.validateBaseUrl("http://signage/pair/x"))
        assertEquals("http://signage:8069", ConfigValidator.validateBaseUrl("http://signage:8069/signage/pair"))
        assertEquals(
            "https://h/signage/pair",
            ConfigValidator.validateBaseUrl("https://h/signage/pair/signage/pair"),
        )
    }

    @Test
    fun `base url with an empty userinfo is rejected`() {
        assertNull(ConfigValidator.validateBaseUrl("https://@h/"))
    }

    @Test
    fun `base url rejects what validateUrl rejects`() {
        for (raw in listOf(null, "", "  ", "ftp://h/", "javascript:alert(1)", "not a url", "http://", "about:blank")) {
            assertNull("expected rejection of $raw", ConfigValidator.validateBaseUrl(raw))
        }
    }
}
