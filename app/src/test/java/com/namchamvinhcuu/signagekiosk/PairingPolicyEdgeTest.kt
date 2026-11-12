package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of [PairingPolicy.isPairedUrl] beyond PairingPolicyTest (step 3 audit). */
class PairingPolicyEdgeTest {

    private fun paired(base: String, candidate: String?) = PairingPolicy.isPairedUrl(base, candidate)

    @Test
    fun `base with a trailing slash or a prefix with a trailing slash behaves like without`() {
        assertTrue(paired("https://h/", "https://h/signage/tv/c?token=t"))
        assertTrue(paired("https://h/odoo/", "https://h/odoo/signage/tv/c?token=t"))
        assertFalse(paired("https://h/odoo/", "https://h/odoo//signage/tv/c?token=t"))
    }

    @Test
    fun `path is case-sensitive but scheme is not`() {
        assertFalse(paired("https://h", "https://h/Signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https://h/signage/TV/c?token=t"))
        assertTrue(paired("https://h", "HTTPS://h/signage/tv/c?token=t"))
        assertTrue(paired("HTTPS://h", "https://h/signage/tv/c?token=t"))
    }

    @Test
    fun `ipv6 host needs the same port`() {
        assertTrue(paired("http://[::1]:8069", "http://[::1]:8069/signage/tv/c?token=t"))
        assertFalse(paired("http://[::1]:8069", "http://[::1]:8070/signage/tv/c?token=t"))
        assertFalse(paired("http://[::1]:8069", "http://[::2]:8069/signage/tv/c?token=t"))
    }

    @Test
    fun `non numeric port is rejected and port 0 never matches a default port`() {
        assertFalse(paired("https://h", "https://h:abc/signage/tv/c?token=t"))
        assertFalse(paired("https://h:0", "https://h/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https://h:0/signage/tv/c?token=t"))
    }

    @Test
    fun `semicolon is not a parameter separator`() {
        assertFalse(paired("https://h", "https://h/signage/tv/c?a=1;token=t"))
    }

    @Test
    fun `a percent-encoded parameter name is not recognised as token`() {
        assertFalse(paired("https://h", "https://h/signage/tv/c?%74oken=t"))
    }

    @Test
    fun `whitespace around the candidate is trimmed but inside it is rejected`() {
        assertTrue(paired("https://h", "  https://h/signage/tv/c?token=t \n"))
        assertFalse(paired("https://h", "https://h/signage/tv/c d?token=t"))
        assertFalse(paired("https://h", "https://h /signage/tv/c?token=t"))
    }

    @Test
    fun `very long code and token are accepted without error`() {
        val long = "a".repeat(10_000)
        assertTrue(paired("https://h", "https://h/signage/tv/$long?token=$long"))
    }

    @Test
    fun `round 2 dot segments in any encoding case or mix are rejected, double encoding is one literal segment`() {
        assertFalse(paired("https://h", "https://h/signage/tv/%2E%2E?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/.%2e?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/%2e?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/%5c?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/a%5Cb?token=t"))
        // decoded once the server sees the literal text "%2e", not a dot segment
        assertTrue(paired("https://h", "https://h/signage/tv/%252e?token=t"))
        // a segment that merely contains dots is a normal code
        assertTrue(paired("https://h", "https://h/signage/tv/a..b?token=t"))
        assertTrue(paired("https://h", "https://h/signage/tv/...?token=t"))
    }

    @Test
    fun `round 2 token value forms`() {
        assertTrue(paired("https://h", "https://h/signage/tv/c?token=a=b"))
        assertTrue(paired("https://h", "https://h/signage/tv/c?token=a+b"))
        assertTrue(paired("https://h", "https://h/signage/tv/c?token=%41"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=+"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=%20%09"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=%"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=%zz"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=&token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/%/x?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/c%?token=t"))
    }

    @Test
    fun `userinfo in the candidate is rejected even with an empty password or user`() {
        assertFalse(paired("https://h", "https://@h/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https://u@h/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https://:p@h/signage/tv/c?token=t"))
    }

    @Test
    fun `scheme-relative and opaque candidates are rejected`() {
        assertFalse(paired("https://h", "//h/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https:h/signage/tv/c?token=t"))
    }
}
