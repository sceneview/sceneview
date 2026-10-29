package io.github.sceneview.demo.feedback

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.common.MotionSensorReport
import io.github.sceneview.demo.labs.Labs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/**
 * Lightweight, permission-free bug-report collection (#2188 successor).
 *
 * This replaces the former MediaProjection screen-recording feedback system.
 * The report is assembled entirely from data the app can read about itself —
 * no runtime permission, no foreground service, no Play Console foreground-
 * service declaration:
 *
 * - a [BugReportInfo.metadata] snapshot (the screen the report was filed from,
 *   app version, device, OS, ABI, display),
 * - the app's own logcat tail ([collectAppLogcat] — readable without any
 *   permission for the app's own pid), and the AR tracking lines found further
 *   back in it ([collectArTrackingLog]), both with paths, URIs, e-mail addresses
 *   and user-chosen names masked ([LogRedaction], #4526),
 * - an optional screenshot captured via `PixelCopy` (see `CapturedScreenshot.kt`).
 *
 * Nothing ever leaves the device by itself: the user explicitly shares the
 * report through the system share sheet, or opens a pre-filled GitHub issue.
 */

/** Device / app / logcat snapshot backing one bug report. */
data class BugReportInfo(
    /** Ordered metadata rows — insertion order is the display order. */
    val metadata: Map<String, String>,
    /** Tail of the app's own logcat, oldest line first. Empty if unreadable. */
    val logcat: List<String>,
    /**
     * The `AR tracking:` lines of the app's own logcat, oldest first — see
     * [collectArTrackingLog]. Empty outside AR, or if unreadable.
     */
    val arTracking: List<String> = emptyList(),
)

/**
 * Lines requested from `logcat -t` for the app's own pid.
 *
 * Deliberately deep: diagnosing a *repeating* warning means seeing it often
 * enough to measure its period, not merely learning that it happened once
 * (#3390 — #3339 was only pinned down by matching a warning's cadence against
 * a throttle interval).
 */
internal const val LOGCAT_TAIL_LINES = 1200

/**
 * Max logcat lines carried by the report file the share path attaches. The
 * file has no URL or Binder budget, so the whole captured tail rides along.
 */
internal const val SHARE_LOGCAT_MAX_LINES = LOGCAT_TAIL_LINES

/**
 * Hard cap, in characters, for the share intent's `EXTRA_TEXT` (#4528).
 *
 * An intent crosses Binder, whose transaction buffer is about 1 MB shared by the
 * whole process: the 1200-line log used to ride in this extra (~600 KB) and the
 * chooser was refused with `TransactionTooLargeException` on the first tap. The
 * log now travels as an attached file and the text keeps the summary; this cap
 * holds whatever the note or a future metadata row weighs.
 */
internal const val SHARE_TEXT_MAX_CHARS = 16_000

/**
 * Most characters of the note carried by the share text. The whole note is in
 * the attached report file.
 */
internal const val SHARE_TEXT_NOTE_MAX_CHARS = 4_000

/** Name of the report file attached by the share path (#4528). */
internal const val REPORT_FILE_NAME = "sceneview-bug-report.txt"

/**
 * Ceiling for the log block in a GitHub-issue body. The real count is decided
 * by [buildGitHubIssueUrl], which packs as many lines as [ISSUE_URL_MAX_LENGTH]
 * allows.
 */
internal const val ISSUE_LOGCAT_MAX_LINES = 400

/**
 * Message prefix of the lines `ARSceneView` logs under the `SceneView` tag when
 * the camera's tracking state changes, and every ten seconds while it is not
 * tracking (#4510): `AR tracking: state=PAUSED reason=NONE for=20s`.
 *
 * A copy of the library's own prefix, which is not part of its public API;
 * `BugReportFormatTest` holds the two together.
 */
internal const val AR_TRACKING_LOG_PREFIX = "AR tracking:"

/** Max AR tracking lines kept by [collectArTrackingLog] and carried by the share text. */
internal const val AR_TRACKING_MAX_LINES = 60

/**
 * How far back [collectArTrackingLog] looks: this many of the app's most recent
 * log lines, whatever their tag. Five minutes of an AR session at ARCore's
 * sixty lines a second — more than a default 256 KiB log buffer holds, so on
 * most phones the bound is the buffer itself.
 */
internal const val AR_TRACKING_SCAN_LINES = 20_000

/**
 * Max AR tracking lines in a GitHub-issue body. Fewer than the share text
 * carries: every line is paid for out of [ISSUE_URL_MAX_LENGTH], and a dozen
 * lines already cover two minutes of a camera that does not track.
 */
internal const val ISSUE_AR_TRACKING_MAX_LINES = 12

/**
 * Hard cap for the pre-filled GitHub issue URL. Browsers and the GitHub app
 * truncate very long URLs; ~8k is a safe practical bound, we stay under it.
 */
internal const val ISSUE_URL_MAX_LENGTH = 7000

/** Where new bug-report issues are opened. */
internal const val GITHUB_NEW_ISSUE_URL = "https://github.com/sceneview/sceneview/issues/new"

/**
 * Where the user was when they opened the report — captured by the sheet host,
 * the only place with a handle on the `NavController` (#3390).
 *
 * @param demoId id of the demo on top, or `null` on the tab host.
 * @param rootScreen label of the visible root screen (see [CurrentRootScreen]),
 *   or `null` when a demo covers the tab host.
 * @param route raw navigation route, used as a last-resort label.
 */
data class ReportScreen(
    val demoId: String? = null,
    val rootScreen: String? = null,
    val route: String? = null,
)

/**
 * Human-readable name of the screen a report was filed from — the first thing
 * a maintainer needs, and the thing the reports were missing (#3390).
 */
fun formatScreenLabel(screen: ReportScreen): String = when {
    !screen.demoId.isNullOrBlank() -> "Demo · ${screen.demoId}"
    !screen.rootScreen.isNullOrBlank() -> screen.rootScreen
    !screen.route.isNullOrBlank() -> screen.route.substringBefore('?')
    else -> "unknown"
}

/**
 * Snapshot of device / app context attached to a bug report — what a
 * maintainer needs to reproduce: where the user was, app build, OS, device,
 * ABI, display.
 *
 * `Screen` names the navigation destination; the display resolution lives
 * under `Display` (it used to own the `Screen` key, which read as the far more
 * useful thing it was not).
 */
fun captureBugReportMetadata(context: Context, screen: ReportScreen): Map<String, String> {
    val dm = context.resources.displayMetrics
    return buildMap {
        put("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        put("Screen", formatScreenLabel(screen))
        // Kept as its own row on purpose: the issue title falls back to it.
        if (!screen.demoId.isNullOrBlank()) put("Demo", screen.demoId)
        put("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        put("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        put("Display", "${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi")
        put("Locale", Locale.getDefault().toLanguageTag())
        // Which early-access features the tester could see (SceneView Labs).
        put("Labs", Labs.reportLine())
        // #4522: only when an AR session stalled and the motion sensors were listened to.
        MotionSensorReport.line()?.let { put(MotionSensorReport.KEY, it) }
    }
}

/**
 * Read the tail of this app's own logcat. An app can always read its own
 * log lines (`--pid=<own pid>`) — no `READ_LOGS` permission involved.
 *
 * Returns an empty list if the `logcat` binary is unavailable or errors
 * (e.g. under Robolectric) — the report is then metadata + note only.
 */
fun collectAppLogcat(maxLines: Int = LOGCAT_TAIL_LINES): List<String> =
    readOwnLogcat(listOf("-t", maxLines.toString())).takeLast(maxLines)

/**
 * Read the AR tracking lines of this app's own logcat — the whole buffer, not
 * its tail (#4510).
 *
 * [collectAppLogcat] cannot be relied on for them: ARCore writes some sixty
 * lines a second from the app's process, so its [LOGCAT_TAIL_LINES] are the
 * last twenty seconds of an AR session, and the line that says where tracking
 * stood may be older than that.
 *
 * Only whole AR tracking lines are kept (see [arTrackingLines]), not the whole
 * `SceneView` tag: other lines under that tag can name a file the user picked,
 * and this block is the one that always makes it into a public issue.
 *
 * The read is bounded to the app's last [AR_TRACKING_SCAN_LINES] log lines, of
 * which `logcat` hands back the `SceneView` ones only. It still spawns a
 * process and waits for it: call it off the main thread.
 *
 * Returns an empty list when `logcat` is unavailable, like [collectAppLogcat].
 */
fun collectArTrackingLog(maxLines: Int = AR_TRACKING_MAX_LINES): List<String> = arTrackingLines(
    readOwnLogcat(listOf("-t", AR_TRACKING_SCAN_LINES.toString(), "-s", "SceneView:I")),
    maxLines,
)

/**
 * The newest [maxLines] of [lines] that are AR tracking lines — the whole line
 * has to be one, tag included (see [AR_TRACKING_LINE]). A line that merely
 * contains the prefix is dropped.
 */
internal fun arTrackingLines(
    lines: List<String>,
    maxLines: Int = AR_TRACKING_MAX_LINES,
): List<String> = lines.filter(AR_TRACKING_LINE::matches).takeLast(maxLines)

/**
 * A `threadtime` line of the `SceneView` tag whose whole message is an AR
 * tracking line: `10-13 17:12:08.316 30621 30621 I SceneView: AR tracking:
 * state=PAUSED reason=NONE for=20s`.
 *
 * Anchored at both ends on purpose. The prefix alone is not enough: a line of
 * the same tag such as `cannot load content://…/AR tracking: notes.spz` carries
 * it too, with a path the user picked, and this block goes into a public issue.
 * Past the prefix, only enum names and a number of seconds can match.
 */
internal val AR_TRACKING_LINE = Regex(
    """^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+I\s+SceneView\s*: """ +
        Regex.escape(AR_TRACKING_LOG_PREFIX) +
        """ state=[A-Z_]+ reason=[A-Z_]+( for=\d+s)?$"""
)

/** `logcat -d` for this app's own pid with [arguments], or an empty list if it fails. */
private fun readOwnLogcat(arguments: List<String>): List<String> = runCatching {
    // `-v threadtime` pins the line shape so [compactLogLine] can strip the
    // redundant columns when the URL budget is tight.
    val command = listOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}")
    val process = Runtime.getRuntime().exec((command + arguments).toTypedArray())
    process.inputStream.bufferedReader().use { reader ->
        reader.readLines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }
    }.also { process.destroy() }
}.getOrDefault(emptyList())

/**
 * Assemble the full report snapshot. Runs `logcat` and waits for it (twice when
 * the AR tracking history is empty): call it off the main thread.
 */
fun captureBugReportInfo(context: Context, screen: ReportScreen): BugReportInfo {
    // Masked here, once, before anything can read them (#4526): the sheet's preview,
    // the attached file, the share text and the public issue all see the same lines.
    val userNames = UserChosenNames.snapshot()
    return BugReportInfo(
        metadata = captureBugReportMetadata(context, screen),
        logcat = LogRedaction.redactLines(collectAppLogcat(), userNames),
        // The in-memory history first (#4529): the log buffer drops these lines within a
        // minute of an AR session, the history keeps them for the whole process.
        arTracking = LogRedaction.redactLines(
            reportArTracking(ArTrackingHistory.snapshot(), ::collectArTrackingLog),
            userNames,
        ),
    )
}

/**
 * The AR tracking lines a report carries: [history] when it holds any, else what
 * [fromLogcat] still finds in the log buffer. The history is empty only before the
 * first AR frame, or when its reader could not start (#4529).
 */
internal fun reportArTracking(
    history: List<String>,
    fromLogcat: () -> List<String>,
): List<String> = history.takeLast(AR_TRACKING_MAX_LINES).ifEmpty { fromLogcat() }

/** Issue / share subject line — includes the demo id when there is one. */
fun formatReportTitle(info: BugReportInfo, note: String): String {
    val summary = note.trim().lineSequence().firstOrNull()?.trim().orEmpty()
    val demo = info.metadata["Demo"]
    return when {
        summary.isNotEmpty() -> "[Demo app] ${summary.take(80)}"
        demo != null -> "[Demo app] Bug report — $demo"
        else -> "[Demo app] Bug report"
    }
}

/**
 * The whole plain-text report, written to [REPORT_FILE_NAME] and attached by
 * the share path: note, metadata, the AR tracking lines and the full log tail.
 */
fun formatReportFile(info: BugReportInfo, note: String): String = buildString {
    appendLine("SceneView demo — bug report")
    appendLine()
    val text = note.trim()
    if (text.isNotEmpty()) {
        appendLine(text)
        appendLine()
    }
    info.metadata.forEach { (key, value) -> appendLine("$key: $value") }
    // Ahead of the general log on purpose: it is short, and it answers the
    // first question about an AR report.
    val arTracking = info.arTracking.takeLast(AR_TRACKING_MAX_LINES)
    if (arTracking.isNotEmpty()) {
        appendLine()
        appendLine("AR tracking (last ${arTracking.size} lines):")
        arTracking.forEach { appendLine(it) }
    }
    val logcat = info.logcat.takeLast(SHARE_LOGCAT_MAX_LINES)
    if (logcat.isNotEmpty()) {
        appendLine()
        appendLine("Recent app log (last ${logcat.size} lines):")
        logcat.forEach { appendLine(it) }
    }
}.trimEnd()

/**
 * The share-sheet `EXTRA_TEXT` (#4528): the summary a reader needs before
 * opening anything — note, metadata, the AR tracking block — and a pointer to
 * the attached [REPORT_FILE_NAME] for the log. Never the log itself, and never
 * longer than [SHARE_TEXT_MAX_CHARS], whatever the log or the note weighs.
 *
 * @param logAttached whether the report file rides along. When it could not be
 *   written, the text says the log is missing instead of pointing at nothing.
 */
fun formatShareText(
    info: BugReportInfo,
    note: String,
    logAttached: Boolean = true,
): String = buildString {
    appendLine("SceneView demo — bug report")
    appendLine()
    val text = note.trim()
    if (text.isNotEmpty()) {
        if (text.length > SHARE_TEXT_NOTE_MAX_CHARS) {
            appendLine(text.take(SHARE_TEXT_NOTE_MAX_CHARS).trimEnd() + " […]")
        } else {
            appendLine(text)
        }
        appendLine()
    }
    info.metadata.forEach { (key, value) -> appendLine("$key: $value") }
    val arTracking = info.arTracking.takeLast(AR_TRACKING_MAX_LINES)
    if (arTracking.isNotEmpty()) {
        appendLine()
        appendLine("AR tracking (last ${arTracking.size} lines):")
        arTracking.forEach { appendLine(it) }
    }
    val logLines = info.logcat.takeLast(SHARE_LOGCAT_MAX_LINES).size
    if (logLines > 0) {
        appendLine()
        if (logAttached) {
            appendLine("Full report with the app log ($logLines lines) attached: $REPORT_FILE_NAME")
        } else {
            appendLine("The app log ($logLines lines) could not be attached.")
        }
    }
}.trimEnd().let { whole ->
    if (whole.length <= SHARE_TEXT_MAX_CHARS) whole else whole.take(SHARE_TEXT_MAX_CHARS - 4) + "\n[…]"
}

/**
 * Markdown body for a pre-filled GitHub issue: the note, a metadata table,
 * the AR tracking lines and the logcat tail, each folded in a `<details>` block.
 *
 * The screenshot cannot ride in a URL — the UI says so and keeps it on the
 * share path instead.
 *
 * @param maxLogcatLines ceiling for the log lines of the body, both blocks
 *   together: the AR tracking lines are taken out of it first, the general
 *   tail gets what is left.
 */
fun formatIssueBody(
    info: BugReportInfo,
    note: String,
    maxLogcatLines: Int = ISSUE_LOGCAT_MAX_LINES,
): String = buildString {
    val text = note.trim()
    appendLine(text.ifEmpty { "_No description provided._" })
    appendLine()
    appendLine("### Context")
    appendLine()
    appendLine("| | |")
    appendLine("|---|---|")
    info.metadata.forEach { (key, value) ->
        appendLine("| $key | ${value.replace("|", "\\|")} |")
    }
    val arTracking = info.arTracking.takeLast(ISSUE_AR_TRACKING_MAX_LINES)
    val logcat = info.logcat.takeLast((maxLogcatLines - arTracking.size).coerceAtLeast(0))
    appendLogDetails("AR tracking (last ${arTracking.size} lines)", arTracking)
    appendLogDetails("App log (last ${logcat.size} lines)", logcat)
    appendLine()
    appendLine("_Reported from the in-app bug reporter._")
}.trimEnd()

/** One folded, fenced block of log [lines] under [summary]; nothing when there are none. */
private fun StringBuilder.appendLogDetails(summary: String, lines: List<String>) {
    if (lines.isEmpty()) return
    appendLine()
    appendLine("<details><summary>$summary</summary>")
    appendLine()
    appendLine("```")
    // A log line containing a ``` fence would terminate the block early
    // and spill raw log into the issue markdown — de-fang it.
    lines.forEach { appendLine(it.replace("```", "'''")) }
    appendLine("```")
    appendLine()
    appendLine("</details>")
}

/**
 * Drop the columns a maintainer never reads back from a `threadtime` line —
 * the date and the pid/tid pair — while keeping the millisecond timestamp,
 * which is load-bearing: the period of a repeating warning is measured from
 * it (#3390). Roughly doubles the number of lines that fit in a URL.
 *
 * `06-01 12:00:00.000  6543  6560 W Filament: msg` → `12:00:00.000 W Filament: msg`
 *
 * Lines in any other shape are returned untouched (minus a leading date).
 */
internal fun compactLogLine(line: String): String = line
    .replaceFirst(THREADTIME_PREFIX, "$1 ")
    .replaceFirst(LEADING_DATE, "")

private val THREADTIME_PREFIX =
    Regex("""^\d{2}-\d{2} (\d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+""")

private val LEADING_DATE = Regex("""^\d{2}-\d{2} (?=\d{2}:\d{2}:\d{2})""")

/**
 * Pre-filled `issues/new` URL, carrying as much log as the URL can hold.
 *
 * GitHub (and browsers) silently truncate over-long URLs, which would corrupt
 * the markdown mid-tag, so [ISSUE_URL_MAX_LENGTH] is a hard budget. Body length
 * grows monotonically with the line count, so the largest fitting tail is
 * binary-searched: the previous coarse 60/30/10/0 ladder snapped an ordinary
 * report down to 30 lines, far too short to read a repeating warning (#3390).
 */
fun buildGitHubIssueUrl(info: BugReportInfo, note: String): String {
    val title = formatReportTitle(info, note)
    val compact = info.copy(
        logcat = info.logcat.map(::compactLogLine),
        arTracking = info.arTracking.map(::compactLogLine),
    )

    fun url(body: String): String = GITHUB_NEW_ISSUE_URL +
        "?title=" + urlEncode(title) +
        "&labels=" + urlEncode("bug") +
        "&body=" + urlEncode(body)

    fun urlWith(lines: Int): String = url(formatIssueBody(compact, note, lines))

    var best = urlWith(lines = 0)
    if (best.length > ISSUE_URL_MAX_LENGTH) {
        // What the user wrote comes before any diagnostic line: give up the AR
        // tracking block before a single character of the note.
        val noLog = compact.copy(logcat = emptyList(), arTracking = emptyList())
        val wholeNote = url(formatIssueBody(noLog, note, 0))
        if (wholeNote.length <= ISSUE_URL_MAX_LENGTH) return wholeNote
        // Metadata-only body still too long (pathological note) — truncate the note.
        return url(formatIssueBody(noLog, note.take(1000), 0))
    }
    // The ceiling counts both log blocks, so the search starts above the AR
    // tracking lines the zero-line body already carries.
    val arTracking = minOf(ISSUE_AR_TRACKING_MAX_LINES, compact.arTracking.size)
    var low = arTracking + 1
    var high = minOf(ISSUE_LOGCAT_MAX_LINES, arTracking + compact.logcat.size)
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = urlWith(mid)
        if (candidate.length <= ISSUE_URL_MAX_LENGTH) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

/** Query-string encoding (`URLEncoder` is form-encoding: spaces become `+`,
 *  which GitHub's issue form decodes correctly). Kept JVM-pure so the
 *  formatting layer stays unit-testable without Robolectric. */
private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * Directory holding report screenshots, exposed via the app's `FileProvider`
 * (`res/xml/file_paths.xml` → `<cache-path name="feedback" path="feedback/">`).
 */
fun feedbackCacheDir(context: Context): File =
    File(context.cacheDir, "feedback").apply { mkdirs() }

/**
 * Write the whole report ([formatReportFile]) to [REPORT_FILE_NAME] in
 * [feedbackCacheDir], for the share path to attach (#4528). Overwritten on every
 * share; swept with the screenshots on the next app start.
 *
 * @return the file, or `null` when it could not be written — the share then goes
 *   out as text only, and says so.
 */
fun writeReportFile(context: Context, info: BugReportInfo, note: String): File? = runCatching {
    File(feedbackCacheDir(context), REPORT_FILE_NAME).apply {
        writeText(formatReportFile(info, note))
    }
}.getOrNull()

/**
 * Delete report screenshots left behind by a previous run — call on app start
 * so a crash or process death never strands a capture on disk. (Named after
 * its recording-era predecessor; it now only ever sees PNG screenshots.)
 */
fun sweepStaleFeedbackMedia(context: Context) {
    runCatching {
        File(context.cacheDir, "feedback").listFiles()?.forEach { it.delete() }
        // Recording-era leftovers from upgrades (feedback-*.mp4 / .m4a in the
        // cache root) — sweep them once too.
        context.cacheDir
            .listFiles { file -> file.name.startsWith("feedback-") }
            ?.forEach { it.delete() }
    }
}

/**
 * A ready-to-share bug report: the metadata + logcat snapshot and the
 * (optional) screenshot, both captured the moment the user asked to report —
 * BEFORE this sheet is composed, so the sheet itself is never in the shot.
 */
data class PendingBugReport(
    val info: BugReportInfo,
    val screenshot: CapturedScreenshot?,
)

/**
 * Process-wide one-shot signal asking the host to open the bug-report sheet.
 *
 * The sheet is owned by `SceneViewDemoApp` (it wraps the whole `NavHost`),
 * but a report entry point also lives in `DemoScaffold` — the shared scaffold
 * for every demo screen (#1930 requires the button on the root tabs AND
 * inside every demo). A demo's top-app-bar action has no direct handle on the
 * host's sheet state, so it raises this flag; `SceneViewDemoApp` observes it,
 * opens the sheet, and clears it.
 */
object FeedbackOpenRequest {
    private val _requested = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    /** Ask the host to open the bug-report sheet. */
    fun request() {
        _requested.value = true
    }

    /** Clear the flag once the host has handled it. */
    fun consume() {
        _requested.value = false
    }
}

/**
 * Which root screen is visible, published for the bug reporter (#3390).
 *
 * The tab selection is local state inside `RootScreen` — the sheet host sees
 * the `NavController`, whose single `list` destination covers all the tabs, so
 * without this a report filed from the tab host could not name the screen.
 * Same single-publisher shape as [FeedbackOpenRequest]: `RootScreen` writes it,
 * the reporter reads it, and it is cleared when the tab host leaves composition
 * (a demo on top names itself instead).
 */
object CurrentRootScreen {
    /**
     * English label of the visible root screen, verbatim as it appears in the
     * report ("Showcase tab", "Explore gallery", …), or `null` when the tab
     * host is gone.
     */
    @Volatile
    var label: String? = null
}
