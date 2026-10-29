package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Colour-chain contract for the materials that cancel the View's tone mapper so their source
 * colour displays as authored: splat, video and view-texture materials (#4595).
 *
 * `createView` grades with PBR Neutral and `createARView` with Filmic. The video and view-texture
 * materials used to call Filament's `inverseTonemapSRGB()` unconditionally, which only inverts
 * Filmic, so once the default became PBR Neutral a ViewNode card or a VideoNode lost its source
 * colours. They now pick the inverse from an `inverseFilmic` parameter the node sets from its view,
 * and share the splat's PBR Neutral inverse.
 */
class ToneMapInverseContractTest {

    private val viewMatched = listOf(
        "video_texture",
        "video_texture_chroma_key",
        "view_texture_lit",
        "view_texture_unlit",
    )

    private fun source(name: String) = File("src/main/materials/$name.mat").readText()

    // ---- Forward Khronos PBR Neutral, and a line-for-line port of the shared GLSL inverse ----

    private fun pbrNeutral(c: DoubleArray): DoubleArray {
        val x = c.min()
        val offset = if (x < 0.08) x - 6.25 * x * x else 0.04
        val col = c.map { it - offset }
        val peak = col.max()
        if (peak < 0.76) return col.toDoubleArray()
        val (newPeak, g) = compression(peak)
        return col.map { (it * newPeak / peak) * (1.0 - g) + newPeak * g }.toDoubleArray()
    }

    private fun compression(p: Double): Pair<Double, Double> {
        val np = 1.0 - 0.0576 / (p - 0.52)
        return np to 1.0 - 1.0 / (0.15 * (p - np) + 1.0)
    }

    private fun toe(c: DoubleArray): DoubleArray {
        val m = c.min()
        val offset = if (m < 0.04) sqrt(max(m, 0.0) / 6.25) - m else 0.04
        return c.map { it + offset }.toDoubleArray()
    }

    private fun enc(v: Double) = v.pow(1.0 / 2.2)

    private fun inversePbrNeutral(o: DoubleArray): DoubleArray {
        val peak = o.max()
        if (peak < 0.76) return toe(o)
        val np = min(peak, 0.99143)
        var p = 0.0576 / (1.0 - np) + 0.52
        var (n, g) = compression(p)
        val c = o.map { (it - n * g) / (1.0 - g) * (p / n) }.toDoubleArray()
        if (c.min() >= -1e-4) return toe(c)
        val omin = max(o.min(), 0.0)
        var lo = 0.76
        var hi = p
        repeat(12) {
            val mid = 0.5 * (lo + hi)
            val (mn, mg) = compression(mid)
            if (enc(peak) - enc(mn) > enc(omin + mn * mg) - enc(omin)) lo = mid else hi = mid
        }
        p = 0.5 * (lo + hi)
        compression(p).let { n = it.first; g = it.second }
        return toe(o.map { ((it - n * g) / (n * (1.0 - g))).coerceIn(0.0, 1.0) * p }.toDoubleArray())
    }

    private fun srgbToLinear(s: Double) = if (s < 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    private fun linearToSrgb(v: Double) =
        v.coerceIn(0.0, 1.0).let { if (it < 0.0031308) 12.92 * it else 1.055 * it.pow(1 / 2.4) - 0.055 }

    private fun hex(rgb: Int) = DoubleArray(3) { srgbToLinear(((rgb shr (16 - 8 * it)) and 0xFF) / 255.0) }
    private fun bytes(c: DoubleArray) = c.map { Math.round(linearToSrgb(it) * 255).toInt() }

    // ---- Source contract ----

    @Test
    fun `video and view materials select the inverse from the view`() {
        viewMatched.forEach { name ->
            val mat = source(name)
            assertTrue("$name declares inverseFilmic", Regex("name\\s*:\\s*inverseFilmic").containsMatchIn(mat))
            assertFalse(
                "$name still assigns inverseTonemapSRGB() unconditionally",
                Regex("=\\s*inverseTonemapSRGB\\(").containsMatchIn(mat)
            )
            assertTrue("$name uses the view-matched inverse", mat.contains("= inverseViewTonemapSRGB("))
        }
    }

    @Test
    fun `all five materials share one PBR Neutral inverse`() {
        val block = Regex(
            "    // ---- Inverse of ToneMapper.PBRNeutralToneMapper[\\s\\S]*?" +
                "\\n    vec3 inversePbrNeutral\\(vec3 o\\) \\{[\\s\\S]*?\\n    }\\n"
        )
        val bodies = (viewMatched + "splat").map { block.find(source(it))?.value }
        assertTrue("every material inlines the block", bodies.all { it != null })
        assertEquals("the inlined blocks are identical", 1, bodies.toSet().size)
    }

    // ---- Colour behaviour of that inverse ----

    @Test
    fun `every colour PBR Neutral can produce round-trips exactly`() {
        val ramp = listOf(0.0, 0.01, 0.03, 0.1, 0.18, 0.5, 0.8, 1.0, 2.0, 4.0, 6.0)
        for (r in ramp) for (g in ramp) for (b in ramp) {
            val display = pbrNeutral(doubleArrayOf(r, g, b))
            if (display.max() > 0.99143) continue
            val back = pbrNeutral(inversePbrNeutral(display))
            for (i in 0..2) assertEquals("($r,$g,$b)[$i]", display[i], back[i], 1e-6)
        }
    }

    @Test
    fun `greys, brand colours and pastels display as authored`() {
        // A full 0xFF channel sits above the 0.99143 cap, so the demo's brand pastels may lose a few
        // steps on that channel; everything below the cap is exact to the 8-bit rounding.
        val tolerance = mapOf(
            0x808080 to 1, 0x1E88E5 to 1, 0xF48FB1 to 1, 0x6750A4 to 1, 0xA4C1FF to 10, 0xD2A8FF to 10,
        )
        for ((rgb, steps) in tolerance) {
            val authored = bytes(hex(rgb))
            val shown = bytes(pbrNeutral(inversePbrNeutral(hex(rgb))))
            for (i in 0..2) {
                assertTrue("#%06X shows $shown, authored $authored".format(rgb), abs(shown[i] - authored[i]) <= steps)
            }
        }
    }

    @Test
    fun `a bright saturated colour stays saturated instead of turning pastel`() {
        // #FF0000 has no preimage (PBR Neutral desaturates highlights). The plain inverse with the
        // white cap displayed it as (255, 212, 212); the balanced fallback keeps it red.
        val shown = bytes(pbrNeutral(inversePbrNeutral(hex(0xFF0000))))
        assertTrue("red channel $shown", shown[0] >= 230)
        assertTrue("green and blue $shown", shown[1] <= 20 && shown[2] <= 20)
    }

    @Test
    fun `no display colour lands more than 20 8-bit steps away`() {
        val ramp = listOf(0.0, 0.02, 0.04, 0.1, 0.18, 0.5, 0.7, 0.76, 0.8, 0.9, 0.95, 0.99, 1.0)
        for (r in ramp) for (g in ramp) for (b in ramp) {
            val display = doubleArrayOf(r, g, b)
            val shown = bytes(pbrNeutral(inversePbrNeutral(display)))
            val authored = bytes(display)
            for (i in 0..2) assertTrue("($r,$g,$b) $shown vs $authored", abs(shown[i] - authored[i]) <= 20)
        }
    }

    @Test
    fun `white stays bounded at Filmic's white scene value`() {
        val scene = inversePbrNeutral(doubleArrayOf(1.0, 1.0, 1.0))
        assertTrue("scene white ${scene.toList()} must stay near 7.24", scene.all { it in 7.0..7.5 })
    }
}
