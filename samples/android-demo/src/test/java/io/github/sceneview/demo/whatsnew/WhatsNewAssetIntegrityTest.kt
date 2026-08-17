package io.github.sceneview.demo.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Staleness guard for the "What's new" surface.
 *
 * The Samples tab derives its What's new content from `CHANGELOG.md`, which
 * `bundle<Variant>ChangelogAsset` (samples/android-demo/build.gradle) copies into the
 * APK assets on every build. Two independent failure modes would silently
 * blank the card while everything still compiled:
 *
 *  1. the Gradle asset wiring rots (task renamed, source-set dropped) and the
 *     asset stops shipping;
 *  2. `collate-changelog.sh` changes the release-header or bullet format past
 *     what [parseWhatsNew] understands, and parsing yields nothing.
 *
 * This test opens the *merged* asset exactly like the app does and requires
 * real parsed content, so either rot fails `testDebugUnitTest` instead of
 * shipping an app whose "what was recently shipped" surface quietly vanished.
 */
@RunWith(RobolectricTestRunner::class)
class WhatsNewAssetIntegrityTest {

    private val assets get() = RuntimeEnvironment.getApplication().assets

    @Test
    fun `bundled changelog asset exists and parses to real releases`() {
        val releases = loadWhatsNew(assets)
        assertEquals(
            "loadWhatsNew must yield exactly WHATS_NEW_MAX_RELEASES releases from " +
                "the bundled CHANGELOG.md — fewer means the asset is missing, " +
                "truncated, or the collator format drifted past the parser",
            WHATS_NEW_MAX_RELEASES,
            releases.size,
        )
        releases.forEach { release ->
            assertTrue(
                "release version '${release.version}' must look like a semver",
                release.version.matches(Regex("""\d+\.\d+(\.\d+)*""")),
            )
        }
        // The card is only worth rendering if the latest release actually
        // carries user-facing highlights. An all-empty parse means the bullet
        // format drifted (e.g. the bold-headline convention changed).
        assertTrue(
            "the latest bundled release must parse to at least one user-facing highlight",
            releases.first().highlights.isNotEmpty(),
        )
        releases.first().highlights.forEach { highlight ->
            assertTrue("headline must be non-blank", highlight.headline.isNotBlank())
        }
    }

    @Test
    fun `latest bundled release is newest-first and carries a date`() {
        val releases = loadWhatsNew(assets)
        val dates = releases.mapNotNull { it.date }
        assertTrue("recent releases all carry ISO dates in CHANGELOG.md", dates.size == releases.size)
        assertEquals(
            "releases must be newest-first — the card shows releases.first()",
            dates.sortedDescending(),
            dates,
        )
    }
}
