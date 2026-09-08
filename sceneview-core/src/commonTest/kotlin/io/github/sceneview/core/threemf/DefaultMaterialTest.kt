package io.github.sceneview.core.threemf

import io.github.sceneview.core.obj.ObjLoader
import io.github.sceneview.core.ply.PlyLoader
import io.github.sceneview.core.splat.readLe32
import io.github.sceneview.core.stl.StlLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The material a colourless file gets — a 3MF with no `<basematerials>`, a plain STL, a PLY with no
 * `red`/`green`/`blue`, an OBJ with no MTL. All four share one fallback, and #3548 is what happens
 * when it is wrong: the fallback held sRGB numbers written straight into glTF's *linear*
 * `baseColorFactor`, so what read as a 0.62 grey was really an sRGB 0.81 near-white. It clipped to
 * a flat, unlit white and bloomed under the demo viewer's 30,000-lux IBL.
 *
 * These tests assert the emitted GLB, because that is what a renderer actually reads.
 */
class DefaultMaterialTest {

    @Test
    fun theFallbackIsALitNeutralGreyNotAnUnlitWhite() {
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.threeMfOf(ThreeMfTestFixtures.modelXml())))

        val rgba = baseColorFactorOf(json)
        // 18% linear grey: the photographic mid-grey, and glTF's baseColorFactor is linear.
        // Tolerance, not equality: Kotlin/JS backs a Float with a double and `String.toFloat()`
        // rounds to float32 while a `0.18f` literal does not, so the two differ in the 8th digit.
        assertEquals(0.18f, rgba[0], Epsilon, "red")
        assertEquals(0.18f, rgba[1], Epsilon, "green")
        assertEquals(0.18f, rgba[2], Epsilon, "blue")
        assertEquals(1f, rgba[3], Epsilon, "opaque")
        assertEquals(rgba[0], rgba[1], Epsilon, "neutral: no colour cast")
        assertEquals(rgba[1], rgba[2], Epsilon, "neutral: no colour cast")
        assertTrue(
            rgba.take(3).all { it < 0.5f },
            "a light albedo clips to white under a 30,000-lux IBL two stops over sunny-16 (#3548)"
        )
    }

    @Test
    fun theFallbackIsShadedRatherThanEmissiveOrUnlit() {
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.threeMfOf(ThreeMfTestFixtures.modelXml())))

        assertContains(json, """"metallicFactor":0""", message = "a print is a dielectric")
        assertContains(json, """"roughnessFactor":0.55""", message = "matte, never a mirror")
        assertTrue("emissiveFactor" !in json, "an emissive fallback would glow and bloom")
        assertTrue("KHR_materials_unlit" !in json, "the fallback must be lit, so the print shades")
        assertTrue("extensions" !in json, "no extension is needed for a plain PBR grey")
    }

    @Test
    fun everyColourlessFormatEmitsTheSameFallback() {
        val threeMf = baseColorFactorOf(
            jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.threeMfOf(ThreeMfTestFixtures.modelXml())))
        )
        val stl = baseColorFactorOf(jsonOf(StlLoader.toGlb(AsciiStl.encodeToByteArray())))
        val ply = baseColorFactorOf(jsonOf(PlyLoader.toGlb(AsciiPly.encodeToByteArray())))
        val obj = baseColorFactorOf(jsonOf(ObjLoader.toGlb(AsciiObj.encodeToByteArray())))

        assertEquals(threeMf.toList(), stl.toList(), "STL shares 3MF's fallback")
        assertEquals(threeMf.toList(), ply.toList(), "PLY shares 3MF's fallback")
        assertEquals(threeMf.toList(), obj.toList(), "OBJ shares 3MF's fallback")
    }

    @Test
    fun anExplicitColourStillWinsOverTheFallback() {
        val xml = ThreeMfTestFixtures.modelXml(
            extra = """<basematerials id="5"><base name="red" displaycolor="#FF0000"/></basematerials>""",
            objectAttributes = """pid="5" pindex="0""""
        )
        val rgba = baseColorFactorOf(jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.threeMfOf(xml))))

        assertEquals(1f, rgba[0], Epsilon, "pure red survives the sRGB→linear transfer unchanged")
        assertEquals(0f, rgba[1], Epsilon)
        assertEquals(0f, rgba[2], Epsilon)
    }

    private fun baseColorFactorOf(json: String): FloatArray {
        val at = json.indexOf(""""baseColorFactor":[""")
        assertTrue(at >= 0, "the GLB declares a baseColorFactor")
        val open = json.indexOf('[', at)
        val close = json.indexOf(']', open)
        val values = json.substring(open + 1, close).split(',').map { it.trim().toFloat() }
        assertEquals(4, values.size, "baseColorFactor is RGBA")
        return values.toFloatArray()
    }

    private fun jsonOf(glb: ByteArray): String =
        glb.decodeToString(20, 20 + readLe32(glb, 12))

    private companion object {
        /** Wide enough to absorb Kotlin/JS float32 rounding, far tighter than any visible step. */
        const val Epsilon = 1e-5f

        const val AsciiStl = """solid one
facet normal 0 0 1
outer loop
vertex 0 0 0
vertex 10 0 0
vertex 0 10 0
endloop
endfacet
endsolid one
"""

        const val AsciiPly = """ply
format ascii 1.0
element vertex 3
property float x
property float y
property float z
element face 1
property list uchar int vertex_index
end_header
0 0 0
10 0 0
0 10 0
3 0 1 2
"""

        const val AsciiObj = """v 0 0 0
v 10 0 0
v 0 10 0
vn 0 0 1
f 1//1 2//1 3//1
"""
    }
}
