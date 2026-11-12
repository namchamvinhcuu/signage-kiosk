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
}
