package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPolicyTest {

    private fun paired(base: String, candidate: String?) = PairingPolicy.isPairedUrl(base, candidate)

    @Test
    fun `player url of the same origin with a token is accepted`() {
        assertTrue(paired("https://odoo.example.com", "https://odoo.example.com/signage/tv/tv-01?token=abc123"))
        assertTrue(paired("http://192.168.1.10:8069", "http://192.168.1.10:8069/signage/tv/tv-01?token=abc123"))
    }

    @Test
    fun `token may sit among other query parameters and a fragment is ignored`() {
        assertTrue(paired("https://h", "https://h/signage/tv/c?a=1&token=t&b=2"))
        assertTrue(paired("https://h", "https://h/signage/tv/c?token=t#frag"))
    }

    @Test
    fun `host is compared case-insensitively and default ports are equal to no port`() {
        assertTrue(paired("https://Odoo.Example.com", "https://odoo.example.com/signage/tv/c?token=t"))
        assertTrue(paired("http://h", "http://h:80/signage/tv/c?token=t"))
        assertTrue(paired("https://h:443", "https://h/signage/tv/c?token=t"))
    }

    @Test
    fun `a base path prefix is required in the player url`() {
        assertTrue(paired("https://h/odoo", "https://h/odoo/signage/tv/c?token=t"))
        assertFalse(paired("https://h/odoo", "https://h/signage/tv/c?token=t"))
        assertFalse(paired("https://h/odoo", "https://h/other/signage/tv/c?token=t"))
    }

    @Test
    fun `a different host port or lookalike host is rejected`() {
        assertFalse(paired("https://h", "https://evil.com/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https://h.evil.com/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https://evil.com@h/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "https://h:8443/signage/tv/c?token=t"))
        assertFalse(paired("http://h:8069", "http://h:8070/signage/tv/c?token=t"))
    }

    @Test
    fun `userinfo in the candidate is rejected`() {
        assertFalse(paired("https://h", "https://user:pw@h/signage/tv/c?token=t"))
    }

    @Test
    fun `an http to https upgrade of the same host is accepted but never a downgrade`() {
        assertTrue(paired("http://h", "https://h/signage/tv/c?token=t"))
        assertTrue(paired("http://h:8069", "https://h:8069/signage/tv/c?token=t"))
        assertFalse(paired("http://h", "https://h:8443/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "http://h/signage/tv/c?token=t"))
    }

    @Test
    fun `a url without a usable token is rejected`() {
        assertFalse(paired("https://h", "https://h/signage/tv/c"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token="))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?xtoken=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?a=token=t"))
    }

    @Test
    fun `only exactly one segment after the tv path is a code`() {
        assertFalse(paired("https://h", "https://h/signage/tv/?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/a/b?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tvx/c?token=t"))
        assertFalse(paired("https://h", "https://h/signage/pair?token=t"))
    }

    @Test
    fun `the pairing page itself is not a paired url`() {
        assertFalse(paired("https://h", "https://h/signage/pair"))
    }

    @Test
    fun `null garbage and non http input is rejected on either side`() {
        assertFalse(paired("https://h", null))
        assertFalse(paired("https://h", ""))
        assertFalse(paired("https://h", "not a url"))
        assertFalse(paired("https://h", "ftp://h/signage/tv/c?token=t"))
        assertFalse(paired("https://h", "javascript:alert(1)"))
        assertFalse(paired("about:blank", "https://h/signage/tv/c?token=t"))
        assertFalse(paired("", "https://h/signage/tv/c?token=t"))
    }

    // Hardening found by the test-writer: these are not player pages even though they sit on the same origin.
    @Test
    fun `dot segments and encoded separators are not a code`() {
        for (code in listOf("..", ".", "%2e%2e", "%2E", "a%2Fb", "a%2fb", "a%5Cb", "a\\b")) {
            assertFalse("code '$code'", paired("https://h", "https://h/signage/tv/$code?token=t"))
        }
    }

    @Test
    fun `a percent-encoded plain code is still a code`() {
        assertTrue(paired("https://h", "https://h/signage/tv/tv%2D01?token=t"))
    }

    @Test
    fun `the first token parameter decides and a blank token is no token`() {
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=&token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=%20"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=+"))
        assertTrue(paired("https://h", "https://h/signage/tv/c?token=t&token="))
    }

    @Test
    fun `a malformed percent escape is rejected instead of throwing`() {
        assertFalse(paired("https://h", "https://h/signage/tv/c%?token=t"))
        assertFalse(paired("https://h", "https://h/signage/tv/c?token=%zz"))
    }
}
