package io.github.sceneview.core.ply

import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlyLoaderTest {
    @Test
    fun asciiAndBothBinaryEndiannessesMatch() {
        val models = listOf(
            PlyLoader.parse(PlyTestFixtures.ascii()),
            PlyLoader.parse(PlyTestFixtures.binary(littleEndian = true)),
            PlyLoader.parse(PlyTestFixtures.binary(littleEndian = false))
        )
        for (model in models.drop(1)) {
            assertContentEquals(models[0].positions, model.positions)
            assertContentEquals(models[0].indices, model.indices)
            assertContentEquals(models[0].normals, model.normals)
            assertContentEquals(models[0].colors, model.colors)
        }
        assertEquals(4, models[0].vertexCount)
        assertEquals(2, models[0].triangleCount)
        assertEquals(ThreeMfUnit.MILLIMETER, models[0].unit)
    }

    @Test
    fun signatureAcceptsLfAndCrLfOnly() {
        assertTrue(PlyLoader.isPly(PlyTestFixtures.ascii()))
        val crlf = PlyTestFixtures.ascii().decodeToString().replace("\n", "\r\n")
        assertTrue(PlyLoader.isPly(crlf.encodeToByteArray()))
        assertFalse(PlyLoader.isPly("ply format ascii".encodeToByteArray()))
        assertFalse(PlyLoader.isPly("glTF".encodeToByteArray()))
    }

    @Test
    fun polygonFanTriangulatesAndPreservesVertexColor() {
        val model = PlyLoader.parse(PlyTestFixtures.ascii())
        assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3), model.indices)
        assertContentEquals(floatArrayOf(1f, 0f, 0f, 1f), model.colors?.copyOfRange(0, 4))
        // Tolerance: Kotlin/JS evaluates 128f / 255f in double precision, the loader stores a Float.
        assertEquals(128f / 255f, model.colors?.get(7) ?: -1f, 0.000001f)
        val glb = PlyLoader.toGlb(PlyTestFixtures.ascii())
        assertEquals("glTF", glb.decodeToString(0, 4))
        assertTrue(glb.decodeToString().contains("COLOR_0"))
    }

    @Test
    fun missingNormalsAreGenerated() {
        val text = PlyTestFixtures.ascii().decodeToString()
            .replace("property float nx\nproperty float ny\nproperty float nz\n", "")
            .replace(Regex(" 0\\.0 0\\.0 1\\.0 (?=[0-9])"), " ")
        val model = PlyLoader.parse(text.encodeToByteArray())
        assertTrue(model.normals.all { it.isFinite() })
        assertTrue(model.normals.indices.filter { it % 3 == 2 }.all { model.normals[it] == 1f })
    }

    @Test
    fun unitAndLimitsAreApplied() {
        val model = PlyLoader.parse(PlyTestFixtures.ascii(), unit = ThreeMfUnit.CENTIMETER)
        assertEquals(ThreeMfUnit.CENTIMETER, model.unit)
        assertFailsWith<PlyParseException> {
            PlyLoader.parse(PlyTestFixtures.ascii(), maxVertices = 3)
        }
        assertFailsWith<PlyParseException> {
            PlyLoader.parse(PlyTestFixtures.ascii(), maxTriangles = 1)
        }
        assertFailsWith<PlyParseException> {
            PlyLoader.parse(PlyTestFixtures.ascii(), maxBytes = 10)
        }
    }

    @Test
    fun malformedInputsUseTypedErrors() {
        val valid = PlyTestFixtures.ascii().decodeToString()
        val malformed = listOf(
            "", "ply\nend_header\n", valid.replace("format ascii", "format binary_middle_endian"),
            valid.replace("element vertex 4", "element vertex -1"),
            valid.replace("property float x", "property string x"),
            valid.replace("4 0 1 2 3", "2 0 1"),
            valid.replace("4 0 1 2 3", "3 0 1 99"),
            valid.dropLast(4), valid + "garbage"
        )
        for (bytes in malformed.map { it.encodeToByteArray() }) {
            assertFailsWith<PlyParseException> { PlyLoader.parse(bytes) }
        }
        val truncated = PlyTestFixtures.binary(littleEndian = true).dropLast(2).toByteArray()
        assertFailsWith<PlyParseException> { PlyLoader.parse(truncated) }
    }
}
