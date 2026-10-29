package io.github.sceneview.gesture

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InertiaTest {

    /** A steady drag at [speed] units/s, sampled at 60 Hz for 200 ms, released on the last one. */
    private fun InertiaTracker.drag(speed: Float, startTime: Double = 0.0): Double {
        var t = startTime
        repeat(13) { i ->
            t = startTime + i / 60.0
            addSample(t, speed * (t - startTime).toFloat())
        }
        return t
    }

    /** Runs the coast at [hz] until it stops; returns (total travel, duration). */
    private fun InertiaTracker.coastAt(hz: Int, maxSeconds: Float = 10f): Pair<Float, Float> {
        val out = FloatArray(dimensions)
        val dt = 1f / hz
        var travel = 0f
        var elapsed = 0f
        while (isCoasting && elapsed < maxSeconds) {
            advance(dt, out)
            travel += out[0]
            elapsed += dt
        }
        return travel to elapsed
    }

    @Test
    fun releaseMeasuresTheFingerVelocity() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 1f)
        val t = tracker.drag(speed = 600f)
        assertTrue(tracker.release(t))
        assertEquals(600f, tracker.velocity(0), 1f)
    }

    @Test
    fun velocityFollowsTheLastHundredMillisecondsNotTheWholeGesture() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 1f)
        // Slow for 300 ms, then a fast flick for the last 100 ms.
        var t = 0.0
        var x = 0f
        repeat(18) {
            t += 1 / 60.0; x += 100f / 60f
            tracker.addSample(t, x)
        }
        repeat(6) {
            t += 1 / 60.0; x += 1200f / 60f
            tracker.addSample(t, x)
        }
        assertTrue(tracker.release(t))
        assertTrue(tracker.velocity(0) > 1000f, "velocity ${tracker.velocity(0)}")
    }

    @Test
    fun decayCurveIsExponentialWithTheTimeConstant() {
        val inertia = Inertia(timeConstantSeconds = 0.35f)
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 0.01f, inertia = inertia)
        tracker.release(tracker.drag(speed = 1000f))
        val v0 = tracker.velocity(0)
        val out = FloatArray(1)
        var elapsed = 0f
        repeat(21) { // 0.35 s at 60 Hz
            tracker.advance(1f / 60f, out)
            elapsed += 1f / 60f
        }
        val expected = v0 * exp(-elapsed / 0.35f)
        assertEquals(expected, tracker.velocity(0), expected * 1e-3f)
        assertEquals(v0 / kotlin.math.E.toFloat(), tracker.velocity(0), v0 * 0.01f)
    }

    @Test
    fun totalTravelIsVelocityTimesTimeConstant() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 0.001f)
        tracker.release(tracker.drag(speed = 800f))
        val v0 = tracker.velocity(0)
        val (travel, _) = tracker.coastAt(60)
        assertEquals(v0 * Inertia.DEFAULT_TIME_CONSTANT_SECONDS, travel, v0 * 0.002f)
    }

    @Test
    fun frameRateIndependentAt30And60And120Hz() {
        val results = listOf(30, 60, 120).map { hz ->
            val tracker = InertiaTracker(dimensions = 1, minSpeed = 0f)
            tracker.release(tracker.drag(speed = 900f))
            // Same wall-clock span (0.5 s) at every rate.
            val out = FloatArray(1)
            var travel = 0f
            repeat(hz / 2) {
                tracker.advance(1f / hz, out)
                travel += out[0]
            }
            travel to tracker.velocity(0)
        }
        val (travel60, velocity60) = results[1]
        results.forEach { (travel, velocity) ->
            assertEquals(travel60, travel, travel60 * 1e-4f)
            assertEquals(velocity60, velocity, abs(velocity60) * 1e-4f)
        }
    }

    @Test
    fun coastStopsUnderTheMinimumSpeed() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 50f)
        tracker.release(tracker.drag(speed = 500f))
        val (_, duration) = tracker.coastAt(60)
        // v(t) = 500·e^(−t/0.35) < 50  ⇔  t > 0.35·ln(10) ≈ 0.806 s
        assertEquals(0.806f, duration, 1f / 60f + 0.001f)
        assertFalse(tracker.isCoasting)
        assertEquals(0f, tracker.velocity(0))
    }

    @Test
    fun slowReleaseDoesNotCoast() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 50f)
        assertFalse(tracker.release(tracker.drag(speed = 20f)))
        assertFalse(tracker.isCoasting)
    }

    @Test
    fun releaseThresholdIsSeparateFromTheStopCutoff() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 10f, minReleaseSpeed = 150f)
        assertFalse(tracker.release(tracker.drag(speed = 100f)))
        assertTrue(tracker.release(tracker.drag(speed = 400f, startTime = 5.0)))
        // Once launched it coasts all the way down to minSpeed, not to minReleaseSpeed.
        val (_, duration) = tracker.coastAt(60)
        assertEquals(0.35f * kotlin.math.ln(40f), duration, 1f / 60f + 0.001f)
    }

    @Test
    fun fingerThatRestedBeforeLiftingDoesNotCoast() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 1f)
        val t = tracker.drag(speed = 1000f)
        // No move event for 80 ms (the finger held still), then the lift.
        assertFalse(tracker.release(t + 0.08))
    }

    @Test
    fun newTouchInterruptsTheCoast() {
        val tracker = InertiaTracker(dimensions = 2, minSpeed = 1f)
        var t = 0.0
        repeat(10) { i ->
            t = i / 60.0
            tracker.addSample(t, 400f * t.toFloat(), -300f * t.toFloat())
        }
        assertTrue(tracker.release(t))
        val out = FloatArray(2)
        tracker.advance(1f / 60f, out)
        assertTrue(out[0] > 0f && out[1] < 0f)
        tracker.reset()
        assertFalse(tracker.isCoasting)
        assertFalse(tracker.advance(1f / 60f, out))
        assertEquals(0f, out[0])
        assertEquals(0f, out[1])
    }

    @Test
    fun diagonalFlickKeepsItsDirection() {
        val tracker = InertiaTracker(dimensions = 2, minSpeed = 10f)
        var t = 0.0
        repeat(10) { i ->
            t = i / 60.0
            tracker.addSample(t, 600f * t.toFloat(), 200f * t.toFloat())
        }
        tracker.release(t)
        val out = FloatArray(2)
        while (tracker.advance(1f / 60f, out)) {
            assertEquals(3f, out[0] / out[1], 1e-3f)
        }
    }

    @Test
    fun noneNeverCoasts() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 1f, inertia = Inertia.None)
        assertFalse(Inertia.None.isEnabled)
        assertTrue(Inertia.Default.isEnabled)
        assertFalse(tracker.release(tracker.drag(speed = 2000f)))
    }

    @Test
    fun releaseSpeedIsClamped() {
        val tracker = InertiaTracker(dimensions = 1, minSpeed = 1f, maxSpeed = 300f)
        tracker.release(tracker.drag(speed = 5000f))
        assertEquals(300f, tracker.velocity(0), 0.01f)
    }

    @Test
    fun aStalledFrameIsIntegratedExactly() {
        val a = InertiaTracker(dimensions = 1, minSpeed = 0f)
        val b = InertiaTracker(dimensions = 1, minSpeed = 0f)
        a.release(a.drag(speed = 700f))
        b.release(b.drag(speed = 700f))
        val out = FloatArray(1)
        a.advance(0.1f, out)
        val single = out[0]
        var split = 0f
        repeat(10) { b.advance(0.01f, out); split += out[0] }
        assertEquals(single, split, single * 1e-4f)
    }
}
