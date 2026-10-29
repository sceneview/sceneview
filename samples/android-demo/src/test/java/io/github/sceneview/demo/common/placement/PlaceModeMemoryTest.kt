package io.github.sceneview.demo.common.placement

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #4531: from Free pose or One call, "Place" in the mode selector went back to the model
 * picker, with the catalogue's first row armed, instead of reopening the Place session with
 * the model it had. Pure JVM: the rules the `ar-placement` card's host applies on a switch.
 */
class PlaceModeMemoryTest {

    @Test
    fun `a first visit opens on the chooser`() {
        assertEquals(PlacementFlowPhase.CHOOSING, placeModeOpeningPhase(visitedOtherMode = false))
        assertEquals(PlacementFlowPhase.CHOOSING, PlaceModeMemory().openingPhase())
    }

    @Test
    fun `switching back from another mode opens the camera, not the picker`() {
        assertEquals(PlacementFlowPhase.PLACING, placeModeOpeningPhase(visitedOtherMode = true))
        assertEquals(PlacementFlowPhase.PLACING, PlaceModeMemory(visitedOtherMode = true).openingPhase())
    }

    @Test
    fun `the model Place had armed is armed again after the switch`() {
        assertEquals("chair", placeModeArmedId(handoffId = null, rememberedId = "chair", defaultId = "first"))
    }

    @Test
    fun `with nothing remembered the catalogue's first row is armed, as on a first visit`() {
        assertEquals("first", placeModeArmedId(handoffId = null, rememberedId = null, defaultId = "first"))
    }

    @Test
    fun `a View in AR handoff still names the model`() {
        assertEquals("helmet", placeModeArmedId(handoffId = "helmet", rememberedId = "chair", defaultId = "first"))
    }

    @Test
    fun `the memory survives a configuration change`() {
        val memory = PlaceModeMemory(modelId = "streamed:abc", wallMode = true, visitedOtherMode = true)
        val saver = PlaceModeMemory.Saver
        val saved = with(saver) { SaverScope { true }.save(memory) }
        assertNotNull(saved)
        val restored = saver.restore(saved!!)!!
        assertEquals("streamed:abc", restored.modelId)
        assertEquals(true, restored.wallMode)
        assertEquals(PlacementFlowPhase.PLACING, restored.openingPhase())
    }

    @Test
    fun `an unarmed memory saves and restores too`() {
        val saver = PlaceModeMemory.Saver
        val saved = with(saver) { SaverScope { true }.save(PlaceModeMemory()) }
        val restored = saver.restore(saved!!)!!
        assertEquals(null, restored.modelId)
        assertEquals(PlacementFlowPhase.CHOOSING, restored.openingPhase())
    }
}
