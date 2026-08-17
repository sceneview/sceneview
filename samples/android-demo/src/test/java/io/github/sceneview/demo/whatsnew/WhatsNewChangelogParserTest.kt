package io.github.sceneview.demo.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [parseWhatsNew] — the parser behind the Samples tab's
 * "What's new" card and sheet.
 *
 * The fixtures reproduce the three header shapes `CHANGELOG.md` has carried
 * over its life (see the file-level KDoc on `WhatsNewChangelog.kt`); the
 * companion [WhatsNewAssetIntegrityTest] runs the same parser against the
 * *real* bundled asset so a collator format change that this parser cannot
 * read fails the build instead of silently blanking the card.
 */
class WhatsNewChangelogParserTest {

    @Test
    fun `parses current header shape - version, date and title`() {
        val releases = parseWhatsNew(
            """
            # Changelog

            ## Unreleased

            ## v4.30.0 — 2026-08-12 — Cached handles that stop lying

            ### Fixed

            - **Depth occlusion had no visible effect at all ([#1617](https://github.com/sceneview/sceneview/issues/1617)).** Long prose explanation.
            """.trimIndent()
        )
        assertEquals(1, releases.size)
        val release = releases.first()
        assertEquals("4.30.0", release.version)
        assertEquals("2026-08-12", release.date)
        assertEquals("Cached handles that stop lying", release.title)
        assertEquals(1, release.highlights.size)
        val highlight = release.highlights.first()
        assertEquals(WhatsNewCategory.Fixed, highlight.category)
        assertEquals("Depth occlusion had no visible effect at all", highlight.headline)
        assertEquals(1617, highlight.issueNumber)
    }

    @Test
    fun `parses titleless header shape`() {
        val releases = parseWhatsNew("## v4.28.0 — 2026-08-10\n\n### Added\n\n- **New thing.** Prose.")
        assertEquals("4.28.0", releases.single().version)
        assertEquals("2026-08-10", releases.single().date)
        assertNull(releases.single().title)
    }

    @Test
    fun `parses legacy header shape - title with trailing date`() {
        val releases = parseWhatsNew(
            "## v4.23.0 — Gaussian Splatting on Android & Web (2026-07-18)\n\n### Added\n\n- **Splats.** Prose."
        )
        val release = releases.single()
        assertEquals("4.23.0", release.version)
        assertEquals("2026-07-18", release.date)
        assertEquals("Gaussian Splatting on Android & Web", release.title)
    }

    @Test
    fun `a release title containing an em dash is rejoined, not truncated`() {
        val releases = parseWhatsNew("## v4.9.0 — 2026-05-16 — One thing — and another\n")
        assertEquals("One thing — and another", releases.single().title)
    }

    @Test
    fun `unreleased section bullets are never reported as shipped`() {
        val releases = parseWhatsNew(
            """
            ## Unreleased

            ### Fixed

            - **A pending fix.** Not shipped yet.

            ## v4.30.0 — 2026-08-12

            ### Fixed

            - **A shipped fix.** Out the door.
            """.trimIndent()
        )
        assertEquals(1, releases.size)
        assertEquals(
            listOf("A shipped fix"),
            releases.single().highlights.map { it.headline },
        )
    }

    @Test
    fun `tests and docs categories are skipped - engineering bookkeeping is not user-facing`() {
        val releases = parseWhatsNew(
            """
            ## v4.30.0 — 2026-08-12

            ### Fixed

            - **User-facing fix.** Prose.

            ### Tests

            - **A gate got a mutation suite.** Prose.

            ### Docs

            - **A doc stopped lying.** Prose.
            """.trimIndent()
        )
        assertEquals(
            listOf("User-facing fix"),
            releases.single().highlights.map { it.headline },
        )
    }

    @Test
    fun `all five user-facing categories are recognised`() {
        val releases = parseWhatsNew(
            """
            ## v1.0.0 — 2026-01-01

            ### Added
            - **A.** x
            ### Fixed
            - **F.** x
            ### Changed
            - **C.** x
            ### Performance
            - **P.** x
            ### Removed
            - **R.** x
            """.trimIndent()
        )
        assertEquals(
            listOf(
                WhatsNewCategory.Added,
                WhatsNewCategory.Fixed,
                WhatsNewCategory.Changed,
                WhatsNewCategory.Performance,
                WhatsNewCategory.Removed,
            ),
            releases.single().highlights.map { it.category },
        )
    }

    @Test
    fun `stops after maxReleases - the 1 MB tail is never parsed`() {
        val text = (1..10).joinToString("\n") { i ->
            "## v4.$i.0 — 2026-01-0$i\n\n### Added\n\n- **Feature $i.** Prose.\n"
        }
        val releases = parseWhatsNew(text, maxReleases = 3)
        assertEquals(listOf("4.1.0", "4.2.0", "4.3.0"), releases.map { it.version })
    }

    @Test
    fun `headline markdown is stripped - links, bold, backticks, trailing punctuation`() {
        val releases = parseWhatsNew(
            """
            ## v1.0.0 — 2026-01-01

            ### Added

            - **`SceneView(isRendering = false)` parks the [frame loop](https://example.com) ([#3108](https://github.com/sceneview/sceneview/issues/3108), [#3110](https://github.com/sceneview/sceneview/issues/3110)).** Prose.
            """.trimIndent()
        )
        val highlight = releases.single().highlights.single()
        assertEquals("SceneView(isRendering = false) parks the frame loop", highlight.headline)
        assertEquals(3108, highlight.issueNumber)
    }

    @Test
    fun `unbolded legacy bullet falls back to its first sentence`() {
        val releases = parseWhatsNew(
            """
            ## v1.0.0 — 2026-01-01

            ### Fixed

            - quality-gate no longer dies without printing a verdict. Under set -e a grep that
              matches nothing exits 1.
            """.trimIndent()
        )
        assertEquals(
            "quality-gate no longer dies without printing a verdict",
            releases.single().highlights.single().headline,
        )
        assertNull(releases.single().highlights.single().issueNumber)
    }

    @Test
    fun `indented continuation lines are not treated as new highlights`() {
        val releases = parseWhatsNew(
            """
            ## v1.0.0 — 2026-01-01

            ### Fixed

            - **Real bullet.** Prose that wraps onto
              - an indented sub-bullet that is part of the same entry.
            """.trimIndent()
        )
        assertEquals(1, releases.single().highlights.size)
    }

    @Test
    fun `bullets outside any recognised category are dropped`() {
        val releases = parseWhatsNew(
            "## v1.0.0 — 2026-01-01\n\n- **Orphan bullet before any category.** Prose.\n"
        )
        assertTrue(releases.single().highlights.isEmpty())
    }

    @Test
    fun `garbage input degrades to an empty list, never a crash`() {
        assertTrue(parseWhatsNew("").isEmpty())
        assertTrue(parseWhatsNew("no headers at all\n- stray bullet\n### stray category").isEmpty())
        assertTrue(parseWhatsNew("## not-a-version — hello\n### Added\n- **X.** y").isEmpty())
    }
}
