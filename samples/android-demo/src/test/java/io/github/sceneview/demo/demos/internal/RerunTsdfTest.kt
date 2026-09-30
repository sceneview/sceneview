package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Rerun demo's final model: raw depth fused into a TSDF ([RerunTsdf]) and meshed by
 * marching cubes ([RerunMarchingCubes]) — shapes whose surface is known, rendered into the depth
 * frames a phone would give ([SyntheticScene]), must come back within a voxel.
 */
class RerunTsdfTest {
    private val voxel = RerunTsdf.VOXEL_M

    @Test
    fun `a wall seen head-on comes back flat within a voxel, facing the camera`() {
        val wall = SyntheticScene(
            listOf(SyntheticShape.Box(Vec3(-5f, -5f, -2.1f), Vec3(5f, 5f, -2f)) { _, _, _ -> GREY }),
        )
        val tsdf = RerunTsdf()
        tsdf.integrate(wall.render(DebugPose(0f, 0f, 0f)))
        tsdf.integrate(wall.render(DebugPose(0.1f, 0.05f, 0f)))
        val mesh = RerunMarchingCubes.extract(tsdf, minComponentTriangles = 0).mesh
        assertTrue("a wall, ${mesh.triangleCount} triangles", mesh.triangleCount > 5_000)
        assertFinite(mesh)
        for (v in 0 until mesh.vertexCount) {
            assertEquals("vertex $v off the wall", -2f, mesh.positions[v * 3 + 2], voxel)
        }
        assertTrue("normals toward the camera", share(mesh) { v -> mesh.normals[v * 3 + 2] > 0.9f } > 0.97f)
        assertTrue("wound toward the camera", faceShare(mesh) { _, n -> n.z > 0f } > 0.99f)
    }

    @Test
    fun `a ball seen from all around closes, within a voxel of its surface`() {
        val radius = 0.4f
        val ball = SyntheticScene(listOf(SyntheticShape.Sphere(Vec3.Zero, radius, GREY)))
        val tsdf = RerunTsdf()
        for (direction in fibonacciSphere(VIEWS)) {
            tsdf.integrate(ball.render(lookAt(direction * VIEW_DISTANCE, Vec3.Zero)))
        }
        val mesh = RerunMarchingCubes.extract(tsdf).mesh
        assertFinite(mesh)
        assertTrue("a ball, ${mesh.triangleCount} triangles", mesh.triangleCount > 2_000)
        assertClosed(mesh)
        for (v in 0 until mesh.vertexCount) {
            val r = position(mesh, v).length()
            assertEquals("vertex $v off the ball", radius, r, voxel)
        }
        val outward = share(mesh) { v -> normal(mesh, v).dot(position(mesh, v).normalized()) > 0.9f }
        assertTrue("normals outward", outward > 0.97f)
        assertTrue("wound outward", faceShare(mesh) { c, n -> n.dot(c) > 0f } > 0.999f)
    }

    @Test
    fun `the synthetic room meshes to a bounded, finite, coloured model inside its walls`() {
        val tsdf = RerunTsdf()
        val start = System.nanoTime()
        var frames = 0
        RerunSyntheticRoom.frames().forEach {
            tsdf.integrate(it)
            frames++
        }
        val fused = System.nanoTime()
        val extraction = RerunMarchingCubes.extract(tsdf)
        val done = System.nanoTime()
        val mesh = extraction.mesh
        println(
            "synthetic room: $frames frames fused in ${(fused - start) / 1_000_000} ms, " +
                "${tsdf.blockCount} blocks (${tsdf.bytes / 1_048_576} MB), " +
                "meshed in ${(done - fused) / 1_000_000} ms: " +
                "${mesh.triangleCount} triangles, ${mesh.vertexCount} vertices " +
                "(${extraction.rawTriangles} raw, ${extraction.droppedComponents} pieces dropped)",
        )
        assertFinite(mesh)
        assertFalse(tsdf.budgetReached)
        assertFalse(extraction.capped)
        assertTrue("${mesh.triangleCount} triangles", mesh.triangleCount in 50_000..RerunMarchingCubes.MAX_TRIANGLES)
        val b = mesh.bounds()
        val slack = 2 * voxel
        assertTrue("bounds ${b.toList()}", b[0] >= -2f - slack && b[3] <= 2f + slack)
        assertTrue("bounds ${b.toList()}", b[1] >= -slack && b[4] <= 2.6f + slack)
        assertTrue("bounds ${b.toList()}", b[2] >= -1.8f - slack && b[5] <= 1.8f + slack)
        // The floor is there, and it is the floor's colours, not grey.
        val floor = (0 until mesh.vertexCount).filter {
            abs(mesh.positions[it * 3 + 1]) < voxel && mesh.normals[it * 3 + 1] > 0.9f
        }
        assertTrue("${floor.size} floor vertices", floor.size > 5_000)
        val coloured = floor.count { mesh.colors[it] != RerunMarchingCubes.NO_COLOR }
        assertTrue("floor coloured", coloured > floor.size * 0.95f)
    }

    @Test
    fun `a full budget drops new blocks, keeps the old, and says so`() {
        val tsdf = RerunTsdf(maxBytes = RerunTsdf.BYTES_PER_BLOCK * 40)
        RerunSyntheticRoom.frames().take(20).forEach { tsdf.integrate(it) }
        assertEquals(40, tsdf.blockCount)
        assertTrue(tsdf.budgetReached)
        assertTrue(tsdf.droppedBlocks > 0)
        assertFinite(RerunMarchingCubes.extract(tsdf, minComponentTriangles = 0).mesh)
    }

    @Test
    fun `the triangle cap stops the extraction short`() {
        val tsdf = RerunTsdf()
        RerunSyntheticRoom.frames().take(40).forEach { tsdf.integrate(it) }
        val extraction = RerunMarchingCubes.extract(tsdf, minComponentTriangles = 0, maxTriangles = 2_000)
        assertTrue(extraction.capped)
        assertTrue(extraction.mesh.triangleCount <= 2_000)
    }

    @Test
    fun `a saved surfel cloud of a floor meshes flat`() {
        val side = 60
        val positions = FloatArray(side * side * 3)
        val normals = FloatArray(side * side * 3)
        for (i in 0 until side) {
            for (j in 0 until side) {
                val p = (i * side + j) * 3
                positions[p] = i * 0.02f
                positions[p + 1] = 0.013f
                positions[p + 2] = j * 0.02f
                normals[p + 1] = 1f
            }
        }
        val cloud = DenseCloud(positions, IntArray(side * side) { OAK }, normals)
        val tsdf = RerunTsdf()
        var last = 0f
        tsdf.integrateSurfels(cloud) { last = it }
        assertEquals(1f, last)
        val mesh = RerunMarchingCubes.extract(tsdf, minWeight = 0.1f).mesh
        assertFinite(mesh)
        assertTrue("${mesh.triangleCount} triangles", mesh.triangleCount > 2_000)
        for (v in 0 until mesh.vertexCount) assertEquals(0.013f, mesh.positions[v * 3 + 1], voxel)
        assertTrue(share(mesh) { v -> mesh.normals[v * 3 + 1] > 0.9f } > 0.95f)
        assertTrue(share(mesh) { v -> mesh.colors[v] == OAK } > 0.9f)
    }

    @Test
    fun `nothing seen, nothing meshed`() {
        val tsdf = RerunTsdf()
        tsdf.integrate(SyntheticScene(emptyList()).render(DebugPose(0f, 0f, 0f)))
        assertEquals(0, tsdf.blockCount)
        assertEquals(0, RerunMarchingCubes.extract(tsdf).mesh.triangleCount)
    }

    companion object {
        private const val GREY = 0xFF808080.toInt()
        private const val OAK = 0xFFB08457.toInt()
        private const val VIEWS = 40
        private const val VIEW_DISTANCE = 1.5f

        fun assertFinite(mesh: RerunMesh) {
            assertTrue("NaN position", mesh.positions.all { it.isFinite() })
            assertTrue("NaN normal", mesh.normals.all { it.isFinite() })
            for (v in 0 until mesh.vertexCount) assertEquals("unit normal $v", 1f, normal(mesh, v).length(), 1e-3f)
            assertTrue("index out of range", mesh.indices.all { it in 0 until mesh.vertexCount })
        }

        /** Every edge in exactly two triangles, once each way: a closed, consistently wound surface. */
        fun assertClosed(mesh: RerunMesh) {
            val directed = HashMap<Long, Int>()
            for (t in 0 until mesh.triangleCount) {
                for (k in 0 until 3) {
                    val a = mesh.indices[t * 3 + k]
                    val b = mesh.indices[t * 3 + (k + 1) % 3]
                    directed.merge(a.toLong() shl 32 or b.toLong(), 1, Int::plus)
                }
            }
            val bad = directed.entries.count { (key, n) ->
                val a = (key ushr 32).toInt()
                val b = (key and 0xFFFFFFFFL).toInt()
                n != 1 || directed[b.toLong() shl 32 or a.toLong()] != 1
            }
            assertEquals("open or inconsistent edges of ${directed.size}", 0, bad)
        }

        fun position(mesh: RerunMesh, v: Int) = vec(mesh.positions, v)
        fun normal(mesh: RerunMesh, v: Int) = vec(mesh.normals, v)
        private fun vec(xyz: FloatArray, v: Int) = Vec3(xyz[v * 3], xyz[v * 3 + 1], xyz[v * 3 + 2])

        fun share(mesh: RerunMesh, test: (Int) -> Boolean): Float =
            (0 until mesh.vertexCount).count(test).toFloat() / mesh.vertexCount.coerceAtLeast(1)

        /** The share of triangles whose centroid and face normal pass [test]. */
        fun faceShare(mesh: RerunMesh, test: (Vec3, Vec3) -> Boolean): Float {
            var pass = 0
            for (t in 0 until mesh.triangleCount) {
                val a = position(mesh, mesh.indices[t * 3])
                val b = position(mesh, mesh.indices[t * 3 + 1])
                val c = position(mesh, mesh.indices[t * 3 + 2])
                if (test((a + b + c) * (1f / 3f), (b - a).cross(c - a))) pass++
            }
            return pass.toFloat() / mesh.triangleCount.coerceAtLeast(1)
        }

        fun fibonacciSphere(n: Int): List<Vec3> = (0 until n).map { i ->
            val y = 1f - 2f * (i + 0.5f) / n
            val r = sqrt(1f - y * y)
            val phi = (i * PI * (3 - sqrt(5.0))).toFloat()
            Vec3(r * cos(phi), y, r * sin(phi))
        }

        /** A camera at [eye] looking at [target], via [RerunSyntheticRoom.lookPose]'s yaw and pitch. */
        fun lookAt(eye: Vec3, target: Vec3): DebugPose {
            val f = (target - eye).normalized()
            val pitch = asin(f.y.coerceIn(-1f, 1f))
            val yaw = atan2(-f.x, -f.z)
            return RerunSyntheticRoom.lookPose(eye, yaw, pitch).also {
                assertTrue("lookAt", acos(it.forward.dot(f).coerceIn(-1f, 1f)) < 1e-2f)
            }
        }
    }
}
