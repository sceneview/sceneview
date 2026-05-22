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
