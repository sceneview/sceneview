package io.github.sceneview.demo.ui.explore

import io.github.sceneview.demo.sketchfab.SketchfabService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** #3993: an Explore feed or search that never answers must end, not spin forever. */
class ExploreFeedLoaderTest {

    @Test
    fun `a request that never answers times out at the Explore ceiling`() = runTest {
        val result = fetchWithTimeout<String> { awaitCancellation() }

        assertEquals(FeedResult.TimedOut, result)
        assertEquals(EXPLORE_FEED_TIMEOUT_MS, currentTime)
    }

    @Test
    fun `a request that answers in time is loaded`() = runTest {
        val result = fetchWithTimeout {
            delay(EXPLORE_FEED_TIMEOUT_MS - 1)
            listOf("helmet")
        }

        assertEquals(FeedResult.Loaded(listOf("helmet")), result)
    }

    @Test
    fun `a failing request is reported as failed and hands over its cause`() = runTest {
        var cause: Throwable? = null
        val result = fetchWithTimeout<String>(onFailure = { cause = it }) { throw IOException("offline") }

        assertEquals(FeedResult.Failed, result)
        assertTrue(cause is IOException)
    }

    @Test
    fun `the caller's own cancellation is not swallowed as a timeout`() = runTest {
        val load = async { fetchWithTimeout<String> { awaitCancellation() } }
        runCurrent()
        load.cancel()

        val thrown = runCatching { load.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `one stalled feed neither blocks past the ceiling nor hides the ones that loaded`() = runTest {
        val load = loadFeeds(kinds = listOf("trending", "staff")) { kind ->
            if (kind == "trending") awaitCancellation() else listOf("lantern")
        }

        assertEquals(EXPLORE_FEED_TIMEOUT_MS, currentTime)
        assertEquals(FeedsStatus.Ready, load.status)
        assertEquals(mapOf("trending" to emptyList(), "staff" to listOf("lantern")), load.feeds)
    }

    @Test
    fun `every feed stalled ends in the failed state once the ceiling passes`() = runTest {
        val load = async {
            loadFeeds<String, String>(kinds = listOf("trending", "staff")) { awaitCancellation() }
        }
        advanceTimeBy(EXPLORE_FEED_TIMEOUT_MS - 1)
        assertFalse(load.isCompleted)
        advanceTimeBy(2)

        assertEquals(FeedsStatus.Failed, load.await().status)
    }

    @Test
    fun `status is empty only when every feed answered with nothing`() {
        assertEquals(
            FeedsStatus.Empty,
            feedsStatusOf(listOf(FeedResult.Loaded(emptyList<String>()), FeedResult.Loaded(emptyList()))),
        )
        assertEquals(
            FeedsStatus.Failed,
            feedsStatusOf(listOf(FeedResult.Loaded(emptyList<String>()), FeedResult.TimedOut)),
        )
        assertEquals(
            FeedsStatus.Failed,
            feedsStatusOf(listOf(FeedResult.Failed, FeedResult.Failed)),
        )
        assertEquals(
            FeedsStatus.Ready,
            feedsStatusOf(listOf(FeedResult.Failed, FeedResult.Loaded(listOf("toy car")))),
        )
    }

    @Test
    fun `only a rejected key or a WAF challenge means Sketchfab is unavailable`() {
        assertTrue(SketchfabService.SketchfabError.KeyRejected(401).isSketchfabUnavailable())
        assertTrue(SketchfabService.SketchfabError.WafChallenge("challenge").isSketchfabUnavailable())
        assertFalse(SketchfabService.SketchfabError.RequestFailed(429).isSketchfabUnavailable())
        assertFalse(IOException("offline").isSketchfabUnavailable())
    }
}
