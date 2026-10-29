package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The `extensionsRequired` gltfio cannot honour, read before any Filament call: gltfio never checks
 * the list itself, and loads such a file anyway without what the extension carried.
 */
@RunWith(RobolectricTestRunner::class) // org.json is an Android API
class GltfRequiredExtensionsTest {

    @Test
    fun `a GLB requiring an extension gltfio does not implement reports it`() {
        val source = glb("""{"extensionsRequired":["EXT_texture_avif","KHR_texture_transform"]}""")

        assertEquals(listOf("EXT_texture_avif"), GltfRequiredExtensions.unsupported(source))
    }

    @Test
    fun `meshopt, quantization, Draco and basisu compression are supported, gltfpack -mi instancing is not`() {
        val source = glb(
            """{"extensionsRequired":["EXT_meshopt_compression","KHR_mesh_quantization",""" +
                """"KHR_draco_mesh_compression","KHR_texture_basisu"]}""",
        )
        // gltfpack `-mi` output: gltfio never reads the instances.
        val instanced = glb(
            """{"extensionsRequired":["EXT_meshopt_compression","KHR_mesh_quantization","EXT_mesh_gpu_instancing"]}""",
        )

        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(source))
        assertEquals(listOf("EXT_mesh_gpu_instancing"), GltfRequiredExtensions.unsupported(instanced))
    }

    @Test
    fun `an extension only used, not required, is left to gltfio`() {
        val source = glb("""{"extensionsUsed":["EXT_texture_avif"]}""")

        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(source))
    }

    @Test
    fun `a text glTF is read whole`() {
        val source = ByteBuffer.wrap("""{"extensionsRequired":["KHR_meshopt_compression"]}""".toByteArray())

        assertEquals(listOf("KHR_meshopt_compression"), GltfRequiredExtensions.unsupported(source))
    }

    @Test
    fun `a payload that is not glTF JSON is left to gltfio`() {
        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(ByteBuffer.wrap("solid stl".toByteArray())))
    }

    @Test
    fun `the check throws a typed error naming every unsupported extension`() {
        val source = glb("""{"extensionsRequired":["EXT_texture_avif","KHR_meshopt_compression"]}""")

        val error = assertThrows(UnsupportedGltfExtensionException::class.java) {
            GltfRequiredExtensions.check(source)
        }

        assertEquals(listOf("EXT_texture_avif", "KHR_meshopt_compression"), error.extensions)
        assertTrue(error.message!!, "EXT_texture_avif" in error.message!!)
    }

    @Test
    fun `the check leaves the buffer position alone`() {
        val source = glb("""{"extensionsRequired":["KHR_mesh_quantization"]}""")
        val position = source.position()

        GltfRequiredExtensions.check(source)

        assertEquals(position, source.position())
    }

    @Test
    fun `only the required array is read, not the base64 a text glTF embeds`() {
        // Broken JSON after the array: a whole-document parse failed and let such a file through.
        val base64 = "A".repeat(1 shl 20)
        val source = ByteBuffer.wrap(
            ("{ \"extensionsRequired\" :\n [ \"EXT_texture_\\u0061vif\" , \"KHR_texture_transform\" ],\n" +
                "\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,$base64\"}],,}").toByteArray(),
        )

        assertEquals(listOf("EXT_texture_avif"), GltfRequiredExtensions.unsupported(source))
    }

    @Test
    fun `the key inside a string value, or a malformed array, is left to gltfio`() {
        val quoted = glb("""{"asset":{"generator":"writes \"extensionsRequired\": [\"EXT_texture_avif\"]"}}""")
        val malformed = glb("""{"extensionsRequired":["EXT_texture_avif" "KHR_texture_transform"]}""")

        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(quoted))
        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(malformed))
    }

    @Test
    fun `only the root object's own key counts, not one under extras or a nested object`() {
        val underExtras = glb("""{"asset":{"version":"2.0"},"extras":{"extensionsRequired":["EXT_texture_avif"]}}""")
        val nested = glb("""{"materials":[{"extensions":{"extensionsRequired":["EXT_texture_avif"]}}]}""")

        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(underExtras))
        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(nested))
    }

    @Test
    fun `the root key is found after nested objects, strings, numbers and literals`() {
        val source = glb(
            """{"asset":{"generator":"a } ] \" {","version":"2.0"},"scene":0,"ok":true,"none":null,""" +
                """"extras":{"extensionsRequired":["KHR_texture_basisu"]},"nodes":[[1,{"a":[2]}],-1.5e3],""" +
                """"extensionsRequired":["KHR_meshopt_compression"]}""",
        )

        assertEquals(listOf("KHR_meshopt_compression"), GltfRequiredExtensions.unsupported(source))
    }

    @Test
    fun `a root key whose value is not an array of strings is left to gltfio`() {
        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(glb("""{"extensionsRequired":"EXT_texture_avif"}""")))
        assertEquals(emptyList<String>(), GltfRequiredExtensions.unsupported(glb("""[{"extensionsRequired":["EXT_texture_avif"]}]""")))
    }

    @Test
    fun `the supported set is what gltfio 1_72_1 reads, plus quantized attributes`() {
        val supported = ModelLoader.SUPPORTED_GLTF_EXTENSIONS

        assertTrue("KHR_mesh_quantization" in supported)
        assertTrue("EXT_meshopt_compression" in supported)
        assertTrue("EXT_texture_avif" !in supported)
        // Parsed by cgltf, never read by gltfio's AssetLoader.
        for (ignored in listOf(
            "KHR_materials_anisotropy", "KHR_materials_iridescence",
            "KHR_materials_diffuse_transmission", "EXT_mesh_gpu_instancing",
        )) assertTrue(ignored, ignored !in supported)
    }

    private fun glb(json: String): ByteBuffer {
        val padded = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        return ByteBuffer.allocate(12 + 8 + padded.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            putInt(capacity())
            putInt(padded.size)
            putInt(0x4E4F534A)
            put(padded)
            flip()
        }
    }
}
