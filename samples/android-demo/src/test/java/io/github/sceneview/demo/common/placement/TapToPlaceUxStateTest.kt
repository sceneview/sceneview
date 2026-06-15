package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [deriveUxState] — the headless-testable core of the
 * tap-to-place UX state machine
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482)).
 *
 * Pins the priority order the on-screen status pill depends on:
 *   - `!cameraReady` ⇒ [TapToPlaceUxState.INITIALIZING] beats everything (the scrim
 *     covers all pills).
 *   - `!isTracking || forcedFailure` ⇒ [TapToPlaceUxState.TRACKING_LOST] beats plane /
 *     reticle state.
 *   - `!anyPlaneTracked` ⇒ [TapToPlaceUxState.SCANNING] (#2234 gate — never claim "tap a
 *     surface" before a plane exists).
 *   - `!hasReticleTarget` ⇒ [TapToPlaceUxState.AIMING], else [TapToPlaceUxState.READY].
 */
class TapToPlaceUxStateTest {

    // ── INITIALIZING wins over everything ───────────────────────────────────

    @Test
    fun `not camera ready is INITIALIZING regardless of all other signals`() {
        // Every other signal set to its "most advanced" value — INITIALIZING must
        // still win because the scrim covers all pills until the first frame lands.
        assertEquals(
            TapToPlaceUxState.INITIALIZING,
            deriveUxState(
                cameraReady = false,
                isTracking = true,
                forcedFailure = true,
                anyPlaneTracked = true,
                hasReticleTarget = true,
            ),
        )
    }

    @Test
    fun `not camera ready beats a forced failure`() {
        assertEquals(
            TapToPlaceUxState.INITIALIZING,
            deriveUxState(
                cameraReady = false,
                isTracking = false,
                forcedFailure = true,
                anyPlaneTracked = false,
                hasReticleTarget = false,
            ),
        )
    }

    // ── TRACKING_LOST: real loss or forced failure ──────────────────────────

    @Test
    fun `camera ready but not tracking is TRACKING_LOST`() {
        assertEquals(
            TapToPlaceUxState.TRACKING_LOST,
            deriveUxState(
                cameraReady = true,
                isTracking = false,
                forcedFailure = false,
                anyPlaneTracked = true,
                hasReticleTarget = true,
            ),
        )
    }

    @Test
    fun `forced failure beats plane and reticle state`() {
        // Tracking is healthy and a plane + reticle target exist, but the #1881 QA
        // override forces the failure pill on both surfaces.
        assertEquals(
            TapToPlaceUxState.TRACKING_LOST,
            deriveUxState(
                cameraReady = true,
                isTracking = true,
                forcedFailure = true,
                anyPlaneTracked = true,
                hasReticleTarget = true,
            ),
        )
    }

    // ── SCANNING: tracking but no plane yet (#2234) ──────────────────────────

    @Test
    fun `tracking with no plane is SCANNING even with a reticle target`() {
        // anyPlaneTracked is the #2234 gate: without a tracked plane we must not
        // claim "tap a surface", even if a stray hit produced a reticle target.
        assertEquals(
            TapToPlaceUxState.SCANNING,
            deriveUxState(
                cameraReady = true,
                isTracking = true,
                forcedFailure = false,
                anyPlaneTracked = false,
                hasReticleTarget = true,
            ),
        )
    }

    // ── AIMING vs READY pivots on the reticle target ─────────────────────────

    @Test
    fun `plane tracked but no reticle target is AIMING`() {
        assertEquals(
            TapToPlaceUxState.AIMING,
            deriveUxState(
                cameraReady = true,
                isTracking = true,
                forcedFailure = false,
                anyPlaneTracked = true,
                hasReticleTarget = false,
            ),
        )
    }

    @Test
    fun `plane tracked with a reticle target is READY`() {
        assertEquals(
            TapToPlaceUxState.READY,
            deriveUxState(
                cameraReady = true,
                isTracking = true,
                forcedFailure = false,
                anyPlaneTracked = true,
                hasReticleTarget = true,
            ),
        )
    }

    // ── Exhaustive 2^5 truth table cross-check ───────────────────────────────

    @Test
    fun `exhaustive truth table matches the documented priority order`() {
        allSignalCombinations().forEach { c ->
            val actual = deriveUxState(
                c.cameraReady,
                c.isTracking,
                c.forcedFailure,
                c.anyPlaneTracked,
                c.hasReticleTarget,
            )
            val expected = expectedUxState(
                c.cameraReady,
                c.isTracking,
                c.forcedFailure,
                c.anyPlaneTracked,
                c.hasReticleTarget,
            )
            assertEquals(c.toString(), expected, actual)
        }
    }

    private data class SignalCombo(
        val cameraReady: Boolean,
        val isTracking: Boolean,
        val forcedFailure: Boolean,
        val anyPlaneTracked: Boolean,
        val hasReticleTarget: Boolean,
    )

    /** All 2^5 = 32 input combinations, generated without nested control flow. */
    private fun allSignalCombinations(): List<SignalCombo> =
        (0 until 32).map { bits ->
            SignalCombo(
                cameraReady = bits and 0b00001 != 0,
                isTracking = bits and 0b00010 != 0,
                forcedFailure = bits and 0b00100 != 0,
                anyPlaneTracked = bits and 0b01000 != 0,
                hasReticleTarget = bits and 0b10000 != 0,
            )
        }

    /** Independent reference implementation of the priority order under test. */
    private fun expectedUxState(
        cameraReady: Boolean,
        isTracking: Boolean,
        forcedFailure: Boolean,
        anyPlaneTracked: Boolean,
        hasReticleTarget: Boolean,
    ): TapToPlaceUxState {
        if (!cameraReady) return TapToPlaceUxState.INITIALIZING
        if (!isTracking || forcedFailure) return TapToPlaceUxState.TRACKING_LOST
        if (!anyPlaneTracked) return TapToPlaceUxState.SCANNING
        if (!hasReticleTarget) return TapToPlaceUxState.AIMING
        return TapToPlaceUxState.READY
    }
}
