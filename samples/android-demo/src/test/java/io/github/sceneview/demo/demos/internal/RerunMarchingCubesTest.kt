package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.assertClosed
import io.github.sceneview.demo.demos.internal.RerunTsdfTest.Companion.assertFinite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Marching cubes' table, its closed surfaces, its small-piece filter, and the model's GLB. */
class RerunMarchingCubesTest {
    @Test
    fun `every pattern's triangles use exactly the edges whose corners disagree`() {
        assertEquals(256, RerunMarchingCubes.TRIANGLES.size)
        for (cube in 0 until 256) {
            var expected = 0
            for (e in 0 until 12) {
                val a = (cube shr RerunMarchingCubes.EDGE_A[e]) and 1
                val b = (cube shr RerunMarchingCubes.EDGE_B[e]) and 1
                if (a != b) expected = expected or (1 shl e)
            }
            assertEquals("pattern $cube", expected, RerunMarchingCubes.EDGES[cube])
            assertEquals("pattern $cube", 0, RerunMarchingCubes.TRIANGLES[cube].size % 3)
        }
    }

    @Test
    fun `a noisy but fully observed field meshes to closed surfaces, ambiguous cells included`() {
        val random = Random(4242)
        val tsdf = RerunTsdf()
        val n = 20
        for (z in 0..n) for (y in 0..n) for (x in 0..n) {
            val border = x == 0 || y == 0 || z == 0 || x == n || y == n || z == n
            val d = if (border) tsdf.truncationM else (random.nextFloat() - 0.4f) * tsdf.truncationM
            tsdf.update(x, y, z, d, 1f)
        }
        val mesh = RerunMarchingCubes.extract(tsdf, minComponentTriangles = 0).mesh
        assertTrue(mesh.triangleCount > 1_000)
        assertFinite(mesh)
        assertClosed(mesh)
    }

    @Test
    fun `pieces under the minimum go, the room stays`() {
        val tsdf = RerunTsdf()
        val voxel = tsdf.voxelM
        fun ball(cx: Int, cy: Int, cz: Int, r: Float) {
            val reach = r.toInt() + 4
            for (z in -reach..reach) for (y in -reach..reach) for (x in -reach..reach) {
                val d = (kotlin.math.sqrt((x * x + y * y + z * z).toFloat()) - r) * voxel
                tsdf.update(cx + x, cy + y, cz + z, d, 1f)
            }
        }
        ball(0, 0, 0, 12f) // ~6 000 triangles
        ball(60, 0, 0, 2f) // a few dozen: a floater
        val all = RerunMarchingCubes.extract(tsdf, minComponentTriangles = 0)
        val kept = RerunMarchingCubes.extract(tsdf)
        assertEquals(0, all.droppedComponents)
        assertEquals(1, kept.droppedComponents)
        assertTrue(kept.mesh.triangleCount in 500 until all.mesh.triangleCount)
        assertTrue(kept.mesh.bounds()[3] < 20 * voxel)
        assertClosed(kept.mesh)
        assertFinite(kept.mesh)
    }

    @Test
    fun `the model's GLB is a glTF binary with positions, normals, colours and indices`() {
        val tsdf = RerunTsdf()
        RerunSyntheticRoom.frames().take(30).forEach { tsdf.integrate(it) }
        val mesh = RerunMarchingCubes.extract(tsdf).mesh
        val glb = RerunMeshGlb.writeShared(mesh, fullResolution = true, floorOrigin = false)
        assertEquals(0, glb.size % 4)
        val header = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, header.int)
        assertEquals(2, header.int)
        assertEquals(glb.size, header.int)
        val file = RerunGlbFile.parse(glb, "room")
        val accessors = file.json.objects("accessors")
        assertEquals(mesh.vertexCount, (accessors[0]["count"] as Number).toInt())
        assertEquals(mesh.vertexCount, (accessors[2]["count"] as Number).toInt())
        assertEquals(true, accessors[2]["normalized"])
        assertEquals(mesh.indices.size, (accessors[3]["count"] as Number).toInt())
        val bin = file.bin!!
        assertTrue(bin.size >= mesh.vertexCount * 28 + mesh.indices.size * if (mesh.vertexCount <= 65535) 2 else 4)
        val attributes = file.json.objects("meshes")[0].objects("primitives")[0].obj("attributes")!!
        assertEquals(setOf("POSITION", "NORMAL", "COLOR_0"), attributes.keys)
    }
}
