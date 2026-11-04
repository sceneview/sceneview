package io.github.sceneview.ar.depth

import io.github.sceneview.ar.depth.DepthOcclusionShaping.hides
import io.github.sceneview.ar.depth.DepthOcclusionShaping.tolerance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DepthOcclusionShapingTest {

    private val thresholds = (0 until 16).map { (it + 0.5f) / 16f }

    /** Share of the pixels (over the dither thresholds) where the virtual fragment is hidden. */
    private fun hiddenShare(depth: Float, virtualDepth: Float): Float =
        thresholds.count { hides(depth, virtualDepth, it) } / thresholds.size.toFloat()

    @Test
    fun `tolerance grows with distance`() {
        assertEquals(0.06f, tolerance(1f), 1e-4f)
        assertEquals(0.11f, tolerance(2f), 1e-4f)
        assertEquals(0.27f, tolerance(4f), 1e-4f)
    }

    @Test
    fun `a mullion 40 cm away hides a model 2 m away on every pixel`() {
        assertEquals(1f, hiddenShare(depth = 0.4f, virtualDepth = 2f))
    }

    @Test
    fun `a model standing on the floor is not cut by the floor's noise`() {
        // Floor measured 3 cm short of the virtual contact point, 1.5 m away.
        assertEquals(0f, hiddenShare(depth = 1.47f, virtualDepth = 1.5f))
    }

    @Test
    fun `the occlusion edge is a ramp, not a step`() {
        val share = hiddenShare(depth = 2f, virtualDepth = 2f + tolerance(2f))
        assertTrue("share $share", share > 0.3f && share < 0.7f)
    }

    @Test
    fun `depth beyond 8 m never occludes, and fades from 5 m`() {
        assertEquals(0f, hiddenShare(depth = 9f, virtualDepth = 30f))
        assertEquals(1f, hiddenShare(depth = 4.5f, virtualDepth = 10f))
        val mid = hiddenShare(depth = 6.5f, virtualDepth = 20f)
        assertTrue("share $mid", mid > 0.2f && mid < 0.8f)
    }

    @Test
    fun `no depth occludes nothing`() {
        assertEquals(0f, hiddenShare(depth = 0f, virtualDepth = 3f))
        assertEquals(0f, hiddenShare(depth = 65.535f, virtualDepth = 3f))
    }

    @Test
    fun `the materials carry the same constants`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "arsceneview/src/main/materials").isDirectory }
        val expected = mapOf(
            "kTolBase" to DepthOcclusionShaping.TOL_BASE,
            "kTolLinear" to DepthOcclusionShaping.TOL_LINEAR,
            "kTolQuad" to DepthOcclusionShaping.TOL_QUAD,
            "kFarFadeStart" to DepthOcclusionShaping.FAR_FADE_START,
            "kFarFadeEnd" to DepthOcclusionShaping.FAR_FADE_END,
            "kMaxValidDepthMeters" to DepthOcclusionShaping.MAX_VALID_DEPTH,
            "kNoDepthDistanceMeters" to DepthOcclusionShaping.NO_DEPTH_DISTANCE,
        )
        listOf("camera_stream_depth.mat", "camera_stream_person_occlusion.mat").forEach { name ->
            val source = File(root, "arsceneview/src/main/materials/$name").readText()
            expected.forEach { (constant, value) ->
                val match = Regex("const float $constant = ([0-9.]+);").find(source)
                    ?: throw AssertionError("$constant missing from $name")
                assertEquals("$constant in $name", value, match.groupValues[1].toFloat(), 0f)
            }
        }
    }
}
