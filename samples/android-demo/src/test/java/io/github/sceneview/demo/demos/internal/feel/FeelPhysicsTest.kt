package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelPhysicsTest {
    private fun simulate(material: FeelMaterial, pressure: Float, seconds: Float, hz: Int = 60, initial: Indentation = Indentation()): Indentation {
        var state = initial
        repeat((seconds * hz).toInt()) { state = stepIndentation(state, material, pressure, 1f / hz) }
        return state
    }

    @Test fun foamSinksDeeplyAndNeverHitsItsSafetyLimit() {
        val foam = simulate(FeelMaterial.Foam, 1f, 10f)
        assertEquals(FeelMaterial.MAX_FORCE_N / FeelMaterial.Foam.stiffness, foam.depthMm, 0.01f)
        assertTrue(foam.depthMm > 20f && foam.depthMm < FeelMaterial.Foam.stopMm)
        assertTrue(!foam.atStop(FeelMaterial.Foam))
    }

    @Test fun oakReachesFirmStopButLowForceStaysBelowIt() {
        val oak = simulate(FeelMaterial.Oak, 1f, 1f)
        assertEquals(0.35f, oak.depthMm, 0.0001f)
        assertTrue(oak.atStop(FeelMaterial.Oak))
        assertTrue(simulate(FeelMaterial.Oak, 0.2f, 1f).depthMm < 0.2f)
    }

    @Test fun steelNeverMovesEvenAtExcessPressure() {
        assertEquals(Indentation(), simulate(FeelMaterial.Steel, 20f, 5f))
    }

    @Test fun foamRecoveryIsSlowerThanOakAndReturnsToRest() {
        val foam = simulate(FeelMaterial.Foam, 1f, 5f)
        val oak = simulate(FeelMaterial.Oak, 1f, 5f)
        val foamReleased = simulate(FeelMaterial.Foam, 0f, 0.3f, initial = foam)
        val oakReleased = simulate(FeelMaterial.Oak, 0f, 0.3f, initial = oak)
        assertTrue(foamReleased.depthMm / foam.depthMm > 0.5f)
        assertTrue(oakReleased.depthMm / oak.depthMm < 0.05f)
        assertTrue(simulate(FeelMaterial.Foam, 0f, 10f, initial = foam).depthMm < 0.001f)
    }

    @Test fun refreshRatesGiveEquivalentResults() {
        for (material in FeelMaterial.entries) {
            val reference = simulate(material, 0.4f, 1f, 60)
            for (hz in listOf(30, 90, 120)) {
                assertEquals(reference.depthMm, simulate(material, 0.4f, 1f, hz).depthMm, 0.04f)
            }
        }
    }

    @Test fun hitchesAndInvalidInputCannotExplodeOrPenetrateStops() {
        for (material in FeelMaterial.entries) {
            var state = Indentation()
            repeat(100) { state = stepIndentation(state, material, 1f, 600f) }
            assertTrue(state.depthMm.isFinite() && state.depthMm in 0f..material.stopMm)
            assertEquals(state, stepIndentation(state, material, 1f, Float.NaN))
            assertEquals(state, stepIndentation(state, material, 1f, -1f))
            state = stepIndentation(state, material, Float.NaN, 0.02f)
            assertTrue(state.depthMm.isFinite())
        }
    }
}
