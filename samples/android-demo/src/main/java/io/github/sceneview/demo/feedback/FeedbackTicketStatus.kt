package io.github.sceneview.demo.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Live Open/Closed state of a tracked feedback ticket. */
enum class TicketState { OPEN, CLOSED, UNKNOWN }

/**
 * Reads the live Open/Closed state of a feedback ticket straight from GitHub's
 * public REST API — the issue is the source of truth, never re-implemented.
 *
 * Results are cached in memory for [CACHE_TTL_MS] so reopening the "My feedback"
 * screen does not burn the unauthenticated 60 req/h GitHub limit. [TicketState.UNKNOWN]
 * results (offline, rate-limited) are deliberately not cached, so the next call
 * — a later screen open, or a row scrolled back into view — retries.
 */
object FeedbackTicketStatus {
    private const val REPO = "sceneview/sceneview"
    private val CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5)

    private data class Cached(val state: TicketState, val at: Long)
    private val cache = mutableMapOf<Int, Cached>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Fetch (or return cached) the state of issue [issueNumber]; never throws. */
    suspend fun fetch(issueNumber: Int): TicketState = withContext(Dispatchers.IO) {
        synchronized(cache) {
            cache[issueNumber]?.let {
                if (System.currentTimeMillis() - it.at < CACHE_TTL_MS) {
                    return@withContext it.state
                }
            }
        }

        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/issues/$issueNumber")
            .header("Accept", "application/vnd.github+json")
            // GitHub rejects API requests that carry no User-Agent.
            .header("User-Agent", "SceneView-Demo")
            .build()

        val state = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TicketState.UNKNOWN
                } else {
                    when (JSONObject(response.body.string()).optString("state")) {
                        "open" -> TicketState.OPEN
                        "closed" -> TicketState.CLOSED
                        else -> TicketState.UNKNOWN
                    }
                }
            }
        } catch (e: Exception) {
            TicketState.UNKNOWN
        }

        if (state != TicketState.UNKNOWN) {
            synchronized(cache) {
                cache[issueNumber] = Cached(state, System.currentTimeMillis())
            }
        }
        state
    }
}
