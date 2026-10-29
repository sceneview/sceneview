package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.OrbitPose
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.demos.internal.ScanFollow
import io.github.sceneview.demo.demos.internal.ScanOpenTop
import io.github.sceneview.demo.demos.internal.ScanSavingOrbit
import io.github.sceneview.demo.demos.internal.ScanSavingPath
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The save flight of a live scan (#4792): the live view draws the map merged far from the eye
 * ([DenseFusion.liveCloud]), dense where the scan ended and sparse across the room, and the
 * flight is planned round the map as it stands — its centre the room's, not the corner the phone
 * stopped in — and cut open at the room's floor.
 *
 * A room of 8 × 6 m, 2.5 m high, scanned whole; the phone stops in a corner, 1.4 m up, over a
 * stairwell going down — three treads under the floor, a sliver of the room, a good share of what
 * the live view draws by the eye.
 */
class ScanLiveOrbitTest {

    @Test
    fun `the save flight turns round the middle of the room, not where the scan ended`() {
        val live = snapshot.live
        val drawn = plan(live.cloud.positions)
        val map = plan(snapshot.map)
        val flown = plan(live.whole)
        // The live view draws a corner of the room whole and the rest merged: planned round what
        // it draws, the flight would turn round a point well off the room's middle.
        assertTrue("planned round the drawn surfels, ${off(drawn, map)} m off", off(drawn, map) > MIN_DRAWN_OFF_M)
        assertTrue("the save flight turned round a point ${off(flown, map)} m off the room's middle", off(flown, map) < MAX_OFF_M)
        assertEquals("the flight's reach", map.distance, flown.distance, map.distance * DISTANCE_SHARE)
    }

    @Test
    fun `the room is cut open at its floor, not at the stairs where the scan ended`() {
        val live = snapshot.live
        val floor = ScanOpenTop.floorOf(snapshot.map)!!
        // What the live view draws stands mostly by the eye, over the stairs.
        assertTrue(ScanOpenTop.floorOf(live.cloud.positions)!! < floor - STAIRS_BELOW_M)
        val open = live.openTop()
        val top = maxY(open.cloud.positions)
        assertEquals("the cut's height", floor + RoomDollhouse.CUTAWAY_HEIGHT_M, top, CUT_TOLERANCE_M)
        // The open top still plans the flight round the map, not round what it draws.
        assertTrue(open.whole === live.whole)
    }

    /** One live snapshot of the room, and the map it was taken from. */
    private class Snapshot(val live: ReplayDenseLayer, val map: FloatArray)

    private companion object {
        const val VOXEL_M = DenseFusion.VOXEL_M
        const val HALF_X_M = 4f
        const val HALF_Z_M = 3f
        const val HEIGHT_M = 2.5f

        /** Where the scan ended: a corner, 1.4 m up, over the stairwell. */
        val EYE = DebugPose(3.1f, 1.4f, 1.6f)

        /** The stairwell by the eye: three treads of 1 × 0.3 m, 20 cm apart, going down. */
        const val STAIRS_X0_M = 2.6f
        const val STAIRS_X1_M = 3.6f
        const val STAIRS_Z0_M = 0.9f
        const val TREAD_DEPTH_M = 0.3f
        const val TREAD_RISE_M = 0.2f
        const val TREADS = 3

        /** The stairs are deeper than this under the floor. */
        const val STAIRS_BELOW_M = 0.15f

        const val MIN_DRAWN_OFF_M = 0.5f
        const val MAX_OFF_M = 0.1f
        const val DISTANCE_SHARE = 0.03f
        const val CUT_TOLERANCE_M = 0.05f

        const val MAX_POINTS = 600_000
        const val GREY = 0xFF808080.toInt()

        val snapshot: Snapshot by lazy { scan() }

        /** The room fused twice into a live scan's dense map, a second apart, from [EYE]. */
        fun scan(): Snapshot {
            var now = 0L
            val dense = ScanDenseMap(maxPoints = MAX_POINTS, clock = { now })
            val room = room()
            val eye = DepthFrame(1, 1, shortArrayOf(0), null, IntArray(1), 1f, 1f, 0.5f, 0.5f, EYE)
            dense.add(dense.project(eye, now))
            dense.add(room)
            now += ScanDenseMap.LIVE_INTERVAL_NS
            dense.add(room)
            val live = checkNotNull(dense.live) { "no live snapshot" }
            val map = dense.fusion.cloud(minViews = DenseFusion.MIN_VIEWS).positions
            return Snapshot(live, map)
        }

        /** The room's surfels, one per 2 cm voxel: floor, walls, ceiling and the stairs' treads. */
        fun room(): DenseSamples {
            val cx = (2 * HALF_X_M / VOXEL_M).toInt()
            val cz = (2 * HALF_Z_M / VOXEL_M).toInt()
            val cy = (HEIGHT_M / VOXEL_M).toInt()
            val count = 2 * cx * cz + 2 * cy * (cx + cz)
            val p = FloatArray(count * 3)
            val n = FloatArray(count * 3)
            var o = 0
            fun add(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
                p[o * 3] = x
                p[o * 3 + 1] = y
                p[o * 3 + 2] = z
                n[o * 3] = nx
                n[o * 3 + 1] = ny
                n[o * 3 + 2] = nz
                o++
            }
            fun x(i: Int) = -HALF_X_M + (i + 0.5f) * VOXEL_M
            fun z(k: Int) = -HALF_Z_M + (k + 0.5f) * VOXEL_M
            fun y(j: Int) = (j + 0.5f) * VOXEL_M
            val stairsZ1 = STAIRS_Z0_M + TREADS * TREAD_DEPTH_M
            // One surface after the other, as a scan finds them: the map's stride and the floor's
            // sample step then read each surface evenly.
            for (i in 0 until cx) for (k in 0 until cz) {
                val x = x(i)
                val z = z(k)
                if (x in STAIRS_X0_M..STAIRS_X1_M && z in STAIRS_Z0_M..stairsZ1) {
                    val tread = ((z - STAIRS_Z0_M) / TREAD_DEPTH_M).toInt().coerceAtMost(TREADS - 1)
                    add(x, y(0) - (tread + 1) * TREAD_RISE_M, z, 0f, 1f, 0f)
                } else {
                    add(x, y(0), z, 0f, 1f, 0f)
                }
            }
            for (i in 0 until cx) for (k in 0 until cz) add(x(i), y(cy - 1), z(k), 0f, -1f, 0f)
            for (j in 0 until cy) for (i in 0 until cx) add(x(i), y(j), z(0), 0f, 0f, 1f)
            for (j in 0 until cy) for (i in 0 until cx) add(x(i), y(j), z(cz - 1), 0f, 0f, -1f)
            for (j in 0 until cy) for (k in 0 until cz) add(x(0), y(j), z(k), 1f, 0f, 0f)
            for (j in 0 until cy) for (k in 0 until cz) add(x(cx - 1), y(j), z(k), -1f, 0f, 0f)
            return DenseSamples(
                count = count,
                positions = p,
                normals = n,
                colors = IntArray(count) { GREY },
                confidences = ByteArray(count) { 0xFF.toByte() },
            )
        }

        /** The pose the save flight settles on, planned round [points] from [EYE]'s follow pose. */
        fun plan(points: FloatArray): OrbitPose {
            val phone = OrbitPose(
                target = Position(EYE.x, EYE.y, EYE.z),
                azimuthDegrees = 0f,
                elevationDegrees = ScanFollow.ELEVATION_DEGREES,
                distance = ScanFollow.DISTANCE_M,
            )
            val orbit = ScanSavingOrbit { 0L }
            orbit.poseFor(phone, points, ArDebugOrbitCamera(drift = false, band = ScanFollow.BAND))
            return orbit.flight!!.poseAt(ScanSavingPath.RISE_SECONDS + ScanSavingPath.OUT_SECONDS + 1f)
        }

        /** How far apart the two flights' centres are. */
        fun off(a: OrbitPose, b: OrbitPose): Float {
            val dx = a.target.x - b.target.x
            val dy = a.target.y - b.target.y
            val dz = a.target.z - b.target.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun maxY(points: FloatArray): Float = (1 until points.size step 3).maxOf { points[it] }
    }
}
