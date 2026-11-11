package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelSimulationTest {
    @Test fun stationaryFingerRampsAndReleaseLetsThePadRecover() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.Foam, 0f, 0f, 1f, 0f, 0)
        for (time in 0L..3000L step 16) simulation.advance(time)
        assertTrue(simulation.pads[0].depthMm > 15f)
        simulation.release(3000)
        for (time in 3000L..13000L step 16) assertNull(simulation.advance(time))
        assertTrue(simulation.pads[0].depthMm < 0.001f)
    }

    @Test fun eachGestureReevaluatesSensorsAndPreviousPadStillRecovers() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.Foam, 0f, 0f, 0.2f, 0f, 0)
        simulation.move(0f, 0f, 0.4f, 0f)
        simulation.move(0f, 0f, 0.8f, 0f)
        for (time in 0L..3000L step 16) simulation.advance(time)
        assertEquals(PressureSource.Pressure, simulation.inferred.source)
        val oldDepth = simulation.pads[0].depthMm
        simulation.begin(FeelMaterial.Oak, 0f, 0f, 1f, 0f, 3000)
        for (time in 3000L..4000L step 16) simulation.advance(time)
        assertEquals(PressureSource.Duration, simulation.inferred.source)
        assertTrue(simulation.pads[0].depthMm < oldDepth)
        assertEquals(0.35f, simulation.pads[1].depthMm, 0.0001f)
    }

    @Test fun hitRegionsRejectTheGapsAndOutsideOfPads() {
        assertEquals(FeelMaterial.Foam, FeelSimulation.padAt(-3f, 0f))
        assertEquals(FeelMaterial.Oak, FeelSimulation.padAt(0f, 0f))
        assertEquals(FeelMaterial.Steel, FeelSimulation.padAt(3f, 0f))
        assertNull(FeelSimulation.padAt(-1.5f, 0f))
        assertNull(FeelSimulation.padAt(0f, 3f))
    }
}
