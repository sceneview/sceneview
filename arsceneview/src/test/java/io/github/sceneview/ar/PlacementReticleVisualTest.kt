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
    fun `the default tint is achromatic`() {
        // #3570: the reticle used to default to 0xFF44E7FF and call it "the DESIGN.md primary
        // cyan". DESIGN.md has no cyan. Every reference reticle (RealityKit FocusEntity, Scene
        // Viewer, IKEA Place) is neutral so it never competes with the model being placed —
        // pin that: red == green == blue.
        assertEquals(RETICLE_TINT.red, RETICLE_TINT.green, 0.0f)
        assertEquals(RETICLE_TINT.green, RETICLE_TINT.blue, 0.0f)
        assertEquals(1.0f, RETICLE_TINT.alpha, 0.0f)
    }

    @Test
    fun `the ready accent is the DESIGN-md primary dark value`() {
        // Accents over a camera feed are the dark-scheme values in both themes — #a4c1ff.
        assertEquals(0xA4 / 255f, RETICLE_READY_ACCENT.red, 0.004f)
        assertEquals(0xC1 / 255f, RETICLE_READY_ACCENT.green, 0.004f)
        assertEquals(0xFF / 255f, RETICLE_READY_ACCENT.blue, 0.004f)
    }

    @Test
    fun `the contact halo is always fainter than the ring it grounds`() {
        for (phase in ReticlePhase.values()) {
            val ring = reticleAlphaFor(phase)
            val halo = reticleHaloAlphaFor(ring)
            assertTrue("$phase halo $halo must be fainter than ring $ring", halo < ring)
            assertTrue("$phase halo $halo must be in 0..1", halo in 0f..1f)
        }
    }

    @Test
    fun `the halo fades with the ring`() {
        assertTrue(
            "a dimmer ring must carry a dimmer halo",
            reticleHaloAlphaFor(reticleAlphaFor(ReticlePhase.SEARCHING)) <
                reticleHaloAlphaFor(reticleAlphaFor(ReticlePhase.READY))
        )
    }

    @Test
    fun `the contact halo traces the ring instead of filling it`() {
        // Regression guard for the shape, not just the alpha: the halo used to be a filled disc,
        // which on a pale floor renders as a grey blob wider than the cursor (measured on the
        // demo's pale-ground swatch, #3570). It must stay a tube — fatter than the ring's, so it
        // peeks out as a shadow, but nowhere near a disc.
        assertTrue(
            "the halo tube must be fatter than the ring's to read as a shadow",
            RETICLE_HALO_MINOR_RADIUS > RING_MINOR_RADIUS
        )
        assertTrue(
            "the halo must stay a hairline, not creep towards a filled disc",
            RETICLE_HALO_MINOR_RADIUS < RING_MAJOR_RADIUS / 4f
        )
    }

    @Test
    fun `RING is the default reticle style`() {
        // The modern consumer-AR default (Scene Viewer / IKEA / Houzz), not the legacy disc.
        assertEquals(PlacementReticleStyle.RING, PlacementReticleStyle.values().first())
    }
}
