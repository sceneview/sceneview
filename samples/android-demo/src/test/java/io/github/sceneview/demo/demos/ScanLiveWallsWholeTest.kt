package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.LivePlane
import io.github.sceneview.demo.demos.internal.LiveWall
import io.github.sceneview.demo.demos.internal.LiveWallSnapshot
import io.github.sceneview.demo.demos.internal.LiveWallSource
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanWalkthrough
import io.github.sceneview.demo.demos.internal.WallExtent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 3D Scan's live walls cut back to the depth map (#4540), along the live view's own path: the
 * synthetic walkthrough's depth frames through the capture's dense map at the capture's cadence,
 * each live snapshot handed to [ScanWallsState.observe] as the live view hands it, and each wall
 * drawn set against the same wall cut back to every point of the cloud the scan saves
 * ([WallExtent.clip]).
 *
 * The walls are the rooms' own, found [OVER_M] long at both ends, so only the cut moves them. The
 * live view draws the far surfaces merged, one surfel per coarse cell ([DenseFusion.liveCloud]):
 * dense by the eye and sparse away from it, so a wall cut back to those points lost the length
 * of its far end, and a wall the scan had left moved each time the eye did.
 */
class ScanLiveWallsWholeTest {

    @Test
    fun `a wall seen far from the camera is drawn as far as the map saw it`() {
        val far = walk.seen.filter { it.room < it.eyeRoom && it.fromEye > FAR_M }
        assertTrue("the walk leaves rooms behind", far.size >= MIN_FAR_SEEN)
        val worst = far.maxBy { it.error }
        assertTrue(
            "of ${far.size} walls drawn more than $FAR_M m from the eye, the worst ended " +
                "${"%.3f".format(worst.error)} m off the map's cut (mean ${"%.3f".format(far.meanError())} m): $worst",
            worst.error <= TOLERANCE_M,
        )
    }

    @Test
    fun `the synthetic room's walls are drawn as far as the map saw them, all along the scan`() {
        val seen = room.seen
        val worst = seen.maxBy { it.error }
        assertTrue(
            "of ${seen.size} walls drawn, the worst ended ${"%.3f".format(worst.error)} m off the map's cut " +
                "(mean ${"%.3f".format(seen.meanError())} m): $worst",
            worst.error <= TOLERANCE_M,
        )
    }

    @Test
    fun `every wall found is drawn, cut back, and turned by nothing`() {
        for (session in listOf(room, walk)) {
            assertEquals(session.found.size, session.last.size)
            session.last.forEachIndexed { k, wall ->
                assertTrue("wall $k was cut back", wall.length < session.found[k].length - OVER_M)
            }
            val turned = session.seen.maxOf { it.turned }
            assertTrue("a wall turned by $turned", turned <= MAX_TURN)
        }
    }

    @Test
    fun `the cut reads the map evenly, no more of it than one read`() {
        val layer = walk.layer
        val whole = layer.whole
        val n = whole.size / 3
        assertTrue("the walk's map holds $n points, more than one read", n > WallExtent.MAX_READ)
        val read = ScanWallsState.depthOf(layer)
        val stride = (n + WallExtent.MAX_READ - 1) / WallExtent.MAX_READ
        assertEquals("points read of $n", (n + stride - 1) / stride, read.size / 3)
        for (k in 0 until read.size / 3) {
            for (c in 0..2) assertEquals("point $k", whole[k * stride * 3 + c], read[k * 3 + c])
        }
        val small = ReplayDenseLayer(
            layer.cloud, layer.voxelM, layer.mesh, layer.atlas, whole = whole.copyOf(WallExtent.MAX_READ * 3),
        )
        assertSame("a map of one read is read as it is", small.whole, ScanWallsState.depthOf(small))
    }

    /**
     * Wall [wall] of room [room], drawn at one live snapshot with the eye in room [eyeRoom],
     * [fromEye] metres from its middle: [error], how far its farther end sits from the map's cut,
     * metres along the wall, and [turned], the sine of the angle it turned from the wall found.
     */
    private data class Seen(
        val room: Int,
        val wall: Int,
        val eyeRoom: Int,
        val fromEye: Float,
        val error: Float,
        val turned: Float,
    )

    private class Session(val found: List<LiveWall>, val seen: List<Seen>, val last: List<LiveWall>, val layer: ReplayDenseLayer)

    /** The [walls] found from the first frame on, as the planes would find them. */
    private class Found(walls: List<LiveWall>) : LiveWallSource {
        private val snapshot = LiveWallSnapshot(walls, floorY = 0f, height = RerunSyntheticRoom.MAX.y)
        override fun update(planes: List<LivePlane>, floorY: Float, time: Float, tracking: Boolean) = snapshot
        override fun reset() = Unit
    }

    private companion object {
        /** How much longer than its room each wall is found, at each end. */
        const val OVER_M = 0.9f

        /** A wall's middle this far from the eye: past the live view's full-detail radius, twice over. */
        const val FAR_M = 4f

        /**
         * How far a drawn end may sit from the map's cut: half the live view's coarsest merged cell
         * ([DenseFusion.VOXEL_M] at level 3, 16 cm), the most a cell's centroid moves a wall's end.
         */
        const val TOLERANCE_M = 0.08f

        const val MIN_FAR_SEEN = 100
        const val MAX_TURN = 1e-4f
        const val PHONE_BYTES = 256L shl 20
        const val NANOS_PER_S = 1e9f
        const val WALLS_PER_ROOM = 4

        /** The whole synthetic room, scanned on its own. */
        val room: Session by lazy { replay(rooms = 1, yawSteps = 30) }

        /** The four rooms of the walkthrough, one after the other: the first ones left behind. */
        val walk: Session by lazy { replay(rooms = ScanWalkthrough.ROOMS, yawSteps = 27) }

        fun replay(rooms: Int, yawSteps: Int): Session {
            val walkthrough = ScanWalkthrough(yawSteps = yawSteps, revisit = false)
            val found = (0 until rooms).flatMap { walls(walkthrough, it) }
            val walls = ScanWallsState(Found(found))
            val coverage = ScanCoverageState()
            var now = 0L
            val dense = ScanDenseMap(maxPoints = DenseFusion.budgetFor(PHONE_BYTES), clock = { now })
            val seen = ArrayList<Seen>()
            var layer: ReplayDenseLayer? = null
            for (i in 0 until walkthrough.frameCount / ScanWalkthrough.ROOMS * rooms) {
                now += ScanCapture.DEPTH_INTERVAL_NS
                val depth = walkthrough.frame(i)
                val before = dense.live
                dense.add(dense.project(depth, now, farM = ScanPreset.ROOM.farM))
                val live = dense.live ?: continue
                if (live === before) continue
                layer = live
                val pose = depth.pose
                val frame = ArDebugFrame(
                    now / NANOS_PER_S, FloatArray(0), pose, FloatArray(0), FloatArray(0), emptyList(), emptyList(),
                )
                walls.observe(frame, coverage, pose, record = null, dense = live)
                val map = WallExtent.clip(found, pieces(dense.savedCloud().positions), 0f, RerunSyntheticRoom.MAX.y)
                val eyeRoom = walkthrough.roomAt(i)
                walls.snapshot.walls.forEachIndexed { k, drawn ->
                    val wall = found[k]
                    val room = k / WALLS_PER_ROOM
                    if (room > eyeRoom) return@forEachIndexed
                    val error = max(
                        abs(along(wall, drawn.ax, drawn.az) - along(wall, map[k].ax, map[k].az)),
                        abs(along(wall, drawn.bx, drawn.bz) - along(wall, map[k].bx, map[k].bz)),
                    )
                    val fromEye = hypot((wall.ax + wall.bx) / 2f - pose.x, (wall.az + wall.bz) / 2f - pose.z)
                    seen += Seen(room, k % WALLS_PER_ROOM, eyeRoom, fromEye, error, turned(wall, drawn))
                }
            }
            return Session(found, seen, walls.snapshot.walls, checkNotNull(layer))
        }

        /** Room [room]'s four walls, [OVER_M] longer than the room at both ends. */
        fun walls(walk: ScanWalkthrough, room: Int): List<LiveWall> {
            val o = walk.offset(room)
            val x0 = o.x + RerunSyntheticRoom.MIN.x
            val x1 = o.x + RerunSyntheticRoom.MAX.x
            val z0 = o.z + RerunSyntheticRoom.MIN.z
            val z1 = o.z + RerunSyntheticRoom.MAX.z
            val sides = listOf(
                floatArrayOf(x0, z0, x1, z0),
                floatArrayOf(x0, z1, x1, z1),
                floatArrayOf(x0, z0, x0, z1),
                floatArrayOf(x1, z0, x1, z1),
            )
            return sides.mapIndexed { k, s ->
                val l = hypot(s[2] - s[0], s[3] - s[1])
                val ux = (s[2] - s[0]) / l
                val uz = (s[3] - s[1]) / l
                LiveWall(
                    room * WALLS_PER_ROOM + k,
                    s[0] - ux * OVER_M, s[1] - uz * OVER_M, s[2] + ux * OVER_M, s[3] + uz * OVER_M,
                    bornAt = 0f,
                )
            }
        }

        /** [cloud] in pieces the cut reads whole ([WallExtent.MAX_READ]): every point counts. */
        fun pieces(cloud: FloatArray): List<FloatArray> = (0 until cloud.size step WallExtent.MAX_READ * 3).map {
            cloud.copyOfRange(it, minOf(cloud.size, it + WallExtent.MAX_READ * 3))
        }

        /** How far along [wall], from its start, [x], [z] lies. */
        fun along(wall: LiveWall, x: Float, z: Float): Float =
            ((x - wall.ax) * (wall.bx - wall.ax) + (z - wall.az) * (wall.bz - wall.az)) / wall.length

        /** The sine of the angle [drawn] turned from [wall]. */
        fun turned(wall: LiveWall, drawn: LiveWall): Float {
            val cross = (wall.bx - wall.ax) * (drawn.bz - drawn.az) - (wall.bz - wall.az) * (drawn.bx - drawn.ax)
            return abs(cross) / (wall.length * max(drawn.length, 1e-6f))
        }

        fun List<Seen>.meanError(): Double = sumOf { it.error.toDouble() } / max(size, 1)
    }
}
