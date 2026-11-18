package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.RerunMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanFloorSlab] lays one floor: two plates of floor seen apart — the strip under a sofa never
 * seen between them — are one slab across a gap up to [ScanFloorSlab.BRIDGE_M]; further apart,
 * the larger alone is the slab, never two islands.
 */
class ScanFloorSlabTest {
    @Test
    fun `two plates a sofa apart are one slab across the gap`() {
        val result = checkNotNull(ScanFloorSlab.of(plates(gapM = 0.6f), floorY = 0f))
        val bridged = BIG * BIG + SMALL * SMALL + 0.6f * SMALL
        assertEquals("the slab is both plates and the strip between", bridged, result.areaM2, AREA_SLACK_M2)
        assertEquals("one piece", 1, slabPieces(result.mesh))
    }

    @Test
    fun `two plates far apart leave the larger alone, not two islands`() {
        val result = checkNotNull(ScanFloorSlab.of(plates(gapM = 3f), floorY = 0f))
        assertEquals("the larger plate alone", BIG * BIG, result.areaM2, AREA_SLACK_M2)
        assertEquals("one piece", 1, slabPieces(result.mesh))
    }

    /** A [BIG] square of floor at the origin and a [SMALL] one [gapM] beyond it along x, in 5 cm quads. */
    private fun plates(gapM: Float): RerunMesh {
        val positions = ArrayList<Float>()
        val indices = ArrayList<Int>()
        fun plate(x0: Float, z0: Float, side: Float) {
            val n = (side / STEP_M).toInt()
            val base = positions.size / 3
            for (j in 0..n) for (i in 0..n) positions += listOf(x0 + i * STEP_M, 0f, z0 + j * STEP_M)
            for (j in 0 until n) {
                for (i in 0 until n) {
                    val a = base + j * (n + 1) + i
                    indices += listOf(a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2)
                }
            }
        }
        plate(0f, 0f, BIG)
        plate(BIG + gapM, 0f, SMALL)
        val count = positions.size / 3
        return RerunMesh(
            positions.toFloatArray(),
            FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f },
            IntArray(count) { GREY },
            indices.toIntArray(),
        )
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
            if (v.any { kotlin.math.abs(p[it * 3 + 1]) > FLAT_M }) continue
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
        const val STEP_M = 0.05f
        const val FLAT_M = 0.005f
        const val AREA_SLACK_M2 = 0.35f
        const val GREY = 0xFF808080.toInt()
    }
}
