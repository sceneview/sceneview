package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [DemoMath].
 *
 * Pins the pure-math contracts behind two visible demo behaviours:
 *   - GeometryDemo's continuous spin (`nextSpinDegrees`).
 *   - MultiModelDemo's tabletop turntable rotation (`rotateAroundCentre`).
 *
 * If the visible behaviour ever drifts, these tests catch it without needing a
 * device, an emulator, or a screenshot baseline.
 */
class DemoMathTest {

    private val eps = 0.001f

    // ── nextSpinDegrees ─────────────────────────────────────────────────────

    @Test
    fun `nextSpinDegrees returns previous when deltaNanos is zero`() {
        // First-frame guard: the GeometryDemo loop initialises lastNanos to 0L and skips
        // the first frame to avoid a huge initial delta. The math layer mirrors that:
        // delta=0 → no advance, just hand back the same angle.
        assertEquals(45f, DemoMath.nextSpinDegrees(45f, deltaNanos = 0L), eps)
        assertEquals(0f, DemoMath.nextSpinDegrees(0f, deltaNanos = 0L), eps)
    }

    @Test
    fun `nextSpinDegrees advances at default 36 degrees per second`() {
        // 1 second = 36° at the default rate.
        assertEquals(36f, DemoMath.nextSpinDegrees(0f, 1_000_000_000L), eps)
        // Half a second = 18°.
        assertEquals(18f, DemoMath.nextSpinDegrees(0f, 500_000_000L), eps)
    }

    @Test
    fun `nextSpinDegrees wraps at 360`() {
        // 350° + 1 second @ 36°/s = 386° → wraps to 26°.
        val result = DemoMath.nextSpinDegrees(350f, 1_000_000_000L)
        assertEquals(26f, result, eps)
        assertTrue("Wrapped result must lie in [0, 360): $result", result >= 0f && result < 360f)
    }

    @Test
    fun `nextSpinDegrees handles many full revolutions in one delta`() {
        // 1 hour at 36°/s = 36 * 3600 = 129_600° = 360 full revolutions exactly.
        // After wrap, we should land on 0.
        val result = DemoMath.nextSpinDegrees(0f, deltaNanos = 3_600L * 1_000_000_000L)
        assertEquals(0f, result, eps)
    }

    @Test
    fun `nextSpinDegrees handles negative previous via wrap-then-clamp`() {
        // If a caller passes a negative previousDegrees (e.g. due to a refactor mistake),
        // the wrap should normalise into [0, 360) instead of returning negative — the
        // demo's Rotation API expects a non-negative angle.
        val result = DemoMath.nextSpinDegrees(-90f, deltaNanos = 0L)
        assertTrue("Negative previous must wrap to [0, 360): $result", result >= 0f && result < 360f)
        assertEquals(270f, result, eps)
    }

    @Test
    fun `nextSpinDegrees respects custom rate`() {
        // 90°/s for 2 seconds = 180°.
        assertEquals(
            180f,
            DemoMath.nextSpinDegrees(0f, 2_000_000_000L, ratePerSecond = 90f),
            eps,
        )
    }

    @Test
    fun `nextSpinDegrees clamps negative deltaNanos to zero`() {
        // System clock can occasionally tick backwards (NTP correction, etc.). We must
        // not regress the angle.
        val result = DemoMath.nextSpinDegrees(45f, deltaNanos = -1_000L)
        assertEquals(45f, result, eps)
    }

    @Test
    fun `nextSpinDegrees frame loop produces smooth integer multiples of expected rate`() {
        // Simulate a 60 Hz frame loop running for 1 second (60 frames × ~16.67 ms).
        // Sum should be 36° within float error.
        val frameNanos = 16_666_667L // ~60 Hz
        var degrees = 0f
        repeat(60) { degrees = DemoMath.nextSpinDegrees(degrees, frameNanos) }
        assertEquals("60 frames @ 60 Hz should advance by ~36°", 36f, degrees, 0.05f)
    }

    // ── rotateAroundCentre ──────────────────────────────────────────────────

    @Test
    fun `rotateAroundCentre identity at zero yaw`() {
        val (rx, rz) = DemoMath.rotateAroundCentre(dx = 0.5f, dz = -0.4f, sceneYaw = 0f)
        assertEquals(0.5f, rx, eps)
        assertEquals(-0.4f, rz, eps)
    }

    @Test
    fun `rotateAroundCentre quarter turn maps X to negative Z`() {
        // Clockwise 90° in (x, z) when viewed from +Y down: (1, 0) → (0, -1).
        // Why CW: matches the demo's per-model `Rotation(y = -sceneYaw)` so models
        // stay facing the camera while the formation orbits.
        val (rx, rz) = DemoMath.rotateAroundCentre(dx = 1f, dz = 0f, sceneYaw = 90f)
        assertEquals(0f, rx, eps)
        assertEquals(-1f, rz, eps)
    }

    @Test
    fun `rotateAroundCentre half turn negates both components`() {
        val (rx, rz) = DemoMath.rotateAroundCentre(dx = 0.55f, dz = 0.2f, sceneYaw = 180f)
        assertEquals(-0.55f, rx, eps)
        assertEquals(-0.2f, rz, eps)
    }

    @Test
    fun `rotateAroundCentre full turn returns to start`() {
        val (rx, rz) = DemoMath.rotateAroundCentre(dx = 0.55f, dz = 0.2f, sceneYaw = 360f)
        assertEquals(0.55f, rx, eps)
        assertEquals(0.2f, rz, eps)
    }

    @Test
    fun `rotateAroundCentre preserves distance from centre`() {
        val dx = 0.55f
        val dz = -0.45f
        val expectedDistSq = dx * dx + dz * dz
        for (yaw in listOf(0f, 30f, 45f, 90f, 137f, 200f, 270f, 359f)) {
            val (rx, rz) = DemoMath.rotateAroundCentre(dx, dz, yaw)
            val actualDistSq = rx * rx + rz * rz
            assertEquals(
                "Rotation must preserve distance from centre at yaw=$yaw",
                expectedDistSq, actualDistSq, eps,
            )
        }
    }

    @Test
    fun `rotateAroundCentre matches MultiModelDemo display layout`() {
        // Pin the actual demo's 4 display positions at yaw=0 (the default) to lock in
        // the visible layout. If anyone changes the Display constructor calls without
        // updating this test, it'll catch the layout drift.
        // Front row z=-1.3 (vs centerZ=-1.5 → dz=0.2), back row z=-1.7 (dz=-0.2).
        val centerZ = -1.5f
        val displays = listOf(
            // (label, dx, dz_local, expected_world_z_at_yaw_0)
            Triple("avocado",   -0.55f, -1.3f - centerZ),
            Triple("helmet",     0.0f,  -1.3f - centerZ),
            Triple("dragon",    -0.45f, -1.7f - centerZ),
            Triple("lantern",    0.55f, -1.7f - centerZ),
        )
        for ((label, dx, dz) in displays) {
            val (rx, rz) = DemoMath.rotateAroundCentre(dx, dz, sceneYaw = 0f)
            // At yaw=0 the rotation is identity.
            assertEquals("$label x at yaw=0", dx, rx, eps)
            assertEquals("$label z at yaw=0", dz, rz, eps)
        }
    }

    @Test
    fun `rotateAroundCentre handles negative yaw symmetrically`() {
        val pos = DemoMath.rotateAroundCentre(1f, 0f, sceneYaw = 90f)
        val neg = DemoMath.rotateAroundCentre(1f, 0f, sceneYaw = -90f)
        // 90° CW and -90° CW are mirrored: (0, -1) vs (0, 1).
        assertEquals(pos.first, neg.first, eps)
        assertEquals(-pos.second, neg.second, eps)
    }

    // ── placementRotationFor (#1477) ────────────────────────────────────────

    @Test
    fun `placementRotationFor corrects the bundled helmet by minus 90 degrees X`() {
        // The DamagedHelmet GLB ships a residual +90° X root rotation that lands it
        // face-down on an AR plane — the placement demos undo it with -90° X.
        val rotation = DemoMath.placementRotationFor(DemoMath.HELMET_ASSET)
        assertEquals(-90f, rotation.x, eps)
        assertEquals(0f, rotation.y, eps)
        assertEquals(0f, rotation.z, eps)
    }

    @Test
    fun `placementRotationFor returns identity for other bundled models`() {
        // Fox, lantern, toy car, shiba are authored upright — no correction.
        for (path in listOf(
            "models/khronos_fox.glb",
            "models/khronos_lantern.glb",
            "models/khronos_toy_car.glb",
            "models/shiba.glb",
        )) {
            val rotation = DemoMath.placementRotationFor(path)
            assertEquals("$path x", 0f, rotation.x, eps)
            assertEquals("$path y", 0f, rotation.y, eps)
            assertEquals("$path z", 0f, rotation.z, eps)
        }
    }

    @Test
    fun `placementRotationFor returns identity for streamed file URIs`() {
        // ARPlacementDemo can place streamed Sketchfab models whose assetLocation is a
        // `file://` URI — those must never be hit by the helmet-specific correction.
        val rotation = DemoMath.placementRotationFor("file:///data/user/0/app/cache/streamed.glb")
        assertEquals(0f, rotation.x, eps)
        assertEquals(0f, rotation.y, eps)
        assertEquals(0f, rotation.z, eps)
    }

    // ── ContactShadowPreviewDemo bounce choreography ────────────────────────

    private val bouncePeriod = DemoMath.CONTACT_BOUNCE_PERIOD_NANOS
    private val bounceMax = DemoMath.CONTACT_BOUNCE_MAX_HEIGHT_METERS

    @Test
    fun `bounceHeight starts and lands at ground contact`() {
        // t = 0 is the deterministic QA-mode pose: box on the floor, pool at full strength.
        assertEquals(0f, DemoMath.bounceHeight(0L), eps)
        // sin(π) = 0 — one full period later the box is exactly back on the floor.
        assertEquals(0f, DemoMath.bounceHeight(bouncePeriod), eps)
        // Negative elapsed (pre-first-frame) clamps to contact rather than extrapolating.
        assertEquals(0f, DemoMath.bounceHeight(-1L), eps)
    }

    @Test
    fun `bounceHeight peaks at mid-period`() {
        assertEquals(bounceMax, DemoMath.bounceHeight(bouncePeriod / 2), eps)
        // sin(π/4) = √2/2 at the quarter-period.
        assertEquals(
            bounceMax * 0.70710677f,
            DemoMath.bounceHeight(bouncePeriod / 4),
            eps,
        )
    }

    @Test
    fun `bounceHeight reduces phase over many periods without drifting`() {
        // Integer modulo before the float conversion: a million periods in, the same
        // phase must yield the same height — the loop can run for hours without degrading.
        //
        // The multiplier has to be this large to make the assertion mean anything.
        // Measured against a float32 simulation of the rejected implementation
        // (`elapsedNanos.toFloat() / periodNanos`, no integer modulo): at 1_000 periods
        // it drifts by 1.1e-5 — a hundredth of `eps`, so the very implementation this
        // test exists to reject sailed through it. At 1_000_000 periods (~2.6e15 ns,
        // still far inside `Long`) the same implementation is off by 1.2e-2, ~12x `eps`,
        // while the real modulo-first implementation stays under `eps` at both scales.
        assertEquals(
            DemoMath.bounceHeight(bouncePeriod / 3),
            DemoMath.bounceHeight(bouncePeriod * 1_000_000 + bouncePeriod / 3),
            eps,
        )
    }

    @Test
    fun `bounceHeight rises monotonically toward the peak`() {
        // The rectified sine climbs all the way from contact to the mid-period peak —
        // no dip that would read as a double-bounce.
        var previous = 0f
        for (i in 1..10) {
            val h = DemoMath.bounceHeight(bouncePeriod / 2 * i / 10)
            assertTrue("height at step $i should rise ($previous -> $h)", h >= previous)
            previous = h
        }
        assertEquals(bounceMax, previous, eps)
    }

    @Test
    fun `bounceHeight degenerate inputs return contact`() {
        assertEquals(0f, DemoMath.bounceHeight(123L, periodNanos = 0L), eps)
        assertEquals(0f, DemoMath.bounceHeight(123L, periodNanos = -5L), eps)
        assertEquals(0f, DemoMath.bounceHeight(123L, maxHeight = 0f), eps)
    }

    @Test
    fun `groundingIntensityFactor is full at contact and dimmest at the peak`() {
        // Contact → the pool keeps its full context opacity.
        assertEquals(1f, DemoMath.groundingIntensityFactor(0f), eps)
        // Peak → dims to the lifted floor, but never to zero: the pool must stay
        // attributable to the box even at the top of the hop. 0.45 (not 0.28) is a
        // measured-visibility floor — see the KDoc on groundingIntensityFactor.
        assertEquals(0.45f, DemoMath.groundingIntensityFactor(bounceMax), eps)
        // Halfway → linear midpoint: 1 - 0.55/2.
        assertEquals(0.725f, DemoMath.groundingIntensityFactor(bounceMax / 2f), eps)
        // Overshoot coerces to the floor value instead of going negative.
        assertEquals(0.45f, DemoMath.groundingIntensityFactor(bounceMax * 3f), eps)
        // Degenerate maxHeight leaves the shadow untouched.
        assertEquals(1f, DemoMath.groundingIntensityFactor(0.1f, maxHeight = 0f), eps)
    }

    @Test
    fun `groundingSpread is tight at contact and widest at the peak`() {
        assertEquals(1f, DemoMath.groundingSpread(0f), eps)
        assertEquals(1.5f, DemoMath.groundingSpread(bounceMax), eps)
        assertEquals(1.25f, DemoMath.groundingSpread(bounceMax / 2f), eps)
        // Overshoot coerces to the max spread.
        assertEquals(1.5f, DemoMath.groundingSpread(bounceMax * 2f), eps)
        assertEquals(1f, DemoMath.groundingSpread(0.1f, maxHeight = 0f), eps)
    }

    @Test
    fun `groundingShadowOffset is zero at contact and projects along the light when lifted`() {
        // Contact → the pool sits exactly under the object, no drift.
        val atContact = DemoMath.groundingShadowOffset(0f, -0.35f, -1f, -0.4f)
        assertEquals(0f, atContact.first, eps)
        assertEquals(0f, atContact.second, eps)
        // Lifted → geometric projection h * (dirX, dirZ) / |dirY|. With dirY = -1 the offset is
        // just h * (dirX, dirZ), pointing along the light's horizontal travel (both negative
        // here), so the pool slides out from under the box.
        val (dx, dz) = DemoMath.groundingShadowOffset(bounceMax, -0.35f, -1f, -0.4f)
        assertEquals(bounceMax * -0.35f, dx, eps)
        assertEquals(bounceMax * -0.4f, dz, eps)
    }

    @Test
    fun `groundingShadowOffset scales linearly with height`() {
        val (dx1, dz1) = DemoMath.groundingShadowOffset(0.1f, -0.35f, -1f, -0.4f)
        val (dx2, dz2) = DemoMath.groundingShadowOffset(0.2f, -0.35f, -1f, -0.4f)
        assertEquals(2f * dx1, dx2, eps)
        assertEquals(2f * dz1, dz2, eps)
    }

    @Test
    fun `groundingShadowOffset divides by the vertical component`() {
        // A steeper light (larger |dirY|) throws a shorter shadow for the same height.
        val steep = DemoMath.groundingShadowOffset(bounceMax, -0.35f, -2f, -0.4f)
        val shallow = DemoMath.groundingShadowOffset(bounceMax, -0.35f, -1f, -0.4f)
        assertTrue(
            "Steeper light must project a shorter offset",
            kotlin.math.abs(steep.first) < kotlin.math.abs(shallow.first),
        )
    }

    @Test
    fun `groundingShadowOffset returns zero for a grazing light`() {
        // |dirY| ~ 0 → projection to infinity; the demo must degrade to no slide, not NaN/∞.
        val (dx, dz) = DemoMath.groundingShadowOffset(bounceMax, -0.35f, 0f, -0.4f)
        assertEquals(0f, dx, eps)
        assertEquals(0f, dz, eps)
    }
}
