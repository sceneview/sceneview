package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanDetail
import io.github.sceneview.demo.demos.internal.ScanPhase
import io.github.sceneview.demo.demos.internal.ScanWalkthrough
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * #4629: what the capture screen says of a long depth scan. Its one count is the points the
 * saved scan keeps; it is never shown against a cap; the coaching line says "Adjusting detail"
 * for a few seconds when the map makes room, and only a map that truly cannot grow says it is
 * full.
 */
class ScanDenseMapTest {
    private var now = 0L
    private fun map(maxPoints: Int, maxLevel: Int = DenseFusion.MAX_LEVEL) =
        ScanDenseMap(maxPoints = maxPoints, maxLevel = maxLevel, clock = { now })

    @Test
    fun `a whole flat on the smallest budget never shows a limit, and the count is the saved one`() {
        val dense = map(DenseFusion.MIN_BUDGET)
        val walk = ScanWalkthrough(yawSteps = YAW_STEPS)
        val seen = HashSet<ScanDetail>()
        val shown = HashSet<String>()
        var last = 0
        for (i in 0 until walk.frameCount) {
            now += ScanCapture.DEPTH_INTERVAL_NS
            dense.add(DepthBackProjection.project(walk.frame(i)))
            seen += dense.detail()
            coaching(lost = false, photos = false, detail = dense.detail())?.let(shown::add)
            val hud = ScanCopy.pointsCaptured(dense.count)
            assertFalse(hud, hud.contains("/") || hud.contains("limit", ignoreCase = true))
            last = dense.count
        }
        assertFalse("never full on an ordinary flat", ScanDetail.Full in seen)
        assertTrue("it did make room, and said so", ScanDetail.Adjusting in seen)
        assertFalse(ScanCopy.SCAN_FULL in shown)
        assertTrue(ScanCopy.ADJUSTING_DETAIL in shown)
        assertEquals(dense.savedCloud().count, last)
        assertTrue(dense.fusion.count <= DenseFusion.MIN_BUDGET)
        val live = dense.live!!.cloud.count
        assertTrue("the live card draws at most its own share: $live", live in 1..ScanDenseMap.LIVE_MAX_SURFELS)
    }

    @Test
    fun `the HUD's count never drops when the map makes room (#4717)`() {
        val dense = map(maxPoints = 2_000)
        val walk = ScanWalkthrough(yawSteps = YAW_STEPS)
        var shown = 0
        var frame = 0
        while (dense.fusion.compactions < 2 && frame < walk.frameCount) {
            now += ScanCapture.DEPTH_INTERVAL_NS
            dense.add(dense.project(walk.frame(frame++), now))
            assertTrue("${dense.captured} after $shown", dense.captured >= shown)
            shown = dense.captured
        }
        assertTrue("the map did make room", dense.fusion.compactions >= 1)
        // What the saved scan keeps can be fewer: the coarsened regions merged their points.
        assertTrue(dense.captured >= dense.count)
    }

    @Test
    fun `"Adjusting detail" shows for a few seconds after the map makes room, then goes`() {
        val dense = map(maxPoints = 2_000)
        assertEquals(ScanDetail.Fine, dense.detail())
        var frame = 0
        while (dense.fusion.compactions == 0) {
            now += ScanCapture.DEPTH_INTERVAL_NS
            dense.add(plane(frame++))
        }
        assertEquals(ScanDetail.Adjusting, dense.detail())
        assertEquals(ScanDetail.Adjusting, dense.detail(now + ScanDenseMap.ADJUSTING_SHOWN_NS - 1))
        assertEquals(ScanDetail.Fine, dense.detail(now + ScanDenseMap.ADJUSTING_SHOWN_NS))
    }

    @Test
    fun `a map that cannot coarsen any more says the scan is full, above the photo limit`() {
        val dense = map(maxPoints = 50, maxLevel = 0)
        for (frame in 0 until 3) dense.add(plane(frame))
        assertEquals(ScanDetail.Full, dense.detail())
        assertEquals(
            ScanCopy.SCAN_FULL,
            coaching(lost = false, photos = true, detail = ScanDetail.Full),
        )
        // A lost track still speaks first; a paused scan still says it is paused.
        assertEquals(
            ScanCopy.TRACKING_LOST,
            coaching(lost = true, photos = false, detail = ScanDetail.Full),
        )
        assertEquals(
            ScanCopy.COACH_PAUSED,
            coaching(lost = false, photos = false, detail = ScanDetail.Full, phase = ScanPhase.Paused),
        )
    }

    @Test
    fun `the timeline's depth stats are rewritten to the saved cloud's prefix, the last one equal to it`() {
        val dense = map(maxPoints = 2_000)
        val events = ArrayList<ArDebugEvent>()
        val atFrames = ArrayList<Int>()
        for (frame in 0 until FRAMES) {
            now += ScanCapture.DEPTH_INTERVAL_NS
            dense.add(plane(frame))
            events += ArDebugEvent.DepthStats(now, added = 0, kept = 0, total = dense.count)
            atFrames += dense.frames
        }
        assertTrue(dense.fusion.compactions > 0)
        val revealed = dense.revealed(events, atFrames.toIntArray()).map { (it as ArDebugEvent.DepthStats).total }
        for (k in 1 until revealed.size) assertTrue(revealed[k] >= revealed[k - 1])
        assertEquals(dense.savedCloud().count, revealed.last())
        // A compaction merged points the live count had: the reveal is not the live count.
        assertNotEquals(events.map { (it as ArDebugEvent.DepthStats).total }, revealed)
    }

    @Test
    fun `the live view draws a point the map made coarser as wide as its level, leaving no holes`() {
        val dense = map(maxPoints = 2_000)
        var frame = 0
        while (dense.fusion.compactions < 2 && frame < FRAMES) {
            now += ScanDenseMap.LIVE_INTERVAL_NS
            dense.add(plane(frame++))
        }
        val live = dense.live!!
        val coarsest = (0 until live.cloud.count).maxOf { live.cloud.levelOf(it) }
        assertTrue("part of the map is coarser now", coarsest > 0)
        // A quad's first two corners span one side of it (DenseSurfels.mesh).
        val p = live.mesh.positions
        var widest = 0f
        for (q in 0 until live.mesh.vertexCount / 4) {
            val a = q * 4 * 3
            val dx = p[a + 3] - p[a]
            val dy = p[a + 4] - p[a + 1]
            val dz = p[a + 5] - p[a + 2]
            widest = maxOf(widest, sqrt(dx * dx + dy * dy + dz * dz))
        }
        // A merged point stands for its whole cell: drawn at the finest size, the wall between
        // it and its neighbours shows through. Its size on screen is capped by the shader.
        val coarse = 2f * DenseFusion.VOXEL_M * DenseSurfels.HALF_SIDE_VOXELS * (1 shl coarsest)
        assertEquals("the widest point over the camera", coarse, widest, 1e-5f)
    }

    private fun coaching(lost: Boolean, photos: Boolean, detail: ScanDetail, phase: ScanPhase = ScanPhase.Scanning) =
        ScanCopy.coaching(phase, trackingLost = lost, photoLimitReached = photos, detail = detail)

    /** Frame [frame] of a floor: a fresh 30 × 30 patch of 2 cm voxels, and the last one seen again. */
    private fun plane(frame: Int): DenseSamples {
        val side = PATCH
        val n = side * side * 2
        val positions = FloatArray(n * 3)
        for (copy in 0 until 2) {
            val patch = (frame - copy).coerceAtLeast(0)
            for (i in 0 until side * side) {
                val o = (copy * side * side + i) * 3
                positions[o] = patch * side * DenseFusion.VOXEL_M + (i % side) * DenseFusion.VOXEL_M + 0.01f
                positions[o + 1] = 0f
                positions[o + 2] = (i / side) * DenseFusion.VOXEL_M + 0.01f
            }
        }
        val normals = FloatArray(n * 3) { if (it % 3 == 1) 1f else 0f }
        return DenseSamples(n, positions, normals, IntArray(n) { COLOR }, ByteArray(n) { -1 })
    }

    private companion object {
        const val YAW_STEPS = 12
        const val PATCH = 30
        const val FRAMES = 40
        const val COLOR = 0xFF336699.toInt()
    }
}
