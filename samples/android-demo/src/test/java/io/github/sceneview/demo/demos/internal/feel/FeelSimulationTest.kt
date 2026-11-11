package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelSimulationTest {
    @Test fun dragHasMaterialSpecificLagIndependentOfFrameRateAndFreezesOnRelease() {
        for (kind in FeelMaterial.entries) {
            val positions = listOf(10L, 20L).map { step ->
                val simulation = FeelSimulation()
                simulation.begin(kind, 0f, 0f, 1f, 0f, 0L)
                simulation.advance(0L)
                simulation.move(1f, -.5f, 1f, 0f)
                for (now in step..100L step step) simulation.advance(now)
                val x = simulation.dentX[kind.ordinal]
                assertEquals(-x * .5f, simulation.dentZ[kind.ordinal], .0001f)
                if (kind.rigid) assertEquals(1f, x, 0f)
                else assertTrue("${kind.name}: $x", x in .5f.. .99f)
                simulation.release(100L)
                simulation.advance(116L)
                assertEquals(x, simulation.dentX[kind.ordinal], 0f)
                x
            }
            assertEquals(kind.name, positions[0], positions[1], .00001f)
        }
    }

    @Test fun draggedFoamAndVelvetKeepABoundedTrailWhichSurvivesReleaseAndFades() {
        for (kind in listOf(FeelMaterial.MemoryFoam, FeelMaterial.Velvet)) {
            val simulation = FeelSimulation()
            val i = kind.ordinal
            simulation.begin(kind, -2f, 0f, 1f, 0f, 0L)
            for (now in 0L..2000L step 10L) simulation.advance(now)
            assertTrue(simulation.trails[i].indices.step(4).all { simulation.trails[i][it + 2] == 0f })
            for (now in 2010L..6000L step 10L) {
                val x = -2f + ((now - 2000L) % 2000L) / 500f
                simulation.move(x, 0f, 1f, 0f)
                simulation.advance(now)
            }
            val trail = simulation.trails[i]
            assertEquals(8 * 4, trail.size)
            val strengths = trail.indices.step(4).map { it + 2 }
            assertTrue(strengths.all { trail[it] in 0f..1f })
            assertTrue(strengths.count { trail[it] > 0f } >= 4)
            simulation.release(6000L)
            val peak = strengths.maxOf { trail[it] }
            for (now in 6010L..7000L step 10L) simulation.advance(now)
            assertTrue(strengths.maxOf { trail[it] } in .1f..peak)
            for (now in 7010L..11000L step 10L) simulation.advance(now)
            assertTrue(strengths.all { trail[it] == 0f })
            assertTrue(trail === simulation.trails[i])
        }
    }

    @Test fun reprojectedStationaryFingerDoesNotPaintATrail() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.MemoryFoam, 0f, 0f, 1f, 0f, 0L)
        for (now in 0L..3000L step 10L) {
            simulation.fingerZ[0] = now / 3000f
            simulation.advance(now)
        }
        assertTrue(simulation.trails[0].indices.step(4).all { simulation.trails[0][it + 2] == 0f })
    }

    @Test fun gelReleaseWaveCapturesTheLaggedContactAndNewPressCancelsIt() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.Gel, -.5f, .2f, 1f, 0f, 0L)
        for (now in 0L..2000L step 10L) simulation.advance(now)
        simulation.move(.5f, .2f, 1f, 0f)
        simulation.advance(2010L)
        simulation.release(2010L)
        assertEquals(simulation.dentX[1], simulation.gelReleaseX, 0f)
        assertTrue(simulation.gelReleaseDepth > .3f)
        simulation.release(2100L)
        assertEquals(2010L, simulation.gelReleasedAt)
        simulation.begin(FeelMaterial.Gel, 1f, 0f, 1f, 0f, 2200L)
        assertEquals(Long.MIN_VALUE, simulation.gelReleasedAt)
    }

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
        val tails = (3000L..13000L step 16).mapNotNull { simulation.advance(it) }
        assertEquals(1, tails.size)
        assertEquals(FeelPrimitive.LowTick, tails.single().pulses.single().kind)
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

    @Test fun gelReleaseHapticsFollowTheSamePadStateAsTheShaderAndSelectionCancelsThem() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.Gel, 0f, 0f, 1f, 0f, 0)
        for (now in 0L..4000L step 16) simulation.advance(now)
        simulation.release(4000)
        var count = 0
        var old = simulation.pads[FeelMaterial.Gel.ordinal]
        for (now in 4016L..7000L step 16) {
            val effect = simulation.advance(now)
            val state = simulation.pads[FeelMaterial.Gel.ordinal]
            if (effect != null) {
                count++
                assertEquals(FeelPrimitive.LowTick, effect.pulses.single().kind)
                assertTrue(old.velocity < 0f && state.velocity >= 0f)
                assertEquals(.65f * kotlin.math.abs(minOf(old.depthMm, state.depthMm)) / 6.15f,
                    effect.pulses.single().scale, .00001f)
            }
            old = state
        }
        assertEquals(3, count)
        simulation.begin(FeelMaterial.Gel, 0f, 0f, 1f, 0f, 7100)
        for (now in 7100L..11100L step 16) simulation.advance(now)
        simulation.release(11100)
        simulation.select(FeelMaterial.Marble, 11110)
        for (now in 11120L..14500L step 16) assertNull(simulation.advance(now))
    }

    @Test fun realMovesBrushLeatherOnBothAxesButReprojectionDoesNot() {
        for (alongX in listOf(false, true)) {
            val simulation = FeelSimulation()
            simulation.begin(FeelMaterial.Leather, 0f, 0f, 1f, 0f, 0)
            assertEquals(FeelPrimitive.Tick, simulation.advance(0)?.pulses?.single()?.kind)
            var grains = 0
            for (now in 10L..500L step 10) {
                val travel = now / 500f
                simulation.move(if (alongX) travel else 0f, if (alongX) 0f else travel, 1f, 0f)
                simulation.advance(now)?.let { grains++ }
            }
            assertTrue(grains > 0)
            // Reprojecting a stationary finger is not physical travel.
            for (now in 510L..1000L step 10) {
                simulation.fingerX[FeelMaterial.Leather.ordinal] += .005f
                assertNull(simulation.advance(now))
            }
        }
    }
}
