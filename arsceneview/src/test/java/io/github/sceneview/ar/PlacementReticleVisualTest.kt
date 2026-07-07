package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the reticle searching↔ready mapping behind [PlacementReticleVisual].
 *
 * The visual itself needs Compose + Filament, but the two decisions that drive the
 * consumer-AR "dim while searching, bright + centre-dot when ready" behaviour are pure
 * functions ([reticlePhaseFor] / [reticleAlphaFor]) so they can be pinned without a device.
 */
class PlacementReticleVisualTest {

    @Test
    fun `a hit maps to READY`() {
        assertEquals(ReticlePhase.READY, reticlePhaseFor(hasHit = true))
    }

    @Test
    fun `no hit maps to SEARCHING`() {
        assertEquals(ReticlePhase.SEARCHING, reticlePhaseFor(hasHit = false))
    }

    @Test
    fun `READY is brighter than SEARCHING`() {
        assertTrue(
            "the ready ring must be more opaque than the searching ring",
            reticleAlphaFor(ReticlePhase.READY) > reticleAlphaFor(ReticlePhase.SEARCHING)
        )
    }

    @Test
    fun `alpha values stay within the unit range`() {
        for (phase in ReticlePhase.values()) {
            val alpha = reticleAlphaFor(phase)
            assertTrue("$phase alpha $alpha must be in 0..1", alpha in 0f..1f)
        }
    }

    @Test
    fun `RING is the default reticle style`() {
        // The modern consumer-AR default (Scene Viewer / IKEA / Houzz), not the legacy disc.
        assertEquals(PlacementReticleStyle.RING, PlacementReticleStyle.values().first())
    }
}
