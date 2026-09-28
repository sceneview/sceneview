package io.github.sceneview.demo.ui.explore

import io.github.sceneview.demo.sketchfab.SketchfabService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/**
 * How long the Explore tab waits for one feed or one search before giving up
 * (#3993).
 *
 * The HTTP clients carry their own ceilings, but they stack: Sketchfab allows a
 * 20 s call plus one transient retry, so a stalled feed could keep the featured
 * stage and the "Trending in 3D" rail spinning for close to a minute, which reads
 * as "broken" long before it ends. Twelve seconds is well past a healthy response
 * on a slow mobile link (a few hundred ms to ~3 s) and short enough that the user
 * gets an error card with a retry while they are still looking at the screen.
 */
internal const val EXPLORE_FEED_TIMEOUT_MS: Long = 12_000L

/** What the feeds effect ended with, and so what the featured stage shows. */
internal enum class FeedsStatus {
    /** A request is in flight: the stage and the rail show a spinner. */
    Loading,

    /** At least one feed returned models. */
    Ready,

    /** Every feed answered, and every feed was empty. */
    Empty,

    /** No feed returned models, and at least one failed or timed out. */
    Failed,
}

/** Outcome of one feed or search request. */
internal sealed interface FeedResult<out T> {
    data class Loaded<T>(val models: List<T>) : FeedResult<T>

    /** The request threw (network down, HTTP error, decode error, rejected key). */
    data object Failed : FeedResult<Nothing>

    /** The request did not answer within the timeout. */
    data object TimedOut : FeedResult<Nothing>
}

/** The models of a [FeedResult], empty when it did not load. */
internal val <T> FeedResult<T>.modelsOrEmpty: List<T>
    get() = (this as? FeedResult.Loaded<T>)?.models.orEmpty()

/**
 * Run [block] under [timeoutMs], mapping every way it can end to a [FeedResult].
 *
 * A timeout becomes [FeedResult.TimedOut] and any other failure
 * [FeedResult.Failed], after handing the throwable to [onFailure] so the caller
 * can react to a rejected Sketchfab key (#2095). A cancellation of the *caller*
 * (source switch, filter toggle, pull-to-refresh, navigating away) is re-thrown
 * so structured concurrency still tears the request down; only our own timeout
 * is swallowed.
 */
internal suspend fun <T> fetchWithTimeout(
    timeoutMs: Long = EXPLORE_FEED_TIMEOUT_MS,
    onFailure: (Throwable) -> Unit = {},
    block: suspend () -> List<T>,
): FeedResult<T> = try {
    FeedResult.Loaded(withTimeout(timeoutMs) { block() })
} catch (timeout: TimeoutCancellationException) {
    FeedResult.TimedOut
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    onFailure(t)
    FeedResult.Failed
}

/**
 * `true` for the failures that mean Sketchfab itself is unavailable to this build:
 * a rejected key (HTTP 401/403) or the CloudFront WAF challenge (HTTP 202 with an
 * empty body). The feeds surface both as the "Sketchfab unavailable" banner rather
 * than three silently empty sections (#2095, #2191).
 */
internal fun Throwable.isSketchfabUnavailable(): Boolean =
    this is SketchfabService.SketchfabError.KeyRejected ||
        this is SketchfabService.SketchfabError.WafChallenge

/** Every feed of a source, loaded, and the [FeedsStatus] they add up to. */
internal data class FeedsLoad<K, T>(
    val feeds: Map<K, List<T>>,
    val status: FeedsStatus,
)

/**
 * Load every feed in [kinds] in parallel, each under its own [timeoutMs].
 *
 * `supervisorScope` so one failing or stalled feed never cancels its siblings:
 * whatever did load still renders (#980, #2645).
 */
internal suspend fun <K, T> loadFeeds(
    kinds: List<K>,
    timeoutMs: Long = EXPLORE_FEED_TIMEOUT_MS,
    onFailure: (Throwable) -> Unit = {},
    fetch: suspend (K) -> List<T>,
): FeedsLoad<K, T> = supervisorScope {
    val results = kinds
        .map { kind -> kind to async { fetchWithTimeout(timeoutMs, onFailure) { fetch(kind) } } }
        .map { (kind, deferred) -> kind to deferred.await() }
    FeedsLoad(
        feeds = results.associate { (kind, result) -> kind to result.modelsOrEmpty },
        status = feedsStatusOf(results.map { it.second }),
    )
}

/**
 * Reduce per-feed results to what the stage shows: models anywhere win; with
 * none, a single failure or timeout means "could not reach the source" (worth a
 * retry), and only a set of clean, empty answers means "nothing here".
 */
internal fun feedsStatusOf(results: List<FeedResult<*>>): FeedsStatus = when {
    results.any { it.modelsOrEmpty.isNotEmpty() } -> FeedsStatus.Ready
    results.any { it !is FeedResult.Loaded } -> FeedsStatus.Failed
    else -> FeedsStatus.Empty
}
