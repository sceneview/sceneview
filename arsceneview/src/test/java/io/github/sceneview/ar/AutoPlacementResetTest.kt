package io.github.sceneview.ar

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlacementResetTest {
    @Test
    fun `reset notifies custom adapters even while tracking is lost`() {
        val state = AutoPlacementState()
        state.requestPlacement()
        state.onFrame(FrameInput(0L, true, true))
        state.onFrame(FrameInput(16L, false, false))
        assertEquals(PlacementPhase.TRACKING_LOST, state.phase)
        Snapshot.sendApplyNotifications()

        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Unit, { invalidations++ }) { assertTrue(state.hasPlacement) }
            state.resetPlacement(100L)
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            assertEquals(PlacementPhase.TRACKING_LOST, state.phase)
            assertFalse(state.hasPlacement)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun `reset releases scene ownership without waiting for another camera frame`() {
        val state = AutoPlacementState()
        var ownedPlacement: Any? = null
        var releases = 0
        state.releasePlacement = {
            if (ownedPlacement != null) releases++
            ownedPlacement = null
        }
        state.requestPlacement()

        repeat(5) { cycle ->
            assertEquals(FrameEffect.PLACE, state.onFrame(FrameInput(cycle * 100L, true, true)) {
                ownedPlacement = Any()
                true
            })
            assertTrue(state.hasPlacement)

            state.resetPlacement(cycle * 100L + 1L)

            assertNull(ownedPlacement)
            assertEquals(cycle + 1, releases)
            assertFalse(state.hasPlacement)
            assertTrue(state.wantsSurface)
            assertEquals(PlacementPhase.SCANNING, state.phase)
        }
    }

    @Test
    fun `dismiss releases ownership and a later reset cannot revive the scene`() {
        val state = AutoPlacementState()
        var ownedPlacement: Any? = Any()
        var releases = 0
        state.releasePlacement = {
            if (ownedPlacement != null) releases++
            ownedPlacement = null
        }
        state.requestPlacement()
        state.onFrame(FrameInput(0L, true, true))
        state.dismiss()
        state.resetPlacement(100L)
        state.dismiss()

        assertNull(ownedPlacement)
        assertEquals(1, releases)
        assertFalse(state.hasPlacement)
        assertFalse(state.wantsSurface)
    }
}
