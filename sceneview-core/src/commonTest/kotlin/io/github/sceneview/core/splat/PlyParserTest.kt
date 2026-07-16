package io.github.sceneview.core.splat

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlyParserTest {

    /** Semantic per-vertex inputs (as stored in an INRIA PLY, before activations). */
    private class Vertex(
        val pos: FloatArray,
        val fdc: FloatArray, // SH DC coefficients
        val opacityRaw: Float, // pre-sigmoid
        val logScale: FloatArray,
        val quatWxyz: FloatArray, // INRIA scalar-first order
    )

    private val vertices = listOf(
        Vertex(
            pos = floatArrayOf(1.5f, -2.25f, 0.5f),
            fdc = floatArrayOf(0.0f, 2.0f, -3.0f), // g clamps to 1, b clamps to 0
            opacityRaw = 0.0f, // sigmoid -> 0.5
            logScale = floatArrayOf(-2.0f, -3.0f, -1.5f),
            quatWxyz = floatArrayOf(1f, 0f, 0f, 0f), // identity
        ),
        Vertex(
            pos = floatArrayOf(-10.0f, 3.14159f, 7.0f),
            fdc = floatArrayOf(0.5f, -0.5f, 0.1f),
            opacityRaw = 2.0f,
            logScale = floatArrayOf(-4.0f, -4.0f, -4.0f),
            quatWxyz = floatArrayOf(cos(0.2618f), 0f, 0f, sin(0.2618f)), // 30° about +Z (wxyz)
        ),
        Vertex(
            pos = floatArrayOf(0.0f, 0.0f, 0.0f),
            fdc = floatArrayOf(-0.2f, 0.2f, 0.0f),
            opacityRaw = -1.0f,
            logScale = floatArrayOf(0.0f, -1.0f, -2.0f),
            quatWxyz = floatArrayOf(cos(0.1745f), sin(0.1745f), 0f, 0f), // 20° about +X (wxyz)
        ),
    )

    // Full INRIA property set, including normals and higher-SH f_rest bands (both ignored).
    private val inriaOrder = listOf(
        "x", "y", "z", "nx", "ny", "nz",
        "f_dc_0", "f_dc_1", "f_dc_2", "f_rest_0", "f_rest_1", "f_rest_2",
        "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"
    )

    private fun valueFor(name: String, v: Vertex): Float = when (name) {
        "x" -> v.pos[0]; "y" -> v.pos[1]; "z" -> v.pos[2]
        "f_dc_0" -> v.fdc[0]; "f_dc_1" -> v.fdc[1]; "f_dc_2" -> v.fdc[2]
        "opacity" -> v.opacityRaw
        "scale_0" -> v.logScale[0]; "scale_1" -> v.logScale[1]; "scale_2" -> v.logScale[2]
        "rot_0" -> v.quatWxyz[0]; "rot_1" -> v.quatWxyz[1]; "rot_2" -> v.quatWxyz[2]; "rot_3" -> v.quatWxyz[3]
        else -> 999f // normals, f_rest_* — sentinel to prove they are ignored
    }

    private fun buildFor(order: List<String>): ByteArray =
        buildPly(order, vertices.map { v -> FloatArray(order.size) { valueFor(order[it], v) } })

    private fun assertDecodes(cloud: SplatCloud) {
        assertEquals(vertices.size, cloud.count)
        for (i in vertices.indices) {
            val v = vertices[i]
            assertEquals(v.pos[0], cloud.positions[i * 3], EXACT, "pos.x[$i]")
            assertEquals(v.pos[1], cloud.positions[i * 3 + 1], EXACT, "pos.y[$i]")
            assertEquals(v.pos[2], cloud.positions[i * 3 + 2], EXACT, "pos.z[$i]")

            assertEquals(exp(v.logScale[0]), cloud.scales[i * 3], TOL, "scale.x[$i]")
            assertEquals(exp(v.logScale[1]), cloud.scales[i * 3 + 1], TOL, "scale.y[$i]")
            assertEquals(exp(v.logScale[2]), cloud.scales[i * 3 + 2], TOL, "scale.z[$i]")

            assertEquals(expectedColor(v.fdc[0]), cloud.colors[i * 3], TOL, "color.r[$i]")
            assertEquals(expectedColor(v.fdc[1]), cloud.colors[i * 3 + 1], TOL, "color.g[$i]")
            assertEquals(expectedColor(v.fdc[2]), cloud.colors[i * 3 + 2], TOL, "color.b[$i]")

            assertEquals(SplatMath.sigmoid(v.opacityRaw), cloud.opacities[i], TOL, "opacity[$i]")

            // Expected xyzw = normalize(rot_1, rot_2, rot_3, rot_0); inputs are already unit length.
            val ex = floatArrayOf(v.quatWxyz[1], v.quatWxyz[2], v.quatWxyz[3], v.quatWxyz[0])
            for (c in 0 until 4) assertEquals(ex[c], cloud.rotations[i * 4 + c], TOL, "quat[$i][$c]")
            assertEquals(1f, quatLength(cloud.rotations, i * 4), TOL, "quat normalized [$i]")
        }
    }

    @Test
    fun parsesInriaOrder() {
        assertDecodes(SplatParser.fromPly(buildFor(inriaOrder)))
        // parse() should also sniff PLY.
        assertDecodes(SplatParser.parse(buildFor(inriaOrder)))
    }

    @Test
    fun toleratesReorderedProperties() {
        // Deliberately shuffle: rotation before scale, colors after opacity, position not first.
        val reordered = listOf(
            "opacity", "rot_0", "rot_1", "rot_2", "rot_3",
            "x", "y", "z", "nx", "ny", "nz",
            "scale_0", "scale_1", "scale_2", "f_rest_0", "f_dc_0", "f_dc_1", "f_dc_2"
        )
        assertDecodes(SplatParser.fromPly(buildFor(reordered)))
    }

    @Test
    fun clampsColorsToUnitRange() {
        val cloud = SplatParser.fromPly(buildFor(inriaOrder))
        assertEquals(1f, cloud.colors[0 * 3 + 1], EXACT, "large +f_dc clamps to 1")
        assertEquals(0f, cloud.colors[0 * 3 + 2], EXACT, "large -f_dc clamps to 0")
        for (c in cloud.colors) assertTrue(c in 0f..1f)
    }

    @Test
    fun rejectsTruncatedBinaryBody() {
        val full = buildFor(inriaOrder)
        assertFailsWith<SplatParseException> { SplatParser.fromPly(full.copyOf(full.size - 10)) }
    }

    @Test
    fun rejectsAsciiFormat() {
        val header = "ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nend_header\n"
        val e = assertFailsWith<SplatParseException> {
            SplatParser.fromPly(header.encodeToByteArray() + ByteArray(4))
        }
        assertTrue(e.message?.contains("binary_little_endian") == true, e.message)
    }

    @Test
    fun rejectsBigEndianFormat() {
        val header = "ply\nformat binary_big_endian 1.0\nelement vertex 1\nproperty float x\nend_header\n"
        assertFailsWith<SplatParseException> {
            SplatParser.fromPly(header.encodeToByteArray() + ByteArray(4))
        }
    }

    @Test
    fun rejectsMissingRequiredProperty() {
        val missingRot3 = inriaOrder.filter { it != "rot_3" }
        val e = assertFailsWith<SplatParseException> { SplatParser.fromPly(buildFor(missingRot3)) }
        assertTrue(e.message?.contains("rot_3") == true, e.message)
    }

    @Test
    fun rejectsMissingHeaderTerminator() {
        val noEnd = "ply\nformat binary_little_endian 1.0\nelement vertex 1\nproperty float x\n"
        assertFailsWith<SplatParseException> { SplatParser.fromPly(noEnd.encodeToByteArray()) }
    }

    private companion object {
        const val EXACT = 1e-6f
        const val TOL = 1e-5f
    }
}
