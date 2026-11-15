package io.github.sceneview.core.model

import io.github.sceneview.core.ply.PlyTestFixtures
import io.github.sceneview.core.splat.gzipStored
import io.github.sceneview.core.stl.StlTestFixtures
import io.github.sceneview.core.threemf.ThreeMfTestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelFormatTest {
    @Test
    fun contentIdentifiesEveryFormatWithMissingOrMisleadingExtensions() {
        val fixtures = mapOf(
            ModelFormat.GLB to "glTF".encodeToByteArray(),
            ModelFormat.GLTF to "  {\"asset\":{\"version\":\"2.0\"}}".encodeToByteArray(),
            ModelFormat.THREE_MF to ThreeMfTestFixtures.CubeThreeMf,
            ModelFormat.STL to StlTestFixtures.binary(),
            ModelFormat.OBJ to "# exported mesh\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".encodeToByteArray(),
            ModelFormat.PLY_MESH to PlyTestFixtures.ascii(),
            ModelFormat.PLY_SPLAT to "ply\r\nformat binary_little_endian 1.0\r\nelement vertex 1\r\nproperty float x\r\nproperty float f_dc_0\r\nproperty float opacity\r\nproperty float scale_0\r\nproperty float rot_0\r\nend_header\r\n".encodeToByteArray(),
            ModelFormat.SPZ to "NGSP".encodeToByteArray(),
        )
        assertEquals(ModelFormat.entries.toSet(), fixtures.keys)
        for ((format, bytes) in fixtures) {
            for (name in listOf(null, "download", "wrong.bin", if (format == ModelFormat.GLB) "wrong.stl" else "wrong.glb")) {
                assertEquals(format, ModelFormat.sniff(name, bytes), "$format from $name")
            }
        }
    }

    @Test
    fun asciiAndSolidPrefixedBinaryStlAreBothDetected() {
        assertEquals(ModelFormat.STL, ModelFormat.sniff(null, StlTestFixtures.ascii()))
        val binary = StlTestFixtures.binary()
        "solid misleading header".encodeToByteArray().copyInto(binary)
        assertEquals(ModelFormat.STL, ModelFormat.sniff(null, binary))
    }

    @Test
    fun legacyGzipSpzAndPlainPlyHeadersAreRecognised() {
        assertEquals(ModelFormat.SPZ, ModelFormat.sniff("wrong.glb", gzipStored("NGSP".encodeToByteArray())))
        val meshHeader = "ply\ncomment opacity scale_0 rot_0 f_dc_0\nelement face 1\nend_header\n"
        assertEquals(ModelFormat.PLY_MESH, ModelFormat.sniff(null, meshHeader.encodeToByteArray()))
    }

    @Test
    fun gltfAssetAndObjFacesCanAppearAfterLargePayloads() {
        val gltf = ("{\"extras\":\"" + "x".repeat(70000) + "\",\"asset\":{\"version\":\"2.0\"}}")
            .encodeToByteArray()
        val obj = ("# export\n" + "v 0 0 0\n".repeat(1000) + "v 1 0 0\nv 0 1 0\nf 1 1001 1002\n")
            .encodeToByteArray()
        for (name in listOf(null, "wrong.stl")) assertEquals(ModelFormat.GLTF, ModelFormat.sniff(name, gltf))
        for (name in listOf(null, "wrong.glb")) assertEquals(ModelFormat.OBJ, ModelFormat.sniff(name, obj))
        val nestedAsset = "{\"extras\":{\"asset\":{}},\"note\":\"asset\"}".encodeToByteArray()
        assertFailsWith<UnsupportedModelFormat> { ModelFormat.sniff(null, nestedAsset) }
    }

    @Test
    fun extensionsAreCaseInsensitiveAndIgnoreUrlQueryAndFragment() {
        for ((extension, format) in mapOf(
            "glb" to ModelFormat.GLB, "gltf" to ModelFormat.GLTF,
            "3mf" to ModelFormat.THREE_MF, "stl" to ModelFormat.STL,
            "obj" to ModelFormat.OBJ, "ply" to ModelFormat.PLY_MESH, "spz" to ModelFormat.SPZ,
        )) {
            assertEquals(format, ModelFormat.sniff("https://host/path/MODEL.${extension.uppercase()}?x=.fbx#part", byteArrayOf()))
        }
    }

    @Test
    fun unknownAndUnrelatedZipOrTextHaveTypedErrors() {
        for (name in listOf(null, "download", "drawing.STEP", "thing.fbx", "thing.usdz")) {
            val error = assertFailsWith<UnsupportedModelFormat> { ModelFormat.sniff(name, byteArrayOf()) }
            assertEquals(name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }, error.extension)
        }
        for (bytes in listOf(
            "# ordinary comment\n".encodeToByteArray(), "solidarity facet".encodeToByteArray(),
            "{\"message\":\"not glTF\"}".encodeToByteArray(),
            ThreeMfTestFixtures.zipOf("readme.txt" to "not a model"),
        )) {
            assertFailsWith<UnsupportedModelFormat> { ModelFormat.sniff("download.bin", bytes) }
        }
    }
}
