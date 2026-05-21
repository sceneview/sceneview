package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [AutoCenterGate] — the union-diagonal-stability gate
 * that drives `SceneView`'s library-level `autoCenterContent` pass.
 *
 * The headline case is **#1540**: an async model that finishes loading *after*
 * a sibling has already centred must still re-frame the scene. The previous
 * first-stable-frame latch froze the framing on whichever model loaded first,
 * leaving later async models bunched in the corner — the multi-model bug #1391
 * fixed on iOS. [deferredAsyncModelReFramesAfterFirstModelCentered] pins that.
 *
 * The second headline case is **#1633**: a scene whose union diagonal jitters
 * above the stability epsilon on every frame (animated model, physics demo)
 * never satisfies the diagonal-stability latch, so the gate must latch via the
 * [AutoCenterGate.MAX_FRAMING_PASSES] ceiling instead of re-framing forever.
 * [perpetuallyJitteringSceneLatchesWithinTheCeiling] pins that — parity with
 * Android's `FramingGateTest`.
 */
class AutoCenterGateTest {

    @Test
    fun freshGateHasNotCenteredYet() {
        val gate = AutoCenterGate()
        assertFalse(gate.didCenter, "a fresh gate must not have centred anything yet")
    }

    @Test
    fun shouldRunWhenEnabledWithContentAndNotYetLatched() {
        val gate = AutoCenterGate()
        assertTrue(
            gate.shouldRun(enabled = true, hasContent = true),
            "the first frame with loaded content must run the centring pass",
        )
    }

    @Test
    fun shouldNotRunWhenAutoCenterContentIsDisabled() {
        val gate = AutoCenterGate()
        assertFalse(
            gate.shouldRun(enabled = false, hasContent = true),
            "autoCenterContent = false must suppress the pass entirely",
        )
    }

    @Test
    fun shouldNotRunWhenThereIsNoContent() {
        val gate = AutoCenterGate()
        assertFalse(
            gate.shouldRun(enabled = true, hasContent = false),
            "with no models loaded there is nothing to centre",
        )
    }

    /** The very first frame always frames — there is no prior diagonal. */
    @Test
    fun firstFrameAlwaysFrames() {
        val gate = AutoCenterGate()
        assertTrue(
            gate.shouldFrame(diagonal = 2.0),
            "the first pass has no prior diagonal so it must always frame",
        )
    }

    /**
     * Two consecutive frames at the same diagonal settle the union, so the
     * second recording latches the gate and later frames are a no-op.
     */
    @Test
    fun stableDiagonalAcrossTwoFramesLatchesTheGate() {
        val gate = AutoCenterGate()
        // Frame 1: first non-degenerate bounds — frames and records.
        assertTrue(gate.shouldFrame(2.0))
        gate.recordFraming(2.0)
        assertFalse(gate.didCenter, "one framed pass alone must not latch — need a stable 2nd")

        // Frame 2: diagonal unchanged -> stable -> latch.
        assertFalse(
            gate.shouldFrame(2.0),
            "an unchanged diagonal must not trigger another re-frame",
        )
        gate.recordFraming(2.0)
        assertTrue(gate.didCenter, "a diagonal stable across two frames must latch the gate")

        // Latched -> the pass is dormant.
        assertFalse(
            gate.shouldRun(enabled = true, hasContent = true),
            "once latched, later frames must not run the pass",
        )
    }

    /**
     * #1540: the core regression. A first model centres and the diagonal
     * settles; an async sibling then lands and grows the union — the gate must
     * re-frame instead of staying latched on the first model's framing.
     */
    @Test
    fun deferredAsyncModelReFramesAfterFirstModelCentered() {
        val gate = AutoCenterGate()

        // Frames 1-2: a single model loads, its 1.0 m diagonal settles, latch.
        assertTrue(gate.shouldFrame(1.0))
        gate.recordFraming(1.0)
        assertFalse(gate.shouldFrame(1.0))
        gate.recordFraming(1.0)
        assertTrue(gate.didCenter, "the first model must latch once its diagonal is stable")

        // A 2nd model finishes loading async — grows the union materially.
        // With the old first-stable-frame latch this never re-framed; the
        // diagonal-stability gate must pick the growth up once re-armed.
        gate.reset()
        assertFalse(gate.didCenter, "reset() must un-latch so the grown union re-frames")
        assertTrue(
            gate.shouldRun(enabled = true, hasContent = true),
            "#1540: the deferred async model must re-arm the centring pass",
        )

        // The union is now larger — the pass must re-frame.
        val grownDiagonal = 3.5
        assertTrue(
            gate.shouldFrame(grownDiagonal),
            "#1540: a grown union must re-frame the scene",
        )
        gate.recordFraming(grownDiagonal)
        assertFalse(gate.didCenter, "one framed pass at the grown size must not latch yet")

        // Diagonal now settles at the grown size -> latch on the new framing.
        assertFalse(gate.shouldFrame(grownDiagonal))
        gate.recordFraming(grownDiagonal)
        assertTrue(gate.didCenter, "the grown union must latch once it settles")
    }

    /**
     * #1540: a streamed model whose bounds keep arriving frame-by-frame must
     * keep re-framing until the union diagonal finally settles — the gate must
     * not latch on a transient intermediate size.
     */
    @Test
    fun growingUnionKeepsReFramingUntilSettled() {
        val gate = AutoCenterGate()

        // The union grows over four frames before settling.
        val diagonals = listOf(1.0, 1.8, 2.6, 2.6)
        var lastFramed = false
        for ((i, d) in diagonals.withIndex()) {
            val shouldFrame = gate.shouldFrame(d)
            gate.recordFraming(d)
            lastFramed = shouldFrame
            if (i < diagonals.lastIndex) {
                assertFalse(
                    gate.didCenter,
                    "must not latch while the union diagonal is still growing (frame $i)",
                )
            }
        }
        assertFalse(lastFramed, "the final frame's unchanged diagonal must not re-frame")
        assertTrue(gate.didCenter, "the gate must latch once the union diagonal settles")
    }

    /**
     * A jitter below [AutoCenterGate.STABILITY_EPSILON] of the diagonal must be
     * treated as settled — sub-millimetre float noise must not keep the pass
     * alive forever.
     */
    @Test
    fun subEpsilonJitterCountsAsStable() {
        val gate = AutoCenterGate()
        gate.recordFraming(10.0)
        // 10.0 -> 10.05 is 0.5% — below the 1% epsilon.
        assertFalse(
            gate.shouldFrame(10.05),
            "a sub-epsilon diagonal change must not trigger a re-frame",
        )
        gate.recordFraming(10.05)
        assertTrue(gate.didCenter, "sub-epsilon jitter must count as a stable, latching frame")
    }

    /**
     * A diagonal change above the epsilon — e.g. a genuinely larger model —
     * must always re-frame.
     */
    @Test
    fun supraEpsilonGrowthAlwaysReFrames() {
        val gate = AutoCenterGate()
        gate.recordFraming(10.0)
        // 10.0 -> 10.5 is 5% — well above the 1% epsilon.
        assertTrue(
            gate.shouldFrame(10.5),
            "a supra-epsilon diagonal change must re-frame",
        )
    }

    /**
     * #1633: a scene whose union diagonal jitters by *more* than
     * [AutoCenterGate.STABILITY_EPSILON] on **every** frame — an animated /
     * skeletal model, a physics demo, or two async models alternately growing
     * the union — never satisfies the diagonal-stability latch. Without the
     * [AutoCenterGate.MAX_FRAMING_PASSES] ceiling the gate would re-frame
     * forever, fighting user interaction and burning a content-bounds walk per
     * frame. The ceiling must force a deterministic latch. Parity with
     * Android's `FramingGateTest.perpetuallyJitteringSceneLatchesWithinTheCeiling`.
     */
    @Test
    fun perpetuallyJitteringSceneLatchesWithinTheCeiling() {
        val gate = AutoCenterGate()
        // A diagonal that swings ~5% (well above the 1% epsilon) every frame —
        // never settles.
        var diagonal = 2.0
        var grow = true
        var passes = 0
        // Drive far more frames than the ceiling: the gate MUST latch before
        // we run out.
        repeat(AutoCenterGate.MAX_FRAMING_PASSES * 4) {
            if (!gate.didCenter && gate.shouldRun(enabled = true, hasContent = true)) {
                // Every frame `shouldFrame` is true because the jitter exceeds
                // the epsilon.
                assertTrue(
                    gate.shouldFrame(diagonal),
                    "a supra-epsilon jitter must always want to re-frame while unlatched",
                )
                gate.recordFraming(diagonal)
                passes++
                diagonal = if (grow) diagonal * 1.05 else diagonal / 1.05
                grow = !grow
            }
        }
        assertTrue(
            gate.didCenter,
            "a perpetually-jittering scene must latch via the MAX_FRAMING_PASSES ceiling",
        )
        assertEquals(
            AutoCenterGate.MAX_FRAMING_PASSES,
            passes,
            "the gate must latch exactly at the framing-pass ceiling, not run unbounded",
        )
        assertFalse(
            gate.shouldRun(enabled = true, hasContent = true),
            "once the ceiling latches the gate, later frames must be a no-op",
        )
    }

    /**
     * The ceiling must not punish a scene that legitimately needs a few
     * staggered re-frames (the #1391 async-model case): a handful of
     * growing-then-settling frames — fewer than the ceiling — must still latch
     * via the *stability* path, not the ceiling.
     */
    @Test
    fun aFewStaggeredLoadsStillLatchViaStabilityNotTheCeiling() {
        val gate = AutoCenterGate()
        // 5 growing frames + a settled repeat — 6 passes total, under the
        // 10-pass ceiling.
        val diagonals = listOf(1.0, 1.5, 2.1, 2.8, 3.5, 3.5)
        var passes = 0
        for (d in diagonals) {
            gate.shouldFrame(d)
            gate.recordFraming(d)
            passes++
        }
        assertTrue(
            gate.didCenter,
            "a scene that settles within the ceiling must latch via the stability path",
        )
        assertTrue(
            passes < AutoCenterGate.MAX_FRAMING_PASSES,
            "the staggered-async case must latch before hitting the ceiling",
        )
    }

    @Test
    fun resetClearsTheLatchAndTheRecordedDiagonal() {
        val gate = AutoCenterGate()
        gate.recordFraming(2.0)
        gate.recordFraming(2.0)
        assertTrue(gate.didCenter)

        gate.reset()
        assertFalse(gate.didCenter, "reset() must clear the latch")
        assertTrue(
            gate.shouldFrame(2.0),
            "reset() must clear the recorded diagonal so the next frame re-frames",
        )
    }

    @Test
    fun resetIsIdempotentBeforeAnyFraming() {
        val gate = AutoCenterGate()
        gate.reset()
        gate.reset()
        assertFalse(gate.didCenter)
        assertTrue(
            gate.shouldRun(enabled = true, hasContent = true),
            "repeated resets must leave the gate armed",
        )
    }

    /**
     * Simulate a full multi-model session: load -> settle -> load -> settle.
     * Each re-armed generation must latch independently at its own union size.
     */
    @Test
    fun reFramingWorksAcrossMultipleModelLoads() {
        val gate = AutoCenterGate()
        val unionSizes = listOf(1.0, 2.5, 4.0)
        for ((load, size) in unionSizes.withIndex()) {
            gate.reset() // each loadModel() re-arms
            assertTrue(
                gate.shouldRun(enabled = true, hasContent = true),
                "model load #$load must re-arm the centring pass",
            )
            // Frame twice at the same size so the union settles and latches.
            gate.recordFraming(size)
            gate.recordFraming(size)
            assertTrue(
                gate.didCenter,
                "after model load #$load settles, the pass must latch",
            )
        }
    }
}
