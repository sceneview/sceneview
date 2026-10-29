package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** While a scan is saved (#4719): the view leaves the phone for a slow orbit round the whole cloud. */
class ScanSavingOrbitTest {
    private val phone = OrbitPose(
        target = Position(1f, 1.4f, 2f),
        azimuthDegrees = 70f,
        elevationDegrees = ScanFollow.ELEVATION_DEGREES,
        distance = ScanFollow.DISTANCE_M,
    )
    private val centre = Position(0f, 1f, 0f)

    /** A cloud round [centre] whose highest point is 1 m up. */
    private val points = floatArrayOf(-2f, 0f, -2f, 2f, 1f, 2f, -2f, 0.5f, 2f, 2f, 0.5f, -2f)

    private fun path(radius: Float = 3f) = ScanSavingPath(phone, points, centre, radius, verticalFovDegrees = 45.0)

    private val settled = ScanSavingPath.RISE_SECONDS + ScanSavingPath.OUT_SECONDS

    @Test
    fun `the orbit starts on the follow pose, with no cut`() {
        assertEquals(phone, path().poseAt(0f))
    }

    @Test
    fun `once on it, the orbit frames the whole cloud round its centre from above`() {
        val on = path().poseAt(settled + 1f)
        assertEquals(centre, on.target)
        assertEquals(ScanSavingPath.ELEVATION_DEGREES, on.elevationDegrees, 1e-4f)
        // 3 m of reach in half a 45° view, at 85 % of it: about 9.2 m out.
        assertEquals(9.22f, on.distance, 0.05f)
        // A small cloud is never looked at from closer than the live view.
        assertEquals(ScanFollow.DISTANCE_M, path(radius = 0.2f).poseAt(settled + 1f).distance, 1e-4f)
    }

    @Test
    fun `it holds the heading while it rises, then turns slowly and one way`() {
        val path = path()
        var previous = phone.azimuthDegrees
        var t = 0.05f
        while (t < 10f) {
            val pose = path.poseAt(t)
            if (t <= ScanSavingPath.RISE_SECONDS) {
                assertEquals(phone.azimuthDegrees, pose.azimuthDegrees, 1e-3f)
            } else {
                // Easing into the turn above the walls, then on at its pace.
                assertTrue("at $t s", pose.azimuthDegrees >= previous - 1e-4f)
                if (t > settled) assertTrue("at $t s", pose.azimuthDegrees > previous)
            }
            previous = pose.azimuthDegrees
            t += 0.05f
        }
        val turned = 10f - ScanSavingPath.RISE_SECONDS - ScanSavingPath.OUT_SECONDS / 2f
        val expected = phone.azimuthDegrees + ScanSavingPath.DEGREES_PER_SECOND * turned
        assertEquals(expected, path.poseAt(10f).azimuthDegrees, 0.05f)
    }

    @Test
    fun `the cloud's reach ignores a few stray far points`() {
        val points = FloatArray(3 * 1_000) { i ->
            val p = i / 3
            when (i % 3) {
                0 -> if (p < 10) 40f else (p % 7 - 3).toFloat()
                1 -> 1f
                else -> 0f
            }
        }
        assertEquals(3f, ScanFollow.cloudRadius(points, Position(0f, 1f, 0f)), 1e-4f)
        assertEquals(0f, ScanFollow.cloudRadius(FloatArray(0), centre), 0f)
    }

    @Test
    fun `the saving card says what it fuses`() {
        assertEquals("Fusing 261k points…", ScanCopy.fusing(261_400))
        assertEquals("Fusing 1 point…", ScanCopy.fusing(1))
        assertEquals(ScanCopy.SAVING, ScanCopy.fusing(0))
    }
}
