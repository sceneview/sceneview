package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.roomShellShown
import io.github.sceneview.demo.demos.sharedRerunModel
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunGlbFile
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.demos.internal.model.JvmAtlasEncoder
import io.github.sceneview.demo.demos.internal.objects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

class RoomShellOutputTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `rectangle shell ground covers the room interior`() {
        val face = floor()
        val ground = ground(face)
        assertEquals(ScanGround.SEEN, at(ground, face, 2f, 2f))
        assertEquals(ScanGround.SEEN, at(ground, face, 0.5f, 3.5f))
        assertEquals(ScanGround.OUTSIDE, at(ground, face, -0.5f, 2f))
        assertEquals(0, ground.missing)
        assertEquals(16f, ground.seen * ground.cellM * ground.cellM, 0.1f)
        assertEquals(0.3f, ground.floorY, 0f)
    }

    @Test
    fun `rotated L shell ground preserves its notch`() {
        for (yaw in listOf(0f, 0.43f)) {
            val face = floor(yaw, floatArrayOf(0f, 0f, 4f, 2f, 0f, 2f, 2f, 4f))
            val ground = ground(face)
            assertEquals(ScanGround.SEEN, at(ground, face, 3f, 1f))
            assertEquals(ScanGround.SEEN, at(ground, face, 1f, 3f))
            assertEquals(ScanGround.OUTSIDE, at(ground, face, 3f, 3f))
            assertEquals(12f, ground.seen * ground.cellM * ground.cellM, 0.3f)
        }
    }

    @Test
    fun `sharing a fallback shell includes its points after saving and reopening`() {
        val glb = checkNotNull(RoomShellGlb.write(listOf(floor()), JvmAtlasEncoder))
        val points = DenseCloud(floatArrayOf(3f, 0.9f, -2f, 4f, 1.2f, -1f), intArrayOf(-1, 0xFF4080C0.toInt()))
        val store = RerunSessionStore(folder.newFolder())
        store.directoryOf("room").mkdirs()
        File(store.directoryOf("room"), RerunSessionStore.INFO).writeText("{}")
        store.saveRoomShell("room", glb, points, 1)
        val (keptGlb, keptPoints) = checkNotNull(store.roomShell("room", 1))
        for ((bytes, cloud) in listOf(glb to points, keptGlb to keptPoints)) {
            val shown = checkNotNull(roomShellShown(bytes, cloud))
            assertSame(bytes, shown.model.glb)
            assertEquals(cloud.count, shown.objects.count)
            val shared = sharedRerunModel(shown.model).glb
            val file = RerunGlbFile.parse(shared, "export")
            val meshes = file.json.objects("meshes")
            val primitives = meshes.flatMap { it.objects("primitives") }
            assertEquals(2, primitives.size)
            val primitive = primitives.single { (it["mode"] as Number).toInt() == 0 }
            val attributes = primitive["attributes"] as Map<*, *>
            val accessors = file.json.objects("accessors")
            val position = accessors[(attributes["POSITION"] as Number).toInt()]
            assertEquals(cloud.count, (position["count"] as Number).toInt())
            val view = file.json.objects("bufferViews")[(position["bufferView"] as Number).toInt()]
            val buffer = ByteBuffer.wrap(checkNotNull(file.bin)).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position((view["byteOffset"] as Number).toInt())
            val back = FloatArray(cloud.positions.size).also { buffer.asFloatBuffer().get(it) }
            assertArrayEquals(cloud.positions, back, 0f)
            assertTrue(attributes.containsKey("COLOR_0"))
            assertEquals(1, file.json.objects("images").size)
            // One parent translation centres both the shell and its points without scaling.
            val root = file.json.objects("nodes").last()
            assertEquals(listOf(-4f, -0.3f, 1f), (root["translation"] as List<*>).map { (it as Number).toFloat() })
        }
    }

    private fun ground(face: ShellFace): ScanGround {
        val glb = checkNotNull(RoomShellGlb.write(listOf(face), JvmAtlasEncoder))
        val geometry = checkNotNull(RerunGlbMesh.readGeometry(glb))
        assertTrue(geometry.shellQuads)
        return checkNotNull(ScanGround.of(geometry.positions, geometry.normals, shellQuads = geometry.shellQuads))
    }

    private fun at(ground: ScanGround, face: ShellFace, u: Float, v: Float): Byte {
        val p = FloatArray(3)
        face.at(u, v, p)
        return ground.at(((p[0] - ground.originX) / ground.cellM).toInt(), ((p[2] - ground.originZ) / ground.cellM).toInt())
    }

    private fun floor(yaw: Float = 0f, pieces: FloatArray = floatArrayOf(0f, 0f, 4f, 4f)) = ShellFace(
        "floor", floatArrayOf(2f, 0.3f, -3f),
        floatArrayOf(cos(yaw), 0f, sin(yaw)), floatArrayOf(-sin(yaw), 0f, cos(yaw)),
        4f, 4f, floatArrayOf(0f, 1f, 0f), 1, 1, pieces,
    ).apply { argb = intArrayOf(-1) }
}
