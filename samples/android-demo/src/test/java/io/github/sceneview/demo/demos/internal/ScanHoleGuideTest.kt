package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ScanHoleGuideTest {

    /** A level phone at the origin turned [yawDegrees] about +Y. */
    private fun phone(yawDegrees: Float = 0f): DebugPose {
        val half = Math.toRadians(yawDegrees / 2.0)
        return DebugPose(0f, 0f, 0f, qy = sin(half).toFloat(), qw = cos(half).toFloat())
    }

    @Test
    fun noHolesHasNoTurnOrTarget() {
        val guide = ScanHoleGuide()

        assertNull(guide.update(emptyList(), phone(), 0L))
        assertNull(guide.target)
    }

    @Test
    fun firstGapIsSaidAtOnceOnEverySide() {
        listOf(
            (-2f to 0f) to ScanTurn.Left,
            (2f to 0f) to ScanTurn.Right,
            (0f to -2f) to ScanTurn.Ahead,
            (0f to 2f) to ScanTurn.Behind,
        ).forEach { (gap, side) ->
            val guide = ScanHoleGuide()

            assertEquals(side, guide.update(listOf(gap), phone(), 0L))
            assertEquals(gap, guide.target)
        }
    }

    @Test
    fun sameGapKeepsItsSideForFourAndAHalfSeconds() {
        val guide = ScanHoleGuide()
        val gap = 0f to -2f
        assertEquals(ScanTurn.Ahead, guide.update(listOf(gap), phone(), 0L))

        assertEquals(ScanTurn.Ahead, guide.update(listOf(gap), phone(90f), 4_499L))
        assertEquals(ScanTurn.Right, guide.update(listOf(gap), phone(90f), 4_500L))
    }

    @Test
    fun gapIdentityFollowsItsCentreWithinOnePointTwoMetres() {
        val kept = ScanHoleGuide()
        kept.update(listOf(0f to -2f), phone(), 0L)
        val shifted = 0.8f to -2f
        assertEquals(ScanTurn.Ahead, kept.update(listOf(shifted), phone(90f), 1L))
        assertEquals(shifted, kept.target)

        val replaced = ScanHoleGuide()
        replaced.update(listOf(0f to -2f), phone(), 0L)
        val distant = 2f to -2f
        assertEquals(ScanTurn.Behind, replaced.update(listOf(distant), phone(90f), 1L))
        assertEquals(distant, replaced.target)
    }

    @Test
    fun disappearingGapNamesAndSaysTheNearestRemainingOneAtOnce() {
        val guide = ScanHoleGuide()
        val named = 0f to -1f
        val remaining = 2f to 0f
        assertEquals(ScanTurn.Ahead, guide.update(listOf(named, remaining), phone(), 0L))

        assertEquals(ScanTurn.Right, guide.update(listOf(remaining), phone(), 1L))
        assertEquals(remaining, guide.target)
    }
}
