package io.github.sceneview.demo.ui.home

import io.github.sceneview.demo.DemoCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [filterDemos] — the category chip + search field logic
 * behind the home grid. No resources, no Compose, no Robolectric.
 */
class HomeFilterTest {

    private val viewer = entry("model-viewer", "Model Viewer", "Any glTF, HDR lighting, one tap to AR",
        DemoCategory.VIEW_3D, "View in 3D", setOf("gltf", "hdr", "ar"), order = 1)
    private val lighting = entry("lighting", "Lighting", "Light types with a movable orbiting light",
        DemoCategory.CREATE, "Create & record", setOf("light", "shadow"), order = 2)
    private val placement = entry("ar-placement", "AR Placement", "Tap a plane to place and move a model",
        DemoCategory.PLACE_AR, "Place in AR", setOf("ar", "plane", "anchor"), order = 6)
    private val fog = entry("fog", "Fog", "Linear, exponential and height fog",
        DemoCategory.CREATE, "Create & record", setOf("fog", "atmosphere"), order = 13)

    // Deliberately out of editorial order: the filter must restore it.
    private val all = listOf(fog, placement, viewer, lighting)

    @Test
    fun `no category and blank query returns everything in editorial order`() {
        assertEquals(listOf("model-viewer", "lighting", "ar-placement", "fog"), ids(filterDemos(all, null, "")))
        assertEquals(listOf("model-viewer", "lighting", "ar-placement", "fog"), ids(filterDemos(all, null, "   ")))
    }

    @Test
    fun `category narrows to that category only`() {
        assertEquals(listOf("lighting", "fog"), ids(filterDemos(all, DemoCategory.CREATE, "")))
        assertEquals(listOf("ar-placement"), ids(filterDemos(all, DemoCategory.PLACE_AR, "")))
        assertTrue(filterDemos(all, DemoCategory.DEV_TOOLS, "").isEmpty())
    }

    @Test
    fun `query matches title case-insensitively`() {
        assertEquals(listOf("model-viewer"), ids(filterDemos(all, null, "VIEWER")))
    }

    @Test
    fun `query matches subtitle`() {
        assertEquals(listOf("ar-placement"), ids(filterDemos(all, null, "plane to place")))
    }

    @Test
    fun `query matches tags`() {
        // "hdr" appears only in the viewer's tags, nowhere in its copy.
        assertEquals(listOf("model-viewer"), ids(filterDemos(all, null, "hdr")))
        // "anchor" is a tag on the placement demo only.
        assertEquals(listOf("ar-placement"), ids(filterDemos(all, null, "anchor")))
        // Matching is by substring, so a short query can hit copy as well as
        // tags: "ar" finds the two `ar`-tagged demos AND "Line-ar" fog.
        assertEquals(listOf("model-viewer", "ar-placement", "fog"), ids(filterDemos(all, null, "ar")))
    }

    @Test
    fun `query matches category label`() {
        // Neither demo carries "record" in its title, subtitle or tags — the only
        // thing that can match it is the category label, which is the point.
        assertEquals(listOf("lighting", "fog"), ids(filterDemos(all, null, "record")))
    }

    @Test
    fun `every word of a multi-word query must match`() {
        assertEquals(listOf("model-viewer"), ids(filterDemos(all, null, "gltf ar")))
        assertTrue(filterDemos(all, null, "gltf fog").isEmpty())
    }

    @Test
    fun `category and query combine`() {
        assertEquals(listOf("fog"), ids(filterDemos(all, DemoCategory.CREATE, "height")))
        assertTrue(filterDemos(all, DemoCategory.PLACE_AR, "height").isEmpty())
    }

    @Test
    fun `no match yields an empty list, never a fallback`() {
        assertTrue(filterDemos(all, null, "zzz-nothing").isEmpty())
    }

    private fun ids(entries: List<HomeSearchEntry>) = entries.map { it.id }

    private fun entry(
        id: String, title: String, subtitle: String, category: String, label: String, tags: Set<String>, order: Int,
    ) = HomeSearchEntry(id, title, subtitle, category, label, tags, order)
}
