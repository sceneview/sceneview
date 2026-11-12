package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterOpeningTest {
    @Test fun onlySystemAnimationsAndRuntimeQaGateTheOpening() {
        assertTrue(MatterOpening.isAllowed(systemAnimations = true, qaMode = false))
        assertFalse(MatterOpening.isAllowed(systemAnimations = false, qaMode = false))
        assertFalse(MatterOpening.isAllowed(systemAnimations = true, qaMode = true))
        assertFalse(MatterOpening.isAllowed(systemAnimations = false, qaMode = true))
    }

    @Test fun loadingOrInitialMissingFocusDoesNotConsumeTheOpening() {
        val simulation = FeelSimulation(openingEnabled = true)
        for (now in 0L..5000L step 16) simulation.advance(now, startOpening = false)
        assertFalse(simulation.opening.finished)
        assertFalse(simulation.opening.visible)
        assertEquals(0f, simulation.pads[0].depthMm, 0f)
        simulation.advance(5100L, startOpening = true)
        assertTrue(simulation.opening.visible)
        assertEquals(MatterOpening.TRAVEL, simulation.opening.heightAbovePad, 0f)
    }

    @Test fun gravityImpactRestAndSlowLiftFitWithinTwoPointThreeSeconds() {
        val opening = MatterOpening(true)
        opening.advance(0)
        assertEquals(MatterOpening.TRAVEL, opening.heightAbovePad, 0f)
        opening.advance(100)
        val firstQuarterFall = MatterOpening.TRAVEL - opening.heightAbovePad
        opening.advance(200)
        assertTrue(MatterOpening.TRAVEL - opening.heightAbovePad > firstQuarterFall * 3.9f)
        assertTrue(opening.advance(400))
        assertEquals(MatterOpening.MARK_MASS_KG, opening.loadKg, 0f)
        assertFalse(opening.advance(416))
        opening.advance(460)
        assertTrue(opening.squash > .01f)
        opening.advance(520)
        assertEquals(12f, opening.impactDepthMm, .0001f)
        for (time in 520L until 920L step 16) {
            opening.advance(time)
            assertEquals(0f, opening.heightAbovePad, 0f)
            assertEquals(MatterOpening.MARK_MASS_KG, opening.loadKg, 0f)
        }
        opening.advance(920)
        assertEquals(0f, opening.loadKg, 0f)
        opening.advance(1370)
        assertEquals(MatterOpening.TRAVEL / 2f, opening.heightAbovePad, .0001f)
        opening.advance(1820)
        assertFalse(opening.visible)
        opening.advance(2220)
        assertTrue(opening.finished)
        assertTrue(MatterOpening.TOTAL_MILLIS <= 2300)
    }

    @Test fun impactThudsOnceMakesAWideDeepWellAndLeavesSlowMemory() {
        val simulation = FeelSimulation(openingEnabled = true)
        val effects = mutableListOf<FeelEffect>()
        for (now in 0L..912L step 16) simulation.advance(now)?.let(effects::add)
        assertEquals(listOf(FeelPrimitive.Thud), effects.map { it.pulses.single().kind })
        assertEquals(1f, effects.single().pulses.single().scale, 0f)
        assertTrue(MatterSurfaces.visualDepth(simulation.pads[0].depthMm, FeelMaterial.MemoryFoam) > .60f)
        assertEquals(MatterOpening.WELL_RADIUS, simulation.radius(FeelMaterial.MemoryFoam), 0f)
        val deep = simulation.pads[0].depthMm
        simulation.advance(928)
        for (now in 944L..1056L step 16) simulation.advance(now)
        assertEquals(deep, simulation.pads[0].depthMm, 0f)
        for (now in 1072L..1904L step 16) simulation.advance(now)
        assertTrue(simulation.pads[0].depthMm in .5f..deep)
        simulation.begin(FeelMaterial.MemoryFoam, 0f, 0f, .1f, 0f, 1920)
        assertEquals(FeelMaterial.MemoryFoam.radius, simulation.radius(FeelMaterial.MemoryFoam), 0f)
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
