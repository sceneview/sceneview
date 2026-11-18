package io.github.sceneview.core.model

import io.github.sceneview.core.obj.ObjLoader
import io.github.sceneview.core.obj.ObjTestFixtures
import io.github.sceneview.core.ply.PlyLoader
import io.github.sceneview.core.ply.PlyTestFixtures
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.stl.StlTestFixtures
import io.github.sceneview.core.threemf.ThreeMfLoader
import io.github.sceneview.core.threemf.ThreeMfTestFixtures
import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ModelConverterTest {
    @Test
    fun meshConversionsDefaultToMillimetresAndRespectEveryExplicitUnit() {
        for ((unit, legacy) in listOf(
            null to ThreeMfUnit.MILLIMETER,
            ModelUnit.Meters to ThreeMfUnit.METER,
            ModelUnit.Centimeters to ThreeMfUnit.CENTIMETER,
            ModelUnit.Millimeters to ThreeMfUnit.MILLIMETER,
            ModelUnit.Inches to ThreeMfUnit.INCH,
        )) {
            assertContentEquals(StlLoader.toGlb(StlTestFixtures.binary(), legacy),
                ModelConverter.toGlb(StlTestFixtures.binary(), ModelFormat.STL, unit))
            assertContentEquals(ObjLoader.toGlb(ObjTestFixtures.Cube, legacy),
                ModelConverter.toGlb(ObjTestFixtures.Cube, ModelFormat.OBJ, unit))
            assertContentEquals(PlyLoader.toGlb(PlyTestFixtures.ascii(), legacy),
                ModelConverter.toGlb(PlyTestFixtures.ascii(), ModelFormat.PLY_MESH, unit))
        }
    }

    @Test
    fun declaredUnitsWinAndGlbIsNotCopied() {
        val threeMf = ThreeMfTestFixtures.CubeThreeMf
        val glb = ThreeMfLoader.toGlb(threeMf)
        assertContentEquals(glb, ModelConverter.toGlb(threeMf, ModelFormat.THREE_MF, ModelUnit.Inches))
        assertSame(glb, ModelConverter.toGlb(glb, ModelFormat.GLB, ModelUnit.Millimeters))
    }

    @Test
    fun gltfJsonIsWrappedInAnAlignedGlbWithoutChangingResourceUrisOrScale() {
        val json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"mesh.bin\",\"byteLength\":12}]}"
        val bytes = ModelConverter.toGlb(json.encodeToByteArray(), ModelFormat.GLTF, ModelUnit.Inches)
        assertEquals("glTF", bytes.decodeToString(0, 4))
        assertEquals(2, le32(bytes, 4))
        assertEquals(bytes.size, le32(bytes, 8))
        assertEquals(bytes.size - 20, le32(bytes, 12))
        assertEquals(0x4e4f534a, le32(bytes, 16))
        assertEquals(0, bytes.size % 4)
        assertEquals(json, bytes.decodeToString(20).trimEnd())
    }

    @Test
    fun splatsAreRecognisedButCannotBeConvertedIntoMeshGeometry() {
        assertEquals(ModelFormat.entries.toSet() - setOf(ModelFormat.PLY_SPLAT, ModelFormat.SPZ), ModelConverter.supportedFormats)
        for (format in listOf(ModelFormat.PLY_SPLAT, ModelFormat.SPZ)) {
            assertFailsWith<UnsupportedModelFormat> { ModelConverter.toGlb(byteArrayOf(), format) }
        }
    }

    private fun le32(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { result, i ->
        result or ((bytes[offset + i].toInt() and 255) shl (i * 8))
    }
}
