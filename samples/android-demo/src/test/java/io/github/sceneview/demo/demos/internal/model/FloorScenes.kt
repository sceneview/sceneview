package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunTsdf
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.tan
import kotlin.random.Random

/*
 * Synthetic floors for the floor tests: a sloped floor with a level strip and a lower landing
 * (with, or without, the foot of a piece of furniture), a room whose sharp blocks leave slivers
 * in the floor's selection, and a room with real holes in its floor. Each is fused into the real
 * TSDF with a phone's depth defects and meshed by the real marching cubes.
 */
internal object FloorScenes {
    /** As [RealScanRoom.Depth.PHONE], without the lace: the holes a scene has are its own. */
    val PHONE_SOLID = RealScanRoom.Depth(
        driftM = 0.009f, blotchM = 0.009f, blotchCellM = 0.05f, whiteM = 0.003f, missedShare = 0f, missedCellM = 1f,
    )

    /** About ±1 cm of noise on every surface, nothing missed. */
    val NOISY = RealScanRoom.Depth(
        driftM = 0f, blotchM = 0.007f, blotchCellM = 0.05f, whiteM = 0.003f, missedShare = 0f, missedCellM = 1f,
    )

    /** The sloped floor: a level strip at 0 ([STRIP_X0]..0), a 1° slope rising from it to [SLOPE_X1], a landing below the strip. */
    object Slope {
        val TILT = tan(Math.toRadians(1.0)).toFloat()
        const val LANDING_X0 = -1.4f
        const val STRIP_X0 = -0.6f
        const val SLOPE_X1 = 4.0f
        const val Z1 = 2.5f
        const val LANDING_Y = -0.08f

        /** The foot of a piece of furniture on the slope's high part, its base at +5.6 to +6.6 cm. */
        val FOOT = Box(3.2f, -0.3f, 0.9f, 3.8f, 0.6f, 1.5f)

        /** The strip along the seam the scan never saw, when it misses it: what cuts the floor in two. */
        const val SEAM_GAP_M = 0.04f

        /** The floor's true height at [x]. */
        fun height(x: Float): Float = when {
            x < STRIP_X0 -> LANDING_Y
            x < 0f -> 0f
            else -> x * TILT
        }

        /** The floor as [depth] sees it, the seam [seamSeen] or not, with [foot] standing on it, the landing or not. */
        fun scan(seamSeen: Boolean, foot: Box?, depth: RealScanRoom.Depth, landing: Boolean = true, seed: Int = SEED): WorkMesh {
            val bounds = Box(if (landing) LANDING_X0 else STRIP_X0, -0.2f, 0f, SLOPE_X1, (foot?.y1 ?: 0.1f) + 0.1f, Z1)
            val floor = { x: Float, y: Float, _: Float -> y - height(x) }
            val sdf = if (foot == null) floor else { x: Float, y: Float, z: Float -> minOf(floor(x, y, z), foot.sdf(x, y, z)) }
            return fuse(bounds, depth, seed, unseen = { x, _, _ -> !seamSeen && x in 0f..SEAM_GAP_M }, sdf = sdf)
        }
    }

    /**
     * A 3 m room, walls 60 cm high, its floor noisy by about ±1 cm, and three sharp-edged blocks:
     * two on the floor, one whose underside is [RAISED_GAP_M] above it, over a hole the scan
     * never saw ([HIDDEN]).
     */
    object Slivers {
        const val SIZE = 3f
        const val WALL_TOP = 0.6f
        const val RAISED_GAP_M = 0.06f
        val BLOCKS = listOf(
            Box(0.6f, -0.2f, 0.6f, 1.0f, 0.45f, 1.0f),
            Box(1.8f, -0.2f, 0.5f, 2.1f, 0.4f, 1.1f),
            Box(1.0f, RAISED_GAP_M, 1.8f, 1.48f, 0.5f, 2.36f),
        )

        /** 8 × 11 [FloorArea.COVER_CELL_M] cells under the raised block. */
        val HIDDEN = Box(1.08f, -1f, 1.86f, 1.40f, 1f, 2.30f)

        fun scan(seed: Int = SEED): WorkMesh {
            val bounds = Box(-0.1f, -0.15f, -0.1f, SIZE + 0.1f, WALL_TOP + 0.1f, SIZE + 0.1f)
            return fuse(bounds, NOISY, seed, unseen = { x, y, z -> y < UNDER_FLOOR_M && HIDDEN.contains(x, z) }) { x, y, z ->
                var d = room(x, y, z, SIZE, WALL_TOP)
                for (block in BLOCKS) d = minOf(d, block.sdf(x, y, z))
                d
            }
        }
    }

    /**
     * A 2 m room, walls 40 cm high, ten holes in its floor the scan never saw, 1 to 9
     * [FloorArea.COVER_CELL_M] cells each, all more than 4 cells from a wall.
     */
    object Holes {
        const val SIZE = 2f
        const val WALL_TOP = 0.4f
        private const val CELL = FloorArea.COVER_CELL_M

        /** Each hole as its centre and its size in cells. */
        private val SPECS = listOf(
            floatArrayOf(0.40f, 0.40f, 1f, 1f), floatArrayOf(0.80f, 0.40f, 1f, 2f),
            floatArrayOf(1.20f, 0.40f, 2f, 2f), floatArrayOf(1.60f, 0.40f, 1f, 3f),
            floatArrayOf(0.40f, 1.00f, 3f, 3f), floatArrayOf(0.80f, 1.00f, 2f, 3f),
            floatArrayOf(1.20f, 1.00f, 2f, 1f), floatArrayOf(1.60f, 1.00f, 3f, 1f),
            floatArrayOf(0.60f, 1.60f, 2f, 2f), floatArrayOf(1.40f, 1.60f, 3f, 2f),
        )
        val HOLES = SPECS.map { (x, z, w, d) -> Box(x - w * CELL / 2f, -1f, z - d * CELL / 2f, x + w * CELL / 2f, 1f, z + d * CELL / 2f) }

        fun scan(seed: Int = SEED): WorkMesh {
            val bounds = Box(-0.1f, -0.15f, -0.1f, SIZE + 0.1f, WALL_TOP + 0.1f, SIZE + 0.1f)
            return fuse(bounds, PHONE_SOLID, seed, unseen = { x, y, z -> y < UNDER_FLOOR_M && HOLES.any { it.contains(x, z) } }) { x, y, z ->
                room(x, y, z, SIZE, WALL_TOP)
            }
        }
    }

    /** Free space inside a [size] square room, floor at 0, walls up to [top]; negative in its walls and under its floor. */
    private fun room(x: Float, y: Float, z: Float, size: Float, top: Float): Float =
        if (y > top) minOf(y, x, size - x, z, size - z).coerceAtLeast(y - top) else minOf(y, x, size - x, z, size - z)

    private fun Box.contains(x: Float, z: Float): Boolean = x in x0..x1 && z in z0..z1

    /**
     * Fuses [sdf] over [bounds] as [depth] sees it: its drift, blotches and white noise on each
     * voxel, its lace left out, and every voxel [unseen] left out too. Meshed whole.
     */
    fun fuse(
        bounds: Box,
        depth: RealScanRoom.Depth,
        seed: Int,
        unseen: (Float, Float, Float) -> Boolean,
        sdf: (Float, Float, Float) -> Float,
    ): WorkMesh {
        val tsdf = RerunTsdf()
        val voxel = tsdf.voxelM
        val band = tsdf.truncationM * 1.5f + depth.maxErrorM
        val random = Random(seed)
        for (gz in floor(bounds.z0 / voxel).toInt()..floor(bounds.z1 / voxel).toInt() + 1) {
            for (gy in floor(bounds.y0 / voxel).toInt()..floor(bounds.y1 / voxel).toInt() + 1) {
                for (gx in floor(bounds.x0 / voxel).toInt()..floor(bounds.x1 / voxel).toInt() + 1) {
                    val x = gx * voxel
                    val y = gy * voxel
                    val z = gz * voxel
                    val clean = sdf(x, y, z)
                    if (abs(clean) > band || unseen(x, y, z) || depth.missed(x, y, z, seed)) continue
                    val d = clean + depth.error(x, y, z, 0f, seed) + (random.nextFloat() * 2f - 1f) * depth.whiteM
                    tsdf.update(gx, gy, gz, d, 1f, CleanScenes.GREY)
                }
            }
        }
        return CleanGeometry.of(RerunMarchingCubes.extractWhole(tsdf, 1f).mesh)
    }

    /** Below this, a voxel of an unseen patch of floor is left out: the floor's own voxels, not what stands above it. */
    private const val UNDER_FLOOR_M = 0.035f
    const val SEED = 61
}
