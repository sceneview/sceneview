package io.github.sceneview.ar.scene

import io.github.sceneview.ar.depth.DepthOcclusionShaping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** CPU reference of the plane shader's bias, with its expressions pinned below. */
class PlaneDepthSurfaceBiasTest {
    @Test fun `a surface never hides its own marks including millimetre quantization`() {
        for (mm in listOf(200, 500, 1000, 2000, 4000, 7999)) {
            val surface = mm / 1000f
            assertEquals("Coplanar at $mm mm", 1f, visibility(mm, surface), 0f)
            assertEquals("Quantization at $mm mm", 1f, visibility(mm - 1, surface), 0f)
            assertEquals("3 cm noise at $mm mm", 1f, visibility(mm - 30, surface), 0f)
        }
    }

    @Test fun `a foreground obstacle hides marks but a surface behind them does not`() {
        assertEquals(0f, visibility(1500, 2f), 0f)
        assertEquals(1f, visibility(2500, 2f), 0f)
        assertEquals(1f, visibility(0, 2f), 0f)
        assertEquals(1f, visibility(65535, 2f), 0f)
    }

    @Test fun `plane shader compares view-axis metres with a positive bias and writes no depth`() {
        val shader = File("src/main/materials/plane_renderer.mat").readText()
            .replace(Regex("\\s+"), " ")
        assertTrue(shader.contains("float4(clipPosition.xy, -viewPosition.z, clipPosition.w)"))
        assertTrue(shader.contains("float2(255.0, 256.0 * 255.0)) * 0.001"))
        assertTrue(shader.contains("if (depth <= 0.0 || depth >= 8.0) return 1.0;"))
        assertTrue(shader.contains("0.03 + depth * (0.02 + 0.01 * depth)"))
        assertTrue(shader.contains("smoothstep(tolerance, tolerance + 0.05, variable_depthPosition.z - depth)"))
        assertTrue(shader.contains("1.0 - hidden * (1.0 - smoothstep(5.0, 8.0, depth))"))
        assertTrue(shader.contains("\"depthWrite\": false"))
        assertFalse(shader.contains("gl_FragDepth"))
        assertEquals(.03f, DepthOcclusionShaping.TOL_BASE, 0f)
        assertEquals(.02f, DepthOcclusionShaping.TOL_LINEAR, 0f)
        assertEquals(.01f, DepthOcclusionShaping.TOL_QUAD, 0f)
    }

    private fun visibility(depthMm: Int, planeMeters: Float): Float {
        // RG8 is little-endian millimetres, normalized per channel, not a metre texture.
        val r = (depthMm and 255) / 255f
        val g = (depthMm ushr 8) / 255f
        val depth = (r * 255f + g * 256f * 255f) * .001f
        if (depth <= 0f || depth >= 8f) return 1f
        val tolerance = DepthOcclusionShaping.tolerance(depth)
        val hidden = smoothstep(tolerance, tolerance + .05f, planeMeters - depth)
        return 1f - hidden * (1f - smoothstep(5f, 8f, depth))
    }

    private fun smoothstep(start: Float, end: Float, value: Float): Float {
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
