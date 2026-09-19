package io.github.sceneview

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of render-on-demand, with no Filament and no device.
 *
 * [FrameRateGate] is the whole of the default's behaviour: everything else in the change is wiring
 * that calls `requestRender()` or reports an active state. What is worth pinning here is not that
 * the gate says "yes" when something moved — that is one line — but the three properties that are
 * invisible in a diff and expensive on a device:
 *
 *  - it starts **dirty**, so a brand-new swap chain is never left blank;
 *  - it owes a **run** of frames after the last change, not one, because Filament finalises texture
 *    uploads and compiles material variants from inside the frame loop;
 *  - the run restarts on every change, so a gesture never gets a partial budget.
 */
class FrameRateGateTest {

    @Test
    fun startsDirtySoANewSwapChainIsNeverLeftBlank() {
        val gate = FrameRateGate()

        assertTrue(
            "a swap chain holds no pixels of its own: a gate that started settled would present " +
                "nothing into a brand-new surface and the view would be black (or transparent " +
                "with isOpaque = false) until something unrelated woke it (#3109)",
            gate.isDirty
        )
        assertTrue(gate.shouldRender(active = false))
    }

    @Test
    fun owesAFullRunOfFramesAfterTheLastChange() {
        val gate = FrameRateGate()
        var presented = 0

        while (gate.shouldRender(active = false)) {
            presented++
            gate.didRender()
            if (presented > SETTLE_FRAMES * 4) break
        }

        assertEquals(
            "the settle tail is what makes 'stops drawing' mean 'stops drawing the finished " +
                "picture': the first frame after a change is routinely not it — the Materials " +
                "demo needs ~4 presented frames over 6.3 s before the ToyCar's clearcoat " +
                "variants are warm, and a one-frame gate would freeze on the untextured one",
            SETTLE_FRAMES,
            presented
        )
        assertTrue(gate.isSettled)
    }

    @Test
    fun anActiveSceneNeverSettles() {
        val gate = FrameRateGate()

        repeat(SETTLE_FRAMES * 3) {
            assertTrue(
                "a finger on the screen, a playing animation or a decoding video must hold full " +
                    "cadence for as long as it lasts, not for one settle budget",
                gate.shouldRender(active = true)
            )
            gate.didRender()
            assertFalse(gate.isSettled)
        }
    }

    @Test
    fun aChangeMidSettleRestartsTheWholeRun() {
        val gate = FrameRateGate()
        repeat(SETTLE_FRAMES - 1) { gate.shouldRender(active = false); gate.didRender() }

        gate.requestRender()
        var presented = 0
        while (gate.shouldRender(active = false)) {
            presented++
            gate.didRender()
            if (presented > SETTLE_FRAMES * 4) break
        }

        assertEquals(
            "a change arriving on the last frame of a settle run must buy a full run, not the one " +
                "frame left over — otherwise the frames a change needs depend on when it landed",
            SETTLE_FRAMES,
            presented
        )
    }

    @Test
    fun requestRenderWritesSnapshotStateOnlyOnTheTransition() {
        val gate = FrameRateGate()
        gate.shouldRender(active = false)   // clears the dirty flag
        assertFalse(gate.isDirty)

        var writes = 0
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { writes++ })
        try {
            snapshot.enter { repeat(50) { gate.requestRender() } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }

        assertEquals(
            "requestRender is called from the touch dispatcher and from every Node transform " +
                "write, so it runs many times per frame on a moving scene. Writing snapshot state " +
                "on each one would invalidate derived state and wake the recomposer at exactly " +
                "the rate this change exists to remove.",
            1,
            writes
        )
    }

    // ── RenderInvalidator ────────────────────────────────────────────────────────────────────────

    @Test
    fun anInvalidatorReplaysTheRequestItReceivedBeforeAttach() {
        val invalidator = RenderInvalidator()
        val gate = FrameRateGate()
        gate.shouldRender(active = false)
        assertFalse(gate.isDirty)

        // A caller that edits a material in the same composition pass that creates the scene.
        invalidator.requestRender()
        invalidator.attach(gate)

        assertTrue(
            "dropping a request made before attach would make the escape hatch quietly racy: it " +
                "would work or not depending on composition order",
            gate.isDirty
        )
    }

    @Test
    fun aDetachedInvalidatorStopsReachingTheOldGate() {
        val invalidator = RenderInvalidator()
        val gate = FrameRateGate()
        invalidator.attach(gate)
        gate.shouldRender(active = false)

        invalidator.detach(gate)
        invalidator.requestRender()

        assertFalse("a disposed scene must not be kept awake by a surviving caller", gate.isDirty)
    }

    // ── Pull sources ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyPullSourceAloneKeepsTheSceneActive() {
        val none = BooleanArray(7)
        assertFalse(
            isSceneFrameActive(none[0], none[1], none[2], none[3], none[4], none[5], none[6])
        )
        repeat(none.size) { i ->
            val one = none.copyOf().also { it[i] = true }
            assertTrue(
                "pull source #$i must alone be enough to hold the cadence: each one is a state " +
                    "that lasts many frames and sends no event of its own",
                isSceneFrameActive(one[0], one[1], one[2], one[3], one[4], one[5], one[6])
            )
        }
    }

    // ── Cadence vote ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theVoteAsksForTheDisplayMaximumWhileSomethingMovesAndRestsAfter() {
        assertEquals(
            "max cadence during the gesture and the animation",
            120f,
            frameRateVote(FrameRatePolicy.OnDemand, active = true, maxRefreshRate = 120f),
            0f
        )
        assertEquals(
            "rest afterwards: 0 means 'no preference', which is what lets a variable refresh rate " +
                "panel drop to its idle mode — it is not a request for zero frames",
            0f,
            frameRateVote(FrameRatePolicy.OnDemand, active = false, maxRefreshRate = 120f),
            0f
        )
        assertEquals(
            30f,
            frameRateVote(FrameRatePolicy.Capped(30), active = false, maxRefreshRate = 120f),
            0f
        )
        assertEquals(
            120f,
            frameRateVote(FrameRatePolicy.Continuous, active = false, maxRefreshRate = 120f),
            0f
        )
        assertEquals(
            "an unknown display gets no vote rather than a guessed number it may not support",
            0f,
            frameRateVote(FrameRatePolicy.Continuous, active = true, maxRefreshRate = null),
            0f
        )
    }

    @Test
    fun aCapPhaseLocksOntoARefreshRateItDoesNotDivideEvenly() {
        val vsync = 1_000_000_000L / 60
        // `withFrameNanos` hands out system uptime, never 0 — and 0 is this function's "no frame
        // presented yet" sentinel, so a test starting at 0 would measure the sentinel, not the cap.
        val base = 1_000_000_000L
        var last = 0L
        var presented = 0
        // One virtual second of 60 Hz vsyncs under a 30 fps cap.
        for (i in 0 until 60) {
            val now = base + i * vsync
            if (shouldPresentAtCap(fps = 30, frameTimeNanos = now, lastPresentNanos = last)) {
                presented++
                last = now
            }
        }

        assertEquals(
            "a strict comparison rejects the vsync at 33.3 ms by a rounding hair and settles at " +
                "one frame per three vsyncs — 20 fps, not the 30 that was asked for",
            30,
            presented
        )
    }

    @Test
    fun theFirstFrameIsNeverHeldBackByACap() {
        assertTrue(shouldPresentAtCap(fps = 1, frameTimeNanos = 0L, lastPresentNanos = 0L))
    }
}
