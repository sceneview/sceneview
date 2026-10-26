package io.github.sceneview.camera

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.Aabb
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * [CameraPosePath]: determinism, keyframes hit on time, bounds, the short rotation arc, the orbit
 * framing its box, travelling, dolly, and [CameraPath] as a [CameraTimeline].
 */
class CameraPosePathTest {

    private fun yaw(degrees: Float): Quaternion {
        val half = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        return Quaternion(0f, sin(half), 0f, cos(half))
    }

    private fun fourKnots(interpolation: CameraPathInterpolation = CameraPathInterpolation.CatmullRom) =
        CameraPosePath(
            keyframes = listOf(
                CameraPoseKeyframe(CameraPose(Position(0f, 1f, 4f), yaw(0f)), 0f),
                CameraPoseKeyframe(CameraPose(Position(2f, 1.5f, 3f), yaw(30f)), 1.5f),
                CameraPoseKeyframe(CameraPose(Position(3f, 1f, 0f), yaw(80f)), 2f, easing = CameraEasing.InOut),
                CameraPoseKeyframe(CameraPose(Position(1f, 2f, -3f), yaw(150f)), 4f),
            ),
            interpolation = interpolation,
        )

    @Test
    fun emptyAndSingleKeyframePathsHold() {
        val empty = CameraPosePath(emptyList())
        assertNull(empty.start)
        assertEquals(0f, empty.durationSeconds, 0f)
        assertEquals(CameraPose(), empty.poseAt(3f))

        val pose = CameraPose(Position(1f, 2f, 3f), yaw(40f))
        val single = CameraPosePath(listOf(CameraPoseKeyframe(pose, 2f)))
        assertEquals(0f, single.durationSeconds, 0f)
        for (t in floatArrayOf(-5f, 0f, 2f, 100f, Float.NaN)) assertPoseClose(pose, single.poseAt(t), 1e-6f)
        assertTrue(single.isFinishedAt(0f))
    }

    @Test
    fun reachesEveryKeyframeExactlyAtItsTime() {
        for (interpolation in CameraPathInterpolation.entries) {
            val path = fourKnots(interpolation)
            assertEquals(4f, path.durationSeconds, 0f)
            for (keyframe in path.keyframes) {
                val pose = path.poseAt(keyframe.seconds)
                assertEquals("$interpolation at ${keyframe.seconds}", keyframe.pose.position, pose.position)
                assertSameRotation(keyframe.pose.quaternion, pose.quaternion, 1e-6f)
            }
        }
    }

    @Test
    fun keyframeTimesCountFromTheFirstKeyframe() {
        val a = CameraPose(Position(0f, 0f, 1f))
        val b = CameraPose(Position(4f, 0f, 1f))
        val path = CameraPosePath(listOf(CameraPoseKeyframe(b, 7f), CameraPoseKeyframe(a, 5f)))
        assertEquals(2f, path.durationSeconds, 0f)
        assertEquals(a.position, path.start!!.position)
        assertEquals(b.position, path.poseAt(2f).position)
    }

    @Test
    fun samePoseForSameTimeWhateverTheCallOrder() {
        val path = fourKnots()
        val times = List(97) { it * 0.0437f - 0.3f }
        val forward = times.associateWith { path.poseAt(it) }
        val backward = times.reversed().associateWith { path.poseAt(it) }
        val shuffled = times.shuffled(java.util.Random(7)).associateWith { path.poseAt(it) }
        for (t in times) {
            assertEquals(forward.getValue(t), backward.getValue(t))
            assertEquals(forward.getValue(t), shuffled.getValue(t))
        }
    }

    @Test
    fun timesOutsideThePathClampOrWrap() {
        val path = fourKnots()
        assertEquals(path.start, path.poseAt(-1f))
        assertEquals(path.end, path.poseAt(4.0001f))
        assertEquals(path.end, path.poseAt(1e6f))
        assertTrue(path.isFinishedAt(4f))
        assertTrue(!path.isFinishedAt(3.99f))

        val looped = CameraPosePath(path.keyframes, loop = true)
        assertTrue(!looped.isFinishedAt(100f))
        for (t in floatArrayOf(0.25f, 1.7f, 3.1f)) {
            assertPoseClose(looped.poseAt(t), looped.poseAt(t + 4f), 1e-5f)
            assertPoseClose(looped.poseAt(t), looped.poseAt(t - 8f), 1e-5f)
        }
    }

    @Test
    fun rotationTakesTheShortArcWhenAKeyframeQuaternionHasTheOppositeSign() {
        // yaw(170°) and -yaw(190°) are close rotations (20° apart); written with opposite signs,
        // a naive interpolation would spin 340° the long way round.
        val q0 = yaw(170f)
        val q1 = -yaw(190f)
        assertTrue(dot(q0, q1) < 0f)
        val path = CameraPosePath(
            listOf(
                CameraPoseKeyframe(CameraPose(Position(), q0), 0f),
                CameraPoseKeyframe(CameraPose(Position(), q1), 1f),
            ),
        )
        assertSameRotation(yaw(180f), path.poseAt(0.5f).quaternion, 1e-5f)
        var previous = path.poseAt(0f).quaternion
        for (step in 1..100) {
            val q = path.poseAt(step / 100f).quaternion
            val degrees = angleDegrees(previous, q)
            assertTrue("step $step turned $degrees°", degrees < 0.25f)
            previous = q
        }
    }

    @Test
    fun catmullRomVelocityIsContinuousAcrossAnUnEasedKnot() {
        fun velocityJump(path: CameraPosePath, knot: Float, h: Float = 5e-4f): Float {
            val before = (path.poseAt(knot).position - path.poseAt(knot - h).position) / h
            val after = (path.poseAt(knot + h).position - path.poseAt(knot).position) / h
            return length(after - before)
        }
        val spline = velocityJump(fourKnots(), 1.5f)
        val polyline = velocityJump(fourKnots(CameraPathInterpolation.Linear), 1.5f)
        // The residue is the finite difference itself (acceleration × h); a polyline jumps by m/s.
        assertTrue("spline velocity jumps by $spline", spline < 0.03f)
        assertTrue("polyline velocity jumps by $polyline", polyline > 1f)
    }

    @Test
    fun anEasedSegmentStopsAtBothOfItsKeyframes() {
        val path = fourKnots(CameraPathInterpolation.Linear)
        val h = 1e-3f
        val leaving = length(path.poseAt(2f + h).position - path.poseAt(2f).position) / h
        assertTrue("leaves the eased keyframe at $leaving m/s", leaving < 0.01f)
    }

    @Test
    fun pathEasingKeepsBothEndsAndStaysMonotonic() {
        val linear = CameraPosePath(
            listOf(
                CameraPoseKeyframe(CameraPose(Position(0f, 0f, 0f)), 0f),
                CameraPoseKeyframe(CameraPose(Position(10f, 0f, 0f)), 5f),
            ),
            interpolation = CameraPathInterpolation.Linear,
            pathEasing = CameraEasing.InOut,
        )
        assertEquals(0f, linear.poseAt(0f).position.x, 0f)
        assertEquals(10f, linear.poseAt(5f).position.x, 0f)
        var previous = -1f
        for (step in 0..50) {
            val x = linear.poseAt(step * 0.1f).position.x
            assertTrue(x >= previous)
            previous = x
        }
        assertTrue("eased start is slower than linear", linear.poseAt(0.5f).position.x < 1f)
    }

    @Test
    fun orbitFramesTheBoxAndLooksAtItsCentreAllTheWayRound() {
        val bounds = Aabb(center = Position(0.2f, 0.6f, -0.1f), halfExtent = Position(0.5f, 0.6f, 0.3f))
        val fov = 45.0
        val aspect = 9.0 / 19.5
        val path = CameraPosePath.orbit(bounds, verticalFovDegrees = fov, aspect = aspect, durationSeconds = 10f)
        val tanV = tan(Math.toRadians(fov / 2)).toFloat()
        val tanH = tanV * aspect.toFloat()
        val radius = length(path.poseAt(0f).position - bounds.center)
        var tightest = 0f
        for (step in 0..400) {
            val pose = path.poseAt(step * 10f / 400)
            val forward = pose.forward
            val right = pose.right
            val up = cross(right, forward)
            val toCenter = normalize(bounds.center - pose.position)
            assertEquals("looks at the centre", 1f, dot(forward, toCenter), 1e-5f)
            assertEquals("keeps its radius", radius, length(pose.position - bounds.center), radius * 1e-4f)
            for (corner in corners(bounds)) {
                val v = corner - pose.position
                val depth = dot(v, forward)
                assertTrue(depth > 0f)
                val sx = abs(dot(v, right)) / depth / tanH
                val sy = abs(dot(v, up)) / depth / tanV
                assertTrue("corner leaves the frame at t=${step * 10f / 400}: $sx, $sy", sx <= 1f && sy <= 1f)
                tightest = maxOf(tightest, sx, sy)
            }
        }
        assertTrue("the box fills the frame, not a corner of it ($tightest)", tightest > 0.75f)
    }

    @Test
    fun orbitLoopIsSeamless() {
        val bounds = Aabb(center = Position(0f, 0.5f, 0f), halfExtent = Position(0.5f, 0.5f, 0.5f))
        val path = CameraPosePath.orbit(bounds, 45.0, 0.5, durationSeconds = 8f, turns = 2f, elevationDegrees = 15f)
        assertTrue(path.loop)
        assertPoseClose(path.poseAt(0f), path.poseAt(8f), 1e-5f)
        val h = 1e-3f
        val beforeSeam = (path.poseAt(8f).position - path.poseAt(8f - h).position) / h
        val afterSeam = (path.poseAt(8f + h).position - path.poseAt(8f).position) / h
        val midway = (path.poseAt(4f + h).position - path.poseAt(4f).position) / h
        assertTrue(length(afterSeam - beforeSeam) < 0.05f * length(midway))
        // Elevation: the eye sits above the centre at 15°.
        val eye = path.poseAt(1f).position - bounds.center
        assertEquals(15.0, Math.toDegrees(atan(eye.y / length(Float3(eye.x, 0f, eye.z)).toDouble())), 0.01)
    }

    @Test
    fun orbitTurnsTheRequestedWay() {
        val bounds = Aabb(halfExtent = Position(0.5f, 0.5f, 0.5f))
        val path = CameraPosePath.orbit(bounds, 45.0, 1.0, durationSeconds = 4f, elevationDegrees = 0f)
        // Azimuth 0 looks from +Z; a quarter of the turn later the camera is on +X.
        assertTrue(path.poseAt(0f).position.z > 0f)
        assertTrue(path.poseAt(1f).position.x > 0f)
        assertEquals(0f, path.poseAt(1f).position.z, 1e-3f)
    }

    @Test
    fun travellingSlidesAlongScreenRightAndKeepsItsOrientation() {
        val from = CameraPose.lookingAt(Position(0f, 1f, 3f), Position(0f, 1f, 0f))
        val path = CameraPosePath.travelling(from, distance = 2f, durationSeconds = 3f)
        val end = path.poseAt(3f)
        assertEquals(2f, end.position.x, 1e-5f)
        assertEquals(1f, end.position.y, 1e-5f)
        assertEquals(3f, end.position.z, 1e-5f)
        for (step in 0..30) assertSameRotation(from.quaternion, path.poseAt(step * 0.1f).quaternion, 1e-6f)
    }

    @Test
    fun dollyEndsAtTheRequestedDistanceFacingTheTarget() {
        val target = Position(0f, 0.5f, 0f)
        val path = CameraPosePath.dolly(target, from = Position(0f, 1.5f, 4f), toDistance = 1.2f, durationSeconds = 2f)
        assertEquals(1.2f, length(path.poseAt(2f).position - target), 1e-5f)
        for (step in 0..20) {
            val pose = path.poseAt(step * 0.1f)
            assertEquals(1f, dot(pose.forward, normalize(target - pose.position)), 1e-5f)
        }
    }

    @Test
    fun lookingAtFacesTheTargetEvenStraightDown() {
        val down = CameraPose.lookingAt(Position(0f, 5f, 0f), Position())
        assertEquals(-1f, down.forward.y, 1e-5f)
        assertTrue(!down.quaternion.x.isNaN())
        assertEquals(Quaternion(), CameraPose.lookingAt(Position(1f, 1f, 1f), Position(1f, 1f, 1f)).quaternion)
    }

    @Test
    fun cameraPathIsATimelineOfItsShots() {
        val path: CameraTimeline = CameraPath.tour(CameraShot(Position(0f, 0.5f, 0f), 0f, 20f, 3f))
        val shotPath = path as CameraPath
        for (t in floatArrayOf(0f, 2.5f, 9f, 30f)) {
            val shot = shotPath.shotAt(t)
            val pose = path.poseAt(t)
            assertEquals(shot.eye, pose.position)
            assertEquals(1f, dot(pose.forward, normalize(shot.target - shot.eye)), 1e-5f)
        }
    }

    private fun corners(box: Aabb): List<Position> = buildList {
        for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            add(box.center + Position(sx * box.halfExtent.x, sy * box.halfExtent.y, sz * box.halfExtent.z))
        }
    }

    private fun angleDegrees(a: Quaternion, b: Quaternion): Float {
        val d = abs(dot(normalize(a), normalize(b))).coerceAtMost(1f)
        return Math.toDegrees(2.0 * kotlin.math.acos(d.toDouble())).toFloat()
    }

    private fun assertSameRotation(expected: Quaternion, actual: Quaternion, tolerance: Float) {
        val d = abs(dot(normalize(expected), actual))
        assertEquals("rotation $actual vs $expected", 1f, d, tolerance)
    }

    private fun assertPoseClose(expected: CameraPose, actual: CameraPose, tolerance: Float) {
        assertTrue("position ${actual.position} vs ${expected.position}", length(actual.position - expected.position) <= tolerance)
        assertSameRotation(expected.quaternion, actual.quaternion, tolerance)
    }
}
