package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `rolling-balls` opening shot fits the table itself (#4180). */
class TrayFramingTest {

    private val min = Float3(-0.885f, -0.59f, -0.885f)
    private val max = Float3(0.885f, -0.396f, 0.885f)
    private val pitch = 40f
    private val fov = 46.4f
    private val tanHalf = tan(Math.toRadians(fov / 2.0)).toFloat()
    private val up = Float3(0f, cos(Math.toRadians(40.0)).toFloat(), -sin(Math.toRadians(40.0)).toFloat())

    private fun shotFor(aspect: Float) = TrayFraming.fit(min, max, aspect, pitch, fov, 0.92f, 0.84f)

    private fun boundsFor(aspect: Float) = TrayFraming.project(min, max, shotFor(aspect), up, tanHalf, aspect)

    @Test
    fun portraitTableSpansTheRequestedWidth() {
        val b = boundsFor(0.81f)
        assertEquals(0.92f, max(abs(b.minX), abs(b.maxX)), 0.005f)
        assertTrue("height ${b.maxY - b.minY}", (b.maxY - b.minY) / 2f <= 0.841f)
    }

    @Test
    fun portraitTableIsCentredVertically() {
        val b = boundsFor(0.81f)
        assertEquals(0f, (b.minY + b.maxY) / 2f, 0.01f)
    }

    @Test
    fun landscapeTableIsBoundByTheHeight() {
        val b = boundsFor(2.4f)
        assertEquals(0.84f, (b.maxY - b.minY) / 2f, 0.005f)
        assertTrue("width ${b.maxX}", max(abs(b.minX), abs(b.maxX)) <= 0.921f)
        assertEquals(0f, (b.minY + b.maxY) / 2f, 0.01f)
    }

    @Test
    fun theCameraLooksDownAtThePitch() {
        val shot = shotFor(0.81f)
        val d = Float3(shot.eye.x - shot.target.x, shot.eye.y - shot.target.y, shot.eye.z - shot.target.z)
        val elevation = Math.toDegrees(kotlin.math.atan2(d.y.toDouble(), d.z.toDouble()))
        assertEquals(40.0, elevation, 0.01)
        assertEquals(0f, shot.eye.x, 1e-4f)
    }
}
