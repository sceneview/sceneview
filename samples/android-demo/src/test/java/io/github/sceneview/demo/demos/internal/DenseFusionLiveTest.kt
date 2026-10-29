package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a live snapshot reads of the dense map every second (#4792): the positions of the map's
 * stride, and the live view's merged cloud, which keeps its working arrays from one snapshot to
 * the next.
 */
class DenseFusionLiveTest {

    @Test
    fun `the stride's positions are the cloud's, under and over the cap`() {
        val fusion = map()
        for (maxPoints in listOf(Int.MAX_VALUE, fusion.count, fusion.count / 3, 1_000, 1, 0)) {
            for (minViews in listOf(1, DenseFusion.MIN_VIEWS)) {
                val cloud = fusion.cloud(minViews = minViews, maxPoints = maxPoints)
                assertArrayEquals(
                    "minViews $minViews, maxPoints $maxPoints",
                    cloud.positions,
                    fusion.cloudPositions(minViews = minViews, maxPoints = maxPoints),
                    0f,
                )
            }
        }
    }

    @Test
    fun `a live cloud is the same after another one, from elsewhere, as on its own`() {
        val reused = map()
        // A first snapshot from inside the room, which merges little and needs larger arrays.
        val first = reused.liveCloud(NEAR_EYE[0], NEAR_EYE[1], NEAR_EYE[2], maxPoints = Int.MAX_VALUE)
        for (maxPoints in listOf(Int.MAX_VALUE, BUDGET)) {
            val fresh = map().liveCloud(FAR_EYE[0], FAR_EYE[1], FAR_EYE[2], maxPoints = maxPoints)
            val again = reused.liveCloud(FAR_EYE[0], FAR_EYE[1], FAR_EYE[2], maxPoints = maxPoints)
            assertTrue("the far eye merges more", fresh.count < first.count)
            assertEquals(fresh.count, again.count)
            assertArrayEquals(fresh.positions, again.positions, 0f)
            assertArrayEquals(fresh.normals, again.normals, 0f)
            assertArrayEquals(fresh.colors, again.colors)
            assertArrayEquals(fresh.confidences, again.confidences)
            assertArrayEquals(fresh.levels, again.levels)
        }
    }

    private companion object {
        const val STEP_M = 0.02f
        const val SIDE = 150
        const val WALL_ROWS = 100
        const val BUDGET = 300

        val NEAR_EYE = floatArrayOf(1.5f, 1f, 1.5f)
        val FAR_EYE = floatArrayOf(12f, 1.5f, -9f)

        /**
         * A 3 × 3 m floor and a 2 m wall, both fused twice, then a second wall fused once: some of
         * the map is confirmed, some not.
         */
        fun map(): DenseFusion {
            val fusion = DenseFusion()
            val floor = samples(SIDE * SIDE) { i -> floatArrayOf((i / SIDE) * STEP_M, 0f, (i % SIDE) * STEP_M) }
            val wall = samples(SIDE * WALL_ROWS) { i ->
                floatArrayOf((i % SIDE) * STEP_M, (i / SIDE + 1) * STEP_M, 0f)
            }
            val side = samples(SIDE * WALL_ROWS) { i ->
                floatArrayOf(0f, (i / SIDE + 1) * STEP_M, (i % SIDE) * STEP_M)
            }
            fusion.add(floor)
            fusion.add(wall)
            fusion.add(side)
            fusion.add(floor)
            fusion.add(wall)
            return fusion
        }

        fun samples(count: Int, at: (Int) -> FloatArray): DenseSamples {
            val positions = FloatArray(count * 3)
            for (i in 0 until count) at(i).copyInto(positions, i * 3)
            return DenseSamples(
                count = count,
                positions = positions,
                normals = FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f },
                colors = IntArray(count) { 0xFF000000.toInt() or (it * 7919 and 0xFFFFFF) },
                confidences = ByteArray(count) { (128 + it % 128).toByte() },
            )
        }
    }
}
