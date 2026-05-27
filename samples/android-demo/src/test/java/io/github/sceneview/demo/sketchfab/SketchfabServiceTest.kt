package io.github.sceneview.demo.sketchfab

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
