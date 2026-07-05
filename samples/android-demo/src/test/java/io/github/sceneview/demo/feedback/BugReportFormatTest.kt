package io.github.sceneview.demo.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Pure-JVM coverage of the lightweight bug-report formatting: the share text,
 * the GitHub-issue markdown, and the pre-filled `issues/new` URL (including
 * its length cap). No Robolectric needed — the formatting layer takes plain
 * data on purpose.
 */
class BugReportFormatTest {

    private val metadata = linkedMapOf(
        "App version" to "4.19.0 (350)",
        "Demo" to "model-viewer",
        "Device" to "Google Pixel 7a",
        "Android" to "15 (API 35)",
    )

    private fun info(
        logcat: List<String> = listOf("06-01 12:00:00.000 I/Demo: started"),
    ) = BugReportInfo(metadata = metadata, logcat = logcat)

    // ── Title ────────────────────────────────────────────────────────────────

    @Test
    fun `title uses the note's first line when present`() {
        val title = formatReportTitle(info(), "Model turns black\nafter rotating")
        assertEquals("[Demo app] Model turns black", title)
    }

    @Test
    fun `title falls back to the demo id then to a generic title`() {
        assertEquals("[Demo app] Bug report — model-viewer", formatReportTitle(info(), " "))
        val noDemo = BugReportInfo(metadata = linkedMapOf("Device" to "x"), logcat = emptyList())
        assertEquals("[Demo app] Bug report", formatReportTitle(noDemo, ""))
    }

    @Test
    fun `title caps a very long first line`() {
        val title = formatReportTitle(info(), "x".repeat(300))
        assertTrue(title.length <= "[Demo app] ".length + 80)
    }

    // ── Share text ───────────────────────────────────────────────────────────

    @Test
    fun `share text carries note, metadata and logcat`() {
        val text = formatShareText(info(), "It crashed")
        assertTrue(text.contains("It crashed"))
        assertTrue(text.contains("App version: 4.19.0 (350)"))
        assertTrue(text.contains("Demo: model-viewer"))
        assertTrue(text.contains("I/Demo: started"))
    }

    @Test
    fun `share text omits the log section when logcat is empty`() {
        val text = formatShareText(info(logcat = emptyList()), "note")
        assertFalse(text.contains("Recent app log"))
    }

    @Test
    fun `share text truncates the logcat to the issue cap`() {
        val lines = (1..200).map { "line $it" }
        val text = formatShareText(info(logcat = lines), "")
        assertTrue(text.contains("last $ISSUE_LOGCAT_MAX_LINES lines"))
        assertFalse(text.contains("line 1\n"))       // oldest lines dropped
        assertTrue(text.contains("line 200"))         // newest lines kept
    }

    // ── Issue body ───────────────────────────────────────────────────────────

    @Test
    fun `issue body is markdown with a context table and a details log block`() {
        val body = formatIssueBody(info(), "Steps: open the demo")
        assertTrue(body.startsWith("Steps: open the demo"))
        assertTrue(body.contains("| App version | 4.19.0 (350) |"))
        assertTrue(body.contains("<details><summary>App log (last 1 lines)</summary>"))
        assertTrue(body.contains("```"))
        assertTrue(body.contains("</details>"))
    }

    @Test
    fun `issue body escapes pipes in metadata and fences in log lines`() {
        val tricky = BugReportInfo(
            metadata = linkedMapOf("Device" to "a|b"),
            logcat = listOf("evil ``` fence"),
        )
        val body = formatIssueBody(tricky, "")
        assertTrue(body.contains("| Device | a\\|b |"))
        assertFalse(body.contains("evil ``` fence"))
        assertTrue(body.contains("evil ''' fence"))
    }

    @Test
    fun `issue body notes a missing description and omits an empty log`() {
        val body = formatIssueBody(info(logcat = emptyList()), "  ")
        assertTrue(body.contains("_No description provided._"))
        assertFalse(body.contains("<details>"))
    }

    // ── GitHub URL ───────────────────────────────────────────────────────────

    @Test
    fun `issue url points at issues-new with an encoded title and body`() {
        val url = buildGitHubIssueUrl(info(), "Model turns black")
        assertTrue(url.startsWith("$GITHUB_NEW_ISSUE_URL?title="))
        val decodedBody = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(decodedBody.contains("| Demo | model-viewer |"))
        assertTrue(decodedBody.contains("Model turns black"))
    }

    @Test
    fun `issue url stays under the length cap even with a huge logcat`() {
        val lines = (1..400).map { "06-01 12:00:00.000 W/Filament: " + "x".repeat(180) }
        val url = buildGitHubIssueUrl(info(logcat = lines), "note")
        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
    }

    @Test
    fun `issue url survives a pathological note`() {
        val url = buildGitHubIssueUrl(info(logcat = emptyList()), "y".repeat(50_000))
        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
    }
}
