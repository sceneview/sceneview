package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the paint over a room scan says: a patch the camera sweeps past stays thin, one it
 * is held on fills in, a fast camera adds nothing, and "Room N% covered" counts walls all around,
 * the floor and the ceiling — never a stray depth pixel.
 */
class ScanCoverageTest {
    private var now = 0L
    private val camera = DebugPose(0f, 1.4f, 0f) // looking along -Z

    private fun coverage() = ScanCoverage(clock = { now })

    /** A 0.6 m square of the wall 2 m ahead, one point per cell. */
    private val patch: FloatArray = buildList {
        for (ix in -1..1) for (iy in 6..8) addAll(listOf(ix * 0.2f + 0.1f, iy * 0.2f + 0.1f, -2f))
    }.toFloatArray()

    private fun look(coverage: ScanCoverage, points: FloatArray = patch, gapNs: Long = 300_000_000L) {
        now += gapNs
        coverage.observePoints(camera, points, FloatArray(points.size / 3) { 1f })
    }

    private fun tiers(coverage: ScanCoverage): List<Int> {
        val tiers = ArrayList<Int>()
        coverage.forEachCell { tiers += it.tier }
        return tiers
    }

    @Test
    fun `an empty scan has zero coverage and is not enough`() {
        val summary = coverage().summary()
        assertEquals(0, summary.percent)
        assertTrue(summary.walls.all { it == 0f })
        assertTrue(!summary.isEnough)
    }

    @Test
    fun `revisiting a full room cannot push the HUD past one hundred percent`() {
        val coverage = coverage()
        val points = room().flat()
        repeat(50) {
            look(coverage, points)
            assertTrue(coverage.summary().percent in 0..100)
        }
        assertEquals(100, coverage.summary().percent)
    }

    @Test
    fun `a patch seen once is thin, a camera held on it fills it in`() {
        val coverage = coverage()
        look(coverage)
        assertEquals(9, coverage.size)
        assertTrue("seen once", tiers(coverage).all { it == 1 })

        repeat(7) { look(coverage) }
        assertTrue("held about two seconds", tiers(coverage).all { it == 4 })
    }

    @Test
    fun `samples of one moment are one look`() {
        val coverage = coverage()
        look(coverage)
        repeat(5) { look(coverage, gapNs = 20_000_000L) }
        assertTrue("a burst within the look's gap counts once", tiers(coverage).all { it == 1 })
    }

    @Test
    fun `a fast camera adds nothing, a blurred one little`() {
        val coverage = coverage()
        coverage.paused = true
        look(coverage)
        assertEquals(0, coverage.size)
        assertNull(coverage.lastLook)

        coverage.paused = false
        coverage.motionFactor = ScanMotion.BLURRED_FACTOR
        repeat(4) { look(coverage) }
        assertTrue("four blurred looks are still a first pass", tiers(coverage).all { it == 1 })
    }

    @Test
    fun `a stray depth pixel paints nothing, two in one cell do`() {
        val coverage = coverage()
        val one = DenseSamples(
            1,
            floatArrayOf(0.1f, 1.5f, -2f),
            floatArrayOf(0f, 0f, 1f),
            IntArray(1),
            byteArrayOf(-1),
        )
        coverage.observeDepth(camera, one, stride = 1)
        assertEquals(0, coverage.size)

        val two = DenseSamples(
            2,
            floatArrayOf(0.1f, 1.5f, -2f, 0.15f, 1.55f, -2f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
            IntArray(2),
            byteArrayOf(-1, -1),
        )
        coverage.observeDepth(camera, two, stride = 1)
        assertEquals(1, coverage.size)
        coverage.forEachCell { assertEquals(ScanCoverage.Kind.Wall, it.kind) }
        assertEquals(2f, coverage.lastLook!!.distanceM, 0.05f)
    }

    @Test
    fun `a full scan grows no new area`() {
        val coverage = coverage()
        look(coverage)
        coverage.frozen = true
        look(coverage, floatArrayOf(1.5f, 1.5f, -2f))
        assertEquals(9, coverage.size)
    }

    @Test
    fun `a look is worth most close, square-on and at the centre of the view`() {
        assertEquals(1f, ScanCoverage.quality(1.5f, 1f, 1f, 1f), 1e-4f)
        assertEquals(0f, ScanCoverage.quality(0.1f, 1f, 1f, 1f), 1e-4f)
        assertTrue("far", ScanCoverage.quality(4.5f, 1f, 1f, 1f) < 0.5f)
        assertEquals("grazing", 0.35f, ScanCoverage.quality(1.5f, 0f, 1f, 1f), 1e-4f)
        assertEquals("at the edge of the view", 0.35f, ScanCoverage.quality(1.5f, 1f, 1f, 0.5f), 1e-4f)
        assertEquals(0.4f, ScanCoverage.depthConfidence(128), 1e-4f)
        assertEquals(1f, ScanCoverage.depthConfidence(255), 1e-4f)
    }

    @Test
    fun `sectors turn around the walk from +Z`() {
        assertEquals(0, ScanCoverage.sectorOf(0f, 1f))
        assertEquals(1, ScanCoverage.sectorOf(1f, 1f))
        assertEquals(4, ScanCoverage.sectorOf(1f, -1f))
        assertEquals(7, ScanCoverage.sectorOf(-1f, -1f))
        assertEquals(10, ScanCoverage.sectorOf(-1f, 1f))
    }

    @Test
    fun `the room is covered once its walls all around, its floor and its ceiling are`() {
        val wall = coverage()
        val north = room().filter { it[2] < -1.9f && it[1] > 0.1f && it[1] < 2.4f }
        repeat(10) { look(wall, north.flat()) }
        val one = wall.summary()
        assertTrue("one wall of four: ${one.percent}%", one.percent in 10..30)
        assertEquals("the wall behind is not scanned", 0f, one.walls[0], 1e-4f)
        assertEquals("the wall ahead is", 1f, one.walls[6], 1e-4f)

        val coverage = coverage()
        val all = room().flat()
        look(coverage, all)
        coverage.summary() // learns where the floor is
        repeat(25) { look(coverage, all) }
        val full = coverage.summary()
        assertTrue("the whole room: ${full.percent}%", full.percent >= 95)
        assertTrue(full.isEnough)
        assertEquals(1f, full.floor, 1e-4f)
        assertEquals(1f, full.ceiling, 1e-4f)
    }

    /** A 4 × 4 m room, 2.5 m high, around the camera: one point per cell of its walls, floor, ceiling. */
    private fun room(): List<FloatArray> {
        val points = ArrayList<FloatArray>()
        val steps = (0 until 20).map { -2f + it * 0.2f + 0.1f }
        val heights = (0 until 12).map { it * 0.2f + 0.1f }
        for (a in steps) {
            for (h in heights) {
                points += floatArrayOf(a, h, -2f)
                points += floatArrayOf(a, h, 2f)
                points += floatArrayOf(-2f, h, a)
                points += floatArrayOf(2f, h, a)
            }
            for (b in steps) {
                points += floatArrayOf(a, 0f, b)
                points += floatArrayOf(a, 2.5f, b)
            }
        }
        return points
    }

    private fun List<FloatArray>.flat(): FloatArray = FloatArray(size * 3).also { out ->
        forEachIndexed { i, p -> p.copyInto(out, i * 3) }
    }
}
