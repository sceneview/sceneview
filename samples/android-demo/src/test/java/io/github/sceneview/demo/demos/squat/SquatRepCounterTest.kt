package io.github.sceneview.demo.demos.squat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Squat Coach counts reps from knee angles alone, so every rule is checked here on
 * synthetic sequences — no camera, no MediaPipe.
 */
class SquatRepCounterTest {

    /** One squat sampled at ~15 Hz: stand, down to [bottom], back up. */
    private fun squat(bottom: Float, steps: Int = 10): List<Float> {
        val down = (0..steps).map { 175f + (bottom - 175f) * it / steps }
        return down + List(3) { bottom } + down.reversed() + List(3) { 175f }
    }

    private fun SquatRepCounter.feed(samples: List<Float?>): SquatCoachState {
        var last = state
        samples.forEach { last = onSample(it) }
        return last
    }

    @Test
    fun fiveCleanSquatsCountFive() {
        val counter = SquatRepCounter()
        val counts = mutableListOf<Int>()
        repeat(5) {
            counter.feed(squat(bottom = 85f))
            counts += counter.state.reps
        }
        assertEquals(listOf(1, 2, 3, 4, 5), counts)
        assertEquals(SquatPhase.STANDING, counter.state.phase)
        assertEquals(SquatFeedback.REP_COUNTED, counter.state.feedback)
        assertTrue(counter.state.lastRepBottomAngle!! < 95f)
    }

    @Test
    fun shallowSquatDoesNotCountAndSaysSo() {
        val counter = SquatRepCounter()
        val state = counter.feed(squat(bottom = 120f))
        assertEquals(0, state.reps)
        assertEquals(SquatFeedback.TOO_SHALLOW, state.feedback)
    }

    @Test
    fun saysLowerOnTheWayDownThenStandUpAtDepth() {
        val counter = SquatRepCounter()
        counter.feed(listOf(175f, 175f, 130f, 120f))
        assertEquals(SquatPhase.GOING_DOWN, counter.state.phase)
        assertEquals(SquatFeedback.GO_LOWER, counter.state.feedback)
        counter.feed(List(6) { 85f })
        assertEquals(SquatPhase.DEEP_ENOUGH, counter.state.phase)
        assertEquals(SquatFeedback.STAND_UP, counter.state.feedback)
        assertEquals(1f, counter.state.depth, 0f)
    }

    @Test
    fun jitterAroundEveryThresholdNeverDoubleCounts() {
        val counter = SquatRepCounter()
        // Wobble across the descent line while standing-ish: no rep.
        counter.feed(List(20) { if (it % 2 == 0) 138f else 158f })
        assertEquals(0, counter.state.reps)
        // Go deep, then wobble right around the depth line and just under the stand line.
        counter.feed(List(8) { 80f })
        counter.feed(List(20) { if (it % 2 == 0) 98f else 104f })
        counter.feed(List(20) { if (it % 2 == 0) 150f else 158f })
        assertEquals(0, counter.state.reps)
        // Only the real stand-up counts, and only once however long it is held or wobbled.
        counter.feed(List(10) { 175f })
        counter.feed(List(20) { if (it % 2 == 0) 165f else 178f })
        assertEquals(1, counter.state.reps)
    }

    @Test
    fun aBriefDropoutHoldsTheRepInProgress() {
        val counter = SquatRepCounter()
        counter.feed(List(8) { 85f })
        counter.feed(List(SquatRepCounter.LOST_SAMPLES_BEFORE_RESET - 1) { null })
        assertNull(counter.state.kneeAngle)
        counter.feed(List(8) { 175f })
        assertEquals(1, counter.state.reps)
    }

    @Test
    fun losingTheLegsMidSquatAbandonsTheRepWithoutAPhantom() {
        val counter = SquatRepCounter()
        counter.feed(List(8) { 85f })
        counter.feed(List(SquatRepCounter.LOST_SAMPLES_BEFORE_RESET) { null })
        assertEquals(SquatPhase.NO_BODY, counter.state.phase)
        // Walks back into frame standing: no rep was finished, none is counted.
        counter.feed(List(8) { 175f })
        assertEquals(0, counter.state.reps)
        assertEquals(SquatPhase.STANDING, counter.state.phase)
    }

    @Test
    fun resetClearsTheCount() {
        val counter = SquatRepCounter()
        counter.feed(squat(85f) + squat(85f))
        assertEquals(2, counter.state.reps)
        counter.reset()
        assertEquals(SquatCoachState(), counter.state)
        counter.feed(squat(85f))
        assertEquals(1, counter.state.reps)
    }

    @Test
    fun depthRunsFromStandingToTheDepthLine() {
        assertEquals(0f, SquatRepCounter.depthOf(175f), 0f)
        assertEquals(0.5f, SquatRepCounter.depthOf(130f), 1e-4f)
        assertEquals(1f, SquatRepCounter.depthOf(60f), 0f)
    }

    // --- Knee angle ---------------------------------------------------------------------

    private fun p(x: Float, y: Float, vis: Float = 1f) = PosePoint(x, y, vis)

    /** A leg with the knee at [degrees], in a square image. */
    private fun legAt(degrees: Double, vis: Float = 1f): Leg {
        val rad = Math.toRadians(degrees)
        val knee = p(0.5f, 0.6f, vis)
        val hip = p(0.5f, 0.4f, vis) // straight up from the knee
        val ankle = p(0.5f + 0.2f * sin(rad).toFloat(), 0.6f - 0.2f * cos(rad).toFloat(), vis)
        return Leg(hip, knee, ankle)
    }

    @Test
    fun straightLegIs180AndRightAngleIs90() {
        assertEquals(180f, kneeAngleDegrees(legAt(180.0), null, 1f)!!, 0.5f)
        assertEquals(90f, kneeAngleDegrees(legAt(90.0), null, 1f)!!, 0.5f)
    }

    @Test
    fun aspectIsAppliedBeforeTheAngle() {
        // In a 3:4 portrait image a 45° diagonal in pixels is (0.25 / 0.75)-skewed in
        // normalised units; scaling x back by the aspect recovers 90° at the knee.
        val knee = p(0.5f, 0.5f)
        val hip = p(0.5f, 0.25f) // 0.25 of the height up
        val ankle = p(0.5f + 0.25f / 0.75f, 0.5f) // same length, sideways, in pixels
        val leg = Leg(hip, knee, ankle)
        assertEquals(90f, kneeAngleDegrees(leg, null, 0.75f)!!, 0.5f)
    }

    @Test
    fun picksTheMoreVisibleLegAndRejectsHiddenOnes() {
        val bent = legAt(90.0, vis = 0.9f)
        val straight = legAt(180.0, vis = 0.6f)
        assertEquals(90f, kneeAngleDegrees(straight, bent, 1f)!!, 0.5f)
        assertNull(kneeAngleDegrees(legAt(90.0, vis = 0.3f), legAt(180.0, vis = 0.2f), 1f))
        assertNotNull(kneeAngleDegrees(null, legAt(120.0, vis = MIN_LEG_VISIBILITY), 1f))
    }

    @Test
    fun collapsedLegHasNoAngle() {
        val knee = p(0.5f, 0.5f)
        assertNull(angleAt(knee, knee, p(0.5f, 0.8f), 1f))
    }
}
