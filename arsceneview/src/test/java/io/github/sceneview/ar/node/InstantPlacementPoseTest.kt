package io.github.sceneview.ar.node

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantPlacementPoseTest {
    private val dropPose = Pose.makeTranslation(0f, 0f, -1f)

    @Test
    fun `moving the camera sideways does not move an approximate placement`() {
        val placement = InstantPlacementPose({ dropPose }, {})

        for (step in 0..10) {
            val cameraFollowingPose = Pose.makeTranslation(step / 10f, 0f, -1f)
            assertPose(dropPose, placement.update(false, cameraFollowingPose, step * 100_000_000L))
        }
        assertFalse(placement.isComplete)
    }

    @Test
    fun `full tracking refines position and orientation without an initial jump`() {
        val placement = InstantPlacementPose({ dropPose }, {})
        val refined = Pose(floatArrayOf(1f, 0.5f, -2f), floatArrayOf(0f, 1f, 0f, 0f))
        placement.update(false, Pose.makeTranslation(1f, 0f, -1f), 0L)

        assertPose(dropPose, placement.update(true, refined, 100_000_000L))
        assertPose(
            Pose(floatArrayOf(0.5f, 0.25f, -1.5f), floatArrayOf(0f, 0.70710677f, 0f, 0.70710677f)),
            placement.update(true, refined, 250_000_000L),
        )
        assertFalse(placement.isComplete)
        assertPose(refined, placement.update(true, refined, 400_000_000L))
        assertTrue(placement.isComplete)
    }

    @Test
    fun `refinement advances with frame time even if the anchor pose does not change`() {
        val placement = InstantPlacementPose({ dropPose }, {})
        val refined = Pose.makeTranslation(0f, 0f, -2f)

        assertPose(dropPose, placement.update(true, refined, 0L))
        assertPose(Pose.makeTranslation(0f, 0f, -1.5f), placement.update(true, refined, 150_000_000L))
        assertPose(refined, placement.update(true, refined, 300_000_000L))
    }

    @Test
    fun `after refinement the placement follows ongoing world tracking corrections`() {
        val placement = InstantPlacementPose({ dropPose }, {})
        placement.update(true, Pose.makeTranslation(0f, 0f, -2f), 0L)
        val corrected = Pose.makeTranslation(0.1f, 0f, -2.1f)

        assertPose(corrected, placement.update(true, corrected, 500_000_000L))
    }

    @Test
    fun `each placement retains its own drop pose and refinement clock`() {
        val first = InstantPlacementPose({ dropPose }, {})
        val secondDrop = Pose.makeTranslation(3f, 0f, -1f)
        val second = InstantPlacementPose({ secondDrop }, {})
        val refined = Pose.makeTranslation(0f, 0f, -2f)
        first.update(true, refined, 0L)

        assertPose(refined, first.update(true, refined, 300_000_000L))
        assertPose(secondDrop, second.update(false, refined, 300_000_000L))
        assertPose(secondDrop, second.update(true, refined, 400_000_000L))
    }

    @Test
    fun `origin translation correction does not physically displace an approximate placement`() {
        var heldAnchorPose = dropPose
        val placement = InstantPlacementPose({ heldAnchorPose }, {})
        val initialRelativePose = placement.update(false, dropPose, 0L)

        // No physical motion: ARCore translates the coordinates of both camera and world anchor.
        val correctedCameraPose = Pose.makeTranslation(0.25f, 0f, 0f)
        heldAnchorPose = correctedCameraPose.compose(dropPose)
        val placedPose = placement.update(false, heldAnchorPose, 100_000_000L)

        assertPose(initialRelativePose, correctedCameraPose.inverse().compose(placedPose))
    }

    @Test
    fun `origin rotation correction does not physically displace an approximate placement`() {
        var heldAnchorPose = dropPose
        val placement = InstantPlacementPose({ heldAnchorPose }, {})
        val initialRelativePose = placement.update(false, dropPose, 0L)
        val correctedCameraPose = Pose(
            floatArrayOf(0.25f, 0.1f, 0f),
            floatArrayOf(0f, 0.70710677f, 0f, 0.70710677f),
        )
        heldAnchorPose = correctedCameraPose.compose(dropPose)

        val placedPose = placement.update(false, heldAnchorPose, 100_000_000L)

        assertPose(initialRelativePose, correctedCameraPose.inverse().compose(placedPose))
    }

    @Test
    fun `full tracking starts from the current held anchor after an origin correction`() {
        var heldAnchorPose = dropPose
        val placement = InstantPlacementPose({ heldAnchorPose }, {})
        placement.update(false, dropPose, 0L)
        val correctedCameraPose = Pose.makeTranslation(0.25f, 0f, 0f)
        heldAnchorPose = correctedCameraPose.compose(dropPose)
        val refinedPose = correctedCameraPose.compose(Pose.makeTranslation(1f, 0f, -2f))

        val placedPose = placement.update(true, refinedPose, 100_000_000L)

        assertPose(dropPose, correctedCameraPose.inverse().compose(placedPose))
    }

    @Test
    fun `origin corrections during refinement preserve camera relative interpolation`() {
        var heldAnchorPose = dropPose
        val placement = InstantPlacementPose({ heldAnchorPose }, {})
        val refinedPose = Pose.makeTranslation(1f, 0f, -2f)
        placement.update(true, refinedPose, 0L)
        val correctedCameraPose = Pose(
            floatArrayOf(0.25f, 0.1f, 0f),
            floatArrayOf(0f, 0.70710677f, 0f, 0.70710677f),
        )
        heldAnchorPose = correctedCameraPose.compose(dropPose)

        val placedPose = placement.update(true, correctedCameraPose.compose(refinedPose), 150_000_000L)

        assertPose(Pose.makeTranslation(0.5f, 0f, -1.5f), correctedCameraPose.inverse().compose(placedPose))
    }

    @Test
    fun `the independent world anchor is retained until refinement completes then detached once`() {
        var detachCount = 0
        val placement = InstantPlacementPose({ dropPose }, { detachCount++ })
        placement.update(false, dropPose, 0L)
        assertEquals(0, detachCount)
        placement.update(true, Pose.makeTranslation(0f, 0f, -2f), 100_000_000L)
        placement.update(true, Pose.makeTranslation(0f, 0f, -2f), 250_000_000L)
        assertEquals(0, detachCount)

        placement.update(true, Pose.makeTranslation(0f, 0f, -2f), 400_000_000L)
        assertEquals(1, detachCount)
        placement.close()
        assertEquals(1, detachCount)
    }

    @Test
    fun `discarding an approximate placement detaches its world anchor only once`() {
        var detachCount = 0
        val placement = InstantPlacementPose({ dropPose }, { detachCount++ })

        placement.close()
        placement.close()

        assertEquals(1, detachCount)
        assertFalse(placement.isComplete)
    }

    private fun assertPose(expected: Pose, actual: Pose) {
        assertArrayEquals(expected.translation, actual.translation, 0.0001f)
        assertArrayEquals(expected.rotationQuaternion, actual.rotationQuaternion, 0.0001f)
    }
}
