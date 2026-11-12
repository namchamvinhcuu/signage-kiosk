package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class UpdateContractTest {

    private val sha = "a".repeat(64)

    private fun props(vararg kv: Pair<String, String>): Properties =
        Properties().apply { kv.forEach { setProperty(it.first, it.second) } }

    private fun validProps(vararg override: Pair<String, String>): Properties {
        val base = mutableMapOf(
            "versionCode" to "12",
            "versionName" to "1.2.0",
            "apk" to "kiosk-1.2.0.apk",
            "sha256" to sha,
            "size" to "1048576",
        )
        override.forEach { base[it.first] = it.second }
        return props(*base.map { it.key to it.value }.toTypedArray())
    }

    private fun propsWithout(key: String): Properties = validProps().also { it.remove(key) }

    // ---- UpdateChannel ----

    @Test
    fun `channel fromWire parses trimmed case-insensitive and rejects unknown`() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromWire(" Stable "))
        assertEquals(UpdateChannel.CANARY, UpdateChannel.fromWire("canary"))
        assertEquals(UpdateChannel.OFF, UpdateChannel.fromWire("OFF"))
        assertNull(UpdateChannel.fromWire("beta"))
        assertNull(UpdateChannel.fromWire(null))
    }

    // ---- UpdateManifest.parse ----

    @Test
    fun `valid manifest parses with null minVersionCode`() {
        val m = UpdateManifest.parse(validProps())
        assertEquals(UpdateManifest(12, "1.2.0", "kiosk-1.2.0.apk", sha, 1048576L, null), m)
    }

    @Test
    fun `every missing required field gives null`() {
        for (k in listOf("versionCode", "versionName", "apk", "sha256", "size")) {
            assertNull("missing $k", UpdateManifest.parse(propsWithout(k)))
        }
    }

    @Test
    fun `values are trimmed`() {
        val m = UpdateManifest.parse(
            validProps(
                "versionCode" to " 12 ", "versionName" to "  1.2.0 ", "apk" to " kiosk.apk ",
                "sha256" to "  $sha ", "size" to " 100 ", "minVersionCode" to " 7 ",
            ),
        )
        assertEquals(UpdateManifest(12, "1.2.0", "kiosk.apk", sha, 100L, 7), m)
    }

    @Test
    fun `versionCode must be a positive int`() {
        for (v in listOf("0", "-1", "abc", "", "1.5", "99999999999")) {
            assertNull("versionCode=$v", UpdateManifest.parse(validProps("versionCode" to v)))
        }
        assertNotNull(UpdateManifest.parse(validProps("versionCode" to "1")))
    }

    @Test
    fun `versionName is non-blank and at most 32 chars`() {
        assertNull(UpdateManifest.parse(validProps("versionName" to "   ")))
        assertNotNull(UpdateManifest.parse(validProps("versionName" to "v".repeat(32))))
        assertNull(UpdateManifest.parse(validProps("versionName" to "v".repeat(33))))
    }

    @Test
    fun `apk must be a bare apk file name`() {
        val bad = listOf(
            "dir/app.apk", "/app.apk", "../app.apk", "..\\app.apk", "a\\b.apk", "app.apk?x=1", "app.apk#f",
            "a..b.apk", "..apk", "a..apk", "a...b.apk", ".app.apk", ".apk", "app.txt", "app", "app.apk/", "", "https://x/app.apk", "a b.apk",
        )
        for (a in bad) assertNull("apk=[$a]", UpdateManifest.parse(validProps("apk" to a)))
    }

    @Test
    fun `apk name length boundary is 100`() {
        val ok100 = "a".repeat(96) + ".apk"
        val bad101 = "a".repeat(97) + ".apk"
        assertEquals(100, ok100.length)
        assertNotNull(UpdateManifest.parse(validProps("apk" to ok100)))
        assertNull(UpdateManifest.parse(validProps("apk" to bad101)))
    }

    @Test
    fun `sha256 must be 64 lowercase hex chars`() {
        assertNotNull(UpdateManifest.parse(validProps("sha256" to "0123456789abcdef".repeat(4))))
        for (s in listOf("A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64), "")) {
            assertNull("sha256=$s", UpdateManifest.parse(validProps("sha256" to s)))
        }
    }

    @Test
    fun `size boundaries 1 to 20 MB`() {
        assertNotNull(UpdateManifest.parse(validProps("size" to "1")))
        assertNotNull(UpdateManifest.parse(validProps("size" to UpdateManifest.MAX_APK_BYTES.toString())))
        assertNull(UpdateManifest.parse(validProps("size" to (UpdateManifest.MAX_APK_BYTES + 1).toString())))
        assertNull(UpdateManifest.parse(validProps("size" to "0")))
        assertNull(UpdateManifest.parse(validProps("size" to "-5")))
        assertNull(UpdateManifest.parse(validProps("size" to "big")))
        assertEquals(20L * 1024 * 1024, UpdateManifest.MAX_APK_BYTES)
    }

    @Test
    fun `minVersionCode is optional but a present value must be a positive int`() {
        assertNull(UpdateManifest.parse(validProps())!!.minVersionCode)
        assertNull(UpdateManifest.parse(validProps("minVersionCode" to ""))!!.minVersionCode)
        assertNull(UpdateManifest.parse(validProps("minVersionCode" to "   "))!!.minVersionCode)
        assertEquals(5, UpdateManifest.parse(validProps("minVersionCode" to "5"))!!.minVersionCode)
        for (v in listOf("0", "-1", "x", "1.5", "99999999999")) {
            assertNull("minVersionCode=$v", UpdateManifest.parse(validProps("minVersionCode" to v)))
        }
    }

    // ---- UpdateOffer.of ----

    @Test
    fun `offer of joins apkBase and apk and copies the fields`() {
        val m = UpdateManifest(12, "1.2.0", "kiosk.apk", sha, 99L, 7)
        assertEquals(
            UpdateOffer(12, "1.2.0", "https://h/d/kiosk.apk", sha, 99L, 7),
            UpdateOffer.of(m, "https://h/d/"),
        )
    }

    // ---- UpdatePolicy.decide ----

    private val now = 1_000_000_000_000L
    private val okGates = UpdateGates(
        canDrawOverlays = true, canRequestInstalls = true, settingsOpen = false, restarting = false,
    )

    private fun offer(code: Int = 10, min: Int? = null) =
        UpdateOffer(code, "n", "https://h/a.apk", sha, 100L, min)

    private fun skip(r: String) = UpdateDecision.Skip(r)

    @Test
    fun `all clear installs`() {
        assertEquals(UpdateDecision.Install, UpdatePolicy.decide(9, offer(10), now, UpdateState(), okGates))
    }

    @Test
    fun `equal or lower offered code is up to date`() {
        assertEquals(skip("up_to_date"), UpdatePolicy.decide(10, offer(10), now, UpdateState(), okGates))
        assertEquals(skip("up_to_date"), UpdatePolicy.decide(11, offer(10), now, UpdateState(), okGates))
    }

    @Test
    fun `too old when installed is below minVersionCode and equal passes`() {
        assertEquals(skip("too_old"), UpdatePolicy.decide(4, offer(10, min = 5), now, UpdateState(), okGates))
        assertEquals(UpdateDecision.Install, UpdatePolicy.decide(5, offer(10, min = 5), now, UpdateState(), okGates))
        assertEquals(UpdateDecision.Install, UpdatePolicy.decide(6, offer(10, min = 5), now, UpdateState(), okGates))
    }

    @Test
    fun `decision order is exactly the documented one`() {
        // Start with every blocker active, then lift them one by one in the documented order.
        var installed = 15L
        var min: Int? = 20
        var g = UpdateGates(
            canDrawOverlays = false, canRequestInstalls = false, settingsOpen = true, restarting = true,
            channelOff = true, installing = true,
        )
        val st = UpdateState(backoffUntilMs = now + 3_600_000L, verifyFailedCode = 15, verifyFailedAtMs = now)
        fun d() = UpdatePolicy.decide(installed, offer(15, min), now, st, g)

        assertEquals(skip("up_to_date"), d())
        installed = 5
        assertEquals(skip("too_old"), d())
        min = null
        assertEquals(skip("channel_off"), d())
        g = g.copy(channelOff = false)
        assertEquals(skip("no_overlay"), d())
        g = g.copy(canDrawOverlays = true)
        assertEquals(skip("no_install_permission"), d())
        g = g.copy(canRequestInstalls = true)
        assertEquals(skip("settings_open"), d())
        g = g.copy(settingsOpen = false)
        assertEquals(skip("restarting"), d())
        g = g.copy(restarting = false)
        assertEquals(skip("installing"), d())
        g = g.copy(installing = false)
        assertEquals(skip("backoff"), d())
        val noBackoff = st.copy(backoffUntilMs = 0L)
        assertEquals(skip("same_code_guard"), UpdatePolicy.decide(installed, offer(15), now, noBackoff, g))
        assertEquals(
            UpdateDecision.Install,
            UpdatePolicy.decide(installed, offer(15), now, noBackoff.copy(verifyFailedCode = 0), g),
        )
    }

    @Test
    fun `backoff deadline boundaries and the wrong-clock rule`() {
        fun d(until: Long) = UpdatePolicy.decide(1, offer(10), now, UpdateState(backoffUntilMs = until), okGates)
        assertEquals(skip("backoff"), d(now + 1))
        assertEquals(skip("backoff"), d(now + 24 * 3_600_000L))
        assertEquals(UpdateDecision.Install, d(now + 24 * 3_600_000L + 1))
        assertEquals(UpdateDecision.Install, d(now))
        assertEquals(UpdateDecision.Install, d(now - 1))
        assertEquals(UpdateDecision.Install, d(0))
    }

    @Test
    fun `same-code guard is for that code only and lasts 24 hours`() {
        fun d(code: Int, failCode: Int, failAt: Long) = UpdatePolicy.decide(
            1, offer(code), now, UpdateState(verifyFailedCode = failCode, verifyFailedAtMs = failAt), okGates,
        )
        assertEquals(skip("same_code_guard"), d(10, 10, now - 3_600_000L))
        assertEquals(skip("same_code_guard"), d(10, 10, now - 24 * 3_600_000L + 1))
        assertEquals(UpdateDecision.Install, d(10, 10, now - 24 * 3_600_000L))
        assertEquals(UpdateDecision.Install, d(11, 10, now - 3_600_000L))
        // Failure timestamp from a wrong clock (far in the future) is ignored.
        assertEquals(UpdateDecision.Install, d(10, 10, now + 5 * 24 * 3_600_000L))
    }

    @Test
    fun `deadlineActive edges`() {
        assertTrue(UpdatePolicy.deadlineActive(now + 1, now))
        assertFalse(UpdatePolicy.deadlineActive(now, now))
        assertTrue(UpdatePolicy.deadlineActive(now + UpdatePolicy.MAX_DEADLINE_AHEAD_MS, now))
        assertFalse(UpdatePolicy.deadlineActive(now + UpdatePolicy.MAX_DEADLINE_AHEAD_MS + 1, now))
    }

    @Test
    fun `afterInstallFailure sets six hours and never shortens a longer active backoff`() {
        val six = 6 * 3_600_000L
        assertEquals(now + six, UpdatePolicy.afterInstallFailure(UpdateState(), now).backoffUntilMs)
        assertEquals(now + six, UpdatePolicy.afterInstallFailure(UpdateState(backoffUntilMs = now + 3_600_000L), now).backoffUntilMs)
        assertEquals(now + six, UpdatePolicy.afterInstallFailure(UpdateState(backoffUntilMs = now - 5), now).backoffUntilMs)
        // longer but active -> kept
        val ten = now + 10 * 3_600_000L
        assertEquals(ten, UpdatePolicy.afterInstallFailure(UpdateState(backoffUntilMs = ten), now).backoffUntilMs)
        // 30 days ahead = wrong clock, not active -> replaced
        val far = now + 30L * 24 * 3_600_000L
        assertEquals(now + six, UpdatePolicy.afterInstallFailure(UpdateState(backoffUntilMs = far), now).backoffUntilMs)
        // other fields survive
        val st = UpdateState(verifyFailedCode = 3, verifyFailedAtMs = 4L)
        val r = UpdatePolicy.afterInstallFailure(st, now)
        assertEquals(3, r.verifyFailedCode)
        assertEquals(4L, r.verifyFailedAtMs)
    }

    @Test
    fun `afterPendingUserAction is 24 hours`() {
        assertEquals(now + 24 * 3_600_000L, UpdatePolicy.afterPendingUserAction(UpdateState(), now).backoffUntilMs)
    }

    @Test
    fun `afterVerifyFailure records code and time only`() {
        val r = UpdatePolicy.afterVerifyFailure(UpdateState(backoffUntilMs = 77L), 12, now)
        assertEquals(UpdateState(backoffUntilMs = 77L, verifyFailedCode = 12, verifyFailedAtMs = now), r)
    }

    // ---- verifyDownload ----

    private val o = UpdateOffer(12, "n", "https://h/a.apk", sha, 100L)

    private fun verify(
        size: Long = 100L, hash: String = sha, pkg: String? = "com.x", code: Long? = 12L,
        own: String = "com.x", signer: Boolean? = null,
    ) = UpdatePolicy.verifyDownload(o, size, hash, pkg, code, own, signer)

    @Test
    fun `verifyDownload ok and each reason`() {
        assertNull(verify())
        assertNull(verify(signer = true))
        assertNull("null signer = pre-check skipped", verify(signer = null))
        assertEquals("size", verify(size = 99))
        assertEquals("size", verify(size = 101))
        assertEquals("sha256", verify(hash = "b".repeat(64)))
        assertEquals("unparsable", verify(pkg = null))
        assertEquals("unparsable", verify(code = null))
        assertEquals("package", verify(pkg = "com.evil"))
        assertEquals("version_code", verify(code = 11L))
        assertEquals("signer", verify(signer = false))
    }

    @Test
    fun `verifyDownload sha compare is case-insensitive`() {
        assertNull(verify(hash = sha.uppercase()))
    }

    @Test
    fun `verifyDownload precedence order`() {
        val bad = "b".repeat(64)
        assertEquals("size", verify(size = 1, hash = bad, pkg = null, code = null, signer = false))
        assertEquals("sha256", verify(hash = bad, pkg = null, code = null, signer = false))
        assertEquals("unparsable", verify(pkg = null, code = 1L, signer = false))
        assertEquals("package", verify(pkg = "com.evil", code = 1L, signer = false))
        assertEquals("version_code", verify(code = 1L, signer = false))
    }

    // ---- sameSigner ----

    @Test
    fun `sameSigner equal sets in any case and order`() {
        assertTrue(UpdatePolicy.sameSigner(setOf("AB12"), setOf("ab12")))
        assertTrue(UpdatePolicy.sameSigner(setOf("aa", "BB", "cc"), setOf("CC", "bb", "AA")))
    }

    @Test
    fun `sameSigner empty subset superset and different are false`() {
        assertFalse(UpdatePolicy.sameSigner(emptySet(), emptySet()))
        assertFalse(UpdatePolicy.sameSigner(setOf("aa"), emptySet()))
        assertFalse(UpdatePolicy.sameSigner(emptySet(), setOf("aa")))
        assertFalse(UpdatePolicy.sameSigner(setOf("aa"), setOf("aa", "bb")))
        assertFalse(UpdatePolicy.sameSigner(setOf("aa", "bb"), setOf("aa")))
        assertFalse(UpdatePolicy.sameSigner(setOf("aa"), setOf("bb")))
    }
}
