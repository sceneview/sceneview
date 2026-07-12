package io.github.sceneview.demo.sources

import io.github.sceneview.demo.sketchfab.SketchfabModel
import io.github.sceneview.demo.sketchfab.SketchfabTag
import io.github.sceneview.demo.sketchfab.SketchfabThumbnail
import io.github.sceneview.demo.sketchfab.SketchfabThumbnails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Pure-JVM tests for the source-agnostic model layer (#2645): the per-source
 * wire → [GalleryModel] mappers, the display helpers, and the source-registry
 * resolution logic. No Android runtime, no network — everything here is a pure
 * function, so these run in the fast plain-JUnit lane (no Robolectric).
 */
class GalleryModelMappingTest {

    // formattedFaceCount / attribution use String.format with the default
    // locale; pin US so "1.2k" doesn't become "1,2k" on a non-US CI runner.
    private var savedLocale: Locale = Locale.getDefault()

    @Before fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    // ── ModelSourceId ────────────────────────────────────────────────────

    @Test fun `fromSlug round-trips every source and rejects unknown`() {
        assertEquals(ModelSourceId.SKETCHFAB, ModelSourceId.fromSlug("sketchfab"))
        assertEquals(ModelSourceId.ICOSA, ModelSourceId.fromSlug("icosa"))
        assertEquals(ModelSourceId.POLY_HAVEN, ModelSourceId.fromSlug("polyhaven"))
        assertNull(ModelSourceId.fromSlug("nope"))
        assertNull(ModelSourceId.fromSlug(null))
    }

    // ── GalleryModel helpers ─────────────────────────────────────────────

    @Test fun `cardKey is unique across sources for the same raw id`() {
        val icosa = model(ModelSourceId.ICOSA, id = "abc")
        val sketchfab = model(ModelSourceId.SKETCHFAB, id = "abc")
        assertEquals("icosa:abc", icosa.cardKey)
        assertEquals("sketchfab:abc", sketchfab.cardKey)
        assertTrue("keys must differ across sources", icosa.cardKey != sketchfab.cardKey)
    }

    @Test fun `preferredThumbnailUrl picks the in-range candidate then falls back`() {
        val m = model(
            ModelSourceId.ICOSA,
            thumbnails = listOf(
                GalleryThumbnail("small", 128, 128),
                GalleryThumbnail("mid", 512, 512),
                GalleryThumbnail("huge", 2048, 2048),
            ),
        )
        // 512 is within the default 320..640 sweet spot.
        assertEquals("mid", m.preferredThumbnailUrl())
        // No sweet-spot candidate → largest wins.
        val onlyBig = model(
            ModelSourceId.ICOSA,
            thumbnails = listOf(GalleryThumbnail("a", 900, 900), GalleryThumbnail("b", 2048, 2048)),
        )
        assertEquals("b", onlyBig.preferredThumbnailUrl())
        // Empty → null (the card renders its placeholder).
        assertNull(model(ModelSourceId.ICOSA, thumbnails = emptyList()).preferredThumbnailUrl())
    }

    @Test fun `primaryTagDisplay title-cases the first tag or falls back`() {
        assertEquals("Car", model(ModelSourceId.ICOSA, tags = listOf("car", "vehicle")).primaryTagDisplay())
        assertEquals("3D Model", model(ModelSourceId.ICOSA, tags = emptyList()).primaryTagDisplay())
    }

    @Test fun `formattedFaceCount renders k and M`() {
        assertEquals("999", model(ModelSourceId.ICOSA, faceCount = 999).formattedFaceCount())
        assertEquals("1.2k", model(ModelSourceId.ICOSA, faceCount = 1_200).formattedFaceCount())
        assertEquals("3.4M", model(ModelSourceId.ICOSA, faceCount = 3_400_000).formattedFaceCount())
    }

    @Test fun `attributionLine composes present parts and always names the source`() {
        val full = model(
            ModelSourceId.ICOSA,
            attribution = GalleryAttribution(authorName = "Ada", license = "CC BY 4.0"),
        )
        assertEquals("by Ada · CC BY 4.0 · via Icosa Gallery", full.attributionLine())
        // No author, no license → only the "via <source>" credit remains.
        val bare = model(ModelSourceId.POLY_HAVEN, attribution = GalleryAttribution())
        assertEquals("via Poly Haven", bare.attributionLine())
    }

    // ── licenseDisplayName ───────────────────────────────────────────────

    @Test fun `licenseDisplayName maps the CC vocabulary`() {
        assertEquals("CC BY 4.0", licenseDisplayName("CREATIVE_COMMONS_BY"))
        assertEquals("CC BY 4.0", licenseDisplayName("cc-by"))
        assertEquals("CC0", licenseDisplayName("CREATIVE_COMMONS_0"))
        assertEquals("All rights reserved", licenseDisplayName("ALL_RIGHTS_RESERVED"))
        assertNull(licenseDisplayName(null))
        assertNull(licenseDisplayName(""))
        // Unknown code degrades to a readable label rather than a raw enum.
        assertEquals("Some custom", licenseDisplayName("SOME_CUSTOM"))
    }

    // ── Icosa wire → GalleryModel ────────────────────────────────────────

    @Test fun `IcosaAsset maps to a GalleryModel and prefers a GLB format`() {
        val asset = IcosaAsset(
            assetId = "asset-1",
            displayName = "Chair",
            authorName = "Ada",
            license = "CREATIVE_COMMONS_BY",
            triangleCount = 4_200,
            tags = listOf("furniture"),
            thumbnail = IcosaFile(url = "https://cdn/thumb.png", width = 800, height = 600),
            formats = listOf(
                IcosaFormat(formatType = "GLTF2", root = IcosaFile(url = "https://cdn/model.gltf")),
                IcosaFormat(formatType = "GLB", root = IcosaFile(url = "https://cdn/model.glb")),
            ),
        )
        val g = asset.toGalleryModel()!!
        assertEquals(ModelSourceId.ICOSA, g.sourceId)
        assertEquals("asset-1", g.id)
        assertEquals("Chair", g.name)
        assertEquals(4_200, g.faceCount)
        assertEquals("Ada", g.attribution.authorName)
        assertEquals("CC BY 4.0", g.attribution.license)
        assertEquals("https://icosa.gallery/view/asset-1", g.attribution.sourceUrl)
        assertEquals(listOf("furniture"), g.tags)
        // GLB is preferred over the GLTF2 entry even though GLTF2 came first.
        assertTrue(asset.preferredFormat()!!.root!!.url!!.endsWith(".glb"))
    }

    @Test fun `IcosaAsset with no usable format self-hides`() {
        val asset = IcosaAsset(assetId = "asset-2", displayName = "Broken", formats = emptyList())
        assertNull("no renderable format → dropped from the feed", asset.toGalleryModel())
    }

    @Test fun `IcosaAsset resolvedId falls back across field names`() {
        assertEquals("A", IcosaAsset(assetId = "A", id = "B").resolvedId())
        assertEquals("B", IcosaAsset(assetId = null, id = "B").resolvedId())
    }

    // ── Poly Haven wire → GalleryModel ───────────────────────────────────

    @Test fun `PolyHavenAsset maps to a CC0 GalleryModel with CDN thumbnails`() {
        val asset = PolyHavenAsset(
            name = "Brass Vase",
            downloadCount = 1_000L,
            authors = linkedMapOf("Greg Zaal" to "artist"),
            categories = listOf("decor"),
            tags = listOf("vase", "brass"),
        )
        val g = asset.toGalleryModel("brass_vase", "https://cdn.polyhaven.com/")
        assertEquals(ModelSourceId.POLY_HAVEN, g.sourceId)
        assertEquals("brass_vase", g.id)
        assertEquals("Brass Vase", g.name)
        assertEquals("CC0", g.attribution.license)
        assertEquals("Greg Zaal", g.attribution.authorName)
        assertEquals("https://polyhaven.com/a/brass_vase", g.attribution.sourceUrl)
        assertEquals(listOf("decor", "vase", "brass"), g.tags)
        assertTrue(
            "thumbnail must point at the Poly Haven CDN thumbs path",
            g.thumbnails.first().url.startsWith("https://cdn.polyhaven.com/asset_img/thumbs/brass_vase.png"),
        )
    }

    @Test fun `PolyHavenAsset name falls back to a humanised slug`() {
        val g = PolyHavenAsset(name = null).toGalleryModel("rusty_barrel", "https://cdn.polyhaven.com/")
        assertEquals("Rusty barrel", g.name)
    }

    @Test fun `PolyHavenAsset matches on name, slug, tag and category`() {
        val asset = PolyHavenAsset(name = "Brass Vase", categories = listOf("decor"), tags = listOf("metal"))
        assertTrue(asset.matches("brass_vase", "vase"))   // name
        assertTrue(asset.matches("brass_vase", "brass"))  // slug
        assertTrue(asset.matches("brass_vase", "metal"))  // tag
        assertTrue(asset.matches("brass_vase", "decor"))  // category
        assertTrue(!asset.matches("brass_vase", "spaceship"))
    }

    // ── Sketchfab wire → GalleryModel ────────────────────────────────────

    @Test fun `SketchfabModel maps onto the shared GalleryModel`() {
        val model = SketchfabModel(
            uid = "uid123",
            name = "Robot",
            thumbnails = SketchfabThumbnails(listOf(SketchfabThumbnail("https://cdn/t.jpg", 512, 512))),
            viewerUrl = "https://sketchfab.com/models/uid123",
            downloadable = true,
            tags = listOf(SketchfabTag("robot"), SketchfabTag("scifi")),
            faceCount = 12_000,
            animationCount = 2,
        )
        val g = model.toGalleryModel()
        assertEquals(ModelSourceId.SKETCHFAB, g.sourceId)
        assertEquals("uid123", g.id)
        assertEquals("Robot", g.name)
        assertEquals(listOf("robot", "scifi"), g.tags)
        assertEquals(12_000, g.faceCount)
        assertEquals(2, g.animationCount)
        assertTrue(g.isAnimated)
        assertTrue(g.downloadable)
        assertEquals("https://sketchfab.com/models/uid123", g.attribution.sourceUrl)
        assertEquals("https://cdn/t.jpg", g.thumbnails.single().url)
    }

    // ── ModelSourcesState.resolveInitial ─────────────────────────────────

    @Test fun `resolveInitial honours a persisted still-available choice`() {
        val sources = listOf(FakeSource(ModelSourceId.ICOSA), FakeSource(ModelSourceId.POLY_HAVEN))
        val chosen = ModelSourcesState.resolveInitial(sources, ModelSourceId.POLY_HAVEN)
        assertEquals(ModelSourceId.POLY_HAVEN, chosen.id)
    }

    @Test fun `resolveInitial falls back to the first when the saved source is gone or null`() {
        val sources = listOf(FakeSource(ModelSourceId.ICOSA), FakeSource(ModelSourceId.POLY_HAVEN))
        // Saved Sketchfab is not in the available list (no key) → first available.
        assertEquals(ModelSourceId.ICOSA, ModelSourcesState.resolveInitial(sources, ModelSourceId.SKETCHFAB).id)
        // No saved choice → first available.
        assertEquals(ModelSourceId.ICOSA, ModelSourcesState.resolveInitial(sources, null).id)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun model(
        sourceId: ModelSourceId,
        id: String = "id",
        thumbnails: List<GalleryThumbnail> = emptyList(),
        attribution: GalleryAttribution = GalleryAttribution(),
        tags: List<String> = emptyList(),
        faceCount: Int = 0,
    ) = GalleryModel(
        sourceId = sourceId,
        id = id,
        name = "name",
        thumbnails = thumbnails,
        attribution = attribution,
        tags = tags,
        faceCount = faceCount,
    )

    /** Minimal [ModelSource] so [ModelSourcesState.resolveInitial] can be unit-tested. */
    private class FakeSource(override val id: ModelSourceId) : ModelSource {
        override val isAvailable = true
        override val feedKinds = listOf(FeedKind.TRENDING)
        override suspend fun feed(kind: FeedKind, animatedOnly: Boolean, limit: Int) = emptyList<GalleryModel>()
        override suspend fun search(query: String, limit: Int) = emptyList<GalleryModel>()
        override suspend fun download(model: GalleryModel, onProgress: ((Long, Long) -> Unit)?): File =
            throw UnsupportedOperationException()
    }
}
