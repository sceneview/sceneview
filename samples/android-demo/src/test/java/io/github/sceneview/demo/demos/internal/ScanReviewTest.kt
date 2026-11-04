package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.SessionFix
import io.github.sceneview.demo.demos.internal.RescanZonesTest.Companion.filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Review before you leave, on the ray-cast room ([RerunSyntheticRoom]) fused as the capture's
 * dense map holds it at Stop ([RescanZonesTest.room]): the parts it finds, and Fix now filling
 * them in until the review comes back empty.
 */
class ScanReviewTest {
    @Test
    fun `a wall strip and a floor square missing are both found at review, the whole room has none`() {
        val whole = ScanReview.of(RescanZonesTest.room)
        assertEquals(whole.zones.joinToString("\n"), 0, whole.zones.size)
        assertTrue(whole.clear)

        val review = ScanReview.of(damaged)
        val report = review.zones.joinToString("\n")
        println("review: ${review.zones.size} parts in ${review.millis} ms on ${damaged.count} surfels\n$report")
        assertEquals(report, 2, review.zones.size)
        val wall = review.zones.single { it.kind == RescanKind.MissingWall }
        val hole = review.zones.single { it.kind == RescanKind.FloorHole }
        assertNear(report, wall, WALL_X, (WALL_Z0 + WALL_Z1) / 2f)
        assertEquals(report, WALL_Z1 - WALL_Z0, wall.size, WALL_TOLERANCE_M)
        assertNear(report, hole, (FLOOR_X0 + FLOOR_X1) / 2f, (FLOOR_Z0 + FLOOR_Z1) / 2f)
        assertEquals(report, (FLOOR_X1 - FLOOR_X0) * (FLOOR_Z1 - FLOOR_Z0), hole.size, HOLE_TOLERANCE_M2)
        // Walls first, as the result's list orders them.
        assertEquals(RescanKind.MissingWall, review.zones.first().kind)
    }

    @Test
    fun `fix now fills each part in and the review comes back empty`() {
        var scan = damaged
        val first = ScanReview.of(scan)
        assertEquals(2, first.zones.size)
        val t0 = 1_000_000_000L

        // Fix the wall: the capture as it stands has nothing there, the same capture scanned on does.
        val wall = first.zones.first()
        val wallFix = SessionFix.start(wall, scan, t0)
        assertTrue("baseline ${wallFix.watch.baseline}", wallFix.watch.baseline < RescanGuidance.COVERED_SHARE)
        scan = withWall
        wallFix.follow(scan)
        val wallFill = (wallFix.status as io.github.sceneview.demo.demos.RescanStatus.Lined).fill
        assertTrue("fill $wallFill", RescanGuidance.covered(wallFill))
        assertFalse("never straight back", wallFix.watch.done(wallFill, t0 + NS / 2))
        assertTrue(wallFix.watch.done(wallFill, t0 + LATER_NS))

        val second = ScanReview.of(scan)
        assertEquals(second.zones.joinToString("\n"), 1, second.zones.size)
        assertEquals(RescanKind.FloorHole, second.zones.single().kind)

        // Fix the floor: the review is empty.
        val floorFix = SessionFix.start(second.zones.single(), scan, t0)
        scan = RescanZonesTest.room
        floorFix.follow(scan)
        val floorFill = (floorFix.status as io.github.sceneview.demo.demos.RescanStatus.Lined).fill
        assertTrue(floorFix.watch.done(floorFill, t0 + LATER_NS))
        assertTrue(ScanReview.of(scan).clear)
    }

    @Test
    fun `a sparse patch fixed three quarters of the way is not offered again as it was`() {
        val review = ScanReview.of(sparse)
        val patch = review.zones.single { it.kind == RescanKind.LowDensity }
        // Measured by a point per small cell, the patch starts covered: the fix would say
        // Covered on its first frame and the review would find it again, forever.
        assertTrue(RescanGuidance.covered(RescanGuidance.fill(patch.kind, patch.box, sparse)))
        // Measured as the review found it, it starts empty.
        val fix = SessionFix.start(patch, sparse, 0L, review) { LATER_NS }
        assertTrue("baseline ${fix.watch.baseline}", fix.watch.baseline < ScanFixWatch.MIN_GAIN)
        assertFalse(fix.done(fix.watch.baseline))

        // Three quarters of it scanned again at full density.
        val partly = partlyFixed(patch)
        fix.follow(partly)
        val fill = (fix.status as io.github.sceneview.demo.demos.RescanStatus.Lined).fill
        println("sparse patch ${patch.footprintM2} m², fill ${fix.watch.baseline} -> $fill")
        assertTrue("fill $fill", fill in 0.6f..0.95f)

        // Whatever is still thin there is set aside, never offered again over the tried part.
        val again = ScanReview.of(partly).without(listOf(patch))
        val back = again.zones.filter { it.overlapM2(patch.box) > 0f }
        assertTrue(again.zones.joinToString("\n"), back.isEmpty())
    }

    @Test
    fun `a part that cannot gain is never done, whatever it started at`() {
        val zone = RescanZone(RescanKind.FloorHole, floatArrayOf(0f, 0f, 0f, 1f, 0.05f, 1f), 1f, "hole")
        val full = ScanFixWatch(zone, baseline = 0.99f, startedNanos = 0L)
        assertFalse(full.done(1f, LATER_NS))
    }

    @Test
    fun `the read at Stop on a full budget of surfels`() {
        // DenseFusion.MAX_BUDGET (600 k) is the most a phone's map holds: the room twice, a
        // millimetre apart, stands in for it.
        val room = RescanZonesTest.room
        val n = room.count
        val twice = DenseCloud(
            positions = FloatArray(n * 6) { if (it < n * 3) room.positions[it] else room.positions[it - n * 3] + 0.001f },
            colors = IntArray(n * 2) { room.colors[it % n] },
            normals = room.normals?.let { r -> FloatArray(n * 6) { r[it % (n * 3)] } },
            levels = room.levels?.let { l -> ByteArray(n * 2) { l[it % n] } },
        )
        ScanReview.of(twice) // warm up
        val review = ScanReview.of(twice)
        println("review at ${twice.count} surfels: ${review.zones.size} parts in ${review.millis} ms")
        assertTrue(review.millis < SLOW_MS)
    }

    @Test
    fun `the view looks at a wall part from inside the room, at a floor part from above`() {
        val review = ScanReview.of(damaged)
        val wall = review.zones.single { it.kind == RescanKind.MissingWall }
        val hole = review.zones.single { it.kind == RescanKind.FloorHole }
        val fov = 45.0
        val atWall = ScanReview.poseFor(wall, review, fov)
        val eye = CameraRig.eye(atWall)
        // The wall is at x = 2: the eye stands on the room's side of it, inside its footprint.
        assertTrue("eye $eye", eye.x < WALL_X - 0.5f && eye.x > RerunSyntheticRoom.MIN.x)
        assertEquals(ScanReview.WALL_ELEVATION, atWall.elevationDegrees, 0f)
        assertTrue(atWall.distance in ScanReview.MIN_DISTANCE_M..ScanReview.MAX_DISTANCE_M)
        val atHole = ScanReview.poseFor(hole, review, fov)
        assertEquals(ScanReview.FLOOR_ELEVATION, atHole.elevationDegrees, 0f)
        assertEquals(hole.box[1], atHole.target.y, 1e-4f)
        assertEquals(hole.centre[0], atHole.target.x, 1e-4f)
        assertEquals(hole.centre[1], atHole.target.z, 1e-4f)
    }

    @Test
    fun `the view rests on each part in turn, round and round`() {
        assertEquals(0, ScanReview.focusAt(0f, 2))
        assertEquals(0, ScanReview.focusAt(ScanReview.DWELL_S - 0.1f, 2))
        assertEquals(1, ScanReview.focusAt(ScanReview.DWELL_S + 0.1f, 2))
        assertEquals(0, ScanReview.focusAt(2 * ScanReview.DWELL_S + 0.1f, 2))
        assertEquals(0, ScanReview.focusAt(10f, 1))
        assertEquals(0, ScanReview.focusAt(10f, 0))
    }

    @Test
    fun `a part already full when the fix starts has to fill in more`() {
        val zone = RescanZone(RescanKind.LowDensity, floatArrayOf(0f, 0f, 0f, 1f, 0.05f, 1f), 1f, "sparse")
        val watch = ScanFixWatch(zone, baseline = 0.9f, startedNanos = 0L)
        assertFalse(watch.done(0.92f, LATER_NS))
        assertTrue(watch.done(1f, LATER_NS))
        assertFalse("not before it scanned a while", watch.done(1f, NS))
        val empty = ScanFixWatch(zone, baseline = 0f, startedNanos = 0L)
        assertFalse("under the covered share", empty.done(0.5f, LATER_NS))
        assertTrue(empty.done(0.8f, LATER_NS))
    }

    @Test
    fun `no dense map, nothing to review`() {
        assertTrue(ScanReview.of(null).clear)
        assertTrue(ScanReview.of(DenseCloud.Empty).clear)
    }

    @Test
    fun `the card's words`() {
        assertEquals("1 part to fix before you leave", ScanReviewCopy.title(1))
        assertEquals("2 parts to fix before you leave", ScanReviewCopy.title(2))
        val wall = RescanZone(RescanKind.MissingWall, floatArrayOf(0f, 0f, 0f, 1f, 2f, 0.3f), 0.84f, "")
        assertEquals("Missing wall · 0.8 m", ScanReviewCopy.part(wall))
        assertEquals("Part 2 of 3 · Missing wall · 0.8 m", ScanReviewCopy.focus(wall, 1, 3))
        assertEquals("Missing wall · 0.8 m", ScanReviewCopy.focus(wall, 0, 1))
    }

    private fun assertNear(report: String, zone: RescanZone, x: Float, z: Float) {
        val c = zone.centre
        assertTrue(
            "${zone.kind} at ${c[0]}, ${c[1]}, not near $x, $z\n$report",
            abs(c[0] - x) <= CENTRE_TOLERANCE_M && abs(c[1] - z) <= CENTRE_TOLERANCE_M,
        )
    }

    companion object {
        /** A strip of the right wall (x = 2), floor to ceiling, clear of the cabinet. */
        const val WALL_X = 2f
        const val WALL_Z0 = -1.3f
        const val WALL_Z1 = -0.4f

        /** A square of open floor, nothing standing over it. */
        const val FLOOR_X0 = -0.45f
        const val FLOOR_X1 = 0.2f
        const val FLOOR_Z0 = 0.45f
        const val FLOOR_Z1 = 1.1f
        const val FLOOR_TOP_M = 0.1f

        private const val CENTRE_TOLERANCE_M = 0.2f
        private const val WALL_TOLERANCE_M = 0.25f
        private const val HOLE_TOLERANCE_M2 = 0.15f
        private const val NS = 1_000_000_000L
        private val LATER_NS = (ScanFixWatch.MIN_SECONDS * NS).toLong() + NS
        private const val SLOW_MS = 10_000L

        /** The near-right corner kept one point in ten, at every height: a sparse patch. */
        private const val SPARSE_X0 = 1.0f
        private const val SPARSE_X1 = 2.1f
        private const val SPARSE_Z0 = -1.9f
        private const val SPARSE_Z1 = -0.8f
        private const val SPARSE_EVERY = 10
        private fun inSparse(x: Float, z: Float) = x in SPARSE_X0..SPARSE_X1 && z in SPARSE_Z0..SPARSE_Z1

        val sparse: DenseCloud by lazy {
            var kept = 0
            RescanZonesTest.room.filter { x, _, z -> !inSparse(x, z) || kept++ % SPARSE_EVERY == 0 }
        }

        /** [sparse] with the three quarters of [patch] nearest the room's middle (lower x) scanned again. */
        fun partlyFixed(patch: RescanZone): DenseCloud {
            var kept = 0
            val edge = patch.box[0] + PARTLY * (patch.box[3] - patch.box[0])
            return RescanZonesTest.room.filter { x, _, z -> !inSparse(x, z) || x < edge || kept++ % SPARSE_EVERY == 0 }
        }

        private const val PARTLY = 0.75f

        private fun inWall(x: Float, z: Float) = x > WALL_X - 0.1f && z in WALL_Z0..WALL_Z1
        private fun inFloor(x: Float, y: Float, z: Float) = y < FLOOR_TOP_M && x in FLOOR_X0..FLOOR_X1 && z in FLOOR_Z0..FLOOR_Z1

        /** The room with the strip of wall and the square of floor never seen. */
        val damaged: DenseCloud by lazy { RescanZonesTest.room.filter { x, y, z -> !inWall(x, z) && !inFloor(x, y, z) } }

        /** …once the wall is fixed: only the floor is still missing. */
        val withWall: DenseCloud by lazy { RescanZonesTest.room.filter { x, y, z -> !inFloor(x, y, z) } }
    }
}
