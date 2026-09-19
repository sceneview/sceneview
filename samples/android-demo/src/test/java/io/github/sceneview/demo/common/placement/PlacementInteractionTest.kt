package io.github.sceneview.demo.common.placement

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.demo.AR_CAMERA_INIT_SCRIM_TIMEOUT_MS
import io.github.sceneview.math.Rotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the tap-to-place interaction core
 * ([#3326](https://github.com/sceneview/sceneview/issues/3326)).
 *
 * The AR emulator produces no ARCore tracking, so none of this behaviour can be exercised
 * on CI through the UI. Everything that *can* be decided without a camera therefore lives
 * in [PlacementRotation] / [PlacementScale] / [PlacementEntrance] / [placementCoaching] as
 * pure functions, and this is where the contract is pinned.
 *
 * The rotation block below is written as a matched pair: the same assertions run against the
 * new composition and against [PlacementRotation.legacyContentOrientation], which reproduces
 * what the demo did before
 * [#3735](https://github.com/sceneview/sceneview/issues/3735). They pass on one and fail on
 * the other, so the fix is pinned by a test that could fail, not by a test written to agree
 * with the code.
 */
class PlacementInteractionTest {

    // ── PlacementRotation: the twist stays a yaw (#3735) ────────────────────

    /**
     * The Khronos helmet is authored Z-up, so the demo stands it up with a −90° X
     * correction ([DemoMath.placementRotationFor][io.github.sceneview.demo.demos.internal.DemoMath]).
     * Built here the way the SDK builds it — `Quaternion.fromEuler` on a [Rotation], exactly
     * what `Node.rotation`'s setter does.
     */
    private val helmetCorrection = Quaternion.fromEuler(Rotation(x = -90f))

    /** The model's own up axis: +Z in the helmet's authored frame, which the correction stands up. */
    private val authoredUp = Float3(z = 1f)

    /** Everything else the demo ships has no correction at all. */
    private val noCorrection = Quaternion()

    @Test
    fun `a twist on a pre-rotated asset turns it on the floor, not off it`() {
        val rest = PlacementRotation.contentOrientation(Quaternion(), helmetCorrection)
        val twisted = PlacementRotation.contentOrientation(
            pivot = PlacementRotation.twist(Quaternion(), 30f),
            assetCorrection = helmetCorrection,
        )

        // What the twist did, expressed in the anchor's frame. It must be a pure yaw, so
        // anything that was vertical is still vertical.
        val applied = PlacementRotation.appliedInAnchorFrame(current = twisted, rest = rest)
        assertEquals(0f, PlacementRotation.tiltDegrees(applied), 1e-4f)
        assertEquals(30f, PlacementRotation.yawDegrees(applied), 1e-3f)

        // Concretely: the helmet's own up axis pointed at the sky at rest and still does.
        val upAtRest = rest * authoredUp
        val upAfter = twisted * authoredUp
        assertEquals(1f, upAtRest.y, 1e-4f)
        assertEquals(upAtRest.x, upAfter.x, 1e-4f)
        assertEquals(upAtRest.y, upAfter.y, 1e-4f)
        assertEquals(upAtRest.z, upAfter.z, 1e-4f)
    }

    @Test
    fun `the old structure tips the same asset over — this is the regression`() {
        // The pre-#3735 composition: the correction lived on the node the twist edited, so
        // the SDK's local-frame `quaternion *= delta` turned about the node's local Y — which
        // the correction had already laid flat onto (0, 0, -1). Same assertions as the test
        // above; they fail here, which is the point.
        val rest = PlacementRotation.legacyContentOrientation(helmetCorrection, 0f)
        val twisted = PlacementRotation.legacyContentOrientation(helmetCorrection, 30f)
        val applied = PlacementRotation.appliedInAnchorFrame(current = twisted, rest = rest)

        // The twist became a full 30° of pitch: not a yaw at all.
        assertEquals(30f, PlacementRotation.tiltDegrees(applied), 1e-3f)

        // And the helmet's up axis left the vertical — it tipped a third of the way to
        // lying on its side.
        val upAfter = twisted * authoredUp
        assertEquals(0.5f, upAfter.x, 1e-4f)
        assertEquals(0.8660254f, upAfter.y, 1e-4f)
        assertTrue(
            "the legacy structure must NOT keep the model upright — if it does, this test " +
                "is no longer proving anything",
            upAfter.y < 0.999f,
        )
    }

    @Test
    fun `repeated small twists accumulate a yaw and drift into no pitch or roll`() {
        var pivot = Quaternion()
        repeat(5) { pivot = PlacementRotation.twist(pivot, 6f) }

        val rest = PlacementRotation.contentOrientation(Quaternion(), helmetCorrection)
        val twisted = PlacementRotation.contentOrientation(pivot, helmetCorrection)
        val applied = PlacementRotation.appliedInAnchorFrame(current = twisted, rest = rest)

        assertEquals(30f, PlacementRotation.yawDegrees(applied), 0.05f)
        assertEquals(0f, PlacementRotation.tiltDegrees(applied), 0.05f)
    }

    @Test
    fun `an asset with no correction keeps the behaviour it already had`() {
        // The non-regression half: for every bundled model but the helmet the correction is
        // identity, both structures agree, and nothing about this fix may change them.
        val pivot = PlacementRotation.twist(Quaternion(), 30f)
        val new = PlacementRotation.contentOrientation(pivot, noCorrection)
        val legacy = PlacementRotation.legacyContentOrientation(noCorrection, 30f)

        assertEquals(new.x, legacy.x, 1e-6f)
        assertEquals(new.y, legacy.y, 1e-6f)
        assertEquals(new.z, legacy.z, 1e-6f)
        assertEquals(new.w, legacy.w, 1e-6f)
        assertEquals(30f, PlacementRotation.yawDegrees(new), 1e-3f)
        assertEquals(0f, PlacementRotation.tiltDegrees(new), 1e-4f)
    }

    @Test
    fun `the asset correction never reaches the edited node, whatever the asset`() {
        // The editable node's rotation is a function of the twists and nothing else. Run the
        // same gesture against four very different corrections — including a `rotationOverride`
        // shape a caller could pass — and the pivot must come out identical every time, with
        // no tilt for the SDK's local-frame delta to convert into a tumble.
        val corrections = listOf(
            noCorrection,
            helmetCorrection,
            Quaternion.fromEuler(Rotation(y = 180f)),
            Quaternion.fromEuler(Rotation(z = 45f)),
        )
        var pivot = Quaternion()
        repeat(3) { pivot = PlacementRotation.twist(pivot, 15f) }
        assertEquals(0f, PlacementRotation.tiltDegrees(pivot), 1e-4f)
        assertEquals(45f, PlacementRotation.yawDegrees(pivot), 1e-3f)

        corrections.forEach { correction ->
            val rest = PlacementRotation.contentOrientation(Quaternion(), correction)
            val twisted = PlacementRotation.contentOrientation(pivot, correction)
            val applied = PlacementRotation.appliedInAnchorFrame(current = twisted, rest = rest)
            // Whatever the asset needed to stand up, the twist that reached the world is the
            // pivot itself: the correction cancels out of `current ∘ rest⁻¹`.
            assertEquals(pivot.x, applied.x, 1e-5f)
            assertEquals(pivot.y, applied.y, 1e-5f)
            assertEquals(pivot.z, applied.z, 1e-5f)
            assertEquals(pivot.w, applied.w, 1e-5f)
            assertEquals(0f, PlacementRotation.tiltDegrees(applied), 1e-4f)
        }
    }

    // ── PlacementScale: the 100 % detent ────────────────────────────────────

    @Test
    fun `range is anchored to the model's own base scale`() {
        // The regression this replaces: a fixed 0.1f..10f band on the raw node scale, which
        // for any model whose fitted scale is below 0.1 rejected the first pinch event.
        val base = 0.004f // a glTF authored large — fitted scale far below the old floor
        val range = PlacementScale.rangeFor(base)
        assertEquals(base * 0.25f, range.start, 1e-9f)
        assertEquals(base * 4f, range.endInclusive, 1e-9f)

        // A pinch out from rest must actually move, at any base scale.
        val next = PlacementScale.next(current = base, base = base, rawFactor = 1.4f)
        assertNotEquals(base, next)
        assertTrue("a pinch out must grow the model", next > base)
    }

    @Test
    fun `a pinch that lands near real-world size snaps to exactly it`() {
        val base = 0.5f
        // Aim for ~103 % — inside the 6 % detent band.
        val next = PlacementScale.next(current = base * 1.06f, base = base, rawFactor = 1.0f)
        assertEquals(base, next, 1e-9f)
        assertTrue(PlacementScale.isRealWorldSize(next, base))
        assertEquals(100, PlacementScale.percent(next, base))
    }

    @Test
    fun `a deliberate resize outside the detent band is not fought`() {
        val base = 0.5f
        val outside = base * 1.5f
        assertEquals(outside, PlacementScale.snap(outside, base), 1e-9f)
        assertFalse(PlacementScale.isRealWorldSize(outside, base))
        assertEquals(150, PlacementScale.percent(outside, base))
    }

    @Test
    fun `scale is clamped to the quarter-to-four band`() {
        val base = 1f
        // Repeatedly pinching in must stop at 25 %, not run to zero.
        var scale = base
        repeat(200) { scale = PlacementScale.next(scale, base, rawFactor = 0.5f) }
        assertEquals(base * PlacementScale.MIN_FACTOR, scale, 1e-6f)

        // …and out must stop at 400 %.
        scale = base
        repeat(200) { scale = PlacementScale.next(scale, base, rawFactor = 2f) }
        assertEquals(base * PlacementScale.MAX_FACTOR, scale, 1e-6f)
    }

    @Test
    fun `sensitivity damps the per-event delta the same way the SDK does`() {
        val base = 1f
        // 1 + (1.4 - 1) * 0.5 = 1.2 — the NodeGestureDelegate formula, so the feel matches
        // the rest of the SDK instead of inventing a second curve.
        assertEquals(1.2f, PlacementScale.next(1f, base, rawFactor = 1.4f, sensitivity = 0.5f), 1e-6f)
        assertEquals(1.4f, PlacementScale.next(1f, base, rawFactor = 1.4f, sensitivity = 1.0f), 1e-6f)
    }

    @Test
    fun `a zero base scale can never divide by zero or move the model`() {
        // A model whose bounding box is degenerate leaves scaleToUnits a no-op.
        assertEquals(2f, PlacementScale.next(current = 2f, base = 0f, rawFactor = 1.5f), 1e-9f)
        assertEquals(100, PlacementScale.percent(scale = 2f, base = 0f))
        assertFalse(PlacementScale.isRealWorldSize(2f, 0f))
    }

    // ── PlacementScale: the detent haptic ───────────────────────────────────

    @Test
    fun `the detent haptic fires once on entry and never while resting inside it`() {
        assertTrue(PlacementScale.shouldTickHaptic(wasRealWorldSize = false, isRealWorldSize = true))
        assertFalse(PlacementScale.shouldTickHaptic(wasRealWorldSize = true, isRealWorldSize = true))
        assertFalse(PlacementScale.shouldTickHaptic(wasRealWorldSize = true, isRealWorldSize = false))
        assertFalse(PlacementScale.shouldTickHaptic(wasRealWorldSize = false, isRealWorldSize = false))
    }

    // ── PlacementEntrance ───────────────────────────────────────────────────

    @Test
    fun `the arrival animation starts small, ends at exactly full size, and never overshoots`() {
        assertEquals(PlacementEntrance.START_FRACTION, PlacementEntrance.scaleFraction(0f), 1e-6f)
        assertEquals(1f, PlacementEntrance.scaleFraction(1f), 1e-6f)

        var previous = -1f
        var t = 0f
        while (t <= 1f) {
            val f = PlacementEntrance.scaleFraction(t)
            assertTrue("must be monotonic — a model that shrinks mid-arrival reads as a glitch", f >= previous)
            assertTrue("must never overshoot: a physical-scale object bouncing reads as wrong size", f <= 1f)
            previous = f
            t += 0.05f
        }
    }

    @Test
    fun `the arrival animation clamps out-of-range progress instead of extrapolating`() {
        assertEquals(PlacementEntrance.START_FRACTION, PlacementEntrance.scaleFraction(-1f), 1e-6f)
        assertEquals(1f, PlacementEntrance.scaleFraction(5f), 1e-6f)
    }

    @Test
    fun `the arrival animation eases out`() {
        // Past the halfway point in time, it must be past the halfway point in scale —
        // that is what "fast out of the gate, settling" means, and it is the difference
        // between an arrival and a linear ramp.
        val half = PlacementEntrance.scaleFraction(0.5f)
        val midpoint = PlacementEntrance.START_FRACTION + (1f - PlacementEntrance.START_FRACTION) / 2f
        assertTrue(half > midpoint)
    }

    // ── Coaching: one line at a time ────────────────────────────────────────

    @Test
    fun `the plane discovery guide owns every pre-surface phase`() {
        // The three states where the guide is on screen must say nothing here, or the user
        // reads two pills making the same request in different words.
        listOf(
            TapToPlaceUxState.INITIALIZING,
            TapToPlaceUxState.TRACKING_LOST,
            TapToPlaceUxState.SCANNING,
        ).forEach { uxState ->
            assertNull(
                "$uxState belongs to PlaneDiscoveryGuide",
                placementCoaching(uxState, placedCount = 0, gestureHintVisible = false),
            )
        }
    }

    @Test
    fun `aiming asks the user to point, ready invites the tap`() {
        assertEquals(
            PlacementCoachingMessage.POINT_AT_SURFACE,
            placementCoaching(TapToPlaceUxState.AIMING, placedCount = 0, gestureHintVisible = false),
        )
        assertEquals(
            PlacementCoachingMessage.TAP_TO_PLACE,
            placementCoaching(TapToPlaceUxState.READY, placedCount = 0, gestureHintVisible = false),
        )
    }

    @Test
    fun `the screen goes quiet once something is placed and the hint has expired`() {
        assertNull(
            placementCoaching(TapToPlaceUxState.READY, placedCount = 1, gestureHintVisible = false),
        )
        assertNull(
            placementCoaching(TapToPlaceUxState.AIMING, placedCount = 3, gestureHintVisible = false),
        )
    }

    @Test
    fun `the gesture hint outranks the placement prompts while its window is open`() {
        assertEquals(
            PlacementCoachingMessage.GESTURE_HINT,
            placementCoaching(TapToPlaceUxState.READY, placedCount = 1, gestureHintVisible = true),
        )
        assertEquals(
            PlacementCoachingMessage.GESTURE_HINT,
            placementCoaching(TapToPlaceUxState.AIMING, placedCount = 1, gestureHintVisible = true),
        )
    }

    // -- Coaching: the "AR never started" fallback ---------------------------

    @Test
    fun `the init scrim owns the wait, so a fresh INITIALIZING says nothing`() {
        // While the scrim is still up it is showing a spinner and "Starting camera…".
        // A second line saying the same thing is the duplication this whole layer removed.
        assertNull(
            placementCoaching(
                TapToPlaceUxState.INITIALIZING,
                placedCount = 0,
                gestureHintVisible = false,
                startupStalled = false,
            ),
        )
    }

    @Test
    fun `a stalled INITIALIZING says AR could not start`() {
        // Past the scrim's own timeout it has dismissed itself, and without this the screen
        // is a black viewport with no words on it and no phase willing to claim it.
        assertEquals(
            PlacementCoachingMessage.AR_UNAVAILABLE,
            placementCoaching(
                TapToPlaceUxState.INITIALIZING,
                placedCount = 0,
                gestureHintVisible = false,
                startupStalled = true,
            ),
        )
    }

    @Test
    fun `the fallback defaults to off, so it is opt-in per call site`() {
        assertNull(
            placementCoaching(
                TapToPlaceUxState.INITIALIZING,
                placedCount = 0,
                gestureHintVisible = false,
            ),
        )
    }

    @Test
    fun `a stalled flag can never surface once a session has started`() {
        // The load-bearing invariant. The state machine leaves INITIALIZING on the first
        // camera frame and never returns, so a stale `true` must be unreachable from every
        // other state — exhaustively, and under every combination of the other two inputs,
        // because the alternative is telling a user whose AR is working that it isn't.
        val started = TapToPlaceUxState.entries.filter { it != TapToPlaceUxState.INITIALIZING }
        started.forEach { uxState ->
            listOf(0, 1, 5).forEach { placedCount ->
                listOf(false, true).forEach { hintVisible ->
                    assertNotEquals(
                        "$uxState must never claim AR failed to start",
                        PlacementCoachingMessage.AR_UNAVAILABLE,
                        placementCoaching(
                            uxState = uxState,
                            placedCount = placedCount,
                            gestureHintVisible = hintVisible,
                            startupStalled = true,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `a stalled flag does not disturb the states that follow a started session`() {
        // Same inputs as the happy-path tests above, with the stale flag set: the answers
        // must be identical, or the fallback has leaked into the normal vocabulary.
        assertEquals(
            PlacementCoachingMessage.POINT_AT_SURFACE,
            placementCoaching(
                TapToPlaceUxState.AIMING,
                placedCount = 0,
                gestureHintVisible = false,
                startupStalled = true,
            ),
        )
        assertEquals(
            PlacementCoachingMessage.TAP_TO_PLACE,
            placementCoaching(
                TapToPlaceUxState.READY,
                placedCount = 0,
                gestureHintVisible = false,
                startupStalled = true,
            ),
        )
        assertNull(
            placementCoaching(
                TapToPlaceUxState.SCANNING,
                placedCount = 0,
                gestureHintVisible = false,
                startupStalled = true,
            ),
        )
    }

    @Test
    fun `the fallback waits until after the init scrim has given up`() {
        // Derived, not restated: if the two were equal the scrim's exit and this line's
        // entrance would race on the same frame, and if this were the smaller number they
        // would be on screen together saying different things about the same phase.
        assertTrue(PLACEMENT_STARTUP_STALL_MS > AR_CAMERA_INIT_SCRIM_TIMEOUT_MS)
    }

    @Test
    fun `an open hint window cannot resurrect coaching over a lost camera`() {
        // The guide is showing "move your phone" over a black or drifting frame; a stale
        // hint window must not stack a second pill on top of it.
        assertNull(
            placementCoaching(
                TapToPlaceUxState.TRACKING_LOST,
                placedCount = 1,
                gestureHintVisible = true,
            ),
        )
    }
}
