package io.github.sceneview.ar.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class ARReplayMetricsTest {

    // ── Projection ──────────────────────────────────────────────────────────

    /** Column-major perspective, camera at the origin looking down -Z (OpenGL convention). */
    private fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, 2f * far * near / (near - far), 0f,
        )
    }

    private val proj90 = perspective(90f, 1f, 0.01f, 100f)

    @Test
    fun `a unit box two metres ahead covers a quarter of each axis`() {
        // At z = -2 with a 90 degree fov the view is 4 m wide; a 1 m box spans 1/4 of it at its
        // near face (z = -1.5: 3 m wide → 1/3). Bounds come from the nearer face.
        val corners = ARReplayMetrics.boxCorners(floatArrayOf(0f, 0f, -2f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val c = ARReplayMetrics.projectCoverage(corners, proj90)
        val side = 1f / 3f
        assertEquals(side * side, c.fraction, 1e-4f)
        assertEquals(0.5f - side / 2f, c.rect!!.left, 1e-4f)
        assertFalse(c.cameraInside)
    }

    @Test
    fun `a box behind the camera is not on screen`() {
        val corners = ARReplayMetrics.boxCorners(floatArrayOf(0f, 0f, 3f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val c = ARReplayMetrics.projectCoverage(corners, proj90)
        assertEquals(0f, c.fraction, 0f)
        assertNull(c.rect)
    }

    @Test
    fun `a box around the camera fills the screen`() {
        // The "giant model": a 100 m box the camera stands in.
        val center = floatArrayOf(0f, 0f, 0f)
        val half = floatArrayOf(50f, 50f, 50f)
        val c = ARReplayMetrics.projectCoverage(ARReplayMetrics.boxCorners(center, half), proj90)
        assertEquals(1f, c.fraction, 0f)
        assertTrue(ARReplayMetrics.isInside(floatArrayOf(0f, 0f, 0f), center, half))
    }

    @Test
    fun `a box off to the side is clipped to the screen`() {
        // Centre far right: only its left part enters the view.
        val corners = ARReplayMetrics.boxCorners(floatArrayOf(2f, 0f, -2f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val c = ARReplayMetrics.projectCoverage(corners, proj90)
        assertEquals(1f, c.rect!!.right, 0f)
        assertTrue(c.fraction > 0f && c.fraction < 0.25f)
    }

    @Test
    fun `a box entirely outside the frustum covers nothing`() {
        val corners = ARReplayMetrics.boxCorners(floatArrayOf(20f, 0f, -2f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val c = ARReplayMetrics.projectCoverage(corners, proj90)
        assertEquals(0f, c.fraction, 0f)
    }

    @Test
    fun `multiply by identity keeps the matrix`() {
        val identity = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f }
        assertEquals(proj90.toList(), ARReplayMetrics.multiply(proj90, identity).toList())
        assertEquals(proj90.toList(), ARReplayMetrics.multiply(identity, proj90).toList())
    }

    @Test
    fun `a view translation moves the box in front of the camera`() {
        // Camera at z = +2 looking down -Z: the view matrix translates the world by -2.
        val view = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f; it[14] = -2f }
        val corners = ARReplayMetrics.boxCorners(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val c = ARReplayMetrics.projectCoverage(corners, ARReplayMetrics.multiply(proj90, view))
        assertEquals(1f / 9f, c.fraction, 1e-4f)
    }

    @Test
    fun `union of two coverages is their bounding rectangle`() {
        val a = ProjectedCoverage(0.01f, ScreenRect(0.1f, 0.1f, 0.2f, 0.2f))
        val b = ProjectedCoverage(0.01f, ScreenRect(0.5f, 0.5f, 0.6f, 0.6f))
        val u = a.union(b)
        assertEquals(0.25f, u.fraction, 1e-6f)
        assertEquals(ScreenRect(0.1f, 0.1f, 0.6f, 0.6f), u.rect)
        assertEquals(a, a.union(ProjectedCoverage.NONE))
    }

    // ── Luminance ───────────────────────────────────────────────────────────

    @Test
    fun `luma of black white and primaries`() {
        assertEquals(0f, ARReplayMetrics.luma(0xFF000000.toInt()), 0f)
        assertEquals(1f, ARReplayMetrics.luma(0xFFFFFFFF.toInt()), 1e-6f)
        assertEquals(0.7152f, ARReplayMetrics.luma(0xFF00FF00.toInt()), 1e-4f)
        assertEquals(0.2126f, ARReplayMetrics.luma(0xFFFF0000.toInt()), 1e-4f)
        // Alpha is ignored.
        assertEquals(1f, ARReplayMetrics.luma(0x00FFFFFF), 1e-6f)
    }

    @Test
    fun `mean luminance over the whole image and a region`() {
        // 4x2: left half black, right half white.
        val b = 0xFF000000.toInt()
        val w = 0xFFFFFFFF.toInt()
        val px = intArrayOf(b, b, w, w, b, b, w, w)
        assertEquals(0.5f, ARReplayMetrics.meanLuminance(px, 4, 2), 1e-6f)
        assertEquals(1f, ARReplayMetrics.meanLuminance(px, 4, 2, ScreenRect(0.5f, 0f, 1f, 1f)), 1e-6f)
        assertEquals(0f, ARReplayMetrics.meanLuminance(px, 4, 2, ScreenRect(0f, 0f, 0.5f, 1f)), 1e-6f)
        assertEquals(0f, ARReplayMetrics.meanLuminance(px, 4, 2, ScreenRect(0.5f, 0.5f, 0.5f, 0.5f)), 0f)
    }

    @Test
    fun `stride samples every nth pixel`() {
        val b = 0xFF000000.toInt()
        val w = 0xFFFFFFFF.toInt()
        // Columns alternate white/black; stride 2 only sees the white columns.
        val px = intArrayOf(w, b, w, b, w, b, w, b)
        assertEquals(0.5f, ARReplayMetrics.meanLuminance(px, 4, 2), 1e-6f)
        assertEquals(1f, ARReplayMetrics.meanLuminance(px, 4, 2, stride = 2), 1e-6f)
    }

    // ── Verdicts ────────────────────────────────────────────────────────────

    private val all = ARReplayCheck.entries.toSet()

    private val good = ARReplayObservation(
        frames = 200,
        firstPlaneFrame = 40,
        trackedAnchors = 1,
        visibleModels = 1,
        coverage = ProjectedCoverage(0.05f, ScreenRect(0.4f, 0.4f, 0.6f, 0.65f)),
        sceneLuminance = 0.35f,
        modelLuminance = 0.3f,
    )

    private fun failed(o: ARReplayObservation) =
        evaluateReplay(o, all).filterNot { it.passed }.map { it.check }

    @Test
    fun `a healthy replay passes every check`() {
        assertEquals(emptyList<ARReplayCheck>(), failed(good))
    }

    @Test
    fun `a hidden model fails coverage`() {
        assertEquals(listOf(ARReplayCheck.MODEL_COVERAGE), failed(good.copy(visibleModels = 0)))
    }

    @Test
    fun `a giant model fails coverage`() {
        val giant = good.copy(coverage = ProjectedCoverage(1f, ScreenRect.FULL, cameraInside = true))
        assertEquals(listOf(ARReplayCheck.MODEL_COVERAGE), failed(giant))
        val huge = good.copy(coverage = ProjectedCoverage(0.95f, ScreenRect.FULL))
        assertEquals(listOf(ARReplayCheck.MODEL_COVERAGE), failed(huge))
    }

    @Test
    fun `a speck of a model fails coverage`() {
        val speck = good.copy(coverage = ProjectedCoverage(0.0001f, ScreenRect(0.5f, 0.5f, 0.51f, 0.51f)))
        assertEquals(listOf(ARReplayCheck.MODEL_COVERAGE), failed(speck))
    }

    @Test
    fun `a dark render fails both brightness checks`() {
        val dark = good.copy(sceneLuminance = 0.02f, modelLuminance = 0.01f)
        assertEquals(
            listOf(ARReplayCheck.SCENE_BRIGHTNESS, ARReplayCheck.MODEL_BRIGHTNESS),
            failed(dark),
        )
    }

    @Test
    fun `a late or missing plane fails the plane check`() {
        assertEquals(listOf(ARReplayCheck.PLANE), failed(good.copy(firstPlaneFrame = 900)))
        assertEquals(listOf(ARReplayCheck.PLANE), failed(good.copy(firstPlaneFrame = null)))
    }

    @Test
    fun `a stalled replay fails the replayed check`() {
        assertEquals(listOf(ARReplayCheck.REPLAYED), failed(good.copy(frames = 0)))
    }

    @Test
    fun `only the requested checks are graded`() {
        val results = evaluateReplay(
            ARReplayObservation(frames = 10),
            setOf(ARReplayCheck.REPLAYED),
        )
        assertEquals(1, results.size)
        assertTrue(results.single().passed)
    }

    @Test
    fun `measured values are locale independent`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val r = evaluateReplay(good, setOf(ARReplayCheck.SCENE_BRIGHTNESS)).single()
            assertEquals("luma 0.350", r.measured)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
