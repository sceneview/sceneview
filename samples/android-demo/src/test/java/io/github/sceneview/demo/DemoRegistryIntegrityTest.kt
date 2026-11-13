package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import io.github.sceneview.demo.fragments.TwoDInThreeDFragment

/**
 * Pure-JVM integrity tests for the demo registry ([ALL_DEMOS]).
 *
 * [ALL_DEMOS] is collated from the per-demo `*Fragment.kt` files by
 * `samples/android-demo/scripts/collate-demos.sh` into the generated
 * `GeneratedDemos.kt`. A bad fragment (duplicate id, a typo'd category, a
 * non-kebab id that breaks `sceneview://demo/<id>` routing) would otherwise only
 * surface when a user opens the Samples tab on a device. These tests catch it at
 * `testDebugUnitTest` time — no emulator, no screenshot.
 *
 * Closes part of [#880](https://github.com/sceneview/sceneview/issues/880) — the
 * non-AR demo state-machine regression suite. Mirrors the deep-link guard tests
 * in [DeepLinkRouterTest].
 */
class DemoRegistryIntegrityTest {

    @Test
    fun `every registered demo source exists relative to the module`() {
        val moduleDirectory = File(System.getProperty("user.dir"))
        for (demo in ALL_DEMOS) {
            val sourcePath = demo.sourcePath ?: error("${demo.id}: no sourcePath")
            assertEquals(
                "https://github.com/sceneview/sceneview/blob/main/samples/android-demo/$sourcePath",
                demo.sourceUrl,
            )
            val source = File(moduleDirectory, sourcePath)
            assertTrue("${demo.id}: missing source ${source.absolutePath}", source.isFile)
            assertTrue(
                "${demo.id}: source must be a Kotlin demo",
                sourcePath.startsWith("src/main/java/io/github/sceneview/demo/demos/") &&
                    sourcePath.endsWith(".kt"),
            )
        }
    }

    /** kebab-case: lowercase ASCII letters / digits, hyphen-separated. */
    private val kebabCase = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    @Test
    fun `registry is non-empty`() {
        // A regression in the collator (or an empty fragments dir) would silently
        // ship a zero-demo Samples tab.
        assertTrue("ALL_DEMOS must not be empty", ALL_DEMOS.isNotEmpty())
    }

    @Test
    fun `every demo id is unique`() {
        val ids = ALL_DEMOS.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("Duplicate demo ids would collide on deep links / Compose keys: $duplicates", duplicates.isEmpty())
        assertEquals("Id count must equal demo count", ALL_DEMOS.size, ids.toSet().size)
    }

    @Test
    fun `every demo id is kebab-case`() {
        // The id is the `sceneview://demo/<id>` path segment and a Compose key —
        // an uppercase letter or a space would break routing and grid stability.
        for (demo in ALL_DEMOS) {
            assertTrue(
                "Demo id '${demo.id}' must be kebab-case (lowercase, hyphen-separated)",
                kebabCase.matches(demo.id),
            )
        }
    }

    @Test
    fun `every demo id is deep-link routable`() {
        // Round-trip every registered id through the deep-link validator using
        // the real registry — guards that no demo is unreachable via
        // `sceneview://demo/<id>` or the `--es demo <id>` QA channel.
        for (demo in ALL_DEMOS) {
            assertEquals(
                "Demo '${demo.id}' must validate against its own registry",
                demo.id,
                DeepLinkRouter.validate(demo.id, ALL_DEMOS),
            )
        }
    }

    @Test
    fun `every demo belongs to a known category`() {
        val knownCategories = DEMO_CATEGORIES.toSet()
        for (demo in ALL_DEMOS) {
            assertTrue(
                "Demo '${demo.id}' has unknown category '${demo.category}' — " +
                    "must be one of $knownCategories",
                demo.category in knownCategories,
            )
        }
    }

    @Test
    fun `every category resolves to a display-name string resource`() {
        // categoryDisplayNameRes must never fall through to the default for a
        // category that's actually in DEMO_CATEGORIES — that would render the
        // wrong header label silently.
        for (category in DEMO_CATEGORIES) {
            val res = categoryDisplayNameRes(category)
            assertTrue("Category '$category' must map to a real string resource", res != 0)
        }
        // The fallback path is still exercised for a genuinely unknown key.
        assertEquals(
            "Unknown category must fall back to the Models & cameras label",
            categoryDisplayNameRes("nope"),
            categoryDisplayNameRes(DemoCategory.VIEW_3D),
        )
    }

    @Test
    fun `every demo carries non-zero title and subtitle resources`() {
        // titleRes / subtitleRes are @StringRes ints — a 0 means an unresolved
        // resource that would crash stringResource() at render time.
        for (demo in ALL_DEMOS) {
            assertTrue("Demo '${demo.id}' has no title resource", demo.titleRes != 0)
            assertTrue("Demo '${demo.id}' has no subtitle resource", demo.subtitleRes != 0)
        }
    }

    @Test
    fun `every demo title and subtitle resource is unique`() {
        // Two demos pointing at the same string resource is almost always a
        // copy-paste bug in a fragment.
        val titleDuplicates = ALL_DEMOS.map { it.titleRes }
            .groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("Demos share a title resource: $titleDuplicates", titleDuplicates.isEmpty())
        val subtitleDuplicates = ALL_DEMOS.map { it.subtitleRes }
            .groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("Demos share a subtitle resource: $subtitleDuplicates", subtitleDuplicates.isEmpty())
    }

    @Test
    fun `every demo carries an icon`() {
        for (demo in ALL_DEMOS) {
            assertNotNull("Demo '${demo.id}' must have an icon", demo.icon)
        }
    }

    @Test
    fun `every demo has a defined status`() {
        // Defaults to Working — pin that the enum is honoured and no demo is
        // left with an out-of-band status.
        val knownStatuses = DemoStatus.entries.toSet()
        for (demo in ALL_DEMOS) {
            assertTrue("Demo '${demo.id}' has an unknown status", demo.status in knownStatuses)
        }
    }

    @Test
    fun `AR diagnostics retain their camera routing in Developer tools`() {
        val expected = setOf(
            "ar-plane-node", "ar-surfaces", "ar-depth-visualization", "ar-point-cloud",
            "ar-raw-depth-point-cloud", "ar-image-stabilization", "ar-cloud-anchor",
            "ar-geospatial-anchors", "ar-ml-object-label", "ar-xr",
        )
        val diagnostics = ALL_DEMOS.filter { it.category == DemoCategory.DEV_TOOLS && it.isArDemo }
        assertEquals(expected, diagnostics.map { it.id }.toSet())
        assertTrue(diagnostics.filterNot { it.id == "ar-xr" }.all { it.opensCameraOnEntry })
        assertFalse(diagnostics.single { it.id == "ar-xr" }.opensCameraOnEntry)
    }

    @Test
    fun `every demo order is unique`() {
        // Registry order also ranks search results; ties would depend on collator internals.
        val duplicates = ALL_DEMOS.map { it.order }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("Demos share an editorial order: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `registry is already in editorial order`() {
        // Keep the collator output deterministic independently of home section order.
        assertEquals(
            "ALL_DEMOS must be sorted by DemoEntry.order",
            ALL_DEMOS.sortedBy { it.order }.map { it.id },
            ALL_DEMOS.map { it.id },
        )
    }

    @Test
    fun `every demo carries at least one search tag`() {
        for (demo in ALL_DEMOS) {
            assertTrue("Demo '${demo.id}' has no tags — home search would never find it", demo.tags.isNotEmpty())
            for (tag in demo.tags) {
                assertTrue("Tag '$tag' on '${demo.id}' must be lowercase and non-blank", tag.isNotBlank() && tag == tag.lowercase())
            }
        }
    }

    @Test
    fun `2D in 3D exposes Inspect and Media on one searchable card`() {
        val demo = ALL_DEMOS.single { it.id == "two-d-in-three-d" }
        assertTrue(demo.tags.containsAll(setOf("picking", "material", "text", "image", "video", "billboard")))
        assertEquals(listOf("inspect", "media"), TwoDInThreeDFragment.modes.map { it.key })
        assertEquals(listOf(R.string.demo_mode_inspect, R.string.demo_mode_media),
            TwoDInThreeDFragment.modes.map { it.labelRes })
        assertEquals(mapOf("inspect" to 0, "media" to 1), DeepLinkRouter.TAB_NAMES[demo.id])
        assertTrue(demo.id in DeepLinkRouter.TABBED_DEMOS)
        for (alias in listOf("text", "image", "video", "billboard")) {
            assertFalse(ALL_DEMOS.any { it.id == alias })
            assertEquals(demo.id, DeepLinkRouter.validate(alias, ALL_DEMOS))
            assertEquals(1, DeepLinkRouter.ALIAS_INITIAL_TAB[alias])
        }
    }

    @Test
    fun `the fragment template id never ships`() {
        // `fragments/README.md` documents a copy-paste template registered as
        // `my-demo`; the collator rejects it and so does this.
        assertFalse("'my-demo' is the template id and must not be registered", ALL_DEMOS.any { it.id == "my-demo" })
    }

    /**
     * #4139: the route asks for the camera before an AR demo composes — except the demos
     * whose first screen needs none, which gate their camera screens themselves. Pinned by
     * id: gating one of these again locks a replay, a chooser or a static pose behind a
     * permission it never uses; a new AR demo is gated unless it is added here on purpose.
     */
    @Test
    fun `every AR demo asks for the camera on entry, except the four that open without one`() {
        val arDemos = ALL_DEMOS.filter { it.isArDemo }
        assertEquals(
            setOf("ar-placement", "ar-rerun", "ar-splat-room", "ar-xr"),
            arDemos.filterNot { it.opensCameraOnEntry }.map { it.id }.toSet(),
        )
        assertTrue("the AR demos behind the route gate", arDemos.count { it.opensCameraOnEntry } > 0)
        assertEquals(
            "a demo that is not AR never asks for the camera on entry",
            emptyList<String>(),
            ALL_DEMOS.filter { !it.isArDemo && it.opensCameraOnEntry }.map { it.id },
        )
    }

    @Test
    fun `deep-link router rejects ids that are not in the registry`() {
        // Negative-space guard: an id that looks like a demo but isn't registered
        // must not route. Complements the per-demo positive check above.
        assertFalse(
            "an unregistered id must not validate",
            DeepLinkRouter.validate("definitely-not-a-real-demo", ALL_DEMOS) != null,
        )
    }
}
