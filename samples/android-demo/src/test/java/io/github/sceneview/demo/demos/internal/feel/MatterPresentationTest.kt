package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MatterPresentationTest {
    @Test fun outgoingClearsBeforeIncomingRisesAndShadowFadesThrough() {
        assertEquals(0f, MatterTransition.outgoingTravel(0f), 0f)
        assertEquals(1f, MatterTransition.outgoingTravel(.4f), 0f)
        assertEquals(1f, MatterTransition.incomingTravel(.4f), 0f)
        assertEquals(0f, MatterTransition.incomingTravel(.82f), .00001f)
        assertEquals(1f, MatterTransition.shadow(0f), 0f)
        assertEquals(0f, MatterTransition.shadow(.4f), 0f)
        assertEquals(1f, MatterTransition.shadow(1f), 0f)
        for (kind in FeelMaterial.entries) {
            assertEquals(-.14f, MatterTransition.height(kind, .4f, true), .00001f)
            assertEquals(-1.05f, MatterTransition.height(kind, .4f, false), .00001f)
            assertEquals(0f, MatterTransition.height(kind, 1f, false), .00001f)
            assertTrue(MatterTransition.height(kind, .90f, false) > 0f)
        }
    }

    @Test fun gelWobblesFoamSettlesSlowlyAndRigidSamplesHaveNoShaderDent() {
        assertTrue(MatterTransition.settleDepth(FeelMaterial.Gel, .10f) < 0f)
        assertTrue(MatterTransition.settleDepth(FeelMaterial.Gel, .20f) > 0f)
        assertTrue(MatterTransition.settleDepth(FeelMaterial.MemoryFoam, .20f) > 0f)
        for (kind in listOf(FeelMaterial.Gel, FeelMaterial.MemoryFoam)) {
            assertTrue(abs(MatterTransition.settleDepth(kind, 1f)) < .00002f)
        }
        for (kind in listOf(FeelMaterial.Marble, FeelMaterial.BrushedSteel)) {
            assertEquals(0f, MatterTransition.settleDepth(kind, .1f), 0f)
        }
    }

    @Test fun idleWaitsFourSecondsOrbitsWithinSixDegreesAndFreezesOnTouch() {
        val idle = MatterIdle()
        idle.advance(0, true)
        for (now in 0L..4000L step 16) {
            idle.advance(now, true)
            assertEquals(0f, idle.yawDegrees, 0f)
        }
        val yaws = (4000L..28000L step 16).map { idle.advance(it, true); idle.yawDegrees }
        assertTrue(yaws.all { it in -6f..6f })
        assertTrue(yaws.max() > 5.99f && yaws.min() < -5.99f)
        idle.advance(29000, true)
        val frozen = idle.yawDegrees
        idle.touch(29000, true)
        for (now in 29000L..35000L step 16) {
            idle.advance(now, true)
            assertEquals(frozen, idle.yawDegrees, 0f)
        }
        idle.touch(35000, false)
        idle.advance(39000, true)
        assertEquals(frozen, idle.yawDegrees, 0f)
        idle.advance(41000, true)
        assertTrue(idle.yawDegrees != frozen)
        idle.advance(41016, false)
        assertEquals(0f, idle.yawDegrees, 0f)
    }

    @Test fun orbitChangesEyeWithoutChangingTheSlabOrTouchCoordinateSystem() {
        val stage = MatterStage().apply { widthPx = 1080; heightPx = 1600; frame() }
        for (yaw in listOf(-6f, 0f, 6f)) {
            stage.orbit(yaw)
            val hit = stage.pointOnSlab(stage.eyeX, stage.eyeY, stage.eyeZ,
                .5f - stage.eyeX, -stage.eyeY, -.2f - stage.eyeZ)!!
            assertEquals(.5f, hit.first, .001f)
            assertEquals(-.2f, hit.second, .001f)
        }
    }
}
