package io.github.sceneview.demo.sketchfab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Curation invariants for [SampleAssets]. None of these touch the network or
 * filesystem — they run on the bare JVM in milliseconds and guard the rules
 * the registry promises:
 *
 *  - Every entry is CC-BY (no NC/ND/SA, no Sketchfab Standard).
 *  - Every entry has a bundled fallback path.
 *  - Every entry has a sane scale + non-empty author.
 *  - The registry has no duplicate uids.
 *  - Every demo category is represented.
 */
class SampleAssetsTest {

    @Test
    fun `registry is non-empty so the resolver has something to resolve`() {
        assertTrue(
            "SampleAssets.all must list at least one curated slug",
            SampleAssets.all.isNotEmpty(),
        )
    }

    @Test
    fun `every entry carries a CC-BY 4_0 license URL`() {
        val forbidden = SampleAssets.all.filterNot {
            it.licenseUrl.startsWith("https://creativecommons.org/licenses/by/")
        }
        assertTrue(
            "Non-CC-BY entries found in registry: ${forbidden.map { it.uid to it.licenseUrl }}",
            forbidden.isEmpty(),
        )
        // Stronger check — we only accept the 4.0 version (the one our
        // legal review covers). Older 3.0 / 2.5 versions need re-review.
        val nonV4 = SampleAssets.all.filterNot {
            it.licenseUrl.contains("/by/4.0/")
        }
        assertTrue(
            "CC-BY entries on a version other than 4.0 found: ${nonV4.map { it.uid to it.licenseUrl }}",
            nonV4.isEmpty(),
        )
    }

    @Test
    fun `every entry has a non-blank author for CC-BY attribution`() {
        val missing = SampleAssets.all.filter { it.author.isBlank() }
        assertTrue(
            "Entries with missing author: ${missing.map { it.uid }}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every entry has a non-blank fallback bundled path`() {
        val missing = SampleAssets.all.filter { it.fallbackBundledPath.isBlank() }
        assertTrue(
            "Entries with missing fallbackBundledPath: ${missing.map { it.uid }}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `scaleToUnits is within sane real-world bounds`() {
        // The bounds sanity check in the resolver rejects models outside
        // [0.05 m, 5 m]. Registry entries with hints outside that window are
        // self-contradicting — fail the test rather than ship them.
        val outliers = SampleAssets.all.filter {
            it.scaleToUnits < 0.05f || it.scaleToUnits > 5.0f
        }
        assertTrue(
            "Entries with out-of-range scaleToUnits: ${outliers.map { it.uid to it.scaleToUnits }}",
            outliers.isEmpty(),
        )
    }

    @Test
    fun `no duplicate uids`() {
        val grouped = SampleAssets.all.groupBy { it.uid }
        val dupes = grouped.filterValues { it.size > 1 }
        assertTrue("Duplicate uids in SampleAssets: ${dupes.keys}", dupes.isEmpty())
    }

    @Test
    fun `requireValid succeeds on the shipped registry`() {
        // Wraps every individual check above plus uid-format. Calling it from
        // the test suite catches the "I edited the registry and forgot to run
        // the other tests" case.
        SampleAssets.requireValid()
    }

    @Test
    fun `byUid lookup returns the same entry that is in all`() {
        SampleAssets.all.forEach { entry ->
            assertEquals(
                "byUid map is out of sync with all",
                entry,
                SampleAssets.byUid[entry.uid],
            )
        }
    }

    @Test
    fun `byCategory groups every entry`() {
        val totalGrouped = SampleAssets.byCategory.values.sumOf { it.size }
        assertEquals(
            "byCategory must group every entry exactly once",
            SampleAssets.all.size,
            totalGrouped,
        )
    }

    @Test
    fun `every demo category is represented`() {
        // If a category disappears the corresponding demo would silently start
        // running on an empty list — better to fail at CI time.
        val expected = setOf(
            "solar", "gallery", "animation", "park",
            "ar_placement", "physics", "materials",
        )
        val missing = expected - SampleAssets.byCategory.keys
        assertTrue(
            "Missing demo categories from SampleAssets: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `ar_placement fallbacks are pairwise distinct`() {
        // Mirrors iOS `testARPlacementFallbacksArePairwiseDistinct`
        // (SceneViewDemoTests/BundledAssetPrimBudgetTests.swift, #2973).
        // ARPlacementDemo / ARInstantPlacementDemo ACCUMULATE placed models —
        // two chips sharing a fallback render as the identical asset, side by
        // side, under two labels (#2940 / #2355).
        assertFallbacksPairwiseDistinct("ar_placement")
    }

    @Test
    fun `solar fallbacks are pairwise distinct`() {
        // Same defect class, different demo: OrbitalARDemo shows ALL four
        // `solar` companions at once on a single anchor, so in keyless mode a
        // shared fallback put the same model at four points of the ring — the
        // "too many models, they all look alike" half of #3341.
        assertFallbacksPairwiseDistinct("solar")
    }

    /**
     * Fails when two slugs of [category] share a `fallbackBundledPath`.
     *
     * Scope is per-category on purpose: distinctness only matters where a demo
     * shows several slugs of that category *simultaneously*. A registry-wide
     * version would fail on entries that are not defects — `gallery` and
     * `animation` may legitimately reuse a bundled GLB because their demos show
     * one model at a time.
     *
     * Distinctness is all this asserts — it says nothing about a fallback
     * RESEMBLING its label; that wider mismatch class is tracked for the iOS
     * registry under #2960.
     *
     * The slug set is derived from the registry BY CATEGORY, never from a
     * hardcoded list of names: a slug added tomorrow is covered without anyone
     * remembering to update this test.
     */
    private fun assertFallbacksPairwiseDistinct(category: String) {
        val slugs = SampleAssets.byCategory[category].orEmpty()

        // Distinctness over fewer than two slugs is vacuously true, so a
        // registry that lost the category would pass silently without this.
        assertTrue(
            "$category has ${slugs.size} slug(s) — pairwise distinctness is " +
                "vacuous below two, so this guard is no longer guarding anything",
            slugs.size > 1,
        )

        // First slug to claim each fallback path, in registry order.
        val claimedBy = mutableMapOf<String, String>()
        slugs.forEach { slug ->
            val owner = claimedBy[slug.fallbackBundledPath]
            if (owner != null) {
                fail(
                    "$category fallback collision: \"$owner\" and " +
                        "\"${slug.displayName}\" both fall back to " +
                        "${slug.fallbackBundledPath}. The demo for this category " +
                        "shows several models in one scene, so in keyless mode the " +
                        "two labels render the identical asset — the #2940 defect. " +
                        "Point one of them at a distinct bundled model (#2355).",
                )
            }
            claimedBy[slug.fallbackBundledPath] = slug.displayName
        }
    }

    @Test
    fun `constructor rejects a non-CC-BY license`() {
        try {
            SketchfabSlug(
                uid = "0".repeat(32),
                displayName = "Bad License",
                author = "test",
                // NC variant is intentionally not allowed.
                licenseUrl = "https://creativecommons.org/licenses/by-nc/4.0/",
                fallbackBundledPath = "models/khronos_fox.glb",
                scaleToUnits = 1f,
                hasBakedAnimation = false,
                category = "gallery",
            )
            fail("expected IllegalArgumentException for non-CC-BY license")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(
                "error message should reference CC-BY policy: ${e.message}",
                e.message!!.contains("CC-BY"),
            )
        }
    }

    @Test
    fun `constructor rejects a blank author`() {
        try {
            SketchfabSlug(
                uid = "0".repeat(32),
                displayName = "No Author",
                author = "",
                licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
                fallbackBundledPath = "models/khronos_fox.glb",
                scaleToUnits = 1f,
                hasBakedAnimation = false,
                category = "gallery",
            )
            fail("expected IllegalArgumentException for blank author")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error message should reference CC-BY attribution: ${e.message}",
                e.message!!.contains("CC-BY attribution"),
            )
        }
    }

    @Test
    fun `constructor rejects a non-positive scale`() {
        try {
            SketchfabSlug(
                uid = "0".repeat(32),
                displayName = "Bad Scale",
                author = "test",
                licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
                fallbackBundledPath = "models/khronos_fox.glb",
                scaleToUnits = 0f,
                hasBakedAnimation = false,
                category = "gallery",
            )
            fail("expected IllegalArgumentException for zero scale")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("scaleToUnits"))
        }
    }
}
