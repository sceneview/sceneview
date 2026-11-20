package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DenseFusionLiveBuildTest {
    @Test
    fun `yielding the live merge preserves every output value and voxel order`() {
        val fusion = DenseFusion(maxPoints = 150_000)
        ReconstructionBench.QUICK.frames().take(12).forEach { fusion.add(DepthBackProjection.project(it)) }
        assertTrue(fusion.count > 4_096)
        val expected = fusion.liveCloud(0f, 1.4f, 0.6f, maxPoints = 5_000, minViews = 1)
        val build = fusion.liveCloudBuild(0f, 1.4f, 0.6f, maxPoints = 5_000, minViews = 1)
        // Zero budget still advances one small batch; it must yield before traversing the map.
        assertNull(build.advance(0))
        var actual: DenseCloud? = null
        var steps = 1
        while (actual == null && steps < 1_000) {
            actual = build.advance(0)
            steps++
        }
        val cloud = checkNotNull(actual)
        assertTrue(steps > 2)
        assertEquals(expected.count, cloud.count)
        assertArrayEquals(expected.positions, cloud.positions, 0f)
        assertArrayEquals(expected.normals, cloud.normals, 0f)
        assertArrayEquals(expected.colors, cloud.colors)
        assertArrayEquals(expected.confidences, cloud.confidences)
        assertArrayEquals(expected.levels, cloud.levels)
    }
}
