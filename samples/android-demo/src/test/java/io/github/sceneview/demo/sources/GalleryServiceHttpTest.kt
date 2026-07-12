package io.github.sceneview.demo.sources

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Offline HTTP-contract tests for the two keyless CC sources (#2645): they
 * exercise query-string construction, wire-JSON parsing, Poly Haven's
 * client-side sort/search, and the "a failed request throws (the tab catches
 * per-feed)" resilience contract — all against a local [MockWebServer], no live
 * network. Robolectric supplies the real [android.content.Context] the services
 * need for their on-disk download cache.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryServiceHttpTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ── Feed-kind declarations (no network) ──────────────────────────────

    @Test fun `sources advertise honest feed kinds and availability`() {
        val icosa = IcosaGalleryService(context)
        val poly = PolyHavenService(context)
        assertTrue(icosa.isAvailable)
        assertTrue(poly.isAvailable)
        assertEquals(
            listOf(FeedKind.TRENDING, FeedKind.STAFF_PICKS, FeedKind.RECENTLY_ADDED),
            icosa.feedKinds,
        )
        // Poly Haven has no editorial curation → no STAFF_PICKS.
        assertEquals(listOf(FeedKind.TRENDING, FeedKind.RECENTLY_ADDED), poly.feedKinds)
        // Only Sketchfab's feed endpoints take an `animated` flag.
        assertTrue(!icosa.supportsAnimatedFilter)
        assertTrue(!poly.supportsAnimatedFilter)
    }

    // ── Icosa Gallery ────────────────────────────────────────────────────

    @Test fun `icosa feed parses assets and sends the trending query`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(ICOSA_LIST).build())
            val icosa = IcosaGalleryService(context, baseUrl = server.url("/v1/").toString())

            val models = icosa.feed(FeedKind.TRENDING, animatedOnly = false, limit = 10)

            assertEquals(1, models.size)
            assertEquals(ModelSourceId.ICOSA, models[0].sourceId)
            assertEquals("Chair", models[0].name)
            assertEquals("CC BY 4.0", models[0].attribution.license)

            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("feed request never reached the mock server", request)
            val url = request!!.url
            assertEquals("assets", url.pathSegments.last())
            assertEquals("GLTF2", url.queryParameter("format"))
            assertEquals("BEST", url.queryParameter("orderBy"))
            assertEquals("10", url.queryParameter("pageSize"))
        }
    }

    @Test fun `icosa staff picks adds the curated flag`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(ICOSA_LIST).build())
            val icosa = IcosaGalleryService(context, baseUrl = server.url("/v1/").toString())

            icosa.feed(FeedKind.STAFF_PICKS, animatedOnly = false, limit = 5)

            val url = server.takeRequest(5, TimeUnit.SECONDS)!!.url
            assertEquals("BEST", url.queryParameter("orderBy"))
            assertEquals("true", url.queryParameter("curated"))
        }
    }

    @Test fun `icosa recently-added orders by newest`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(ICOSA_LIST).build())
            val icosa = IcosaGalleryService(context, baseUrl = server.url("/v1/").toString())

            icosa.feed(FeedKind.RECENTLY_ADDED, animatedOnly = false, limit = 5)

            assertEquals("NEWEST", server.takeRequest(5, TimeUnit.SECONDS)!!.url.queryParameter("orderBy"))
        }
    }

    @Test fun `icosa search sends the keywords parameter`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(ICOSA_LIST).build())
            val icosa = IcosaGalleryService(context, baseUrl = server.url("/v1/").toString())

            val results = icosa.search(query = "chair", limit = 24)

            assertEquals(1, results.size)
            val url = server.takeRequest(5, TimeUnit.SECONDS)!!.url
            assertEquals("chair", url.queryParameter("keywords"))
            assertEquals("24", url.queryParameter("pageSize"))
        }
    }

    @Test fun `icosa feed throws on an HTTP error so the tab can catch per-feed`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(500).body("boom").build())
            val icosa = IcosaGalleryService(context, baseUrl = server.url("/v1/").toString())

            var thrown: Throwable? = null
            runBlocking {
                thrown = runCatching { icosa.feed(FeedKind.TRENDING) }.exceptionOrNull()
            }
            assertTrue("expected IOException on HTTP 500, got $thrown", thrown is IOException)
        }
    }

    // ── Poly Haven ───────────────────────────────────────────────────────

    @Test fun `poly haven sorts trending by downloads and recent by date, index cached`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // A single index response backs BOTH feed calls — the second reads
            // the in-memory cache (TTL), so only one HTTP round-trip happens.
            server.enqueue(MockResponse.Builder().code(200).body(POLY_INDEX).build())
            val poly = PolyHavenService(context, baseUrl = server.url("/").toString())

            val trending = poly.feed(FeedKind.TRENDING, limit = 10)
            val recent = poly.feed(FeedKind.RECENTLY_ADDED, limit = 10)

            // barrel has more downloads; vase is newer.
            assertEquals("barrel", trending.first().id)
            assertEquals("vase", recent.first().id)

            val first = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("models", first!!.url.queryParameter("t"))
            // Second feed served from cache → exactly one HTTP round-trip total.
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `poly haven search matches client-side over the index`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(POLY_INDEX).build())
            val poly = PolyHavenService(context, baseUrl = server.url("/").toString())

            val hits = poly.search(query = "vase", limit = 10)

            assertEquals(1, hits.size)
            assertEquals("vase", hits.first().id)
            // Blank query short-circuits to empty (no crash, no full-catalog dump).
            assertTrue(poly.search(query = "   ", limit = 10).isEmpty())
        }
    }

    @Test fun `poly haven dedups a concurrent cold index fetch to a single request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Two responses are available, but a correct in-flight dedup issues
            // exactly ONE request. The body delay keeps the first fetch in-flight
            // long enough for the second concurrent caller to reach the (still
            // empty) cache — without it the race window can close before the
            // second caller looks, hiding a regression. The sequential existing
            // test (`… index cached`) never exercises this path.
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder().code(200).body(POLY_INDEX)
                        .bodyDelay(300, TimeUnit.MILLISECONDS).build(),
                )
            }
            val poly = PolyHavenService(context, baseUrl = server.url("/").toString())

            // Fire TRENDING + RECENTLY_ADDED CONCURRENTLY, mirroring the Explore
            // tab's parallel cold-open feeds (the exact scenario the reviewer
            // flagged). `async` dispatches each `feed()` onto Dispatchers.IO, so
            // both genuinely reach `modelsIndex()` at once.
            val (trending, recent) = coroutineScope {
                val a = async { poly.feed(FeedKind.TRENDING, limit = 10) }
                val b = async { poly.feed(FeedKind.RECENTLY_ADDED, limit = 10) }
                a.await() to b.await()
            }

            assertEquals("barrel", trending.first().id)
            assertEquals("vase", recent.first().id)
            // The whole point: concurrent cold callers collapse to ONE round-trip.
            assertEquals(1, server.requestCount)
        }
    }

    // ── Download hardening (#2645 review) ─────────────────────────────────

    @Test fun `icosa single-file download sanitises a hostile id into the cache dir`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val binaryUrl = server.url("/cdn/evil.glb").toString()
            val detail = """
                {
                  "assetId": "evil",
                  "displayName": "Evil",
                  "formats": [ { "formatType": "GLTF2", "root": { "url": "$binaryUrl" } } ]
                }
            """.trimIndent()
            server.enqueue(MockResponse.Builder().code(200).body(detail).build())     // asset detail
            server.enqueue(MockResponse.Builder().code(200).body("GLBDATA").build())  // the GLB bytes
            val icosa = IcosaGalleryService(context, baseUrl = server.url("/v1/").toString())

            // An id that tries to climb out of the per-source cache directory.
            val model = GalleryModel(
                sourceId = ModelSourceId.ICOSA,
                id = "../../../../tmp/evil",
                name = "Evil",
            )
            val file = icosa.download(model)

            // The cached file must stay confined to gallery/icosa/ — the traversal
            // segments are flattened by NetworkModelDownloader.sanitize, not honoured.
            val cacheRoot = File(context.cacheDir, NetworkModelDownloader.CACHE_DIR_NAME).canonicalFile
            assertTrue(
                "downloaded file escaped the cache dir: ${file.canonicalPath}",
                file.canonicalPath.startsWith(cacheRoot.path + File.separator),
            )
            assertTrue(file.exists() && file.length() > 0L)
        }
    }

    @Test fun `readBoundedBody rejects an over-cap body and accepts one under it`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("x".repeat(500)).build())
            server.enqueue(MockResponse.Builder().code(200).body("small").build())
            val client = OkHttpClient()

            client.newCall(Request.Builder().url(server.url("/big")).build()).execute().use { resp ->
                val thrown = runCatching { resp.readBoundedBody(maxBytes = 64) }.exceptionOrNull()
                assertTrue("over-cap body should throw IOException, got $thrown", thrown is IOException)
            }
            client.newCall(Request.Builder().url(server.url("/ok")).build()).execute().use { resp ->
                assertEquals("small", resp.readBoundedBody(maxBytes = 64))
            }
        }
    }

    @Test fun `downloadSingle aborts over-cap streams without poisoning the cache`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // (a) known Content-Length over the cap → header fast-fail.
            server.enqueue(MockResponse.Builder().code(200).body("x".repeat(1000)).build())
            // (b) chunked body (length hidden, -1) over the cap → mid-stream guard,
            //     the case that matters for a genuinely unbounded hostile stream.
            server.enqueue(MockResponse.Builder().code(200).chunkedBody("x".repeat(1000), 64).build())
            val downloader = NetworkModelDownloader(context, maxModelBytes = 100)

            val known = runCatching {
                downloader.downloadSingle(ModelSourceId.ICOSA, "known.glb", server.url("/known").toString())
            }.exceptionOrNull()
            assertTrue("known-length over-cap should throw, got $known", known is IOException)

            val chunked = runCatching {
                downloader.downloadSingle(ModelSourceId.ICOSA, "chunked.glb", server.url("/chunked").toString())
            }.exceptionOrNull()
            assertTrue("chunked over-cap should throw, got $chunked", chunked is IOException)

            // No partial file renamed into place, and no leftover temp files.
            val dir = File(File(context.cacheDir, NetworkModelDownloader.CACHE_DIR_NAME), ModelSourceId.ICOSA.slug)
            assertTrue("known.glb poisoned the cache", !File(dir, "known.glb").exists())
            assertTrue("chunked.glb poisoned the cache", !File(dir, "chunked.glb").exists())
            val temps = dir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
            assertTrue("leftover temp files: $temps", temps.isEmpty())
        }
    }

    private companion object {
        val ICOSA_LIST = """
            {
              "assets": [
                {
                  "assetId": "a1",
                  "displayName": "Chair",
                  "authorName": "Ada",
                  "license": "CREATIVE_COMMONS_BY",
                  "triangleCount": 4200,
                  "tags": ["furniture"],
                  "thumbnail": { "url": "https://cdn/thumb.png", "width": 800, "height": 600 },
                  "formats": [
                    { "formatType": "GLTF2", "root": { "url": "https://cdn/model.glb" } }
                  ]
                }
              ]
            }
        """.trimIndent()

        // barrel: many downloads, old; vase: few downloads, new.
        val POLY_INDEX = """
            {
              "barrel": { "name": "Barrel", "download_count": 900, "date_published": 100 },
              "vase":   { "name": "Vase",   "download_count": 50,  "date_published": 300 }
            }
        """.trimIndent()
    }
}
