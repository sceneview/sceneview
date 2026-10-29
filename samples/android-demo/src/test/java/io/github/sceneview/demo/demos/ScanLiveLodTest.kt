package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanWalkthrough
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * 3D Scan's live view over a scan the length of a real one (#4743): two rooms of the synthetic
 * walkthrough, 48.6 s of depth frames through the capture's own dense map at the capture's
 * cadence, the live snapshot looked at from the eye that scanned it, as the live card draws it
 * ([LiveDrawLoad]).
 *
 * The live card draws a bounded number of surfels, the near ones as the map holds them, and
 * still covers what the camera sees; the floor cells the snapshot hands the live map, and the
 * cloud the scan saves, are the map's own, whatever the card draws.
 */
class ScanLiveLodTest {

    /**
     * One live snapshot, against the map it was taken from: the surfels it draws, those of the map
     * within [NEAR_M] of the eye, how many of them it draws elsewhere or not at all, the map's
     * confirmed surfels, and whether it hands over the floor cells the map's own stride covers.
     */
    private class Snapshot(val drawn: Int, val near: Int, val missing: Int, val confirmed: Int, val sameCells: Boolean)

    private class Session(val snapshots: List<Snapshot>, val loads: List<LiveDrawLoad>, val saved: DenseCloud)

    @Test
    fun `the live view draws 50 000 surfels at most once a depth frame gave it an eye`() {
        val snapshots = session.snapshots
        assertTrue("the scan takes a live snapshot every second", snapshots.size >= SNAPSHOTS)
        val largest = snapshots.maxOf { it.drawn }
        assertTrue("the live view drew $largest surfels", largest <= DRAWN_SURFELS)
        // Two rooms: the map holds several times what the card draws.
        assertTrue(snapshots.last().confirmed > 4 * DRAWN_SURFELS)
    }

    @Test
    fun `the live view still covers the screen from the eye that scans`() {
        val loads = session.loads
        val coverage = loads.map { it.coverage.toDouble() }.pct(P50)
        val overdraw = loads.map { it.overdraw.toDouble() }.pct(P50)
        assertTrue("the live view covered ${"%.3f".format(coverage)} of the screen", coverage >= MIN_COVERAGE)
        assertTrue("the live view drew ${"%.2f".format(overdraw)} fragments a pixel", overdraw <= MAX_OVERDRAW)
    }

    @Test
    fun `the live view draws every surfel within a metre of the eye as the map holds it`() {
        val snapshots = session.snapshots
        val missing = snapshots.sumOf { it.missing }
        val near = snapshots.sumOf { it.near }
        assertTrue("the eye scans its surroundings at arm's length", near > 0)
        assertEquals("of $near surfels within $NEAR_M m of the eye, left out of the live view", 0, missing)
    }

    @Test
    fun `a live snapshot hands the live map the floor cells of the map, as before`() {
        val snapshots = session.snapshots
        assertEquals(snapshots.size, snapshots.count { it.sameCells })
    }

    @Test
    fun `the live snapshots leave the saved cloud as a map that never took one saves it`() {
        val without = replay(snapshotEverySecond = false).saved
        val with = session.saved
        assertEquals(without.count, with.count)
        assertArrayEquals(without.positions, with.positions, 0f)
        assertArrayEquals(without.colors, with.colors)
        assertArrayEquals(without.normals, with.normals, 0f)
        assertArrayEquals(without.levels, with.levels)
    }

    private companion object {
        /** Two rooms of the walkthrough, 243 depth frames each: 48.6 s at 10 frames a second. */
        const val YAW_STEPS = 27
        const val FRAMES = 486
        const val SNAPSHOTS = 40

        /** What the live card may draw: 200 k vertices. */
        const val DRAWN_SURFELS = 50_000

        /** At arm's length and closer, the live card draws the map's own surfels. */
        const val NEAR_M = 1f

        /** The stride drawn before left 0.706 of the screen covered (p50) and 1.13 fragments a pixel. */
        const val MIN_COVERAGE = 0.9

        /**
         * The merged surfels overlap: 3.14 fragments a pixel at p50 when this bound was set (#4792).
         * The walkthrough is synthetic and has no noise, so the figure moves only when the code
         * does. The bound stays 10 % above it on purpose: a change that makes the live view draw
         * more fragments than this is measured on a phone before the bound is raised.
         */
        const val MAX_OVERDRAW = 3.5

        const val P50 = 0.5

        val session: Session by lazy { replay(snapshotEverySecond = true) }

        /**
         * The walkthrough through a phone-budget dense map; with [snapshotEverySecond] false the
         * map's clock stands still and it takes its first live snapshot only.
         */
        fun replay(snapshotEverySecond: Boolean): Session {
            val walk = ScanWalkthrough(yawSteps = YAW_STEPS, revisit = false)
            var now = 0L
            var mapNow = 0L
            val dense = ScanDenseMap(maxPoints = DenseFusion.budgetFor(256L shl 20), clock = { mapNow })
            val farM = ScanPreset.ROOM.farM
            val snapshots = ArrayList<Snapshot>()
            val loads = ArrayList<LiveDrawLoad>()
            for (i in 0 until minOf(FRAMES, walk.frameCount)) {
                now += ScanCapture.DEPTH_INTERVAL_NS
                if (snapshotEverySecond) mapNow = now
                val frame = walk.frame(i)
                val before = dense.live
                dense.add(dense.project(frame, now, farM = farM))
                val live = dense.live ?: continue
                if (snapshotEverySecond && live !== before) snapshots += snapshot(dense, live, frame.pose)
                if (snapshotEverySecond) loads += LiveDrawLoad.of(live.cloud, live.voxelM, frame.pose)
            }
            return Session(snapshots, loads, dense.savedCloud())
        }

        /** [live], just taken from [eye], against the map [dense] holds now. */
        fun snapshot(dense: ScanDenseMap, live: ReplayDenseLayer, eye: DebugPose): Snapshot {
            val fusion = dense.fusion
            val drawn = HashSet<Triple<Float, Float, Float>>(live.cloud.count * 2)
            val p = live.cloud.positions
            for (i in 0 until live.cloud.count) drawn += Triple(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
            val map = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
            val m = map.positions
            var near = 0
            var missing = 0
            for (i in 0 until map.count) {
                val dx = m[i * 3] - eye.x
                val dy = m[i * 3 + 1] - eye.y
                val dz = m[i * 3 + 2] - eye.z
                if (dx * dx + dy * dy + dz * dz >= NEAR_M * NEAR_M) continue
                near++
                if (Triple(m[i * 3], m[i * 3 + 1], m[i * 3 + 2]) !in drawn) missing++
            }
            val stride = fusion.cloud(minViews = DenseFusion.MIN_VIEWS, maxPoints = ScanDenseMap.LIVE_MAX_SURFELS)
            val cells = CoverageGrid.coveredKeys(stride.positions, stride.count)
            return Snapshot(live.cloud.count, near, missing, map.count, cells.contentEquals(live.coveredCells))
        }

        /** Nearest rank. */
        fun List<Double>.pct(p: Double): Double {
            val sorted = sorted()
            return sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        }
    }
}
