package io.github.sceneview.demo.demos.internal

/**
 * The marks 3D Scan's live model lays on the floor still to scan (2026-11-13, the review of u25:
 * a hatching over half the floor read as site tape and drowned the model). Every missing cell of
 * the ground gets a thin diagonal, [FAINT_HALF_WIDTH] of a cell, drawn quiet; only the gap the
 * guidance names ([target], bounds `[minX, minZ, maxX, maxZ]`) gets the wider [STRONG_HALF_WIDTH]
 * one, drawn bright. Cell after cell, the diagonals join into a 45° hatching.
 */
internal object ScanHoleMarks {

    /** The quiet hatching of every gap, and the bright one of the gap named. */
    class Meshes(val faint: DebugMesh, val strong: DebugMesh)

    fun of(ground: ScanGround, target: FloatArray?): Meshes {
        val faint = DebugMesh()
        val strong = DebugMesh()
        val s = ground.cellM
        val y = ground.floorY - ScanGroundMeshes.SLAB_DROP_M + ScanGroundMeshes.HATCH_LIFT_M
        for (r in 0 until ground.rows) {
            val z0 = ground.originZ + r * s
            val z1 = z0 + s
            for (c in 0 until ground.columns) {
                if (ground.at(c, r) != ScanGround.MISSING) continue
                val x0 = ground.originX + c * s
                val x1 = x0 + s
                val named = target != null && inside(target, x0 + s / 2f, z0 + s / 2f)
                val mesh = if (named) strong else faint
                val h = s * if (named) STRONG_HALF_WIDTH else FAINT_HALF_WIDTH
                mesh.quad(
                    mesh.vertex(x0 - h, y, z1 - h),
                    mesh.vertex(x0 + h, y, z1 + h),
                    mesh.vertex(x1 + h, y, z0 + h),
                    mesh.vertex(x1 - h, y, z0 - h),
                )
            }
        }
        return Meshes(faint, strong)
    }

    private fun inside(b: FloatArray, x: Float, z: Float) = x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]

    /** Half a quiet stripe's width, share of a cell: a hairline, 1 cm on a 25 cm cell. */
    const val FAINT_HALF_WIDTH = 0.02f

    /** Half the named gap's stripe, share of a cell: about 2 cm wide on a 25 cm cell. */
    const val STRONG_HALF_WIDTH = 0.045f
}
