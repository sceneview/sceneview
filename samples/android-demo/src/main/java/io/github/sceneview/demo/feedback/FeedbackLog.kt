package io.github.sceneview.demo.feedback

private val LOG_TIME_PREFIX = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}\s+""")
private val FRAME_FENCE_NOISE = Regex(
    """^[VDIWEFAS]\s+FrameEvents\s*: updateAcquireFence: Did not find frame\.$""",
)

/** Compare priority, tag and message, ignoring logcat's date, time and process/thread columns. */
private fun feedbackLogKey(line: String): String = compactLogLine(line).replaceFirst(LOG_TIME_PREFIX, "")

/**
 * Fold consecutive equal messages before applying report budgets. Keep the first line and the
 * last occurrence's timestamp so a repeating warning still has a measurable time span (#3390).
 * Only the exact per-frame fence bookkeeping seen in #4995 is discarded; other errors survive.
 */
internal fun collapseFeedbackLog(lines: List<String>): List<String> = buildList {
    var first = ""
    var last = ""
    var key: String? = null
    var count = 0

    fun appendRun() {
        if (count == 0) return
        val lastTime = LOG_TIME_PREFIX.find(compactLogLine(last))?.value?.trim()
        add(buildString {
            append(first)
            if (count > 1) {
                append(" ×$count")
                if (lastTime != null) append(" (last at $lastTime)")
            }
        })
    }

    for (line in lines) {
        val nextKey = feedbackLogKey(line)
        if (FRAME_FENCE_NOISE.matches(nextKey)) continue
        if (nextKey != key) {
            appendRun()
            first = line
            key = nextKey
            count = 0
        }
        last = line
        count++
    }
    appendRun()
}
