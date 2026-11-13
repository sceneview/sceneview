package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.PoseStep
import io.github.sceneview.demo.demos.internal.RecordedDepthFrame
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.ScanDepthHeader
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.SyntheticScene
import io.github.sceneview.demo.demos.internal.SyntheticShape
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.room.RoomDepthMesh
import io.github.sceneview.demo.demos.internal.room.ScanFloorSlab
import io.github.sceneview.demo.demos.internal.room.ShellRender
import io.github.sceneview.demo.demos.internal.model.JvmImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The result of a short, partial room scan (13/11: a 26 s walk over a third of a room came out as
 * shreds of parquet under a grey disc): a 26 s walk, ten depth frames a second, in one corner of
 * a 6 × 5 m room whose floor has patches the depth never reads (a rug's sheen, a dark board), and
 * a pose ARCore teleported 2.5 m and 80° for a second without the phone's guard flagging it.
 *
 * Its result ([depthModelOf]) stands on one clean floor — one outline, no hole — with an edge
 * down all round, and nothing in it lies further than the depth is sure ([RoomDepthMesh.RELIABLE_FAR_M])
 * from where the camera really was, nor outside the room: the slip fused nothing.
 */
class ScanPartialWalkTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a short partial walk stands on one clean floor with an edge, where the raw fusion is lace`() {
        val raw = checkNotNull(RoomDepthMesh.fuse(recording(), RoomDepthMesh.Settings(farM = RoomDepthMesh.RELIABLE_FAR_M), guard = guard()))
        val rawFloor = floorOutlines(raw.mesh, band = ScanFloorSlab.FLOOR_BAND_M)
        assertTrue("the fused floor alone is lace: $rawFloor outlines", rawFloor > 2)

        val mesh = result()
        assertEquals("one outline round the floor, no hole in it", 1, floorOutlines(mesh, band = FLAT_M))
        val area = floorArea(mesh)
        assertTrue("a floor, not a patch: $area m²", area > MIN_FLOOR_M2)
        val edge = (0 until mesh.vertexCount).count { abs(mesh.positions[it * 3 + 1] + ScanFloorSlab.EDGE_M) < FLAT_M }
        assertTrue("an edge down from the floor all round", edge > 0)
        // What stands on the floor is kept: the sofa's top, 80 cm up.
        assertTrue("the sofa is kept", (0 until mesh.vertexCount).any { abs(mesh.positions[it * 3 + 1] - SOFA_TOP) < 0.05f })
    }

    @Test
    fun `nothing in the result lies past the sure range of the true walk, slip included`() {
        val mesh = result()
        var furthest = 0f
        var misplaced = 0
        for (v in 0 until mesh.vertexCount) {
            val x = mesh.positions[v * 3]
            val y = mesh.positions[v * 3 + 1]
            val z = mesh.positions[v * 3 + 2]
            furthest = maxOf(furthest, walk.minOf { distance(it.at, x, y, z) })
            if (offTheRoom(x, y, z) > OFF_M) misplaced++
        }
        assertTrue("furthest vertex ${"%.2f".format(furthest)} m from the walk", furthest <= RoomDepthMesh.RELIABLE_FAR_M + BAND_M)
        assertEquals("vertices out of place", 0, misplaced)
    }

    @Test
    fun `without the guard run again the unflagged slip fuses a ghost room`() {
        // The control: the same recording fused as the phone's verdicts alone say.
        val unguarded = checkNotNull(RoomDepthMesh.fuse(recording(), RoomDepthMesh.Settings(farM = RoomDepthMesh.RELIABLE_FAR_M)))
        val p = unguarded.mesh.positions
        val ghost = (0 until unguarded.mesh.vertexCount).count { offTheRoom(p[it * 3], p[it * 3 + 1], p[it * 3 + 2]) > OFF_M }
        assertTrue("the slip, fused, puts surfaces out of place: $ghost vertices", ghost > MIN_GHOST)
    }

    /**
     * With `SV_WALK_OUT` set, the walk's result drawn off the phone ([ShellRender]) before and
     * after: `before-*` as the result was fused until now (5 m, the phone's verdicts alone, the
     * floor as fused), `after-*` as [depthModelOf] builds it now. For the pull request's pictures.
     */
    @Test
    fun `draws the walk's result before and after when asked`() {
        val out = System.getenv("SV_WALK_OUT")?.let(::File) ?: return
        out.mkdirs()
        val before = checkNotNull(RoomDepthMesh.fuse(recording(), RoomDepthMesh.Settings(farM = RoomDepthMesh.FAR_M))).mesh
        val after = result()
        for ((name, mesh) in listOf("before" to before, "after" to after)) {
            for ((view, azimuth) in listOf("side" to 60.0, "top" to null)) {
                val image = ShellRender.view(mesh, azimuth)
                File(out, "$name-$view.png").writeBytes(JvmImages.png(image.argb, image.width, image.height))
            }
        }
    }

    /** The walk's result as the app builds it: a room scan with no room box. */
    private fun result(): RerunMesh {
        val store = RerunSessionStore(folder.newFolder())
        val dir = store.directoryOf(ID).apply { mkdirs() }
        File(dir, RerunSessionStore.INFO).writeText("{}")
        recording().copyTo(File(dir, ScanDepthFormat.FILE))
        val noBox = DenseCloud(FloatArray(0), IntArray(0))
        val model = checkNotNull(depthModelOf(store, ID, ScanScope.Room, noBox, null))
        return checkNotNull(RerunGlbMesh.read(model.glb))
    }

    private fun guard() = io.github.sceneview.demo.demos.internal.PoseContinuity()

    private fun recording(): File {
        val file = File(folder.root, "walk-${System.nanoTime()}.bin")
        file.outputStream().buffered().use { out ->
            out.write(ScanDepthFormat.header(HEADER))
            for ((i, step) in walk.withIndex()) {
                val truth = RerunSyntheticRoom.lookPose(step.at, step.yaw, step.pitch)
                val frame = withBlindPatches(scene.render(truth))
                // ARCore's slip: the image is the room as it is, the pose it reports is not.
                val reported = if (i in SLIP) {
                    RerunSyntheticRoom.lookPose(
                        Vec3(step.at.x + SLIP_M, step.at.y, step.at.z),
                        step.yaw + Math.toRadians(SLIP_DEG).toFloat(),
                        step.pitch,
                    )
                } else {
                    truth
                }
                val recorded = DepthFrame(
                    frame.width, frame.height, frame.depthMm, frame.confidence, frame.colors,
                    frame.fx, frame.fy, frame.cx, frame.cy, reported,
                )
                val nanos = i * FRAME_NANOS
                // The phone's guard missed it: every frame recorded as integrated.
                out.write(ScanDepthFormat.encode(RecordedDepthFrame(nanos, nanos, PoseStep.Continuous, 128, recorded, reported, 0f, null)))
            }
        }
        return file
    }

    /** [frame] with the floor's blind patches at no confidence: the depth never reads them. */
    private fun withBlindPatches(frame: DepthFrame): DepthFrame {
        val conf = ByteArray(frame.width * frame.height) { -1 }
        for (py in 0 until frame.height) {
            for (px in 0 until frame.width) {
                val i = py * frame.width + px
                val mm = frame.depthMm[i].toInt()
                if (mm <= 0) continue
                val z = mm / 1000f
                val w = frame.pose.rotate((px - frame.cx) / frame.fx * z, -(py - frame.cy) / frame.fy * z, -z)
                val y = frame.pose.y + w.y
                if (y > BLIND_HEIGHT_M) continue
                val bx = floor((frame.pose.x + w.x) / BLIND_CELL_M).toInt()
                val bz = floor((frame.pose.z + w.z) / BLIND_CELL_M).toInt()
                if (Math.floorMod(bx * 7 + bz * 13, BLIND_EVERY) == 0) conf[i] = 0
            }
        }
        return DepthFrame(frame.width, frame.height, frame.depthMm, conf, frame.colors, frame.fx, frame.fy, frame.cx, frame.cy, frame.pose)
    }

    /** Outlines (boundary loops) of [mesh]'s faces lying flat within [band] of the floor. */
    private fun floorOutlines(mesh: RerunMesh, band: Float): Int {
        val p = mesh.positions
        val ix = mesh.indices
        val edges = HashMap<Long, Int>()
        for (t in 0 until mesh.triangleCount) {
            val v = IntArray(3) { ix[t * 3 + it] }
            if (v.any { abs(p[it * 3 + 1]) > band } || !flat(p, v)) continue
            for (k in 0 until 3) {
                val a = minOf(v[k], v[(k + 1) % 3]).toLong()
                val b = maxOf(v[k], v[(k + 1) % 3]).toLong()
                edges.merge((a shl 32) or b, 1, Int::plus)
            }
        }
        val parent = HashMap<Int, Int>()
        fun root(x: Int): Int {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
            return r
        }
        for ((key, count) in edges) {
            if (count != 1) continue
            val a = root((key shr 32).toInt())
            val b = root((key and 0xFFFFFFFFL).toInt())
            parent.putIfAbsent(a, a)
            parent[b] = a
        }
        return parent.keys.map { root(it) }.toSet().size
    }

    /** Whether triangle [v] lies flat, facing up or down. */
    private fun flat(p: FloatArray, v: IntArray): Boolean {
        val ux = p[v[1] * 3] - p[v[0] * 3]
        val uy = p[v[1] * 3 + 1] - p[v[0] * 3 + 1]
        val uz = p[v[1] * 3 + 2] - p[v[0] * 3 + 2]
        val vx = p[v[2] * 3] - p[v[0] * 3]
        val vy = p[v[2] * 3 + 1] - p[v[0] * 3 + 1]
        val vz = p[v[2] * 3 + 2] - p[v[0] * 3 + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        return abs(ny) > FLAT_NY * sqrt(nx * nx + ny * ny + nz * nz)
    }

    /**
     * How far a point lies from the room as it is — its floor, walls and ceiling, and the sofa:
     * anything further than a few centimetres is out of place, a ghost of a slipped pose.
     */
    private fun offTheRoom(x: Float, y: Float, z: Float): Float {
        val inside = x in ROOM_MIN.x..ROOM_MAX.x && y in ROOM_MIN.y..ROOM_MAX.y && z in ROOM_MIN.z..ROOM_MAX.z
        val room = if (inside) {
            minOf(minOf(x - ROOM_MIN.x, ROOM_MAX.x - x), minOf(y - ROOM_MIN.y, ROOM_MAX.y - y), minOf(z - ROOM_MIN.z, ROOM_MAX.z - z))
        } else {
            boxDistance(ROOM_MIN, ROOM_MAX, x, y, z)
        }
        val sofaInside = x in SOFA_MIN.x..SOFA_MAX.x && y in SOFA_MIN.y..SOFA_MAX.y && z in SOFA_MIN.z..SOFA_MAX.z
        val sofa = if (sofaInside) 0f else boxDistance(SOFA_MIN, SOFA_MAX, x, y, z)
        return minOf(room, sofa)
    }

    private fun boxDistance(lo: Vec3, hi: Vec3, x: Float, y: Float, z: Float): Float {
        val dx = maxOf(lo.x - x, 0f, x - hi.x)
        val dy = maxOf(lo.y - y, 0f, y - hi.y)
        val dz = maxOf(lo.z - z, 0f, z - hi.z)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun floorArea(mesh: RerunMesh): Float {
        val p = mesh.positions
        val ix = mesh.indices
        var area = 0f
        for (t in 0 until mesh.triangleCount) {
            val a = ix[t * 3]
            val b = ix[t * 3 + 1]
            val c = ix[t * 3 + 2]
            if (listOf(a, b, c).any { abs(p[it * 3 + 1]) > FLAT_M }) continue
            val ux = p[b * 3] - p[a * 3]
            val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]
            val vz = p[c * 3 + 2] - p[a * 3 + 2]
            area += abs(ux * vz - uz * vx) / 2f
        }
        return area
    }

    private fun distance(a: Vec3, x: Float, y: Float, z: Float): Float =
        sqrt((a.x - x) * (a.x - x) + (a.y - y) * (a.y - y) + (a.z - z) * (a.z - z))

    private class Step(val at: Vec3, val yaw: Float, val pitch: Float)

    private companion object {
        const val ID = "walk"
        const val FRAME_NANOS = 100_000_000L
        const val FRAMES = 260
        val SLIP = 120 until 130
        const val SLIP_M = 2.5f
        const val SLIP_DEG = 80.0
        const val BLIND_HEIGHT_M = 0.02f
        const val BLIND_CELL_M = 0.2f
        const val BLIND_EVERY = 5
        const val FLAT_M = 0.005f
        const val MIN_FLOOR_M2 = 4f
        const val OFF_M = 0.1f
        const val FLAT_NY = 0.9f
        const val MIN_GHOST = 500
        const val BAND_M = 0.2f
        const val SOFA_TOP = 0.8f
        val ROOM_MIN = Vec3(-3f, 0f, -2.5f)
        val ROOM_MAX = Vec3(3f, 2.6f, 2.5f)
        val SOFA_MIN = Vec3(-2.9f, 0f, -2.4f)
        val SOFA_MAX = Vec3(-1.9f, SOFA_TOP, -1.6f)
        val HEADER = ScanDepthHeader(maxPoints = 200_000, maxLevel = DenseFusion.MAX_LEVEL, farM = 5f, voxelM = DenseFusion.VOXEL_M)

        val scene = SyntheticScene(
            listOf(
                SyntheticShape.Room(ROOM_MIN, ROOM_MAX) { x, y, _ ->
                    // Planks 15 cm wide on the floor, pale walls.
                    if (y < 0.01f) {
                        if (Math.floorMod(floor(x / 0.15f).toInt(), 2) == 0) 0xFF9A6B43.toInt() else 0xFF8A5D38.toInt()
                    } else {
                        0xFFE8E2D6.toInt()
                    }
                },
                SyntheticShape.Box(SOFA_MIN, SOFA_MAX) { _, _, _ -> 0xFF4F6D8A.toInt() },
            ),
        )

        /**
         * 26 s in the room's far-left third, chest high: along the wall and back, the phone
         * sweeping from across the room to the corner, looking down at the floor and across.
         */
        val walk: List<Step> = List(FRAMES) { i ->
            val s = i / (FRAMES - 1f)
            val leg = if (s < 0.5f) s * 2f else (1f - s) * 2f
            val at = Vec3(-2.2f + 1.4f * leg, 1.4f, -0.4f - 0.6f * leg)
            val sweep = Math.toRadians(-60.0 + 150.0 * (0.5 + 0.5 * kotlin.math.sin(i * 0.12))).toFloat()
            val pitch = Math.toRadians(if (i % 3 == 0) -15.0 else -45.0).toFloat()
            Step(at, sweep, pitch)
        }
    }
}
