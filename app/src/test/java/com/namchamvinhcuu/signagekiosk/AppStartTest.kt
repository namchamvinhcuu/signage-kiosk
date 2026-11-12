package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStartTest {

    @Test
    fun `no extra and no exit reason is a plain launch`() {
        assertEquals(AppStartInfo("launch", null), AppStart.derive(null, null))
    }

    @Test
    fun `an exit reason without an extra is a restart`() {
        assertEquals(AppStartInfo("restart", "periodic"), AppStart.derive(null, "periodic"))
    }

    @Test
    fun `the launching extra wins and keeps the exit reason`() {
        assertEquals(AppStartInfo("boot", null), AppStart.derive("boot", null))
        assertEquals(AppStartInfo("boot", "periodic"), AppStart.derive("boot", "periodic"))
    }

    @Test
    fun `pending equal to running version is an update start`() {
        assertEquals(AppStartInfo("update", "update_5", false), AppStart.derive(null, null, 5, 5))
    }

    @Test
    fun `an update wins over a leftover exit reason`() {
        assertEquals(AppStartInfo("update", "update_5", false), AppStart.derive(null, "periodic", 5, 5))
    }

    @Test
    fun `pending different from running flags pendingNotInstalled and keeps the exit reason`() {
        assertEquals(AppStartInfo("restart", "periodic", true), AppStart.derive(null, "periodic", 7, 5))
        assertEquals(AppStartInfo("launch", null, true), AppStart.derive(null, null, 7, 5))
    }

    @Test
    fun `extra start wins over the derived start but prevReason is still update_N`() {
        assertEquals(AppStartInfo("boot", "update_5", false), AppStart.derive("boot", "periodic", 5, 5))
        assertEquals(AppStartInfo("boot", "periodic", true), AppStart.derive("boot", "periodic", 7, 5))
    }

    @Test
    fun `pending zero is never an update`() {
        assertEquals(AppStartInfo("launch", null, false), AppStart.derive(null, null, 0, 5))
        assertEquals(AppStartInfo("launch", null, false), AppStart.derive(null, null, 0, 0))
        assertEquals(AppStartInfo("restart", "crash", false), AppStart.derive(null, "crash", 0, 0))
    }
}
