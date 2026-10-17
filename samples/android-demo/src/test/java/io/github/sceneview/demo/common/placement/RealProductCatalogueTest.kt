package io.github.sceneview.demo.common.placement

import io.github.sceneview.demo.ui.viewer.ModelThumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the AR placement catalogue to real products at their real size (#4560): no sample asset
 * opens it again, every card has its own picture, and every bundled file is one the asset
 * manifest fetches.
 */
class RealProductCatalogueTest {

    private val sampleAssets = listOf(
        "khronos_toy_car", "threejs_soldier", "khronos_glam_velvet_sofa", "khronos_sheen_chair",
        "khronos_lantern", "khronos_iridescent_dish",
    )

    @Test
    fun `the placement catalogue opens on real products, never on sample assets`() {
        assertEquals("ikea-nockeby-sofa", BUNDLED_PLACEMENT_MODELS.first().id)
        BUNDLED_PLACEMENT_MODELS.forEach { row ->
            assertFalse(row.assetLocation, sampleAssets.any { row.assetLocation.contains(it) })
        }
    }

    @Test
    fun `every row is placed at its real, measured size`() {
        BUNDLED_PLACEMENT_MODELS.forEach { row ->
            assertTrue(row.id, row.sizeIsMeasured)
            assertTrue(row.id, row.realWorldSizeMeters != PlacementModel.DEFAULT_REAL_WORLD_SIZE_METERS)
        }
        assertEquals(2.30f, BUNDLED_PLACEMENT_MODELS.first { it.id == "ikea-nockeby-sofa" }.realWorldSizeMeters)
        assertEquals(4.69f, BUNDLED_PLACEMENT_MODELS.first { it.id == "tesla-model-3" }.realWorldSizeMeters)
    }

    @Test
    fun `every product card shows its own picture`() {
        (BUNDLED_REAL_PRODUCTS + DOWNLOADABLE_REAL_PRODUCTS).forEach { product ->
            assertNotNull(product.id, ModelThumbnails.resourceFor(product.thumbnailStem))
        }
        BUNDLED_PLACEMENT_MODELS.forEach { row -> assertNotNull(row.id, placementThumbnailResFor(row)) }
    }

    @Test
    fun `a downloadable row keeps its picture before and after the download`() {
        val product = DOWNLOADABLE_REAL_PRODUCTS.first()
        val pending = product.downloadablePlacementRow("file:///hd/x.glb", ready = false, downloading = true)
        val ready = product.downloadablePlacementRow("file:///hd/x.glb", ready = true, downloading = false)
        assertTrue(pending.pending)
        assertFalse(ready.pending)
        assertNotNull(placementThumbnailResFor(pending))
        assertEquals(placementThumbnailResFor(pending), placementThumbnailResFor(ready))
    }

    @Test
    fun `no product is offered twice`() {
        val ids = (BUNDLED_REAL_PRODUCTS + DOWNLOADABLE_REAL_PRODUCTS).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every bundled product is registered in the asset manifest`() {
        val manifest = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "assets/manifest.json") }
            .first { it.isFile }
            .readText()
        BUNDLED_REAL_PRODUCTS.forEach { product ->
            assertTrue(product.id, manifest.contains("samples/android-demo/src/main/assets/models/${product.stem}.glb"))
        }
    }

    @Test
    fun `real sizes print like the website catalogue`() {
        assertEquals("2.30 m", formatRealSize(2.30f))
        assertEquals("63 cm", formatRealSize(0.63f))
        assertEquals("18 cm", formatRealSize(0.18f))
        assertEquals("4.5 cm", formatRealSize(0.045f))
    }
}
