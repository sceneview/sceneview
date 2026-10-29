package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [FeaturePointAccumulator] and [PointCloudSprites] (#4624). */
class FeaturePointAccumulatorTest {

    private fun frame(vararg points: Pair<Int, FloatArray>): Pair<IntArray, FloatArray> {
        val ids = IntArray(points.size) { points[it].first }
        val xyzc = FloatArray(points.size * 4)
        points.forEachIndexed { i, (_, p) -> p.copyInto(xyzc, i * 4) }
        return ids to xyzc
    }

    private fun FeaturePointAccumulator.feed(nowMs: Long, vararg points: Pair<Int, FloatArray>) {
        val (ids, xyzc) = frame(*points)
        ingest(ids, xyzc, ids.size, nowMs)
    }

    private fun p(x: Float, y: Float = 0f, z: Float = -1f, c: Float = 0.5f) = floatArrayOf(x, y, z, c)

    @Test
    fun `points accumulate across frames instead of being replaced`() {
        val cloud = FeaturePointAccumulator(capacity = 100)
        cloud.feed(0L, 1 to p(0f), 2 to p(1f))
        cloud.feed(33L, 3 to p(2f), 4 to p(3f))
        assertEquals(4, cloud.size)
        assertTrue(1 in cloud && 4 in cloud)
    }

    @Test
    fun `a known id keeps its latest position and confidence`() {
        val cloud = FeaturePointAccumulator(capacity = 100)
        cloud.feed(0L, 7 to p(0f, c = 0.2f))
        cloud.feed(50L, 7 to p(5f, 6f, 7f, c = 0.9f))
        assertEquals(1, cloud.size)
        assertEquals(5f, cloud.x(0))
        assertEquals(6f, cloud.y(0))
        assertEquals(7f, cloud.z(0))
        assertEquals(0.9f, cloud.confidence(0))
        assertEquals(50L, cloud.lastSeenMs(0))
    }

    @Test
    fun `the cap evicts the point seen longest ago`() {
        val cloud = FeaturePointAccumulator(capacity = 3)
        cloud.feed(0L, 1 to p(0f))
        cloud.feed(10L, 2 to p(1f))
        cloud.feed(20L, 3 to p(2f))
        // Point 1 is seen again, so 2 is now the stalest.
        cloud.feed(30L, 1 to p(0f))
        cloud.feed(40L, 4 to p(3f))
        assertEquals(3, cloud.size)
        assertFalse(2 in cloud)
        assertTrue(1 in cloud && 3 in cloud && 4 in cloud)
        assertEquals(1L, cloud.evicted)
    }

    @Test
    fun `size never exceeds capacity and the index stays consistent`() {
        val cloud = FeaturePointAccumulator(capacity = 50)
        for (frame in 0 until 40) {
            val pts = Array(10) { (frame * 10 + it) to p(it.toFloat()) }
            cloud.feed(frame * 33L, *pts)
        }
        assertEquals(50, cloud.size)
        // The newest 50 ids are the ones kept, each reachable through its own slot.
        for (id in 350 until 400) assertTrue("id $id", id in cloud)
        for (i in 0 until cloud.size) assertTrue(cloud.id(i) in cloud)
        assertEquals(350L, cloud.evicted)
    }

    @Test
    fun `non-finite points are ignored and confidence is clamped`() {
        val cloud = FeaturePointAccumulator(capacity = 10)
        cloud.feed(0L, 1 to floatArrayOf(Float.NaN, 0f, 0f, 1f), 2 to p(0f, c = 1.7f))
        assertEquals(1, cloud.size)
        assertEquals(1f, cloud.confidence(0))
    }

    @Test
    fun `count filters by confidence and clear empties the cloud`() {
        val cloud = FeaturePointAccumulator(capacity = 10)
        cloud.feed(0L, 1 to p(0f, c = 0.05f), 2 to p(1f, c = 0.3f), 3 to p(2f, c = 0.8f))
        assertEquals(2, cloud.count(0.1f))
        assertEquals(1, cloud.count(0.4f))
        cloud.clear()
        assertEquals(0, cloud.size)
        assertFalse(1 in cloud)
    }

    @Test
    fun `sprites keep a constant on-screen radius and skip filtered points`() {
        val cloud = FeaturePointAccumulator(capacity = 10)
        cloud.feed(0L, 1 to p(0f, 0f, -1f, c = 1f), 2 to p(0f, 0f, -2f, c = 1f), 3 to p(0f, c = 0.05f))
        val palette = PointCloudSprites.Palette(
            low = floatArrayOf(0f, 0f, 1f),
            high = floatArrayOf(0f, 1f, 0f),
            fresh = floatArrayOf(1f, 0f, 0f),
        )
        val out = FloatArray(10 * PointCloudSprites.VERTICES_PER_POINT * PointCloudSprites.STRIDE)
        val written = PointCloudSprites.write(
            cloud, minConfidence = 0.1f, nowMs = 10_000L, camX = 0f, camY = 0f, camZ = 0f,
            camForwardX = 0f, camForwardY = 0f, camForwardZ = -1f,
            focalPx = 1000f, radiusPx = 10f, palette = palette, out = out,
        )
        assertEquals(2, written)
        val stridePerPoint = PointCloudSprites.VERTICES_PER_POINT * PointCloudSprites.STRIDE
        val radiusNear = out[9]
        val radiusFar = out[stridePerPoint + 9]
        // Twice as far → twice the world radius → same size on screen (10 px × 1 m / 1000 px).
        assertEquals(0.01f, radiusNear, 1e-6f)
        assertEquals(2f * radiusNear, radiusFar, 1e-6f)
        // Long settled, fully confident: the "high" colour at the history opacity.
        assertEquals(1f, out[4], 1e-6f)
        assertEquals(PointCloudSprites.HISTORY_ALPHA, out[6], 1e-6f)
    }

    @Test
    fun `a point seen now takes the fresh colour at full opacity`() {
        val cloud = FeaturePointAccumulator(capacity = 4)
        cloud.feed(1_000L, 1 to p(0f, 0f, -1f, c = 0f))
        val palette = PointCloudSprites.Palette(
            low = floatArrayOf(0f, 0f, 1f),
            high = floatArrayOf(0f, 1f, 0f),
            fresh = floatArrayOf(1f, 0f, 0f),
        )
        val out = FloatArray(4 * PointCloudSprites.VERTICES_PER_POINT * PointCloudSprites.STRIDE)
        PointCloudSprites.write(
            cloud, minConfidence = 0f, nowMs = 1_100L, camX = 0f, camY = 0f, camZ = 0f,
            camForwardX = 0f, camForwardY = 0f, camForwardZ = -1f,
            focalPx = 1000f, radiusPx = 10f, palette = palette, out = out,
        )
        assertEquals(1f, out[3], 1e-6f)
        assertEquals(0f, out[5], 1e-6f)
        assertEquals(1f, out[6], 1e-6f)
    }

    @Test
    fun `settle ramps from fresh to history`() {
        assertEquals(0f, PointCloudSprites.settle(0L))
        assertEquals(0f, PointCloudSprites.settle(PointCloudSprites.FRESH_MS))
        val halfway = PointCloudSprites.FRESH_MS + PointCloudSprites.SETTLE_MS / 2
        assertEquals(0.5f, PointCloudSprites.settle(halfway), 1e-6f)
        assertEquals(1f, PointCloudSprites.settle(60_000L))
    }

    @Test
    fun `indices draw two triangles per quad`() {
        val idx = PointCloudSprites.indices(2)
        assertEquals(listOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7), idx.toList())
    }

    @Test
    fun `status pill reads empty, sparse, then built`() {
        assertEquals(PointCloudFeedback.Status.Empty, PointCloudFeedback.status(0))
        assertEquals(PointCloudFeedback.Status.Sparse, PointCloudFeedback.status(35))
        assertEquals(PointCloudFeedback.Status.Built, PointCloudFeedback.status(PointCloudFeedback.SPARSE_BELOW))
    }
}
