package io.github.sceneview.ar

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the reticle's searching / hit / locked mapping behind
 * [PlacementReticleVisual].
 *
 * The visual itself needs Compose + Filament, but every decision that drives the consumer-AR
 * "dim while searching, brighter on a surface, accented centre dot once locked" behaviour is a
 * pure function ([reticlePhaseFor] / [reticleAlphaFor] / [reticleDotRadiusFor] /
 * [reticleDotColorFor]) so it can be pinned without a device.
 */
class PlacementReticleVisualTest {

    @Test
    fun `a hit on an estimated surface maps to READY`() {
        assertEquals(ReticlePhase.READY, reticlePhaseFor(hasHit = true, lockedOnPlane = false))
    }

    @Test
    fun `a hit on a tracked plane maps to LOCKED`() {
        assertEquals(ReticlePhase.LOCKED, reticlePhaseFor(hasHit = true, lockedOnPlane = true))
    }

    @Test
    fun `no hit maps to SEARCHING whatever the plane flag says`() {
        assertEquals(ReticlePhase.SEARCHING, reticlePhaseFor(hasHit = false))
        assertEquals(
            ReticlePhase.SEARCHING,
            reticlePhaseFor(hasHit = false, lockedOnPlane = true)
        )
    }

    @Test
    fun `the three states form a brightness ramp`() {
        // searching < hit < locked: the escalation is readable without any text or hue.
        assertTrue(
            "a hit ring must be more opaque than a searching one",
            reticleAlphaFor(ReticlePhase.READY) > reticleAlphaFor(ReticlePhase.SEARCHING)
        )
        assertTrue(
            "a locked ring must be more opaque than a hit one",
            reticleAlphaFor(ReticlePhase.LOCKED) > reticleAlphaFor(ReticlePhase.READY)
        )
    }

    @Test
    fun `the locked ring stops short of fully opaque`() {
        // 90 %, not 100 %: an opaque white ring over a camera frame reads as a sticker on the
        // lens, and the remaining alpha is what lets the contact halo show through (#3570).
        assertEquals(0.9f, reticleAlphaFor(ReticlePhase.LOCKED), 0.0001f)
        assertTrue(reticleAlphaFor(ReticlePhase.LOCKED) < 1f)
    }

    @Test
    fun `only the locked state is allowed a hue`() {
        // The whole point of #3570: the cursor is achromatic until ARCore actually has a plane.
        for (phase in listOf(ReticlePhase.SEARCHING, ReticlePhase.READY)) {
            val dot = reticleDotColorFor(phase)
            assertEquals("$phase dot must be achromatic", dot.red, dot.green, 0.0f)
            assertEquals("$phase dot must be achromatic", dot.green, dot.blue, 0.0f)
        }
        assertEquals(RETICLE_READY_ACCENT, reticleDotColorFor(ReticlePhase.LOCKED))
    }

    @Test
    fun `a custom tint colours the ring and the unlocked dot, never the locked accent`() {
        val custom = Color(0xFF_12_34_56)
        assertEquals(custom, reticleDotColorFor(ReticlePhase.READY, custom))
        assertEquals(RETICLE_READY_ACCENT, reticleDotColorFor(ReticlePhase.LOCKED, custom))
    }

    @Test
    fun `the centre dot grows with the state and never exists while searching`() {
        assertEquals(0f, reticleDotRadiusFor(ReticlePhase.SEARCHING), 0.0f)
        assertTrue(
            "a hit must show a dot",
            reticleDotRadiusFor(ReticlePhase.READY) > 0f
        )
        assertTrue(
            "the locked dot must be the bigger of the two",
            reticleDotRadiusFor(ReticlePhase.LOCKED) > reticleDotRadiusFor(ReticlePhase.READY)
        )
    }

    @Test
    fun `the centre dot stays small against the ring`() {
        // "Small centre dot", not a filled target: the dot must stay well inside the ring it
        // sits in, or the cursor reads as the loud blob #3570 was filed about.
        assertTrue(
            "the locked dot must stay under a quarter of the ring radius",
            reticleDotRadiusFor(ReticlePhase.LOCKED) < RING_MAJOR_RADIUS / 4f
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
    fun `the locked accent is the DESIGN-md primary dark value`() {
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
                reticleHaloAlphaFor(reticleAlphaFor(ReticlePhase.LOCKED))
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
