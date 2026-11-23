package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the component wiring of the SOURCE manifest (app/src/main/AndroidManifest.xml) and the accessibility
 * config it points at (plain JDK DOM parser, no build output): which components are exported, that the
 * start-after-boot AccessibilityService is bindable only by the system and listens to nothing, and that
 * MainActivity keeps its launcher filter. Only structure is checked: the system bind at boot and the
 * background-start exemption on a real TV are field-verified, not covered here.
 */
class ManifestComponentsTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
        val ALLOWED_CONFIG_ATTRS = setOf("accessibilityFeedbackType", "canRetrieveWindowContent", "description")
        const val MAIN = "android.intent.action.MAIN"
        const val LAUNCHER = "android.intent.category.LAUNCHER"
        const val LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"
        const val HOME = "android.intent.category.HOME"
        const val BIND_PERMISSION = "android.permission.BIND_ACCESSIBILITY_SERVICE"
        const val ACCESSIBILITY_ACTION = "android.accessibilityservice.AccessibilityService"
        const val ACCESSIBILITY_META = "android.accessibilityservice"
        const val MAIN_ACTIVITY = ".MainActivity"
        const val START_SERVICE = ".KioskStartService"
        val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")
    }

    private data class Filter(val actions: Set<String>, val categories: Set<String>)

    private class Component(
        val tag: String,
        val name: String,
        val exported: String?,
        val permission: String?,
        val process: String?,
        val filters: List<Filter>,
        val metaData: Map<String, String?>,
    )

    private fun existing(vararg paths: String): File =
        paths.map { File(it) }.firstOrNull { it.isFile }
            ?: error("none of ${paths.toList()} found from ${File(".").absolutePath}")

    /** Gradle runs unit tests with the module dir as cwd; the second path covers a run from the repo root. */
    private fun manifestFile(): File =
        existing("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")

    private fun resXml(name: String): File =
        existing("src/main/res/xml/$name.xml", "app/src/main/res/xml/$name.xml")

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement

    private fun attr(el: Element, name: String): String? =
        if (el.hasAttributeNS(ANDROID_NS, name)) el.getAttributeNS(ANDROID_NS, name) else null

    private fun names(parent: Element, tag: String): Set<String> {
        val list = parent.getElementsByTagName(tag)
        return (0 until list.length).map { attr(list.item(it) as Element, "name")!! }.toSet()
    }

    private fun components(): List<Component> {
        val application = parse(manifestFile()).getElementsByTagName("application").item(0) as Element
        val result = mutableListOf<Component>()
        val children = application.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.tagName !in COMPONENT_TAGS) continue
            val filterEls = el.getElementsByTagName("intent-filter")
            val filters = (0 until filterEls.length).map {
                val f = filterEls.item(it) as Element
                Filter(names(f, "action"), names(f, "category"))
            }
            val metaEls = el.getElementsByTagName("meta-data")
            val meta = (0 until metaEls.length).associate {
                val m = metaEls.item(it) as Element
                attr(m, "name")!! to attr(m, "resource")
            }
            result += Component(el.tagName, attr(el, "name")!!, attr(el, "exported"), attr(el, "permission"), attr(el, "process"), filters, meta)
        }
        return result
    }

    private fun component(name: String): Component =
        components().singleOrNull { it.name == name }.also { assertNotNull("$name declared exactly once", it) }!!

    @Test
    fun `MainActivity keeps its LAUNCHER and LEANBACK_LAUNCHER filter`() {
        val hasLauncher = component(MAIN_ACTIVITY).filters.any {
            MAIN in it.actions && LAUNCHER in it.categories && LEANBACK in it.categories
        }
        assertTrue("MainActivity needs a MAIN + LAUNCHER + LEANBACK_LAUNCHER intent-filter", hasLauncher)
    }

    @Test
    fun `no component declares the HOME category`() {
        val owners = components().filter { c -> c.filters.any { HOME in it.categories } }.map { it.name }
        assertTrue("HOME category declared on $owners (HOME launcher direction was dropped)", owners.isEmpty())
    }

    @Test
    fun `exported components are exactly MainActivity and KioskStartService`() {
        val exported = components().filter { it.exported == "true" }.map { it.name }.toSet()
        assertEquals(setOf(MAIN_ACTIVITY, START_SERVICE), exported)
    }

    @Test
    fun `every other component declares exported false explicitly`() {
        val bad = components().filter { it.name != MAIN_ACTIVITY && it.name != START_SERVICE && it.exported != "false" }
            .map { it.name }
        assertTrue("components without exported=\"false\": $bad", bad.isEmpty())
    }

    @Test
    fun `KioskStartService is a service that only the system can bind`() {
        val service = component(START_SERVICE)
        assertEquals("service", service.tag)
        assertEquals(BIND_PERMISSION, service.permission)
    }

    @Test
    fun `KioskStartService has a single intent-filter with only the AccessibilityService action`() {
        val filters = component(START_SERVICE).filters
        assertEquals(1, filters.size)
        assertEquals(setOf(ACCESSIBILITY_ACTION), filters[0].actions)
        assertTrue("no categories expected, got ${filters[0].categories}", filters[0].categories.isEmpty())
    }

    private fun accessibilityConfig(): Element {
        val resource = component(START_SERVICE).metaData[ACCESSIBILITY_META]
        assertNotNull("service needs meta-data $ACCESSIBILITY_META with a resource", resource)
        assertTrue("resource must be an @xml/ reference, got $resource", resource!!.startsWith("@xml/"))
        return parse(resXml(resource.removePrefix("@xml/")))
    }

    @Test
    fun `accessibility config uses only allow-listed attributes`() {
        val config = accessibilityConfig()
        assertEquals("accessibility-service", config.tagName)

        // ALLOW-LIST: a new capability (events, gestures, key filtering, touch exploration, flags...) shows up here as
        // an unknown attribute and must be a deliberate decision, not a silent addition to a service that listens to nothing.
        val attrs = config.attributes
        val found = (0 until attrs.length).map { attrs.item(it) }
            .filter { it.namespaceURI != XMLNS_NS && !it.nodeName.startsWith("xmlns") }
            .map { (if (it.namespaceURI == ANDROID_NS) "android:" else "${it.namespaceURI}:") + it.localName }
            .toSet()
        val allowed = ALLOWED_CONFIG_ATTRS.map { "android:$it" }.toSet()
        assertTrue("accessibility config has attributes outside the allow-list $allowed: ${found - allowed}", allowed.containsAll(found))
    }

    @Test
    fun `accessibility config listens to no events and cannot read window content`() {
        val config = accessibilityConfig()
        assertNull("accessibilityEventTypes must be absent (listen to no events)", attr(config, "accessibilityEventTypes"))
        assertEquals(
            "canRetrieveWindowContent must be the literal \"false\" (not a @bool reference, not missing)",
            "false",
            attr(config, "canRetrieveWindowContent"),
        )
    }

    @Test
    fun `KioskStartService runs in the app process`() {
        // KioskStartService reads the process-local FrontWatch.visible for its kiosk_in_front gate. With an
        // android:process attribute it would run in another process, where that flag is never set, so the gate would
        // be permanently false and every connect would look like "kiosk not in front".
        assertNull("KioskStartService must not declare android:process", component(START_SERVICE).process)
    }
}
