package io.github.sceneview.core.stl

import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StlLoaderTest {
    @Test
    fun binaryBoxWeldsThirtySixCornersToEightVertices() {
        val bytes = StlTestFixtures.binary()
        assertTrue(StlLoader.isStl(bytes))
        val model = StlLoader.parse(bytes)
        assertEquals(12, model.triangleCount)
        assertEquals(8, model.vertexCount)
        assertEquals(ThreeMfUnit.MILLIMETER, model.unit)
        assertEquals(70f, model.positions.maxOrNull())
    }

    @Test
    fun asciiAndBinaryHaveIdenticalGeometry() {
        val ascii = StlTestFixtures.ascii()
        assertTrue(StlLoader.isStl(ascii))
        val a = StlLoader.parse(ascii)
        val b = StlLoader.parse(StlTestFixtures.binary())
        assertContentEquals(b.positions, a.positions)
        assertContentEquals(b.indices, a.indices)
        assertContentEquals(b.normals, a.normals)
        assertContentEquals(StlLoader.toGlb(ascii), StlLoader.toGlb(StlTestFixtures.binary()))
    }

    @Test
    fun binarySolidHeaderWinsEvenWhenItContainsFacet() {
        val bytes = StlTestFixtures.binary(header = "solid misleading facet normal")
        assertTrue(StlLoader.isStl(bytes))
        assertEquals(12, StlLoader.parse(bytes).triangleCount)
    }

    @Test
    fun binaryLengthMustMatchUnsignedCountExactly() {
        val bytes = StlTestFixtures.binary()
        for (length in listOf(0, 79, 83, 84, bytes.size - 1, bytes.size - 50, bytes.size + 1)) {
            val truncated = bytes.copyOf(length)
            assertFalse(StlLoader.isStl(truncated))
            assertFailsWith<StlParseException> { StlLoader.parse(truncated) }
        }
        StlTestFixtures.putInt(bytes, 80, -1)
        assertFailsWith<StlParseException> { StlLoader.parse(bytes, maxTriangles = Int.MAX_VALUE) }
    }

    @Test
    fun capsRejectBothFormatsAndPermitExactLimits() {
        for (bytes in listOf(StlTestFixtures.binary(), StlTestFixtures.ascii())) {
            assertFailsWith<StlParseException> { StlLoader.toGlb(bytes, maxTriangles = 11) }
            assertFailsWith<StlParseException> { StlLoader.parse(bytes, maxBytes = bytes.size - 1) }
            assertFailsWith<StlParseException> { StlLoader.parse(bytes, maxTriangles = 0) }
            assertFailsWith<StlParseException> { StlLoader.parse(bytes, maxBytes = -1) }
            assertEquals(12, StlLoader.parse(bytes, maxTriangles = 12, maxBytes = bytes.size).triangleCount)
        }
    }

    @Test
    fun zeroNormalsAreAreaWeightedAcrossWeldedVertices() {
        val model = StlLoader.parse(StlTestFixtures.binary(StlTestFixtures.wedge))
        assertEquals(5, model.vertexCount)
        // Shared origin: normalize (0,1,2), not an unweighted average of unit normals.
        val inverseLength = (1.0 / sqrt(5.0)).toFloat()
        assertNormal(model, 0, 0f, inverseLength, 2 * inverseLength)
        assertNormal(model, 3, 0f, inverseLength, 2 * inverseLength)
        assertNormal(model, 1, 0f, 0f, 1f)
        assertNormal(model, 4, 0f, 1f, 0f)
    }

    @Test
    fun validFacetNormalsAreNormalizedAndPreservedAlongsideRepairs() {
        val facets = StlTestFixtures.wedge.map { it.copyOf() }
        facets[0][0] = 3f
        val model = StlLoader.parse(StlTestFixtures.binary(facets))
        repeat(3) { assertNormal(model, it, 1f, 0f, 0f) }
        val inverseLength = (1.0 / sqrt(5.0)).toFloat()
        assertNormal(model, 3, 0f, inverseLength, 2 * inverseLength)
    }

    @Test
    fun nonFiniteNormalsAreRepairedAndZeroAreaFacesStayFinite() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val facets = StlTestFixtures.wedge.map { it.copyOf().also { f -> f[0] = invalid } }
            val repaired = StlLoader.parse(StlTestFixtures.binary(facets))
            val expected = StlLoader.parse(StlTestFixtures.binary(StlTestFixtures.wedge))
            assertContentEquals(expected.normals, repaired.normals)
        }
        val degenerate = StlLoader.parse(StlTestFixtures.binary(listOf(FloatArray(12))))
        assertEquals(1, degenerate.vertexCount)
        repeat(3) { assertNormal(degenerate, it, 0f, 1f, 0f) }
    }

    @Test
    fun signedZeroWeldsAndVerySmallOrLargeNormalsNormalize() {
        val facets = StlTestFixtures.wedge.map { it.copyOf() }
        facets[1][3] = -0f
        facets[0][2] = Float.MIN_VALUE
        facets[1][1] = Float.MAX_VALUE
        val model = StlLoader.parse(StlTestFixtures.binary(facets))
        assertEquals(5, model.vertexCount)
        assertNormal(model, 0, 0f, 0f, 1f)
        assertNormal(model, 3, 0f, 1f, 0f)
    }

    @Test
    fun nonFinitePositionsFailForBothEncodings() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val facets = listOf(StlTestFixtures.wedge[0].copyOf().also { it[3] = value })
            assertFailsWith<StlParseException> { StlLoader.parse(StlTestFixtures.binary(facets)) }
            assertFailsWith<StlParseException> { StlLoader.parse(StlTestFixtures.ascii(facets)) }
        }
    }

    @Test
    fun asciiSupportsWhitespaceExponentsMissingNormalsAndMultipleSolids() {
        val text = StlTestFixtures.ascii(StlTestFixtures.wedge).decodeToString()
            .replace("facet normal 0.0 0.0 0.0", "facet")
            .replace("2.0", "2e0").replace(" ", "\t").replace("\n", "\r\n")
        val model = StlLoader.parse((" \n$text$text").encodeToByteArray())
        assertEquals(4, model.triangleCount)
        assertEquals(5, model.vertexCount)
        assertTrue(model.normals.all { it.isFinite() })
    }

    @Test
    fun malformedAsciiFailsWithTypedErrors() {
        val text = StlTestFixtures.ascii().decodeToString()
        for (bad in listOf(
            "solid only\nendsolid only\n", "solidarity facet", text.replace("endfacet", ""),
            text.replace("endsolid", "missing"), text.replace("outer loop", "outer broken"),
            text.replaceFirst("vertex", "wrong"), text.replaceFirst("70.0", "no-number"),
            text.replaceFirst("70.0", "1".repeat(129)), text + "garbage", "glTF"
        )) {
            assertFailsWith<StlParseException>(bad.take(40)) { StlLoader.parse(bad.encodeToByteArray()) }
        }
        assertFalse(StlLoader.isStl("solid only".encodeToByteArray()))
        assertFalse(StlLoader.isStl("solidarity facet".encodeToByteArray()))
        assertFalse(StlLoader.isStl("solid name\n multifacet".encodeToByteArray()))
    }

    private fun assertNormal(model: StlModel, corner: Int, x: Float, y: Float, z: Float) {
        for ((axis, expected) in floatArrayOf(x, y, z).withIndex()) {
            assertEquals(expected, model.normals[corner * 3 + axis], 0.000001f)
        }
    }
}
