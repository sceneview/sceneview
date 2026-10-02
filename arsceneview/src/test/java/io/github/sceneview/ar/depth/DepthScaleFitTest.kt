package io.github.sceneview.ar.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DepthScaleFitTest {

    /** Anchors that follow `1/z = s·d + t` exactly, z spread over [zNear, zFar]. */
    private fun anchors(
        n: Int,
        s: Double,
        t: Double,
        zNear: Float = 0.5f,
        zFar: Float = 4f,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val d = FloatArray(n)
        val z = FloatArray(n)
        for (i in 0 until n) {
            z[i] = zNear + (zFar - zNear) * i / (n - 1)
            d[i] = ((1.0 / z[i] - t) / s).toFloat()
        }
        return Triple(d, z, FloatArray(n) { 1f })
    }

    private fun fit(result: DepthScaleFit.Result): DepthScale {
        assertTrue("expected a fit, got $result", result is DepthScaleFit.Result.Fit)
        return (result as DepthScaleFit.Result.Fit).scale
    }

    @Test
    fun `exact anchors recover scale and offset`() {
        val (d, z, c) = anchors(40, s = 0.37, t = 0.08)
        val scale = fit(DepthScaleFit.fit(d, z, c, 40))
        assertEquals(0.37, scale.scale, 1e-4)
        assertEquals(0.08, scale.offset, 1e-4)
        assertEquals(40, scale.inliers)
        assertTrue(scale.rmsRelativeError < 1e-4)
        assertEquals(z[20], scale.depthMeters(d[20]), 1e-3f)
    }

    @Test
    fun `noisy anchors stay within a few percent`() {
        val random = Random(7)
        val (d, z, c) = anchors(200, s = 0.5, t = 0.02, zNear = 0.4f, zFar = 6f)
        for (i in z.indices) z[i] *= 1f + (random.nextFloat() - 0.5f) * 0.06f
        val scale = fit(DepthScaleFit.fit(d, z, c, z.size))
        assertEquals(0.5, scale.scale, 0.02)
        assertTrue("rms ${scale.rmsRelativeError}", scale.rmsRelativeError < 0.03)
    }

    @Test
    fun `gross outliers are rejected and do not bias the fit`() {
        val (d, z, c) = anchors(60, s = 0.4, t = 0.05)
        // 15 % of the anchors are wildly wrong (a hand in front of the camera, a far wall
        // behind a window).
        for (i in 0 until 60 step 7) z[i] *= if (i % 2 == 0) 3f else 0.3f
        val scale = fit(DepthScaleFit.fit(d, z, c, 60))
        assertEquals(0.4, scale.scale, 0.01)
        assertEquals(0.05, scale.offset, 0.01)
        assertTrue("inliers ${scale.inliers}", scale.inliers in 49..52)
    }

    @Test
    fun `fewer than twelve anchors is refused`() {
        val (d, z, c) = anchors(11, s = 0.4, t = 0.05)
        val result = DepthScaleFit.fit(d, z, c, 11)
        assertEquals(DepthScaleFit.Rejection.TooFewAnchors, (result as DepthScaleFit.Result.Rejected).reason)
    }

    @Test
    fun `anchors spanning too little depth are refused`() {
        val (d, z, c) = anchors(30, s = 0.4, t = 0.05, zNear = 2f, zFar = 2.8f)
        val result = DepthScaleFit.fit(d, z, c, 30)
        assertEquals(DepthScaleFit.Rejection.FlatDepthRange, (result as DepthScaleFit.Result.Rejected).reason)
    }

    @Test
    fun `a negative scale is refused`() {
        // Network output that grows with distance: the wrong sign for inverse depth.
        val (d, z, c) = anchors(30, s = -0.4, t = 1.5, zNear = 0.7f, zFar = 2f)
        val result = DepthScaleFit.fit(d, z, c, 30)
        assertEquals(DepthScaleFit.Rejection.NonPositiveScale, (result as DepthScaleFit.Result.Rejected).reason)
    }

    @Test
    fun `constant network output is singular`() {
        val z = FloatArray(20) { 0.5f + it * 0.2f }
        val d = FloatArray(20) { 3f }
        val result = DepthScaleFit.fit(d, z, FloatArray(20) { 1f }, 20)
        assertTrue(result is DepthScaleFit.Result.Rejected)
    }

    @Test
    fun `prior pulls a weak fit towards the previous scale`() {
        val (d, z, c) = anchors(20, s = 0.4, t = 0.05)
        val prior = DepthScale(0.6, 0.05, 20, 0.5f, 4f, 0.0)
        val free = fit(DepthScaleFit.fit(d, z, c, 20))
        val pulled = fit(DepthScaleFit.fit(d, z, c, 20, prior))
        assertTrue(pulled.scale > free.scale)
        assertTrue("prior must stay weak: ${pulled.scale}", pulled.scale < 0.45)
    }

    @Test
    fun `invalid anchors are ignored`() {
        val (d, z, c) = anchors(30, s = 0.4, t = 0.05)
        z[0] = 0f
        c[1] = 0f
        d[2] = Float.NaN
        val scale = fit(DepthScaleFit.fit(d, z, c, 30))
        assertEquals(27, scale.inliers)
        assertEquals(0.4, scale.scale, 1e-3)
    }

    @Test
    fun `smoother blends log scale and holds for five refused frames`() {
        val smoother = DepthScaleSmoother(gain = 0.5, maxHeldFrames = 5)
        val a = DepthScale(1.0, 0.0, 20, 0.5f, 4f, 0.0)
        val b = DepthScale(4.0, 0.2, 20, 0.5f, 4f, 0.0)
        assertSame(a, smoother.update(DepthScaleFit.Result.Fit(a)))
        val blended = smoother.update(DepthScaleFit.Result.Fit(b))!!
        assertEquals(2.0, blended.scale, 1e-9) // geometric mean
        assertEquals(0.1, blended.offset, 1e-9)
        assertFalse(smoother.isHolding)

        val refused = DepthScaleFit.Result.Rejected(DepthScaleFit.Rejection.TooFewAnchors, 3)
        repeat(5) {
            assertSame(blended, smoother.update(refused))
            assertTrue(smoother.isHolding)
        }
        assertNull(smoother.update(refused))
        assertNull(smoother.prior)
    }
}
