package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.RerunMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [ScanFloorSlab] joins two plates of floor seen apart only on evidence: a sofa over the gap says
 * the floor runs on under it, and the slab is one piece; a gap that shows nothing, or a wall, stays
 * a gap. Real floor seen is never erased: two plates far apart are both kept.
 */
class ScanFloorSlabTest {
    @Test
    fun `two plates with a sofa over the gap are one slab across it`() {
        val result = checkNotNull(ScanFloorSlab.of(plates(GAP_M, between = Between.Sofa), floorY = 0f))
        val bridged = BIG * BIG + SMALL * SMALL + GAP_M * SMALL
        assertEquals("both plates and the strip under the sofa", bridged, result.areaM2, AREA_SLACK_M2)
        assertEquals("one piece", 1, slabPieces(result.mesh))
    }

    @Test
    fun `a gap that shows nothing is not bridged, both plates kept`() {
        val result = checkNotNull(ScanFloorSlab.of(plates(GAP_M, between = Between.Nothing), floorY = 0f))
        assertEquals("both plates, nothing invented", BIG * BIG + SMALL * SMALL, result.areaM2, AREA_SLACK_M2)
        assertEquals("two pieces", 2, slabPieces(result.mesh))
    }

    @Test
    fun `a wall in the gap is not bridged, both plates kept`() {
        val result = checkNotNull(ScanFloorSlab.of(plates(GAP_M, between = Between.Wall), floorY = 0f))
        assertEquals("both plates, nothing under the wall", BIG * BIG + SMALL * SMALL, result.areaM2, AREA_SLACK_M2)
        assertEquals("two pieces", 2, slabPieces(result.mesh))
    }

    @Test
    fun `the foot of a wall half seen, upright and no top, is no evidence`() {
        val result = checkNotNull(ScanFloorSlab.of(plates(GAP_M, between = Between.WallFoot), floorY = 0f))
        assertEquals("both plates, nothing bridged", BIG * BIG + SMALL * SMALL, result.areaM2, AREA_SLACK_M2)
        assertEquals("two pieces", 2, slabPieces(result.mesh))
    }

    @Test
    fun `two plates far apart are both kept, real floor never erased`() {
        val result = checkNotNull(ScanFloorSlab.of(plates(gapM = 3f, between = Between.Nothing), floorY = 0f))
        assertEquals("both plates", BIG * BIG + SMALL * SMALL, result.areaM2, AREA_SLACK_M2)
        assertEquals("two pieces", 2, slabPieces(result.mesh))
    }

    private enum class Between { Nothing, Sofa, Wall, WallFoot }

    /**
     * A [BIG] square of floor at the origin and a [SMALL] one [gapM] beyond it along x, and in
     * the gap [between]: nothing, a sofa's top and front (45 cm), or a wall (2.4 m).
     */
    private fun plates(gapM: Float, between: Between): RerunMesh {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val indices = ArrayList<Int>()

        /** A grid of quads from [o] along [u] then [v], [nu] × [nv] steps, facing [n]. */
        fun sheet(o: FloatArray, u: FloatArray, v: FloatArray, nu: Int, nv: Int, n: FloatArray) {
            val base = positions.size / 3
            for (j in 0..nv) {
                for (i in 0..nu) {
                    for (k in 0 until 3) positions += o[k] + u[k] * i + v[k] * j
                    normals += n.toList()
                }
            }
            for (j in 0 until nv) {
                for (i in 0 until nu) {
                    val a = base + j * (nu + 1) + i
                    indices += listOf(a, a + nu + 1, a + 1, a + 1, a + nu + 1, a + nu + 2)
                }
            }
        }
        val up = floatArrayOf(0f, 1f, 0f)
        fun plate(x0: Float, side: Float) {
            val n = (side / STEP_M).toInt()
            sheet(floatArrayOf(x0, 0f, 0f), floatArrayOf(STEP_M, 0f, 0f), floatArrayOf(0f, 0f, STEP_M), n, n, up)
        }
        plate(0f, BIG)
        plate(BIG + gapM, SMALL)
        val across = (gapM / FINE_M).toInt()
        val along = (SMALL / FINE_M).toInt()
        when (between) {
            Between.Nothing -> Unit
            Between.Sofa -> {
                sheet(floatArrayOf(BIG, SOFA_M, 0f), floatArrayOf(FINE_M, 0f, 0f), floatArrayOf(0f, 0f, FINE_M), across, along, up)
                val rise = (SOFA_M / FINE_M).toInt()
                sheet(floatArrayOf(BIG, 0f, SMALL), floatArrayOf(FINE_M, 0f, 0f), floatArrayOf(0f, FINE_M, 0f), across, rise, floatArrayOf(0f, 0f, 1f))
            }
            Between.Wall, Between.WallFoot -> {
                val rise = ((if (between == Between.Wall) WALL_M else FOOT_M) / FINE_M).toInt()
                sheet(
                    floatArrayOf(BIG + gapM / 2, 0f, 0f), floatArrayOf(0f, 0f, FINE_M), floatArrayOf(0f, FINE_M, 0f),
                    (BIG / FINE_M).toInt(), rise, floatArrayOf(1f, 0f, 0f),
                )
            }
        }
        val count = positions.size / 3
        return RerunMesh(positions.toFloatArray(), normals.toFloatArray(), IntArray(count) { GREY }, indices.toIntArray())
    }

    /** Pieces of the slab's top: its triangles flat at the floor, joined by shared vertices. */
    private fun slabPieces(mesh: RerunMesh): Int {
        val p = mesh.positions
        val ix = mesh.indices
        val parent = IntArray(mesh.vertexCount) { it }
        fun root(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        val top = HashSet<Int>()
        for (t in 0 until mesh.triangleCount) {
            val v = IntArray(3) { ix[t * 3 + it] }
            if (v.any { abs(p[it * 3 + 1]) > FLAT_M }) continue
            top += v.toList()
            parent[root(v[1])] = root(v[0])
            parent[root(v[2])] = root(v[0])
        }
        assertTrue("no slab top", top.isNotEmpty())
        return top.map { root(it) }.toSet().size
    }

    private companion object {
        const val BIG = 2f
        const val SMALL = 1.5f
        const val GAP_M = 0.6f
        const val SOFA_M = 0.45f
        const val WALL_M = 2.4f
        const val FOOT_M = 1f
        const val STEP_M = 0.05f
        const val FINE_M = 0.02f
        const val FLAT_M = 0.005f
        const val AREA_SLACK_M2 = 0.35f
        const val GREY = 0xFF808080.toInt()
    }
}
