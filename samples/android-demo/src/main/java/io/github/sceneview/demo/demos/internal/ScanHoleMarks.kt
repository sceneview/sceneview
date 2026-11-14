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

    /**
     * Where the guidance points for the gap whose bounds are [bounds]: the centre of its missing
     * cell nearest the mean of its missing cells, so always on the gap itself. The middle of the
     * bounds is not: an L-shaped gap's lies on floor already scanned, and pointed the line the
     * wrong way (Codex's review of u26). `null` without a missing cell in [bounds].
     */
    fun aimOf(ground: ScanGround, bounds: FloatArray): Pair<Float, Float>? {
        var sx = 0f
        var sz = 0f
        var n = 0
        forMissing(ground, bounds) { x, z -> sx += x; sz += z; n++ }
        if (n == 0) return null
        val mx = sx / n
        val mz = sz / n
        var best: Pair<Float, Float>? = null
        var bestGap = Float.MAX_VALUE
        forMissing(ground, bounds) { x, z ->
            val d = (x - mx) * (x - mx) + (z - mz) * (z - mz)
            if (d < bestGap) {
                bestGap = d
                best = x to z
            }
        }
        return best
    }

    /** Each missing cell's centre (x, z) inside [bounds]. */
    private inline fun forMissing(ground: ScanGround, bounds: FloatArray, each: (Float, Float) -> Unit) {
        val s = ground.cellM
        for (r in 0 until ground.rows) {
            for (c in 0 until ground.columns) {
                if (ground.at(c, r) != ScanGround.MISSING) continue
                val x = ground.originX + (c + 0.5f) * s
                val z = ground.originZ + (r + 0.5f) * s
                if (inside(bounds, x, z)) each(x, z)
            }
        }
    }

    private fun inside(b: FloatArray, x: Float, z: Float) = x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]

    /** Half the stripe's width, share of a cell: about 2 cm wide on a 25 cm cell. */
    const val HALF_WIDTH = 0.045f
}
