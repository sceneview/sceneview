package io.github.sceneview.demo.sources

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
