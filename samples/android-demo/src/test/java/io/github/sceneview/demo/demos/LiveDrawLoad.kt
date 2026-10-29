package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSurfels
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What the live 3D view asks of the GPU for one dense snapshot seen from one eye, worked out on
 * the JVM: the vertices and triangles it submits, and the fragments its surfels cover before the
 * depth test, through the same size rule as `scan_surfel.mat` ([DenseSurfels.screenScale]).
 *
 * The view is a portrait phone screen of [VIEW_LONG_PX] × [VIEW_SHORT_PX] whose long side spans
 * the camera's long field of view ([TAN_HALF_LONG], the synthetic depth camera's). A surfel's
 * square is foreshortened by the angle between its normal and the ray to its centre.
 */
internal class LiveDrawLoad(
    /** Surfels the snapshot holds: four vertices and two triangles each, all submitted. */
    val surfels: Int,
    /** Surfels whose centre lies in front of the eye and on (or overlapping) the screen. */
    val inView: Int,
    /** Fragments the surfels cover before the depth test, over the screen's pixels. */
    val overdraw: Float,
    /** Share of the screen's [CELL_PX] cells whose centre at least one surfel covers. */
    val coverage: Float,
    /** Share of the surfels in view covering fewer than [TINY_AREA_PX] pixels. */
    val tiny: Float,
) {
    val vertices: Int get() = surfels * VERTICES_PER_SURFEL
    val triangles: Int get() = surfels * TRIANGLES_PER_SURFEL

    companion object {
        /** A 19.5:9 phone held upright: 2340 pixels tall, 1080 wide. */
        const val VIEW_LONG_PX = 2340
        const val VIEW_SHORT_PX = 1080

        /** `tan` of half the camera's long field of view ([RerunSyntheticRoom] depth camera). */
        const val TAN_HALF_LONG = 0.64f

        /** The live view's near plane: nearer surfels are clipped. */
        const val NEAR_M = 0.05f

        /** The coverage grid's cell side, in pixels. */
        const val CELL_PX = 12

        /** A splat under 8 × 8 pixels: a few 2 × 2 quads, mostly shaded for nothing. */
        const val TINY_AREA_PX = 64f

        const val VERTICES_PER_SURFEL = 4
        const val TRIANGLES_PER_SURFEL = 2

        const val HALF_LONG = VIEW_LONG_PX / 2f
        const val HALF_SHORT = VIEW_SHORT_PX / 2f

        /** The focal length in pixels: half the long side over [TAN_HALF_LONG]. */
        const val FOCAL_PX = HALF_LONG / TAN_HALF_LONG

        /** The projection's `[1][1]` term along the long side, which `scan_surfel.mat` reads. */
        const val CLIP_Y_SCALE = 1f / TAN_HALF_LONG

        /** [cloud] of [voxelM] surfels as the live view draws it from [eye], surfels capped at [maxSide]. */
        fun of(
            cloud: DenseCloud,
            voxelM: Float,
            eye: DebugPose,
            maxSide: Float = DenseSurfels.LIVE_MAX_SCREEN_SIDE,
        ): LiveDrawLoad {
            val cols = ceil(VIEW_LONG_PX / CELL_PX.toFloat()).toInt()
            val rows = ceil(VIEW_SHORT_PX / CELL_PX.toFloat()).toInt()
            val covered = BooleanArray(cols * rows)
            var inView = 0
            var tiny = 0
            var fragments = 0.0
            forEachSplat(cloud, voxelM, eye, maxSide) { _, u, v, _, area ->
                inView++
                fragments += area
                if (area < TINY_AREA_PX) tiny++
                // The square of the same area, axis-aligned: the cells whose centre it covers.
                val reach = sqrt(area) / 2f
                val c0 = ceil((u - reach + HALF_LONG - CELL_PX / 2f) / CELL_PX).toInt().coerceAtLeast(0)
                val c1 = floor((u + reach + HALF_LONG - CELL_PX / 2f) / CELL_PX).toInt().coerceAtMost(cols - 1)
                val r0 = ceil((v - reach + HALF_SHORT - CELL_PX / 2f) / CELL_PX).toInt().coerceAtLeast(0)
                val r1 = floor((v + reach + HALF_SHORT - CELL_PX / 2f) / CELL_PX).toInt().coerceAtMost(rows - 1)
                for (r in r0..r1) for (c in c0..c1) covered[r * cols + c] = true
            }
            return LiveDrawLoad(
                surfels = cloud.count,
                inView = inView,
                overdraw = (fragments / (VIEW_LONG_PX.toDouble() * VIEW_SHORT_PX)).toFloat(),
                coverage = covered.count { it } / covered.size.toFloat(),
                tiny = if (inView == 0) 0f else tiny / inView.toFloat(),
            )
        }

        /**
         * Each surfel of [cloud] the live view puts on screen from [eye]: its index, its centre in
         * pixels from the screen's centre (`u` along the long side, `v` up), its depth, and the
         * pixels its foreshortened square covers before the depth test.
         */
        fun forEachSplat(
            cloud: DenseCloud,
            voxelM: Float,
            eye: DebugPose,
            maxSide: Float = DenseSurfels.LIVE_MAX_SCREEN_SIDE,
            action: (index: Int, u: Float, v: Float, depth: Float, area: Float) -> Unit,
        ) {
            val normals = cloud.normals
            for (i in 0 until cloud.count) {
                val wx = cloud.positions[i * 3] - eye.x
                val wy = cloud.positions[i * 3 + 1] - eye.y
                val wz = cloud.positions[i * 3 + 2] - eye.z
                // World to camera: the pose's rotation inverted (its conjugate).
                val (cx, cy, cz) = inverseRotate(eye, wx, wy, wz)
                val depth = -cz
                if (depth < NEAR_M) continue
                val half = voxelM * DenseSurfels.HALF_SIDE_VOXELS *
                    (1 shl cloud.levelOf(i).coerceAtMost(DenseFusion.LEVEL_LIMIT))
                val scale = DenseSurfels.screenScale(
                    halfDiagonal = half * SQRT_2,
                    depth = depth,
                    clipYScale = CLIP_Y_SCALE,
                    maxSide = maxSide,
                    nearFade = DenseSurfels.NEAR_FADE_M,
                )
                val side = 2f * half * FOCAL_PX / depth * scale
                val u = cx * FOCAL_PX / depth
                val v = cy * FOCAL_PX / depth
                if (abs(u) > HALF_LONG + side / 2f || abs(v) > HALF_SHORT + side / 2f) continue
                val nx = normals?.get(i * 3) ?: 0f
                val ny = normals?.get(i * 3 + 1) ?: 1f
                val nz = normals?.get(i * 3 + 2) ?: 0f
                val distance = sqrt(wx * wx + wy * wy + wz * wz).coerceAtLeast(1e-6f)
                val facing = abs(nx * wx + ny * wy + nz * wz) / distance
                action(i, u, v, depth, side * side * facing)
            }
        }

        private fun inverseRotate(pose: DebugPose, x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
            // v' = v + 2w (u × v) + 2 u × (u × v), u the conjugate's vector part.
            val ux = -pose.qx
            val uy = -pose.qy
            val uz = -pose.qz
            val w = pose.qw
            val tx = 2f * (uy * z - uz * y)
            val ty = 2f * (uz * x - ux * z)
            val tz = 2f * (ux * y - uy * x)
            return Triple(
                x + w * tx + (uy * tz - uz * ty),
                y + w * ty + (uz * tx - ux * tz),
                z + w * tz + (ux * ty - uy * tx),
            )
        }

        private const val SQRT_2 = 1.4142135f
    }
}
