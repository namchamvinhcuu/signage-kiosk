package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTextTest {

    @Test
    fun `quote plain empty and unicode`() {
        assertEquals("\"abc\"", JsonText.quote("abc"))
        assertEquals("\"\"", JsonText.quote(""))
        assertEquals("\"Việt 漢\"", JsonText.quote("Việt 漢"))
    }

    @Test
    fun `quote escapes quote backslash and control chars`() {
        assertEquals("\"a\\\"b\"", JsonText.quote("a\"b"))
        assertEquals("\"a\\\\b\"", JsonText.quote("a\\b"))
        assertEquals("\"\\u000a\\u0009\\u0000\\u001f\"", JsonText.quote("\n\t\u0000\u001f"))
        // a space (0x20) is not a control char
        assertEquals("\" \"", JsonText.quote(" "))
    }

    @Test
    fun `flat object renders each type and skips nulls`() {
        val s = JsonText.flatObject(linkedMapOf("s" to "x", "i" to 1, "l" to 2L, "b" to false, "d" to 1.5, "n" to null))
        assertEquals("""{"s":"x","i":1,"l":2,"b":false,"d":1.5}""", s)
    }

    @Test
    fun `flat object empty and non finite double`() {
        assertEquals("{}", JsonText.flatObject(emptyMap()))
        assertEquals("""{"d":null}""", JsonText.flatObject(mapOf("d" to Double.POSITIVE_INFINITY)))
        assertEquals("""{"d":null}""", JsonText.flatObject(mapOf("d" to Double.NaN)))
    }

    @Test
    fun `flat object escapes keys and string values`() {
        val s = JsonText.flatObject(mapOf("k\"" to "v\\"))
        assertEquals("""{"k\"":"v\\"}""", s)
        assertTrue(s.startsWith("{") && s.endsWith("}"))
    }

    @Test
    fun `other object types are rendered as quoted strings`() {
        assertEquals("""{"x":"[1, 2]"}""", JsonText.flatObject(mapOf("x" to listOf(1, 2))))
    }
}
