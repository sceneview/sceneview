package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Which of a model's external resources are buffers, the ones a load cannot go on without. */
@RunWith(RobolectricTestRunner::class) // org.json is an Android API
class GltfBufferUrisTest {

    private val json =
        """{"buffers":[{"uri":"scene.bin"},{"uri":"data:application/octet-stream;base64,AAAA"}],""" +
            """"images":[{"uri":"albedo.png"},{"uri":"normal.bin.png"}]}"""

    @Test
    fun `a glTF's buffers are its declared buffer uris, not its images`() {
        val uris = listOf("scene.bin", "albedo.png", "normal.bin.png")

        assertEquals(setOf("scene.bin"), GltfBufferUris.of(ByteBuffer.wrap(json.toByteArray()), uris))
    }

    @Test
    fun `a GLB's buffers come from its JSON chunk`() {
        assertEquals(setOf("scene.bin"), GltfBufferUris.of(glb(json), listOf("scene.bin", "albedo.png")))
    }

    @Test
    fun `data uris are never buffers to fetch`() {
        val dataUri = "data:application/octet-stream;base64,AAAA"

        assertEquals(emptySet<String>(), GltfBufferUris.of(ByteBuffer.wrap(json.toByteArray()), listOf(dataUri)))
    }

    @Test
    fun `a percent-encoded uri has the key cgltf decodes it to`() {
        assertEquals("my model.bin", GltfBufferUris.cgltfKey("my%20model.bin"))
        assertEquals("upper and lower hex digits", "a\u00E9/b.bin", GltfBufferUris.cgltfKey("a%C3%a9%2Fb.bin"))
        assertEquals("a malformed sequence is left as is", "100% a%2 %zz b.bin", GltfBufferUris.cgltfKey("100% a%2 %zz%20b.bin"))
        assertEquals("a trailing pair is decoded", "a.bin ", GltfBufferUris.cgltfKey("a.bin%20"))
        assertEquals("no %: the uri itself", null, GltfBufferUris.cgltfKey("my model.bin"))
        assertEquals("nothing decoded: the uri itself", null, GltfBufferUris.cgltfKey("100%.bin"))
        assertEquals("%25 is decoded once", "a%20.bin", GltfBufferUris.cgltfKey("a%2520.bin"))
        assertEquals("bytes that are not UTF-8", null, GltfBufferUris.cgltfKey("a%FF.bin"))
        assertEquals("a NUL ends a C string", null, GltfBufferUris.cgltfKey("a%00.bin"))
        assertEquals("JNI encodes a character outside the BMP otherwise", null, GltfBufferUris.cgltfKey("%F0%9F%98%80.bin"))
        assertEquals("a % two bytes from the end is left as is", null, GltfBufferUris.cgltfKey("x%2"))
        assertEquals("a % one byte from the end is left as is", null, GltfBufferUris.cgltfKey("%4"))
    }

    @Test
    fun `a uri cgltf decodes to a key no resource can be fed under is unfeedable`() {
        listOf("a%FF.bin", "a%00.bin", "%F0%9F%98%80.bin", "caf%E9.bin").forEach {
            assertTrue(it, GltfBufferUris.isUnfeedable(it))
        }
        listOf("my%20model.bin", "100%.bin", "x%2", "scene.bin").forEach {
            assertFalse(it, GltfBufferUris.isUnfeedable(it))
        }
    }

    @Test
    fun `an unreadable payload falls back to the bin suffix`() {
        val garbage = ByteBuffer.wrap(byteArrayOf(1, 2, 3))

        assertEquals(setOf("a.BIN"), GltfBufferUris.of(garbage, listOf("a.BIN", "b.png")))
    }

    private fun glb(json: String): ByteBuffer {
        val padded = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        return ByteBuffer.allocate(GLB_HEADER + CHUNK_HEADER + padded.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            putInt(capacity())
            putInt(padded.size)
            putInt(0x4E4F534A)
            put(padded)
            flip()
        }
    }

    private companion object {
        const val GLB_HEADER = 12
        const val CHUNK_HEADER = 8
    }
}
