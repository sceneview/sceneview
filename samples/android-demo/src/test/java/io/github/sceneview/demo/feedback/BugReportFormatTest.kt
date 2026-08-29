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
        "Screen" to "Demo · model-viewer",
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
    fun `share text carries the whole captured window, not the issue cap`() {
        // The share path has no URL budget: it is the one that must survive a
        // repeating warning long enough to measure its period (#3390).
        val lines = (1..LOGCAT_TAIL_LINES).map { "line $it" }
        val text = formatShareText(info(logcat = lines), "")
        assertTrue(text.contains("last $LOGCAT_TAIL_LINES lines"))
        assertTrue(text.contains("line 1\n"))
        assertTrue(text.contains("line $LOGCAT_TAIL_LINES"))
    }

    @Test
    fun `share text keeps the newest lines when the capture overflows`() {
        val lines = (1..SHARE_LOGCAT_MAX_LINES + 50).map { "line $it" }
        val text = formatShareText(info(logcat = lines), "")
        assertTrue(text.contains("last $SHARE_LOGCAT_MAX_LINES lines"))
        assertFalse(text.contains("line 1\n"))                        // oldest dropped
        assertTrue(text.contains("line ${SHARE_LOGCAT_MAX_LINES + 50}")) // newest kept
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
    fun `issue url packs a usable log window, not a couple of dozen lines`() {
        // Regression guard for #3390: the coarse fallback ladder used to snap to
        // 30 lines — too short to see a repeating warning twice and measure its
        // period. Realistic threadtime lines, so the budget arithmetic is real.
        val lines = (1..ISSUE_LOGCAT_MAX_LINES).map {
            val millis = (it % 1000).toString().padStart(3, '0')
            "06-01 12:00:00.$millis  6543  6560 W Filament: throttled draw call skipped"
        }
        val url = buildGitHubIssueUrl(info(logcat = lines), "Repeating warning in the AR view")
        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)

        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        val packed = Regex("""App log \(last (\d+) lines\)""").find(body)!!.groupValues[1].toInt()
        assertTrue("packed only $packed lines", packed >= 60)
        assertTrue(body.contains("| Screen | Demo · model-viewer |"))
    }

    // ── Log compaction ───────────────────────────────────────────────────────

    @Test
    fun `compaction drops the date and pid columns but keeps the milliseconds`() {
        // Milliseconds are what makes a repeating warning measurable; the date
        // and the pid/tid pair are constant across the whole capture.
        assertEquals(
            "12:00:00.123 W Filament: msg",
            compactLogLine("06-01 12:00:00.123  6543  6560 W Filament: msg"),
        )
        // Other shapes only lose their leading date.
        assertEquals("12:00:00.123 I/Demo: msg", compactLogLine("06-01 12:00:00.123 I/Demo: msg"))
        assertEquals("a free-form line", compactLogLine("a free-form line"))
    }

    @Test
    fun `issue url survives a pathological note`() {
        val url = buildGitHubIssueUrl(info(logcat = emptyList()), "y".repeat(50_000))
        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
    }
}
