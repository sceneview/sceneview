package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDepthSurfaceTest {
    @Test fun `weak observed edges close but unobserved corners never mesh`() {
        fun cell(missing: Boolean): RerunTsdf = RerunTsdf().also { tsdf ->
            for (z in 0..1) for (y in 0..1) for (x in 0..1) {
                if (missing && x == 1 && y == 1 && z == 1) continue
                tsdf.update(x, y, z, (z - 0.5f) * 0.02f,
                    if (x == 0 && y == 0 && z == 0) 0.8f else 0.1f)
            }
        }
        val observed = cell(false)
        assertEquals(0, RerunMarchingCubes.extract(observed, minWeight = 0.5f,
            minComponentTriangles = 0).mesh.triangleCount)
        assertTrue(RerunMarchingCubes.extract(observed, minWeight = 0.5f,
            weakestCorner = 0.05f, minComponentTriangles = 0).mesh.triangleCount > 0)
        assertEquals(0, RerunMarchingCubes.extract(cell(true), minWeight = 0.5f,
            weakestCorner = 0.05f, minComponentTriangles = 0).mesh.triangleCount)
    }

    @Test fun `depth surface keeps a large unobserved gap and neutral uncoloured walls`() {
        val tsdf = RerunTsdf(voxelM = 0.02f)
        for (start in listOf(0, 70)) {
            for (x in start..start + 30) for (y in 0..30) for (z in 0..1) {
                tsdf.update(x, y, z, (z - 0.5f) * 0.02f, 1f)
            }
        }
        val mesh = ScanDepthSurface.extract(tsdf)
        assertTrue(mesh.triangleCount >= 3000)
        assertTrue(mesh.colors.all { it == RerunMarchingCubes.NO_COLOR })
        for (v in 0 until mesh.vertexCount) {
            val x = mesh.positions[v * 3]
            assertTrue("unobserved gap at $x", x <= 0.63f || x >= 1.39f)
            assertTrue(x in 0f..2.03f)
        }
        assertTrue(mesh.normals.all { it.isFinite() })
    }
}
