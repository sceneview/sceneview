package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.PoseStep
import io.github.sceneview.demo.demos.internal.RecordedDepthFrame
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.ScanDepthHeader
import io.github.sceneview.demo.demos.internal.SyntheticScene
import io.github.sceneview.demo.demos.internal.SyntheticShape
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.model.JvmAtlasEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * [RoomDepthMesh] and the shell's object mesh on a ray-cast room holding one box: the depth
 * recording fused into a surface, the box cut out of it as the room's only object — not its walls,
 * not its floor — and that mesh carried through the shell's `.glb` and the saved shell.
 */
class RoomDepthMeshTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the recording fuses into the room's walls, floor and the box`() {
        val fused = checkNotNull(RoomDepthMesh.fuse(recording())) { "nothing fused" }
        assertEquals(scanFrames.size, fused.frames)
        assertEquals(scanFrames.size, fused.fused)
        assertEquals("the byte cap dropped blocks", 0, fused.droppedBlocks)
        val m = fused.mesh
        assertTrue("${m.triangleCount} triangles", m.triangleCount > MIN_ROOM_TRIANGLES)
        assertTrue("no wall", vertices(m).any { (x, y, _) -> abs(x - MAX.x) < NEAR_M && y > 1f })
        assertTrue("no floor", vertices(m).any { (x, y, z) -> abs(y) < NEAR_M && !inBox(x, y, z, 0.1f) })
        assertTrue("no box top", vertices(m).any { (x, y, z) -> abs(y - BOX_MAX.y) < NEAR_M && inBox(x, y - NEAR_M, z, 0f) })
    }

    @Test
    fun `the object mesh is the box, not the walls or the floor`() {
        val objects = RoomShellBuilder.objectMesh(fusedMesh, BOX) { a, b -> minOf(a - BOX.a0, BOX.a1 - a, b - BOX.b0, BOX.b1 - b) }
        assertTrue("${objects.triangleCount} triangles", objects.triangleCount > MIN_BOX_TRIANGLES)
        for ((x, y, z) in vertices(objects)) {
            assertTrue("($x, $y, $z) is not the box's", inBox(x, y, z, SLACK_M))
        }
        // The box as the walk saw it: its top, and the two sides its stops face (-x, +z).
        val v = vertices(objects)
        assertTrue("no top", v.count { (_, y, _) -> abs(y - BOX_MAX.y) < NEAR_M } > MIN_FACE_VERTICES)
        assertTrue("no -x side", v.count { (x, _, _) -> abs(x - BOX_MIN.x) < NEAR_M } > MIN_FACE_VERTICES)
        assertTrue("no +z side", v.count { (_, _, z) -> abs(z - BOX_MAX.z) < NEAR_M } > MIN_FACE_VERTICES)
    }

    @Test
    fun `frames the pose guard held back are not fused`() {
        assertNull("all held back", RoomDepthMesh.fuse(recording(held = true)))
        val half = scanFrames.indices.associate { it.toLong() to (it % 2 == 0) }
        val fused = checkNotNull(RoomDepthMesh.fuse(recording(held = true), integrates = half))
        assertEquals((scanFrames.size + 1) / 2, fused.fused)
    }

    @Test
    fun `a room shell built with the depth has its objects as a mesh in its glb, and no points`() {
        val shell = checkNotNull(runBlocking { RoomShellBuilder.build(cloud, null, 0f, depthMesh = fusedMesh) }) { "no shell" }
        val objects = assertNotNullAnd(shell.objectMesh)
        assertEquals(0, shell.objects.count)
        assertEquals(objects.triangleCount, shell.stats.objectTriangles)
        for ((x, y, z) in vertices(objects)) assertTrue("($x, $y, $z) is not the box's", inBox(x, y, z, SLACK_M))
        val glb = checkNotNull(RoomShellGlb.write(shell.faces, JvmAtlasEncoder, objects = objects))
        // Read back as the renderer and the app's own readers read it: the faces where they were.
        val read = ShellRender.read(glb)
        assertEquals(shell.faces.size, read.faces.size)
        val back = assertNotNullAnd(read.objects)
        assertEquals(objects.triangleCount, back.triangleCount)
        assertEquals(objects.positions.toList(), back.positions.toList())
        assertEquals(shell.faces.sumOf { it.pieceCount } * 4, assertNotNullAnd(RerunGlbMesh.read(glb)).vertexCount)
        // Kept: the shell reads back with its mesh in the glb and no points beside it.
        val store = RerunSessionStore(folder.newFolder("sessions"))
        store.directoryOf(ID).mkdirs()
        File(store.directoryOf(ID), RerunSessionStore.INFO).writeText("{}")
        store.saveRoomShell(ID, glb, shell.objects, 1)
        val (keptGlb, keptPoints) = checkNotNull(store.roomShell(ID, 1))
        assertEquals(0, keptPoints.count)
        assertEquals(objects.triangleCount, assertNotNullAnd(ShellRender.read(keptGlb).objects).triangleCount)
    }

    @Test
    fun `without the depth the shell keeps its object points and its glb has no mesh`() {
        val shell = checkNotNull(runBlocking { RoomShellBuilder.build(cloud, null, 0f) })
        assertNull(shell.objectMesh)
        assertTrue("${shell.objects.count} points", shell.objects.count > 0)
        assertNull(ShellRender.read(checkNotNull(RoomShellGlb.write(shell.faces, JvmAtlasEncoder))).objects)
    }

    @Test
    fun `a depth surface with no furniture left keeps the object points, in the glb and the kept shell`() {
        // Only the room's own walls and floor: the object cut keeps no triangle of it.
        val bare = RerunMesh(
            floatArrayOf(MIN.x, 0f, MIN.z, MAX.x, 0f, MIN.z, MAX.x, 0f, MAX.z),
            floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f),
            IntArray(3) { FLOOR },
            intArrayOf(0, 1, 2),
        )
        val empty = RerunMesh(FloatArray(0), FloatArray(0), IntArray(0), IntArray(0))
        for (depth in listOf(bare, empty)) {
            val shell = checkNotNull(runBlocking { RoomShellBuilder.build(cloud, null, 0f, depthMesh = depth) })
            assertNull(shell.objectMesh)
            assertEquals(0, shell.stats.objectTriangles)
            assertTrue("${shell.objects.count} points", shell.objects.count > 0)
            val glb = checkNotNull(RoomShellGlb.write(shell.faces, JvmAtlasEncoder, objects = shell.objectMesh))
            assertNull(ShellRender.read(glb).objects)
            val store = RerunSessionStore(folder.newFolder())
            store.directoryOf(ID).mkdirs()
            File(store.directoryOf(ID), RerunSessionStore.INFO).writeText("{}")
            store.saveRoomShell(ID, glb, shell.objects, 1)
            assertEquals(shell.objects.count, checkNotNull(store.roomShell(ID, 1)).second.count)
        }
    }

    /** The scan's frames written as a depth recording, each marked held back when [held]; its time its index. */
    private fun recording(held: Boolean = false): File {
        val file = folder.newFile()
        file.outputStream().buffered().use { out ->
            out.write(ScanDepthFormat.header(HEADER))
            scanFrames.forEachIndexed { i, frame ->
                val step = if (held) PoseStep.Held else PoseStep.Continuous
                out.write(ScanDepthFormat.encode(RecordedDepthFrame(i.toLong(), i.toLong(), step, CONFIDENCE, frame, frame.pose, 0f, null)))
            }
        }
        return file
    }

    private fun vertices(m: RerunMesh): List<Triple<Float, Float, Float>> =
        (0 until m.vertexCount).map { Triple(m.positions[it * 3], m.positions[it * 3 + 1], m.positions[it * 3 + 2]) }

    private fun inBox(x: Float, y: Float, z: Float, slack: Float) =
        x in BOX_MIN.x - slack..BOX_MAX.x + slack && y in BOX_MIN.y - slack..BOX_MAX.y + slack && z in BOX_MIN.z - slack..BOX_MAX.z + slack

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return checkNotNull(value)
    }

    private companion object {
        val MIN = RerunSyntheticRoom.MIN
        val MAX = RerunSyntheticRoom.MAX
        val BOX_MIN = Vec3(0.2f, 0f, -0.3f)
        val BOX_MAX = Vec3(0.8f, 0.6f, 0.3f)

        /** The room's own box, as [RoomBox.of] finds it on this room: square to the world. */
        val BOX = RoomBox(yaw = 0f, a0 = MIN.x, a1 = MAX.x, b0 = MIN.z, b1 = MAX.z, floorY = MIN.y, ceilingY = MAX.y)

        val scene = SyntheticScene(
            listOf(
                SyntheticShape.Room(MIN, MAX) { _, y, _ -> if (y < SURFACE) FLOOR else WALL },
                SyntheticShape.Box(BOX_MIN, BOX_MAX) { _, y, _ -> if (y > BOX_MAX.y - SURFACE) BOX_TOP else BOX_SIDE },
            ),
        )

        /** The walk of the furnished room's scan, every frame sure of its depth, as ARCore's confidence says. */
        val scanFrames: List<DepthFrame> by lazy {
            RerunSyntheticRoom.poses().map { pose ->
                val f = scene.render(pose)
                DepthFrame(f.width, f.height, f.depthMm, ByteArray(f.width * f.height) { -1 }, f.colors, f.fx, f.fy, f.cx, f.cy, f.pose)
            }
        }
        val cloud: DenseCloud by lazy {
            DenseFusion().apply { scanFrames.forEach { add(DepthBackProjection.project(it)) } }.cloud(minViews = DenseFusion.MIN_VIEWS)
        }
        val fusedMesh: RerunMesh by lazy {
            val dir = File(System.getProperty("java.io.tmpdir"), "room-depth-mesh-${System.nanoTime()}").apply { mkdirs() }
            val file = File(dir, ScanDepthFormat.FILE)
            file.outputStream().buffered().use { out ->
                out.write(ScanDepthFormat.header(HEADER))
                scanFrames.forEachIndexed { i, frame ->
                    out.write(
                        ScanDepthFormat.encode(RecordedDepthFrame(i.toLong(), i.toLong(), PoseStep.Continuous, CONFIDENCE, frame, frame.pose, 0f, null)),
                    )
                }
            }
            checkNotNull(RoomDepthMesh.fuseSession(dir)).mesh.also { dir.deleteRecursively() }
        }

        val HEADER = ScanDepthHeader(maxPoints = 200_000, maxLevel = DenseFusion.MAX_LEVEL, farM = 5f, voxelM = DenseFusion.VOXEL_M)
        const val ID = "room"
        const val CONFIDENCE = 128
        const val SURFACE = 1e-3f
        const val NEAR_M = 0.04f
        const val SLACK_M = 0.05f
        const val MIN_ROOM_TRIANGLES = 20_000
        const val MIN_BOX_TRIANGLES = 1_000
        const val MIN_FACE_VERTICES = 50
        const val FLOOR = 0xFFB08457.toInt()
        const val WALL = 0xFFE8E2D6.toInt()
        const val BOX_TOP = 0xFF4F6D8A.toInt()
        const val BOX_SIDE = 0xFF3E5670.toInt()
    }
}
