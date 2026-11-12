package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class MatterDetailAtlasTest {
    @Test fun cellNeighbourhoodCacheIsIndependentOfSamplingOrder() {
        val reused = MatterDetailAtlas.Field()
        for (kind in listOf(FeelMaterial.MemoryFoam, FeelMaterial.Leather)) {
            for (i in -24..24) {
                val x = i * .1f; val z = i * -.037f
                val fresh = MatterDetailAtlas.Field()
                fresh.sample(kind, x, -.04f, z)
                reused.sample(kind, x, -.04f, z)
                assertEquals(fresh.red, reused.red, 0f)
                // Return to the first point after visiting a different 3D neighbourhood.
                reused.sample(kind, -x, -.7f, -z)
                reused.sample(kind, x, -.04f, z)
                assertEquals(fresh.red, reused.red, 0f)
            }
        }
    }

    @Test fun chartsDoNotOverlapAndSharedEdgesAddressTheSameRestPoint() {
        val occupancy = BooleanArray(MatterDetailAtlas.WIDTH * MatterDetailAtlas.HEIGHT)
        for (chart in MatterDetailAtlas.charts) {
            for (y in chart.y until chart.y + chart.height) for (x in chart.x until chart.x + chart.width) {
                val i = y * MatterDetailAtlas.WIDTH + x
                assertFalse(occupancy[i]); occupancy[i] = true
            }
        }
        val top = FloatArray(3); val side = FloatArray(3)
        for (i in 0..100) {
            val u = i / 100f
            MatterDetailAtlas.charts[0].point(u, 0f, top)
            MatterDetailAtlas.charts[2].point(u, 1f, side)
            assertArrayEquals(top, side, .000001f)
        }
    }

    @Test fun leavingTheScreenCancelsBeforeFinishingABake() {
        var rows = 0
        try {
            MatterDetailAtlas.bake(FeelMaterial.MemoryFoam) {
                if (++rows == 3) throw CancellationException()
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(3, rows)
        }
    }

    @Test fun bakedCarraraRetainsBroadHazeWithoutBlackVeins() {
        val pixels = MatterDetailAtlas.bake(FeelMaterial.Marble)
        assertEquals(MatterDetailAtlas.WIDTH * MatterDetailAtlas.HEIGHT * 2, pixels.size)
        val chart = MatterDetailAtlas.charts[0]
        var low = 255; var high = 0
        for (y in 4 until chart.height - 4) for (x in 4 until chart.width - 4) {
            val mineral = pixels[(y * MatterDetailAtlas.WIDTH + x) * 2].toInt() and 255
            low = minOf(low, mineral); high = maxOf(high, mineral)
        }
        assertTrue("Whole slab haze", low >= 25)
        assertTrue("Broad and secondary seams survive the bake", high > 140)
        assertTrue("v9 mineral cap", high <= 184)
    }
}
