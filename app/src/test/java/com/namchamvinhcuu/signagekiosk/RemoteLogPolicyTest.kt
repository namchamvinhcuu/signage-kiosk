package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogPolicyTest {

    private val base = "https://odoo.example.com"
    private val paired = "https://odoo.example.com/signage/tv/tv-01?token=SECRETTOKEN123"

    private fun bodyText(event: String, fields: Map<String, Any?>, token: String = "SECRETTOKEN123"): String =
        String(RemoteLogPolicy.body(event, fields, token)!!, Charsets.UTF_8)

    // ---- event mapping / whitelist

    @Test
    fun `server events are exactly the contract whitelist`() {
        assertEquals(
            setOf("boot", "heartbeat", "js_error", "http_error", "auth_403", "offline", "online", "reload", "watchdog"),
            RemoteLogPolicy.SERVER_EVENTS,
        )
    }

    @Test
    fun `every mapped app event maps into the whitelist and unmapped ones to null`() {
        val mapped = listOf(
            "app_start", "app_status", "app_load_error", "app_render_gone", "app_recreate",
            "app_hang", "app_restart", "app_trim", "app_refront", "app_error",
        )
        for (e in mapped) assertTrue(e, RemoteLogPolicy.serverEvent(e) in RemoteLogPolicy.SERVER_EVENTS)
        assertEquals("boot", RemoteLogPolicy.serverEvent("app_start"))
        assertNull(RemoteLogPolicy.serverEvent("app_unknown"))
        assertNull(RemoteLogPolicy.serverEvent(""))
        assertNull(RemoteLogPolicy.serverEvent("boot")) // a server event name is not an app event
    }

    @Test
    fun `body refuses an event outside the whitelist`() {
        assertNull(RemoteLogPolicy.body("custom", mapOf("a" to "b"), "tok12345"))
        assertNull(RemoteLogPolicy.body("", emptyMap(), "tok12345"))
    }

    // ---- body shape and limits

    @Test
    fun `simple body is event plus data object and drops null values`() {
        val s = bodyText("boot", linkedMapOf("a" to "x", "n" to null, "i" to 5, "b" to true, "l" to 7L, "d" to 1.5))
        assertEquals("""{"event":"boot","data":{"a":"x","i":5,"b":true,"l":7,"d":1.5}}""", s)
    }

    @Test
    fun `empty fields give an empty data object`() {
        assertEquals("""{"event":"heartbeat","data":{}}""", bodyText("heartbeat", emptyMap()))
    }

    @Test
    fun `non finite double becomes empty string`() {
        assertEquals("""{"event":"boot","data":{"d":""}}""", bodyText("boot", mapOf("d" to Double.NaN)))
    }

    @Test
    fun `more than 20 keys are cut to 20`() {
        val fields = (1..25).associate { "k$it" to "v" }
        val s = bodyText("boot", fields)
        assertTrue(s.contains("\"k20\""))
        assertFalse(s.contains("\"k21\""))
    }

    @Test
    fun `null values do not count toward the key limit`() {
        val fields = linkedMapOf<String, Any?>()
        repeat(5) { fields["n$it"] = null }
        (1..20).forEach { fields["k$it"] = "v" }
        val s = bodyText("boot", fields)
        assertTrue(s.contains("\"k20\""))
    }

    @Test
    fun `key is cut to 40 chars and value to 200 chars`() {
        val s = bodyText("boot", mapOf("k".repeat(60) to "v".repeat(300)))
        assertTrue(s.contains("\"" + "k".repeat(40) + "\":"))
        assertFalse(s.contains("k".repeat(41)))
        assertTrue(s.contains("\"" + "v".repeat(200) + "\""))
        assertFalse(s.contains("v".repeat(201)))
    }

    @Test
    fun `value of exactly 200 chars is kept whole and 199 too`() {
        assertTrue(bodyText("boot", mapOf("a" to "x".repeat(200))).contains("x".repeat(200)))
        assertTrue(bodyText("boot", mapOf("a" to "x".repeat(199))).contains("x".repeat(199)))
    }

    @Test
    fun `body stays within 2048 bytes with 20 keys of long non ascii values`() {
        val fields = (1..20).associate { "key$it" to "ệ".repeat(200) } // 3 bytes UTF-8 each
        val bytes = RemoteLogPolicy.body("watchdog", fields, "tok12345")!!
        assertTrue("size ${bytes.size}", bytes.size <= RemoteLogPolicy.MAX_BODY_BYTES)
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `body stays within 2048 bytes with 20 keys of escaped values`() {
        val fields = (1..20).associate { "key$it" to "\n\"\\".repeat(66) } // each char escapes to 2-6 bytes
        val bytes = RemoteLogPolicy.body("watchdog", fields, "tok12345")!!
        assertTrue("size ${bytes.size}", bytes.size <= RemoteLogPolicy.MAX_BODY_BYTES)
        // still valid: ends with closing braces
        assertTrue(String(bytes, Charsets.UTF_8).endsWith("}}"))
    }

    @Test
    fun `dropping over budget entries removes from the end and keeps earlier ones`() {
        val fields = linkedMapOf<String, Any?>()
        (1..20).forEach { fields["k%02d".format(it)] = "ệ".repeat(200) }
        val s = bodyText("boot", fields)
        assertTrue(s.contains("\"k01\""))
        assertFalse(s.contains("\"k20\""))
    }

    // ---- JSON validity

    @Test
    fun `quotes backslashes control chars and unicode are escaped to valid json`() {
        val s = bodyText("boot", mapOf("q" to "a\"b\\c\n\t\u0001é漢"))
        assertTrue(s, s.contains("\"q\":\"a\\\"b\\\\c\\u000a\\u0009\\u0001é漢\""))
        // no raw control char in the output
        assertTrue(s.none { it < ' ' })
    }

    // ---- scrub / redaction

    @Test
    fun `scrub reduces urls to scheme host path and drops the query`() {
        val out = RemoteLogPolicy.scrub("load failed https://h.example.com:8069/signage/tv/c1?token=SECRETTOKEN123&x=1 end", "SECRETTOKEN123")
        assertEquals("load failed https://h.example.com:8069/signage/tv/c1 end", out)
    }

    @Test
    fun `scrub removes a bare token equals text and the token itself`() {
        val out = RemoteLogPolicy.scrub("bad token=abcdef&x=1 and SECRETTOKEN123 here", "SECRETTOKEN123")
        assertFalse(out.contains("abcdef"))
        assertFalse(out.contains("SECRETTOKEN123"))
        assertTrue(out.contains("token=<redacted>"))
    }

    @Test
    fun `scrub is case insensitive for token and scheme`() {
        val out = RemoteLogPolicy.scrub("Token=Zzz HTTPS://H/p?token=Q", "SECRETTOKEN123")
        assertFalse(out.contains("Zzz"))
        assertFalse(out.contains("=Q"))
    }

    @Test
    fun `url that does not parse becomes url placeholder`() {
        assertEquals("<url>", RemoteLogPolicy.scrub("http://[bad", "SECRETTOKEN123"))
        val out = RemoteLogPolicy.scrub("http://[bad/x?token=SECRETTOKEN123", "SECRETTOKEN123")
        assertFalse(out.contains("SECRETTOKEN123"))
    }

    @Test
    fun `redaction the token never appears in a built body wherever it is passed`() {
        val token = "SECRETTOKEN123"
        val url = "https://odoo.example.com/signage/tv/tv-01?token=$token"
        val fields = linkedMapOf<String, Any?>(
            "url" to url,
            "msg" to "java.net.ConnectException: failed to connect to $url (port 443)",
            "plain" to "the token is $token ok",
            "param" to "token=$token&other=1",
            "token=$token" to "keyhit",
            "key$token" to "v",
            "multi" to "see $url and $url again",
            "ex" to RuntimeException("boom for $url token=$token").toString(),
            "upper" to "TOKEN=$token",
        )
        val s = bodyText("watchdog", fields, token)
        assertFalse(s, s.contains(token))
        assertFalse(s, s.contains("token=SECRET"))
        assertFalse(s, s.contains("?token"))
        assertTrue(s, s.contains("https://odoo.example.com/signage/tv/tv-01"))
    }

    @Test
    fun `token in the kind field or the event is also scrubbed`() {
        val token = "SECRETTOKEN123"
        val s = bodyText("boot", mapOf("kind" to "app_$token"), token)
        assertFalse(s.contains(token))
    }

    @Test
    fun `a url with query inside the value is cut before truncation so the token cannot be half cut`() {
        val token = "SECRETTOKEN123"
        val filler = "x".repeat(190)
        val s = bodyText("boot", mapOf("m" to "$filler https://h/p?token=$token"), token)
        assertFalse(s.contains("SECRET"))
        assertFalse(s.contains("token=S"))
    }

    // ---- endpoint

    @Test
    fun `endpoint for a paired url is player url slash log with the raw token`() {
        val ep = RemoteLogPolicy.endpoint(base, paired)!!
        assertEquals("https://odoo.example.com/signage/tv/tv-01/log?token=SECRETTOKEN123", ep.url)
    }

    @Test
    fun `endpoint keeps the explicit port`() {
        assertNull(RemoteLogPolicy.endpoint("http://10.0.0.5:8069", "http://10.0.0.5:8069/signage/tv/c1/?token=abc")) // trailing slash = 2nd segment, rejected by PairingPolicy
        val ep = RemoteLogPolicy.endpoint("http://10.0.0.5:8069", "http://10.0.0.5:8069/signage/tv/c1?token=abc")!!
        assertEquals("http://10.0.0.5:8069/signage/tv/c1/log?token=abc", ep.url)
    }

    @Test
    fun `endpoint does not forward other query params or fragment`() {
        val ep = RemoteLogPolicy.endpoint(base, "https://odoo.example.com/signage/tv/c?a=1&token=T0K3N&b=2#frag")!!
        assertEquals("https://odoo.example.com/signage/tv/c/log?token=T0K3N", ep.url)
    }

    @Test
    fun `endpoint upgrades http paired url to https when the proxy upgraded it`() {
        val ep = RemoteLogPolicy.endpoint("http://odoo.example.com", "https://odoo.example.com/signage/tv/c1?token=tok")!!
        assertTrue(ep.url.startsWith("https://odoo.example.com/signage/tv/c1/log?token=tok"))
    }

    @Test
    fun `percent encoded token stays raw in the url but is decoded for scrubbing`() {
        val ep = RemoteLogPolicy.endpoint(base, "https://odoo.example.com/signage/tv/c1?token=a%2Fb%3Dc%20d")!!
        assertEquals("https://odoo.example.com/signage/tv/c1/log?token=a%2Fb%3Dc%20d", ep.url)
        // decoded token is what scrub removes
        assertEquals("x <redacted> y", RemoteLogPolicy.scrub("x a/b=c d y", ep.token))
    }

    @Test
    fun `endpoint is null for urls pairing policy rejects`() {
        assertNull(RemoteLogPolicy.endpoint(base, null))
        assertNull(RemoteLogPolicy.endpoint(base, ""))
        assertNull(RemoteLogPolicy.endpoint(base, "https://odoo.example.com/signage/tv/c1")) // no token
        assertNull(RemoteLogPolicy.endpoint(base, "https://odoo.example.com/signage/tv/c1?token=")) // blank token
        assertNull(RemoteLogPolicy.endpoint(base, "https://evil.example.com/signage/tv/c1?token=t")) // foreign host
        assertNull(RemoteLogPolicy.endpoint(base, "https://odoo.example.com/other/c1?token=t")) // wrong path
        assertNull(RemoteLogPolicy.endpoint(base, "https://odoo.example.com/signage/tv/a/b?token=t")) // two segments
        assertNull(RemoteLogPolicy.endpoint(base, "ftp://odoo.example.com/signage/tv/c1?token=t"))
        assertNull(RemoteLogPolicy.endpoint(base, "https://u:p@odoo.example.com/signage/tv/c1?token=t"))
        assertNull(RemoteLogPolicy.endpoint(base, "not a url"))
    }

    @Test
    fun `endpoint is null for a different base server`() {
        assertNull(RemoteLogPolicy.endpoint("https://other.example.com", paired))
        assertNull(RemoteLogPolicy.endpoint("https://odoo.example.com:8443", paired))
    }

    @Test
    fun `endpoint toString never contains url or token`() {
        val ep = RemoteLogPolicy.endpoint(base, paired)!!
        val s = ep.toString()
        assertNotNull(s)
        assertFalse(s.contains("SECRETTOKEN123"))
        assertFalse(s.contains("odoo.example.com"))
        assertFalse(s.contains("token"))
        assertFalse("$ep".contains("SECRET"))
    }
}
