package io.github.sceneview.snapshot

import io.github.sceneview.math.Position
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where a thumbnail camera sits and what it looks at. [near] and [far] bracket the whole box. */
internal data class SnapshotFraming(
    val eye: Position,
    val target: Position,
    val up: Position,
    val near: Float,
    val far: Float,
)

/**
 * The tightest camera that shows a box whole, seen from [yawDegrees] / [pitchDegrees], with
 * [padding] of the image left empty on each side.
 *
 * The view direction is exactly the requested angle: the camera is slid sideways and up (not
 * turned) until the box sits in the middle of the frame, so a long bench seen at three quarters is
 * centred on its silhouette rather than on its bounding-box centre.
 *
 * Exact, not a bounding-sphere approximation. For one image axis, with `a` a corner's offset across
 * the view, `z` its offset toward the camera and `t` the padded half-FOV tangent, a camera at
 * distance `D` slid by `s` frames every corner iff `|a − s| ≤ t · (D − z)` for all corners. That is
 * feasible iff `D ≥ (a_i − a_j) / 2t + (z_i + z_j) / 2` for every pair of corners, so the smallest
 * `D` is the largest of those 64 values, over both axes; `s` is then the middle of what remains
 * feasible, which is a single point on the axis that binds.
 *
 * @param yawDegrees rotation of the camera around the vertical axis, from the model's front (+Z)
 * toward its right (+X).
 * @param pitchDegrees elevation of the camera above the model's horizontal plane, in (-90, 90).
 * @param verticalFovDegrees the lens's vertical field of view.
 * @param aspect image width / height.
 * @param padding fraction of the image width and height left empty on each side, in [0, 0.5).
 */
internal fun frameSnapshot(
    center: Position,
    halfExtent: Position,
    yawDegrees: Float,
    pitchDegrees: Float,
    verticalFovDegrees: Float,
    aspect: Float,
    padding: Float,
): SnapshotFraming {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    val pitch = Math.toRadians(pitchDegrees.toDouble())
    // Camera basis: back points from the model toward the camera.
    val back = doubleArrayOf(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
    val right = doubleArrayOf(cos(yaw), 0.0, -sin(yaw))
    val up = doubleArrayOf(
        back[1] * right[2] - back[2] * right[1],
        back[2] * right[0] - back[0] * right[2],
        back[0] * right[1] - back[1] * right[0],
    )

    val fill = 1.0 - 2.0 * padding
    val tanY = tan(Math.toRadians(verticalFovDegrees / 2.0)) * fill
    val tanX = tanY * aspect

    val xs = DoubleArray(8)
    val ys = DoubleArray(8)
    val zs = DoubleArray(8)
    var i = 0
    for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
        val p = doubleArrayOf(
            sx * halfExtent.x.toDouble(),
            sy * halfExtent.y.toDouble(),
            sz * halfExtent.z.toDouble(),
        )
        xs[i] = dot(p, right)
        ys[i] = dot(p, up)
        zs[i] = dot(p, back)
        i++
    }
    val radius = sqrt(dot(halfExtent.toDoubles(), halfExtent.toDoubles()))
    // A point-sized model still gets a camera at a finite, non-zero distance.
    val scale = if (radius > 1e-6) radius else 1.0
    val maxZ = zs.max()
    val minZ = zs.min()

    val distance = maxOf(
        axisDistance(xs, zs, tanX),
        axisDistance(ys, zs, tanY),
        maxZ + scale * 1e-2,
        if (radius > 1e-6) 0.0 else 1.0,
    )
    val shiftX = axisShift(xs, zs, tanX, distance)
    val shiftY = axisShift(ys, zs, tanY, distance)

    val target = DoubleArray(3) { center.component(it) + right[it] * shiftX + up[it] * shiftY }
    val eye = DoubleArray(3) { target[it] + back[it] * distance }

    val nearest = distance - maxZ
    val farthest = distance - minZ
    val near = max(nearest * 0.5, distance * 1e-3)
    val far = max(farthest * 2.0, near * 4.0)
    return SnapshotFraming(
        eye = eye.toPosition(),
        target = target.toPosition(),
        up = up.toPosition(),
        near = near.toFloat(),
        far = far.toFloat(),
    )
}

/** The smallest camera distance at which the corners fit within `±tan` on one image axis. */
private fun axisDistance(a: DoubleArray, z: DoubleArray, tan: Double): Double {
    var best = -Double.MAX_VALUE
    for (i in a.indices) for (j in a.indices) {
        best = max(best, (a[i] - a[j]) / (2.0 * tan) + (z[i] + z[j]) / 2.0)
    }
    return best
}

/** The sideways slide, at [distance], that centres the corners on one image axis. */
private fun axisShift(a: DoubleArray, z: DoubleArray, tan: Double, distance: Double): Double {
    var low = -Double.MAX_VALUE
    var high = Double.MAX_VALUE
    for (i in a.indices) {
        low = max(low, a[i] - tan * (distance - z[i]))
        high = min(high, a[i] + tan * (distance - z[i]))
    }
    return (low + high) / 2.0
}

private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun Position.toDoubles() = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())

private fun Position.component(index: Int): Double = when (index) {
    0 -> x.toDouble()
    1 -> y.toDouble()
    else -> z.toDouble()
}

private fun DoubleArray.toPosition() = Position(this[0].toFloat(), this[1].toFloat(), this[2].toFloat())

/**
 * Copies an RGBA image whose rows are [rowStride] bytes apart into a tightly packed buffer, the
 * layout `Bitmap.copyPixelsFromBuffer` expects. Returns a view of [source] when it is already
 * packed. [source] is read from its position 0 and left untouched.
 */
internal fun packRgbaRows(
    source: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): ByteBuffer {
    require(pixelStride == 4) { "Expected 4 bytes per RGBA pixel, got $pixelStride" }
    val rowBytes = width * 4
    require(rowStride >= rowBytes) { "Row stride $rowStride is shorter than a $width-pixel row" }
    val input = source.duplicate().apply { position(0) }
    if (rowStride == rowBytes) {
        return input.apply { limit(rowBytes * height) }.slice()
    }
    val packed = ByteBuffer.allocateDirect(rowBytes * height)
    for (y in 0 until height) {
        input.limit(y * rowStride + rowBytes)
        input.position(y * rowStride)
        packed.put(input)
    }
    packed.rewind()
    return packed
}
