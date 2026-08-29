package io.github.sceneview.demo.feedback

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.sceneview.demo.BuildConfig
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
 *   permission for the app's own pid),
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
 * Max logcat lines carried by the share text. The share path has no URL
 * budget, so the whole captured tail rides along.
 */
internal const val SHARE_LOGCAT_MAX_LINES = LOGCAT_TAIL_LINES

/**
 * Ceiling for the log block in a GitHub-issue body. The real count is decided
 * by [buildGitHubIssueUrl], which packs as many lines as [ISSUE_URL_MAX_LENGTH]
 * allows.
 */
internal const val ISSUE_LOGCAT_MAX_LINES = 400

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
    }
}

/**
 * Read the tail of this app's own logcat. An app can always read its own
 * log lines (`--pid=<own pid>`) — no `READ_LOGS` permission involved.
 *
 * Returns an empty list if the `logcat` binary is unavailable or errors
 * (e.g. under Robolectric) — the report is then metadata + note only.
 */
fun collectAppLogcat(maxLines: Int = LOGCAT_TAIL_LINES): List<String> = runCatching {
    val process = Runtime.getRuntime().exec(
        // `-v threadtime` pins the line shape so [compactLogLine] can strip the
        // redundant columns when the URL budget is tight.
        arrayOf(
            "logcat", "-d", "-v", "threadtime",
            "-t", maxLines.toString(), "--pid=${Process.myPid()}",
        ),
    )
    process.inputStream.bufferedReader().use { reader ->
        reader.readLines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }
            .takeLast(maxLines)
    }.also { process.destroy() }
}.getOrDefault(emptyList())

/** Assemble the full report snapshot. Cheap enough for the main thread, but
 *  callers run it alongside the (async) screenshot capture anyway. */
fun captureBugReportInfo(context: Context, screen: ReportScreen): BugReportInfo =
    BugReportInfo(
        metadata = captureBugReportMetadata(context, screen),
        logcat = collectAppLogcat(),
    )

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
 * Plain-text report used as the share-sheet `EXTRA_TEXT` (the screenshot, when
 * included, rides along as an `EXTRA_STREAM` attachment).
 */
fun formatShareText(info: BugReportInfo, note: String): String = buildString {
    appendLine("SceneView demo — bug report")
    appendLine()
    val text = note.trim()
    if (text.isNotEmpty()) {
        appendLine(text)
        appendLine()
    }
    info.metadata.forEach { (key, value) -> appendLine("$key: $value") }
    val logcat = info.logcat.takeLast(SHARE_LOGCAT_MAX_LINES)
    if (logcat.isNotEmpty()) {
        appendLine()
        appendLine("Recent app log (last ${logcat.size} lines):")
        logcat.forEach { appendLine(it) }
    }
}.trimEnd()

/**
 * Markdown body for a pre-filled GitHub issue: the note, a metadata table,
 * and the logcat tail folded in a `<details>` block.
 *
 * The screenshot cannot ride in a URL — the UI says so and keeps it on the
 * share path instead.
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
    val logcat = info.logcat.takeLast(maxLogcatLines)
    if (logcat.isNotEmpty()) {
        appendLine()
        appendLine("<details><summary>App log (last ${logcat.size} lines)</summary>")
        appendLine()
        appendLine("```")
        // A log line containing a ``` fence would terminate the block early
        // and spill raw log into the issue markdown — de-fang it.
        logcat.forEach { appendLine(it.replace("```", "'''")) }
        appendLine("```")
        appendLine()
        appendLine("</details>")
    }
    appendLine()
    appendLine("_Reported from the in-app bug reporter._")
}.trimEnd()

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
    val compact = info.copy(logcat = info.logcat.map(::compactLogLine))

    fun url(body: String): String = GITHUB_NEW_ISSUE_URL +
        "?title=" + urlEncode(title) +
        "&labels=" + urlEncode("bug") +
        "&body=" + urlEncode(body)

    fun urlWith(lines: Int): String = url(formatIssueBody(compact, note, lines))

    var best = urlWith(lines = 0)
    if (best.length > ISSUE_URL_MAX_LENGTH) {
        // Metadata-only body still too long (pathological note) — truncate the note.
        return url(formatIssueBody(compact.copy(logcat = emptyList()), note.take(1000), 0))
    }
    var low = 1
    var high = minOf(ISSUE_LOGCAT_MAX_LINES, compact.logcat.size)
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
 * for every demo screen (#1930 requires the button "on the 4 tabs AND inside
 * every demo"). A demo's top-app-bar action has no direct handle on the
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
