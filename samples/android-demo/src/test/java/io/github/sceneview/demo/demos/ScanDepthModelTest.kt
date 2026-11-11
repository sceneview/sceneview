package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugPose
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
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.ScanDepthHeader
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.SyntheticScene
import io.github.sceneview.demo.demos.internal.SyntheticShape
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.room.RoomBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a scan's result opens on ([resultKindOf], [depthModelOf]): a room that stands as one, its
 * shell; a scan with a depth recording that does not, its whole fused surface — built, kept and
 * read back; without a recording, the model as before. And the live TSDF let go for a recording
 * still leaves the model as before something to build from ([usable]).
 */
class ScanDepthModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a room that stands as one opens on its shell, with or without a recording`() {
        assertEquals(ResultKind.Shell, resultKindOf(ScanScope.Room, hasRecording = true, standsAsRoom = true))
        assertEquals(ResultKind.Shell, resultKindOf(ScanScope.Room, hasRecording = false, standsAsRoom = true))
    }

    @Test
    fun `a recording without a room opens on the fused surface, whatever the scope`() {
        for (scope in ScanScope.entries) {
            assertEquals(scope.name, ResultKind.DepthModel, resultKindOf(scope, hasRecording = true, standsAsRoom = false))
        }
        // Only a room scan's box makes a shell: an object scan of a room's corner is still its surface.
        assertEquals(ResultKind.DepthModel, resultKindOf(ScanScope.Object, hasRecording = true, standsAsRoom = true))
    }

    @Test
    fun `no recording and no room is the model as before`() {
        for (scope in ScanScope.entries) {
            assertEquals(scope.name, ResultKind.Model, resultKindOf(scope, hasRecording = false, standsAsRoom = false))
        }
    }

    @Test
    fun `an object scan with a recording gets its fused surface, kept and read back`() {
        val store = store(recording = true)
        var released = 0
        val built = checkNotNull(depthModelOf(store, ID, ScanScope.Object, null, null, onRecording = { released++ }))
        assertEquals(1, released)
        assertTrue("${built.triangles} triangles", built.triangles > MIN_TRIANGLES)
        assertTrue(built.triangles <= DEPTH_MODEL_MAX_TRIANGLES)
        // The floor is kept: no shell gives it here.
        val mesh = checkNotNull(RerunGlbMesh.read(built.glb))
        assertTrue("no floor", (0 until mesh.vertexCount).any { mesh.positions[it * 3 + 1] < FLOOR_M })
        assertTrue(store.hasDepthModel(ID, DEPTH_MODEL_VERSION))
        // Reopened: read back as it was, nothing fused again.
        val back = checkNotNull(depthModelOf(store, ID, ScanScope.Object, null, null, onRecording = { released++ }))
        assertEquals(1, released)
        assertTrue(back.glb.contentEquals(built.glb))
        // A kept surface of another version is built again.
        assertNull(store.depthModel(ID, DEPTH_MODEL_VERSION + 1))
    }

    @Test
    fun `a room scan with a recording and a room box is left to its shell`() {
        val store = store(recording = true)
        assertNotNull("the synthetic room stands as a room", RoomBox.of(cloud, 0f))
        assertNull(depthModelOf(store, ID, ScanScope.Room, cloud, 0f))
        // Its cloud not read yet: unknown, left to the shell too.
        assertNull(depthModelOf(store, ID, ScanScope.Room, null, null))
        assertTrue(!store.hasDepthModel(ID, DEPTH_MODEL_VERSION))
    }

    @Test
    fun `a room scan with a recording and no room box gets its fused surface`() {
        val store = store(recording = true)
        // A cloud with no walls: a patch of floor, as a garden's.
        val ground = DenseCloud(
            FloatArray(GROUND_POINTS * 3) { i -> if (i % 3 == 1) 0f else (i / 3 % 40) * 0.05f },
            IntArray(GROUND_POINTS) { GREY },
            FloatArray(GROUND_POINTS * 3) { if (it % 3 == 1) 1f else 0f },
        )
        assertNull(RoomBox.of(ground, 0f))
        assertNotNull(depthModelOf(store, ID, ScanScope.Room, ground, 0f))
    }

    @Test
    fun `without a recording, or one that does not read, nothing is fused and the live TSDF stays`() {
        var released = 0
        assertNull(depthModelOf(store(recording = false), ID, ScanScope.Object, null, null, onRecording = { released++ }))
        val garbled = store(recording = false).also { File(it.directoryOf(ID), ScanDepthFormat.FILE).writeText("not a recording") }
        assertNull(depthModelOf(garbled, ID, ScanScope.Object, null, null, onRecording = { released++ }))
        assertEquals(0, released)
        assertNull(depthModelOf(null, ID, ScanScope.Object, null, null))
        assertNull(depthModelOf(store(recording = true), null, ScanScope.Object, null, null))
    }

    @Test
    fun `a live TSDF let go gives way to its surfels, kept otherwise`() {
        val live = RerunModelSource.Live(RerunTsdf(), cloud)
        assertSame(live, live.usable())
        live.release()
        assertTrue(live.usable() is RerunModelSource.Surfels)
        assertNull(RerunModelSource.Live(RerunTsdf(), null).apply { release() }.usable())
    }

    @Test
    fun `a surface over the cap is simplified to it, one under it is left alone`() {
        val small = RerunMesh(FloatArray(9), FloatArray(9), IntArray(3), intArrayOf(0, 1, 2))
        assertSame(small, capped(small))
    }

    /** A store holding session [ID], with the synthetic room's depth recording when [recording]. */
    private fun store(recording: Boolean): RerunSessionStore {
        val store = RerunSessionStore(folder.newFolder())
        val dir = store.directoryOf(ID).apply { mkdirs() }
        File(dir, RerunSessionStore.INFO).writeText("{}")
        if (recording) {
            File(dir, ScanDepthFormat.FILE).outputStream().buffered().use { out ->
                out.write(ScanDepthFormat.header(HEADER))
                frames.forEachIndexed { i, f ->
                    out.write(ScanDepthFormat.encode(RecordedDepthFrame(i.toLong(), i.toLong(), PoseStep.Continuous, 128, f, f.pose, 0f, null)))
                }
            }
        }
        return store
    }

    private companion object {
        const val ID = "scan"
        const val MIN_TRIANGLES = 5_000
        const val FLOOR_M = 0.05f
        const val GROUND_POINTS = 1_600
        const val GREY = 0xFF808080.toInt()
        const val WALL = 0xFFE8E2D6.toInt()
        const val BOX = 0xFF4F6D8A.toInt()
        val HEADER = ScanDepthHeader(maxPoints = 200_000, maxLevel = DenseFusion.MAX_LEVEL, farM = 5f, voxelM = DenseFusion.VOXEL_M)

        val scene = SyntheticScene(
            listOf(
                SyntheticShape.Room(RerunSyntheticRoom.MIN, RerunSyntheticRoom.MAX) { _, _, _ -> WALL },
                SyntheticShape.Box(Vec3(0.2f, 0f, -0.3f), Vec3(0.8f, 0.6f, 0.3f)) { _, _, _ -> BOX },
            ),
        )

        /** One stop's turn: enough of the room for a surface, quick to fuse. */
        val frames: List<DepthFrame> by lazy {
            RerunSyntheticRoom.poses(yawSteps = 12).map { pose: DebugPose ->
                val f = scene.render(pose)
                DepthFrame(f.width, f.height, f.depthMm, ByteArray(f.width * f.height) { -1 }, f.colors, f.fx, f.fy, f.cx, f.cy, f.pose)
            }
        }
        val cloud: DenseCloud by lazy {
            val fusion = DenseFusion()
            frames.forEach { fusion.add(DepthBackProjection.project(it)) }
            fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        }
    }
}
