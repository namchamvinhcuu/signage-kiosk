package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GithubUpdateSourceTest {

    private val repo = "owner/repo"
    private val src = GithubUpdateSource(repo)
    private val stableUrl = "https://github.com/owner/repo/releases/latest/download/version.properties"
    private val tagBase = "https://github.com/owner/repo/releases/download/"

    private fun reject(r: HopResult, reason: String) = assertEquals(HopResult.Reject(reason), r)

    // ---- constructor ----

    @Test
    fun `bad repo names are refused`() {
        for (bad in listOf("a", "a/b/c", "a b/c", "a/b c", "", "a/", "/b", "a//b", "a/b?x", "a/b#x")) {
            try {
                GithubUpdateSource(bad)
                fail("accepted [$bad]")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `good repo is accepted and trimmed`() {
        assertEquals(stableUrl, GithubUpdateSource("  owner/repo ").manifestUrl(UpdateChannel.STABLE))
        GithubUpdateSource("my-org.x/my_repo.y")
    }

    // ---- manifestUrl ----

    @Test
    fun `manifestUrl per channel`() {
        assertEquals(stableUrl, src.manifestUrl(UpdateChannel.STABLE))
        assertEquals(
            "https://github.com/owner/repo/releases/download/canary/version.properties",
            src.manifestUrl(UpdateChannel.CANARY),
        )
        assertNull(src.manifestUrl(UpdateChannel.OFF))
    }

    // ---- validateHop ----

    @Test
    fun `allowed hosts are followed case-insensitively`() {
        for (h in listOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "GitHub.COM")) {
            val url = "https://$h/path/x?y=1"
            assertEquals("host $h", HopResult.Follow(url), src.validateHop(stableUrl, url, 1))
        }
    }

    @Test
    fun `explicit port 443 is fine, other ports are not`() {
        assertEquals(
            HopResult.Follow("https://github.com:443/x"),
            src.validateHop(stableUrl, "https://github.com:443/x", 1),
        )
        reject(src.validateHop(stableUrl, "https://github.com:8443/x", 1), "port_not_allowed")
        reject(src.validateHop(stableUrl, "https://github.com:80/x", 1), "port_not_allowed")
    }

    @Test
    fun `non-https foreign host userinfo are rejected`() {
        reject(src.validateHop(stableUrl, "http://github.com/x", 1), "not_https")
        reject(src.validateHop(stableUrl, "ftp://github.com/x", 1), "not_https")
        reject(src.validateHop(stableUrl, "https://evil.example/x", 1), "host_not_allowed")
        reject(src.validateHop(stableUrl, "https://github.com.evil.example/x", 1), "host_not_allowed")
        reject(src.validateHop(stableUrl, "https://user@github.com/x", 1), "userinfo")
        reject(src.validateHop(stableUrl, "https://github.com@evil.example/x", 1), "userinfo")
    }

    @Test
    fun `hop number limit is 3`() {
        assertTrue(src.validateHop(stableUrl, "https://github.com/x", 3) is HopResult.Follow)
        reject(src.validateHop(stableUrl, "https://github.com/x", 4), "too_many_hops")
        assertEquals(3, GithubUpdateSource.MAX_HOPS)
    }

    @Test
    fun `null blank and malformed Location are rejected`() {
        reject(src.validateHop(stableUrl, null, 1), "no_location")
        reject(src.validateHop(stableUrl, "", 1), "no_location")
        reject(src.validateHop(stableUrl, "   ", 1), "no_location")
        reject(src.validateHop(stableUrl, "https://github.com/a b", 1), "bad_location")
        reject(src.validateHop(stableUrl, "https://github.com/%zz", 1), "bad_location")
        reject(src.validateHop("https://github.com/a b", "/x", 1), "bad_request_url")
    }

    @Test
    fun `relative Location is resolved against the requested URL before judging`() {
        assertEquals(
            HopResult.Follow("https://github.com/owner/repo/releases/download/v1/version.properties"),
            src.validateHop(stableUrl, "/owner/repo/releases/download/v1/version.properties", 1),
        )
        assertEquals(
            HopResult.Follow("https://github.com/owner/repo/releases/latest/download/other"),
            src.validateHop(stableUrl, "other", 1),
        )
        // scheme-relative -> evil host
        reject(src.validateHop(stableUrl, "//evil.example/x", 1), "host_not_allowed")
        // a relative path against a non-allowed request URL is judged on the resolved host
        reject(src.validateHop("https://evil.example/a", "/b", 1), "host_not_allowed")
        // a relative path against an http request URL is judged on the resolved scheme
        reject(src.validateHop("http://github.com/a", "/b", 1), "not_https")
    }

    // ---- apkBase ----

    @Test
    fun `stable with hop to the tag dir gives that tag dir`() {
        val hop = "${tagBase}v1.2.0/version.properties"
        assertEquals("${tagBase}v1.2.0/", src.apkBase(stableUrl, hop))
    }

    @Test
    fun `stable refuses everything but the exact tag hop`() {
        val bad = listOf(
            null,
            "https://release-assets.githubusercontent.com/abc/def",
            "https://objects.githubusercontent.com/x/version.properties",
            "https://github.com/other/repo/releases/download/v1/version.properties",
            "${tagBase}v1/other.properties",
            "${tagBase}v1/extra/version.properties",
            "${tagBase}v1/version.properties?x=1",
            "${tagBase}v1/version.properties#f",
            "${tagBase}../version.properties",
            "${tagBase}./version.properties",
            "${tagBase}version.properties",
            "${tagBase}/version.properties",
            "https://github.com/owner/repo/releases/latest/download/version.properties",
        )
        for (h in bad) assertNull("hop=$h", src.apkBase(stableUrl, h))
    }

    @Test
    fun `canary manifest request gives the canary dir without any hop`() {
        val canary = src.manifestUrl(UpdateChannel.CANARY)!!
        assertEquals("${tagBase}canary/", src.apkBase(canary, null))
        assertEquals("${tagBase}canary/", src.apkBase(canary, "https://release-assets.githubusercontent.com/z"))
    }

    @Test
    fun `an apk download request uses its own tag dir`() {
        assertEquals("${tagBase}v2/", src.apkBase("${tagBase}v2/kiosk.apk", null))
    }

    @Test
    fun `urls of another repo or odd shapes give null`() {
        assertNull(src.apkBase("https://github.com/other/repo/releases/download/v2/kiosk.apk", null))
        assertNull(src.apkBase("${tagBase}a/b/c", null))
        assertNull(src.apkBase("${tagBase}v2", null))
        assertNull(src.apkBase("${tagBase}v2/", null))
        assertNull(src.apkBase("${tagBase}../kiosk.apk", null))
        assertNull(src.apkBase("${tagBase}v2/kiosk.apk?x=1", null))
        assertNull(src.apkBase("https://evil.example/owner/repo/releases/download/v2/kiosk.apk", null))
    }
}
