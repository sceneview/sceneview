package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.tan
import org.junit.Test

/**
 * Pins the shared demo framing helper and the per-demo numbers it replaced (#3426).
 *
 * The audit behind the issue found that every non-AR sample carried a hand-tuned orbit radius —
 * `2.0f`, `2.2f`, `2.4f`, `1.6f`, or nothing at all, which left the library's stock 2.78 m pose —
 * and that **none** of them looked at the viewport aspect. On a portrait phone the horizontal FOV
 * is the binding one, so those literals produced anything between a subject overflowing the frame
 * and one filling a sixth of it, for the same content at the same scale.
 *
 * These tests state the property the literals could not: *whatever* the subject's size, the
 * framing puts it at a consistent fraction of the frame.
 */
class DemoOrbitFramingTest {

    /** Pixel 9, the device every one of the three issues was reported from. */
    private val phonePortrait = 1080f / 2424f

    /** Vertical FOV of SceneView's default 28 mm lens, against Filament's 24 mm sensor. */
    private val vfov = io.github.sceneview.verticalFovDegreesForFocalLength(28.0)

    /** Fraction of the frame WIDTH a cube of side [extent] spans when framed at [radius]. */
    private fun widthFill(extent: Float, radius: Float, aspect: Float = phonePortrait): Float {
        val sweptRadius = kotlin.math.sqrt(2f) * extent / 2f
        val halfWidth = radius * tan(Math.toRadians(vfov) / 2.0).toFloat() * aspect
        return sweptRadius / halfWidth
    }

    // ── The property the literals could not hold ──────────────────────────────────────────────

    @Test
    fun everySubjectSizeLandsOnTheSameFractionOfTheFrame() {
        // A 5 cm bee and a 155 m landscape must read as "comfortably framed" identically. This is
        // the whole point of computing the radius instead of writing one down.
        val fills = listOf(0.05f, 0.2f, 0.3f, 0.5f, 0.85f, 1f, 155f).map {
            widthFill(it, fitOrbitRadius(it, it, it, phonePortrait))
        }
        fills.forEach { assertEquals(fills.first(), it, 1e-3f) }
        assertEquals(
            "the subject must span the documented share of the frame",
            DEMO_FRAMING_FILL, fills.first(), 0.02f,
        )
    }

    @Test
    fun theRadiusScalesLinearlyWithTheSubject() {
        val small = fitOrbitRadius(0.5f, 0.5f, 0.5f, phonePortrait)
        val large = fitOrbitRadius(1f, 1f, 1f, phonePortrait)
        assertEquals(2f, large / small, 1e-4f)
    }

    @Test
    fun aWiderViewportBringsTheCameraCloser() {
        // The aspect-blindness the issue is about: the same subject genuinely needs less distance
        // on a tablet or a landscape fold than on a portrait phone.
        val portrait = fitOrbitRadius(0.5f, 0.5f, 0.5f, 0.4455f)
        val tablet = fitOrbitRadius(0.5f, 0.5f, 0.5f, 0.75f)
        val landscape = fitOrbitRadius(0.5f, 0.5f, 0.5f, 2.17f)
        assertTrue("portrait must be furthest — $portrait vs $tablet", portrait > tablet)
        assertTrue("landscape must be closest — $landscape vs $tablet", landscape < tablet)
    }

    // ── The demos this PR re-framed ───────────────────────────────────────────────────────────

    @Test
    fun theDemosThatInheritedTheStockPoseNowFillTheFrame() {
        // LightingLab's first section and SecondaryCamera's main view both passed NO manipulator,
        // so they inherited the library's 2.78 m stock pose for a helmet normalised to 0.5 units.
        // Their siblings, which do pass a radius, framed the same helmet visibly larger.
        val stockPose = 2.7789f
        val before = widthFill(0.5f, stockPose)
        val after = widthFill(0.5f, fitOrbitRadius(0.5f, 0.5f, 0.5f, phonePortrait,
            elevationDegrees = DEFAULT_ORBIT_ELEVATION_DEGREES))
        assertTrue("the stock pose really was undersized — $before", before < 0.7f)
        assertTrue("the fit must materially enlarge the subject — $before → $after", after > before * 1.25f)
        assertTrue("…without overflowing the frame", after <= 1f)
    }

    @Test
    fun theGallerysFixedRadiusCouldNotServeItsOwnSlugs() {
        // The gallery's slugs are normalised from 0.20 to 0.85 units and every one of them was
        // framed from a flat 1.6 m. One overflowed the frame, another filled a sixth of it.
        val legacy = 1.6f
        assertTrue("the 0.85 slug overflowed at 1.6 m", widthFill(0.85f, legacy) > 1f)
        assertTrue(
            "the same shot must have varied by more than 4× across the gallery's own chips",
            widthFill(0.85f, legacy) / widthFill(0.20f, legacy) > 4f,
        )
        // Fitted per chip, both land on the same share of the frame.
        val big = widthFill(0.85f, fitOrbitRadius(0.85f, 0.85f, 0.85f, phonePortrait))
        val small = widthFill(0.20f, fitOrbitRadius(0.20f, 0.20f, 0.20f, phonePortrait))
        assertEquals(big, small, 1e-3f)
        assertTrue(big <= 1f)
    }

    @Test
    fun theGestureSceneFramesTheGizmoNotJustTheHelmet() {
        // The node-gestures section is about touching the model, so the axes gizmo — the widest
        // thing on screen — is what must fit, not the 0.3-unit helmet inside it.
        val gizmoExtent = 0.3f / 2f + 0.3f * 1.5f
        val radius = fitOrbitRadius(gizmoExtent, gizmoExtent, gizmoExtent, phonePortrait,
            elevationDegrees = DEFAULT_ORBIT_ELEVATION_DEGREES)
        assertTrue("the gizmo must fit the frame", widthFill(gizmoExtent, radius) <= 1f)
        assertTrue("…and must not shrink back towards the stock pose", radius < 2.7789f)
    }

    // ── Degenerate inputs ─────────────────────────────────────────────────────────────────────

    @Test
    fun aDegenerateSubjectFallsBackRatherThanPlacingTheCameraOnIt() {
        assertEquals(2.78f, fitOrbitRadius(0f, 0f, 0f, phonePortrait), 1e-2f)
        assertTrue(fitOrbitRadius(Float.NaN, 1f, 1f, phonePortrait).isFinite())
        assertTrue(fitOrbitRadius(1f, 1f, 1f, Float.NaN).isFinite())
        assertTrue(fitOrbitRadius(1f, 1f, 1f, 0f).isFinite())
        assertTrue(fitOrbitRadius(1f, 1f, 1f, phonePortrait, fill = 0f) > 0f)
    }
}
