package io.github.sceneview.camera

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CameraAnimator]: frame stepping, exact landing, one callback, the dt cap and interrupt velocity. */
class CameraAnimatorTest {

    private val home = CameraShot(Position(0f, 0f, 0f), 35f, 32f, 3f)
    private val plan = home.copy(elevationDegrees = 84f)

    @Test
    fun restIsStill() {
        val animator = CameraAnimator(home)
        assertTrue(!animator.isPlaying)
        assertEquals(home, animator.advance(1f / 60f))
        assertEquals(CameraShotVelocity.Zero, animator.velocity)
        assertEquals(CameraShotVelocity.Zero, animator.interrupt())
    }

    @Test
    fun flyToLandsExactlyAndCallsBackOnce() {
        val animator = CameraAnimator(home)
        var finished = 0
        animator.flyTo(plan, durationSeconds = 0.5f) { finished++ }
        assertTrue(animator.isPlaying)
        var frames = 0
        while (animator.isPlaying) {
            animator.advance(1f / 60f)
            frames++
            assertTrue(frames < 1000)
        }
        assertEquals(plan, animator.shot)
        assertEquals(1, finished)
        assertEquals(30, frames)
        animator.advance(1f / 60f)
        assertEquals(1, finished)
        assertEquals(0f, animator.elapsed, 0f)
    }

    @Test
    fun playStartsOnThePathsFirstShotAndEndsOnItsLast() {
        val animator = CameraAnimator(home)
        val reveal = CameraPath.reveal(home, durationSeconds = 1f)
        animator.play(reveal)
        assertEquals(reveal.start, animator.shot)
        var guard = 0
        while (animator.isPlaying && guard++ < 1000) animator.advance(1f / 30f)
        assertEquals(home.azimuthDegrees, animator.shot.azimuthDegrees, 1e-3f)
        assertEquals(home.distance, animator.shot.distance, 1e-3f)
    }

    @Test
    fun sameLandingAt30And120Hz() {
        val slow = CameraAnimator(home).apply { flyTo(plan, durationSeconds = 0.7f) }
        val fast = CameraAnimator(home).apply { flyTo(plan, durationSeconds = 0.7f) }
        // Step both to the same instant, 0.4 s in.
        repeat(12) { slow.advance(1f / 30f) }
        repeat(48) { fast.advance(1f / 120f) }
        assertEquals(slow.elapsed, fast.elapsed, 1e-4f)
        assertEquals(slow.shot.elevationDegrees, fast.shot.elevationDegrees, 1e-2f)
    }

    @Test
    fun aStalledFrameAdvancesByTheCapOnly() {
        val animator = CameraAnimator(home)
        animator.flyTo(plan, durationSeconds = 1f)
        animator.advance(2f)
        assertEquals(CameraAnimator.MAX_STEP_SECONDS, animator.elapsed, 1e-6f)
        assertTrue(animator.isPlaying)
        animator.advance(-1f)
        assertEquals(CameraAnimator.MAX_STEP_SECONDS, animator.elapsed, 1e-6f)
        animator.advance(Float.NaN)
        assertEquals(CameraAnimator.MAX_STEP_SECONDS, animator.elapsed, 1e-6f)
    }

    @Test
    fun interruptKeepsTheShotAndReturnsTheVelocity() {
        val animator = CameraAnimator(home)
        val tour = CameraPath.tour(home, durationSeconds = 8f)
        animator.play(tour)
        repeat(30) { animator.advance(1f / 60f) }
        val onScreen = animator.shot
        val velocity = animator.interrupt()
        assertTrue(!animator.isPlaying)
        assertEquals(onScreen, animator.shot)
        // A one-lap 8 s tour turns at 45°/s.
        assertEquals(45f, velocity.azimuthDegreesPerSecond, 1.5f)
        assertEquals(CameraShotVelocity.Zero, animator.velocity)
        // A finished callback is not fired by an interrupt.
        var finished = 0
        animator.flyTo(plan, durationSeconds = 1f) { finished++ }
        animator.advance(0.02f)
        animator.interrupt()
        assertEquals(0, finished)
    }

    @Test
    fun flyToStartsFromWhereTheCameraIs() {
        val animator = CameraAnimator(home)
        animator.flyTo(plan, durationSeconds = 1f)
        repeat(20) { animator.advance(1f / 60f) }
        val midway = animator.shot
        animator.flyTo(home, durationSeconds = 1f)
        assertEquals(midway, animator.shot)
        assertEquals(midway, animator.advance(0f))
        assertEquals(0f, animator.elapsed, 0f)
    }

    @Test
    fun snapToStopsEverything() {
        val animator = CameraAnimator(home)
        var finished = 0
        animator.flyTo(plan) { finished++ }
        animator.snapTo(plan)
        assertTrue(!animator.isPlaying)
        assertEquals(plan, animator.shot)
        assertEquals(0, finished)
    }
}
