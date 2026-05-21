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
 * The unauthenticated GitHub API budget is only 60 requests/hour, so this
 * deliberately paces and caches its calls:
 *
 *  - A successful OPEN / CLOSED result is cached for [CACHE_TTL_MS].
 *  - When GitHub reports the rate limit is exhausted (a 403/429 with
 *    `X-RateLimit-Remaining: 0`), a "rate-limited until reset" sentinel is
 *    cached so every other tracked row stops hammering the dead budget until
 *    `X-RateLimit-Reset` passes — without it, 50 rate-limited rows would retry
 *    forever.
 *  - A 200 whose body does not parse is cached as a short-lived negative, so a
 *    permanently-broken response is not retried in a tight loop.
 */
object FeedbackTicketStatus {
    private const val REPO = "sceneview/sceneview"
    private val CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5)

    /** How long an unparseable-success negative result stays cached. */
    private val NEGATIVE_TTL_MS = TimeUnit.MINUTES.toMillis(10)

    /** Fallback rate-limit cooldown when GitHub sends no usable reset header. */
    private val RATE_LIMIT_FALLBACK_MS = TimeUnit.MINUTES.toMillis(10)

    private data class Cached(val state: TicketState, val expiresAt: Long)

    private val cache = mutableMapOf<Int, Cached>()

    /**
     * Process-wide "the GitHub budget is exhausted until this epoch-ms"
     * sentinel. While set and in the future, [fetch] short-circuits to
     * [TicketState.UNKNOWN] for every issue without making a request.
     */
    @Volatile
    private var rateLimitedUntil = 0L

    private val defaultClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** OkHttp client used for the GitHub call. Overridable in tests. */
    internal var client: OkHttpClient = defaultClient

    /** Base of the GitHub issues API. Overridable in tests (MockWebServer). */
    internal var apiBaseUrl: String = "https://api.github.com"

    /** Test-only — clears every cache entry and the rate-limit sentinel and
     *  restores the default client / base URL. */
    internal fun resetForTest() {
        synchronized(cache) { cache.clear() }
        rateLimitedUntil = 0L
        client = defaultClient
        apiBaseUrl = "https://api.github.com"
    }

    /** Fetch (or return cached) the state of issue [issueNumber]; never throws. */
    suspend fun fetch(issueNumber: Int): TicketState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        synchronized(cache) {
            cache[issueNumber]?.let {
                if (now < it.expiresAt) return@withContext it.state
            }
        }

        // The whole budget is known-exhausted — don't spend a request just to
        // get another 403. Every tracked row sees this until the reset passes.
        if (now < rateLimitedUntil) return@withContext TicketState.UNKNOWN

        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/repos/$REPO/issues/$issueNumber")
            .header("Accept", "application/vnd.github+json")
            // GitHub rejects API requests that carry no User-Agent.
            .header("User-Agent", "SceneView-Demo")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
                if ((response.code == 403 || response.code == 429) && remaining == 0) {
                    // Budget exhausted — park every row until the reset epoch.
                    val resetMs = response.header("X-RateLimit-Reset")
                        ?.toLongOrNull()
                        ?.let { it * 1000L }
                    rateLimitedUntil = resetMs?.takeIf { it > now }
                        ?: (now + RATE_LIMIT_FALLBACK_MS)
                    return@use TicketState.UNKNOWN
                }
                if (!response.isSuccessful) {
                    // A transient non-success (404, network blip) — not cached
                    // so a later open retries.
                    return@use TicketState.UNKNOWN
                }
                val parsed = runCatching {
                    JSONObject(response.body.string()).optString("state")
                }.getOrNull()
                when (parsed) {
                    "open" -> cacheState(issueNumber, TicketState.OPEN, now + CACHE_TTL_MS)
                    "closed" -> cacheState(issueNumber, TicketState.CLOSED, now + CACHE_TTL_MS)
                    else -> {
                        // 200 but unparseable / unexpected body — cache a
                        // short-lived UNKNOWN so it is not retried in a loop.
                        cacheState(issueNumber, TicketState.UNKNOWN, now + NEGATIVE_TTL_MS)
                    }
                }
            }
        } catch (e: Exception) {
            // Offline / timeout — not cached, the next open retries.
            TicketState.UNKNOWN
        }
    }

    private fun cacheState(issueNumber: Int, state: TicketState, expiresAt: Long): TicketState {
        synchronized(cache) { cache[issueNumber] = Cached(state, expiresAt) }
        return state
    }
}
