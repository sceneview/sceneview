package io.github.sceneview.demo.demos

import com.google.android.filament.Box
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.viewerEnvelopeFraming
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.verticalFovDegreesForFocalLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEnvelopeFramingTest {
    private val fov = verticalFovDegreesForFocalLength(VIEWER_FOCAL_LENGTH_MM)

    @Test
    fun `symmetric pose frames with zero horizontal offset`() {
        val framing = viewerEnvelopeFraming(
            envelope = AnimationEnvelope(listOf(Box(0f, 0f, 0f, 1f, 2f, 0.5f))),
            center = Position(),
            viewportWidth = 411f,
            viewportHeight = 914f,
            topInset = 96f,
            bottomInset = 128f,
            verticalFovDegrees = fov,
            pitchDegrees = 25f,
        )!!
        assertEquals(0f, framing.targetOffset.first, 1e-5f)
        assertEquals(0f, framing.eyeOffset.first, 1e-5f)
    }

    @Test
    fun `band shift moves eye and target by the same vector`() {
        val envelope = AnimationEnvelope(listOf(Box(0f, 0f, 0f, 1f, 2f, 0.5f)))
        fun framing(top: Float, bottom: Float) = viewerEnvelopeFraming(
            envelope, Position(), 411f, 914f, top, bottom, fov, pitchDegrees = 25f,
        )!!
        val shiftedUp = framing(50f, 150f)
        val shiftedDown = framing(150f, 50f)
        assertEquals(shiftedUp.distance, shiftedDown.distance, 0f)
        for (axis in 0..2) {
            val eyeShift = shiftedDown.eyeOffset.toList()[axis] - shiftedUp.eyeOffset.toList()[axis]
            val targetShift = shiftedDown.targetOffset.toList()[axis] - shiftedUp.targetOffset.toList()[axis]
            assertEquals(targetShift, eyeShift, 1e-5f)
        }
    }

    @Test
    fun `silhouette envelope brings a tall deep subject closer`() {
        val box = Box(0f, 0f, 0f, 0.5f, 2.5f, 2f)
        val envelope = viewerEnvelopeFraming(
            AnimationEnvelope(listOf(box)), Position(), 411f, 914f, 96f, 128f, fov,
            pitchDegrees = 25f,
        )!!
        val corners = DemoMath.viewerFraming(
            extentX = 1f, extentY = 5f, extentZ = 4f,
            viewportWidth = 411f, viewportHeight = 914f,
            topInset = 96f, bottomInset = 128f,
            verticalFovDegrees = fov, pitchDegrees = 25f,
        )
        assertTrue(envelope.distance < corners.distance)
    }

    @Test
    fun `null envelope fit falls back exactly to viewer framing`() {
        val extents = Size(1f, 2f, 0.5f)
        val expected = viewerHomeFraming(
            extents, viewportWidth = 411f, viewportHeight = 914f,
            topInset = 96f, bottomInset = 128f, restingAspect = null,
        )
        val actual = viewerHomeFraming(
            extents = extents,
            envelope = AnimationEnvelope(listOf(Box(0f, 0f, 0f, 0f, 0f, 0f))),
            viewportWidth = 411f, viewportHeight = 914f,
            topInset = 96f, bottomInset = 128f, restingAspect = null,
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `a huge subject stays inside the far plane`() {
        val framing = viewerEnvelopeFraming(
            AnimationEnvelope(listOf(Box(0f, 0f, 0f, 2000f, 3000f, 2000f))), Position(), 411f, 914f,
            96f, 128f, fov, pitchDegrees = 25f,
        )!!
        assertEquals(DemoMath.MAX_VIEWER_DISTANCE, framing.distance, 0f)
        val (x, y, z) = framing.eyeOffset
        val (tx, ty, tz) = framing.targetOffset
        val eyeToTarget = kotlin.math.sqrt((x - tx) * (x - tx) + (y - ty) * (y - ty) + (z - tz) * (z - tz))
        assertEquals(DemoMath.MAX_VIEWER_DISTANCE, eyeToTarget, 0.5f)
    }
}
