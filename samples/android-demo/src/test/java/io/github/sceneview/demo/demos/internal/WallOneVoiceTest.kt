package io.github.sceneview.demo.demos.internal

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.ArGuidanceState
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.PlacementSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wall Placement shows one instruction at a time (#4190): while the coaching glyph speaks,
 * the bottom pill steps aside, and it comes back only once the glyph is gone. The #4070
 * search hints ride on the glyph as its caption instead of a second sentence in the pill.
 */
class WallOneVoiceTest {

    private val flags = listOf(false, true)

    private fun cueFor(phase: PlacementPhase, placedAt: Long = 0L, now: Long = 10_000L): ArGuidanceCue {
        val guidance = ArGuidanceState(PlacementSurface.WALL)
        // Start in INITIALIZING long enough ago that its own grace delay has passed.
        guidance.update(PlacementPhase.INITIALIZING, 0L, 0L)
        guidance.update(phase, placedAt, now)
        return guidance.cue
    }

    @Test fun `the pill is silent in every phase where the glyph speaks`() {
        for (phase in PlacementPhase.entries) {
            val cue = cueFor(phase)
            if (cue == ArGuidanceCue.NONE) continue
            for (invalidMove in flags) for (hint in flags) for (lowLight in flags) {
                assertNull(
                    "$phase / $cue with invalidMove=$invalidMove hint=$hint lowLight=$lowLight",
                    wallStatus(phase, cardShown = false, coaching = true, invalidMove, hint, lowLight),
                )
            }
        }
    }

    @Test fun `five seconds into a search the glyph carries the hint and the pill says nothing`() {
        // The device report on 900cace0: at t = 5 s the glyph said "Point your phone at a wall"
        // while the pill said "Stand about a metre from the wall". Now there is one sentence.
        val cue = cueFor(PlacementPhase.SCANNING)
        assertEquals(ArGuidanceCue.SCAN, cue)
        assertEquals(
            WallCoachingHint.MOVE_SIDEWAYS,
            wallCoachingHint(PlacementPhase.SCANNING, cameraLive = true, trackingFailure = null, lingered = true),
        )
        assertNull(wallStatus(PlacementPhase.SCANNING, false, coaching = true, false, false, false))
    }

    @Test fun `a plain wall is still named, by the glyph`() {
        val cue = cueFor(PlacementPhase.INITIALIZING)
        assertEquals(ArGuidanceCue.INITIALIZING, cue)
        assertEquals(
            WallCoachingHint.PLAIN_WALL,
            wallCoachingHint(
                PlacementPhase.INITIALIZING,
                cameraLive = true,
                trackingFailure = TrackingFailureReason.INSUFFICIENT_FEATURES,
                lingered = false,
            ),
        )
        assertNull(wallStatus(PlacementPhase.INITIALIZING, false, coaching = true, false, false, false))
    }

    @Test fun `the pill comes back once the glyph is gone`() {
        val guidance = ArGuidanceState(PlacementSurface.WALL)
        guidance.update(PlacementPhase.SCANNING, 0L, 0L)
        guidance.update(PlacementPhase.PLACED, 1_000L, 1_000L)
        assertNotEquals(ArGuidanceCue.NONE, guidance.cue)
        assertNull(wallStatus(PlacementPhase.PLACED, false, guidance.isCoaching, false, true, false))
        guidance.update(PlacementPhase.PLACED, 1_000L, 1_000L + ArGuidanceState.FOUND_HOLD_MS)
        assertEquals(ArGuidanceCue.NONE, guidance.cue)
        assertEquals(
            WallStatus.GESTURE_HINT,
            wallStatus(PlacementPhase.PLACED, false, guidance.isCoaching, false, true, false),
        )
    }

    @Test fun `low light waits for the glyph, whose sentence already asks for a brighter spot`() {
        assertNull(wallStatus(PlacementPhase.TRACKING_LOST, false, coaching = true, false, false, lowLight = true))
        assertEquals(
            WallStatus.TRACKING_PAUSED_LOW_LIGHT,
            wallStatus(PlacementPhase.TRACKING_LOST, false, coaching = false, false, false, lowLight = true),
        )
    }

    @Test fun `a card never shares the screen with the pill`() {
        for (phase in PlacementPhase.entries) {
            assertNull(wallStatus(phase, cardShown = true, coaching = false, true, true, true))
        }
    }
}
