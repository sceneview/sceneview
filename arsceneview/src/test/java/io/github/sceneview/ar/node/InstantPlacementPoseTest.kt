package io.github.sceneview.ar.node

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantPlacementPoseTest {
    private val dropPose = Pose.makeTranslation(0f, 0f, -1f)

    @Test
    fun `moving the camera sideways does not move an approximate placement`() {
        val placement = InstantPlacementPose(dropPose)

        for (step in 0..10) {
            val cameraFollowingPose = Pose.makeTranslation(step / 10f, 0f, -1f)
            assertPose(dropPose, placement.update(false, cameraFollowingPose, step * 100_000_000L))
        }
        assertFalse(placement.isComplete)
    }

    @Test
    fun `full tracking refines position and orientation without an initial jump`() {
        val placement = InstantPlacementPose(dropPose)
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
        val placement = InstantPlacementPose(dropPose)
        val refined = Pose.makeTranslation(0f, 0f, -2f)

        assertPose(dropPose, placement.update(true, refined, 0L))
        assertPose(Pose.makeTranslation(0f, 0f, -1.5f), placement.update(true, refined, 150_000_000L))
        assertPose(refined, placement.update(true, refined, 300_000_000L))
    }

    @Test
    fun `after refinement the placement follows ongoing world tracking corrections`() {
        val placement = InstantPlacementPose(dropPose)
        placement.update(true, Pose.makeTranslation(0f, 0f, -2f), 0L)
        val corrected = Pose.makeTranslation(0.1f, 0f, -2.1f)

        assertPose(corrected, placement.update(true, corrected, 500_000_000L))
    }

    @Test
    fun `each placement retains its own drop pose and refinement clock`() {
        val first = InstantPlacementPose(dropPose)
        val secondDrop = Pose.makeTranslation(3f, 0f, -1f)
        val second = InstantPlacementPose(secondDrop)
        val refined = Pose.makeTranslation(0f, 0f, -2f)
        first.update(true, refined, 0L)

        assertPose(refined, first.update(true, refined, 300_000_000L))
        assertPose(secondDrop, second.update(false, refined, 300_000_000L))
        assertPose(secondDrop, second.update(true, refined, 400_000_000L))
    }

    private fun assertPose(expected: Pose, actual: Pose) {
        assertArrayEquals(expected.translation, actual.translation, 0.0001f)
        assertArrayEquals(expected.rotationQuaternion, actual.rotationQuaternion, 0.0001f)
    }
}
