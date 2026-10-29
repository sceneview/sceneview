package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.assertClosed
import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.assertFinite
import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.faceShare
import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.fibonacciSphere
import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.position
import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.share
import io.github.sceneview.demo.demos.internal.model.GlbCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** [ScreenedPoisson] on clouds whose surface is known: a ball, a floor with holes, a wall. */
class ScreenedPoissonTest {

    @Test
    fun `a ball's surfels give a closed ball of the right size, facing out`() {
        val result = assertNotNull(ScreenedPoisson.reconstruct(ball()))
        val mesh = RerunMarchingCubes.extractWhole(result.tsdf).mesh
        assertTrue("triangles", mesh.triangleCount > 1000)
        assertFinite(mesh)
        assertClosed(mesh)
        val off = (0 until mesh.vertexCount).maxOf { abs((position(mesh, it) - CENTRE).length() - RADIUS) }
        assertTrue("furthest vertex $off m off the ball", off < 0.015f)
        val outward = faceShare(mesh) { centroid, normal -> normal.dot(centroid - CENTRE) > 0f }
        assertTrue("outward faces $outward", outward > 0.99f)
        assertEquals(RerunTsdf.VOXEL_M, result.voxelM)
        assertTrue("converged to ${result.residual}", result.residual <= ScreenedPoisson.TOLERANCE)
    }

    @Test
    fun `a small hole in a floor closes, a large one stays open`() {
        val floor = floor(holes = listOf(SMALL_HOLE to 0.05f, LARGE_HOLE to 0.25f))
        val mesh = RerunMarchingCubes.extractWhole(assertNotNull(ScreenedPoisson.reconstruct(floor)).tsdf).mesh
        assertTrue("small hole covered", covered(mesh, SMALL_HOLE, 0.02f))
        assertTrue("large hole left open", !covered(mesh, LARGE_HOLE, 0.1f))
        // Flat where it was seen and where it closed: no bulge.
        val flat = share(mesh) { abs(position(mesh, it).y - FLOOR_Y) < 0.01f }
        assertTrue("on the floor $flat", flat > 0.99f)
        // The fused TSDF of the same surfels leaves the small hole open.
        val fused = RerunTsdf().apply { integrateSurfels(floor) }
        assertTrue("fused small hole open", !covered(RerunMarchingCubes.extractWhole(fused).mesh, SMALL_HOLE, 0.02f))
    }

    @Test
    fun `the field is positive in front of a wall and negative behind, as the TSDF's`() {
        val tsdf = assertNotNull(ScreenedPoisson.reconstruct(wall())).tsdf
        val k = 0 // the wall at z = 0, facing +z
        for ((i, j) in listOf(10 to 30, 0 to 40, -10 to 50)) {
            assertTrue("in front", tsdf.distanceAt(i, j, k + 2) > 0f)
            assertTrue("behind", tsdf.distanceAt(i, j, k - 2) < 0f)
        }
    }

    @Test
    fun `over the budget the grid coarsens, and gives up past two doublings`() {
        val cloud = ball()
        val full = assertNotNull(ScreenedPoisson.reconstruct(cloud))
        val coarse = assertNotNull(ScreenedPoisson.reconstruct(cloud, maxBytes = full.solveBytes - 1))
        assertEquals(RerunTsdf.VOXEL_M * 2f, coarse.voxelM)
        assertTrue("fits", coarse.solveBytes < full.solveBytes)
        assertClosed(RerunMarchingCubes.extractWhole(coarse.tsdf).mesh)
        assertNull(ScreenedPoisson.reconstruct(cloud, maxBytes = 1024))
    }

    @Test
    fun `the grid it would solve on is known before solving, and is the one it solves on`() {
        val cloud = ball()
        val full = assertNotNull(ScreenedPoisson.reconstruct(cloud))
        assertEquals(full.voxelM, ScreenedPoisson.spacing(cloud))
        val tight = full.solveBytes - 1
        assertEquals(assertNotNull(ScreenedPoisson.reconstruct(cloud, maxBytes = tight)).voxelM, ScreenedPoisson.spacing(cloud, maxBytes = tight))
        assertEquals(RerunTsdf.VOXEL_M * 2f, ScreenedPoisson.spacing(cloud, maxBytes = tight))
        assertNull(ScreenedPoisson.spacing(cloud, maxBytes = 1024))
        assertNull(ScreenedPoisson.spacing(DenseCloud(cloud.positions, cloud.colors)))
        assertNull(ScreenedPoisson.spacing(DenseCloud(FloatArray(0), IntArray(0), FloatArray(0))))
    }

    @Test
    fun `no normals or no surfels give nothing`() {
        val ball = ball()
        assertNull(ScreenedPoisson.reconstruct(DenseCloud(ball.positions, ball.colors)))
        assertNull(ScreenedPoisson.reconstruct(DenseCloud(FloatArray(0), IntArray(0), FloatArray(0))))
    }

    @Test
    fun `a coarsened surfel covers its square`() {
        // Level-2 surfels, 8 cm squares, 8 cm apart: the floor DenseFusion's level of detail leaves.
        val side = DenseFusion.VOXEL_M * 4
        val cloud = floor(spacing = side, level = 2)
        val mesh = RerunMarchingCubes.extractWhole(assertNotNull(ScreenedPoisson.reconstruct(cloud)).tsdf).mesh
        val area = area(mesh)
        val expected = FLOOR_SIDE * FLOOR_SIDE
        assertTrue("area $area of $expected m²", abs(area - expected) / expected < 0.1f)
    }

    @Test
    fun `colours stay where they were seen, and the glb is valid`() {
        val mesh = RerunMarchingCubes.extractWhole(assertNotNull(ScreenedPoisson.reconstruct(floor())).tsdf).mesh
        val west = (0 until mesh.vertexCount).filter { position(mesh, it).x < -0.1f }
        val east = (0 until mesh.vertexCount).filter { position(mesh, it).x > 0.1f }
        assertTrue("red west", west.count { mesh.colors[it] == RED }.toFloat() / west.size > 0.95f)
        assertTrue("blue east", east.count { mesh.colors[it] == BLUE }.toFloat() / east.size > 0.95f)
        assertEquals(emptyList<String>(), GlbCheck.problems(RerunMeshGlb.write(mesh)))
    }

    private fun ball(): DenseCloud {
        // One surfel per 2 cm square of the ball's surface, as DenseFusion leaves.
        val count = (4.0 * Math.PI * RADIUS * RADIUS / (DenseFusion.VOXEL_M * DenseFusion.VOXEL_M)).toInt()
        val directions = fibonacciSphere(count)
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        directions.forEachIndexed { i, d ->
            val p = CENTRE + d * RADIUS
            positions[i * 3] = p.x
            positions[i * 3 + 1] = p.y
            positions[i * 3 + 2] = p.z
            normals[i * 3] = d.x
            normals[i * 3 + 1] = d.y
            normals[i * 3 + 2] = d.z
        }
        return DenseCloud(positions, IntArray(count) { RED }, normals)
    }

    /** A square floor at [FLOOR_Y] facing up, red west of x = 0 and blue east, minus [holes] (centre, radius). */
    private fun floor(
        holes: List<Pair<Vec3, Float>> = emptyList(),
        spacing: Float = DenseFusion.VOXEL_M,
        level: Int = 0,
    ): DenseCloud {
        val positions = ArrayList<Float>()
        val colors = ArrayList<Int>()
        val n = (FLOOR_SIDE / spacing).toInt()
        for (a in 0 until n) {
            for (b in 0 until n) {
                val x = -FLOOR_SIDE / 2 + (a + 0.5f) * spacing
                val z = -FLOOR_SIDE / 2 + (b + 0.5f) * spacing
                if (holes.any { (c, r) -> hypot(x - c.x, z - c.z) < r }) continue
                positions += listOf(x, FLOOR_Y, z)
                colors += if (x < 0f) RED else BLUE
            }
        }
        val count = colors.size
        val normals = FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f }
        val levels = if (level == 0) null else ByteArray(count) { level.toByte() }
        return DenseCloud(positions.toFloatArray(), colors.toIntArray(), normals, levels = levels)
    }

    /** A wall in the plane z = 0, facing +z, 2 m wide and high, 2 cm surfels. */
    private fun wall(): DenseCloud {
        val n = (2f / DenseFusion.VOXEL_M).toInt()
        val positions = FloatArray(n * n * 3)
        for (a in 0 until n) {
            for (b in 0 until n) {
                val i = (a * n + b) * 3
                positions[i] = -1f + (a + 0.5f) * DenseFusion.VOXEL_M
                positions[i + 1] = 0.3f + (b + 0.5f) * DenseFusion.VOXEL_M
            }
        }
        val normals = FloatArray(n * n * 3) { if (it % 3 == 2) 1f else 0f }
        return DenseCloud(positions, IntArray(n * n) { RED }, normals)
    }

    /** Whether a triangle of [mesh] lies within [radius] of [centre], across the floor. */
    private fun covered(mesh: RerunMesh, centre: Vec3, radius: Float): Boolean = faceShare(mesh) { centroid, _ ->
        hypot(centroid.x - centre.x, centroid.z - centre.z) < radius
    } > 0f

    private fun area(mesh: RerunMesh): Float {
        var total = 0f
        for (t in 0 until mesh.triangleCount) {
            val a = position(mesh, mesh.indices[t * 3])
            val b = position(mesh, mesh.indices[t * 3 + 1])
            val c = position(mesh, mesh.indices[t * 3 + 2])
            total += (b - a).cross(c - a).length() / 2f
        }
        return total
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        assertNotNull("reconstruction", value)
        return value!!
    }

    private companion object {
        val CENTRE = Vec3(0.31f, 1.02f, -0.27f)
        const val RADIUS = 0.4f
        const val FLOOR_SIDE = 1.6f
        const val FLOOR_Y = 0.013f
        val SMALL_HOLE = Vec3(-0.4f, FLOOR_Y, -0.3f)
        val LARGE_HOLE = Vec3(0.35f, FLOOR_Y, 0.3f)
        const val RED = 0xFFC62828.toInt()
        const val BLUE = 0xFF1565C0.toInt()
    }
}
