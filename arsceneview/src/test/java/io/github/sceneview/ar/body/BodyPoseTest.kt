package io.github.sceneview.ar.body

import io.github.sceneview.ar.body.BodyPose.RawLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the MediaPipe-landmark → ARKit-parity-[Joint] projection in
 * [BodyPose] and [Joint]. No Android or MediaPipe runtime — these guard the mapping
 * contract that the AR Body Tracker demo relies on (#1763).
 */
class BodyPoseTest {

    /** Builds a 33-entry MediaPipe landmark list, all `visibility = 1`, with a per-index x. */
    private fun fullLandmarks(): List<RawLandmark> =
        (0 until Joint.MEDIAPIPE_LANDMARK_COUNT).map { i ->
            RawLandmark(x = i / 32f, y = i / 32f, z = 0f, visibility = 1f)
        }

    @Test
    fun `landmark index maps to the ARKit-parity joint`() {
        assertEquals(Joint.HEAD, Joint.fromMediaPipeLandmarkIndex(0))
        assertEquals(Joint.LEFT_SHOULDER, Joint.fromMediaPipeLandmarkIndex(11))
        assertEquals(Joint.RIGHT_SHOULDER, Joint.fromMediaPipeLandmarkIndex(12))
        assertEquals(Joint.LEFT_HAND, Joint.fromMediaPipeLandmarkIndex(15))
        assertEquals(Joint.RIGHT_HAND, Joint.fromMediaPipeLandmarkIndex(16))
        assertEquals(Joint.LEFT_FOOT, Joint.fromMediaPipeLandmarkIndex(27))
        assertEquals(Joint.RIGHT_FOOT, Joint.fromMediaPipeLandmarkIndex(28))
    }

    @Test
    fun `facial and extra-detail landmarks have no joint`() {
        // Eyes / ears / mouth (1..10) and pinky/index/thumb/heel detail (17..22, 29..32).
        listOf(1, 5, 10, 17, 21, 29, 32).forEach { i ->
            assertNull("landmark $i should not map to a joint", Joint.fromMediaPipeLandmarkIndex(i))
        }
    }

    @Test
    fun `synthesised joints are never read straight from a landmark index`() {
        // ROOT / SPINE / NECK are derived by BodyPose, so no index resolves to them.
        (0 until Joint.MEDIAPIPE_LANDMARK_COUNT).forEach { i ->
            val joint = Joint.fromMediaPipeLandmarkIndex(i)
            assertFalse(joint == Joint.ROOT || joint == Joint.SPINE || joint == Joint.NECK)
        }
    }

    @Test
    fun `empty or null landmark list yields an untracked pose`() {
        assertFalse(BodyPose.fromMediaPipeLandmarks(null).isTracked)
        assertFalse(BodyPose.fromMediaPipeLandmarks(emptyList()).isTracked)
        assertTrue(BodyPose.fromMediaPipeLandmarks(null).landmarks.isEmpty())
    }

    @Test
    fun `full landmark set produces all 13 direct joints plus 3 synthesised`() {
        val pose = BodyPose.fromMediaPipeLandmarks(fullLandmarks())
        assertTrue(pose.isTracked)
        // 13 mapped + ROOT + SPINE + NECK = 16 of the 17 joints (no 17th-joint gap here).
        assertEquals(16, pose.landmarks.size)
        Joint.entries.forEach { assertNotNull("joint $it missing", pose[it]) }
    }

    @Test
    fun `ROOT is the midpoint of the two hips`() {
        val pose = BodyPose.fromMediaPipeLandmarks(fullLandmarks())
        val leftHip = pose[Joint.LEFT_HIP]!!
        val rightHip = pose[Joint.RIGHT_HIP]!!
        val root = pose[Joint.ROOT]!!
        assertEquals((leftHip.x + rightHip.x) / 2f, root.x, 1e-6f)
        assertEquals((leftHip.y + rightHip.y) / 2f, root.y, 1e-6f)
    }

    @Test
    fun `NECK is the midpoint of the two shoulders`() {
        val pose = BodyPose.fromMediaPipeLandmarks(fullLandmarks())
        val ls = pose[Joint.LEFT_SHOULDER]!!
        val rs = pose[Joint.RIGHT_SHOULDER]!!
        val neck = pose[Joint.NECK]!!
        assertEquals((ls.x + rs.x) / 2f, neck.x, 1e-6f)
    }

    @Test
    fun `synthesised joint is dropped when a parent landmark is missing`() {
        // Truncate before the right-hip index — a too-short list omits index 24 and
        // beyond, so RIGHT_HIP is never detected and ROOT/SPINE cannot be synthesised.
        val truncated = fullLandmarks().subList(0, Joint.MP_RIGHT_HIP)
        val pose = BodyPose.fromMediaPipeLandmarks(truncated)
        assertNull(pose[Joint.RIGHT_HIP])
        assertNull(pose[Joint.ROOT])
        assertNull(pose[Joint.SPINE])
        // Shoulders still present → NECK still synthesised.
        assertNotNull(pose[Joint.NECK])
    }

    @Test
    fun `synthesised joint confidence is the minimum of its parents`() {
        val landmarks = fullLandmarks().toMutableList()
        landmarks[Joint.MP_LEFT_HIP] = RawLandmark(0.4f, 0.5f, 0f, visibility = 0.3f)
        landmarks[Joint.MP_RIGHT_HIP] = RawLandmark(0.6f, 0.5f, 0f, visibility = 0.9f)
        val root = BodyPose.fromMediaPipeLandmarks(landmarks)[Joint.ROOT]!!
        assertEquals(0.3f, root.inFrameLikelihood, 1e-6f)
    }

    @Test
    fun `skeleton bones reference only valid joints`() {
        assertTrue(SKELETON_BONES.isNotEmpty())
        SKELETON_BONES.forEach { (a, b) ->
            assertTrue("bone endpoints must differ", a != b)
        }
    }
}
