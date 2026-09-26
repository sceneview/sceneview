package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.demo.VIEWER_MIN_ZOOM_FACTOR
import io.github.sceneview.demo.common.placement.PlacementRotation
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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

    @Test fun `viewerFraming frames the same mesh identically at every order of magnitude`() {
        // #3543 — an STL authored in metres and read as millimetres is 2 mm across. The framing
        // must put it on screen at the same size as the same mesh a hundred, and a million, times
        // bigger: the 0.2 m floor this used to carry framed the small one forty times too far out,
        // which is the "near-invisible dot" of the report. The top of the range stops short of the
        // 900 m far-plane ceiling, which is a real constraint rather than a scale opinion.
        val tiny = DemoMath.viewerFraming(0.002f, 0.00114f, 0.00086f, 411f, 914f, 96f, 128f)
        for (scale in listOf(1f, 100f, 1_000f, 100_000f)) {
            val scaled = DemoMath.viewerFraming(
                0.002f * scale, 0.00114f * scale, 0.00086f * scale, 411f, 914f, 96f, 128f,
            )
            assertEquals("distance scales with the subject", tiny.distance * scale, scaled.distance,
                tiny.distance * scale * 0.0001f)
            // Same angular size on screen, therefore the same picture.
            assertEquals(
                heightFraction(tiny, 0.00114f, 0.00086f),
                heightFraction(scaled, 0.00114f * scale, 0.00086f * scale),
                0.0001f,
            )
        }
        // And the tiny one is genuinely close, not parked on an absolute floor.
        assertTrue("2 mm frames at millimetres, not at 20 cm", tiny.distance < 0.02f)
        assertTrue(tiny.distance > DemoMath.MIN_VIEWER_DISTANCE)
    }

    @Test fun `viewerNearPlane follows a small subject and leaves metre-scale scenes alone`() {
        // A metre-scale scene keeps the library default exactly — no depth-precision change.
        assertEquals(DemoMath.DEFAULT_NEAR_PLANE, DemoMath.viewerNearPlane(2.7f), 0f)
        assertEquals(DemoMath.DEFAULT_NEAR_PLANE, DemoMath.viewerNearPlane(1f), 0f)
        // The 2 mm mesh frames at ~5 mm: a 1 cm near plane would swallow it whole.
        val tiny = DemoMath.viewerFraming(0.002f, 0.00114f, 0.00086f, 411f, 914f, 96f, 128f)
        val near = DemoMath.viewerNearPlane(tiny.distance)
        assertTrue("near plane is in front of the subject", near < tiny.distance * VIEWER_MIN_ZOOM_FACTOR)
        assertEquals(DemoMath.DEFAULT_NEAR_PLANE, DemoMath.viewerNearPlane(Float.NaN), 0f)
        assertEquals(DemoMath.DEFAULT_NEAR_PLANE, DemoMath.viewerNearPlane(0f), 0f)
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
    fun `single-model spin keeps the pivot fixed only when translate and rotate signs match`() {
        // #3821 — ModelViewerDemo's Single-model "Spin scene" counter-translates the mesh's
        // bounding-box centre `C` so it stays put under the camera while the node itself
        // rotates by `modelYaw`: `position = C - rotateAroundCentre(C, modelYaw)`, then the
        // node applies `Rotation(y = modelYaw)` on top. The world position of the pivot is
        // `position + Rotate(modelYaw)·C`, and it only reduces back to `C` (stays fixed) when
        // `rotateAroundCentre` is called with the SAME signed angle as the node's own rotation.
        // The bug called it with `-modelYaw`, breaking the cancellation, so the mesh visibly
        // swam off its centre as it spun — read at a glance as the environment orbiting rather
        // than a clean model-only spin.
        //
        // `nodeRotated{X,Z}` is a standalone re-derivation of the node's actual right-handed
        // Y-axis rotation (independent of `rotateAroundCentre`'s own implementation), so this
        // test would also catch `rotateAroundCentre` and the SDK's `Rotation(y = …)` drifting
        // to different sign conventions in the future.
        val centreX = 0.12f
        val centreZ = -0.34f
        for (modelYaw in listOf(15f, 90f, 137f, 200f, 270f, 359f)) {
            val rad = Math.toRadians(modelYaw.toDouble())
            val cosY = cos(rad).toFloat()
            val sinY = sin(rad).toFloat()
            val nodeRotatedX = centreX * cosY + centreZ * sinY
            val nodeRotatedZ = -centreX * sinY + centreZ * cosY

            // Fixed pairing (the fix): translate by rotateAroundCentre(+modelYaw).
            val (fixedRx, fixedRz) = DemoMath.rotateAroundCentre(centreX, centreZ, modelYaw)
            val fixedWorldX = (centreX - fixedRx) + nodeRotatedX
            val fixedWorldZ = (centreZ - fixedRz) + nodeRotatedZ
            assertEquals("fixed pairing must hold pivot x at yaw=$modelYaw", centreX, fixedWorldX, eps)
            assertEquals("fixed pairing must hold pivot z at yaw=$modelYaw", centreZ, fixedWorldZ, eps)

            // Buggy pairing: translate by rotateAroundCentre(-modelYaw) — the pivot drifts.
            val (buggyRx, buggyRz) = DemoMath.rotateAroundCentre(centreX, centreZ, -modelYaw)
            val buggyWorldX = (centreX - buggyRx) + nodeRotatedX
            val buggyWorldZ = (centreZ - buggyRz) + nodeRotatedZ
            val drift = hypot(buggyWorldX - centreX, buggyWorldZ - centreZ)
            assertTrue("buggy pairing must drift the pivot at yaw=$modelYaw (drift=$drift)", drift > 0.01f)
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

        // The framing box is the union of the slots' `scale` cubes, each centred on its x / z and
        // standing on the ground plane — recomputed here from the literals above.
        assertEquals(-0.90f, PARK_BOUNDS.minX, eps)
        assertEquals(0.90f, PARK_BOUNDS.maxX, eps)
        assertEquals(-0.90f, PARK_BOUNDS.minY, eps)
        assertEquals(0.90f, PARK_BOUNDS.maxY, eps)
        assertEquals(-1.10f, PARK_BOUNDS.minZ, eps)
        assertEquals(0.70f, PARK_BOUNDS.maxZ, eps)
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
    fun `parkCamera target is the centre of the formation bounds`() {
        val camera = parkCamera(411f, 914f, 96f, 128f)
        assertEquals(PARK_BOUNDS.center.x, camera.target.x, eps)
        assertEquals(PARK_BOUNDS.center.y, camera.target.y, eps)
        assertEquals(PARK_BOUNDS.center.z, camera.target.z, eps)
        // In front of the formation, pitched above it, at the returned distance.
        val dy = camera.eye.y - camera.target.y
        val dz = camera.eye.z - camera.target.z
        assertTrue("eye must be in front (+Z)", dz > 0f)
        assertTrue("eye must be above the target", dy > 0f)
        assertEquals(camera.distance, hypot(dy, dz), 1e-3f)
        assertEquals(camera.eye.x, camera.target.x, eps)
    }

    @Test
    fun `parkCamera keeps every corner of the formation inside the visible band`() {
        // The defect in #3923: the old cover distance (~2.1 m) put the lens inside the streamed
        // trees and cropped the bundled fallbacks to two lanterns. Every corner of PARK_BOUNDS
        // (an upper bound of whatever stands in the slots) must project inside the band between
        // the identity row and the dock, in front of the near plane, on phones, tablets and in
        // landscape.
        // width, height, top inset, bottom inset (dp)
        val viewports = listOf(
            floatArrayOf(411f, 914f, 96f, 128f), // Pixel 7a portrait
            floatArrayOf(360f, 780f, 96f, 128f), // small phone
            floatArrayOf(800f, 1280f, 96f, 128f), // tablet portrait
            floatArrayOf(914f, 411f, 72f, 104f), // phone landscape
            floatArrayOf(1280f, 800f, 72f, 104f), // tablet landscape
        )
        for (viewport in viewports) {
            val w = viewport[0]
            val h = viewport[1]
            val top = viewport[2]
            val bottom = viewport[3]
            val camera = parkCamera(w, h, top, bottom)
            val bandTop = 1f - 2f * top / h
            val bandBottom = -1f + 2f * bottom / h
            for (corner in parkCorners()) {
                val (x, y, depth) = project(camera, corner, aspect = w / h)
                val where = "viewport ${w}x$h corner $corner"
                assertTrue("$where is behind the near plane (depth $depth)", depth > DemoMath.DEFAULT_NEAR_PLANE)
                assertTrue("$where is off the side of the frame (x $x)", abs(x) <= 1f)
                assertTrue("$where is above the band (y $y > $bandTop)", y <= bandTop)
                assertTrue("$where is below the band (y $y < $bandBottom)", y >= bandBottom)
            }
        }
    }

    @Test
    fun `parkCamera no longer opens on the old cover distance`() {
        // The #2913 cover distance filled the frame height with the tallest model. The fitted
        // camera must stand well behind it, or the regression of #3923 is back.
        val cover = (PARK_HEIGHT / 2f) / halfTan
        val fitted = parkCamera(411f, 914f, 96f, 128f).distance
        assertTrue("fitted $fitted must be well behind the old cover distance $cover", fitted > 2f * cover)
    }

    @Test
    fun `parkCamera honours a camera distance override along the same direction`() {
        val fitted = parkCamera(411f, 914f, 96f, 128f)
        val pinned = parkCamera(411f, 914f, 96f, 128f, distanceOverride = 3f)
        assertEquals(3f, pinned.distance, eps)
        assertEquals(fitted.target, pinned.target)
        // Same direction from the target: the eye offsets are proportional.
        val ratio = 3f / fitted.distance
        assertEquals((fitted.eye.y - fitted.target.y) * ratio, pinned.eye.y - pinned.target.y, 1e-4f)
        assertEquals((fitted.eye.z - fitted.target.z) * ratio, pinned.eye.z - pinned.target.z, 1e-4f)
        // An unusable override is ignored rather than propagated.
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("override=$bad", fitted.distance, parkCamera(411f, 914f, 96f, 128f, bad).distance, eps)
        }
    }

    /** The eight corners of [PARK_BOUNDS]. */
    private fun parkCorners(): List<Position> = PARK_BOUNDS.run {
        listOf(minX, maxX).flatMap { x ->
            listOf(minY, maxY).flatMap { y -> listOf(minZ, maxZ).map { z -> Position(x, y, z) } }
        }
    }

    /**
     * Pinhole projection of [p] through a camera at [camera]'s eye looking at its target with the
     * default 28 mm lens: normalised device x / y in `[-1, 1]` across the viewport, and the depth
     * along the view axis.
     */
    private fun project(camera: ParkCamera, p: Position, aspect: Float): Triple<Float, Float, Float> {
        val forward = normalize(camera.target - camera.eye)
        val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
        val up = cross(right, forward)
        val v = p - camera.eye
        val depth = dot(v, forward)
        return Triple(dot(v, right) / (depth * halfTan * aspect), dot(v, up) / (depth * halfTan), depth)
    }

    @Test
    fun `rotateAroundCentre handles negative yaw symmetrically`() {
        val pos = DemoMath.rotateAroundCentre(1f, 0f, sceneYaw = 90f)
        val neg = DemoMath.rotateAroundCentre(1f, 0f, sceneYaw = -90f)
        // 90° CW and -90° CW are mirrored: (0, -1) vs (0, 1).
        assertEquals(pos.first, neg.first, eps)
        assertEquals(-pos.second, neg.second, eps)
    }

    // ── placementRotationFor (#1477 → #3735) ────────────────────────────────

    /** The GLB's own root-node quaternion, read from the JSON chunk of the bundled file. */
    private fun glbRootRotation(glb: File): Quaternion {
        val bytes = glb.readBytes()
        val jsonLength = ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val json = String(bytes, 20, jsonLength, Charsets.UTF_8)
        val values = Regex("\"rotation\"\\s*:\\s*\\[([^\\]]+)]").find(json)
            ?.groupValues?.get(1)?.split(',')?.map { it.trim().toFloat() }
            ?: return Quaternion()
        // glTF stores [x, y, z, w], the same order as kotlin-math's constructor.
        return Quaternion(values[0], values[1], values[2], values[3])
    }

    private fun bundledModel(assetPath: String) =
        repoFile("samples/android-demo/src/main/assets/$assetPath")

    private fun assertSameAxes(message: String, expected: Quaternion, actual: Quaternion) {
        for (axis in listOf(Float3(x = 1f), Float3(y = 1f), Float3(z = 1f))) {
            val e = expected * axis
            val a = actual * axis
            assertEquals("$message $axis x", e.x, a.x, 1e-5f)
            assertEquals("$message $axis y", e.y, a.y, 1e-5f)
            assertEquals("$message $axis z", e.z, a.z, 1e-5f)
        }
    }

    @Test
    fun `placementRotationFor never tilts a bundled model off its loaded pose`() {
        // Every GLB in the assets tree, so a model added later is covered on arrival, plus a
        // streamed file:// URI. glTF is +Y up: a placement may turn a model, never tip it.
        val bundled = bundledModel("models").listFiles { file -> file.extension == "glb" }!!
            .map { "models/${it.name}" }
        assertTrue("the helmet must be among $bundled", DemoMath.HELMET_ASSET in bundled)
        for (path in bundled + "file:///data/user/0/app/cache/streamed.glb") {
            val rotation = Quaternion.fromEuler(DemoMath.placementRotationFor(path))
            assertEquals("$path tilt", 0f, PlacementRotation.tiltDegrees(rotation), 1e-4f)
        }
    }

    @Test
    fun `the helmet stands in AR the way the Model Viewer shows it`() {
        // The +90° X root quaternion is the file's own Z-up to Y-up conversion. gltfio applies
        // it on load, which is why the Model Viewer — no correction at all — shows the helmet
        // upright, visor forward.
        val root = glbRootRotation(bundledModel(DemoMath.HELMET_ASSET))
        assertEquals(0.70710677f, root.x, 1e-6f)
        assertEquals(0f, root.y, 1e-6f)
        assertEquals(0f, root.z, 1e-6f)
        assertEquals(0.70710677f, root.w, 1e-6f)

        val viewer = root
        val ar = Quaternion.fromEuler(DemoMath.placementRotationFor(DemoMath.HELMET_ASSET)) * root
        assertSameAxes("helmet AR vs viewer", viewer, ar)

        // The regression (#3735): the #1477 correction undid that conversion and tipped the
        // helmet's up axis a full 90° — on its back, visor to the ceiling, in AR only.
        val legacy = Quaternion.fromEuler(Rotation(x = -90f))
        assertEquals(90f, PlacementRotation.tiltDegrees(legacy), 1e-3f)
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${File("").absolutePath}")
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
