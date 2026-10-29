package io.github.sceneview.demo.feedback

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
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

    // ── Report file ──────────────────────────────────────────────────────────

    @Test
    fun `report file carries note, metadata and logcat`() {
        val text = formatReportFile(info(), "It crashed")
        assertTrue(text.contains("It crashed"))
        assertTrue(text.contains("App version: 4.19.0 (350)"))
        assertTrue(text.contains("Demo: model-viewer"))
        assertTrue(text.contains("I/Demo: started"))
    }

    @Test
    fun `report file omits the log section when logcat is empty`() {
        val text = formatReportFile(info(logcat = emptyList()), "note")
        assertFalse(text.contains("Recent app log"))
    }

    @Test
    fun `report file carries the whole captured window, not the issue cap`() {
        // The share path has no URL budget: it is the one that must survive a
        // repeating warning long enough to measure its period (#3390).
        val lines = (1..LOGCAT_TAIL_LINES).map { "line $it" }
        val text = formatReportFile(info(logcat = lines), "")
        assertTrue(text.contains("last $LOGCAT_TAIL_LINES lines"))
        assertTrue(text.contains("line 1\n"))
        assertTrue(text.contains("line $LOGCAT_TAIL_LINES"))
    }

    @Test
    fun `report file keeps the newest lines when the capture overflows`() {
        val lines = (1..SHARE_LOGCAT_MAX_LINES + 50).map { "line $it" }
        val text = formatReportFile(info(logcat = lines), "")
        assertTrue(text.contains("last $SHARE_LOGCAT_MAX_LINES lines"))
        assertFalse(text.contains("line 1\n"))                        // oldest dropped
        assertTrue(text.contains("line ${SHARE_LOGCAT_MAX_LINES + 50}")) // newest kept
    }

    // ── Share text (#4528) ───────────────────────────────────────────────────

    @Test
    fun `share text carries note and metadata, and points at the file instead of the log`() {
        val text = formatShareText(info(), "It crashed")
        assertTrue(text.contains("It crashed"))
        assertTrue(text.contains("App version: 4.19.0 (350)"))
        assertTrue(text.contains("Demo: model-viewer"))
        assertFalse(text.contains("I/Demo: started"))
        assertTrue(text.contains("app log (1 lines) attached: $REPORT_FILE_NAME"))
    }

    @Test
    fun `share text says the log is missing when the file could not be written`() {
        val text = formatShareText(info(), "", logAttached = false)
        assertFalse(text.contains(REPORT_FILE_NAME))
        assertTrue(text.contains("could not be attached"))
    }

    @Test
    fun `share text stays under its budget whatever the log weighs`() {
        // 1200 lines of ~500 characters: the ~600 KB extra that the chooser refused.
        val heavy = (1..LOGCAT_TAIL_LINES).map { "06-01 12:00:00.000 I/Demo: " + "x".repeat(500) + " $it" }
        val text = formatShareText(
            BugReportInfo(metadata, logcat = heavy, arTracking = trackingLines(AR_TRACKING_MAX_LINES)),
            "Stuck",
        )
        assertTrue("share text is ${text.length} chars", text.length <= SHARE_TEXT_MAX_CHARS)
        assertTrue(text.contains("AR tracking (last $AR_TRACKING_MAX_LINES lines):"))
    }

    @Test
    fun `share text stays under its budget with a pathological note, which the file keeps whole`() {
        val note = "word ".repeat(20_000)
        val info = BugReportInfo(metadata, logcat = listOf(appLine), arTracking = trackingLines(5))

        val text = formatShareText(info, note)

        assertTrue("share text is ${text.length} chars", text.length <= SHARE_TEXT_MAX_CHARS)
        assertTrue(text.contains("[…]"))
        assertTrue(text.contains("AR tracking (last 5 lines):"))
        assertTrue(formatReportFile(info, note).contains(note.trim()))
    }

    @Test
    fun `share text stays under its budget with an oversized metadata row`() {
        val info = BugReportInfo(linkedMapOf("Labs" to "y".repeat(SHARE_TEXT_MAX_CHARS * 2)), logcat = emptyList())
        assertTrue(formatShareText(info, "").length <= SHARE_TEXT_MAX_CHARS)
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

    // ── AR tracking lines (#4510) ────────────────────────────────────────────

    /** [count] lines as `ARSceneView` logs them, numbered through their `for=` field. */
    private fun trackingLines(count: Int): List<String> = (1..count).map {
        "10-13 16:55:46.108 29383 29383 I SceneView: AR tracking: state=PAUSED reason=NONE for=${it}0s"
    }

    private val appLine = "10-13 16:56:00.000 29383 29716 I native  : VisualInertialState is kNotTracking."

    @Test
    fun `only AR tracking lines are kept from the SceneView tag, the newest sixty`() {
        val read = listOf(
            "10-13 16:55:40.000 29383 29383 E SceneView: rememberSplatCloud: cannot load " +
                "content://com.android.providers.downloads.documents/document/42",
            "10-13 16:55:40.001 29383 29383 E SceneView: java.io.FileNotFoundException: /sdcard/a.spz",
        ) + trackingLines(AR_TRACKING_MAX_LINES + 5)

        val kept = arTrackingLines(read)

        assertEquals(AR_TRACKING_MAX_LINES, kept.size)
        assertTrue(kept.all { AR_TRACKING_LOG_PREFIX in it })
        assertTrue(kept.first().endsWith("for=60s"))   // the five oldest are dropped
        assertTrue(kept.last().endsWith("for=650s"))
    }

    @Test
    fun `a line that only contains the AR tracking prefix is dropped, with the path it names`() {
        val head = "10-13 16:55:40.000 29383 29383"
        val transition = "$head I SceneView: AR tracking: state=PAUSED reason=CAMERA_UNAVAILABLE"
        val repeat = "$head I SceneView: AR tracking: state=PAUSED reason=NONE for=20s"
        val read = listOf(
            // The prefix inside a file name the user picked, at either level.
            "$head E SceneView: rememberSplatCloud: cannot load " +
                "content://com.android.providers.downloads.documents/AR tracking: notes.spz",
            "$head I SceneView: loading /sdcard/Download/AR tracking: state=PAUSED reason=NONE",
            // A real line with something after it.
            "$head I SceneView: AR tracking: state=PAUSED reason=NONE /sdcard/Download/home.spz",
            "$head I SceneView: AR tracking: state=PAUSED reason=NONE for=20s /sdcard/home.spz",
            // Not an enum name, not a number of seconds.
            "$head I SceneView: AR tracking: state=/sdcard/home.spz reason=NONE",
            "$head I SceneView: AR tracking: state=PAUSED reason=NONE for=home.spz",
            // Another tag, another level.
            "$head I Downloads: AR tracking: state=PAUSED reason=NONE",
            "$head E SceneView: AR tracking: state=PAUSED reason=NONE",
            transition,
            repeat,
        )

        assertEquals(listOf(transition, repeat), arTrackingLines(read))
    }

    /**
     * [AR_TRACKING_LOG_PREFIX] is a copy: the library's prefix is `internal` to another module,
     * so nothing but this test holds the two together. It reads the library's constant, then
     * runs the library's own lines through the report's filter — every state and reason ARCore
     * has, the repeated form included.
     */
    @Test
    fun `the report keeps exactly the lines the library writes`() {
        val log = Class.forName("io.github.sceneview.ar.ARTrackingStateLog")
        assertEquals(log.getField("PREFIX").get(null), AR_TRACKING_LOG_PREFIX)

        val transition = log.getMethod(
            "transition",
            TrackingState::class.java,
            TrackingFailureReason::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val repeatNanos = log.getField("REPEAT_NANOS").getLong(null)
        val written = TrackingState.values().flatMap { state ->
            TrackingFailureReason.values().flatMap { reason ->
                val instance = log.getDeclaredConstructor().newInstance()
                // The line of the transition, then the one repeated while it lasts.
                listOf(0L, repeatNanos).mapNotNull { now ->
                    transition.invoke(instance, state, reason, now, 1) as String?
                }
            }
        }.map { "10-13 16:55:46.108 29383 29383 I SceneView: $it" }

        // One line per pair, and a repeat for every pair but the tracking ones.
        val pairs = TrackingState.values().size * TrackingFailureReason.values().size
        assertEquals(2 * pairs - TrackingFailureReason.values().size, written.size)
        assertTrue(written.any { it.endsWith(" for=10s") })
        assertEquals(written, arTrackingLines(written, maxLines = written.size))
    }

    @Test
    fun `report file puts the AR tracking lines ahead of the general log`() {
        val text = formatReportFile(
            BugReportInfo(metadata, logcat = listOf(appLine), arTracking = trackingLines(3)),
            "Stuck on Getting ready",
        )

        val tracking = text.indexOf("AR tracking (last 3 lines):")
        val general = text.indexOf("Recent app log (last 1 lines):")
        assertTrue(tracking > text.indexOf("Android: 15 (API 35)"))
        assertTrue("tracking block at $tracking, general log at $general", tracking in 0 until general)
        assertTrue(text.indexOf("for=30s") < general)
    }

    @Test
    fun `share text carries no more than sixty AR tracking lines, the newest`() {
        val info = BugReportInfo(metadata, logcat = emptyList(), arTracking = trackingLines(75))

        val text = formatShareText(info, "")

        assertTrue(text.contains("AR tracking (last $AR_TRACKING_MAX_LINES lines):"))
        assertFalse(text.contains("for=150s"))
        assertTrue(text.contains("for=160s"))
        assertTrue(text.contains("for=750s"))
    }

    @Test
    fun `a report without AR tracking lines reads as it did`() {
        val info = info()

        assertFalse(formatShareText(info, "note").contains("AR tracking"))
        assertFalse(formatReportFile(info, "note").contains("AR tracking"))
        assertFalse(formatIssueBody(info, "note").contains("AR tracking"))
    }

    @Test
    fun `issue body puts the AR tracking block ahead of the app log`() {
        val body = formatIssueBody(
            BugReportInfo(metadata, logcat = listOf(appLine), arTracking = trackingLines(2)),
            "Stuck on Getting ready",
        )

        val tracking = body.indexOf("<details><summary>AR tracking (last 2 lines)</summary>")
        val general = body.indexOf("<details><summary>App log (last 1 lines)</summary>")
        assertTrue("tracking block at $tracking, app log at $general", tracking in 0 until general)
        assertTrue(tracking > body.indexOf("### Context"))
    }

    @Test
    fun `issue body takes the AR tracking lines out of the log ceiling instead of adding to it`() {
        val info = BugReportInfo(
            metadata,
            logcat = (1..LOGCAT_TAIL_LINES).map { "line $it" },
            arTracking = trackingLines(AR_TRACKING_MAX_LINES),
        )

        val body = formatIssueBody(info, "")

        val general = ISSUE_LOGCAT_MAX_LINES - ISSUE_AR_TRACKING_MAX_LINES
        assertTrue(body.contains("AR tracking (last $ISSUE_AR_TRACKING_MAX_LINES lines)"))
        assertTrue(body.contains("App log (last $general lines)"))
        // The newest of each, and nothing older than the ceiling allows.
        assertTrue(body.contains("for=600s"))
        assertFalse(body.contains("for=480s"))
        assertTrue(body.contains("line ${LOGCAT_TAIL_LINES - general + 1}\n"))
        assertFalse(body.contains("line ${LOGCAT_TAIL_LINES - general}\n"))
    }

    @Test
    fun `issue url keeps the AR tracking lines, compacted, under the length cap`() {
        val general = (1..ISSUE_LOGCAT_MAX_LINES).map {
            "10-13 16:56:00.000 29383 29716 I native  : " + "x".repeat(120)
        }
        val info = BugReportInfo(metadata, logcat = general, arTracking = trackingLines(40))

        val url = buildGitHubIssueUrl(info, "Stuck on Getting ready")

        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.contains("AR tracking (last $ISSUE_AR_TRACKING_MAX_LINES lines)"))
        assertTrue(body.contains("16:55:46.108 I SceneView: AR tracking: state=PAUSED reason=NONE for=400s"))
        assertFalse(body.contains("29383"))
        val packed = Regex("""App log \(last (\d+) lines\)""").find(body)!!.groupValues[1].toInt()
        assertTrue("packed only $packed general lines", packed >= 10)
    }

    @Test
    fun `issue url gives up the AR tracking lines before a character of the note`() {
        val bare = BugReportInfo(metadata, logcat = emptyList())
        // As long a note as the URL holds with no log at all: the tracking lines no longer fit.
        val room = ISSUE_URL_MAX_LENGTH - buildGitHubIssueUrl(bare, "Stuck\n").length - 10
        val note = "Stuck\n" + "y".repeat(room)
        assertTrue("the note has to be longer than the truncation it used to get", room > 1000)
        val info = BugReportInfo(metadata, logcat = listOf(appLine), arTracking = trackingLines(12))

        val url = buildGitHubIssueUrl(info, note)

        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue("the note was cut", body.startsWith(note))
        assertFalse(body.contains("<details>"))
    }

    @Test
    fun `issue url drops every log block for a pathological note`() {
        val info = BugReportInfo(metadata, logcat = listOf(appLine), arTracking = trackingLines(12))

        val url = buildGitHubIssueUrl(info, "y".repeat(50_000))

        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
        assertFalse(URLDecoder.decode(url.substringAfter("&body="), "UTF-8").contains("<details>"))
    }
}
