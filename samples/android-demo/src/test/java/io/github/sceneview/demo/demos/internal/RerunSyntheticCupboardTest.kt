package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * A space's measure on [RerunSyntheticCupboard]: a cupboard filmed from its opening, its depth
 * fused as a recording fuses it, with the units round it, the floor before it and the wall over
 * its worktop in the scan too. What [SpaceFitTest]'s hand-laid points do not go through.
 */
class RerunSyntheticCupboardTest {
    @Test
    fun `a cupboard among kitchen units is measured, not the wall it stands against`() {
        val fit = fitOf(RerunSyntheticCupboard.cloud(), RerunSyntheticCupboard.view())
        assertInside(fit, RerunSyntheticCupboard.DEPTH)
    }

    @Test
    fun `its own back panel is its back, not the wall behind it`() {
        val cloud = RerunSyntheticCupboard.cloud(RerunSyntheticCupboard.frames(scene = RerunSyntheticCupboard.scene(BACK_PANEL_M)))
        val fit = fitOf(cloud, RerunSyntheticCupboard.view())
        assertInside(fit, RerunSyntheticCupboard.DEPTH - BACK_PANEL_M)
    }

    @Test
    fun `filmed standing, the phone tilted down at it, it is still the space found`() {
        val poses = RerunSyntheticCupboard.poses(eye = STANDING, tiltDeg = STANDING_TILT_DEG)
        val view = RerunSyntheticCupboard.view(poses)
        val fit = fitOf(RerunSyntheticCupboard.cloud(RerunSyntheticCupboard.frames(poses)), view)
        assertEquals(said(fit), RerunSyntheticCupboard.WIDTH, fit.width, TOLERANCE)
        assertEquals(said(fit), RerunSyntheticCupboard.DEPTH, fit.depth, TOLERANCE)
        // From up there the underside of the worktop is never seen: the top is left open, and said so.
        assertEquals(said(fit), setOf(SpaceFace.Front, SpaceFace.Top), fit.open)
    }

    @Test
    fun `through a phone's depth noise it keeps its figures and its one thing in the way`() {
        val view = RerunSyntheticCupboard.view()
        for (sigma in NOISES_M) {
            val random = Random(SEED)
            val fit = fitOf(RerunSyntheticCupboard.cloud(RerunSyntheticCupboard.frames().map { noisy(it, sigma, random) }), view)
            val what = "noise $sigma: ${said(fit)}"
            assertEquals(what, RerunSyntheticCupboard.WIDTH, fit.width, TOLERANCE)
            assertEquals(what, RerunSyntheticCupboard.HEIGHT, fit.height, TOLERANCE)
            // Its depth is read short, never long: a box cut to it still goes in.
            assertTrue(what, fit.depth in (RerunSyntheticCupboard.DEPTH - NOISY_SHORT_M)..RerunSyntheticCupboard.DEPTH)
            assertEquals(what, setOf(SpaceFace.Front), fit.open)
            assertTrue(what, SpaceFitter.isOneSpace(fit, view))
            // The walls' own noise is no thing in the way: the pipe alone is, and the box stands before it.
            assertEquals(what, 1, fit.obstacles.size)
            val pipe = RerunSyntheticCupboard.PIPE
            assertTrue(what, checkNotNull(fit.fits) { what }.z >= pipe.z + pipe.depth)
        }
    }

    private fun assertInside(fit: SpaceFit, depth: Float) {
        assertEquals(said(fit), RerunSyntheticCupboard.WIDTH, fit.width, TOLERANCE)
        assertEquals(said(fit), RerunSyntheticCupboard.HEIGHT, fit.height, TOLERANCE)
        assertEquals(said(fit), depth, fit.depth, TOLERANCE)
        assertEquals(said(fit), setOf(SpaceFace.Front), fit.open)
        // The units round it, the floor and the wall over the worktop are two thirds of the scan.
        assertTrue(said(fit), SpaceFitter.isOneSpace(fit, RerunSyntheticCupboard.view()))
        assertEquals(said(fit), 1, fit.obstacles.size)
    }

    private fun fitOf(cloud: DenseCloud, view: SpaceView): SpaceFit = checkNotNull(SpaceFitter.of(cloud, view = view)) { "no space" }

    private fun said(fit: SpaceFit) =
        "%.3f x %.3f x %.3f, open %s, %d obstacles %s, fits %s, share %.2f, spread %.3f".format(
            fit.width, fit.height, fit.depth, fit.open, fit.obstacles.size, fit.obstacles, fit.fits, fit.share, fit.spread)

    /** [frame] as a phone's depth gives it: each pixel off by a draw of [sigma] metres. */
    private fun noisy(frame: DepthFrame, sigma: Float, random: Random): DepthFrame {
        val depth = ShortArray(frame.depthMm.size) { i ->
            val mm = frame.depthMm[i].toInt()
            if (mm <= 0) 0 else (mm + random.nextGaussian() * sigma * MM_PER_M).toInt().coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
        }
        return DepthFrame(frame.width, frame.height, depth, null, frame.colors, frame.fx, frame.fy, frame.cx, frame.cy, frame.pose)
    }

    private companion object {
        /** The scan's 2 cm grid, at either end of a side. */
        const val TOLERANCE = 0.025f

        /** A phone's depth noise, per pixel: up to the 3 cm real scans show ([SpaceFitCorpusTest]). */
        val NOISES_M = listOf(0.01f, 0.02f, 0.03f)

        /** How much short a depth may be read through that noise. */
        const val NOISY_SHORT_M = 0.04f
        const val MM_PER_M = 1000f
        const val SEED = 20261115L
        const val BACK_PANEL_M = 0.05f
        val STANDING = Vec3(0f, 1.25f, RerunSyntheticCupboard.DEPTH + 0.75f)
        const val STANDING_TILT_DEG = -42
    }
}
