package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Model Viewer → AR handoff (#3493): what [resolveRequestedExtraPlacementRow] decides
 * to arm when the viewer's "View in AR" button names a model.
 *
 * The bug this guards: the report was filed from the Model Viewer while looking at the bundled
 * Damaged Helmet — deliberately absent from [BUNDLED_PLACEMENT_MODELS] (#2023) — and tapping
 * "View in AR" dropped the user on the model picker instead of opening the camera on the helmet.
 * The handoff silently produced no armed row for any model outside the curated six, so
 * `ARPlacementDemo`'s `flow.enterAr()` never fired.
 */
class PlacementModelPickerTest {

    private val catalogue = listOf(
        PlacementModel(
            id = "soldier",
            displayName = "Soldier",
            assetLocation = "models/threejs_soldier.glb",
        ),
    )

    @Test
    fun `no handoff means nothing to add`() {
        assertNull(resolveRequestedExtraPlacementRow(requestedModel = null, catalogue = catalogue))
        assertNull(resolveRequestedExtraPlacementRow(requestedModel = "", catalogue = catalogue))
    }

    @Test
    fun `a full asset path already in the catalogue needs no extra row`() {
        // The catalogue's own row is armed instead — see the caller's fallback lookup.
        assertNull(
            resolveRequestedExtraPlacementRow(
                requestedModel = "models/threejs_soldier.glb",
                catalogue = catalogue,
            ),
        )
    }

    @Test
    fun `a bare file stem already in the catalogue needs no extra row`() {
        assertNull(
            resolveRequestedExtraPlacementRow(requestedModel = "threejs_soldier", catalogue = catalogue),
        )
    }

    @Test
    fun `the Damaged Helmet — bundled, but outside the curated six — still gets a row`() {
        // This is the exact #3493 repro: a bundled model the Model Viewer shows but the AR
        // placement catalogue does not curate. It must not be dropped on the floor.
        val row = resolveRequestedExtraPlacementRow(
            requestedModel = "models/khronos_damaged_helmet.glb",
            catalogue = catalogue,
            requestedDisplayName = "Damaged Helmet",
        )
        assertEquals(REQUESTED_MODEL_PLACEMENT_ROW_ID, row?.id)
        assertEquals("Damaged Helmet", row?.displayName)
        assertEquals("models/khronos_damaged_helmet.glb", row?.assetLocation)
    }

    @Test
    fun `an uncatalogued model with no viewer-supplied name derives one from the file stem`() {
        val row = resolveRequestedExtraPlacementRow(
            requestedModel = "models/khronos_damaged_helmet.glb",
            catalogue = catalogue,
        )
        assertEquals("khronos_damaged_helmet", row?.displayName)
    }

    @Test
    fun `an opened file is recognised by its scheme and named from openedDisplayName first`() {
        val row = resolveRequestedExtraPlacementRow(
            requestedModel = "file:///data/user/0/io.github.sceneview.demo/cache/opened-model.glb",
            catalogue = catalogue,
            requestedDisplayName = "should not win for an opened file",
            openedDisplayName = "my_scan.3mf",
            openedSizeMeters = 0.06f,
        )
        assertEquals(OPENED_FILE_PLACEMENT_ROW_ID, row?.id)
        assertEquals("my_scan.3mf", row?.displayName)
        assertEquals(0.06f, row?.realWorldSizeMeters)
    }

    @Test
    fun `a measured opened-file size never leaks onto a bundled row`() {
        // DemoSettings.openedModelSizeMeters is a measurement of the file the viewer had
        // loaded. A bundled handoff must not inherit it just because the field was still set.
        val row = resolveRequestedExtraPlacementRow(
            requestedModel = "models/khronos_damaged_helmet.glb",
            catalogue = catalogue,
            openedSizeMeters = 0.06f,
        )
        assertEquals(PlacementModel.DEFAULT_REAL_WORLD_SIZE_METERS, row?.realWorldSizeMeters)
    }

    @Test
    fun `an opened file with no display name falls back to its staged basename`() {
        val row = resolveRequestedExtraPlacementRow(
            requestedModel = "file:///data/user/0/io.github.sceneview.demo/cache/opened-model.glb",
            catalogue = catalogue,
        )
        assertEquals("opened-model.glb", row?.displayName)
    }

    @Test
    fun `an opened file with no measured size falls back to the catalogue default`() {
        val row = resolveRequestedExtraPlacementRow(
            requestedModel = "file:///tmp/opened-model.glb",
            catalogue = catalogue,
        )
        assertEquals(PlacementModel.DEFAULT_REAL_WORLD_SIZE_METERS, row?.realWorldSizeMeters)
    }

    @Test
    fun `a non-finite or non-positive measured size is rejected, not trusted blindly`() {
        val nan = resolveRequestedExtraPlacementRow(
            requestedModel = "file:///tmp/opened-model.glb",
            catalogue = catalogue,
            openedSizeMeters = Float.NaN,
        )
        assertEquals(PlacementModel.DEFAULT_REAL_WORLD_SIZE_METERS, nan?.realWorldSizeMeters)

        val zero = resolveRequestedExtraPlacementRow(
            requestedModel = "file:///tmp/opened-model.glb",
            catalogue = catalogue,
            openedSizeMeters = 0f,
        )
        assertEquals(PlacementModel.DEFAULT_REAL_WORLD_SIZE_METERS, zero?.realWorldSizeMeters)
    }
}
