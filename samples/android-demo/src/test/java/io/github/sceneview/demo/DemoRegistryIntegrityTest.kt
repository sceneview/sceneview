package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.sceneview.demo.ui.home.CHIP_CATEGORY_KEYS

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
            "Unknown category must fall back to the Viewer label",
            categoryDisplayNameRes("nope"),
            categoryDisplayNameRes(DemoCategory.VIEWER),
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
    fun `every category holds at least one demo`() {
        // Since #2239 a category is a SECTION HEADER on the home grid, not only a
        // filter chip. An empty category therefore no longer fails quietly — it
        // would draw a header with nothing under it. The old form of this test
        // exempted the AR category; there is no reason to, and with AR now split
        // across four categories the exemption would hide exactly the mistake it
        // was written to catch.
        val populated = ALL_DEMOS.map { it.category }.toSet()
        for (category in DEMO_CATEGORIES) {
            assertTrue(
                "Category '$category' has no demos — it would draw an empty section " +
                    "header, or a fragment failed to collate",
                category in populated,
            )
        }
    }

    @Test
    fun `each category occupies one contiguous run of the editorial order`() {
        // The home grid draws a section header by watching for "the category
        // changed" as it walks ALL_DEMOS (HomeScreen.kt). That is only correct
        // while a category's demos are contiguous: an `order` that sends one AR
        // Placement demo down among the Platform ones would draw the AR Placement
        // header twice and split the section in two.
        val runs = mutableListOf<String>()
        for (demo in ALL_DEMOS) {
            if (runs.lastOrNull() != demo.category) runs += demo.category
        }
        val split = runs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            "Categories are split across the editorial order and would draw a " +
                "duplicate section header: $split",
            split.isEmpty(),
        )
    }

    @Test
    fun `categories run in DEMO_CATEGORIES order`() {
        // DEMO_CATEGORIES is the chip-row order; the grid's section order comes
        // from `order`. If the two disagree, tapping the third chip scrolls to the
        // fifth section — the catalogue would be structured differently depending
        // on which control you used.
        val gridOrder = ALL_DEMOS.map { it.category }.distinct()
        assertEquals(
            "Section order on the grid must match DEMO_CATEGORIES",
            DEMO_CATEGORIES.filter { it in gridOrder.toSet() },
            gridOrder,
        )
    }

    @Test
    fun `every category has a filter chip`() {
        // A category with no chip is unreachable by filter; a chip for a category
        // with no demos filters to an empty grid. Both are #2239 regressions the
        // 9-category split made easy to introduce.
        assertEquals(
            "CHIP_CATEGORIES must cover exactly DEMO_CATEGORIES, in the same order",
            DEMO_CATEGORIES,
            CHIP_CATEGORY_KEYS,
        )
    }

    @Test
    fun `every demo order is unique`() {
        // `order` is the home-grid position; two demos sharing one would make
        // the grid order depend on collator internals.
        val duplicates = ALL_DEMOS.map { it.order }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("Demos share an editorial order: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `registry is already in editorial order`() {
        // The collator sorts on `order` so HomeScreen renders ALL_DEMOS flat,
        // with no re-sort. If this breaks, the home grid silently falls back to
        // whatever order the fragments were discovered in.
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
    fun `the fragment template id never ships`() {
        // `fragments/README.md` documents a copy-paste template registered as
        // `my-demo`; the collator rejects it and so does this.
        assertFalse("'my-demo' is the template id and must not be registered", ALL_DEMOS.any { it.id == "my-demo" })
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
