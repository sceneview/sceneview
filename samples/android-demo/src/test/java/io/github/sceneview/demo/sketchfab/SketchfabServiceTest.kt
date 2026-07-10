package io.github.sceneview.demo.sketchfab

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Pure-JVM tests for [SketchfabService] — Robolectric is the cheapest path
 * to a real [android.content.Context] without spinning up a device.
 *
 * The tests are offline-only: they exercise URL construction and the
 * `MissingApiKey` guard. Live network round-trips against api.sketchfab.com
 * belong in a separate integration suite gated behind `SKETCHFAB_API_KEY`.
 */
@RunWith(RobolectricTestRunner::class)
class SketchfabServiceTest {

    private val service: SketchfabService by lazy {
        SketchfabService.getInstance(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `buildUrl produces the documented download endpoint`() {
        val url = service.buildUrl("models/abc123/download") {}
        assertEquals(
            "https://api.sketchfab.com/v3/models/abc123/download",
            url.toString(),
        )
    }

    @Test
    fun `buildUrl assembles search query parameters`() {
        val url = service.buildUrl("search") {
            addQueryParameter("type", "models")
            addQueryParameter("q", "car")
            addQueryParameter("downloadable", "true")
            addQueryParameter("count", "24")
        }
        val str = url.toString()
        assertTrue("expected scheme/host prefix, got $str",
            str.startsWith("https://api.sketchfab.com/v3/search?"))
        assertTrue("missing type param: $str", str.contains("type=models"))
        assertTrue("missing q param: $str", str.contains("q=car"))
        assertTrue("missing downloadable param: $str", str.contains("downloadable=true"))
        assertTrue("missing count param: $str", str.contains("count=24"))
    }

    /**
     * Regression for #1181 — Polish model names with `ś` were rendering as
     * `My�linice` (U+FFFD REPLACEMENT CHARACTER) because OkHttp's
     * `body.string()` falls back to ISO-8859-1 when the response
     * `Content-Type` header lacks an explicit `charset=` parameter.
     *
     * Verifies that the Json parser the service uses round-trips a payload
     * containing Polish / Czech / Greek / CJK names without substitution.
     * This guards the schema layer; the wire-level UTF-8 enforcement is the
     * `readString(Charsets.UTF_8)` call in `authenticatedGet` itself.
     */
    @Test
    fun `decodes non-ascii model names without substitution`() {
        val payload = """
            {
              "results": [
                {
                  "uid": "abc",
                  "name": "Myślinice — Polish",
                  "thumbnails": { "images": [] },
                  "viewerUrl": "https://sketchfab.com/abc",
                  "isDownloadable": true
                },
                {
                  "uid": "def",
                  "name": "Olomoucký hrad",
                  "thumbnails": { "images": [] },
                  "viewerUrl": "https://sketchfab.com/def",
                  "isDownloadable": true
                },
                {
                  "uid": "ghi",
                  "name": "Παρθενών — Acropolis",
                  "thumbnails": { "images": [] },
                  "viewerUrl": "https://sketchfab.com/ghi",
                  "isDownloadable": true
                },
                {
                  "uid": "jkl",
                  "name": "東京タワー",
                  "thumbnails": { "images": [] },
                  "viewerUrl": "https://sketchfab.com/jkl",
                  "isDownloadable": true
                }
              ]
            }
        """.trimIndent()
        val response = service.json.decodeFromString(
            SketchfabSearchResponse.serializer(),
            payload,
        )
        val names = response.results.map { it.name }
        assertEquals(
            listOf(
                "Myślinice — Polish",
                "Olomoucký hrad",
                "Παρθενών — Acropolis",
                "東京タワー",
            ),
            names,
        )
        for (name in names) {
            assertTrue(
                "name '$name' must not contain U+FFFD REPLACEMENT CHARACTER",
                !name.contains('�'),
            )
        }
    }

    /**
     * The load-bearing guarantee of the #2644 fix: cancelling the coroutine
     * that runs [SketchfabService.search] must abort the in-flight HTTP call
     * (socket included) instead of letting it run to completion behind the
     * caller's back.
     *
     * Differential by construction — the mock server delays the response body
     * by 10 s. With the pre-fix blocking `Call.execute()`, `cancelAndJoin()`
     * cannot return until the IO thread unblocks, so it stalls for the full
     * delay and the 3 s assertion fails. With `executeAsync()` the cancel
     * propagates to `Call.cancel()` and join returns in milliseconds.
     *
     * This is the mechanism that stops fast typing in the Explore search from
     * stacking zombie requests — the burst that used to trip Sketchfab's
     * CloudFront WAF and latch the "Sketchfab unavailable" banner.
     */
    @Test
    fun `in-flight search is cooperatively cancelled instead of running to completion`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"results":[]}""")
                    .bodyDelay(10, TimeUnit.SECONDS)
                    .build(),
            )
            // Direct construction (not getInstance) so the test instance points
            // at the local server with a fake token — no global state touched.
            val cancellableService = SketchfabService(
                ApplicationProvider.getApplicationContext(),
                baseUrl = server.url("/v3/").toString(),
                apiKeyProvider = { "test-token" },
            )
            runBlocking {
                val job = launch {
                    runCatching { cancellableService.search("chair") }
                }
                // Deterministic sync point: only cancel once the request has
                // genuinely reached the server, so the call is provably in
                // flight (no sleep-and-hope racing).
                val received = withContext(Dispatchers.IO) {
                    server.takeRequest(5, TimeUnit.SECONDS)
                }
                assertNotNull("search request never reached the mock server", received)
                val elapsedMs = measureTimeMillis { job.cancelAndJoin() }
                assertTrue(
                    "cancellation must abort the in-flight call promptly instead of " +
                        "blocking behind the mock server's 10 s body delay " +
                        "(took $elapsedMs ms) — regression guard for the blocking " +
                        "execute() this fix replaced (#2644)",
                    elapsedMs < 3_000,
                )
            }
        }
    }

    /**
     * The JSON endpoints carry an overall call ceiling so a degraded mobile
     * link can't pin IO threads indefinitely (#2644). The GLB download path
     * intentionally lifts it (large models legitimately stream for minutes) —
     * that derived client is private, so this guards the half that's visible.
     */
    @Test
    fun `client enforces an overall call timeout on the JSON endpoints`() {
        assertEquals(20_000, service.client.callTimeoutMillis)
    }

    /**
     * Live probing (2026-07-10, #2644) showed Sketchfab's degraded search
     * backend failing a majority of burst queries with HTTP 408 — and the
     * identical query re-sent moments later succeeding. The service owes the
     * user that one retry: a transient 408 must NOT surface as "0 results".
     */
    @Test
    fun `transient 408 is retried once and the retry's results are returned`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(408).body("{}").build())
            server.enqueue(
                MockResponse.Builder().code(200).body("""{"results":[]}""").build(),
            )
            val retryingService = SketchfabService(
                ApplicationProvider.getApplicationContext(),
                baseUrl = server.url("/v3/").toString(),
                apiKeyProvider = { "test-token" },
            )
            val results = runBlocking { retryingService.search("chair") }
            assertEquals(emptyList<SketchfabModel>(), results)
            assertEquals("expected the 408 to be retried exactly once", 2, server.requestCount)
        }
    }

    /** Non-transient client errors must fail fast — retrying a 404 is noise. */
    @Test
    fun `non-transient status is not retried`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).body("{}").build())
            val failingService = SketchfabService(
                ApplicationProvider.getApplicationContext(),
                baseUrl = server.url("/v3/").toString(),
                apiKeyProvider = { "test-token" },
            )
            @Suppress("SwallowedException") // expected exception in a negative test
            try {
                runBlocking { failingService.search("chair") }
                fail("expected RequestFailed(404), got success")
            } catch (e: SketchfabService.SketchfabError.RequestFailed) {
                assertEquals(404, e.statusCode)
            }
            assertEquals("a 404 must not be retried", 1, server.requestCount)
        }
    }

    @Test
    fun `search throws MissingApiKey when key is absent`() {
        // BuildConfig.SKETCHFAB_API_KEY is empty in unit tests (no env var,
        // no local.properties value injected) so SketchfabConfig.apiKey
        // resolves to null. The service must surface that as a typed error
        // rather than firing an unauthenticated request.
        if (SketchfabConfig.apiKey != null) {
            // Developer machine has the env var exported — skip rather than
            // make a real network call from a unit test.
            return
        }
        @Suppress("SwallowedException") // expected exception in a negative test — no action needed on catch
        try {
            runBlocking { service.search("car", limit = 5) }
            fail("expected MissingApiKey, got success")
        } catch (e: SketchfabService.SketchfabError.MissingApiKey) {
            // expected
        }
    }
}
