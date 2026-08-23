package io.github.sceneview.demo.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the "since you last tested" rule — the part of the feature
 * that decides what a given install has and has not been shown.
 *
 * The cases below are the ones that would silently make the surface useless
 * rather than obviously broken: an install that skipped several releases seeing
 * only the newest, and the far more frequent one — between two releases, every
 * build re-presenting the whole pending backlog because nothing tracks entries
 * individually.
 */
class WhatsNewSinceTest {

    private val changelog = """
        # Changelog

        ## Unreleased

        ### Fixed

        - **Pending fix A ([#100](https://github.com/sceneview/sceneview/issues/100)).** Prose.
        - **Pending fix B.** Prose.

        ## v4.31.0 — 2026-08-17 — Latest

        ### Added

        - **Shipped in 4.31.** Prose.

        ## v4.30.0 — 2026-08-12 — Older

        ### Fixed

        - **Shipped in 4.30.** Prose.

        ## v4.29.0 — 2026-08-01 — Oldest

        ### Changed

        - **Shipped in 4.29.** Prose.
    """.trimIndent()

    private fun sections() = parseChangelogSections(changelog, maxReleases = 10)

    @Test
    fun `unreleased fragments parse as a version-less section`() {
        val unreleased = sections().first()
        assertTrue("the unreleased block must lead the list", unreleased.isUnreleased)
        assertEquals(2, unreleased.entries.size)
        assertEquals("Pending fix A", unreleased.entries.first().text)
    }

    @Test
    fun `released sections still parse newest-first`() {
        val versions = sections().filterNot { it.isUnreleased }.map { it.version }
        assertEquals(listOf("4.31.0", "4.30.0", "4.29.0"), versions)
    }

    @Test
    fun `skipping three releases shows all three, not just the newest`() {
        val seen = WhatsNewSeen(300, "4.28.0-main.abc1234", emptySet())
        val unseen = unseenSections(sections(), seen)
        assertEquals(
            listOf(null, "4.31.0", "4.30.0", "4.29.0"),
            unseen.map { it.version },
        )
    }

    @Test
    fun `a release at or below the seen version is not shown again`() {
        val seen = WhatsNewSeen(310, "4.30.0-main.abc1234", emptySet())
        val unseen = unseenSections(sections(), seen)
        assertEquals(listOf(null, "4.31.0"), unseen.map { it.version })
    }

    @Test
    fun `two builds of the same release do not re-show the acknowledged backlog`() {
        // The common case: no release in between, only merges. Acknowledging on
        // build A must silence A's pending entries on build B — a version-only
        // comparison would re-present the entire Unreleased section forever.
        val all = sections()
        val seen = WhatsNewSeen(320, "4.31.0-main.aaaaaaa", unreleasedEntryIds(all))
        assertTrue(unseenSections(all, seen).isEmpty())
    }

    @Test
    fun `only the entries added since acknowledgement are shown`() {
        val before = parseChangelogSections(changelog, maxReleases = 10)
        val seen = WhatsNewSeen(320, "4.31.0-main.aaaaaaa", unreleasedEntryIds(before))

        val after = parseChangelogSections(
            changelog.replace(
                "- **Pending fix B.** Prose.",
                "- **Pending fix B.** Prose.\n- **Brand new fix C.** Prose.",
            ),
            maxReleases = 10,
        )
        val unseen = unseenSections(after, seen)
        assertEquals(1, unseen.size)
        assertEquals(listOf("Brand new fix C"), unseen.single().entries.map { it.text })
    }

    @Test
    fun `an entry acknowledged while unreleased stays silent once it ships`() {
        // The id is a hash of the entry text precisely so it survives collation
        // moving the bullet from `## Unreleased` into a version section.
        val pending = parseChangelogSections(changelog, maxReleases = 10)
        val seen = WhatsNewSeen(320, "4.31.0-main.aaaaaaa", unreleasedEntryIds(pending))

        val released = parseChangelogSections(
            """
            # Changelog

            ## Unreleased

            ## v4.32.0 — 2026-08-25 — Ships the backlog

            ### Fixed

            - **Pending fix A ([#100](https://github.com/sceneview/sceneview/issues/100)).** Prose.
            - **Pending fix B.** Prose.
            - **Something genuinely new.** Prose.
            """.trimIndent(),
            maxReleases = 10,
        )
        val unseen = unseenSections(released, seen)
        assertEquals(
            listOf("Something genuinely new"),
            unseen.single().entries.map { it.text },
        )
    }

    @Test
    fun `no marker shows nothing rather than the whole history`() {
        assertTrue(unseenSections(sections(), seen = null).isEmpty())
    }

    @Test
    fun `entry id ignores the reference the collator attaches`() {
        // The same sentence, before and after collation appends its issue link.
        assertEquals(
            whatsNewEntryId("Pending fix A"),
            whatsNewEntryId("Pending fix A ([#100](https://github.com/sceneview/sceneview/issues/100))"),
        )
    }

    @Test
    fun `entry id survives cosmetic churn but distinguishes real entries`() {
        assertEquals(whatsNewEntryId("A  fixed   thing"), whatsNewEntryId("a fixed thing"))
        assertEquals(whatsNewEntryId("`Node` resize"), whatsNewEntryId("Node resize"))
        assertFalse(whatsNewEntryId("A fixed thing") == whatsNewEntryId("Another fixed thing"))
    }

    @Test
    fun `base version strips the build suffix so only a release moves the axis`() {
        assertEquals("4.31.0", baseVersionOf("4.31.0-main.abc1234"))
        assertEquals("4.31.0", baseVersionOf("4.31.0+ci.7"))
        assertEquals("4.31.0", baseVersionOf("4.31.0"))
    }

    @Test
    fun `version comparison is numeric, not lexicographic`() {
        // The bug this guards: "4.9.0" > "4.31.0" as strings, which would hide
        // every release after a tester's marker for the rest of the 4.x line.
        assertTrue(compareVersions("4.31.0", "4.9.0") > 0)
        assertTrue(compareVersions("4.30.0", "4.31.0") < 0)
        assertEquals(0, compareVersions("4.31", "4.31.0"))
    }

    @Test
    fun `an empty unreleased placeholder is not a section`() {
        val parsed = parseChangelogSections(
            "# Changelog\n\n## Unreleased\n\n## v4.31.0 — 2026-08-17\n\n### Added\n\n- **Thing.** Prose.",
            maxReleases = 5,
        )
        assertTrue("the committed placeholder must not render", parsed.none { it.isUnreleased })
    }

    @Test
    fun `the shipped card API is unchanged by the section refactor`() {
        val releases = parseWhatsNew(changelog, maxReleases = 3)
        assertEquals(listOf("4.31.0", "4.30.0", "4.29.0"), releases.map { it.version })
        assertEquals("Latest", releases.first().title)
        // The card has always shown code spans stripped.
        assertEquals("Shipped in 4.31", releases.first().highlights.single().headline)
    }
}
