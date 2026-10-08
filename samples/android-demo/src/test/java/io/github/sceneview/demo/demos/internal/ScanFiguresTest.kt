package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins what a scan in progress says about itself: how much surface it has found (an area, not
 * ARCore's count of plane objects, which falls every time two patches of one floor merge), what
 * its line reads as the point budget comes into sight and is spent, and the one notice that
 * names a spent budget. A filmed scan stopped counting at 500,000 points and said nothing.
 */
class ScanFiguresTest {

    // ── Surfaces found ────────────────────────────────────────────────────────

    @Test
    fun `a floor, a wall and a tilted patch each measure their own area`() {
        val floor = floatArrayOf(0f, 0f, 0f, 3f, 0f, 0f, 3f, 0f, 2f, 0f, 0f, 2f)
        val wall = floatArrayOf(1f, 0f, 5f, 1f, 2.5f, 5f, 1f, 2.5f, 9f, 1f, 0f, 9f)
        // A 1 × √2 m rectangle leaning at 45°.
        val ramp = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, 1f, 1f)

        assertEquals(6f, ArDebugStats.polygonArea(floor), 1e-4f)
        assertEquals(10f, ArDebugStats.polygonArea(wall), 1e-4f)
        assertEquals(1.41421f, ArDebugStats.polygonArea(ramp), 1e-4f)
        assertEquals("winding does not matter", 6f, ArDebugStats.polygonArea(reversed(floor)), 1e-4f)
    }

    @Test
    fun `a boundary that is not yet a surface counts for nothing`() {
        assertEquals(0f, ArDebugStats.polygonArea(FloatArray(0)), 0f)
        assertEquals(0f, ArDebugStats.polygonArea(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)), 0f)
        assertEquals(0f, ArDebugStats.polygonArea(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, Float.NaN, 0f, 1f)), 0f)
    }

    @Test
    fun `four patches of one floor merged into one are the same surface, not a quarter of it`() {
        fun patch(x: Float, z: Float) = DebugPlane(
            id = (x * 10 + z).toInt(),
            kind = DebugPlaneKind.Floor,
            polygon = floatArrayOf(x, 0f, z, x + 1f, 0f, z, x + 1f, 0f, z + 1f, x, 0f, z + 1f),
        )
        val patches = listOf(patch(0f, 0f), patch(1f, 0f), patch(0f, 1f), patch(1f, 1f))
        val merged = listOf(
            DebugPlane(9, DebugPlaneKind.Floor, floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 0f, 2f, 0f, 0f, 2f)),
        )

        assertEquals(4, patches.size)
        assertEquals(1, merged.size)
        assertEquals(ArDebugStats.surfaceArea(patches), ArDebugStats.surfaceArea(merged), 1e-4f)
        assertEquals(0f, ArDebugStats.surfaceArea(emptyList()), 0f)
    }

    @Test
    fun `an area reads in square metres, one decimal while it is small`() {
        assertEquals("0 m²", ArDebugFormat.area(0f))
        assertEquals("0 m²", ArDebugFormat.area(Float.NaN))
        assertEquals("0.4 m²", ArDebugFormat.area(0.42f))
        assertEquals("9.9 m²", ArDebugFormat.area(9.94f))
        assertEquals("10 m²", ArDebugFormat.area(9.96f))
        assertEquals("14 m²", ArDebugFormat.area(14.236f))
        assertEquals("1,250 m²", ArDebugFormat.area(1_250.4f))
    }

    @Test
    fun `over the bundled walk the plane count comes and goes while the area found grows`() {
        val events = parseArDebugLog(fixture().readLines().asSequence())
        val trace = ArDebugTrace.of(events)
        val areas = ArrayList<Float>()
        val counts = ArrayList<Int>()
        var time = 0f
        while (time <= trace.duration) {
            val stats = ArDebugStats.of(trace.frameAt(time), trace.duration)
            areas += stats.surfaceMetres2
            counts += stats.planes
            time += SAMPLE_STEP_S
        }
        val last = ArDebugStats.of(trace.frameAt(trace.duration), trace.duration).surfaceMetres2

        var peak = 0f
        var worstFall = 0f
        for (area in areas) {
            worstFall = maxOf(worstFall, peak - area)
            peak = maxOf(peak, area)
        }
        val series = areas.joinToString { ArDebugFormat.area(it) }
        assertTrue("a room's worth of surface, got $last m²", last in 10f..40f)
        assertEquals("the scan ends on the most it found — $series", peak, last, 0.5f)
        assertTrue("never falls back by more than a correction ($worstFall m²) — $series", worstFall < 1.5f)
        assertTrue("the fixture does merge planes: $counts", counts.max() >= 2)
    }

    // ── The point budget ──────────────────────────────────────────────────────

    @Test
    fun `a depth scan holds half a million points, a sparse one twelve thousand`() {
        assertEquals(500_000, ScanLimits.pointBudget(depthScan = true))
        assertEquals(DenseFusion.MAX_POINTS, ScanLimits.pointBudget(depthScan = true))
        assertEquals(ArDebugTrace.MAX_MAP_POINTS, ScanLimits.pointBudget(depthScan = false))
    }

    @Test
    fun `the line counts against the budget once it is in sight, and says full when it is spent`() {
        val budget = 500_000
        assertEquals("0 points", ScanCopy.pointsLine(0, budget))
        assertEquals("1 point", ScanCopy.pointsLine(1, budget))
        assertEquals("246k points", ScanCopy.pointsLine(246_300, budget))
        assertEquals("399k points", ScanCopy.pointsLine(399_999, budget))
        assertEquals("400k / 500k", ScanCopy.pointsLine(400_000, budget))
        assertEquals("499k / 500k", ScanCopy.pointsLine(499_999, budget))
        assertEquals("500k · full", ScanCopy.pointsLine(500_000, budget))
        assertEquals("500k · full", ScanCopy.pointsLine(512_000, budget))
        assertEquals("12k · full", ScanCopy.pointsLine(12_000, 12_000))
    }

    @Test
    fun `a screen reader hears whole numbers, and the limit by name`() {
        assertEquals("246,300 points", ScanCopy.pointsSpoken(246_300, 500_000))
        assertEquals("412,000 of 500,000 points", ScanCopy.pointsSpoken(412_000, 500_000))
        assertEquals("point limit of 500,000 reached", ScanCopy.pointsSpoken(500_000, 500_000))
    }

    @Test
    fun `the sheet reads each figure against its budget`() {
        assertEquals("246k of 500k", ScanCopy.budgeted(246_300, 500_000))
        assertEquals("500k of 500k · full", ScanCopy.budgeted(500_000, 500_000))
        assertEquals("42 of 300", ScanCopy.budgeted(42, 300))
        assertEquals("300 of 300 · full", ScanCopy.budgeted(301, 300))
    }

    @Test
    fun `figures know which budget is spent`() {
        val scanning = ScanFigures(points = 246_300, pointBudget = 500_000, surfaceMetres2 = 14f, photos = 42)
        assertFalse(scanning.pointsFull)
        assertFalse(scanning.photosFull)
        assertEquals(KeyframeGate.MAX_PHOTOS, scanning.photoBudget)
        assertTrue(scanning.copy(points = 500_000).pointsFull)
        assertTrue(scanning.copy(photos = KeyframeGate.MAX_PHOTOS).photosFull)
        assertFalse("no budget, nothing to fill", ScanLimits.isFull(0, 0))
        assertFalse(ScanLimits.isNear(0, 0))
    }

    @Test
    fun `one notice names the spent budget, and none while there is room`() {
        assertNull(ScanCopy.limitNotice(pointsFull = false, photosFull = false))
        assertEquals(ScanCopy.POINTS_FULL, ScanCopy.limitNotice(pointsFull = true, photosFull = false))
        assertEquals(ScanCopy.FULL, ScanCopy.limitNotice(pointsFull = false, photosFull = true))
        assertEquals(ScanCopy.SCAN_FULL, ScanCopy.limitNotice(pointsFull = true, photosFull = true))
        // Plain English a walker reads at a glance: a limit, what it changes, no jargon.
        for (line in listOf(ScanCopy.POINTS_FULL, ScanCopy.FULL, ScanCopy.SCAN_FULL)) {
            assertTrue(line, line.length <= MAX_NOTICE_CHARS)
            assertTrue(line, line.contains("limit"))
            for (jargon in listOf("voxel", "surfel", "TSDF", "buffer", "cap ")) assertFalse(line, line.contains(jargon))
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun reversed(polygon: FloatArray): FloatArray {
        val corners = polygon.size / 3
        return FloatArray(polygon.size) { i -> polygon[(corners - 1 - i / 3) * 3 + i % 3] }
    }

    private fun fixture(): File = listOf(
        File("src/main/assets/rerun/sample-session.jsonl"),
        File("samples/android-demo/src/main/assets/rerun/sample-session.jsonl"),
    ).first { it.exists() }

    private companion object {
        const val SAMPLE_STEP_S = 0.5f

        /** Three lines of `type-caption` beside the 3D card on a 360 dp phone. */
        const val MAX_NOTICE_CHARS = 64
    }
}
