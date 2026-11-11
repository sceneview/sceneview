package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterOpeningTest {
    @Test fun descentContactLiftAndHintFitInTwoSeconds() {
        val opening = MatterOpening(true)
        opening.advance(100)
        assertEquals(MatterOpening.TRAVEL, opening.heightAbovePad, 0.0001f)
        assertEquals(0f, opening.loadKg, 0f)
        opening.advance(450)
        assertEquals(MatterOpening.MARK_MASS_KG, opening.loadKg, 0f)
        assertEquals(0f, opening.heightAbovePad, 0f)
        opening.advance(1500)
        assertEquals(0f, opening.loadKg, 0f)
        assertTrue(opening.heightAbovePad > 0f)
        opening.advance(2100)
        assertTrue(opening.finished)
        assertFalse(opening.visible)
        opening.advance(5000)
        assertFalse(opening.visible)
    }

    @Test fun markSpansOneFifthOfBlockAndFirstPressPermanentlyDismissesHint() {
        assertEquals(FeelSimulation.PAD_HALF_WIDTH * 2f / 5f, MatterOpening.MARK_WIDTH, 0f)
        val simulation = FeelSimulation(openingEnabled = true)
        assertFalse(simulation.hasPressed)
        simulation.begin(FeelMaterial.BrushedSteel, 0f, 0f, 1f, 0f, 100L)
        simulation.move(1f, 0.2f, 1f, 0f)
        assertEquals(0f, simulation.contactX[FeelMaterial.BrushedSteel.ordinal], 0f)
        assertEquals(0f, simulation.contactZ[FeelMaterial.BrushedSteel.ordinal], 0f)
        simulation.release(150L)
        simulation.advance(3000L)
        assertTrue(simulation.hasPressed)
        assertTrue(simulation.opening.finished)
    }

    @Test fun reducedMotionAndQaNeverCreateALoad() {
        val opening = MatterOpening(false)
        for (now in 0L..3000L step 10) {
            opening.advance(now)
            assertTrue(opening.finished)
            assertFalse(opening.visible)
            assertEquals(0f, opening.loadKg, 0f)
        }
    }

    @Test fun cancelDuringEveryPhaseIsPermanent() {
        for (cancelAt in listOf(0L, 200L, 700L, 1400L)) {
            val opening = MatterOpening(true)
            opening.advance(0)
            opening.advance(cancelAt)
            opening.cancel()
            opening.advance(3000)
            assertTrue(opening.finished)
            assertFalse(opening.visible)
            assertEquals(0f, opening.loadKg, 0f)
        }
    }
}
