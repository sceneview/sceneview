package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression pins for [FramingGate] — the union-diagonal-stability gate that drives
 * `SceneView`'s library-level `autoCenterContent` ([SceneAutoCenterState]) and `autoFitContent`
 * ([SceneAutoFitState]) passes.
 *
 * Android port of the web `AutoCenterGateTest`. The headline case is **#1596 / #1540**: an async
 * model that finishes loading *after* a sibling has already framed must still re-frame the scene.
 * The previous first-stable-frame latch froze the framing on whichever model loaded first, leaving
 * later async models bunched in the corner — the multi-model bug #1391 fixed on web / iOS.
 * [deferredAsyncModelReFramesAfterFirstModelFramed] pins that.
 *
 * The second headline case is the **Tier-2 #1596 review** bug: a scene whose union diagonal jitters
 * above the stability epsilon on every frame (animated model, physics demo) never satisfies the
 * diagonal-stability latch, so the gate must latch via the [FramingGate.MAX_FRAMING_PASSES] ceiling
 * instead of re-framing forever. [perpetuallyJitteringSceneLatchesWithinTheCeiling] pins that.
 */
class FramingGateTest {

    @Test
    fun freshGateHasNotLatchedYet() {
        val gate = FramingGate()
        assertFalse("a fresh gate must not have latched anything yet", gate.latched)
    }

    @Test
    fun shouldRunWhenContentPresentAndNotYetLatched() {
        val gate = FramingGate()
        assertTrue(
            "the first frame with loaded content must run the framing pass",
            gate.shouldRun(hasContent = true)
        )
    }

    @Test
    fun shouldNotRunWhenThereIsNoContent() {
        val gate = FramingGate()
        assertFalse(
            "with no content bounds there is nothing to frame",
            gate.shouldRun(hasContent = false)
        )
    }

    /** The very first frame always frames — there is no prior diagonal. */
    @Test
    fun firstFrameAlwaysFrames() {
        val gate = FramingGate()
        assertTrue(
            "the first pass has no prior diagonal so it must always frame",
            gate.shouldFrame(diagonal = 2f)
        )
    }

    /**
     * Two consecutive frames at the same diagonal settle the union, so the second recording
     * latches the gate and later frames are a no-op.
     */
    @Test
    fun stableDiagonalAcrossTwoFramesLatchesTheGate() {
        val gate = FramingGate()
        // Frame 1: first non-degenerate bounds — frames and records.
        assertTrue(gate.shouldFrame(2f))
        gate.recordFraming(2f)
        assertFalse("one framed pass alone must not latch — need a stable 2nd", gate.latched)

        // Frame 2: diagonal unchanged -> stable -> latch.
        assertFalse(
            "an unchanged diagonal must not trigger another re-frame",
            gate.shouldFrame(2f)
        )
        gate.recordFraming(2f)
        assertTrue("a diagonal stable across two frames must latch the gate", gate.latched)

        // Latched -> the pass is dormant.
        assertFalse(
            "once latched, later frames must not run the pass",
            gate.shouldRun(hasContent = true)
        )
    }

    /**
     * #1596 / #1540: the core regression. A first model frames and the diagonal settles; an async
     * sibling then lands and grows the union — the gate must re-frame instead of staying latched
     * on the first model's framing.
     */
    @Test
    fun deferredAsyncModelReFramesAfterFirstModelFramed() {
        val gate = FramingGate()

        // Frames 1-2: a single model loads, its 1.0 m diagonal settles, latch.
        assertTrue(gate.shouldFrame(1f))
        gate.recordFraming(1f)
        assertFalse(gate.shouldFrame(1f))
        gate.recordFraming(1f)
        assertTrue("the first model must latch once its diagonal is stable", gate.latched)

        // A 2nd model finishes loading async — grows the union materially.
        // With the old first-stable-frame latch this never re-framed; the diagonal-stability gate
        // must pick the growth up once re-armed.
        gate.reset()
        assertFalse("reset() must un-latch so the grown union re-frames", gate.latched)
        assertTrue(
            "#1596: the deferred async model must re-arm the framing pass",
            gate.shouldRun(hasContent = true)
        )

        // The union is now larger — the pass must re-frame.
        val grownDiagonal = 3.5f
        assertTrue("#1596: a grown union must re-frame the scene", gate.shouldFrame(grownDiagonal))
        gate.recordFraming(grownDiagonal)
        assertFalse("one framed pass at the grown size must not latch yet", gate.latched)

        // Diagonal now settles at the grown size -> latch on the new framing.
        assertFalse(gate.shouldFrame(grownDiagonal))
        gate.recordFraming(grownDiagonal)
        assertTrue("the grown union must latch once it settles", gate.latched)
    }

    /**
     * #1596 / #1540: a streamed model whose bounds keep arriving frame-by-frame must keep
     * re-framing until the union diagonal finally settles — the gate must not latch on a transient
     * intermediate size.
     */
    @Test
    fun growingUnionKeepsReFramingUntilSettled() {
        val gate = FramingGate()

        // The union grows over four frames before settling.
        val diagonals = listOf(1f, 1.8f, 2.6f, 2.6f)
        var lastFramed = false
        for ((i, d) in diagonals.withIndex()) {
            val shouldFrame = gate.shouldFrame(d)
            gate.recordFraming(d)
            lastFramed = shouldFrame
            if (i < diagonals.lastIndex) {
                assertFalse(
                    "must not latch while the union diagonal is still growing (frame $i)",
                    gate.latched
                )
            }
        }
        assertFalse("the final frame's unchanged diagonal must not re-frame", lastFramed)
        assertTrue("the gate must latch once the union diagonal settles", gate.latched)
    }

    /**
     * A jitter below [FramingGate.STABILITY_EPSILON] of the diagonal must be treated as settled —
     * sub-millimetre float noise must not keep the pass alive forever.
     */
    @Test
    fun subEpsilonJitterCountsAsStable() {
        val gate = FramingGate()
        gate.recordFraming(10f)
        // 10.0 -> 10.05 is 0.5% — below the 1% epsilon.
        assertFalse(
            "a sub-epsilon diagonal change must not trigger a re-frame",
            gate.shouldFrame(10.05f)
        )
        gate.recordFraming(10.05f)
        assertTrue("sub-epsilon jitter must count as a stable, latching frame", gate.latched)
    }

    /** A diagonal change above the epsilon — a genuinely larger model — must always re-frame. */
    @Test
    fun supraEpsilonGrowthAlwaysReFrames() {
        val gate = FramingGate()
        gate.recordFraming(10f)
        // 10.0 -> 10.5 is 5% — well above the 1% epsilon.
        assertTrue("a supra-epsilon diagonal change must re-frame", gate.shouldFrame(10.5f))
    }

    /**
     * Tier-2 #1596 review regression: a scene whose union diagonal jitters by *more* than
     * [FramingGate.STABILITY_EPSILON] on **every** frame — an animated / skeletal model, a physics
     * demo, or two async models alternately growing the union — never satisfies the
     * diagonal-stability latch. Without the [FramingGate.MAX_FRAMING_PASSES] ceiling the gate would
     * re-frame forever, fighting user interaction and burning a `computeContentBounds` walk per
     * frame. The ceiling must force a deterministic latch.
     */
    @Test
    fun perpetuallyJitteringSceneLatchesWithinTheCeiling() {
        val gate = FramingGate()
        // A diagonal that swings ~5% (well above the 1% epsilon) every frame — never settles.
        var diagonal = 2f
        var grow = true
        var passes = 0
        // Drive far more frames than the ceiling: the gate MUST latch before we run out.
        repeat(FramingGate.MAX_FRAMING_PASSES * 4) {
            if (!gate.latched && gate.shouldRun(hasContent = true)) {
                // Every frame `shouldFrame` is true because the jitter exceeds the epsilon.
                assertTrue(
                    "a supra-epsilon jitter must always want to re-frame while unlatched",
                    gate.shouldFrame(diagonal)
                )
                gate.recordFraming(diagonal)
                passes++
                diagonal = if (grow) diagonal * 1.05f else diagonal / 1.05f
                grow = !grow
            }
        }
        assertTrue(
            "a perpetually-jittering scene must latch via the MAX_FRAMING_PASSES ceiling",
            gate.latched
        )
        assertEquals(
            "the gate must latch exactly at the framing-pass ceiling, not run unbounded",
            FramingGate.MAX_FRAMING_PASSES,
            passes
        )
        assertFalse(
            "once the ceiling latches the gate, later frames must be a no-op",
            gate.shouldRun(hasContent = true)
        )
    }

    /**
     * The ceiling must not punish a scene that legitimately needs a few staggered re-frames (the
     * #1391 async-model case): a handful of growing-then-settling frames — fewer than the ceiling —
     * must still latch via the *stability* path, not the ceiling.
     */
    @Test
    fun aFewStaggeredLoadsStillLatchViaStabilityNotTheCeiling() {
        val gate = FramingGate()
        // 5 growing frames + a settled repeat — 6 passes total, under the 10-pass ceiling.
        val diagonals = listOf(1f, 1.5f, 2.1f, 2.8f, 3.5f, 3.5f)
        for (d in diagonals) {
            gate.shouldFrame(d)
            gate.recordFraming(d)
        }
        assertTrue(
            "a scene that settles within the ceiling must latch via the stability path",
            gate.latched
        )
    }

    @Test
    fun resetClearsTheLatchAndTheRecordedDiagonal() {
        val gate = FramingGate()
        gate.recordFraming(2f)
        gate.recordFraming(2f)
        assertTrue(gate.latched)

        gate.reset()
        assertFalse("reset() must clear the latch", gate.latched)
        assertTrue(
            "reset() must clear the recorded diagonal so the next frame re-frames",
            gate.shouldFrame(2f)
        )
    }

    @Test
    fun resetIsIdempotentBeforeAnyFraming() {
        val gate = FramingGate()
        gate.reset()
        gate.reset()
        assertFalse(gate.latched)
        assertTrue("repeated resets must leave the gate armed", gate.shouldRun(hasContent = true))
    }

    /**
     * Simulate a full multi-model session: load -> settle -> load -> settle. Each re-armed
     * generation must latch independently at its own union size.
     */
    @Test
    fun reFramingWorksAcrossMultipleModelLoads() {
        val gate = FramingGate()
        val unionSizes = listOf(1f, 2.5f, 4f)
        for ((load, size) in unionSizes.withIndex()) {
            gate.reset() // each content replacement re-arms
            assertTrue(
                "model load #$load must re-arm the framing pass",
                gate.shouldRun(hasContent = true)
            )
            // Frame twice at the same size so the union settles and latches.
            gate.recordFraming(size)
            gate.recordFraming(size)
            assertTrue("after model load #$load settles, the pass must latch", gate.latched)
        }
    }

    // ── Aabb.diagonal — the scalar the gate tracks ────────────────────────────────────────────

    @Test
    fun emptyAabbHasZeroDiagonal() {
        assertEquals("an empty box has no measurable diagonal", 0f, Aabb().diagonal, 1e-6f)
    }

    @Test
    fun unitCubeDiagonalIsSqrt3() {
        // A 1×1×1 cube has a space diagonal of sqrt(3).
        val cube = Aabb(halfExtent = io.github.sceneview.math.Position(0.5f, 0.5f, 0.5f))
        assertEquals(kotlin.math.sqrt(3f), cube.diagonal, 1e-5f)
    }

    @Test
    fun diagonalGrowsWithAUnionAddingAModel() {
        // #1596 in geometric terms: when an async model joins the union, the diagonal — which is
        // what the gate compares frame-to-frame — grows, so `shouldFrame` fires a re-frame.
        val firstModel = Aabb.fromMinMax(
            io.github.sceneview.math.Position(-0.5f, -0.5f, -0.5f),
            io.github.sceneview.math.Position(0.5f, 0.5f, 0.5f)
        )
        val secondModel = Aabb.fromMinMax(
            io.github.sceneview.math.Position(2f, 2f, 2f),
            io.github.sceneview.math.Position(3f, 3f, 3f)
        )
        val union = firstModel.union(secondModel)
        assertTrue(
            "the union diagonal must exceed either model's own diagonal",
            union.diagonal > firstModel.diagonal && union.diagonal > secondModel.diagonal
        )
    }
}
