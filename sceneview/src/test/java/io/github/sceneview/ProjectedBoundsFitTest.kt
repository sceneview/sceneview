package io.github.sceneview

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Test

/**
 * Containment and tightness pins for the per-FOV-axis camera fit (#3426 — the Android half of the
 * iOS #3383 / PR #3395 fix).
 *
 * These are not "pin whatever the code returns" tests: every expectation is derived from an
 * **independent** frustum implementation ([framesEveryCorner]) that places a camera at the fitted
 * distance and checks the subject's eight corners against both FOV planes. The fit can therefore
 * fail the code rather than merely record it.
 *
 * The companion [CameraFramingTest] keeps the scale / padding / degenerate-input contracts.
 */
class ProjectedBoundsFitTest {

    private fun box(x: Float, y: Float, z: Float) =
        Aabb(halfExtent = Position(x / 2f, y / 2f, z / 2f))

    /** Viewport aspects the demos actually ship on: phone portrait, square, landscape tablet. */
    private val aspects = listOf(0.4455, 1.0, 2.17)

    // ── An independent frustum oracle ──────────────────────────────────────────────────────────

    /**
     * `true` when every corner of [bounds], swept to [azimuthDegrees], is inside the frustum of a
     * camera sitting [distance] away at [elevationDegrees], with [slack] world units of tolerance.
     *
     * Written from the frustum definition, not from the production formula: a point is inside iff
     * its depth along the view axis covers its lateral offset scaled by the axis's half-FOV
     * tangent.
     */
    private fun framesEveryCorner(
        bounds: Aabb,
        distance: Float,
        verticalFovDegrees: Double,
        aspect: Double,
        elevationDegrees: Double,
        azimuthDegrees: Double,
        slack: Float = 1e-3f,
    ): Boolean {
        val tanY = tan(Math.toRadians(verticalFovDegrees) / 2.0)
        val tanX = tanY * aspect
        val el = Math.toRadians(elevationDegrees)
        val az = Math.toRadians(azimuthDegrees)
        // Camera basis for an eye at `distance` along (back), orbiting the origin.
        val back = doubleArrayOf(sin(az) * cos(el), sin(el), cos(az) * cos(el))
        val right = doubleArrayOf(cos(az), 0.0, -sin(az))
        val up = doubleArrayOf(
            back[1] * right[2] - back[2] * right[1],
            back[2] * right[0] - back[0] * right[2],
            back[0] * right[1] - back[1] * right[0],
        )
        val h = bounds.halfExtent
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val v = doubleArrayOf(
                (sx * h.x).toDouble(), (sy * h.y).toDouble(), (sz * h.z).toDouble()
            )
            val depth = distance - (v[0] * back[0] + v[1] * back[1] + v[2] * back[2])
            val lateral = abs(v[0] * right[0] + v[1] * right[1] + v[2] * right[2])
            val vertical = abs(v[0] * up[0] + v[1] * up[1] + v[2] * up[2])
            if (depth * tanX + slack < lateral) return false
            if (depth * tanY + slack < vertical) return false
        }
        return true
    }

    /** The pre-#3426 bounding-sphere fit, recomputed inline so the comparison is honest. */
    private fun legacySphereFit(
        bounds: Aabb,
        verticalFovDegrees: Double,
        aspect: Double,
        padding: Float,
    ): Float {
        val e = bounds.extents
        val radius = 0.5f * sqrt(e.x * e.x + e.y * e.y + e.z * e.z) * (1f + padding)
        val halfV = Math.toRadians(verticalFovDegrees) / 2.0
        val halfH = kotlin.math.atan(tan(halfV) * aspect)
        return maxOf(radius / sin(halfV).toFloat(), radius / sin(halfH).toFloat())
    }

    // ── Containment ───────────────────────────────────────────────────────────────────────────

    @Test
    fun fittedSubjectNeverClipsAtAnyAzimuth() {
        // The whole point of framing the Y-sweep: an auto-rotating model must stay inside the
        // frame as it turns broadside. 24 azimuths × 3 aspects × 3 elevations × 4 subjects.
        val subjects = listOf(
            box(1f, 1f, 1f),          // cubic
            box(4f, 0.5f, 0.5f),      // wide and flat
            box(0.3f, 3f, 0.3f),      // tall column
            box(0.05f, 0.05f, 0.05f), // a 5 cm bee
        )
        for (subject in subjects) for (aspect in aspects) for (elevation in listOf(0.0, 12.0, 45.0)) {
            val d = fitDistanceForBounds(subject, 46.4, aspect, padding = 0f, elevationDegrees = elevation)
            assertTrue("fit must be positive", d > 0f)
            for (step in 0 until 24) {
                assertTrue(
                    "subject $subject clipped at azimuth ${step * 15}° " +
                        "(aspect $aspect, elevation $elevation, d=$d)",
                    framesEveryCorner(subject, d, 46.4, aspect, elevation, step * 15.0),
                )
            }
        }
    }

    @Test
    fun theFitIsTightNotMerelySafe() {
        // A fit that always doubled the distance would pass the containment test above and still
        // be the bug this issue is about. Tightness has to be judged at the **binding azimuth**,
        // not at azimuth 0: the fit frames the subject's sweep, so a box is deliberately loose
        // when it happens to be facing the camera square-on. Somewhere on the turntable, shrinking
        // the distance by 2 % must clip.
        val subjects = listOf(box(1f, 1f, 1f), box(0.3f, 3f, 0.3f), box(4f, 0.5f, 0.5f))
        for (subject in subjects) for (aspect in aspects) {
            val d = fitDistanceForBounds(subject, 46.4, aspect, padding = 0f, elevationDegrees = 0.0)
            val clipsSomewhere = (0 until 360).any { azimuth ->
                !framesEveryCorner(subject, d * 0.98f, 46.4, aspect, 0.0, azimuth.toDouble(), slack = 0f)
            }
            assertTrue(
                "fit of $subject at aspect $aspect is loose — $d leaves room to spare at every yaw",
                clipsSomewhere,
            )
        }
    }

    // ── The regression the issue reports ──────────────────────────────────────────────────────

    @Test
    fun aTallSubjectFillsAPortraitViewport() {
        // #3426's headline. A 3 m column, 46.4° vertical FOV: its half-height of 1.5 m needs
        // exactly 1.5 / tan(23.2°) ≈ 3.50 m of distance and NOTHING more, because it is bound by
        // its height. The sphere fit charged it the *horizontal* axis's distance on a portrait
        // viewport and pushed it more than twice as far.
        val column = box(0.3f, 3f, 0.3f)
        // Its half-height over the vertical half-FOV tangent, plus the swept radius of its own
        // 0.3 × 0.3 footprint — the near face is that much closer to the lens. Nothing else.
        val exact = 1.5f / tan(Math.toRadians(46.4) / 2.0).toFloat() + sqrt(2f) * 0.15f
        val fitted = fitDistanceForBounds(column, 46.4, aspect = 0.4455, padding = 0f)
        assertEquals("a height-bound subject must pay only its height", exact, fitted, 0.01f)

        val legacy = legacySphereFit(column, 46.4, 0.4455, 0f)
        assertTrue(
            "the sphere fit must have been materially worse — was $legacy, now $fitted",
            legacy > fitted * 1.8f,
        )
    }

    @Test
    fun aVerticallyBoundSubjectCostsTheSameOnEveryViewport() {
        // Its width never binds, so widening the viewport must not change the distance at all.
        val column = box(0.3f, 3f, 0.3f)
        val distances = aspects.map { fitDistanceForBounds(column, 46.4, it, padding = 0f) }
        distances.forEach { assertEquals(distances.first(), it, 1e-3f) }
    }

    @Test
    fun theNewFitIsNeverFurtherThanTheOldOne() {
        // The guarantee that lets this ship without re-tuning every caller: no scene is framed
        // further away than it was.
        val subjects = listOf(
            box(1f, 1f, 1f), box(4f, 0.5f, 0.5f), box(0.3f, 3f, 0.3f),
            box(0.5f, 0.2f, 0.9f), box(155f, 90f, 40f),
        )
        for (subject in subjects) for (aspect in aspects) for (elevation in listOf(0.0, 8.27, 30.0)) {
            val fitted = fitDistanceForBounds(subject, 46.4, aspect, 0.15f, elevation)
            val legacy = legacySphereFit(subject, 46.4, aspect, 0.15f)
            assertTrue(
                "$subject at aspect $aspect elevation $elevation: $fitted > $legacy",
                fitted <= legacy + 1e-3f,
            )
        }
    }

    // ── Azimuth invariance, and the switch that trades it away ────────────────────────────────

    @Test
    fun theSweptFitContainsEveryAzimuthAndIsBindingOnAtLeastOne() {
        // Nothing in the swept formula reads azimuth, so this is really a statement about the
        // ORACLE: the fitted distance contains every yaw, and is *exactly* right on the worst one.
        // For a 2 × 0.4 footprint the binding yaw is not 0° but the corner direction
        // (atan(0.2 / 1.0) ≈ 11°), which is precisely why a per-pose fit would clip an
        // auto-rotating model.
        val plate = box(2f, 0.1f, 0.4f)
        val d = fitDistanceForBounds(plate, 46.4, aspect = 1.0, padding = 0f)
        for (azimuth in 0 until 360) {
            assertTrue(
                "clipped at azimuth $azimuth°",
                framesEveryCorner(plate, d, 46.4, 1.0, 0.0, azimuth.toDouble()),
            )
        }
        assertTrue(
            "no azimuth binds — the fit is loose everywhere",
            (0 until 360).any {
                !framesEveryCorner(plate, d * 0.98f, 46.4, 1.0, 0.0, it.toDouble(), slack = 0f)
            },
        )
    }

    @Test
    fun theNonInvariantFitIsTighterAndNamesItsTrade() {
        // A static, head-on scene should not pay for a rotation it never performs.
        val deepPlate = box(0.5f, 0.5f, 4f)
        val swept = fitDistanceForBounds(deepPlate, 46.4, 1.0, 0f, 0.0, azimuthInvariant = true)
        val fixed = fitDistanceForBounds(deepPlate, 46.4, 1.0, 0f, 0.0, azimuthInvariant = false)
        assertTrue("the fixed-azimuth fit must be closer — $fixed vs $swept", fixed < swept)
        assertTrue(framesEveryCorner(deepPlate, fixed, 46.4, 1.0, 0.0, 0.0))
        // …and it deliberately does NOT survive a quarter turn: what was 4 m of depth becomes 4 m
        // of width. That is the trade `azimuthInvariant = false` names, held here so nobody makes
        // it the default.
        assertTrue(
            "the fixed-azimuth fit is only valid head-on",
            !framesEveryCorner(deepPlate, fixed, 46.4, 1.0, 0.0, 90.0, slack = 0f),
        )
        assertTrue(framesEveryCorner(deepPlate, swept, 46.4, 1.0, 0.0, 90.0))
    }

    // ── Elevation ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun elevationIsHonouredAndSurvivesThePoles() {
        val subject = box(1f, 1f, 1f)
        for (elevation in listOf(-90.0, -89.9, 0.0, 45.0, 89.9, 90.0)) {
            val d = fitDistanceForBounds(subject, 46.4, 0.4455, padding = 0f, elevationDegrees = elevation)
            assertTrue("elevation $elevation produced $d", d.isFinite() && d > 0f)
            assertTrue(
                "clipped at elevation $elevation",
                framesEveryCorner(subject, d, 46.4, 0.4455, elevation, 0.0),
            )
        }
    }

    @Test
    fun lookDirectionIsTranslatedIntoElevation() {
        // `frameToBounds` derives the fit's elevation from its look direction; a camera above the
        // subject looks DOWN, so the direction's y is negative and the elevation positive.
        assertEquals(0.0, elevationDegreesForDirection(Position(0f, 0f, -1f)), 1e-6)
        assertEquals(90.0, elevationDegreesForDirection(Position(0f, -1f, 0f)), 1e-6)
        assertEquals(-90.0, elevationDegreesForDirection(Position(0f, 1f, 0f)), 1e-6)
        assertEquals(45.0, elevationDegreesForDirection(Position(0f, -1f, -1f)), 1e-4)
        // Degenerate input falls back rather than producing NaN.
        assertEquals(
            DEFAULT_FRAMING_ELEVATION_DEGREES,
            elevationDegreesForDirection(Position(0f, 0f, 0f)),
            1e-9,
        )
    }
}
