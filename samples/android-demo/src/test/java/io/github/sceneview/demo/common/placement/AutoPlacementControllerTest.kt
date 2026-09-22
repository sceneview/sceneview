package io.github.sceneview.demo.common.placement

import io.github.sceneview.demo.common.placement.AutoPlacementController.Companion.NO_SURFACE_TIMEOUT_MS
import io.github.sceneview.demo.common.placement.AutoPlacementController.Companion.RECOVERY_TIMEOUT_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic placement decision, pinned on the JVM.
 *
 * The AR emulator replays no ARCore frames, so nothing below can be reached through the
 * UI on CI. [AutoPlacementController] is therefore a pure state machine fed one
 * [FrameInput] per frame, and this file is where the plan's invariants live:
 *
 *  - **one request, one placement** — 100 frames with a surface and any number of
 *    background taps create exactly one anchor;
 *  - **a tracking interruption never creates a second anchor** and never resets to
 *    scanning (§2.2);
 *  - **stale asset results cannot land** — a ticket minted before `dismiss()` or before
 *    the next `selectModel()` is refused;
 *  - the **10 s** timeouts, at exactly 10 s and not a frame before;
 *  - the phase transitions the overlays render.
 */
class AutoPlacementControllerTest {

    // ── One placement per request ─────────────────────────────────────────────────────

    @Test
    fun `a request is consumed by exactly one placement across 100 frames`() {
        val c = AutoPlacementController()
        c.requestPlacement()

        val effects = (0 until 100).map { i ->
            c.onFrame(FrameInput(nowMillis = i * 16L, tracking = true, surfaceAvailable = true))
        }

        assertEquals(1, effects.count { it == FrameEffect.PLACE })
        assertEquals(FrameEffect.PLACE, effects.first())
        assertEquals(1, c.placementsCreated)
        assertEquals(PlacementPhase.PLACED, c.phase)
        assertFalse("the request must be consumed", c.placementRequested)
        assertFalse("nothing should be searching once placed", c.wantsSurface)
    }

    @Test
    fun `repeated background taps never place anything`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, tracking = true, surfaceAvailable = true))

        repeat(50) { assertEquals(FrameEffect.NONE, c.onBackgroundTap()) }
        repeat(100) { i ->
            c.onBackgroundTap()
            c.onFrame(FrameInput(16L * i, tracking = true, surfaceAvailable = true, anchorTracking = true))
        }

        assertEquals(1, c.placementsCreated)
    }

    @Test
    fun `a background tap before any surface does not place either`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        repeat(20) {
            assertEquals(FrameEffect.NONE, c.onBackgroundTap())
            assertEquals(FrameEffect.NONE, c.onFrame(FrameInput(16L * it, true, surfaceAvailable = false)))
        }
        assertEquals(0, c.placementsCreated)
    }

    @Test
    fun `requestPlacement is idempotent while pending and a no-op once placed`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.requestPlacement()
        c.onFrame(FrameInput(16L, true, surfaceAvailable = true, anchorTracking = true))
        assertEquals(1, c.placementsCreated)
    }

    @Test
    fun `nothing searches before an asset is offered`() {
        val c = AutoPlacementController()
        assertFalse(c.wantsSurface)
        repeat(10) { c.onFrame(FrameInput(16L * it, true, surfaceAvailable = true)) }
        assertEquals(0, c.placementsCreated)
        assertEquals("frames without a request still leave INITIALIZING", PlacementPhase.SCANNING, c.phase)
    }

    // ── Tracking loss ─────────────────────────────────────────────────────────────────

    @Test
    fun `tracking loss and recovery cannot create another anchor`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))

        assertEquals(FrameEffect.TRACKING_LOST, c.onFrame(FrameInput(100L, tracking = false, surfaceAvailable = false)))
        assertEquals(PlacementPhase.TRACKING_LOST, c.phase)
        // While lost, a surface is meaningless and must not be taken.
        repeat(30) {
            assertEquals(FrameEffect.NONE, c.onFrame(FrameInput(200L + it, tracking = false, surfaceAvailable = true)))
        }
        // Back: the anchor re-tracks, no second placement, straight back to PLACED.
        val back = c.onFrame(FrameInput(1_000L, tracking = true, surfaceAvailable = true, anchorTracking = true))
        assertEquals(FrameEffect.NONE, back)
        assertEquals(PlacementPhase.PLACED, c.phase)
        assertEquals(1, c.placementsCreated)
        assertFalse(c.wantsSurface)
    }

    @Test
    fun `tracking loss emits its effect once and restores the prior phase`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)

        assertEquals(FrameEffect.TRACKING_LOST, c.onFrame(FrameInput(10L, tracking = false, surfaceAvailable = false)))
        assertEquals(FrameEffect.NONE, c.onFrame(FrameInput(20L, tracking = false, surfaceAvailable = false)))
        assertEquals(PlacementPhase.TRACKING_LOST, c.phase)

        c.onFrame(FrameInput(30L, tracking = true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
    }

    @Test
    fun `tracking loss does not reset a placement to scanning`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.onFrame(FrameInput(10L, tracking = false, surfaceAvailable = false))
        assertTrue(c.hasPlacement)
        c.onFrame(FrameInput(20L, tracking = true, surfaceAvailable = false, anchorTracking = true))
        assertEquals(PlacementPhase.PLACED, c.phase)
        assertTrue(c.hasPlacement)
    }

    @Test
    fun `the no-surface clock does not run while tracking is lost`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = false))
        c.onFrame(FrameInput(5_000L, tracking = false, surfaceAvailable = false))
        // 20 s later tracking returns: the search clock restarts, no card yet.
        c.onFrame(FrameInput(25_000L, tracking = true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
        c.onFrame(FrameInput(25_000L + NO_SURFACE_TIMEOUT_MS - 1, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
        c.onFrame(FrameInput(25_000L + NO_SURFACE_TIMEOUT_MS, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.NO_SURFACE, c.phase)
    }

    // ── Stale asset results ───────────────────────────────────────────────────────────

    @Test
    fun `a ticket issued before dismiss is refused afterwards`() {
        val c = AutoPlacementController()
        val ticket = c.selectModel()
        assertTrue(c.acceptsAsset(ticket))
        c.dismiss()
        assertFalse("a dismissed session accepts nothing", c.acceptsAsset(ticket))
        assertFalse(c.wantsSurface)
        assertEquals(FrameEffect.NONE, c.onFrame(FrameInput(0L, true, surfaceAvailable = true)))
        assertEquals(0, c.placementsCreated)
    }

    @Test
    fun `a new selection after dismiss opens a fresh session that places again`() {
        // Codex review of #3766, P1: the host keeps one state across chooser ↔ camera, so
        // Back (dismiss) followed by a re-entry must not leave a bricked controller.
        val c = AutoPlacementController()
        val before = c.selectModel()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.dismiss()
        assertEquals(PlacementPhase.INITIALIZING, c.phase)

        val after = c.selectModel()
        assertFalse("the pre-dismiss ticket stays stale", c.acceptsAsset(before))
        assertTrue("the fresh ticket is the live one", c.acceptsAsset(after))
        c.requestPlacement()
        assertTrue(c.wantsSurface)
        assertEquals(FrameEffect.PLACE, c.onFrame(FrameInput(16L, true, surfaceAvailable = true)))
        assertEquals(2, c.placementsCreated)
    }

    @Test
    fun `withdrawing the request stops the search until the next offer`() {
        // Codex review of #3766, P1: a streamed row picked while the previous asset is
        // still scanning must not let a surface place the previous asset.
        val c = AutoPlacementController()
        c.selectModel()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = false))
        c.selectModel()
        c.withdrawRequest()
        assertFalse(c.placementRequested)
        assertFalse(c.wantsSurface)
        repeat(20) {
            assertEquals(FrameEffect.NONE, c.onFrame(FrameInput(16L * it, true, surfaceAvailable = true)))
        }
        assertEquals(0, c.placementsCreated)
        assertEquals(PlacementPhase.SCANNING, c.phase)

        // The download lands: one placement, for the new asset's request.
        c.requestPlacement()
        assertEquals(FrameEffect.PLACE, c.onFrame(FrameInput(1_000L, true, surfaceAvailable = true)))
        assertEquals(1, c.placementsCreated)
    }

    @Test
    fun `withdrawing never touches a standing placement`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.withdrawRequest()
        assertTrue(c.hasPlacement)
        assertEquals(PlacementPhase.PLACED, c.phase)
    }

    @Test
    fun `a ticket is superseded by the next selection`() {
        val c = AutoPlacementController()
        val first = c.selectModel()
        val second = c.selectModel()
        assertFalse("the earlier download must not land", c.acceptsAsset(first))
        assertTrue(c.acceptsAsset(second))
    }

    @Test
    fun `a request after dismiss is ignored`() {
        val c = AutoPlacementController()
        c.dismiss()
        c.requestPlacement()
        assertFalse(c.placementRequested)
        assertFalse(c.wantsSurface)
        repeat(10) { c.onFrame(FrameInput(16L * it, true, surfaceAvailable = true)) }
        assertEquals(0, c.placementsCreated)
    }

    // ── Timeouts ──────────────────────────────────────────────────────────────────────

    @Test
    fun `no surface becomes a card at exactly 10 seconds`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(1_000L, true, surfaceAvailable = false))
        c.onFrame(FrameInput(1_000L + NO_SURFACE_TIMEOUT_MS - 1, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
        c.onFrame(FrameInput(1_000L + NO_SURFACE_TIMEOUT_MS, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.NO_SURFACE, c.phase)
        assertEquals(10_000L, NO_SURFACE_TIMEOUT_MS)
    }

    @Test
    fun `the no-surface card still places when a surface finally appears`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = false))
        c.onFrame(FrameInput(NO_SURFACE_TIMEOUT_MS, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.NO_SURFACE, c.phase)
        // Detection never stopped: the card is advice, not a gate.
        assertEquals(FrameEffect.PLACE, c.onFrame(FrameInput(NO_SURFACE_TIMEOUT_MS + 16, true, surfaceAvailable = true)))
        assertEquals(PlacementPhase.PLACED, c.phase)
    }

    @Test
    fun `keep scanning restarts the 10 second clock`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = false))
        c.onFrame(FrameInput(NO_SURFACE_TIMEOUT_MS, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.NO_SURFACE, c.phase)

        c.keepScanning(12_000L)
        assertEquals(PlacementPhase.SCANNING, c.phase)
        c.onFrame(FrameInput(12_000L + NO_SURFACE_TIMEOUT_MS - 1, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
        c.onFrame(FrameInput(12_000L + NO_SURFACE_TIMEOUT_MS, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.NO_SURFACE, c.phase)
    }

    @Test
    fun `keep scanning is a no-op outside the no-surface card`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.keepScanning(1L)
        assertEquals(PlacementPhase.PLACED, c.phase)
    }

    @Test
    fun `a paused anchor recovers or fails at exactly 10 seconds`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))

        c.onFrame(FrameInput(1_000L, true, surfaceAvailable = false, anchorTracking = false))
        assertEquals(PlacementPhase.RECOVERING, c.phase)
        c.onFrame(FrameInput(1_000L + RECOVERY_TIMEOUT_MS - 1, true, surfaceAvailable = false, anchorTracking = false))
        assertEquals(PlacementPhase.RECOVERING, c.phase)
        c.onFrame(FrameInput(1_000L + RECOVERY_TIMEOUT_MS, true, surfaceAvailable = false, anchorTracking = false))
        assertEquals(PlacementPhase.RECOVERY_FAILED, c.phase)
        // Still one anchor, and nothing is searching: the card asks, the user decides.
        assertEquals(1, c.placementsCreated)
        assertFalse(c.wantsSurface)
        assertEquals(10_000L, RECOVERY_TIMEOUT_MS)
    }

    @Test
    fun `an anchor that re-tracks in time goes back to placed`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.onFrame(FrameInput(1_000L, true, surfaceAvailable = false, anchorTracking = false))
        assertEquals(PlacementPhase.RECOVERING, c.phase)
        c.onFrame(FrameInput(4_000L, true, surfaceAvailable = false, anchorTracking = true))
        assertEquals(PlacementPhase.PLACED, c.phase)
        // A new pause starts a fresh 10 s, not the remainder of the first.
        c.onFrame(FrameInput(5_000L, true, surfaceAvailable = false, anchorTracking = false))
        c.onFrame(FrameInput(5_000L + RECOVERY_TIMEOUT_MS - 1, true, surfaceAvailable = false, anchorTracking = false))
        assertEquals(PlacementPhase.RECOVERING, c.phase)
    }

    @Test
    fun `scan again after a failed recovery places once more`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.onFrame(FrameInput(1_000L, true, surfaceAvailable = false, anchorTracking = false))
        c.onFrame(FrameInput(1_000L + RECOVERY_TIMEOUT_MS, true, surfaceAvailable = false, anchorTracking = false))
        assertEquals(PlacementPhase.RECOVERY_FAILED, c.phase)

        c.resetPlacement(20_000L)
        assertEquals(PlacementPhase.SCANNING, c.phase)
        assertTrue(c.wantsSurface)
        assertEquals(FrameEffect.PLACE, c.onFrame(FrameInput(20_016L, true, surfaceAvailable = true)))
        assertEquals(2, c.placementsCreated)
    }

    // ── Transitions ───────────────────────────────────────────────────────────────────

    @Test
    fun `the first frame moves initializing to scanning`() {
        val c = AutoPlacementController()
        assertEquals(PlacementPhase.INITIALIZING, c.phase)
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
    }

    @Test
    fun `reset placement from placed drops the anchor and scans again`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(100L, true, surfaceAvailable = true))
        assertEquals(100L, c.placedAtMillis)

        c.resetPlacement(500L)
        assertFalse(c.hasPlacement)
        assertTrue(c.placementRequested)
        assertTrue(c.wantsSurface)
        assertEquals(PlacementPhase.SCANNING, c.phase)
        assertEquals(0L, c.placedAtMillis)

        assertEquals(FrameEffect.PLACE, c.onFrame(FrameInput(600L, true, surfaceAvailable = true)))
        assertEquals(2, c.placementsCreated)
        assertEquals(600L, c.placedAtMillis)
    }

    @Test
    fun `reset placement during tracking loss waits for tracking`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, true, surfaceAvailable = true))
        c.onFrame(FrameInput(10L, tracking = false, surfaceAvailable = false))
        c.resetPlacement(20L)
        assertEquals(PlacementPhase.TRACKING_LOST, c.phase)
        c.onFrame(FrameInput(30L, true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
    }

    @Test
    fun `camera failure is only reachable from initializing`() {
        val stuck = AutoPlacementController()
        stuck.cameraFailed()
        assertEquals(PlacementPhase.CAMERA_ERROR, stuck.phase)
        // Frames after the verdict change nothing — the host recreates the session.
        stuck.requestPlacement()
        assertEquals(FrameEffect.NONE, stuck.onFrame(FrameInput(0L, true, surfaceAvailable = true)))
        assertEquals(PlacementPhase.CAMERA_ERROR, stuck.phase)
        assertEquals(0, stuck.placementsCreated)

        val running = AutoPlacementController()
        running.requestPlacement()
        running.onFrame(FrameInput(0L, true, surfaceAvailable = false))
        running.cameraFailed()
        assertEquals(PlacementPhase.SCANNING, running.phase)
    }

    @Test
    fun `tracking loss before the first frame returns to scanning`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.onFrame(FrameInput(0L, tracking = false, surfaceAvailable = false))
        assertEquals(PlacementPhase.TRACKING_LOST, c.phase)
        c.onFrame(FrameInput(16L, tracking = true, surfaceAvailable = false))
        assertEquals(PlacementPhase.SCANNING, c.phase)
    }

    @Test
    fun `dismiss stops every frame effect`() {
        val c = AutoPlacementController()
        c.requestPlacement()
        c.dismiss()
        assertEquals(FrameEffect.NONE, c.onFrame(FrameInput(0L, tracking = false, surfaceAvailable = false)))
        assertEquals(FrameEffect.NONE, c.onFrame(FrameInput(16L, tracking = true, surfaceAvailable = true)))
        assertEquals(PlacementPhase.INITIALIZING, c.phase)
    }

    @Test
    fun `the ticket carries both generations`() {
        val c = AutoPlacementController()
        val t0 = c.ticket
        val t1 = c.selectModel()
        assertEquals(AssetTicket(0, 0), t0)
        assertEquals(AssetTicket(0, 1), t1)
        c.dismiss()
        assertEquals(AssetTicket(1, 1), c.ticket)
    }
}
