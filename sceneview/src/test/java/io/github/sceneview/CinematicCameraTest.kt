package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Pure-JVM pins for the cinematic turntable maths ([CinematicCamera.kt]) — the eased orbit that
 * drives `SceneView`'s "hero shot" camera. No renderer is involved; this locks the azimuth ramp,
 * the elevation bob, the orbit geometry and the framing-distance relation.
 */
class CinematicCameraTest {

    private val twoPi = (2.0 * PI).toFloat()

    // ── cinematicAzimuth ─────────────────────────────────────────────────────────

    @Test
    fun azimuthStartsAtZero() {
        assertEquals(0f, cinematicAzimuth(0f, CinematicCameraProfile.HeroProduct), 1e-6f)
    }

    @Test
    fun azimuthReachesOneRevolutionPerPeriodAtConstantSpeed() {
        // NeutralWeb has no ease-in: after secondsPerRevolution it has travelled exactly 2π.
        val p = CinematicCameraProfile.NeutralWeb
        assertEquals(twoPi, cinematicAzimuth(p.secondsPerRevolution, p), 1e-4f)
        assertEquals(twoPi / 2f, cinematicAzimuth(p.secondsPerRevolution / 2f, p), 1e-4f)
    }

    @Test
    fun azimuthIsMonotonicallyIncreasing() {
        val p = CinematicCameraProfile.HeroProduct
        var prev = -1f
        var t = 0f
        while (t <= 12f) {
            val a = cinematicAzimuth(t, p)
            assertTrue("azimuth must not decrease at t=$t", a >= prev)
            prev = a
            t += 0.1f
        }
    }

    @Test
    fun azimuthEaseInIsContinuousAtTheRampBoundary() {
        // Across t = easeInSeconds the ramp and cruise formulas must meet with no speed jump:
        // over a 2·δ window straddling the boundary the camera should travel exactly the
        // cruise-speed distance (the ramp has reached cruise speed precisely at the boundary).
        val p = CinematicCameraProfile.HeroProduct
        val e = p.easeInSeconds
        val delta = 1e-3f
        val cruiseSpeed = twoPi / p.secondsPerRevolution
        val travelled = cinematicAzimuth(e + delta, p) - cinematicAzimuth(e - delta, p)
        assertEquals(cruiseSpeed * 2f * delta, travelled, 1e-5f)
    }

    @Test
    fun azimuthEaseInTravelsLessThanConstantSpeedWouldDuringRamp() {
        // During the ramp the camera is slower than cruise — it has covered strictly less ground
        // than a no-ease orbit of the same period would have.
        val p = CinematicCameraProfile.HeroProduct
        val cruiseSpeed = twoPi / p.secondsPerRevolution
        val tMid = p.easeInSeconds / 2f
        assertTrue(cinematicAzimuth(tMid, p) < cruiseSpeed * tMid)
    }

    // ── cinematicElevation ───────────────────────────────────────────────────────

    @Test
    fun elevationWithoutBobIsConstant() {
        val p = CinematicCameraProfile.HeroProduct // elevationBobDegrees = 0
        val expected = (p.elevationDegrees * PI / 180.0).toFloat()
        assertEquals(expected, cinematicElevation(0f, p), 1e-6f)
        assertEquals(expected, cinematicElevation(5f, p), 1e-6f)
    }

    @Test
    fun elevationBobStartsAtBaseAndPeaksAtAQuarterCycle() {
        val p = CinematicCameraProfile.SlowCinematic // has a bob
        val base = (p.elevationDegrees * PI / 180.0).toFloat()
        assertEquals(base, cinematicElevation(0f, p), 1e-6f)
        // sin peaks at a quarter period → base + full amplitude.
        val peak = ((p.elevationDegrees + p.elevationBobDegrees) * PI / 180.0).toFloat()
        assertEquals(peak, cinematicElevation(p.bobSecondsPerCycle / 4f, p), 1e-5f)
    }

    @Test
    fun elevationIsClampedShortOfThePoles() {
        // A custom profile whose base + bob would cross +90° must stay short of the pole, or the
        // orbit basis degenerates and the camera tips over the subject.
        val steep = CinematicCameraProfile.SlowCinematic.copy(
            elevationDegrees = 85f,
            elevationBobDegrees = 20f
        )
        val limitRad = 89f * (PI / 180.0).toFloat()
        for (t in listOf(0f, 2f, 3f, 6f, 9f, 12f, 15f)) {
            val e = cinematicElevation(t, steep)
            assertTrue("elevation at t=$t must stay within the pole limit", e <= limitRad + 1e-4f)
        }
    }

    // ── cinematicCameraEye ───────────────────────────────────────────────────────

    @Test
    fun eyeIsAlwaysAtTheOrbitRadiusFromTheOrigin() {
        val p = CinematicCameraProfile.SlowCinematic
        for (t in listOf(0f, 1f, 4.5f, 9f, 13.7f)) {
            val eye = cinematicCameraEye(t, p, distance = 3f)
            val r = sqrt(eye.x * eye.x + eye.y * eye.y + eye.z * eye.z)
            assertEquals("radius at t=$t", 3f, r, 1e-4f)
        }
    }

    @Test
    fun eyeStartsInFrontOfAndAboveTheSubject() {
        // At t = 0 azimuth is 0 → camera sits on +z, lifted by the elevation, centred on x.
        val eye = cinematicCameraEye(0f, CinematicCameraProfile.HeroProduct, distance = 4f)
        assertEquals(0f, eye.x, 1e-5f)
        assertTrue("camera in front (+z)", eye.z > 0f)
        assertTrue("camera above the horizon", eye.y > 0f)
    }

    // ── cinematicDistance ────────────────────────────────────────────────────────

    @Test
    fun distanceForA90DegreeLensEqualsRadiusTimesMargin() {
        // tan(45°) = 1, so distance reduces to contentRadius · frameMargin.
        val p = CinematicCameraProfile.NeutralWeb.copy(fovDegrees = 90f, frameMargin = 1f)
        assertEquals(2f, cinematicDistance(contentRadius = 2f, profile = p), 1e-4f)
    }

    @Test
    fun distanceScalesLinearlyWithContentRadius() {
        val p = CinematicCameraProfile.HeroProduct
        assertEquals(
            2f * cinematicDistance(1f, p),
            cinematicDistance(2f, p),
            1e-4f
        )
    }

    @Test
    fun narrowerLensPushesTheCameraFurtherBack() {
        // Hero (30° FOV) is a longer lens than NeutralWeb (45°) → larger framing distance for the
        // same subject, ignoring frame margin.
        val hero = CinematicCameraProfile.HeroProduct.copy(frameMargin = 1f)
        val web = CinematicCameraProfile.NeutralWeb.copy(frameMargin = 1f)
        assertTrue(cinematicDistance(1f, hero) > cinematicDistance(1f, web))
    }
}
