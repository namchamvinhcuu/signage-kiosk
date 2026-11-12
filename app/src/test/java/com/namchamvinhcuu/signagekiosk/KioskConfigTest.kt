package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskConfigTest {

    @Test
    fun `DEFAULT carries the blank url and the documented restart and memory defaults`() {
        assertEquals(KioskConfig("about:blank", 240, 0), KioskConfig.DEFAULT)
    }

    @Test
    fun `default numeric values survive normalization unchanged`() {
        assertEquals(KioskConfig.DEFAULT_RESTART_MIN, ConfigValidator.normalizeRestartMin(KioskConfig.DEFAULT_RESTART_MIN))
        assertEquals(KioskConfig.DEFAULT_LOW_MEM_MB, ConfigValidator.normalizeLowMemMb(KioskConfig.DEFAULT_LOW_MEM_MB))
    }

    @Test
    fun `AppStart empty extra still wins over a previous exit reason`() {
        assertEquals(AppStartInfo("", "crash"), AppStart.derive("", "crash"))
    }
}
