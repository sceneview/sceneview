package io.github.sceneview.demo.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The What's new surfaces show only the changelog entries written for the demo app's
 * users (#3992) — [demoAppHeadline] and [forDemoApp]. The headlines below are real
 * `CHANGELOG.md` lines, trimmed.
 */
class WhatsNewDemoScopeTest {

    @Test
    fun `a scope used as a label is stripped and the rest capitalised`() {
        assertEquals(
            "Rerun Debug now draws the AR session on the phone",
            demoAppHeadline("Demo app: Rerun Debug now draws the AR session on the phone"),
        )
        assertEquals(
            "Tapping Update in the in-app update prompt now shows the download",
            demoAppHeadline("Demo (Android): tapping Update in the in-app update prompt now shows the download"),
        )
        assertEquals(
            "Animation & Physics: the clip card no longer covers the back button",
            demoAppHeadline("Android demo, Animation & Physics: the clip card no longer covers the back button"),
        )
        assertEquals(
            "Materials: leaving Inspect no longer cuts the camera",
            demoAppHeadline("Demo — Materials: leaving Inspect no longer cuts the camera"),
        )
        assertEquals(
            "In dark theme, the Settings sheet is readable",
            demoAppHeadline("Android demo app: in dark theme, the Settings sheet is readable"),
        )
        assertEquals(
            "Renamed \"SceneView Demo — SDK samples\"",
            demoAppHeadline("Demo apps: renamed \"SceneView Demo — SDK samples\""),
        )
    }

    @Test
    fun `a scope that is the subject of the sentence keeps the whole headline`() {
        val headline = "The demo app opens on a live dusk flight"
        assertEquals(headline, demoAppHeadline(headline))
    }

    @Test
    fun `entries for other surfaces, other apps and the team are not shown`() {
        listOf(
            // The two lines the visual QA flagged.
            "Demo cold start: the ~88 refused frames after the home hero loads are the emulator",
            "Release: the npm publish check now waits up to 5 minutes",
            "iOS demo: native Liquid Glass on iOS 26",
            "TV demo: picking a model now shows a loading state",
            "Flutter: AR tap-to-place with touch manipulation",
            "The camera no longer jumps when a model loads",
            "Website: open any 3D model from a link",
            "Demo application note",
        ).forEach { assertNull(it, demoAppHeadline(it)) }
    }

    @Test
    fun `forDemoApp keeps each kept entry's id so acknowledgements survive`() {
        val kept = WhatsNewEntry(
            id = whatsNewEntryId("Demo app: Rerun Debug draws in 3D"),
            category = WhatsNewCategory.Added,
            text = "Demo app: Rerun Debug draws in 3D",
        )
        val dropped = WhatsNewEntry(
            id = whatsNewEntryId("CI stops running work"),
            category = WhatsNewCategory.Changed,
            text = "CI stops running work",
        )
        val scoped = listOf(WhatsNewSection(null, null, null, listOf(kept, dropped))).forDemoApp()
        assertEquals(1, scoped.single().entries.size)
        val entry = scoped.single().entries.single()
        assertEquals(kept.id, entry.id)
        assertEquals("Rerun Debug draws in 3D", entry.text)
    }

    @Test
    fun `a colon inside the bold headline is read as a scope label`() {
        val section = parseChangelogSections(
            """
            ## Unreleased

            ### Fixed
            - **Demo — Materials:** leaving Inspect a few seconds after dragging no longer cuts. More prose.
            """.trimIndent(),
        ).forDemoApp().single()
        assertEquals(
            "Materials: leaving Inspect a few seconds after dragging no longer cuts",
            section.entries.single().text,
        )
    }
}
