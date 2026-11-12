package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader
import java.util.Properties

/** Fixtures are the REAL output of scripts/make-version-file.sh (plan kiosk-selfupdate step 8.3), copied verbatim. */
class GeneratedVersionFileTest {

    private val v1 = "versionCode=1\n" +
        "versionName=0.1.0\n" +
        "apk=kiosk-v1.apk\n" +
        "sha256=d4c052fbb6ced57694bfda90d5741e18fc11f32f4fa3f57f7e07a92e149b95a5\n" +
        "size=973511\n"

    private val v2 = "versionCode=2\n" +
        "versionName=0.2.0\n" +
        "apk=kiosk-v2.apk\n" +
        "sha256=9b98d769de7822aca039c0fe0ab921e0bcb617854f47d981ed81d9e9630332c1\n" +
        "size=917883\n" +
        "minVersionCode=1\n"

    private fun parse(text: String): UpdateManifest? =
        UpdateManifest.parse(Properties().apply { load(StringReader(text)) })

    @Test
    fun `v1 file parses with exact fields and no minVersionCode`() {
        assertEquals(
            UpdateManifest(
                1, "0.1.0", "kiosk-v1.apk",
                "d4c052fbb6ced57694bfda90d5741e18fc11f32f4fa3f57f7e07a92e149b95a5", 973511L, null,
            ),
            parse(v1),
        )
    }

    @Test
    fun `v2 file parses with exact fields and minVersionCode 1`() {
        assertEquals(
            UpdateManifest(
                2, "0.2.0", "kiosk-v2.apk",
                "9b98d769de7822aca039c0fe0ab921e0bcb617854f47d981ed81d9e9630332c1", 917883L, 1,
            ),
            parse(v2),
        )
    }

    @Test
    fun `offer built from the v2 file points at the apk in the tag dir`() {
        val offer = UpdateOffer.of(parse(v2)!!, "https://github.com/o/r/releases/download/v2/")
        assertEquals("https://github.com/o/r/releases/download/v2/kiosk-v2.apk", offer.apkUrl)
        assertEquals(2, offer.versionCode)
        assertEquals(1, offer.minVersionCode)
    }

    @Test
    fun `mutated variants of the real file parse to null`() {
        val sha = "9b98d769de7822aca039c0fe0ab921e0bcb617854f47d981ed81d9e9630332c1"
        assertNull("uppercase sha256", parse(v2.replace(sha, sha.uppercase())))
        assertNull("trailing size=0 overrides size", parse(v2 + "size=0\n"))
        assertNull("path in apk", parse(v2.replace("apk=kiosk-v2.apk", "apk=../x.apk")))
        assertNull("minVersionCode=0", parse(v2.replace("minVersionCode=1", "minVersionCode=0")))
    }

    @Test
    fun `an unknown extra key is ignored`() {
        val m = parse(v2 + "releaseNotes=fixes\n")
        assertNotNull(m)
        assertEquals(parse(v2), m)
    }
}
