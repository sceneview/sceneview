package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.sketchfab.SampleAssets
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    // ── viewerFraming ───────────────────────────────────────────────────────

    private val halfTan = tan(Math.toRadians(DemoMath.DEFAULT_VERTICAL_FOV_DEGREES) / 2.0).toFloat()

    /** Fraction of the full viewport height a model of [extentY] spans at [f]'s distance. */
    private fun heightFraction(f: DemoMath.ViewerFraming, extentY: Float, extentZ: Float) =
        extentY / (2f * (f.distance - extentZ * DemoMath.DEPTH_ALLOWANCE) * halfTan)

    @Test fun `viewerFraming fills 65 percent of the visible band for a tall model`() {
        // Portrait phone: 411 × 914 dp, 96 dp identity row, 104 dp dock + 24 dp nav bar.
        val f = DemoMath.viewerFraming(1f, 2f, 0.5f, 411f, 914f, 96f, 128f)
        val visible = (914f - 96f - 128f) / 914f
        assertEquals(DemoMath.VIEWER_FILL, heightFraction(f, 2f, 0.5f) / visible, 0.001f)
    }

    @Test fun `viewerFraming fills 92 percent of the width for a wide model`() {
        val f = DemoMath.viewerFraming(4f, 1f, 1f, 411f, 914f, 96f, 128f)
        val widthFraction = 4f / (2f * (f.distance - 1f * DemoMath.DEPTH_ALLOWANCE) * halfTan * (411f / 914f))
        assertEquals(DemoMath.VIEWER_HORIZONTAL_FILL, widthFraction, 0.001f)
        assertTrue("wide model is constrained by width, so it spans less than 65 % of the height",
            heightFraction(f, 1f, 1f) / ((914f - 96f - 128f) / 914f) < DemoMath.VIEWER_FILL)
    }

    @Test fun `viewerFraming backs off by a share of the depth for a deep model`() {
        val shallow = DemoMath.viewerFraming(1f, 1f, 0f, 411f, 914f, 96f, 128f)
        val deep = DemoMath.viewerFraming(1f, 1f, 3f, 411f, 914f, 96f, 128f)
        assertEquals(shallow.distance + 3f * DemoMath.DEPTH_ALLOWANCE, deep.distance, 0.0001f)
    }

    @Test fun `viewerFraming looks from the front and slightly above`() {
        val f = DemoMath.viewerFraming(1f, 1f, 1f, 411f, 914f, 0f, 0f)
        val (_, eyeY, eyeZ) = f.eyeOffset
        assertTrue("eye is in front (+Z)", eyeZ > 0f)
        val elevation = Math.toDegrees(atan((eyeY / eyeZ).toDouble())).toFloat()
        assertEquals(DemoMath.VIEWER_PITCH_DEGREES, elevation, 0.01f)
        assertEquals(f.distance, hypot(eyeY, eyeZ), 0.001f)
        assertEquals(0f, f.targetOffset.second, 0.0001f)
    }

    @Test fun `viewerFraming shifts the target so the model sits mid-band`() {
        // Dock band taller than the identity row: the visible centre is ABOVE the viewport centre,
        // so the target must sit BELOW the model (model drawn higher on screen).
        val f = DemoMath.viewerFraming(1f, 1f, 1f, 411f, 914f, 96f, 128f)
        assertTrue(f.targetOffset.second < 0f)
        val halfTan = tan(Math.toRadians(DemoMath.DEFAULT_VERTICAL_FOV_DEGREES) / 2.0).toFloat()
        val expectedShift = ((96f - 128f) / 2f) / (914f / 2f) * f.distance * halfTan
        val shift = -hypot(f.targetOffset.second, f.targetOffset.third)
        assertEquals(expectedShift, shift, 0.0001f)
        // Symmetric insets: no shift at all.
        assertEquals(0f, DemoMath.viewerFraming(1f, 1f, 1f, 411f, 914f, 100f, 100f).targetOffset.second, 0.0001f)
    }

    @Test fun `viewerFraming keeps the Khronos Fox inside the far plane and never collapses`() {
        // The bundled Fox is ~155 glTF units long: it must frame far back but inside 1000 m.
        val fox = DemoMath.viewerFraming(155f, 80f, 40f, 411f, 914f, 96f, 128f)
        assertTrue(fox.distance in 100f..900f)
        // Degenerate bounds and viewport: a finite, positive distance on the safe floor.
        val empty = DemoMath.viewerFraming(0f, Float.NaN, -1f, 0f, 0f, 0f, 0f)
        assertEquals(0.2f, empty.distance, 0.0001f)
        assertTrue(empty.eyeOffset.toList().all { it.isFinite() })
    }

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
        // Pin the actual demo's 4 slot offsets at yaw=0 (the default) to lock in the
        // visible layout. Since #2913 the formation is centred on the world origin
        // (`autoCenterContent = false`), so the offsets ARE the world coordinates:
        // back row z=-0.2, front row z=+0.2. The literals live in `parkSlotLayout` below, which is
        // the single place the layout is pinned.
        for ((index, slot) in PARK_SLOTS.withIndex()) {
            val (rx, rz) = DemoMath.rotateAroundCentre(slot.x, slot.z, sceneYaw = 0f)
            // At yaw=0 the rotation is identity.
            assertEquals("slot $index x at yaw=0", slot.x, rx, eps)
            assertEquals("slot $index z at yaw=0", slot.z, rz, eps)
        }
    }

    @Test
    fun `park formation layout is pinned and the framing bounds are derived from it`() {
        // One test owns the layout literals, so changing PARK_SLOTS fails HERE — loudly and in one
        // place — instead of leaving the framing assertions describing a formation that no longer
        // exists. Before #2913 the bounds were restated next to the layout and could drift.
        // MultiModelSection unrolls one `rememberSlugFile` / `rememberFileModelInstance` pair
        // per slot (fixed composition slots, #1464) while everything else is sized from
        // PARK_SLOTS. Resizing the layout without matching that unrolling compiles and then
        // throws IndexOutOfBounds on the first composition — a crash on screen. Pin the count
        // so it fails in the unit tests, with an instruction, instead.
        assertEquals(
            "PARK_SLOTS size changed — add or remove a matching rememberSlugFile / " +
                "rememberFileModelInstance pair in MultiModelSection before changing this",
            4,
            PARK_SLOTS.size,
        )

        assertEquals(
            listOf(
                ParkSlot(uid = "d841c3bcc5324daebee50f45619e05fc", x = 0.0f, z = -0.2f, scale = 1.80f),
                ParkSlot(uid = "6d1aeea748f147789004bc03e1930d32", x = 0.0f, z = 0.2f, scale = 0.65f),
                ParkSlot(uid = "4f6ab5594a8a415aba3f958682b9ced5", x = -0.55f, z = 0.2f, scale = 0.40f),
                ParkSlot(uid = "fd582b0d4a8c4af1a1b5c4f21a481c93", x = 0.55f, z = 0.2f, scale = 0.15f),
            ),
            PARK_SLOTS,
        )

        // Every slot is bottom-aligned on a shared ground plane, so the union is as tall as the
        // tallest model — recomputed here independently of the production expression.
        assertEquals(PARK_SLOTS.maxOf { it.scale }, PARK_HEIGHT, eps)
        assertEquals(1.80f, PARK_HEIGHT, eps)

        // Each slot orbits the centre at hypot(x, z) AND spins on its own Y, so a model filling its
        // `scale` cube reaches scale·√2/2 from its own centre at 45° — not scale/2.
        val expectedSpan = 2f * PARK_SLOTS.maxOf {
            hypot(it.x, it.z) + it.scale * sqrt(2f) / 2f
        }
        assertEquals(expectedSpan, PARK_SPAN, eps)
        assertTrue(
            "the formation must be wider than it is tall, or cover framing has nothing to crop",
            PARK_SPAN > PARK_HEIGHT,
        )
    }

    @Test
    fun `every park slot uid resolves to a park registry entry`() {
        // The visibility chips read their label off the resolved slug's `displayName` (#2933). A
        // uid that no longer exists in the registry does not crash — the slot degrades to the
        // positional "Model N" label and, worse, loads whatever sits at the same INDEX in the
        // `park` category. That is silent on a device and invisible in a screenshot, so it is
        // pinned here: a registry edit that drops or re-keys a park asset fails the build.
        // `displayName` is not asserted non-blank here: SketchfabSlug's own `init` already
        // requires it, so a blank one cannot exist in the registry to be caught.
        val park = SampleAssets.byCategory["park"].orEmpty()
        for ((index, slot) in PARK_SLOTS.withIndex()) {
            assertNotNull("slot $index uid ${slot.uid} is not in SampleAssets", SampleAssets.byUid[slot.uid])
            assertTrue(
                "slot $index uid ${slot.uid} resolves outside the `park` category",
                park.any { it.uid == slot.uid },
            )
        }
    }

    @Test
    fun `parkCameraDistance frames the formation full height on both portrait classes`() {
        // The demo's own entry point, not just the math underneath it: phone and tablet portrait
        // must resolve to the same distance (Filament fixes the VERTICAL fov), and that distance
        // must be the one that puts PARK_HEIGHT edge to edge.
        val expected = (PARK_HEIGHT / 2f) / tan(Math.toRadians(defaultVfovDegrees) / 2.0).toFloat()
        assertEquals(expected, parkCameraDistance(0.47f), 1e-3f)
        assertEquals(expected, parkCameraDistance(0.64f), 1e-3f)
        assertEquals(parkCameraDistance(0.47f), parkCameraDistance(PARK_FALLBACK_ASPECT), eps)
    }

    @Test
    fun `rotateAroundCentre handles negative yaw symmetrically`() {
        val pos = DemoMath.rotateAroundCentre(1f, 0f, sceneYaw = 90f)
        val neg = DemoMath.rotateAroundCentre(1f, 0f, sceneYaw = -90f)
        // 90° CW and -90° CW are mirrored: (0, -1) vs (0, 1).
        assertEquals(pos.first, neg.first, eps)
        assertEquals(-pos.second, neg.second, eps)
    }

    // ── coverDistance (#2913) ───────────────────────────────────────────────

    /** SceneView's default 28 mm lens against Filament's 24 mm sensor height. */
    private val defaultVfovDegrees = Math.toDegrees(2.0 * atan(24.0 / (2.0 * 28.0)))

    // The Multi-Model park formation, read from the SAME declarations the demo frames itself with
    // (`ParkFraming.kt`). Restating them as literals here is exactly what would let the layout and
    // the framing drift apart in silence: the assertions would stay green while describing a
    // formation that no longer existed (#2913).
    private val parkWidth = PARK_SPAN
    private val parkHeight = PARK_HEIGHT

    /** Half-extents of the world visible at [distance], for the cover assertions below. */
    private fun visibleHalfExtents(distance: Float, aspect: Float): Pair<Float, Float> {
        val halfHeight = distance * tan(Math.toRadians(defaultVfovDegrees) / 2.0).toFloat()
        return (halfHeight * aspect) to halfHeight
    }

    @Test
    fun `coverDistance frames the park formation full height on a portrait viewport`() {
        // The defect in #2913: the camera sat ~0.6 m from the grove centroid, so a wider
        // viewport revealed backdrop wall instead of more trees. The framing must put the
        // formation's full height in the frame — d = (h / 2) / tan(vfov / 2).
        val expected = (parkHeight / 2f) / tan(Math.toRadians(defaultVfovDegrees) / 2.0).toFloat()
        val phone = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.47f)
        assertEquals(expected, phone, eps)
    }

    @Test
    fun `coverDistance is identical on phone and tablet portrait aspects`() {
        // Filament fixes the VERTICAL fov, so between two portrait aspects the vertical term
        // wins on both and the distance does not move — the tablet simply sees more world to
        // the left and right, which at this distance is more trees rather than backdrop.
        val phone = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.47f)
        val tablet = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.64f)
        assertEquals(phone, tablet, eps)
    }

    @Test
    fun `coverDistance covers both axes at every portrait and landscape aspect`() {
        // The contract: at the returned distance the content is at least as large as the
        // frame on BOTH axes (cover), never smaller on one of them (which is `fit`).
        for (aspect in listOf(0.42f, 0.47f, 0.56f, 0.64f, 0.75f, 1.0f, 1.33f, 1.78f, 2.4f)) {
            val d = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, aspect)
            val (halfWidth, halfHeight) = visibleHalfExtents(d, aspect)
            assertTrue(
                "aspect=$aspect: visible half-width $halfWidth must not exceed the content's " +
                    "${parkWidth / 2f}",
                halfWidth <= parkWidth / 2f + eps,
            )
            assertTrue(
                "aspect=$aspect: visible half-height $halfHeight must not exceed the content's " +
                    "${parkHeight / 2f}",
                halfHeight <= parkHeight / 2f + eps,
            )
        }
    }

    @Test
    fun `coverDistance pulls the camera in as the viewport widens past the content`() {
        // Once the frame is wider than the formation is tall relative to its width, the
        // horizontal term takes over: a landscape / foldable viewport must come CLOSER, or the
        // formation stops spanning the width and the backdrop takes over the sides again.
        val portrait = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.64f)
        val landscape = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 1.78f)
        assertTrue("landscape ($landscape) must be closer than portrait ($portrait)", landscape < portrait)
        val expected = (parkWidth / 2f) /
            (tan(Math.toRadians(defaultVfovDegrees) / 2.0).toFloat() * 1.78f)
        assertEquals(expected, landscape, eps)
    }

    @Test
    fun `coverDistance scales inversely with fill`() {
        val base = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.47f)
        // fill = 2 ⇒ the content spans twice the frame ⇒ half the distance.
        val cropped = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.47f, fill = 2f)
        assertEquals(base / 2f, cropped, eps)
        // fill = 0.5 ⇒ the content spans half the frame ⇒ twice the distance.
        val roomy = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.47f, fill = 0.5f)
        assertEquals(base * 2f, roomy, eps)
    }

    @Test
    fun `coverDistance falls back to a square viewport on an unmeasured aspect`() {
        // BoxWithConstraints can report a zero / infinite constraint before layout; a NaN
        // camera position would black out the viewport, so degenerate input resolves to 1.
        val square = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 1f)
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("aspect=$bad", square, DemoMath.coverDistance(
                parkWidth, parkHeight, defaultVfovDegrees, bad,
            ), eps)
        }
        assertEquals(square, DemoMath.coverDistance(
            parkWidth, parkHeight, defaultVfovDegrees, 1f, fill = Float.NaN,
        ), eps)
    }

    @Test
    fun `coverDistance lets a single measurable axis drive the framing`() {
        // A degenerate axis must not constrain the result (a zero would otherwise win the
        // `min` and drop the camera onto the subject).
        val heightOnly = DemoMath.coverDistance(0f, parkHeight, defaultVfovDegrees, 0.47f)
        val expected = (parkHeight / 2f) / tan(Math.toRadians(defaultVfovDegrees) / 2.0).toFloat()
        assertEquals(expected, heightOnly, eps)
        val widthOnly = DemoMath.coverDistance(parkWidth, 0f, defaultVfovDegrees, 1.78f)
        val expectedWidth = (parkWidth / 2f) /
            (tan(Math.toRadians(defaultVfovDegrees) / 2.0).toFloat() * 1.78f)
        assertEquals(expectedWidth, widthOnly, eps)
    }

    @Test
    fun `coverDistance never returns NaN for a non-finite field of view`() {
        // `Double.coerceIn` returns NaN unchanged, so clamping the FOV is not enough on its own —
        // a NaN would survive every later guard and reach the camera as a NaN position, which
        // blacks out the viewport. Non-finite input falls back to the default 28 mm lens.
        val default = DemoMath.coverDistance(parkWidth, parkHeight, defaultVfovDegrees, 0.47f)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val d = DemoMath.coverDistance(parkWidth, parkHeight, bad, 0.47f)
            assertTrue("fov=$bad returned $d", d.isFinite())
            assertEquals("fov=$bad", default, d, 1e-2f)
        }
    }

    @Test
    fun `coverDistance stays inside its clamp`() {
        // A fully degenerate box clamps to the far end rather than returning 0 / NaN.
        assertEquals(50f, DemoMath.coverDistance(0f, 0f, defaultVfovDegrees, 1f), eps)
        // A hair-thin box clamps to the near end rather than putting the camera at 0.
        assertEquals(0.2f, DemoMath.coverDistance(0.0001f, 0.0001f, defaultVfovDegrees, 1f), eps)
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
        // Fox, lantern, toy car, shiba are authored upright — no correction. So are the
        // three Khronos furniture/tableware models the picker gained in #3324: they are
        // authored in metres, Y-up, sitting on y = 0, which is why they need no rotation
        // and why their `realWorldSizeMeters` is the GLB's own measured extent.
        for (path in listOf(
            "models/khronos_fox.glb",
            "models/khronos_glam_velvet_sofa.glb",
            "models/khronos_iridescent_dish.glb",
            "models/khronos_lantern.glb",
            "models/khronos_sheen_chair.glb",
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

    // ── floatHoverY — the floating twin (#2740 differentiated-motion redesign) ───

    private val floatPeriod = DemoMath.CONTACT_FLOAT_PERIOD_NANOS
    private val floatCenter = DemoMath.CONTACT_FLOAT_CENTER_Y_METERS
    private val floatBob = DemoMath.CONTACT_FLOAT_BOB_METERS

    @Test
    fun `floatHoverY rests at centre height at t=0 and every full period`() {
        // t = 0 is the deterministic QA-mode pose: the floating box sits at its rest height,
        // high above the floor (sin 0 = 0) — the same zeroed clock that lands bounceHeight.
        assertEquals(floatCenter, DemoMath.floatHoverY(0L), eps)
        // One full period later, back to the rest height (sin 2π = 0).
        assertEquals(floatCenter, DemoMath.floatHoverY(floatPeriod), eps)
        // Negative elapsed (pre-first-frame) clamps to rest rather than extrapolating.
        assertEquals(floatCenter, DemoMath.floatHoverY(-1L), eps)
    }

    @Test
    fun `floatHoverY bobs a smooth sine above and below the rest height`() {
        // Quarter period → top of the bob (sin π/2 = 1).
        assertEquals(floatCenter + floatBob, DemoMath.floatHoverY(floatPeriod / 4), eps)
        // Half period → back through centre (sin π = 0).
        assertEquals(floatCenter, DemoMath.floatHoverY(floatPeriod / 2), eps)
        // Three-quarter period → bottom of the bob (sin 3π/2 = -1).
        assertEquals(floatCenter - floatBob, DemoMath.floatHoverY(floatPeriod * 3 / 4), eps)
    }

    @Test
    fun `floatHoverY never leaves the bob envelope`() {
        // Sample a whole period densely: the hover must stay within ±bob of the rest height,
        // so the floating box can never drift into the floor or the wall TV.
        for (i in 0..200) {
            val t = floatPeriod * i / 200
            val y = DemoMath.floatHoverY(t)
            assertTrue(
                "y=$y out of envelope at step $i",
                y in (floatCenter - floatBob - eps)..(floatCenter + floatBob + eps),
            )
        }
    }

    @Test
    fun `floatHoverY degenerate period returns the rest height`() {
        assertEquals(floatCenter, DemoMath.floatHoverY(123L, periodNanos = 0L), eps)
        assertEquals(floatCenter, DemoMath.floatHoverY(123L, periodNanos = -5L), eps)
    }

    @Test
    fun `floatHoverY box faces clear the landed box and stay below the wall TV`() {
        // Asserts on box FACES, never on centres: a centre comparison is satisfied by two boxes
        // that visibly interpenetrate, so it cannot testify to any clearance (#2961 — the
        // predecessor of this test compared centres while its comment claimed a face clearance).
        // Box edge is 0.38 m (BOX_EDGE_METERS in the demo), so the half-edge is 0.19 m; the demo
        // seats the grounded box at (half-edge + hop) and the floating box at floatHoverY().
        val boxHalfEdge = 0.38f / 2f
        val groundedLandedTop = boxHalfEdge + boxHalfEdge
        val groundedPeakTop = boxHalfEdge + DemoMath.CONTACT_BOUNCE_MAX_HEIGHT_METERS + boxHalfEdge
        val floatingLowestBottom = floatCenter - floatBob - boxHalfEdge
        val floatingLowestTop = floatCenter - floatBob + boxHalfEdge
        val floatingHighestTop = floatCenter + floatBob + boxHalfEdge

        // 1. Floor of the design: the floating box's bottom face never sinks below the grounded
        // box's top face at its LANDING pose. Measured clearance is 0.000 m — the two faces are
        // exactly flush (0.38 m), so this bound is knife-edge and any downward drift breaks it.
        val landedClearance = floatingLowestBottom - groundedLandedTop
        assertTrue(
            "floating bottom ($floatingLowestBottom m) must not sink below the landed box's top " +
                "($groundedLandedTop m) — clearance ${landedClearance} m, must be >= 0.000 m",
            landedClearance >= -eps,
        )

        // 2. And the honest converse: over the PEAK of the hop there is NO clearance at all. The
        // grounded box's top face rises 0.340 m ABOVE the floating box's lowest bottom face; the
        // silhouettes overlap in screen-Y and are told apart only by their 0.38 m X separation.
        // Pinned so the KDoc's stated overlap can never quietly stop matching the constants.
        assertEquals(
            "grounded peak top ($groundedPeakTop m) vs floating lowest bottom " +
                "($floatingLowestBottom m): overlap must be 0.340 m, not a clearance",
            0.340f,
            groundedPeakTop - floatingLowestBottom,
            eps,
        )

        // 3. What actually carries the "aloft" reading at every phase is the TOP-face ordering:
        // the floating box's top face at its lowest still clears the grounded box's top face at
        // its highest by 0.040 m, so the hopping twin never overtakes it.
        val topMargin = floatingLowestTop - groundedPeakTop
        assertTrue(
            "floating top at the bottom of the bob ($floatingLowestTop m) must stay above the " +
                "grounded top at the peak of the hop ($groundedPeakTop m) — margin ${topMargin} m, " +
                "expected 0.040 m",
            topMargin > eps,
        )
        assertEquals("top-face margin, metres", 0.040f, topMargin, eps)

        // 4. Headroom: the floating box's top face stays 0.070 m below the wall TV's bottom edge
        // (TV centre y = 1.3 m, height 0.74 m → bottom 0.93 m) so they never visually overlap.
        val tvMargin = 0.93f - floatingHighestTop
        assertTrue(
            "floating box top ($floatingHighestTop m) must stay below the TV bottom (0.93 m) — " +
                "margin ${tvMargin} m, expected 0.070 m",
            tvMargin > eps,
        )
        assertEquals("wall-TV headroom, metres", 0.070f, tvMargin, eps)
    }
}
