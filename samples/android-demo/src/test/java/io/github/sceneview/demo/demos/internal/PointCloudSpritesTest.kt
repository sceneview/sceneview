package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class PointCloudSpritesTest {

    @Test
    fun `points at equal view depth have the same world radius off axis`() {
        val cloud = FeaturePointAccumulator(capacity = 2)
        cloud.ingest(
            pointIds = intArrayOf(1, 2),
            xyzc = floatArrayOf(0f, 0f, -1f, 1f, 1f, 0f, -1f, 1f),
            count = 2,
            nowMs = 0L,
        )
        val palette = PointCloudSprites.Palette(
            low = floatArrayOf(0f, 0f, 0f),
            high = floatArrayOf(1f, 1f, 1f),
            fresh = floatArrayOf(1f, 1f, 1f),
        )
        val out = FloatArray(2 * PointCloudSprites.VERTICES_PER_POINT * PointCloudSprites.STRIDE)

        PointCloudSprites.write(
            cloud, minConfidence = 0f, nowMs = 10_000L, camX = 0f, camY = 0f, camZ = 0f,
            camForwardX = 0f, camForwardY = 0f, camForwardZ = -1f,
            focalPx = 1000f, radiusPx = 10f, palette = palette, out = out,
        )

        val stridePerPoint = PointCloudSprites.VERTICES_PER_POINT * PointCloudSprites.STRIDE
        assertEquals(out[9], out[stridePerPoint + 9], 1e-6f)
    }
}
