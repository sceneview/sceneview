package io.github.sceneview.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The checks that turn an unframable or invisible model into a failure instead of an empty image. */
class ModelSnapshotChecksTest {

    private val quantizedJson =
        """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_mesh_quantization", "KHR_texture_transform"],""" +
            """"extensionsRequired":["KHR_mesh_quantization"]}"""

    /** A binary glTF: header, the JSON chunk (space-padded to 4 bytes), then a BIN chunk. */
    private fun glb(json: String, firstChunkType: Int = 0x4E4F534A): ByteArray {
        val text = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val bin = ByteArray(16) { 0x7F }
        val total = 12 + 8 + text.size + 8 + bin.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(total)
            .putInt(text.size).putInt(firstChunkType).put(text)
            .putInt(bin.size).putInt(0x004E4942).put(bin)
            .array()
    }

    @Test
    fun extensionsUsedAreListed() {
        assertEquals(listOf(KHR_MESH_QUANTIZATION, "KHR_texture_transform"), gltfExtensionsUsed(quantizedJson))
    }

    @Test
    fun noExtensionsUsedIsEmpty() {
        assertEquals(emptyList<String>(), gltfExtensionsUsed("""{"asset":{"version":"2.0"}}"""))
        assertEquals(emptyList<String>(), gltfExtensionsUsed("""{"extensionsUsed":[]}"""))
    }

    @Test
    fun glbJsonChunkIsRead() {
        val json = readGltfJson(ByteArrayInputStream(glb(quantizedJson)))
        assertEquals(quantizedJson, json?.trimEnd())
        assertEquals(listOf(KHR_MESH_QUANTIZATION, "KHR_texture_transform"), gltfExtensionsUsed(json!!))
    }

    @Test
    fun glbWhoseFirstChunkIsNotJsonIsRejected() {
        assertNull(readGltfJson(ByteArrayInputStream(glb(quantizedJson, firstChunkType = 0x004E4942))))
    }

    @Test
    fun truncatedGlbIsRejected() {
        val bytes = glb(quantizedJson)
        assertNull(readGltfJson(ByteArrayInputStream(bytes.copyOf(30))))
        assertNull(readGltfJson(ByteArrayInputStream(bytes.copyOf(8))))
    }

    @Test
    fun gltfTextIsReadWhole() {
        val text = "\n  $quantizedJson\n"
        assertEquals(text, readGltfJson(ByteArrayInputStream(text.toByteArray())))
    }

    @Test
    fun anythingElseIsRejected() {
        assertNull(readGltfJson(ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3))))
        assertNull(readGltfJson(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun anyNonZeroAlphaIsVisible() {
        val transparent = ByteBuffer.allocate(4 * 9)
        assertFalse(hasVisiblePixel(transparent))
        // Colour without alpha (premultiplied: impossible, but not "drawn" either).
        transparent.put(0, 0x7F).put(5, 0x7F).put(10, 0x7F)
        assertFalse(hasVisiblePixel(transparent))
        transparent.put(4 * 8 + 3, 1)
        assertTrue(hasVisiblePixel(transparent))
    }

    @Test
    fun onlyTheLimitIsScanned() {
        val pixels = ByteBuffer.allocate(4 * 4).put(4 * 3 + 3, -1)
        pixels.limit(4 * 3)
        assertFalse(hasVisiblePixel(pixels))
    }

    @Test
    fun finiteNonEmptyBoxesAreFramable() {
        assertTrue(isFramable(floatArrayOf(0f, 1f, 0f), floatArrayOf(0.5f, 0.5f, 0.5f)))
        // A flat quad: one axis without extent is still a picture.
        assertTrue(isFramable(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 1f)))
    }

    @Test
    fun emptyOrNonFiniteBoxesAreNot() {
        assertFalse(isFramable(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f)))
        assertFalse(isFramable(floatArrayOf(Float.NaN, 0f, 0f), floatArrayOf(1f, 1f, 1f)))
        assertFalse(isFramable(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, Float.POSITIVE_INFINITY, 1f)))
        assertFalse(isFramable(floatArrayOf(0f, 0f, 0f), floatArrayOf(-1f, 1f, 1f)))
    }
}
