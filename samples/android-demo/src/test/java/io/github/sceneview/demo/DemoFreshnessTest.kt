package io.github.sceneview.demo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "New" / "Updated" marker rule (#3566).
 *
 * The value of the marker is entirely in when it goes *away*: a badge that never
 * expires is on a third of the grid within a quarter and means nothing. Most of
 * what is asserted here is therefore the negative case.
 */
class DemoFreshnessTest {

    private fun demo(
        id: String = "demo",
        sinceVersion: String? = null,
        updatedIn: String? = null,
    ) = DemoEntry(
        id = id,
        titleRes = 1,
        subtitleRes = 2,
        category = DemoCategory.GEOMETRY_MATERIALS,
        icon = Icons.Filled.Palette,
        order = 1,
        tags = setOf("tag"),
        sinceVersion = sinceVersion,
        updatedIn = updatedIn,
    )

    // ── The window ────────────────────────────────────────────────────────

    @Test
    fun `the build's own release is inside the window`() {
        assertTrue(isRecentVersion("4.35.0", buildVersion = "4.35.0"))
    }

    @Test
    fun `the release before the build's is inside the default window`() {
        assertTrue(isRecentVersion("4.34.0", buildVersion = "4.35.0"))
    }

    @Test
    fun `two releases back is outside the default window`() {
        assertFalse(isRecentVersion("4.33.0", buildVersion = "4.35.0"))
    }

    @Test
    fun `a version declared ahead of the build is fresh, not an error`() {
        // VERSION_NAME is bumped at release and then stays put, so every `main`
        // build between two releases reports the version that already shipped
        // while carrying work declared for the next one. That work is the newest
        // thing in the app — the marker's whole subject.
        assertTrue(isRecentVersion("4.35.0", buildVersion = "4.34.0"))
        assertTrue(isRecentVersion("5.0.0", buildVersion = "4.34.0"))
    }

    @Test
    fun `the previous major is out however high its minor was`() {
        assertFalse(isRecentVersion("4.99.0", buildVersion = "5.0.0"))
    }

    @Test
    fun `a build suffix does not change the comparison`() {
        assertTrue(isRecentVersion("4.35.0", buildVersion = "4.35.0-main.abc1234"))
        assertTrue(isRecentVersion("4.35.0", buildVersion = "4.35.0+ci.7"))
    }

    @Test
    fun `the patch level does not re-open the window`() {
        assertFalse(isRecentVersion("4.33.9", buildVersion = "4.35.2"))
    }

    @Test
    fun `a malformed or absent version never earns a chip`() {
        // A typo in a fragment must lose the badge, never crash the Showcase.
        assertFalse(isRecentVersion(null, buildVersion = "4.35.0"))
        assertFalse(isRecentVersion("", buildVersion = "4.35.0"))
        assertFalse(isRecentVersion("v4.35.0", buildVersion = "4.35.0"))
        assertFalse(isRecentVersion("4", buildVersion = "4.35.0"))
        assertFalse(isRecentVersion("main", buildVersion = "4.35.0"))
        assertFalse(isRecentVersion("4.35.0", buildVersion = "not-a-version"))
    }

    @Test
    fun `a wider window can be asked for explicitly`() {
        assertFalse(isRecentVersion("4.32.0", buildVersion = "4.35.0"))
        assertTrue(isRecentVersion("4.32.0", buildVersion = "4.35.0", window = 3))
    }

    // ── The marker ────────────────────────────────────────────────────────

    @Test
    fun `no declared version means no marker`() {
        assertEquals(DemoFreshness.None, demo().freshness("4.35.0"))
    }

    @Test
    fun `sinceVersion in the window reads New`() {
        assertEquals(
            DemoFreshness.New,
            demo(sinceVersion = "4.35.0").freshness("4.35.0"),
        )
    }

    @Test
    fun `updatedIn in the window reads Updated`() {
        assertEquals(
            DemoFreshness.Updated,
            demo(updatedIn = "4.35.0").freshness("4.35.0"),
        )
    }

    @Test
    fun `New wins over Updated when both are in the window`() {
        // A demo that arrived and was then touched inside the same window is
        // still, to a user, new.
        assertEquals(
            DemoFreshness.New,
            demo(sinceVersion = "4.35.0", updatedIn = "4.35.0").freshness("4.35.0"),
        )
    }

    @Test
    fun `an old arrival with a recent change reads Updated`() {
        assertEquals(
            DemoFreshness.Updated,
            demo(sinceVersion = "4.10.0", updatedIn = "4.35.0").freshness("4.35.0"),
        )
    }

    @Test
    fun `a stale declaration goes quiet on its own`() {
        // The property that makes a hand-maintained field survivable: nobody
        // ever has to open a fragment to remove a marker.
        val stale = demo(sinceVersion = "4.20.0", updatedIn = "4.21.0")
        assertEquals(DemoFreshness.None, stale.freshness("4.35.0"))
    }

    // ── The list and its headline ─────────────────────────────────────────

    @Test
    fun `freshDemos keeps registry order and drops everything unmarked`() {
        val demos = listOf(
            demo(id = "a", updatedIn = "4.35.0"),
            demo(id = "b"),
            demo(id = "c", sinceVersion = "4.35.0"),
            demo(id = "d", updatedIn = "4.10.0"),
        )
        assertEquals(listOf("a", "c"), freshDemos(demos, "4.35.0").map { it.id })
    }

    @Test
    fun `freshDemos is empty when no demo moved`() {
        assertTrue(freshDemos(listOf(demo(), demo(id = "b")), "4.35.0").isEmpty())
    }

    @Test
    fun `the headline names the highest version in the window, not the build`() {
        // Between two releases the build still reports the shipped version while
        // the cards below the headline are badged with the next one. The honest
        // headline is the one that matches the cards.
        val demos = listOf(
            demo(id = "a", updatedIn = "4.34.0"),
            demo(id = "b", updatedIn = "4.35.0"),
        )
        assertEquals("4.35", freshnessHeadlineVersion(demos, buildVersion = "4.34.0"))
    }

    @Test
    fun `the headline ignores declarations outside the window`() {
        val demos = listOf(demo(id = "a", updatedIn = "4.35.0"), demo(id = "b", sinceVersion = "3.99.0"))
        assertEquals("4.35", freshnessHeadlineVersion(demos, buildVersion = "4.35.0"))
    }

    @Test
    fun `the headline falls back to the build version with nothing in the window`() {
        assertEquals("4.35", freshnessHeadlineVersion(listOf(demo()), buildVersion = "4.35.0"))
    }

    @Test
    fun `the headline never shows a patch or a build suffix`() {
        assertEquals("4.35", shortVersionOf("4.35.2-main.abc1234"))
        assertEquals("4.35", shortVersionOf("4.35.0"))
    }

    // ── The real registry ─────────────────────────────────────────────────

    @Test
    fun `every declared version in the registry parses`() {
        // A typo silently loses the badge rather than failing, so the only place
        // it can be caught is here.
        ALL_DEMOS.forEach { demo ->
            listOfNotNull(demo.sinceVersion to "sinceVersion", demo.updatedIn to "updatedIn")
                .forEach { (version, field) ->
                    if (version != null) {
                        assertTrue(
                            "${demo.id}: $field = \"$version\" is not a major.minor.patch",
                            Regex("""^\d+\.\d+\.\d+$""").matches(version),
                        )
                    }
                }
        }
    }

    @Test
    fun `a demo never claims to have been updated before it existed`() {
        ALL_DEMOS.forEach { demo ->
            val since = demo.sinceVersion
            val updated = demo.updatedIn
            if (since != null && updated != null) {
                assertTrue(
                    "${demo.id}: updatedIn ($updated) predates sinceVersion ($since)",
                    isRecentVersion(updated, buildVersion = since, window = 0) ||
                        updated == since,
                )
            }
        }
    }

    @Test
    fun `the registry marks something, and not most of the grid`() {
        // Both failure modes of a hand-maintained marker in one assertion: a
        // release that forgot to declare anything, and a release that declared
        // everything. The upper bound is a third of the catalogue.
        val marked = freshDemos(ALL_DEMOS, BuildConfig.VERSION_NAME)
        assertTrue("no demo carries a freshness marker", marked.isNotEmpty())
        assertTrue(
            "${marked.size} of ${ALL_DEMOS.size} demos are marked — the badge means nothing",
            marked.size * 3 <= ALL_DEMOS.size,
        )
    }
}
