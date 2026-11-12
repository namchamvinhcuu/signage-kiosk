package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// DebugOriginSource exists only in debug builds (src/debug), so this test lives in src/testDebug and only runs there.
class DebugOriginSourceTest {

    private val origin = "http://10.0.2.2:8099"
    private val src = DebugOriginSource(origin)
    private val reqUrl = "$origin/stable/version.properties"

    private fun reject(reason: String) = HopResult.Reject(reason)

    @Test
    fun `manifestUrl for stable and canary, null for off`() {
        assertEquals("$origin/stable/version.properties", src.manifestUrl(UpdateChannel.STABLE))
        assertEquals("$origin/canary/version.properties", src.manifestUrl(UpdateChannel.CANARY))
        assertNull(src.manifestUrl(UpdateChannel.OFF))
    }

    @Test
    fun `origin with trailing slash and spaces is normalised`() {
        val s = DebugOriginSource("  $origin/  ")
        assertEquals("$origin/stable/version.properties", s.manifestUrl(UpdateChannel.STABLE))
        assertEquals(HopResult.Follow("$origin/x"), s.validateHop(reqUrl, "$origin/x", 1))
    }

    @Test
    fun `relative location is resolved against the requested url and followed`() {
        assertEquals(HopResult.Follow("$origin/stable/app.apk"), src.validateHop(reqUrl, "app.apk", 1))
        assertEquals(HopResult.Follow("$origin/other/app.apk"), src.validateHop(reqUrl, "/other/app.apk", 1))
    }

    @Test
    fun `absolute location inside origin is followed`() {
        assertEquals(HopResult.Follow("$origin/canary/a.apk"), src.validateHop(reqUrl, "$origin/canary/a.apk", 2))
    }

    @Test
    fun `absolute other origin is rejected`() {
        assertEquals(reject("origin_not_allowed"), src.validateHop(reqUrl, "http://evil.example/x", 1))
        assertEquals(reject("origin_not_allowed"), src.validateHop(reqUrl, "https://10.0.2.2:8099/x", 1))
    }

    @Test
    fun `url that only starts with the origin text is rejected - boundary`() {
        assertEquals(reject("origin_not_allowed"), src.validateHop(reqUrl, "http://10.0.2.2:8099.evil.example/x", 1))
        assertEquals(reject("origin_not_allowed"), src.validateHop(reqUrl, "http://10.0.2.2:80990/x", 1))
    }

    @Test
    fun `hop limit, blank, null and malformed location`() {
        assertEquals(HopResult.Follow("$origin/x"), src.validateHop(reqUrl, "/x", 3))
        assertEquals(reject("too_many_hops"), src.validateHop(reqUrl, "/x", 4))
        assertEquals(reject("no_location"), src.validateHop(reqUrl, null, 1))
        assertEquals(reject("no_location"), src.validateHop(reqUrl, "   ", 1))
        assertEquals(reject("bad_location"), src.validateHop(reqUrl, "http://[bad", 1))
    }

    @Test
    fun `apkBase is the directory of the requested url under the origin, else null`() {
        assertEquals("$origin/stable/", src.apkBase(reqUrl, null))
        assertEquals("$origin/a/b/", src.apkBase("$origin/a/b/c.properties", "x"))
        assertNull(src.apkBase("http://10.0.2.2:8099.evil.example/stable/version.properties", null))
        assertNull(src.apkBase("http://10.0.2.2:80990/stable/v", null))
        assertNull(src.apkBase("http://other.example/stable/v", null))
    }
}
