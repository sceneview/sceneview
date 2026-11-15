package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Pins the web splat's inverse to the view's luminance-scaled Filmic transform (#4623). */
class WebToneMapInverseContractTest {
    private val weights = doubleArrayOf(0.2126730, 0.7151520, 0.0721750)

    private fun luminance(rgb: DoubleArray) = rgb.indices.sumOf { rgb[it] * weights[it] }

    private fun inverse(rgb: DoubleArray): DoubleArray {
        val y = luminance(rgb)
        if (y <= 0.0) return DoubleArray(3)
        val inv = (0.03 - 0.59 * y - sqrt(0.0009 + 1.3702 * y - 1.0127 * y * y)) / (-5.02 + 4.86 * y)
        return DoubleArray(3) { rgb[it] * (inv / y) }
    }

    // Filament ColorGrading.cpp's EVILS transform, including highlight desaturation.
    private fun forward(rgb: DoubleArray): DoubleArray {
        val peak = rgb.max()
        if (peak == 0.0) return DoubleArray(3)
        val y = luminance(rgb)
        val out = y * (2.51 * y + 0.03) / (y * (2.43 * y + 0.59) + 0.14)
        val ratio = DoubleArray(3) { rgb[it] / peak }
        val ratioLuminance = luminance(ratio)
        val difference = max(out - ratioLuminance, 0.0)
        return DoubleArray(3) {
            ratio[it] * (out - difference) / ratioLuminance +
                (1.0 - ratio[it]) * difference / max(1.0 - ratioLuminance, Double.MIN_VALUE)
        }
    }

    @Test
    fun `shader and both views use the matching colour transform`() {
        val shader = File("../sceneview-web/materials/splat_web.mat").readText()
        assertTrue(shader.contains("dot(x, float3(0.2126730, 0.7151520, 0.0721750))"))
        assertTrue(shader.contains("if (y <= 0.0) return float3(0.0);"))
        assertTrue(shader.contains("(0.03 - 0.59 * y - sqrt(0.0009 + 1.3702 * y - 1.0127 * y * y)) / (-5.02 + 4.86 * y)"))
        assertTrue(shader.contains("return x * (inverse / y);"))
        for (path in listOf(
            "../website-static/js/sceneview.js",
            "../sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/bindings/FilamentHelpers.kt",
        )) {
            assertTrue("$path must match the splat inverse", File(path).readText().contains(".luminanceScaling(true)"))
        }
    }

    @Test
    fun `all display colours round trip including black white and saturated primaries`() {
        val levels = listOf(0.0, 0.01, 0.1, 0.5, 0.9, 1.0)
        for (r in levels) for (g in levels) for (b in levels) {
            val linear = doubleArrayOf(r, g, b).map {
                if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
            }.toDoubleArray()
            val hdr = inverse(linear)
            val result = forward(hdr)
            for (c in 0..2) {
                assertTrue(hdr[c].isFinite() && hdr[c] >= 0.0)
                assertEquals("$r $g $b channel $c", linear[c], result[c], 1e-9)
            }
        }
    }
}
