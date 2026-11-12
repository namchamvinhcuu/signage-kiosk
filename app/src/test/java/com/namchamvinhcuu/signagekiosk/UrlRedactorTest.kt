package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UrlRedactorTest {

    private fun assertNoSecrets(out: String?) {
        for (bad in listOf("token", "secret", "user", "pass", "@", "?")) {
            assertFalse("'$bad' leaked into $out", out!!.contains(bad))
        }
    }

    @Test
    fun `plain page url is kept including the hash route`() {
        assertEquals("http://h:13000/#checkin", UrlRedactor.redactUrl("http://h:13000/#checkin"))
    }

    @Test
    fun `userinfo and query are dropped while port path and fragment stay`() {
        val out = UrlRedactor.redactUrl("https://user:pass@host:8443/a/b?token=secret&x=1#frag")
        assertEquals("https://host:8443/a/b#frag", out)
        assertNoSecrets(out)
    }

    @Test
    fun `query without a path is dropped`() {
        val out = UrlRedactor.redactUrl("http://host?token=secret")
        assertEquals("http://host", out)
        assertNoSecrets(out)
    }

    @Test
    fun `userinfo without password is dropped`() {
        val out = UrlRedactor.redactUrl("http://user@host/p")
        assertEquals("http://host/p", out)
        assertNoSecrets(out)
    }

    @Test
    fun `no port means no colon after the host`() {
        assertEquals("http://host/p", UrlRedactor.redactUrl("http://host/p"))
    }

    @Test
    fun `input is trimmed`() {
        assertEquals("http://host/p", UrlRedactor.redactUrl("  http://host/p?token=secret \n"))
    }

    @Test
    fun `path keeps its percent encoding`() {
        assertEquals("http://host/a%20b", UrlRedactor.redactUrl("http://host/a%20b?token=secret"))
    }

    @Test
    fun `other schemes with a host are redacted the same way`() {
        val out = UrlRedactor.redactUrl("ftp://user:pass@host/x?token=secret")
        assertEquals("ftp://host/x", out)
        assertNoSecrets(out)
    }

    @Test
    fun `null unparsable and hostless input give null`() {
        for (raw in listOf(null, "", "   ", "not a url", "javascript:alert(1)", "about:blank", "file:///sdcard/x", "http:///x", "/relative?token=secret")) {
            assertNull("expected null for '$raw'", UrlRedactor.redactUrl(raw))
        }
    }

    // Regression test: a query inside a hash-SPA route leaked verbatim
    // (redactUrl("http://h:1/#checkin?token=secret") returned the token, round-2 finding, UrlRedactor fragment handling).
    @Test
    fun `query inside the fragment is dropped Fix_hash_route_token_leak`() {
        val out = UrlRedactor.redactUrl("http://h:1/#checkin?token=secret")
        assertEquals("http://h:1/#checkin", out)
        assertNoSecrets(out)
    }

    @Test
    fun `fragment that is only a query leaves a bare hash`() {
        assertEquals("http://h:1/#", UrlRedactor.redactUrl("http://h:1/#?x=1"))
    }

    @Test
    fun `query in the url and in the fragment are both dropped`() {
        val out = UrlRedactor.redactUrl("http://user:pass@h:1/p?token=secret#checkin?token=secret&y=2")
        assertEquals("http://h:1/p#checkin", out)
        assertNoSecrets(out)
    }

    @Test
    fun `unparsable url with credentials is not echoed back`() {
        // A space makes java.net.URI throw: the raw text (with its secrets) must not be logged.
        assertNull(UrlRedactor.redactUrl("http://user:pass@host/a b?token=secret"))
    }
}
