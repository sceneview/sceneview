package io.github.sceneview.demo.demos.internal

/**
 * The mark 3D Scan's live model lays on the floor still to scan: the gap the guidance names alone
 * ([of]'s target, bounds `[minX, minZ, maxX, maxZ]`), each of its missing cells crossed by a
 * diagonal [HALF_WIDTH] of a cell wide on each side, so cell after cell they join into a 45°
 * hatching. Every other gap stays unmarked (2026-11-13: half the floor hatched read as site tape,
 * and intention.md asks for one indication of where to go, no orange zones).
 */
internal object ScanHoleMarks {

    /** The hatching of [ground]'s missing cells inside [target]; empty without one. */
    fun of(ground: ScanGround, target: FloatArray?): DebugMesh {
        val mesh = DebugMesh()
        target ?: return mesh
        val s = ground.cellM
        val y = ground.floorY - ScanGroundMeshes.SLAB_DROP_M + ScanGroundMeshes.HATCH_LIFT_M
        val h = s * HALF_WIDTH
        for (r in 0 until ground.rows) {
            val z0 = ground.originZ + r * s
            val z1 = z0 + s
            for (c in 0 until ground.columns) {
                if (ground.at(c, r) != ScanGround.MISSING) continue
                val x0 = ground.originX + c * s
                val x1 = x0 + s
                if (!inside(target, x0 + s / 2f, z0 + s / 2f)) continue
                mesh.quad(
                    mesh.vertex(x0 - h, y, z1 - h),
                    mesh.vertex(x0 + h, y, z1 + h),
                    mesh.vertex(x1 + h, y, z0 + h),
                    mesh.vertex(x1 - h, y, z0 - h),
                )
            }
        }
        return mesh
    }

    private fun inside(b: FloatArray, x: Float, z: Float) = x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]

    /** Half the stripe's width, share of a cell: about 2 cm wide on a 25 cm cell. */
    const val HALF_WIDTH = 0.045f
}
