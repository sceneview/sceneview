package io.github.sceneview.core.stl

import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StlGlbTest {
    @Test
    fun boxBoundsAndBinaryPositionsAreSeventyByFortyByThirtyMillimetres() {
        for (bytes in listOf(StlTestFixtures.binary(), StlTestFixtures.ascii())) {
            val glb = StlLoader.toGlb(bytes)
            val json = json(glb)
            assertContains(json, "\"min\":[0,0,0]")
            assertContains(json, "\"max\":[0.07,0.04,0.03]")
            assertContains(json, "\"count\":8,\"type\":\"VEC3\"")
            assertContains(json, "\"indices\":2")
            assertContains(json, "\"doubleSided\":true")
            assertTrue("\"matrix\"" !in json, "STL axes are preserved; metres are baked into positions")
            val start = binStart(glb)
            val bounds = FloatArray(3)
            repeat(8) { vertex ->
                repeat(3) { axis ->
                    bounds[axis] = maxOf(bounds[axis], float(glb, start + (vertex * 3 + axis) * 4))
                }
            }
            assertEquals(0.07f, bounds[0], 0.0000001f)
            assertEquals(0.04f, bounds[1], 0.0000001f)
            assertEquals(0.03f, bounds[2], 0.0000001f)
        }
    }

    @Test
    fun explicitUnitsScaleToMetres() {
        for (unit in ThreeMfUnit.entries) {
            val glb = StlLoader.toGlb(StlTestFixtures.binary(), unit)
            val start = binStart(glb)
            var maxX = 0f
            repeat(8) { maxX = maxOf(maxX, float(glb, start + it * 12)) }
            assertEquals(70f * unit.meters, maxX, 0.00001f)
            assertEquals(unit, StlLoader.parse(StlTestFixtures.binary(), unit).unit)
        }
    }

    @Test
    fun glbChunksAndViewsAreAlignedAndFillTheContainer() {
        val glb = StlLoader.toGlb(StlTestFixtures.binary())
        assertEquals(0x46546c67, int(glb, 0))
        assertEquals(2, int(glb, 4))
        assertEquals(glb.size, int(glb, 8))
        val jsonLength = int(glb, 12)
        assertEquals(0x4e4f534a, int(glb, 16))
        assertEquals(0, jsonLength % 4)
        val json = json(glb)
        val end = json.lastIndexOf('}') + 1
        assertTrue(json.substring(end).all { it == ' ' }, "JSON padding must be spaces")
        assertTrue(json.length - end in 0..3)
        val binLength = int(glb, 20 + jsonLength)
        assertEquals(0x004e4942, int(glb, 24 + jsonLength))
        assertEquals(0, binLength % 4)
        assertEquals(glb.size, binStart(glb) + binLength)
        // Eight positions + eight normals (float3), then 36 uint32 indices: no BIN padding needed.
        assertEquals(8 * 12 * 2 + 36 * 4, binLength)
        var expectedOffset = 0
        val views = Regex("\"byteOffset\":(\\d+),\"byteLength\":(\\d+)").findAll(json).toList()
        assertEquals(3, views.size)
        for (view in views) {
            val offset = view.groupValues[1].toInt()
            val length = view.groupValues[2].toInt()
            assertEquals(expectedOffset, offset)
            assertEquals(0, offset % 4)
            assertEquals(0, length % 4)
            expectedOffset += length
        }
        assertEquals(binLength, expectedOffset)
        val indicesStart = binStart(glb) + 8 * 12 * 2
        repeat(36) { assertTrue(int(glb, indicesStart + it * 4) in 0..7) }
    }

    @Test
    fun smoothNormalsReachTheGlbAndValidFacetsSplitNormalSeams() {
        val smooth = StlLoader.toGlb(StlTestFixtures.binary(StlTestFixtures.wedge))
        assertContains(json(smooth), "\"count\":5,\"type\":\"VEC3\"")
        val normalStart = binStart(smooth) + 5 * 12
        assertEquals(0f, float(smooth, normalStart))
        assertEquals(0.4472136f, float(smooth, normalStart + 4), 0.000001f)
        assertEquals(0.8944272f, float(smooth, normalStart + 8), 0.000001f)
        val facets = StlTestFixtures.wedge.map { it.copyOf() }
        facets[0][2] = 1f
        facets[1][1] = 1f
        val flat = StlLoader.toGlb(StlTestFixtures.binary(facets))
        assertContains(json(flat), "\"count\":6,\"type\":\"VEC3\"")
    }

    @Test
    fun extremeBoundsRemainJsonNumbersAndDoNotRoundToZero() {
        for (size in listOf(1e-12f, 1e20f)) {
            val facet = StlTestFixtures.wedge[0].copyOf()
            facet[6] = size
            val glb = StlLoader.toGlb(StlTestFixtures.binary(listOf(facet)), ThreeMfUnit.METER)
            val max = Regex("\"max\":\\[([^,]+),").find(json(glb))!!.groupValues[1].toFloat()
            assertTrue(kotlin.math.abs((max - size) / size) < 0.000001f)
        }
    }

    private fun json(glb: ByteArray): String = glb.decodeToString(20, 20 + int(glb, 12))
    private fun binStart(glb: ByteArray): Int = 28 + int(glb, 12)
    private fun int(bytes: ByteArray, at: Int): Int = stlUInt32(bytes, at).toInt()
    private fun float(bytes: ByteArray, at: Int): Float = Float.fromBits(int(bytes, at))
}
