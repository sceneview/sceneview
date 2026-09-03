package io.github.sceneview

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Pure-JVM pins for the auto-fit camera-framing math used by `SceneView`'s library-level
 * `autoFitContent` feature ([CameraFraming.kt], #1439).
 *
 * The Filament / Compose plumbing (the camera reposition, the frame-loop hook) is exercised on
 * device; this suite locks the pure trigonometry that decides *how far* the camera must sit so a
 * model — of any intrinsic size — fills the viewport.
 */
class CameraFramingTest {

    private fun box(extentX: Float, extentY: Float, extentZ: Float, center: Position = Position()) =
        Aabb(center = center, halfExtent = Position(extentX / 2f, extentY / 2f, extentZ / 2f))

    // ── verticalFovDegreesForFocalLength ──────────────────────────────────────────────────────

    @Test
    fun focalLength28mmGivesAround46DegreesVerticalFov() {
        // 28 mm on a full-frame 24 mm sensor → vfov = 2·atan(24/(2·28)) ≈ 46.4°.
        val vfov = verticalFovDegreesForFocalLength(28.0)
        assertEquals(46.4, vfov, 0.3)
    }

    @Test
    fun longerLensGivesNarrowerFov() {
        assertTrue(
            "an 85 mm lens must be narrower than a 28 mm lens",
            verticalFovDegreesForFocalLength(85.0) < verticalFovDegreesForFocalLength(28.0)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroFocalLengthIsRejected() {
        verticalFovDegreesForFocalLength(0.0)
    }

    // ── fitDistanceForBounds ──────────────────────────────────────────────────────────────────

    @Test
    fun emptyBoundsYieldZeroDistance() {
        assertEquals(0f, fitDistanceForBounds(Aabb(), verticalFovDegrees = 45.0, aspect = 1.0))
    }

    @Test
    fun distanceScalesLinearlyWithModelSize() {
        // A model twice as large must be framed twice as far away — the helper must hide the
        // model's intrinsic size, which is the whole point of #1439.
        val small = fitDistanceForBounds(box(1f, 1f, 1f), verticalFovDegrees = 45.0, aspect = 1.0)
        val large = fitDistanceForBounds(box(2f, 2f, 2f), verticalFovDegrees = 45.0, aspect = 1.0)
        assertTrue(small > 0f)
        assertEquals(2f, large / small, 1e-4f)
    }

    @Test
    fun tinyAndHugeModelsBothFrameToANonDegenerateDistance() {
        // 5 cm bee and 5 m crate — the tester's exact scenario. Both must produce a sane,
        // strictly positive distance proportional to their size.
        val bee = fitDistanceForBounds(box(0.05f, 0.05f, 0.05f), 46.4, aspect = 0.5)
        val crate = fitDistanceForBounds(box(5f, 5f, 5f), 46.4, aspect = 0.5)
        assertTrue("bee distance must be positive", bee > 0f)
        assertTrue("crate distance must be positive", crate > 0f)
        assertEquals("distance is proportional to size", 100f, crate / bee, 1e-3f)
    }

    @Test
    fun distanceMatchesClosedFormForSquareViewport() {
        // #3426 replaced the bounding-sphere fit with a per-FOV-axis one, so the closed form is no
        // longer `r / sin(vfov/2)`. On a square viewport at elevation 0 the binding axis is the
        // vertical one, whose support for a cube of side `e` is `e/2 · 1/tan(vfov/2) + R`, with
        // `R = hypot(e/2, e/2)` the radius of the cube's sweep about Y.
        val extent = 2f
        val half = extent / 2f
        val sweptRadius = sqrt(2f) * half
        val vfov = 60.0
        val expected = half / kotlin.math.tan(Math.toRadians(vfov / 2.0)).toFloat() + sweptRadius
        val actual = fitDistanceForBounds(
            box(extent, extent, extent), verticalFovDegrees = vfov, aspect = 1.0, padding = 0f
        )
        assertEquals(expected, actual, 1e-3f)
    }

    @Test
    fun paddingPushesTheCameraBack() {
        val tight = fitDistanceForBounds(box(1f, 1f, 1f), 45.0, aspect = 1.0, padding = 0f)
        val padded = fitDistanceForBounds(box(1f, 1f, 1f), 45.0, aspect = 1.0, padding = 0.15f)
        assertEquals(1.15f, padded / tight, 1e-4f)
    }

    @Test
    fun portraitViewportNeedsMoreDistanceThanLandscape() {
        // A tall, narrow (portrait) viewport has a tighter horizontal FOV, so the same model
        // must be framed further away than on a wide landscape viewport.
        val portrait = fitDistanceForBounds(box(1f, 1f, 1f), 46.4, aspect = 0.5)
        val landscape = fitDistanceForBounds(box(1f, 1f, 1f), 46.4, aspect = 2.0)
        assertTrue("portrait must require a larger distance", portrait > landscape)
    }

    @Test
    fun equalSpaceDiagonalsNoLongerMeanEqualFraming() {
        // #3426 — this used to assert that a wide flat plate and a cube of the same *space
        // diagonal* frame identically. They share a bounding sphere, which is exactly the
        // over-charge the issue is about: the cube is 1.16 m across and was billed for the plate's
        // 2 m reach. The plate keeps its distance (it really is 2 m wide, there is no slack to
        // recover); the cube comes closer.
        val plate = box(2f, 0.1f, 0.1f)
        val diagonal = sqrt(2f * 2f + 0.1f * 0.1f + 0.1f * 0.1f)
        val edge = diagonal / sqrt(3f)
        val cube = box(edge, edge, edge)
        val dPlate = fitDistanceForBounds(plate, 46.4, aspect = 1.0)
        val dCube = fitDistanceForBounds(cube, 46.4, aspect = 1.0)
        assertTrue(
            "the compact cube must no longer pay the plate's width — $dCube vs $dPlate",
            dCube < dPlate * 0.9f,
        )
    }

    @Test
    fun framingIsInvariantToTheSubjectsYaw() {
        // What must still hold after #3426: an orbiting camera never clips, because the fit frames
        // the subject's sweep about Y. Turning a subject 90° about Y swaps its X and Z extents and
        // must not change the distance by a hair.
        val subject = box(2f, 0.6f, 0.4f)
        val turned = box(0.4f, 0.6f, 2f)
        assertEquals(
            fitDistanceForBounds(subject, 46.4, aspect = 0.4455),
            fitDistanceForBounds(turned, 46.4, aspect = 0.4455),
            1e-4f,
        )
    }

    @Test
    fun nonFiniteAspectFallsBackToSquare() {
        val square = fitDistanceForBounds(box(1f, 1f, 1f), 46.4, aspect = 1.0)
        val nan = fitDistanceForBounds(box(1f, 1f, 1f), 46.4, aspect = Double.NaN)
        assertEquals(square, nan, 1e-4f)
    }

    @Test
    fun framedModelSubtendsTheExpectedAngle() {
        // Sanity check on the geometry. Post-#3426 the fit measures the subject's *projected*
        // extent, not its bounding sphere, so the angle to check is the one subtended by the
        // nearest top corner — half the height, at the depth of the near face.
        val extent = 1f
        val half = extent / 2f
        val vfov = 50.0
        val d = fitDistanceForBounds(box(extent, extent, extent), vfov, aspect = 1.0, padding = 0f)
        // Distance to the near face, which is where the swept silhouette is tallest on screen.
        val nearDepth = d - sqrt(2f) * half
        val subtendedHalfAngle = Math.toDegrees(atan((half / nearDepth).toDouble()))
        assertTrue(
            "the subject must fill the frame's height without exceeding it — was $subtendedHalfAngle°",
            subtendedHalfAngle in 20.0..(vfov / 2.0 + 1e-3),
        )
    }
}
