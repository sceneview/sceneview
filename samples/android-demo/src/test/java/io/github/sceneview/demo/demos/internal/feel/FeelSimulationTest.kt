package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelSimulationTest {
    @Test fun lightSensorPressureMakesAWideVisibleFoamBowlAndStillRecoversSlowly() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.MemoryFoam, 0f, 0f, .10f, 0f, 0L)
        simulation.move(0f, 0f, .20f, 0f)
        simulation.move(0f, 0f, .12f, 0f)
        for (now in 0L..2500L step 10) simulation.advance(now)
        val depth = simulation.pads[0].depthMm
        assertTrue("light pressure remains a small physical measurement", depth in 2f..3f)
        val visible = MatterSurfaces.visualDepth(depth, FeelMaterial.MemoryFoam)
        assertTrue("light pressure gives a deep visual bowl", visible > FeelSimulation.PAD_THICKNESS * .35f)
        assertTrue(FeelMaterial.MemoryFoam.radius > 1.2f)
        simulation.release(2500L)
        for (now in 2510L..2630L step 10) simulation.advance(now)
        assertEquals(visible, MatterSurfaces.visualDepth(simulation.pads[0].depthMm, FeelMaterial.MemoryFoam), .0001f)
        for (now in 2640L..3200L step 10) simulation.advance(now)
        assertTrue(MatterSurfaces.visualDepth(simulation.pads[0].depthMm, FeelMaterial.MemoryFoam) > .10f)
        for (now in 3210L..13000L step 10) simulation.advance(now)
        assertTrue(MatterSurfaces.visualDepth(simulation.pads[0].depthMm, FeelMaterial.MemoryFoam) < .003f)
    }

    @Test fun stationaryFingerRampsAndReleaseLetsThePadRecover() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.MemoryFoam, 0f, 0f, 1f, 0f, 0)
        for (time in 0L..3000L step 16) simulation.advance(time)
        assertTrue(simulation.pads[0].depthMm > 15f)
        simulation.release(3000)
        for (time in 3000L..13000L step 16) assertNull(simulation.advance(time))
        assertTrue(simulation.pads[0].depthMm < 0.001f)
    }

    @Test fun eachGestureReevaluatesSensorsAndPreviousPadStillRecovers() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.MemoryFoam, 0f, 0f, 0.2f, 0f, 0)
        simulation.move(0f, 0f, 0.4f, 0f)
        simulation.move(0f, 0f, 0.8f, 0f)
        for (time in 0L..3000L step 16) simulation.advance(time)
        assertEquals(PressureSource.Pressure, simulation.inferred.source)
        val oldDepth = simulation.pads[0].depthMm
        simulation.begin(FeelMaterial.Leather, 0f, 0f, 1f, 0f, 3000)
        for (time in 3000L..6000L step 16) simulation.advance(time)
        assertEquals(PressureSource.Duration, simulation.inferred.source)
        assertTrue(simulation.pads[0].depthMm < oldDepth)
        assertEquals(FeelMaterial.Leather.stopMm, simulation.pads[FeelMaterial.Leather.ordinal].depthMm, 0.0001f)
    }

    @Test fun hitRegionsRejectOutsideOfEachLocalPad() {
        assertTrue(FeelSimulation.contains(0f, 0f))
        assertTrue(FeelSimulation.contains(FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_LENGTH))
        assertTrue(!FeelSimulation.contains(FeelSimulation.PAD_HALF_WIDTH + 0.01f, 0f))
        assertTrue(!FeelSimulation.contains(0f, FeelSimulation.PAD_HALF_LENGTH + 0.01f))
    }

    @Test fun foamRetainsItsDentBrieflyBeforeRecovery() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.MemoryFoam, 0f, 0f, 1f, 0f, 0)
        for (time in 0L..2000L step 10) simulation.advance(time)
        val dent = simulation.pads[0].depthMm
        assertTrue(dent > 17f)
        simulation.release(2000)
        for (time in 2010L..2130L step 10) simulation.advance(time)
        assertEquals(dent, simulation.pads[0].depthMm, 0.0001f)
        for (time in 2140L..3000L step 10) simulation.advance(time)
        assertTrue(simulation.pads[0].depthMm in 0.1f..dent)
    }

    @Test fun openingLoadsOnlyFoamAndUsesTheSameSpringWithoutHaptics() {
        val simulation = FeelSimulation(openingEnabled = true)
        for (time in 0L..1200L step 10) assertNull(simulation.advance(time))
        assertTrue(simulation.pads[0].depthMm > 10f)
        assertEquals(0f, simulation.pads[1].depthMm, 0f)
        assertEquals(0f, simulation.pads[2].depthMm, 0f)
        assertTrue(simulation.opening.visible)
        assertEquals(0f, simulation.opening.heightAbovePad, 0f)
        for (time in 1210L..2000L step 10) assertNull(simulation.advance(time))
        assertTrue(simulation.opening.finished)
        assertTrue(simulation.pads[0].depthMm > 0f)
        for (time in 2010L..13000L step 10) simulation.advance(time)
        assertTrue(simulation.pads[0].depthMm < 0.001f)
    }

    @Test fun touchCancelsTheOpeningAndCannotLeaveAHiddenLoad() {
        val simulation = FeelSimulation(openingEnabled = true)
        for (time in 0L..600L step 10) simulation.advance(time)
        simulation.begin(FeelMaterial.BrushedSteel, 0f, 0f, 1f, 0f, 610)
        assertTrue(simulation.opening.finished)
        assertEquals(0f, simulation.opening.loadKg, 0f)
        val oldDepth = simulation.pads[0].depthMm
        for (time in 610L..1610L step 10) simulation.advance(time)
        assertTrue(simulation.pads[0].depthMm < oldDepth)
        assertEquals(FeelMaterial.BrushedSteel, simulation.active)
    }

    @Test fun onlyTransverseRealMovesProduceLeatherGrain() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.Leather, 0f, 0f, 1f, 0f, 0)
        assertEquals(FeelPulseKind.Click, simulation.advance(0)?.kind)
        simulation.move(0f, 0.5f, 1f, 0f)
        assertNull(simulation.advance(200))
        simulation.move(6f * FeelMaterial.WORLD_PER_MM, 0.5f, 1f, 0f)
        assertEquals(FeelPulseKind.LowTick, simulation.advance(400)?.kind)
        // Reprojecting a stationary finger is not physical travel.
        simulation.fingerX[FeelMaterial.Leather.ordinal] += 0.5f
        assertNull(simulation.advance(600))
    }
}
