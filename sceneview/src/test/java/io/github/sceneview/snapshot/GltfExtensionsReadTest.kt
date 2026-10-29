package io.github.sceneview.snapshot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [readGltfExtensionsUsed] tells "lists no extension" from "could not be checked": a model whose
 * glTF cannot be read is rendered but never cached, since its quantized positions went unchecked.
 */
@RunWith(RobolectricTestRunner::class)
class GltfExtensionsReadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun a_readable_gltf_gives_its_extensions() {
        val file = folder.newFile("quantized.gltf")
        file.writeText("""{"asset":{"version":"2.0"},"extensionsUsed":["KHR_mesh_quantization"]}""")

        assertEquals(listOf(KHR_MESH_QUANTIZATION), readGltfExtensionsUsed(context, "file://${file.path}"))
    }

    @Test
    fun a_readable_gltf_without_extensions_gives_an_empty_list() {
        val file = folder.newFile("plain.gltf")
        file.writeText("""{"asset":{"version":"2.0"}}""")

        assertEquals(emptyList<String>(), readGltfExtensionsUsed(context, "file://${file.path}"))
    }

    @Test
    fun an_http_model_missing_from_the_file_cache_cannot_be_checked() {
        assertNull(readGltfExtensionsUsed(context, "https://example.com/never-downloaded.glb"))
    }

    @Test
    fun a_resource_uri_by_name_cannot_be_checked() {
        assertNull(readGltfExtensionsUsed(context, "android.resource://${context.packageName}/raw/chair"))
    }

    @Test
    fun a_missing_file_or_a_file_that_is_not_gltf_cannot_be_checked() {
        val text = folder.newFile("notes.txt").apply { writeText("not a model") }

        assertNull(readGltfExtensionsUsed(context, "file://${folder.root.path}/missing.glb"))
        assertNull(readGltfExtensionsUsed(context, "file://${text.path}"))
    }
}
