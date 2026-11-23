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
 * colour displays as authored: splat, video, view-texture (#4595) and Scene Semantics overlay
 * (#5102) materials.
 *
 * `createView` grades with PBR Neutral and `createARView` with Filmic. The video and view-texture
 * materials used to call Filament's `inverseTonemapSRGB()` unconditionally, which only inverts
 * Filmic, so once the default became PBR Neutral a ViewNode card or a VideoNode lost its source
 * colours. They now pick the inverse from an `inverseFilmic` parameter the node sets from its view.
 *
 * Filament grades in Rec.2020, so the inverse runs there too (#5102, then #5219 for every material
 * but the overlay). It lives in one file, `include/view_tone_map_inverse.fs`, that each of these
 * materials includes; the AR camera-stream materials include it as well (`CameraStreamColorContractTest`).
 */
class ToneMapInverseContractTest {

    private val viewMatched = listOf(
        "video_texture",
        "video_texture_chroma_key",
        "view_texture_lit",
        "view_texture_unlit",
        "semantics_overlay",
    )

    private fun source(name: String) = File("src/main/materials/$name.mat").readText()

    /** The inverse every pass-through material includes (#5219). */
    private val shared by lazy { File("src/main/materials/include/view_tone_map_inverse.fs").readText() }

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
            assertTrue(
                "$name uses the view-matched inverse",
                mat.contains("= inverseViewTonemapSRGB(") && mat.contains(", materialParams.inverseFilmic);")
            )
        }
    }

    @Test
    fun `every pass-through material includes the one shared inverse`() {
        (viewMatched + "splat").forEach { name ->
            val mat = source(name)
            assertTrue("$name includes the shared inverse", mat.contains("#include \"include/view_tone_map_inverse.fs\""))
            // A copy left beside the include would be a second definition, or a drift waiting to happen.
            listOf("vec3 inversePbrNeutral(", "vec2 pbrNeutralCompression(", "const mat3 ", "pow((s + 0.055)").forEach {
                assertFalse("$name defines its own `$it`", mat.contains(it))
            }
        }
        listOf(
            "vec3 viewInverseSrgbToLinear(vec3 srgb)",
            "vec3 inversePbrNeutral(vec3 o)",
            "vec3 viewInverseFilmic(vec3 x)",
            "vec3 inverseViewTonemapSRGB(vec3 srgb, bool filmic)",
        ).forEach { assertTrue("the include defines $it", shared.contains(it)) }
        // No Filament built-in: splat.mat includes it in a vertex block, where they do not exist.
        listOf("Inverse_Tonemap_Filmic", "inverseTonemapSRGB(", "materialParams").forEach {
            assertFalse("the include calls `$it`", shared.lines().filterNot { l -> l.trimStart().startsWith("//") }
                .any { l -> l.contains(it) })
        }
    }

    @Test
    fun `the shared inverse decodes, converts and clips as this model does`() {
        val body = Regex("vec3 inverseViewTonemapSRGB\\(vec3 srgb, bool filmic\\) \\{([\\s\\S]*?)\\n}").find(shared)!!
            .groupValues[1].lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "vec3 working = VIEW_INVERSE_REC709_TO_REC2020 * viewInverseSrgbToLinear(srgb);",
                "vec3 scene = filmic ? viewInverseFilmic(working) : inversePbrNeutral(working);",
                "return max(VIEW_INVERSE_REC2020_TO_REC709 * scene, vec3(0.0));",
            ),
            body
        )
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

    // ---- Scene Semantics overlay palette (#5102) ----

    // Filament's ToneMapper.Filmic (Narkowicz ACES fit) and its exact analytic inverse,
    // Inverse_Tonemap_Filmic, which the shared include spells out as viewInverseFilmic.
    private fun filmic(x: Double) = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)
    private fun inverseFilmic(x: Double) =
        (0.03 - 0.59 * x - sqrt(0.0009 + 1.3702 * x - 1.0127 * x * x)) / (-5.02 + 4.86 * x)

    /** The 11 drawn classes of `semantics_overlay.mat`, as the 8-bit sRGB bytes a legend paints. */
    private fun semanticsLegend(): List<IntArray> {
        val table = Regex("SEMANTIC_PALETTE\\[12] = vec3\\[12]\\(([\\s\\S]*?)\\);")
            .find(source("semantics_overlay"))!!.groupValues[1]
        val colours = Regex("vec3\\(([0-9.]+), ([0-9.]+), ([0-9.]+)\\)").findAll(table)
            .map { m -> IntArray(3) { Math.round(m.groupValues[it + 1].toDouble() * 255).toInt() } }
            .toList()
        assertEquals("12 classes, UNLABELED first", 12, colours.size)
        return colours.drop(1)
    }

    // Filament's ColorGrading tone-maps in Rec.2020: scene Rec.709 -> Rec.2020, the curve per
    // channel there, back to Rec.709. Row-major here; the material has them column-major.
    private val rec709ToRec2020 = arrayOf(
        doubleArrayOf(0.6274040, 0.3292820, 0.0433136),
        doubleArrayOf(0.0690970, 0.9195400, 0.0113612),
        doubleArrayOf(0.0163916, 0.0880132, 0.8955950),
    )
    private val rec2020ToRec709 = arrayOf(
        doubleArrayOf(1.6604910, -0.5876411, -0.0728499),
        doubleArrayOf(-0.1245505, 1.1328999, -0.0083494),
        doubleArrayOf(-0.0181508, -0.1005789, 1.1187297),
    )

    /** A `const mat3 <name> = mat3(...)` of the shared include, as the rows it multiplies by. */
    private fun shaderMatrix(name: String): Array<DoubleArray> {
        val body = Regex("const mat3 $name = mat3\\(([^)]*)\\);").find(shared)
            ?.groupValues?.get(1) ?: error("view_tone_map_inverse.fs declares no mat3 $name")
        val columnMajor = body.split(',').map { it.trim().toDouble() }
        assertEquals("$name has 9 entries", 9, columnMajor.size)
        return Array(3) { row -> DoubleArray(3) { col -> columnMajor[col * 3 + row] } }
    }

    @Test
    fun `the shared inverse converts with the matrices this model grades with`() {
        for ((name, rows) in listOf(
            "VIEW_INVERSE_REC709_TO_REC2020" to rec709ToRec2020,
            "VIEW_INVERSE_REC2020_TO_REC709" to rec2020ToRec709,
        )) {
            val shader = shaderMatrix(name)
            for (r in 0..2) for (c in 0..2) assertEquals("$name[$r][$c]", rows[r][c], shader[r][c], 0.0)
        }
        // And the two undo each other, so a typo copied into both tables cannot pass either.
        for (c in 0..2) {
            val unit = DoubleArray(3) { if (it == c) 1.0 else 0.0 }
            val back = rec2020ToRec709.times(rec709ToRec2020.times(unit))
            for (r in 0..2) assertEquals("round trip [$r][$c]", unit[r], back[r], 1e-5)
        }
    }

    private fun Array<DoubleArray>.times(v: DoubleArray) =
        DoubleArray(3) { r -> (0..2).sumOf { this[r][it] * v[it] } }

    /** What the View shows for a scene colour, graded by [curve] in Rec.2020. */
    private fun graded(scene: DoubleArray, curve: (DoubleArray) -> DoubleArray) =
        bytes(rec2020ToRec709.times(curve(rec709ToRec2020.times(scene))))

    /** `inverseViewTonemapSRGB` of `include/view_tone_map_inverse.fs`, line for line. */
    private fun overlayScene(legend: IntArray, inverse: (DoubleArray) -> DoubleArray): DoubleArray {
        val working = rec709ToRec2020.times(DoubleArray(3) { srgbToLinear(legend[it] / 255.0) })
        return rec2020ToRec709.times(inverse(working)).map { max(it, 0.0) }.toDoubleArray()
    }

    private fun filmicCurve(c: DoubleArray) = c.map { filmic(it) }.toDoubleArray()
    private fun inverseFilmicCurve(c: DoubleArray) = c.map { inverseFilmic(it) }.toDoubleArray()

    /**
     * Each class shows its legend byte to the rounding, but a channel darker than 0x30 may land up
     * to 16 steps above it: VEHICLE's and WATER's red would need a negative scene value under
     * Filmic, which the colour buffer cannot hold (the material clips it at zero).
     */
    private fun assertShownAsLegend(view: String, shown: (IntArray) -> List<Int>) {
        semanticsLegend().forEachIndexed { i, legend ->
            val got = shown(legend)
            for (c in 0..2) {
                val tolerance = if (legend[c] < 0x30) 16 else 1
                assertTrue(
                    "$view, class ${i + 1}: legend ${legend.toList()}, shown $got (tolerance $tolerance)",
                    abs(got[c] - legend[c]) <= tolerance
                )
            }
        }
    }

    @Test
    fun `semantics overlay shows its legend colours in an AR view`() {
        assertShownAsLegend("Filmic (createARView)") { legend ->
            graded(overlayScene(legend) { inverseFilmicCurve(it) }) { filmicCurve(it) }
        }
    }

    @Test
    fun `semantics overlay shows its legend colours in a 3D view`() {
        assertShownAsLegend("PBR Neutral (createView)") { legend ->
            graded(overlayScene(legend) { inversePbrNeutral(it) }) { pbrNeutral(it) }
        }
    }

    @Test
    fun `inverted channel by channel in Rec 709, a saturated class is still off`() {
        // The first cut of #5102 inverted Filmic in Rec.709: SKY read back as #95B2FB on the
        // emulator. The model of the Rec.2020 grading gives that colour, within the LUT's steps.
        val sky = semanticsLegend()[0]
        val per709 = DoubleArray(3) { inverseFilmic(srgbToLinear(sky[it] / 255.0)) }
        val shown = graded(per709) { filmicCurve(it) }
        assertTrue("SKY inverted in Rec.709 shows $shown", abs(shown[0] - 0x95) <= 3 && abs(shown[2] - 0xFB) <= 3)
    }

    @Test
    fun `read as linear, the palette was drawn lighter than its legend`() {
        // What #5102 measured on a Pixel 4a: SKY #66B0F5 drawn as (157, 204, 237) by PBR Neutral.
        val sky = semanticsLegend()[0]
        val asLinear = graded(DoubleArray(3) { sky[it] / 255.0 }) { pbrNeutral(it) }
        assertTrue("SKY read as linear shows $asLinear", abs(asLinear[0] - 157) <= 3 && abs(asLinear[1] - 204) <= 3)
    }

    // ---- Every pass-through material (#5219) ----

    /**
     * A saturated chart, as a video frame, a drawn View or a camera image carries it: the Scene
     * Semantics legend's most saturated classes, and a grey.
     */
    private val chart = listOf(0x66B0F5, 0xD95F57, 0x4CB050, 0x2196F2, 0xF5428C, 0x00BDD4, 0xFFC207, 0x9E75BD, 0x808080)
        .map { rgb -> IntArray(3) { (rgb shr (16 - 8 * it)) and 0xFF } }

    private fun assertChartPassesThrough(
        view: String,
        inverse: (DoubleArray) -> DoubleArray,
        curve: (DoubleArray) -> DoubleArray,
    ) {
        chart.forEach { source ->
            val shown = graded(overlayScene(source, inverse), curve)
            for (c in 0..2) {
                // A channel that would need a negative scene value is clipped at zero (a strong
                // blue's and a cyan's red under Filmic): it lands a few steps above its source.
                val tolerance = if (source[c] < 0x30) 16 else 1
                assertTrue(
                    "$view: source ${source.toList()}, shown $shown (tolerance $tolerance)",
                    abs(shown[c] - source[c]) <= tolerance
                )
            }
        }
    }

    @Test
    fun `a saturated chart passes through unchanged in an AR view`() {
        assertChartPassesThrough("Filmic (createARView)", { inverseFilmicCurve(it) }) { filmicCurve(it) }
    }

    @Test
    fun `a saturated chart passes through unchanged in a 3D view`() {
        assertChartPassesThrough("PBR Neutral (createView)", { inversePbrNeutral(it) }) { pbrNeutral(it) }
    }

    @Test
    fun `inverted channel by channel in Rec 709, the camera sky was lighter and less saturated`() {
        // What #5219 predicted for the AR camera feed (exact sRGB EOTF, then Inverse_Tonemap_Filmic
        // per channel in Rec.709): a #66B0F5 sky shown at #93B2FA.
        val sky = chart[0]
        val shown = graded(DoubleArray(3) { inverseFilmic(srgbToLinear(sky[it] / 255.0)) }) { filmicCurve(it) }
        assertEquals("camera sky, inverted in Rec.709", listOf(0x93, 0xB2, 0xFA), shown)
    }
}
