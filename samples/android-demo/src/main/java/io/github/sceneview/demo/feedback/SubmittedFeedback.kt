package io.github.sceneview.demo.feedback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A feedback submission the user made, indexed locally so the "My feedback"
 * screen can list it and deep-link back into the real GitHub issue.
 *
 * The GitHub issue is the source of truth — this record only keeps enough to
 * show a row and open the issue; the live Open/Closed status is read straight
 * from GitHub by [FeedbackTicketStatus].
 */
data class SubmittedFeedback(
    val feedbackId: String,
    val issueNumber: Int,
    val issueUrl: String,
    val category: FeedbackCategory,
    /** The note the user typed — may be blank when only a recording was sent. */
    val title: String,
    val createdAt: Long,
)

/**
 * Local index of submitted feedback, backed by [android.content.SharedPreferences]
 * (a small JSON array). Newest first. Capped at [MAX_ENTRIES] so a heavy tester
 * never grows the prefs unbounded.
 */
object SubmittedFeedbackStore {
    private const val PREFS = "sceneview_feedback"
    private const val KEY_TICKETS = "submitted_tickets"
    private const val MAX_ENTRIES = 50

    /** Every submitted ticket, newest first. */
    fun all(context: Context): List<SubmittedFeedback> {
        val raw = prefs(context).getString(KEY_TICKETS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    /** Record a freshly submitted ticket; a re-send of the same issue replaces it. */
    fun add(context: Context, entry: SubmittedFeedback) {
        val kept = all(context).filter { it.issueNumber != entry.issueNumber }
        val updated = (listOf(entry) + kept).take(MAX_ENTRIES)
        val arr = JSONArray()
        updated.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY_TICKETS, arr.toString()).apply()
    }

    private fun toJson(e: SubmittedFeedback): JSONObject = JSONObject().apply {
        put("feedbackId", e.feedbackId)
        put("issueNumber", e.issueNumber)
        put("issueUrl", e.issueUrl)
        put("category", e.category.name)
        put("title", e.title)
        put("createdAt", e.createdAt)
    }

    private fun fromJson(o: JSONObject?): SubmittedFeedback? {
        if (o == null) return null
        val issueNumber = o.optInt("issueNumber", 0)
        val issueUrl = o.optString("issueUrl")
        if (issueNumber <= 0 || issueUrl.isBlank()) return null
        return SubmittedFeedback(
            feedbackId = o.optString("feedbackId"),
            issueNumber = issueNumber,
            issueUrl = issueUrl,
            category = runCatching { FeedbackCategory.valueOf(o.optString("category")) }
                .getOrDefault(FeedbackCategory.BUG),
            title = o.optString("title"),
            createdAt = o.optLong("createdAt", 0L),
        )
    }

    // applicationContext — prefs access must never pin an Activity context.
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
