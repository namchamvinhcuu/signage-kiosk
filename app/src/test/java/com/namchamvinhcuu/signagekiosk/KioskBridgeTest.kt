package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class KioskBridgeTest {

    private class Rig(
        var allowed: Boolean = true,
        var allowedThrows: Boolean = false,
        var memoryThrows: Boolean = false,
        var infoThrows: Boolean = false,
        var restartThrows: Boolean = false,
        var revokedThrows: Boolean = false,
    ) {
        var memoryCalls = 0
        var infoCalls = 0
        var restarts = 0
        var revoked = 0
        val logs = mutableListOf<Pair<String, Throwable>>()
        val bridge = KioskBridge(
            allowed = { if (allowedThrows) throw IllegalStateException("gate") else allowed },
            memory = { memoryCalls++; if (memoryThrows) throw IllegalStateException("mem") else "{\"m\":1}" },
            info = { infoCalls++; if (infoThrows) throw IllegalArgumentException("inf") else "{\"i\":1}" },
            onRestart = { restarts++; if (restartThrows) throw IllegalStateException("rs") },
            onAuthRevoked = { revoked++; if (revokedThrows) throw IllegalStateException("rv") },
            log = { m, t -> logs += m to t },
        )
    }

    @Test
    fun `when not allowed the getters answer null and call nothing`() {
        val r = Rig(allowed = false)
        assertNull(r.bridge.getMemory())
        assertNull(r.bridge.info())
        assertEquals(0, r.memoryCalls)
        assertEquals(0, r.infoCalls)
    }

    @Test
    fun `when not allowed the actions do nothing`() {
        val r = Rig(allowed = false)
        r.bridge.restart()
        r.bridge.onAuthRevoked()
        assertEquals(0, r.restarts)
        assertEquals(0, r.revoked)
        assertEquals(0, r.logs.size)
    }

    @Test
    fun `when allowed everything delegates`() {
        val r = Rig()
        assertEquals("{\"m\":1}", r.bridge.getMemory())
        assertEquals("{\"i\":1}", r.bridge.info())
        r.bridge.restart()
        r.bridge.onAuthRevoked()
        assertEquals(1, r.memoryCalls)
        assertEquals(1, r.infoCalls)
        assertEquals(1, r.restarts)
        assertEquals(1, r.revoked)
        assertEquals(0, r.logs.size)
    }

    @Test
    fun `gate is evaluated on every call`() {
        val r = Rig()
        assertEquals("{\"m\":1}", r.bridge.getMemory())
        r.allowed = false
        assertNull(r.bridge.getMemory())
        r.bridge.restart()
        assertEquals(0, r.restarts)
    }

    @Test
    fun `a throwing getter is caught logged and answers null`() {
        val r = Rig(memoryThrows = true, infoThrows = true)
        assertNull(r.bridge.getMemory())
        assertNull(r.bridge.info())
        assertEquals(listOf("bridge getMemory failed", "bridge info failed"), r.logs.map { it.first })
        assertEquals("mem", r.logs[0].second.message)
    }

    @Test
    fun `a throwing action is caught and logged`() {
        val r = Rig(restartThrows = true, revokedThrows = true)
        r.bridge.restart()
        r.bridge.onAuthRevoked()
        assertEquals(listOf("bridge restart failed", "bridge onAuthRevoked failed"), r.logs.map { it.first })
    }

    @Test
    fun `a throwing gate is caught too`() {
        val r = Rig(allowedThrows = true)
        assertNull(r.bridge.getMemory())
        assertNull(r.bridge.info())
        r.bridge.restart()
        r.bridge.onAuthRevoked()
        assertEquals(4, r.logs.size)
        assertEquals(0, r.restarts)
        assertSame(r.logs[0].second.javaClass, IllegalStateException::class.java)
    }
}
