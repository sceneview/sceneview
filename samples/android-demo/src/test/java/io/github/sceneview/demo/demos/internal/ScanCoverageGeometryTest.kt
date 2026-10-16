package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ScanCoverageGeometryTest {
    private fun cell(x: Float, y: Float, z: Float, normal: FloatArray, evidence: Float = 1f) =
        ScanCoverage.Cell(0).apply {
            absorb(
                ScanCoverage.Acc().apply {
                    count = 1
                    this.x = x
                    this.y = y
                    this.z = z
                    nx = normal[0]
                    ny = normal[1]
                    nz = normal[2]
                    best = evidence
                },
                0L,
            )
        }

    @Test
    fun `a wall mark uses the grid centre projected onto its measured plane`() {
        val cell = cell(-0.03f, 1.43f, -2.03f, floatArrayOf(0f, 0f, 1f))
        val mesh = DebugMesh()
        ScanCoverageGeometry.append(mesh, cell, CELL_M)
        assertEquals(7, mesh.vertexCount)
        assertEquals(6, mesh.triangleCount)
        assertEquals(-0.1f, mesh.positions[0], EPSILON)
        assertEquals(1.5f, mesh.positions[1], EPSILON)
        assertEquals(-2.03f, mesh.positions[2], EPSILON)
        repeat(mesh.vertexCount) { assertEquals(-2.03f, mesh.positions[it * 3 + 2], EPSILON) }
        assertTrue(mesh.indices.take(mesh.indexCount).all { it in 0 until mesh.vertexCount })
    }

    @Test
    fun `every vertex of an oblique wall lies on its measured plane`() {
        val diagonal = 1f / sqrt(2f)
        val cell = cell(-0.03f, 1.43f, -2.03f, floatArrayOf(diagonal, 0f, diagonal))
        val mesh = DebugMesh()
        ScanCoverageGeometry.append(mesh, cell, CELL_M)
        repeat(mesh.vertexCount) {
            val distance = (mesh.positions[it * 3] - cell.x + mesh.positions[it * 3 + 2] - cell.z) * diagonal
            assertEquals(0f, distance, EPSILON)
        }
    }

    @Test
    fun `full floors and ceilings are world X Z squares at the measured height`() {
        for (sign in listOf(-1f, 1f)) {
            val cell = cell(0.03f, sign * 1.43f, -0.03f, floatArrayOf(0f, sign, 0f), evidence = 10f)
            val mesh = DebugMesh()
            ScanCoverageGeometry.append(mesh, cell, CELL_M)
            assertEquals(4, mesh.vertexCount)
            assertEquals(2, mesh.triangleCount)
            repeat(mesh.vertexCount) { assertEquals(cell.y, mesh.positions[it * 3 + 1], EPSILON) }
            val bounds = mesh.bounds()!!
            assertEquals(CELL_M * ScanCoverageGeometry.FULL_SIDE, bounds[3] - bounds[0], EPSILON)
            assertEquals(CELL_M * ScanCoverageGeometry.FULL_SIDE, bounds[5] - bounds[2], EPSILON)
        }
    }

    @Test
    fun `a stronger observation grows the mark and an unseen cell draws nothing`() {
        val empty = DebugMesh()
        ScanCoverageGeometry.append(empty, ScanCoverage.Cell(0), CELL_M)
        assertTrue(empty.isEmpty)
        val widths = listOf(1f, 2f, 4f).map { evidence ->
            val mesh = DebugMesh()
            ScanCoverageGeometry.append(mesh, cell(0f, 0f, 0f, floatArrayOf(0f, 0f, 1f), evidence), CELL_M)
            assertEquals(6, mesh.triangleCount)
            val bounds = mesh.bounds()!!
            bounds[3] - bounds[0]
        }
        assertTrue(widths.zipWithNext().all { (a, b) -> b > a })
    }

    private companion object {
        const val CELL_M = 0.2f
        const val EPSILON = 1e-5f
    }
}
