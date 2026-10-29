package io.github.sceneview.snapshot

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pins the thumbnail framing against an **independent** pinhole camera: every expectation below
 * is checked by projecting the box's eight corners through a camera rebuilt from the returned eye
 * and target alone, not by re-running the production formula.
 */
class ModelSnapshotFramingTest {

    private val boxes = listOf(
        Position(0.5f, 0.5f, 0.5f), // cube
        Position(0.2f, 1.5f, 0.2f), // tall (a lamp)
        Position(2.0f, 0.1f, 1.0f), // flat (a rug)
        Position(3.0f, 0.4f, 0.3f), // long (a bench)
    )
    private val centers = listOf(Position(0f, 0f, 0f), Position(12f, -3f, 40f))
    private val yaws = listOf(0f, 30f, 135f, -60f)
    private val pitches = listOf(0f, 20f, 60f, -30f)
    private val aspects = listOf(1f, 0.5f, 2f)
    private val vFov = 33.4f
    private val padding = 0.08f

    private data class Projection(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val minDepth: Double, val maxDepth: Double)

    /** Projects the eight corners through a pinhole camera built from [framing]'s eye and target. */
    private fun project(framing: SnapshotFraming, center: Position, half: Position, aspect: Float): Projection {
        val f = doubleArrayOf(
            (framing.target.x - framing.eye.x).toDouble(),
            (framing.target.y - framing.eye.y).toDouble(),
            (framing.target.z - framing.eye.z).toDouble(),
        ).normalized()
        val worldUp = doubleArrayOf(0.0, 1.0, 0.0)
        val r = cross(f, worldUp).normalized()
        val u = cross(r, f)
        val tanY = tan(Math.toRadians(vFov / 2.0))
        val tanX = tanY * aspect
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var minD = Double.MAX_VALUE; var maxD = -Double.MAX_VALUE
        for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) for (sz in listOf(-1, 1)) {
            val p = doubleArrayOf(
                center.x + sx * half.x - framing.eye.x.toDouble(),
                center.y + sy * half.y - framing.eye.y.toDouble(),
                center.z + sz * half.z - framing.eye.z.toDouble(),
            )
            val depth = dot(p, f)
            val x = dot(p, r) / (depth * tanX)
            val y = dot(p, u) / (depth * tanY)
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            minD = min(minD, depth); maxD = max(maxD, depth)
        }
        return Projection(minX, maxX, minY, maxY, minD, maxD)
    }

    private fun forEachCase(block: (Position, Position, Float, Float, Float, SnapshotFraming) -> Unit) {
        for (half in boxes) for (center in centers) for (yaw in yaws) for (pitch in pitches) for (aspect in aspects) {
            val framing = frameSnapshot(center, half, yaw, pitch, vFov, aspect, padding)
            block(center, half, yaw, pitch, aspect, framing)
        }
    }

    @Test
    fun everyCornerIsInsideThePaddedFrame() = forEachCase { center, half, _, _, aspect, framing ->
        val p = project(framing, center, half, aspect)
        val limit = 1.0 - 2.0 * padding + 1e-3
        assertTrue("x $p", p.minX >= -limit && p.maxX <= limit)
        assertTrue("y $p", p.minY >= -limit && p.maxY <= limit)
    }

    @Test
    fun theBoxFillsTheFrameOnItsBindingAxis() = forEachCase { center, half, _, _, aspect, framing ->
        val p = project(framing, center, half, aspect)
        val limit = 1.0 - 2.0 * padding
        val spanX = (p.maxX - p.minX) / 2.0
        val spanY = (p.maxY - p.minY) / 2.0
        assertEquals("not tight: $p", limit, max(spanX, spanY), 2e-3)
    }

    @Test
    fun theBoxIsCenteredOnItsBindingAxis() = forEachCase { center, half, _, _, aspect, framing ->
        val p = project(framing, center, half, aspect)
        val spanX = p.maxX - p.minX
        val spanY = p.maxY - p.minY
        val offset = if (spanX >= spanY) p.maxX + p.minX else p.maxY + p.minY
        assertEquals("off-centre: $p", 0.0, offset, 2e-3)
    }

    @Test
    fun theViewDirectionIsTheRequestedAngle() = forEachCase { _, _, yaw, pitch, _, framing ->
        val back = doubleArrayOf(
            (framing.eye.x - framing.target.x).toDouble(),
            (framing.eye.y - framing.target.y).toDouble(),
            (framing.eye.z - framing.target.z).toDouble(),
        ).normalized()
        val y = Math.toRadians(yaw.toDouble())
        val pt = Math.toRadians(pitch.toDouble())
        val expected = doubleArrayOf(sin(y) * cos(pt), sin(pt), cos(y) * cos(pt))
        for (i in 0..2) assertEquals("axis $i", expected[i], back[i], 1e-4)
    }

    @Test
    fun clipPlanesBracketTheBox() = forEachCase { center, half, _, _, aspect, framing ->
        val p = project(framing, center, half, aspect)
        assertTrue("near ${framing.near} vs ${p.minDepth}", framing.near > 0f && framing.near < p.minDepth)
        assertTrue("far ${framing.far} vs ${p.maxDepth}", framing.far > p.maxDepth)
    }

    @Test
    fun aDegenerateBoxStillGivesAUsableCamera() {
        val framing = frameSnapshot(Position(1f, 2f, 3f), Position(0f, 0f, 0f), 30f, 20f, vFov, 1f, padding)
        val d = sqrt(
            ((framing.eye.x - 1f) * (framing.eye.x - 1f) +
                (framing.eye.y - 2f) * (framing.eye.y - 2f) +
                (framing.eye.z - 3f) * (framing.eye.z - 3f)).toDouble()
        )
        assertTrue("distance $d", d > 0.0 && d.isFinite())
        assertTrue(framing.near > 0f && framing.far > framing.near)
    }

    @Test
    fun aWiderLensMovesTheCameraCloser() {
        val half = Position(0.5f, 0.5f, 0.5f)
        val narrow = frameSnapshot(Position(), half, 30f, 20f, 20f, 1f, padding)
        val wide = frameSnapshot(Position(), half, 30f, 20f, 60f, 1f, padding)
        fun dist(f: SnapshotFraming) = sqrt((f.eye.x * f.eye.x + f.eye.y * f.eye.y + f.eye.z * f.eye.z).toDouble())
        assertTrue(dist(wide) < dist(narrow))
    }

    private fun DoubleArray.normalized(): DoubleArray {
        val l = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])
        return doubleArrayOf(this[0] / l, this[1] / l, this[2] / l)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    @Suppress("unused")
    private fun near(a: Double, b: Double) = abs(a - b) < 1e-6
}
