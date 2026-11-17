package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AreaSurface]: the ground a model holds against the ground its scan covers. */
class AreaSurfaceTest {
    /** A mesh of the quads [quads], each four corners (x, y, z) in turn round it. */
    private fun mesh(vararg quads: FloatArray): RerunMesh {
        val positions = quads.flatMap { it.asList() }.toFloatArray()
        val indices = quads.indices.flatMap { q -> listOf(0, 1, 2, 0, 2, 3).map { q * 4 + it } }.toIntArray()
        return RerunMesh(positions, FloatArray(positions.size), IntArray(positions.size / 3), indices)
    }

    private fun level(x0: Float, z0: Float, x1: Float, z1: Float, y: Float = 0f) =
        floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1)

    @Test
    fun `level ground counts in full, whichever way its triangles wind`() {
        assertEquals(6f, AreaSurface.levelM2(mesh(level(0f, 0f, 2f, 3f))), 1e-4f)
        // The same slab seen from below.
        assertEquals(6f, AreaSurface.levelM2(mesh(floatArrayOf(0f, 0f, 0f, 0f, 0f, 3f, 2f, 0f, 3f, 2f, 0f, 0f))), 1e-4f)
    }

    @Test
    fun `a wall is not ground, a gentle slope is`() {
        val wall = floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 2f, 0f, 0f, 2f, 0f)
        assertEquals(0f, AreaSurface.levelM2(mesh(wall)), 1e-4f)
        // Two metres long, rising 40 cm: its own area, not its shadow's.
        val slope = floatArrayOf(0f, 0f, 0f, 2f, 0.4f, 0f, 2f, 0.4f, 1f, 0f, 0f, 1f)
        assertEquals(2.04f, AreaSurface.levelM2(mesh(slope)), 0.01f)
    }

    @Test
    fun `scraps of a terrace are in pieces, most of it is not`() {
        // Four scraps of half a square metre out of fifteen scanned.
        val scraps = mesh(level(0f, 0f, 1f, 0.5f), level(2f, 0f, 3f, 0.5f), level(0f, 2f, 1f, 2.5f), level(2f, 2f, 3f, 2.5f))
        assertTrue(AreaSurface.inPieces(AreaSurface.levelM2(scraps), scannedM2 = 15f))
        assertFalse(AreaSurface.inPieces(AreaSurface.levelM2(mesh(level(0f, 0f, 3f, 4f))), scannedM2 = 15f))
    }

    @Test
    fun `a scan with no ground has nothing to be in pieces beside`() {
        assertFalse(AreaSurface.inPieces(modelM2 = 0f, scannedM2 = 0f))
    }

    @Test
    fun `the ground a scan covers is its seen cells`() {
        // A 2 m by 1 m slab of floor points every 5 cm.
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        for (i in 0..40) for (j in 0..20) {
            positions += listOf(i * 0.05f, 0f, j * 0.05f)
            normals += listOf(0f, 1f, 0f)
        }
        val ground = ScanGround.of(positions.toFloatArray(), normals.toFloatArray())!!
        assertEquals(2f, AreaSurface.groundM2(ground), 0.5f)
    }
}
