package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rerun's dense fusion weighs each depth sample by its confidence, its distance and its angle,
 * and a saved scan keeps only the voxels at least two depth frames saw (study Q2, defect D2).
 */
class DenseFusionWeightTest {
    @Test
    fun `a sample's weight is its confidence, falling as 1 over d squared past a metre, times the cosine`() {
        assertEquals(1f, DepthBackProjection.weight(255, 0.5f, 1f), 1e-6f)
        assertEquals(1f, DepthBackProjection.weight(255, 1f, 1f), 1e-6f)
        assertEquals(0.25f, DepthBackProjection.weight(255, 2f, 1f), 1e-6f)
        assertEquals(0.5f * 0.25f, DepthBackProjection.weight(255, 2f, 0.5f), 1e-6f)
        // The study's example: 4 m at confidence 130 against 0.8 m at 250.
        val far = DepthBackProjection.weight(130, 4f, 1f)
        val near = DepthBackProjection.weight(250, 0.8f, 1f)
        assertEquals(31f, near / far, 0.5f)
    }

    @Test
    fun `a far doubtful sample barely moves a voxel a near confident one set`() {
        val fusion = DenseFusion()
        fusion.add(samples(floatArrayOf(0.010f, 0.010f, 0.001f), weights = floatArrayOf(1f)))
        fusion.add(samples(floatArrayOf(0.010f, 0.010f, 0.019f), weights = floatArrayOf(1f / 31f)))
        // Unweighted it would sit at 0.010, halfway; weighted it moves 1/32 of the way.
        assertEquals(0.001f + 0.018f / 32f, fusion.cloud().positions[2], 1e-6f)
    }

    @Test
    fun `without weights every sample counts alike, as before`() {
        val fusion = DenseFusion()
        fusion.add(samples(floatArrayOf(0.001f, 0.001f, 0.001f, 0.011f, 0.009f, 0.005f)))
        assertEquals(0.006f, fusion.cloud().positions[0], 1e-6f)
    }

    @Test
    fun `a voxel one depth frame alone saw is left out, however many of its pixels hit it`() {
        val fusion = DenseFusion()
        // Frame 1: three pixels in voxel A, one in voxel B. Frame 2: voxel A again.
        fusion.add(samples(floatArrayOf(0.001f, 0f, 0f, 0.002f, 0f, 0f, 0.003f, 0f, 0f, 0.5f, 0f, 0f)))
        fusion.add(samples(floatArrayOf(0.004f, 0f, 0f)))
        assertEquals(2, fusion.count)
        assertEquals(2, fusion.cloud().count)
        val saved = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        assertEquals(1, saved.count)
        assertEquals(0.0025f, saved.positions[0], 1e-6f)
        assertEquals(1, saved.confidences!!.size)
        assertEquals(1, fusion.cloud(limit = 1, minViews = DenseFusion.MIN_VIEWS).count)
    }

    @Test
    fun `a noisy wall seen near and far fuses at least 30 percent thinner`() {
        val random = Random(SEED)
        val frames = NEAR_X.map { wall(random, NEAR_M, it, NEAR_CONFIDENCE) } +
            FAR_X.map { wall(random, FAR_M, it, FAR_CONFIDENCE) }
        val projected = frames.map { DepthBackProjection.project(it) }

        val before = DenseFusion()
        projected.forEach { s -> before.add(DenseSamples(s.count, s.positions, s.normals, s.colors, s.confidences)) }
        val after = DenseFusion()
        projected.forEach { after.add(it) }

        val rmsBefore = thickness(before.cloud())
        val rmsWeighted = thickness(after.cloud())
        val rmsAfter = thickness(after.cloud(minViews = DenseFusion.MIN_VIEWS))
        // In the two voxel layers on the wall, where near and far samples share a voxel.
        val onWallBefore = thickness(before.cloud(), onWall = true)
        val onWallWeighted = thickness(after.cloud(), onWall = true)
        println(
            "wall RMS thickness: before %.2f mm, weighted %.2f mm, weighted + seen twice %.2f mm (%.0f %% thinner); "
                .format(rmsBefore * 1000, rmsWeighted * 1000, rmsAfter * 1000, (1 - rmsAfter / rmsBefore) * 100) +
                "on-wall voxels: before %.2f mm, weighted %.2f mm"
                    .format(onWallBefore * 1000, onWallWeighted * 1000),
        )
        assertTrue("weighting alone thins the wall", rmsWeighted < rmsBefore)
        assertTrue("weighting pulls the on-wall voxels in", onWallWeighted < onWallBefore)
        assertTrue("$rmsAfter against $rmsBefore", rmsAfter <= rmsBefore * 0.7f)
    }

    private companion object {
        const val W = 160
        const val H = 90
        const val F = 125f
        const val NEAR_M = 0.8f
        const val FAR_M = 3.5f
        const val NEAR_CONFIDENCE = 250
        const val FAR_CONFIDENCE = 140
        const val SEED = 42L

        /** Depth noise σ = 2 mm × d²: 1.3 mm at 0.8 m, 2.5 cm at 3.5 m. */
        const val NOISE_PER_M2 = 0.002

        /** A far frame's share of pixels thrown 8–25 cm off the wall: the dust. */
        const val OUTLIER_SHARE = 0.03

        val NEAR_X = listOf(-0.05f, 0f, 0.05f)
        val FAR_X = listOf(-0.3f, -0.1f, 0.1f, 0.3f)

        /** The wall z = 0 seen face on from [distance] m, the camera at x = [x]. */
        fun wall(random: Random, distance: Float, x: Float, confidence: Int): DepthFrame {
            val sigma = NOISE_PER_M2 * distance * distance
            val depth = ShortArray(W * H) {
                var d = distance + random.nextGaussian() * sigma
                if (distance > 1f && random.nextDouble() < OUTLIER_SHARE) {
                    d += (if (random.nextBoolean()) 1 else -1) * (0.08 + random.nextDouble() * 0.17)
                }
                (d * 1000).roundToInt().toShort()
            }
            return DepthFrame(
                width = W,
                height = H,
                depthMm = depth,
                confidence = ByteArray(W * H) { confidence.toByte() },
                colors = IntArray(W * H) { 0xFF808080.toInt() },
                fx = F,
                fy = F,
                cx = W / 2f,
                cy = H / 2f,
                pose = DebugPose(x, 0f, distance),
            )
        }

        /**
         * RMS distance to the wall of the voxels in front of the near views (|x| < 0.35, |y| < 0.2);
         * [onWall], only those of the two 2 cm layers either side of it.
         */
        fun thickness(cloud: DenseCloud, onWall: Boolean = false): Float {
            var sum = 0.0
            var n = 0
            for (i in 0 until cloud.count) {
                val x = cloud.positions[i * 3]
                val y = cloud.positions[i * 3 + 1]
                val z = cloud.positions[i * 3 + 2].toDouble()
                val inFront = abs(x) < 0.35f && abs(y) < 0.2f
                if (!inFront || onWall && abs(z) >= 0.02) continue
                sum += z * z
                n++
            }
            assertTrue("voxels in the region", n > 0)
            return sqrt(sum / n).toFloat()
        }

        fun samples(positions: FloatArray, weights: FloatArray? = null): DenseSamples {
            val n = positions.size / 3
            return DenseSamples(
                count = n,
                positions = positions,
                normals = FloatArray(positions.size) { if (it % 3 == 2) 1f else 0f },
                colors = IntArray(n) { 0xFF808080.toInt() },
                confidences = ByteArray(n) { 200.toByte() },
                weights = weights,
            )
        }
    }
}
