package io.github.sceneview.demo.demos.internal

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.PlacementPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wall Placement coaching copy (#4070). */
class WallCoachingTest {

    @Test fun `a plain wall is named at once, before any tracked frame`() {
        // The #4070 case: ARCore never starts tracking on a blank wall, so the flow stays in
        // INITIALIZING and the No-surface card, which counts tracked time only, never shows.
        assertEquals(
            WallCoachingHint.PLAIN_WALL,
            wallCoachingHint(
                PlacementPhase.INITIALIZING,
                cameraLive = true,
                trackingFailure = TrackingFailureReason.INSUFFICIENT_FEATURES,
                lingered = false,
            ),
        )
    }

    @Test fun `darkness and fast motion are named too`() {
        assertEquals(
            WallCoachingHint.TOO_DARK,
            wallCoachingHint(PlacementPhase.INITIALIZING, true, TrackingFailureReason.INSUFFICIENT_LIGHT, false),
        )
        assertEquals(
            WallCoachingHint.SLOW_DOWN,
            wallCoachingHint(PlacementPhase.INITIALIZING, true, TrackingFailureReason.EXCESSIVE_MOTION, false),
        )
    }

    @Test fun `a search with no named cause asks to move sideways only after lingering`() {
        for (phase in listOf(PlacementPhase.INITIALIZING, PlacementPhase.SCANNING)) {
            assertEquals(
                WallCoachingHint.NONE,
                wallCoachingHint(phase, true, TrackingFailureReason.NONE, lingered = false),
            )
            assertEquals(
                WallCoachingHint.MOVE_SIDEWAYS,
                wallCoachingHint(phase, true, TrackingFailureReason.NONE, lingered = true),
            )
            assertEquals(
                WallCoachingHint.MOVE_SIDEWAYS,
                wallCoachingHint(phase, true, trackingFailure = null, lingered = true),
            )
        }
    }

    @Test fun `nothing is said before the camera is live`() {
        assertEquals(
            WallCoachingHint.NONE,
            wallCoachingHint(
                PlacementPhase.INITIALIZING,
                cameraLive = false,
                trackingFailure = TrackingFailureReason.INSUFFICIENT_FEATURES,
                lingered = true,
            ),
        )
    }

    @Test fun `phases with their own copy get no coaching hint`() {
        val others = PlacementPhase.values().toList() - PlacementPhase.INITIALIZING - PlacementPhase.SCANNING
        for (phase in others) {
            assertEquals(
                phase.name,
                WallCoachingHint.NONE,
                wallCoachingHint(phase, true, TrackingFailureReason.INSUFFICIENT_FEATURES, true),
            )
        }
    }

    @Test fun `the linger clock runs only while searching with a live camera`() {
        assertTrue(isSearchingForWall(PlacementPhase.INITIALIZING, cameraLive = true))
        assertTrue(isSearchingForWall(PlacementPhase.SCANNING, cameraLive = true))
        assertFalse(isSearchingForWall(PlacementPhase.INITIALIZING, cameraLive = false))
        assertFalse(isSearchingForWall(PlacementPhase.PLACED, cameraLive = true))
        assertFalse(isSearchingForWall(PlacementPhase.NO_SURFACE, cameraLive = true))
    }
}
