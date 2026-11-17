package io.github.sceneview.demo.demos

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.demos.internal.model.SplatFaces
import io.github.sceneview.demo.demos.internal.model.SurfelSplats
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The scan result's Photo | Model | Plan switch ([ResultLayerPicker]), QA preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultLayerPickerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `Photo, Model and Plan switch the layer, and Plan moves the camera as the dock does`() {
        val layers = ResultLayers(offered = true)
        var plan by mutableStateOf(false)
        val moves = mutableListOf<Boolean>()
        rule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                ResultLayerPicker(layers, plan, ready = true, onPlan = { moves += it; plan = it })
            }
        }

        rule.onNodeWithTag("result_layer_model").assertIsOn()
        rule.onNodeWithTag("result_layer_photo").performClick()
        rule.waitForIdle()
        assertTrue(layers.photoPicked)
        rule.onNodeWithTag("result_layer_photo").assertIsOn()
        // Picked before the splats are built: the line says it is coming.
        rule.onNodeWithText("Preparing the photo preview…").assertIsDisplayed()

        layers.splats = SplatCloud(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        rule.waitForIdle()
        rule.onNodeWithText("Photo preview · measures stay on the model").assertIsDisplayed()

        rule.onNodeWithTag("result_layer_plan").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), moves)
        assertFalse("the plan is read on the model", layers.photoPicked)
        rule.onNodeWithTag("result_layer_plan").assertIsOn()

        rule.onNodeWithTag("result_layer_model").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true, false), moves)
        rule.onNodeWithTag("result_layer_model").assertIsOn()
    }

    @Test
    fun `once the model is seen Photo can be picked, and a scan with nothing to splat goes back to the model`() {
        val layers = ResultLayers(offered = true)
        var ready by mutableStateOf(false)
        rule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                ResultLayerPicker(layers, plan = false, ready = ready, onPlan = {})
            }
        }
        assertFalse(layers.photoPicked)
        ready = true
        rule.waitForIdle()
        rule.onNodeWithTag("result_layer_photo").performClick()
        rule.waitForIdle()
        assertTrue(layers.photoPicked)

        layers.unavailable = true
        rule.waitForIdle()
        assertFalse(layers.photoPicked)
        // Picked again, it stays on the model: the pill never says Photo over the mesh.
        rule.onNodeWithTag("result_layer_photo").performClick()
        rule.waitForIdle()
        assertFalse(layers.photoPicked)
        rule.onNodeWithTag("result_layer_model").assertIsOn()
    }

    @Test
    fun `a pick reversed while the veil comes up leaves no veil over the scene`() {
        val layers = ResultLayers(offered = true)
        layers.splats = SplatCloud(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        rule.mainClock.autoAdvance = false
        rule.setContent { LaunchedEffect(layers) { layers.swapUnderVeil() } }
        rule.mainClock.advanceTimeByFrame()

        // A full swap: up, the layers trade places, down again.
        pick(layers, true)
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertTrue(layers.photoShown)
        assertEquals(0f, layers.veil.value, 0f)

        // Model picked, then Photo again while the veil is part way up (150 ms rise): the swap is
        // cancelled with nothing to trade, and the veil still goes.
        pick(layers, false)
        rule.mainClock.advanceTimeBy(MID_RISE_MS)
        val midway = layers.veil.value
        assertTrue("veil part way up, was $midway", midway > 0f && midway < VEIL_PEAK)
        pick(layers, true)
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        assertTrue(layers.photoShown)
        assertEquals(0f, layers.veil.value, 0f)
    }

    @Test
    fun `a growing Photo layer is first shown without the swap's veil`() {
        val layers = ResultLayers(offered = true, photoFirst = true)
        assertEquals(0f, layers.grown, 0f)
        rule.mainClock.autoAdvance = false
        rule.setContent { LaunchedEffect(layers) { layers.swapUnderVeil() } }
        rule.mainClock.advanceTimeByFrame()
        val splats = SplatCloud(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        rule.runOnUiThread { Snapshot.withMutableSnapshot { layers.splats = splats } }
        rule.mainClock.advanceTimeBy(MID_RISE_MS)
        assertTrue(layers.photoShown)
        assertEquals(0f, layers.veil.value, 0f)

        // From then on it swaps as any layer: Model and back, under the veil.
        pick(layers, false)
        rule.mainClock.advanceTimeBy(MID_RISE_MS)
        assertTrue(layers.veil.value > 0f)
    }

    @Test
    fun `a scan whose model is read back from its cache still offers Photo, built from its own surfels`() {
        // #4896: reopened from the cache, the scan has its model and its recording but no model
        // source; the splats need only the surfels, so Photo is offered and built from them.
        val surfels = DenseCloud(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            colors = IntArray(3) { 0xFF808080.toInt() },
            normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        )
        assertFalse("nothing to splat: no Photo", photoOffered(null, null))
        assertTrue(photoOffered(null, surfels))
        // Photo is offered in debug builds only: testReleaseUnitTest would wait for splats never built.
        assumeTrue(BuildConfig.DEBUG)
        val qa = DemoSettings.qaMode
        DemoSettings.qaMode = true
        try {
            lateinit var layers: ResultLayers
            rule.setContent { layers = rememberResultLayers(source = null, surfels = surfels, key = "cached", settled = true) }
            rule.waitUntil(WAIT_MS) { layers.splats != null || layers.unavailable }
            assertTrue("Photo offered without a model source", layers.offered)
            assertEquals(3, layers.splats?.count)
        } finally {
            DemoSettings.qaMode = qa
        }
    }

    @Test
    fun `a captured scan arrives whole instead of regrowing from an empty result`() {
        val cloud = DenseCloud(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            colors = IntArray(3) { 0xFF808080.toInt() },
            normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        )
        val prepared = SplatFaces.of(SurfelSplats.ofPlace(cloud))
        lateinit var layers: ResultLayers
        rule.setContent {
            layers = rememberResultLayers(null, cloud, "just captured", settled = false,
                photoFirst = true, prebuilt = prepared)
        }
        rule.waitForIdle()
        assertTrue(layers.photoShown)
        assertTrue(layers.faces === prepared)
        assertEquals(1f, layers.grown, 0f)
        assertEquals(0f, layers.veil.value, 0f)
    }

    @Test
    fun `outside QA mode there is no switch`() {
        rule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                ResultLayerPicker(ResultLayers(offered = false), plan = false, ready = true, onPlan = {})
            }
        }
        rule.onNodeWithTag(RESULT_LAYERS_TAG).assertDoesNotExist()
    }

    /** A pick as a tap makes it: applied at once, so the swap sees it on the next frame. */
    private fun pick(layers: ResultLayers, photo: Boolean) {
        rule.runOnUiThread { Snapshot.withMutableSnapshot { layers.photoPicked = photo } }
    }

    private companion object {
        const val SETTLE_MS = 2_000L
        const val MID_RISE_MS = 80L
        const val WAIT_MS = 5_000L
    }
}
