package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FeelPhysicsTest {
    private fun simulate(material: FeelMaterial, pressure: Float, seconds: Float, hz: Int = 60,
                         initial: Indentation = Indentation()): Indentation {
        var state = initial
        repeat((seconds * hz).toInt()) { state = stepIndentation(state, material, pressure, 1f / hz) }
        return state
    }

    @Test fun samplesAreOrderedByFirmnessAndSettleUnderLoadThenRecover() {
        assertEquals(6, FeelMaterial.entries.size)
        assertTrue(FeelMaterial.entries.zipWithNext().all { (a, b) -> a.stiffness < b.stiffness })
        for (kind in FeelMaterial.entries) {
            val held = simulate(kind, 1f, 10f)
            val expected = minOf(FeelMaterial.MAX_FORCE_N / kind.stiffness, kind.stopMm)
            assertEquals(kind.name, expected, held.depthMm, 0.01f)
            assertEquals(kind.name, 0f, held.velocity, 0.01f)
            val released = simulate(kind, 0f, 12f, initial = held)
            assertEquals(kind.name, 0f, released.depthMm, 0.001f)
            assertEquals(kind.name, 0f, released.velocity, 0.001f)
        }
    }

    @Test fun rigidSamplesStayAtZeroEvenWithExcessPressureAndNonzeroInitialState() {
        for (kind in listOf(FeelMaterial.Marble, FeelMaterial.BrushedSteel)) {
            assertEquals(Indentation(), simulate(kind, 20f, 5f, initial = Indentation(8f, 5f)))
            assertEquals(0f, MatterSurfaces.visualDepth(24f, kind), 0f)
        }
    }

    @Test fun memoryFoamKeepsItsSlowRecoveryWhileSkinRecoversSmoothly() {
        for (kind in listOf(FeelMaterial.MemoryFoam, FeelMaterial.Velvet, FeelMaterial.Leather)) {
            val held = simulate(kind, 1f, 5f)
            val released = simulate(kind, 0f, 0.3f, initial = held)
            if (kind == FeelMaterial.MemoryFoam) assertTrue(released.depthMm / held.depthMm > .5f)
            else assertTrue(released.depthMm / held.depthMm < .2f)
            var state = held
            repeat(600) {
                val next = stepIndentation(state, kind, 0f, 1f / 60f)
                assertTrue(next.depthMm in 0f..state.depthMm)
                state = next
            }
        }
    }

    @Test fun gelOvershootsRestRingsForSeveralCyclesThenSettles() {
        val kind = FeelMaterial.Gel
        var state = simulate(kind, 0.7f, 5f)
        var crossings = 0
        var minimum = 0f
        var maximumAfterFirstCrossing = 0f
        repeat(360) {
            val next = stepIndentation(state, kind, 0f, 1f / 60f)
            if (next.depthMm * state.depthMm < 0f) crossings++
            minimum = minOf(minimum, next.depthMm)
            if (crossings >= 1) maximumAfterFirstCrossing = maxOf(maximumAfterFirstCrossing, next.depthMm)
            state = next
        }
        assertTrue(minimum < -1f)
        assertTrue(maximumAfterFirstCrossing > .5f)
        assertTrue(crossings >= 4)
        assertTrue(abs(state.depthMm) < .001f && abs(state.velocity) < .001f)
        assertTrue(MatterSurfaces.visualDepth(minimum, kind) < 0f)
    }

    @Test fun refreshRatesGiveEquivalentResults() {
        for (kind in FeelMaterial.entries) {
            val reference = simulate(kind, .4f, 1f, 60)
            for (hz in listOf(30, 90, 120)) {
                assertEquals(kind.name, reference.depthMm, simulate(kind, .4f, 1f, hz).depthMm, .05f)
            }
        }
    }

    @Test fun hitchesInvalidInputAndDisplayAmplificationCannotInvertTheSlab() {
        for (kind in FeelMaterial.entries) {
            var state = Indentation()
            repeat(100) { state = stepIndentation(state, kind, 1f, 600f) }
            assertTrue(state.depthMm.isFinite() && state.depthMm in 0f..kind.stopMm)
            assertEquals(state, stepIndentation(state, kind, 1f, Float.NaN))
            assertEquals(state, stepIndentation(state, kind, 1f, -1f))
            assertTrue(stepIndentation(state, kind, Float.NaN, .02f).depthMm.isFinite())
            assertTrue(MatterSurfaces.visualDepth(1000f, kind) < FeelSimulation.PAD_THICKNESS)
        }
    }
}
