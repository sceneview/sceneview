package io.github.sceneview.demo.common.placement

import com.google.android.filament.gltfio.FilamentAsset
import io.github.sceneview.model.ModelInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Compose-side holder, driven the way [TapToPlaceExperience] and the hosts drive it —
 * the two P1s of the Codex review of #3766, pinned as regressions.
 */
class TapToPlaceStateTest {

    private val sofa = PlacementSpec(assetLocation = "models/sofa.glb", displayName = "Sofa")
    private val chair = PlacementSpec(assetLocation = "file:///cache/chair.glb", displayName = "Chair")

    @Test
    fun `reset reloads the selected model before each new placement`() {
        val state = TapToPlaceState()
        state.modelInstance = modelInstanceWithoutJni()
        assertTrue(state.offerAsset(state.controller.selectModel(), sofa))
        assertEquals(FrameEffect.PLACE, state.controller.onFrame(FrameInput(0L, true, surfaceAvailable = true)))

        repeat(5) { reset ->
            val retryBefore = state.assetRetry
            val now = (reset + 1) * 100L
            state.resetPlacement(nowMillis = now)

            assertSame("Reset keeps the selected asset", sofa, state.spec)
            assertNull("the disposed node's Filament instance must not be reused", state.modelInstance)
            assertEquals("the experience must load a fresh instance", retryBefore + 1, state.assetRetry)
            assertEquals(PlacementPhase.SCANNING, state.phase)
            assertEquals(
                "a surface must not commit a placement before the replacement model arrives",
                FrameEffect.NONE,
                state.controller.onFrame(FrameInput(now, true, surfaceAvailable = true)) {
                    state.modelInstance != null
                },
            )
            assertEquals(reset + 1, state.controller.placementsCreated)

            // The experience reloads the same selection, then the next surface can place it.
            state.modelInstance = modelInstanceWithoutJni()
            assertTrue(state.offerAsset(state.controller.selectModel(), sofa))
            assertEquals(
                FrameEffect.PLACE,
                state.controller.onFrame(FrameInput(now + 16L, true, surfaceAvailable = true)) {
                    state.modelInstance != null
                },
            )
            assertEquals(reset + 2, state.controller.placementsCreated)
        }
    }

    @Test
    fun `leaving the camera and coming back places again with the same holder`() {
        val state = TapToPlaceState()
        assertTrue(state.offerAsset(state.controller.selectModel(), sofa))
        assertEquals(FrameEffect.PLACE, state.controller.onFrame(FrameInput(0L, true, surfaceAvailable = true)))

        // Back to the chooser: `clearAll()` on a holder the host keeps alive.
        state.clearAll()
        assertNull(state.spec)
        assertEquals(PlacementPhase.INITIALIZING, state.phase)
        assertFalse(state.cameraReady)

        // Re-enter: the experience mints a new selection and offers the armed row again.
        val reoffered = state.offerAsset(state.controller.selectModel(), sofa)
        assertTrue("a dismissed holder must accept the next entry's offer", reoffered)
        assertTrue(state.controller.wantsSurface)
        assertEquals(FrameEffect.PLACE, state.controller.onFrame(FrameInput(16L, true, surfaceAvailable = true)))
        assertEquals(2, state.controller.placementsCreated)
    }

    @Test
    fun `a stale result from before leaving the camera is still refused`() {
        val state = TapToPlaceState()
        val stale = state.controller.selectModel()
        state.clearAll()
        state.controller.selectModel()
        assertFalse(state.offerAsset(stale, sofa))
    }

    @Test
    fun `picking a row that is still downloading withdraws the previous offer`() {
        val state = TapToPlaceState()
        assertTrue(state.offerAsset(state.controller.selectModel(), sofa))
        val previous = state.controller.ticket
        state.controller.onFrame(FrameInput(0L, true, surfaceAvailable = false))

        // The user arms a streamed row; its file is not here yet.
        state.holdForPendingAsset()
        assertNull("nothing is offered while the file downloads", state.spec)
        assertFalse(state.controller.wantsSurface)
        assertEquals(FrameEffect.NONE, state.controller.onFrame(FrameInput(16L, true, surfaceAvailable = true)))
        assertEquals("the sofa must not be placed under the chair's name", 0, state.controller.placementsCreated)
        assertFalse("the sofa's ticket is stale", state.controller.acceptsAsset(previous))

        // The file lands: the chair is offered and placed, once.
        assertTrue(state.offerAsset(state.controller.selectModel(), chair))
        assertEquals(chair, state.spec)
        assertEquals(FrameEffect.PLACE, state.controller.onFrame(FrameInput(32L, true, surfaceAvailable = true)))
        assertEquals(1, state.controller.placementsCreated)
    }

    @Test
    fun `picking a downloading row while an object stands keeps the object`() {
        val state = TapToPlaceState()
        assertTrue(state.offerAsset(state.controller.selectModel(), sofa))
        state.controller.onFrame(FrameInput(0L, true, surfaceAvailable = true))

        state.holdForPendingAsset()
        assertEquals("the standing spec is kept until the swap lands", sofa, state.spec)
        assertTrue(state.controller.hasPlacement)
        assertEquals(PlacementPhase.PLACED, state.controller.phase)
    }

    /** A reference-only token: no native methods or rendering are exercised by this state test. */
    private fun modelInstanceWithoutJni(): ModelInstance = ModelInstance::class.java
        .getDeclaredConstructor(FilamentAsset::class.java, Long::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .newInstance(null, 0L)
}
