package io.github.sceneview.demo.demos.internal

import io.github.sceneview.camera.CameraAnimator
import io.github.sceneview.camera.CameraShot
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ResultFilmTest {
    private val home = CameraShot(
        target = Position(0.4f, 0.9f, -1.2f),
        azimuthDegrees = 35f,
        elevationDegrees = 30f,
        distance = 5f,
    )
    private val plan = home.copy(elevationDegrees = 80f, distance = 6.5f)
    private val film = ResultFilm.of(home, plan)

    @Test
    fun `the film is a short clip, 8 to 10 seconds`() {
        assertTrue("${film.durationSeconds} s", film.durationSeconds in 8f..10f)
        // Whole frames at the clip's rate: the recording stops on a frame, not between two.
        val frames = film.frameCount
        assertEquals(frames.toFloat() / ResultFilm.FPS, film.durationSeconds, 1f / ResultFilm.FPS)
    }

    @Test
    fun `the clip is 720p, at the film's rate, and stops on its own just after the film`() {
        val video = film.video
        assertEquals(720, video.maxShortEdge)
        assertEquals(ResultFilm.FPS, video.frameRate)
        assertTrue(video.maxDurationMillis > film.durationSeconds * 1000f)
        // A safety net only: the film plays in frame time, so it may run long on a slow device.
        assertTrue(video.maxDurationMillis >= film.durationSeconds * 2000f)
    }

    @Test
    fun `it opens on the reveal, lands on the framing, and ends on the plan`() {
        assertEquals(film.reveal.start, film.shotAt(0f))
        assertSame(home, film.shotAt(film.turnStart))
        val end = film.shotAt(film.durationSeconds)
        assertEquals(plan.elevationDegrees, end.elevationDegrees, 1e-3f)
        assertEquals(plan.distance, end.distance, 1e-3f)
        // Held: the last second of the clip is the plan, still.
        assertSame(end, film.shotAt(film.durationSeconds - ResultFilm.HOLD_SECONDS + 0.01f))
    }

    @Test
    fun `frame n is the same camera every time`() {
        val again = ResultFilm.of(home, plan)
        for (n in 0..film.frameCount) {
            val t = n.toFloat() / ResultFilm.FPS
            assertEquals(film.shotAt(t), again.shotAt(t))
        }
    }

    @Test
    fun `the camera never jumps from one frame to the next, seams included`() {
        var last = film.shotAt(0f).eye
        var worst = 0f
        for (n in 1..film.frameCount) {
            val eye = film.shotAt(n.toFloat() / ResultFilm.FPS).eye
            val step = sqrt(
                (eye.x - last.x) * (eye.x - last.x) + (eye.y - last.y) * (eye.y - last.y) + (eye.z - last.z) * (eye.z - last.z),
            )
            worst = maxOf(worst, step)
            last = eye
        }
        // A fifth of the framing's distance a frame at most: fast at the turn's middle, never a cut.
        assertTrue("worst step $worst m", worst < home.distance * MAX_STEP_SHARE)
        for (seam in listOf(film.turnStart, film.planStart)) {
            val before = film.shotAt(seam - 1e-4f).eye
            val after = film.shotAt(seam + 1e-4f).eye
            val gap = sqrt((after.x - before.x) * (after.x - before.x) + (after.y - before.y) * (after.y - before.y) + (after.z - before.z) * (after.z - before.z))
            assertTrue("gap $gap m at $seam s", gap < home.distance * 0.01f)
        }
    }

    @Test
    fun `at 15 fps the camera still lands on the plan, later than a wall-clock stop would allow`() {
        val animator = CameraAnimator(film.shotAt(0f))
        var landed = false
        film.play(animator) { landed = true }
        val frame = 1f / SLOW_FPS
        var wall = 0f
        var shot = animator.shot
        while (!landed && wall < film.durationSeconds * 3f) {
            shot = animator.advance(frame)
            wall += frame
        }
        assertTrue("never landed", landed)
        // Each step is capped at 50 ms, so at 15 fps the film runs late: a stop on the wall clock
        // (the film's length after the start) would cut the crane before the plan.
        assertTrue("landed at $wall s", wall + ResultFilm.HOLD_SECONDS > film.durationSeconds)
        assertEquals(plan.elevationDegrees, shot.elevationDegrees, 1e-2f)
        assertEquals(plan.distance, shot.distance, 1e-2f)
    }

    @Test
    fun `a take starts only once the reveal's wire is read`() {
        assertFalse(ResultFilm.canStart(wirePending = true))
        assertTrue(ResultFilm.canStart(wirePending = false))
    }

    @Test
    fun `a take runs through the film's seams, and drops on a touch or a stopped recording`() {
        val animator = CameraAnimator(film.shotAt(0f))
        var landed = false
        film.play(animator) { landed = true }
        var frames = 0
        while (!landed) {
            animator.advance(1f / ResultFilm.FPS)
            frames++
            // The camera keeps playing from one segment to the next: never a false abort.
            assertFalse("aborted at frame $frames", ResultFilm.aborts(landed, animator.isPlaying, recording = true))
            assertTrue("never landed", frames < film.frameCount * 2)
        }
        // Landed: held, not dropped, while it records; dropped once the recording stops.
        assertFalse(ResultFilm.aborts(landed = true, cameraPlaying = false, recording = true))
        assertTrue(ResultFilm.aborts(landed = true, cameraPlaying = false, recording = false))

        // A touch takes the camera over mid-film: interrupt() ends the move without landing.
        val touched = CameraAnimator(film.shotAt(0f))
        var touchedLanded = false
        film.play(touched) { touchedLanded = true }
        repeat(ResultFilm.FPS * 4) { touched.advance(1f / ResultFilm.FPS) }
        touched.interrupt()
        assertFalse(touchedLanded)
        assertTrue(ResultFilm.aborts(touchedLanded, touched.isPlaying, recording = true))
        // The app left the foreground: the recording stopped while the camera still plays.
        assertTrue(ResultFilm.aborts(landed = false, cameraPlaying = true, recording = false))
    }

    private fun assertSame(expected: CameraShot, actual: CameraShot) {
        assertEquals(expected.target.x, actual.target.x, 1e-3f)
        assertEquals(expected.target.y, actual.target.y, 1e-3f)
        assertEquals(expected.target.z, actual.target.z, 1e-3f)
        val turn = ((expected.azimuthDegrees - actual.azimuthDegrees) % 360f + 360f) % 360f
        assertEquals(0f, minOf(turn, 360f - turn), 1e-2f)
        assertEquals(expected.elevationDegrees, actual.elevationDegrees, 1e-3f)
        assertEquals(expected.distance, actual.distance, 1e-3f)
    }

    private companion object {
        const val MAX_STEP_SHARE = 0.2f
        const val SLOW_FPS = 15f
    }
}
