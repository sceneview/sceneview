package io.github.sceneview.demo.feedback

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Cache-semantics tests for [FeedbackTicketStatus] (#1930 review).
 *
 * The unauthenticated GitHub API budget is only 60 requests/hour, so the
 * crucial behaviours are: a successful OPEN/CLOSED is cached, a rate-limit hit
 * is cached process-wide so 50 rows stop hammering the dead budget, and a
 * 200-with-garbage-body is cached as a short-lived negative rather than retried
 * forever.
 *
 * Runs under Robolectric so `org.json` resolves to a real implementation.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackTicketStatusTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        FeedbackTicketStatus.resetForTest()
        server = MockWebServer()
        server.start()
        FeedbackTicketStatus.client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        FeedbackTicketStatus.apiBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.close()
        FeedbackTicketStatus.resetForTest()
    }

    @Test
    fun `an open issue resolves to OPEN`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"state":"open"}""").build())

        assertEquals(TicketState.OPEN, FeedbackTicketStatus.fetch(1))
    }

    @Test
    fun `a closed issue resolves to CLOSED`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"state":"closed"}""").build())

        assertEquals(TicketState.CLOSED, FeedbackTicketStatus.fetch(2))
    }

    @Test
    fun `a successful result is cached - the second fetch makes no request`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"state":"open"}""").build())

        assertEquals(TicketState.OPEN, FeedbackTicketStatus.fetch(3))
        assertEquals(TicketState.OPEN, FeedbackTicketStatus.fetch(3))

        // Only the first fetch hit the network — the second was served from cache.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a rate-limit hit parks every issue without further requests`() = runTest {
        // GitHub reports the budget is exhausted.
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader(
                    "X-RateLimit-Reset",
                    ((System.currentTimeMillis() / 1000) + 3600).toString(),
                )
                .body("""{"message":"API rate limit exceeded"}""")
                .build(),
        )

        // First issue trips the rate-limit sentinel.
        assertEquals(TicketState.UNKNOWN, FeedbackTicketStatus.fetch(10))
        // Every other tracked issue now short-circuits — no request is made.
        assertEquals(TicketState.UNKNOWN, FeedbackTicketStatus.fetch(11))
        assertEquals(TicketState.UNKNOWN, FeedbackTicketStatus.fetch(12))

        assertEquals("only the first issue hit the network", 1, server.requestCount)
    }

    @Test
    fun `a 200 with an unparseable body is cached as a negative`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("not json at all").build())

        // First call hits the network and gets UNKNOWN.
        assertEquals(TicketState.UNKNOWN, FeedbackTicketStatus.fetch(20))
        // Second call is served from the short-lived negative cache — no retry
        // loop against an unparseable response.
        assertEquals(TicketState.UNKNOWN, FeedbackTicketStatus.fetch(20))

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 404 is not cached so a later fetch retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"state":"open"}""").build())

        assertEquals(TicketState.UNKNOWN, FeedbackTicketStatus.fetch(30))
        // The transient failure was not cached — the retry sees the new result.
        assertEquals(TicketState.OPEN, FeedbackTicketStatus.fetch(30))

        assertEquals(2, server.requestCount)
    }
}
