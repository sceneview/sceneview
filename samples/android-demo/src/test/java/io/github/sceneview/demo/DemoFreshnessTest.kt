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
        // Old enough to sit outside every window these tests use.
        addedIn: String = "4.0.0",
        updatedIn: String? = null,
        status: DemoStatus = DemoStatus.Working,
    ) = DemoEntry(
        id = id,
        titleRes = 1,
        subtitleRes = 2,
        category = DemoCategory.CREATE,
        icon = Icons.Filled.Palette,
        order = 1,
        tags = setOf("tag"),
        addedIn = addedIn,
        updatedIn = updatedIn,
        status = status,
    )

    // ── The window ────────────────────────────────────────────────────────

    @Test
    fun `the build's own release is inside the window`() {
        assertTrue(isRecentVersion("4.35.0", buildVersion = "4.35.0"))
    }

    @Test
    fun `the two releases before the build's are inside the default window`() {
        // Two minors, shared with iOS: a 4.35 build marks 4.33, 4.34 and 4.35.
        assertEquals(2, FRESHNESS_WINDOW_MINORS)
        assertTrue(isRecentVersion("4.34.0", buildVersion = "4.35.0"))
        assertTrue(isRecentVersion("4.33.0", buildVersion = "4.35.0"))
    }

    @Test
    fun `three releases back is outside the default window`() {
        assertFalse(isRecentVersion("4.32.0", buildVersion = "4.35.0"))
    }

    @Test
    fun `a version declared ahead of the build is fresh, not a crash`() {
        // The registry test below forbids it; the rule itself still has to read
        // a stray one as the newest thing in the app rather than as old.
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
        assertFalse(isRecentVersion("4.32.9", buildVersion = "4.35.2"))
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
        assertFalse(isRecentVersion("4.31.0", buildVersion = "4.35.0"))
        assertTrue(isRecentVersion("4.31.0", buildVersion = "4.35.0", window = 4))
    }

    // ── The marker ────────────────────────────────────────────────────────

    @Test
    fun `an old arrival with no update means no marker`() {
        assertEquals(DemoFreshness.None, demo().freshness("4.35.0"))
    }

    @Test
    fun `addedIn in the window reads New`() {
        assertEquals(
            DemoFreshness.New,
            demo(addedIn = "4.35.0").freshness("4.35.0"),
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
            demo(addedIn = "4.35.0", updatedIn = "4.35.0").freshness("4.35.0"),
        )
    }

    @Test
    fun `an old arrival with a recent change reads Updated`() {
        assertEquals(
            DemoFreshness.Updated,
            demo(addedIn = "4.10.0", updatedIn = "4.35.0").freshness("4.35.0"),
        )
    }

    @Test
    fun `a stale declaration goes quiet on its own`() {
        // The property that makes a hand-maintained field survivable: nobody
        // ever has to open a fragment to remove a marker.
        val stale = demo(addedIn = "4.20.0", updatedIn = "4.21.0")
        assertEquals(DemoFreshness.None, stale.freshness("4.35.0"))
    }

    @Test
    fun `a coming-soon demo is never marked`() {
        // Nothing new to try on a card that does not run yet — the iOS rule.
        val comingSoon = DemoStatus.ComingSoon
        assertEquals(
            DemoFreshness.None,
            demo(addedIn = "4.35.0", status = comingSoon).freshness("4.35.0"),
        )
        assertEquals(
            DemoFreshness.None,
            demo(updatedIn = "4.35.0", status = comingSoon).freshness("4.35.0"),
        )
        assertTrue(
            freshDemos(listOf(demo(addedIn = "4.35.0", status = comingSoon)), "4.35.0").isEmpty(),
        )
    }

    @Test
    fun `a known-issue demo keeps its marker`() {
        // It runs, so a visible change is still worth pointing at.
        assertEquals(
            DemoFreshness.Updated,
            demo(updatedIn = "4.35.0", status = DemoStatus.KnownIssue).freshness("4.35.0"),
        )
    }

    // ── The list ──────────────────────────────────────────────────────────

    @Test
    fun `freshDemos keeps registry order and drops everything unmarked`() {
        val demos = listOf(
            demo(id = "a", updatedIn = "4.35.0"),
            demo(id = "b"),
            demo(id = "c", addedIn = "4.35.0"),
            demo(id = "d", updatedIn = "4.10.0"),
        )
        assertEquals(listOf("a", "c"), freshDemos(demos, "4.35.0").map { it.id })
    }

    @Test
    fun `freshDemos is empty when no demo moved`() {
        assertTrue(freshDemos(listOf(demo(), demo(id = "b")), "4.35.0").isEmpty())
    }

    // ── The real registry ─────────────────────────────────────────────────

    @Test
    fun `every declared version in the registry parses`() {
        // A typo silently loses the badge rather than failing, so the only place
        // it can be caught is here.
        ALL_DEMOS.forEach { demo ->
            listOf(demo.addedIn to "addedIn", demo.updatedIn to "updatedIn")
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
    fun `every demo declares the version it was added in`() {
        // Required, so a new fragment cannot ship without one: the field is what
        // puts it under "New" for two releases and then takes it off again.
        ALL_DEMOS.forEach { demo ->
            assertTrue("${demo.id}: addedIn is blank", demo.addedIn.isNotBlank())
        }
    }

    @Test
    fun `no demo declares a version newer than the build`() {
        // Work on main declares the version it ships in today, VERSION_NAME. A
        // version from the future would badge itself "New" until that release
        // and two more after it.
        val build = BuildConfig.VERSION_NAME
        ALL_DEMOS.forEach { demo ->
            listOfNotNull(demo.addedIn to "addedIn", demo.updatedIn?.let { it to "updatedIn" })
                .forEach { (version, field) ->
                    assertTrue(
                        "${demo.id}: $field = \"$version\" is newer than the build ($build)",
                        isRecentVersion(build, buildVersion = version, window = 0),
                    )
                }
        }
    }

    @Test
    fun `a demo never claims to have been updated before it existed`() {
        ALL_DEMOS.forEach { demo ->
            val since = demo.addedIn
            val updated = demo.updatedIn
            if (updated != null) {
                assertTrue(
                    "${demo.id}: updatedIn ($updated) predates addedIn ($since)",
                    isRecentVersion(updated, buildVersion = since, window = 0) ||
                        updated == since,
                )
            }
        }
    }

    @Test
    fun `a release where no demo moved marks nothing`() {
        // The state #3927 made legal: every declaration is out of the window.
        val registry = listOf(
            demo(id = "a", updatedIn = "4.39.0"),
            demo(id = "b", addedIn = "4.20.0"),
            demo(id = "c"),
        )
        assertTrue(freshDemos(registry, buildVersion = "4.43.0").isEmpty())
        assertTrue(registry.all { it.freshness("4.43.0") == DemoFreshness.None })
    }

    @Test
    fun `the registry never marks most of the grid`() {
        // The failure mode of a hand-maintained marker CI can catch: a release
        // that declared everything. The upper bound is a third of the catalogue.
        //
        // An empty set is legal on purpose (#3927). A minor release in which no
        // demo changed visibly has nothing to badge; the Showcase then draws no
        // chip and the What's-new sheet drops its "to try" section. The former
        // lower bound ("the registry marks something") turned such a bump PR red
        // inside the release run, after the QA gate, and was only ever fixed by
        // hand-adding an `updatedIn` nobody had earned.
        val marked = freshDemos(ALL_DEMOS, BuildConfig.VERSION_NAME)
        assertTrue(
            "${marked.size} of ${ALL_DEMOS.size} demos are marked — the badge means nothing",
            marked.size * 3 <= ALL_DEMOS.size,
        )
    }
}
