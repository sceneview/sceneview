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

    private val settled = ScanHoleGuide.SETTLE_MS

    /** A guide that first saw [holes] at 0: they are settled from [settled] on. */
    private fun watching(holes: List<Pair<Float, Float>>) = ScanHoleGuide().also { it.update(holes, phone(), 0L) }

    @Test
    fun noHolesHasNoTurnOrTarget() {
        val guide = ScanHoleGuide()

        assertNull(guide.update(emptyList(), phone(), 0L))
        assertNull(guide.target)
    }

    @Test
    fun aGapIsNamedOnlyOnceItHasStayedEmpty() {
        val guide = ScanHoleGuide()
        val gap = 2f to 0f

        assertNull(guide.update(listOf(gap), phone(), 0L))
        assertNull(guide.target)
        assertNull(guide.update(listOf(gap), phone(), settled - 1))
        assertEquals(ScanTurn.Right, guide.update(listOf(gap), phone(), settled))
        assertEquals(gap, guide.target)
    }

    @Test
    fun aGapTheSweepFillsOnItsWayIsNeverNamed() {
        // u30's first ten seconds: two gaps named and gone within two seconds each.
        val guide = watching(listOf(2f to 0f))
        assertNull(guide.update(emptyList(), phone(), 2_000L))

        // Another opens elsewhere: its own wait starts.
        val other = -2f to 0f
        assertNull(guide.update(listOf(other), phone(), 2_500L))
        assertNull(guide.update(listOf(other), phone(), 2_500L + settled - 1))
        assertEquals(ScanTurn.Left, guide.update(listOf(other), phone(), 2_500L + settled))
    }

    @Test
    fun aSettledGapIsSaidOnEverySide() {
        listOf(
            (-2f to 0f) to ScanTurn.Left,
            (2f to 0f) to ScanTurn.Right,
            (0f to -2f) to ScanTurn.Ahead,
            (0f to 2f) to ScanTurn.Behind,
        ).forEach { (gap, side) ->
            val guide = watching(listOf(gap))

            assertEquals(side, guide.update(listOf(gap), phone(), settled))
            assertEquals(gap, guide.target)
        }
    }

    @Test
    fun sameGapKeepsItsSideForFourAndAHalfSeconds() {
        val gap = 0f to -2f
        val guide = watching(listOf(gap))
        assertEquals(ScanTurn.Ahead, guide.update(listOf(gap), phone(), settled))

        assertEquals(ScanTurn.Ahead, guide.update(listOf(gap), phone(90f), settled + 4_499L))
        assertEquals(ScanTurn.Right, guide.update(listOf(gap), phone(90f), settled + 4_500L))
    }

    @Test
    fun gapIdentityFollowsItsCentreWithinOnePointTwoMetres() {
        val kept = watching(listOf(0f to -2f))
        kept.update(listOf(0f to -2f), phone(), settled)
        val id = kept.gapId
        val shifted = 0.8f to -2f
        assertEquals(ScanTurn.Ahead, kept.update(listOf(shifted), phone(90f), settled + 1))
        assertEquals(shifted, kept.target)
        assertEquals(id, kept.gapId)

        // Two metres away is another gap: it waits its turn, then takes another number.
        val replaced = watching(listOf(0f to -2f))
        replaced.update(listOf(0f to -2f), phone(), settled)
        val before = replaced.gapId
        val distant = 2f to -2f
        assertNull(replaced.update(listOf(distant), phone(90f), settled + 1))
        assertNull(replaced.target)
        assertEquals(ScanTurn.Behind, replaced.update(listOf(distant), phone(90f), 2 * settled + 1))
        assertEquals(distant, replaced.target)
        assertEquals(before + 1, replaced.gapId)
    }

    @Test
    fun whenTheNamedGapFillsASettledOneIsSaidAtOnce() {
        val named = 0f to -1f
        val remaining = 2f to 0f
        val guide = watching(listOf(named, remaining))
        assertEquals(ScanTurn.Ahead, guide.update(listOf(named, remaining), phone(), settled))
        val id = guide.gapId

        assertEquals(ScanTurn.Right, guide.update(listOf(remaining), phone(), settled + 1))
        assertEquals(remaining, guide.target)
        assertEquals(id + 1, guide.gapId)
    }

    @Test
    fun theHatchingIsTheNamedGapsWhileTheLineNamesIt() {
        val gap = 2f to 0f
        val guide = watching(listOf(gap))
        guide.update(listOf(gap), phone(), settled)

        assertEquals(gap, guide.marked(guide.gapId))
        // The line says something else, or still names the gap before this one.
        assertNull(guide.marked(null))
        assertNull(guide.marked(guide.gapId - 1))
    }
}
