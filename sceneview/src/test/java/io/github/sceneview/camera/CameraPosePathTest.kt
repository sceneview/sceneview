@file:OptIn(ExperimentalSceneViewApi::class)

package io.github.sceneview.camera

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.Aabb
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun aPauseInTheMiddleIsNotOvershot() {
        // 0 -> 10 in one second, then held for a second. Plain Catmull-Rom ran to x = 10.74 and
        // came back during the hold.
        val path = straightPath(0f to 0f, 10f to 1f, 10f to 2f)
        assertStaysWithinNeighbouringKeyframes(path)
        for (step in 0..100) assertEquals(10f, path.poseAt(1f + step * 0.01f).position.x, 1e-5f)
    }

    @Test
    fun aPauseAtTheStartDoesNotBackUp() {
        // Held for a second, then 0 -> 10. Plain Catmull-Rom backed up to x < 0 during the hold.
        val path = straightPath(0f to 0f, 0f to 1f, 10f to 2f)
        assertStaysWithinNeighbouringKeyframes(path)
        for (step in 0..100) assertEquals(0f, path.poseAt(step * 0.01f).position.x, 0f)
    }

    @Test
    fun aNearPauseStaysOnItsSegment() {
        // 10 units in the first second, 0.1 in the next: the capped tangent keeps the camera
        // between 10 and 10.1 instead of running past 10.1 and coming back.
        assertStaysWithinNeighbouringKeyframes(straightPath(0f to 0f, 10f to 1f, 10.1f to 2f))
    }

    @Test
    fun aPauseIn3dIsNotOvershotOnAnyAxis() {
        val a = Position(0f, 0f, 0f)
        val b = Position(4f, 2f, -3f)
        val c = Position(-2f, 5f, 1f)
        val path = CameraPosePath(
            listOf(a to 0f, b to 1f, b to 1.5f, c to 3f, c to 4f).map { (p, t) -> CameraPoseKeyframe(CameraPose(p), t) },
        )
        assertStaysWithinNeighbouringKeyframes(path)
    }

    @Test
    fun aThereAndBackIsNotOvershot() {
        // Out and partly back along one line. The length cap alone still ran past the turning
        // keyframe (0 -> 10 -> 5 reached x = 10.11, 0 -> 10 -> 9 reached 10.32); a reversal now
        // gets a zero tangent and the camera stops on x = 10.
        for (back in floatArrayOf(5f, 9f, 0f)) {
            val path = straightPath(0f to 0f, 10f to 1f, back to 2f)
            assertStaysWithinNeighbouringKeyframes(path)
            val peak = (0..2000).maxOf { path.poseAt(it * 0.001f).position.x }
            assertEquals("0 -> 10 -> $back", 10f, peak, 1e-5f)
        }
    }

    @Test
    fun aTurnOfLessThanNinetyDegreesKeepsItsSpeed() {
        // 26.6° turn at t = 1: the camera flows through the keyframe instead of stopping on it.
        val path = CameraPosePath(
            listOf(Position(0f, 0f, 0f) to 0f, Position(10f, 0f, 0f) to 1f, Position(20f, 5f, 0f) to 2f)
                .map { (p, t) -> CameraPoseKeyframe(CameraPose(p), t) },
        )
        val h = 1e-3f
        val speed = length(path.poseAt(1f + h).position - path.poseAt(1f - h).position) / (2 * h)
        assertTrue("speed at the keyframe was $speed", speed > 5f)
    }

    @Test
    fun aRightAngleCornerKeepsItsSpeed() {
        // A square, one side per second: every corner is exactly 90°, and the camera flows
        // round each one instead of stopping (or not) depending on rounding.
        val path = CameraPosePath(
            listOf(
                Position(0f, 0f, 0f), Position(10f, 0f, 0f), Position(10f, 0f, 10f),
                Position(0f, 0f, 10f), Position(0f, 0f, 0f),
            ).mapIndexed { i, p -> CameraPoseKeyframe(CameraPose(p), i.toFloat()) },
        )
        for (corner in 1..3) {
            val speed = speedAt(path, corner.toFloat())
            assertTrue("speed at corner $corner was $speed", speed > 5f)
        }
    }

    @Test
    fun aTurnBackStopsBeyond120DegreesOnly() {
        fun turn(degrees: Float): CameraPosePath {
            val radians = Math.toRadians(degrees.toDouble())
            val out = Position(10f + 10f * cos(radians).toFloat(), 0f, 10f * sin(radians).toFloat())
            return CameraPosePath(
                listOf(Position(0f, 0f, 0f) to 0f, Position(10f, 0f, 0f) to 1f, out to 2f)
                    .map { (p, t) -> CameraPoseKeyframe(CameraPose(p), t) },
            )
        }
        val below = speedAt(turn(119f), 1f)
        val beyond = speedAt(turn(121f), 1f)
        assertTrue("119° turn slowed to $below", below > 2f)
        assertTrue("121° turn kept $beyond", beyond < 0.1f)
    }

    @Test
    fun aMinimumOrbitDoesNotSlowDownOnItsKeyframes() {
        // Asking for 1 or 4 keys per turn gets the minimum, 6: before, 4 keys made 90° chords and
        // the camera nearly stopped on each key (0.001 u/s against 1.69 between keys).
        val box = Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f))
        for (keys in intArrayOf(1, 4)) {
            val orbit = CameraPosePath.orbit(box, 45.0, 1.0, keyframesPerTurn = keys)
            assertEquals(7, orbit.keyframes.size)
            assertKeepsItsSpeedOnEveryKeyframe(orbit, includingTheSeam = true)
        }
    }

    @Test
    fun aThreeQuarterOrbitDoesNotStopOnItsKeyframes() {
        val box = Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f))
        val orbit = CameraPosePath.orbit(box, 45.0, 1.0, turns = 0.75f, loop = false, keyframesPerTurn = 4)
        assertKeepsItsSpeedOnEveryKeyframe(orbit, includingTheSeam = false)
    }

    @Test
    fun aFractionalOrbitCarriesOnAcrossTheSeam() {
        val box = Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f))
        val orbit = CameraPosePath.orbit(box, 45.0, 1.0, durationSeconds = 6f, turns = 1.5f)
        for (lap in 1..3) {
            val seam = lap * 6f
            val before = orbit.poseAt(seam - 1e-3f)
            val after = orbit.poseAt(seam + 1e-3f)
            assertTrue("lap $lap", length(after.position - before.position) < 0.01f)
            assertTrue("lap $lap", abs(dot(after.quaternion, before.quaternion)) > 0.9999f)
        }
        // Lap two starts half a turn on, where lap one ended.
        assertTrue(length(orbit.poseAt(6f).position - orbit.poseAt(6f + 1e-4f).position) < 1e-3f)
        assertTrue(length(orbit.poseAt(12f).position - orbit.poseAt(0f).position) < 1e-3f)
    }

    @Test
    fun anOrbitRefusesAbsurdTurns() {
        val box = Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f))
        for (turns in floatArrayOf(1e5f, -1e5f, 1e9f)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                CameraPosePath.orbit(box, 45.0, 1.0, turns = turns)
            }
            assertTrue(error.message.orEmpty().startsWith("turns must be"))
        }
        assertEquals(1000 * 32 + 1, CameraPosePath.orbit(box, 45.0, 1.0, turns = 1000f).keyframes.size)
    }

    @Test
    fun aKeyframeDroppedForItsTimeIsNotCheckedForItsPosition() {
        val path = CameraPosePath(
            listOf(
                CameraPoseKeyframe(CameraPose(Position()), 0f),
                CameraPoseKeyframe(CameraPose(Position(Float.NaN, 0f, 0f)), Float.NaN),
                CameraPoseKeyframe(CameraPose(Position(10f, 0f, 0f)), 1f),
            ),
        )
        assertEquals(2, path.keyframes.size)
    }

    @Test
    fun aNonFiniteKeyframePositionIsRejected() {
        for (bad in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                CameraPosePath(
                    listOf(
                        CameraPoseKeyframe(CameraPose(Position()), 0f),
                        CameraPoseKeyframe(CameraPose(Position(bad, 0f, 0f)), 1f),
                    ),
                )
            }
        }
    }

    @Test
    fun aCutSplitsTheSpline() {
        // Two keyframes at t = 1: the camera jumps from x = 10 to x = 100. Each side moves on its
        // own; before the fix the tangent across the cut (90 units in 0 s, read over 2 s) threw the
        // first segment far past x = 10.
        val path = straightPath(0f to 0f, 10f to 1f, 100f to 1f, 110f to 2f)
        assertStaysWithinNeighbouringKeyframes(path)
        assertEquals(100f, path.poseAt(1f).position.x, 1e-4f)
        assertTrue(path.poseAt(0.999f).position.x <= 10f)
        for (step in 0..99) assertEquals(step * 0.1f, path.poseAt(step * 0.01f).position.x, 1e-3f)
    }

    @Test
    fun presetsRejectNonFiniteInputs() {
        val box = Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f))
        assertThrows(IllegalArgumentException::class.java) {
            CameraPosePath.orbit(Aabb(Position(Float.NaN, 0f, 0f), box.halfExtent), 45.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) { CameraPosePath.orbit(box, Double.NaN, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { CameraPosePath.orbit(box, 45.0, Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) {
            CameraPosePath.travelling(CameraPose(Position(0f, 1f, 4f)), Float.NaN, 2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraPosePath.dolly(Position(), Position(0f, 1f, 4f), Float.NaN, 2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraPosePath.dolly(Position(), Position(Float.NaN, 1f, 4f), 1f, 2f)
        }
    }

    @Test
    fun anEasedSegmentStopsAtBothOfItsKeyframes() {
        val path = fourKnots(CameraPathInterpolation.Linear)
        val h = 1e-3f
        val leaving = length(path.poseAt(2f + h).position - path.poseAt(2f).position) / h
        assertTrue("leaves the eased keyframe at $leaving m/s", leaving < 0.01f)
        val arriving = length(path.poseAt(4f).position - path.poseAt(4f - h).position) / h
        assertTrue("arrives at the next keyframe at $arriving m/s", arriving < 0.01f)
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

    /** Speed of the camera at [t], by central difference. */
    private fun speedAt(path: CameraPosePath, t: Float, h: Float = 1e-3f): Float =
        length(path.poseAt(t + h).position - path.poseAt(t - h).position) / (2 * h)

    /**
     * On every keyframe the camera keeps at least 70 % of the speed it has midway along a
     * segment. With 60° chords, Catmull-Rom itself dips to 77 % on a key (0.866 against 1.125
     * chords per second); a stopped key reads 0.
     */
    private fun assertKeepsItsSpeedOnEveryKeyframe(path: CameraPosePath, includingTheSeam: Boolean) {
        val frames = path.keyframes
        val origin = frames.first().seconds
        val cruise = speedAt(path, (frames[1].seconds - origin) / 2f)
        val keys = if (includingTheSeam) frames.indices else 1 until frames.size - 1
        for (i in keys) {
            val speed = speedAt(path, frames[i].seconds - origin)
            assertTrue("keyframe $i of ${frames.size}: $speed against $cruise", speed > 0.7f * cruise)
        }
    }

    /** A Catmull-Rom path along x, from `x to seconds` pairs. */
    private fun straightPath(vararg keys: Pair<Float, Float>) = CameraPosePath(
        keys.map { (x, t) -> CameraPoseKeyframe(CameraPose(Position(x, 1f, 0f)), t) },
    )

    /** Every sample of every segment lies, axis by axis, between the segment's two keyframes. */
    private fun assertStaysWithinNeighbouringKeyframes(path: CameraPosePath, steps: Int = 400) {
        val frames = path.keyframes
        val origin = frames.first().seconds
        for (i in 0 until frames.size - 1) {
            val a = frames[i]
            val b = frames[i + 1]
            if (b.seconds - a.seconds <= 0f) continue
            for (step in 0..steps) {
                // Stay off the far keyframe: at its exact time the next segment (or a cut) applies.
                val t = a.seconds + (b.seconds - a.seconds) * step / (steps + 1)
                val p = path.poseAt(t - origin).position
                for (axis in 0..2) {
                    val low = minOf(a.pose.position[axis], b.pose.position[axis]) - ENVELOPE_TOLERANCE
                    val high = maxOf(a.pose.position[axis], b.pose.position[axis]) + ENVELOPE_TOLERANCE
                    assertTrue(
                        "segment $i at t=$t: axis $axis = ${p[axis]} outside [$low, $high]",
                        p[axis] in low..high,
                    )
                }
            }
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

    private companion object {
        const val ENVELOPE_TOLERANCE = 1e-4f
    }

    private fun assertPoseClose(expected: CameraPose, actual: CameraPose, tolerance: Float) {
        assertTrue("position ${actual.position} vs ${expected.position}", length(actual.position - expected.position) <= tolerance)
        assertSameRotation(expected.quaternion, actual.quaternion, tolerance)
    }
}
