package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.DebugPlane
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BoundedPlaneCompletionTest {
    @Test fun `observed wall closes a large depth hole within its polygon`() {
        val raw = wall(0.5f, 1.5f)
        val plane = DebugPlane(1, DebugPlaneKind.Wall, floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 2f, 0f, 0f, 2f, 0f))
        val result = BoundedPlaneCompletion.complete(raw, listOf(plane))
        assertTrue("observed wall is continuous", covers(result, 1f, 1f))
        for (v in 0 until result.vertexCount) {
            assertTrue(result.positions[v * 3] in -0.0001f..2.0001f)
            assertTrue(result.positions[v * 3 + 1] in -0.0001f..2.0001f)
            assertTrue(abs(result.positions[v * 3 + 2]) < 0.0001f)
        }
    }

    @Test fun `fusion bridges a small gap but does not invent a large unseen wall`() {
        assertTrue(covers(BoundedPlaneCompletion.complete(wall(0.9f, 1.1f), emptyList()), 1.01f, 1.01f))
        assertFalse(covers(BoundedPlaneCompletion.complete(wall(0.4f, 1.6f), emptyList()), 1.01f, 1.01f))
    }

    @Test fun `clipped slanted edges stay inside the observed triangle`() {
        val raw = wall(0.4f, 1.6f)
        val plane = DebugPlane(1, DebugPlaneKind.Wall, floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f))
        val result = BoundedPlaneCompletion.complete(raw, listOf(plane))
        assertTrue(covers(result, 0.8f, 0.8f))
        assertFalse("no completion beyond the diagonal into the large hole", covers(result, 1.2f, 1.2f))
    }

    @Test fun `off-plane furniture retains its position`() {
        val wall = wall(0.5f, 1.5f)
        val mesh = WorkMesh(wall.positions + floatArrayOf(0.8f, 0.7f, 0.4f, 1.2f, 0.7f, 0.4f, 1f, 1.2f, 0.4f),
            wall.colors + IntArray(3) { -1 }, wall.indices + intArrayOf(wall.vertexCount, wall.vertexCount + 1, wall.vertexCount + 2))
        val plane = DebugPlane(1, DebugPlaneKind.Wall, floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 2f, 0f, 0f, 2f, 0f))
        val result = BoundedPlaneCompletion.complete(mesh, listOf(plane))
        assertTrue((0 until result.vertexCount).count { abs(result.positions[it * 3 + 2] - 0.4f) < 0.0001f } == 3)
    }

    private fun wall(lo: Float, hi: Float): WorkMesh {
        val p = ArrayList<Float>(); val ids = ArrayList<Int>()
        for (y in 0..20) for (x in 0..20) p.addAll(listOf(x / 10f, y / 10f, 0f))
        for (y in 0 until 20) for (x in 0 until 20) {
            if ((x + 0.5f) / 10f in lo..hi && (y + 0.5f) / 10f in lo..hi) continue
            val a = y * 21 + x
            ids.addAll(listOf(a, a + 1, a + 22, a, a + 22, a + 21))
        }
        return WorkMesh(p.toFloatArray(), IntArray(p.size / 3) { -1 }, ids.toIntArray()).keepTriangles(BooleanArray(ids.size / 3) { true })
    }

    private fun covers(mesh: WorkMesh, x: Float, y: Float): Boolean {
        for (t in 0 until mesh.triangleCount) {
            val vs = (0..2).map { mesh.indices[t * 3 + it] }
            val signs = (0..2).map { k ->
                val a = vs[k] * 3; val b = vs[(k + 1) % 3] * 3
                (mesh.positions[b] - mesh.positions[a]) * (y - mesh.positions[a + 1]) -
                    (mesh.positions[b + 1] - mesh.positions[a + 1]) * (x - mesh.positions[a])
            }
            if (signs.all { it >= -1e-6f } || signs.all { it <= 1e-6f }) return true
        }
        return false
    }
}
