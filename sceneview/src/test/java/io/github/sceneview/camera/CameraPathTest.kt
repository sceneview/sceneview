package io.github.sceneview.camera

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** [CameraPath]: keyframes hit on time, C1 continuity across a knot, seamless loop, reveal and tour. */
class CameraPathTest {

    private val home = CameraShot(Position(0f, 0f, 0f), 35f, 32f, 3f)

    private fun threeKnots(easing: (Float) -> Float = CameraEasing.Linear) = CameraPath(
        keyframes = listOf(
            CameraKeyframe(CameraShot(Position(0f, 0f, 0f), 0f, 20f, 2f), 0f),
            CameraKeyframe(CameraShot(Position(1f, 0f, 0f), 60f, 40f, 3f), 1f),
            CameraKeyframe(CameraShot(Position(3f, 1f, 0f), 90f, 10f, 5f), 3f),
        ),
        easing = easing,
    )

    @Test
    fun emptyAndSinglePathsHold() {
        val empty = CameraPath(emptyList())
        assertNull(empty.start)
        assertEquals(0f, empty.durationSeconds, 0f)
        assertEquals(CameraShot(), empty.shotAt(1f))
        val single = CameraPath(listOf(CameraKeyframe(home, 2f)))
        assertEquals(home, single.shotAt(0f))
        assertEquals(home, single.shotAt(10f))
        assertTrue(single.isFinishedAt(0f))
    }

    @Test
    fun passesEachKeyframeAtItsTime() {
        val path = threeKnots()
        for (keyframe in path.keyframes) {
            val shot = path.shotAt(keyframe.seconds)
            assertShotClose(keyframe.shot, shot)
        }
        assertEquals(3f, path.durationSeconds, 0f)
        assertShotClose(path.keyframes.last().shot, path.shotAt(99f))
        assertTrue(path.isFinishedAt(3f))
        assertTrue(!path.isFinishedAt(2.99f))
    }

    @Test
    fun keyframesAreSortedByTime() {
        val path = CameraPath(
            listOf(CameraKeyframe(home.copy(distance = 9f), 2f), CameraKeyframe(home, 0f)),
        )
        assertEquals(home, path.start)
        assertEquals(9f, path.end!!.distance, 0f)
    }

    @Test
    fun velocityIsContinuousAcrossAKnot() {
        val path = threeKnots()
        val h = 1e-3f
        val knot = 1f
        // Finite-difference velocity just before and just after the middle keyframe.
        val before = CameraShotVelocity.between(path.shotAt(knot - 2 * h), path.shotAt(knot - h), h)
        val after = CameraShotVelocity.between(path.shotAt(knot + h), path.shotAt(knot + 2 * h), h)
        assertEquals(before.azimuthDegreesPerSecond, after.azimuthDegreesPerSecond, 0.5f)
        assertEquals(before.elevationDegreesPerSecond, after.elevationDegreesPerSecond, 0.5f)
        assertEquals(before.distancePerSecond, after.distancePerSecond, 0.05f)
        assertEquals(before.targetPerSecond.x, after.targetPerSecond.x, 0.05f)
        // And it is the Catmull-Rom tangent: the mean rate between the neighbours.
        val tangent = CameraShotVelocity.between(path.keyframes[0].shot, path.keyframes[2].shot, 3f)
        assertEquals(tangent.azimuthDegreesPerSecond, after.azimuthDegreesPerSecond, 0.5f)
    }

    @Test
    fun shortestArcUnwrapsAzimuths() {
        val path = CameraPath(
            listOf(
                CameraKeyframe(home.copy(azimuthDegrees = 350f), 0f),
                CameraKeyframe(home.copy(azimuthDegrees = 10f), 1f),
            ),
            easing = CameraEasing.Linear,
        )
        assertEquals(370f, path.keyframes[1].shot.azimuthDegrees, 1e-4f)
        assertEquals(360f, path.shotAt(0.5f).azimuthDegrees, 1e-3f)
        val long = CameraPath(path.keyframes.map { it.copy(shot = it.shot.copy()) }.let {
            listOf(it[0], CameraKeyframe(home.copy(azimuthDegrees = 10f), 1f))
        }, easing = CameraEasing.Linear, shortestArc = false)
        assertEquals(180f, long.shotAt(0.5f).azimuthDegrees, 1e-3f)
    }

    @Test
    fun revealStartsHighWideAndTurnedAndLandsOnHome() {
        val path = CameraPath.reveal(home)
        val start = path.start!!
        assertEquals(home.azimuthDegrees + CameraPath.REVEAL_TURN_DEGREES, start.azimuthDegrees, 1e-4f)
        assertEquals(CameraPath.REVEAL_START_ELEVATION, start.elevationDegrees, 1e-4f)
        assertEquals(home.distance * CameraPath.REVEAL_DISTANCE_FACTOR, start.distance, 1e-4f)
        assertEquals(home.target, start.target)
        assertEquals(CameraPath.REVEAL_SECONDS, path.durationSeconds, 0f)
        assertShotClose(home, path.shotAt(CameraPath.REVEAL_SECONDS))
        // Expressive: most of the way there by half time, never past home.
        val half = path.shotAt(CameraPath.REVEAL_SECONDS / 2)
        assertTrue(abs(half.azimuthDegrees - home.azimuthDegrees) < abs(CameraPath.REVEAL_TURN_DEGREES) * 0.35f)
        assertTrue(half.distance >= home.distance)
        assertTrue(half.distance <= start.distance)
    }

    @Test
    fun tourIsOneLapAtConstantSpeedAndLoopsSeamlessly() {
        val path = CameraPath.tour(home, durationSeconds = 8f)
        assertTrue(path.loop)
        assertEquals(8f, path.durationSeconds, 0f)
        assertEquals(home.azimuthDegrees + 360f, path.end!!.azimuthDegrees, 1e-4f)
        // Constant azimuth speed, 45°/s, everywhere including across the seam.
        val h = 1e-2f
        for (t in listOf(0.5f, 2f, 3.9f, 4.1f, 7.99f, 8.01f, 12f)) {
            val v = CameraShotVelocity.between(path.shotAt(t), path.shotAt(t + h), h)
            assertEquals("at $t", 45f, v.azimuthDegreesPerSecond, 1.5f)
        }
        // Wraps: the lap after is the same lap.
        assertEquals(
            CameraShot.normalizeDegrees(path.shotAt(1f).azimuthDegrees),
            CameraShot.normalizeDegrees(path.shotAt(9f).azimuthDegrees),
            1e-2f,
        )
        assertEquals(path.shotAt(1f).elevationDegrees, path.shotAt(9f).elevationDegrees, 1e-2f)
        // And the azimuth keeps counting, so there is no jump at the seam.
        assertEquals(path.shotAt(1f).azimuthDegrees + 360f, path.shotAt(9f).azimuthDegrees, 1e-2f)
        assertEquals(home.azimuthDegrees + 360f, path.shotAt(8f).azimuthDegrees, 1e-3f)
        // Rises at half-lap, back home at the seam.
        assertEquals(home.elevationDegrees + CameraPath.TOUR_RISE_DEGREES, path.shotAt(4f).elevationDegrees, 1e-2f)
        assertEquals(home.elevationDegrees, path.shotAt(0f).elevationDegrees, 1e-3f)
        assertTrue(!path.isFinishedAt(100f))
        assertEquals(home.distance, path.shotAt(3f).distance, 1e-3f)
    }

    @Test
    fun tourWithoutLoopFinishes() {
        val path = CameraPath.tour(home, durationSeconds = 4f, turns = 2f, loop = false)
        assertTrue(path.isFinishedAt(4f))
        assertEquals(home.azimuthDegrees + 720f, path.end!!.azimuthDegrees, 1e-3f)
    }

    @Test
    fun throughSpacesShotsEvenly() {
        val rooms = listOf(home, home.copy(target = Position(4f, 0f, 0f)), home.copy(target = Position(4f, 0f, 5f)))
        val path = CameraPath.through(rooms, secondsPerShot = 2f)
        assertEquals(4f, path.durationSeconds, 0f)
        // Expressive by default, and still on each shot at its declared second (easing per segment).
        assertShotClose(rooms[1], path.shotAt(2f))
        assertShotClose(rooms[2], path.shotAt(4f))
        assertEquals(home, path.start)
    }

    @Test
    fun easedPathReachesEveryKeyframeAtItsDeclaredSecond() {
        val shots = listOf(
            CameraShot(Position(0f, 0f, 0f), 0f, 20f, 2f),
            CameraShot(Position(2f, 0f, 1f), 90f, 45f, 4f),
            CameraShot(Position(5f, 1f, 0f), 140f, 10f, 3f),
        )
        val path = CameraPath(
            shots.mapIndexed { index, shot -> CameraKeyframe(shot, index * 2f) },
            easing = CameraEasing.Expressive,
        )
        assertShotClose(shots[0], path.shotAt(0f))
        assertShotClose(shots[1], path.shotAt(2f))
        assertShotClose(shots[2], path.shotAt(4f))
        // Expressive within a segment: well past half-way by half the segment's time.
        val mid = path.shotAt(1f)
        assertTrue(mid.azimuthDegrees > 45f && mid.azimuthDegrees < 90f)
    }

    @Test
    fun timeCountsFromTheFirstKeyframe() {
        val path = CameraPath(
            listOf(CameraKeyframe(home, 1f), CameraKeyframe(home.copy(distance = 6f), 3f)),
            easing = CameraEasing.Linear,
        )
        assertShotClose(home, path.shotAt(0f))
        // Finished at its duration, and on its last shot then: what CameraAnimator lands on.
        assertTrue(path.isFinishedAt(2f))
        assertShotClose(path.end!!, path.shotAt(2f))
    }

    @Test
    fun aFractionalLoopingTourClosesAtTheSeam() {
        for (turns in listOf(1.5f, 0.75f, 2.3f)) {
            val path = CameraPath.tour(home, durationSeconds = 9f, turns = turns)
            val end = path.end!!
            val start = path.start!!
            assertEquals("elevation at the seam, $turns turns", start.elevationDegrees, end.elevationDegrees, 1e-3f)
            assertEquals(start.distance, end.distance, 1e-4f)
            assertEquals(start.target, end.target)
            // No jump across the seam: just before and just after it are a frame's motion apart.
            val h = 1f / 120f
            val before = path.shotAt(9f - h)
            val after = path.shotAt(9f + h)
            assertEquals(
                "elevation across the seam, $turns turns",
                before.elevationDegrees,
                after.elevationDegrees,
                0.05f,
            )
            assertTrue(abs(after.azimuthDegrees - before.azimuthDegrees) < 2f)
            // It still rises in between.
            assertTrue((0..90).any { path.shotAt(it / 10f).elevationDegrees > home.elevationDegrees + 1f })
        }
    }

    private fun assertShotClose(expected: CameraShot, actual: CameraShot, tolerance: Float = 1e-3f) {
        assertEquals("azimuth", expected.azimuthDegrees, actual.azimuthDegrees, tolerance)
        assertEquals("elevation", expected.elevationDegrees, actual.elevationDegrees, tolerance)
        assertEquals("distance", expected.distance, actual.distance, tolerance)
        assertEquals("target.x", expected.target.x, actual.target.x, tolerance)
        assertEquals("target.y", expected.target.y, actual.target.y, tolerance)
        assertEquals("target.z", expected.target.z, actual.target.z, tolerance)
    }
}
