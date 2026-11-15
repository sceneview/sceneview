package io.github.sceneview.core.model

import io.github.sceneview.core.threemf.ThreeMfUnit
import io.github.sceneview.core.threemf.ThreeMfTestFixtures
import io.github.sceneview.core.threemf.ThreeMfLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelFormatTest {
    @Test fun sniffsMeshesWithoutAFileNameAndConvertsToGlb() {
        for ((format, text) in meshes) {
            val bytes = text.encodeToByteArray()
            assertEquals(format, ModelFormat.sniff(bytes))
            assertEquals("glTF", format.toGlb(bytes).decodeToString(0, 4))
            assertTrue(format.isUnitless)
        }
    }

    @Test fun threeMfKeepsItsDeclaredUnit() {
        val bytes = ThreeMfTestFixtures.CubeThreeMf
        assertEquals(ModelFormat.THREE_MF, ModelFormat.sniff(bytes, "download"))
        assertTrue(ModelFormat.THREE_MF.toGlb(bytes, ThreeMfUnit.METER)
            .contentEquals(ThreeMfLoader.toGlb(bytes)))
    }

    @Test fun keepsGltfBytesAndRejectsUnknownFormats() {
        for (bytes in listOf("glTFtest", "{\"asset\":{\"version\":\"2.0\"}}").map { it.encodeToByteArray() }) {
            assertSame(bytes, ModelFormat.sniff(bytes).toGlb(bytes))
        }
        assertFailsWith<UnsupportedModelFormatException> { ModelFormat.sniff(byteArrayOf(), "part.fbx") }
        assertFailsWith<UnsupportedModelFormatException> { ModelFormat.sniff("{}".encodeToByteArray()) }
    }

    @Test fun filenameAllowsObjWithALongHeaderAndPayloadWinsOverHint() {
        val obj = ("#" + "x".repeat(5000) + "\n" + meshes.getValue(ModelFormat.OBJ)).encodeToByteArray()
        assertEquals(ModelFormat.OBJ, ModelFormat.sniff(obj, "https://example.com/PART.OBJ?download=1#file"))
        assertEquals(ModelFormat.OBJ, ModelFormat.sniff(meshes.getValue(ModelFormat.OBJ).encodeToByteArray(), "wrong.stl"))
    }

    @Test fun unitsChangeConvertedMeshScaleAndDefaultToMillimeters() {
        for ((format, text) in meshes) {
            val bytes = text.encodeToByteArray()
            assertTrue(format.toGlb(bytes).contentEquals(format.toGlb(bytes, ThreeMfUnit.MILLIMETER)))
            val glb = format.toGlb(bytes, ThreeMfUnit.METER)
            assertTrue(!glb.contentEquals(format.toGlb(bytes)))
            assertEquals(10f, xExtent(glb), 0.00001f)
            assertEquals(0.01f, xExtent(format.toGlb(bytes)), 0.00001f)
        }
    }

    private fun xExtent(glb: ByteArray): Float {
        // Geometry may bake units into positions or store them on the root node.
        val json = glb.decodeToString()
        val max = Regex("\"max\":\\[([^]]+)\\]").find(json)!!.groupValues[1]
            .substringBefore(',').toFloat()
        val scale = Regex("\"scale\":\\[([^]]+)\\]").find(json)?.groupValues?.get(1)
            ?.substringBefore(',')?.toFloat() ?: 1f
        return max * scale
    }

    private val meshes = mapOf(
        ModelFormat.STL to "solid part\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 10 0 0\nvertex 0 10 0\nendloop\nendfacet\nendsolid part\n",
        ModelFormat.OBJ to "v 0 0 0\nv 10 0 0\nv 0 10 0\nf 1 2 3\n",
        ModelFormat.PLY to "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n10 0 0\n0 10 0\n3 0 1 2\n"
    )
}
