package io.github.sceneview

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * [fitCameraToBounds] — an arbitrary box framed under a given angle.
 *
 * The oracle is independent of the production code: it rebuilds a look-at camera from the returned
 * eye and target alone, projects the eight corners, and measures the picture. A fit is right when
 * the picture touches the requested fill on the axis that binds, stays inside it on the other,
 * and is centred on both.
 */
class CameraFitToBoundsTest {

    /** Projected extent of a box, in half-view units: `±1` are the edges of the view. */
    private class Picture(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
        val halfWidth get() = (maxX - minX) / 2.0
        val halfHeight get() = (maxY - minY) / 2.0
        val centreX get() = (maxX + minX) / 2.0
        val centreY get() = (maxY + minY) / 2.0
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
    )

    private fun normalized(a: DoubleArray): DoubleArray {
        val length = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        return doubleArrayOf(a[0] / length, a[1] / length, a[2] / length)
    }

    /** Projects [bounds] through a look-at camera standing at [fit]. */
    private fun picture(bounds: Aabb, fit: CameraFit, fovDegrees: Double, aspect: Double): Picture {
        val forward = normalized(
            doubleArrayOf(
                (fit.target.x - fit.eye.x).toDouble(),
                (fit.target.y - fit.eye.y).toDouble(),
                (fit.target.z - fit.eye.z).toDouble()
            )
        )
        var right = cross(forward, doubleArrayOf(0.0, 1.0, 0.0))
        right = if (abs(right[0]) + abs(right[2]) < 1e-6) doubleArrayOf(1.0, 0.0, 0.0) else normalized(right)
        val up = cross(right, forward)
        val tanY = tan(Math.toRadians(fovDegrees) / 2.0)
        val tanX = tanY * aspect
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until 8) {
            val v = doubleArrayOf(
                (if (i and 1 == 0) bounds.min.x else bounds.max.x).toDouble() - fit.eye.x,
                (if (i and 2 == 0) bounds.min.y else bounds.max.y).toDouble() - fit.eye.y,
                (if (i and 4 == 0) bounds.min.z else bounds.max.z).toDouble() - fit.eye.z
            )
            val depth = v[0] * forward[0] + v[1] * forward[1] + v[2] * forward[2]
            assertTrue("a corner is behind the camera", depth > 0.0)
            val x = (v[0] * right[0] + v[1] * right[1] + v[2] * right[2]) / (depth * tanX)
            val y = (v[0] * up[0] + v[1] * up[1] + v[2] * up[2]) / (depth * tanY)
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
        return Picture(minX, maxX, minY, maxY)
    }

    /** Look direction (camera → subject) for an orbit at [azimuth] / [elevation] degrees. */
    private fun direction(azimuth: Double, elevation: Double): Position {
        val az = Math.toRadians(azimuth)
        val el = Math.toRadians(elevation)
        return Position(
            (-sin(az) * cos(el)).toFloat(), (-sin(el)).toFloat(), (-cos(az) * cos(el)).toFloat()
        )
    }

    private val fov = 46.4
    private val aspects = listOf(0.4455, 0.81, 1.0, 2.17)
    private val subjects = listOf(
        // The rolling-balls tray: wide, flat, below the origin.
        Aabb(center = Position(0f, -0.493f, 0f), halfExtent = Position(0.885f, 0.097f, 0.885f)),
        // A tall, off-centre figure.
        Aabb(center = Position(1.5f, 0.9f, -2f), halfExtent = Position(0.3f, 0.9f, 0.2f)),
        // A long vehicle.
        Aabb(center = Position(0f, 0.7f, 0f), halfExtent = Position(0.9f, 0.7f, 2.3f)),
    )

    @Test
    fun theBoxTouchesTheFillOnTheBindingAxisAndIsCentredOnBoth() {
        for (bounds in subjects) for (aspect in aspects)
            for (azimuth in listOf(0.0, 30.0, 135.0, -70.0)) for (elevation in listOf(0.0, 25.0, 40.0, 75.0)) {
                val fit = fitCameraToBounds(
                    bounds, direction(azimuth, elevation), fov, aspect, widthFill = 0.92f, heightFill = 0.84f
                )
                val case = "bounds=$bounds aspect=$aspect az=$azimuth el=$elevation"
                assertNotNull(case, fit)
                val p = picture(bounds, fit!!, fov, aspect)
                assertEquals("centred in x — $case", 0.0, p.centreX, 2e-3)
                assertEquals("centred in y — $case", 0.0, p.centreY, 2e-3)
                assertTrue("width ${p.halfWidth} — $case", p.halfWidth <= 0.92 + 2e-3)
                assertTrue("height ${p.halfHeight} — $case", p.halfHeight <= 0.84 + 2e-3)
                val slack = min(0.92 - p.halfWidth, 0.84 - p.halfHeight)
                assertEquals("one axis binds — $case", 0.0, slack, 2e-3)
            }
    }

    // The `rolling-balls` opening shot (#4180), which this fit replaced a demo-only solver for:
    // its expectations moved here with it, on the table's real numbers.

    private val table = Aabb(
        center = Position(0f, -0.493f, 0f), halfExtent = Position(0.885f, 0.097f, 0.885f)
    )

    private fun tablePicture(aspect: Double): Picture {
        val fit = fitCameraToBounds(table, direction(0.0, 40.0), fov, aspect, 0.92f, 0.84f)!!
        return picture(table, fit, fov, aspect)
    }

    @Test
    fun aPortraitTableSpansTheRequestedWidthAndIsCentred() {
        val p = tablePicture(0.81)
        assertEquals(0.92, p.halfWidth, 0.005)
        assertTrue("height ${p.halfHeight}", p.halfHeight <= 0.841)
        assertEquals(0.0, p.centreY, 0.01)
    }

    @Test
    fun aLandscapeTableIsBoundByTheHeight() {
        val p = tablePicture(2.4)
        assertEquals(0.84, p.halfHeight, 0.005)
        assertTrue("width ${p.halfWidth}", p.halfWidth <= 0.921)
        assertEquals(0.0, p.centreY, 0.01)
    }

    @Test
    fun theCameraLooksAlongTheRequestedDirection() {
        val bounds = subjects[0]
        val fit = fitCameraToBounds(bounds, direction(0.0, 40.0), fov, 0.81)!!
        val d = fit.eye - fit.target
        assertEquals(40.0, Math.toDegrees(atan2(d.y.toDouble(), d.z.toDouble())), 0.01)
        assertEquals(0f, fit.eye.x, 1e-4f)
    }

    @Test
    fun theTargetSitsAtTheDepthOfTheBoxCentre() {
        // The orbit pivot: on the optical axis, level with the subject — not in front of or
        // behind it, so an orbit turns around the box.
        for (bounds in subjects) {
            val look = direction(30.0, 25.0)
            val fit = fitCameraToBounds(bounds, look, fov, 1.0)!!
            val toCentre = bounds.center - fit.target
            val alongView = toCentre.x * look.x + toCentre.y * look.y + toCentre.z * look.z
            assertEquals(0f, alongView, 1e-3f)
        }
    }

    @Test
    fun straightDownIsFramedToo() {
        val bounds = subjects[0]
        val fit = fitCameraToBounds(bounds, Position(0f, -1f, 0f), fov, 0.81, 0.9f, 0.9f)
        assertNotNull(fit)
        assertTrue(fit!!.eye.y > bounds.max.y)
        assertEquals(bounds.center.x, fit.eye.x, 1e-4f)
        assertEquals(bounds.center.z, fit.eye.z, 1e-4f)
    }

    @Test
    fun aRelativelyWiderVisibleAreaBringsAWidthBoundSubjectCloser() {
        // What `contentPadding` changes for the fit: only the aspect ratio. A portrait surface
        // whose bottom half is covered is a relatively wider area, so a tray that was bound by
        // the width can be framed from closer — this is the size the pose refit wins back.
        val bounds = subjects[0]
        val look = direction(0.0, 40.0)
        val full = fitCameraToBounds(bounds, look, fov, 1080.0 / 2400.0)!!
        val halfCovered = fitCameraToBounds(bounds, look, fov, 1080.0 / 1200.0)!!
        assertTrue(halfCovered.distance < full.distance)
    }

    @Test
    fun theDefaultFillLeavesATenthOnEachSide() {
        val bounds = subjects[2]
        val fit = fitCameraToBounds(bounds, verticalFovDegrees = fov, aspect = 1.0)!!
        val p = picture(bounds, fit, fov, 1.0)
        assertEquals(DEFAULT_FRAMING_FILL.toDouble(), max(p.halfWidth, p.halfHeight), 2e-3)
    }

    @Test
    fun aFlatBoxIsFramedOnItsOnlyExtent() {
        val card = Aabb(halfExtent = Position(0.5f, 0.25f, 0f))
        val fit = fitCameraToBounds(card, verticalFovDegrees = fov, aspect = 1.0, widthFill = 1f, heightFill = 1f)!!
        val p = picture(card, fit, fov, 1.0)
        assertEquals(1.0, p.halfWidth, 2e-3)
        assertEquals(0.5, p.halfHeight, 2e-3)
    }

    @Test
    fun nothingToFrameIsNull() {
        assertNull(fitCameraToBounds(Aabb(), verticalFovDegrees = fov, aspect = 1.0))
        val broken = Aabb(halfExtent = Position(Float.POSITIVE_INFINITY, 1f, 1f))
        assertNull(fitCameraToBounds(broken, verticalFovDegrees = fov, aspect = 1.0))
    }
}
