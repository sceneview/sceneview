package io.github.sceneview.loaders

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contract of the `EXT_texture_webp` → PNG rewrite that makes WebP-textured glTF assets load on
 * Filament's Android build, which ships no `image/webp` texture provider (#2305).
 *
 * The rewrite itself is pure buffer/JSON surgery, so it is fully testable on the JVM with a fake
 * [WebPTextureTranscoder.ImageTranscoder] — the real [android.graphics.BitmapFactory] decode is
 * verified on device instead.
 */
@RunWith(RobolectricTestRunner::class)
class WebPTextureTranscoderTest {

    private val webPBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private var decodeCount = 0
    private val fakeTranscoder = WebPTextureTranscoder.ImageTranscoder {
        decodeCount++
        pngBytes
    }
    private val failingTranscoder = WebPTextureTranscoder.ImageTranscoder { null }

    @Test
    fun `glb with embedded webp textures is rewritten to png`() {
        val json = JSONObject(
            """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "extensionsRequired": ["EXT_texture_webp"],
              "buffers": [{"byteLength": 8}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 4}],
              "images": [{"mimeType": "image/webp", "bufferView": 0}],
              "textures": [{"sampler": 0, "extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
            """.trimIndent()
        )
        val bin = webPBytes + byteArrayOf(0, 0, 0, 0)

        val result = transcode(glb(json, bin))

        val (rewritten, newBin) = parseGlb(result)
        assertEquals(1, decodeCount)
        assertFalse(rewritten.toString().contains("image/webp"))
        assertFalse(rewritten.toString().contains("EXT_texture_webp"))
        assertNull(rewritten.optJSONArray("extensionsRequired"))

        val image = rewritten.getJSONArray("images").getJSONObject(0)
        assertEquals("image/png", image.getString("mimeType"))
        val view = rewritten.getJSONArray("bufferViews").getJSONObject(image.getInt("bufferView"))
        assertEquals(0, view.getInt("buffer"))
        assertEquals(pngBytes.size, view.getInt("byteLength"))
        val offset = view.getInt("byteOffset")
        assertEquals(0, offset % 4)
        assertArrayEquals(pngBytes, newBin.copyOfRange(offset, offset + pngBytes.size))

        // The texture now points at the image directly, and the buffer length matches the new BIN.
        val texture = rewritten.getJSONArray("textures").getJSONObject(0)
        assertEquals(0, texture.getInt("source"))
        assertFalse(texture.has("extensions"))
        assertEquals(newBin.size, rewritten.getJSONArray("buffers").getJSONObject(0).getInt("byteLength"))
    }

    @Test
    fun `an image declaring no mimeType is still transcoded through its extension`() {
        // Neither `image/webp` nor an unescaped slash appears anywhere here: the WebP-ness of the
        // asset is carried only by the extension name, which the pre-parse gate must still catch.
        val json = JSONObject(
            """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "buffers": [{"byteLength": 4}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 4}],
              "images": [{"bufferView": 0}],
              "textures": [{"extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
            """.trimIndent()
        )
        val (rewritten, _) = parseGlb(transcode(glb(json, webPBytes)))

        assertEquals(1, decodeCount)
        assertEquals(0, rewritten.getJSONArray("textures").getJSONObject(0).getInt("source"))
        assertEquals(
            "image/png",
            rewritten.getJSONArray("images").getJSONObject(0).getString("mimeType")
        )
    }

    @Test
    fun `glb without webp textures is returned untouched`() {
        val json = JSONObject(
            """
            {
              "buffers": [{"byteLength": 4}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 4}],
              "images": [{"mimeType": "image/png", "bufferView": 0}],
              "textures": [{"source": 0}]
            }
            """.trimIndent()
        )
        val source = glb(json, pngBytes.copyOfRange(0, 4))

        assertSame(source, transcode(source))
        assertEquals(0, decodeCount)
    }

    @Test
    fun `a texture keeping a png fallback drops the extension without decoding`() {
        val json = JSONObject(
            """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "buffers": [{"byteLength": 8}],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 4},
                {"buffer": 0, "byteOffset": 4, "byteLength": 4}
              ],
              "images": [
                {"mimeType": "image/png", "bufferView": 0},
                {"mimeType": "image/webp", "bufferView": 1}
              ],
              "textures": [{"source": 0, "extensions": {"EXT_texture_webp": {"source": 1}}}]
            }
            """.trimIndent()
        )
        val result = transcode(glb(json, pngBytes))

        val (rewritten, _) = parseGlb(result)
        // Image 1 is still transcoded (it is declared image/webp) but the texture keeps its
        // original PNG source rather than being repointed.
        assertEquals(1, decodeCount)
        val texture = rewritten.getJSONArray("textures").getJSONObject(0)
        assertEquals(0, texture.getInt("source"))
        assertFalse(texture.has("extensions"))
    }

    @Test
    fun `an undecodable webp texture is reported instead of silently untextured`() {
        val json = JSONObject(
            """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "extensionsRequired": ["EXT_texture_webp"],
              "images": [
                {"mimeType": "image/webp", "uri": "external.webp"},
                {"mimeType": "image/webp", "bufferView": 0}
              ],
              "buffers": [{"byteLength": 4}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 4}],
              "textures": [{"extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
            """.trimIndent()
        )
        var reported = -1
        val result = WebPTextureTranscoder.transcode(
            glb(json, webPBytes),
            fakeTranscoder
        ) { reported = it }

        // The external-URI image cannot be read here, the embedded one can.
        assertEquals(1, reported)
        val (rewritten, _) = parseGlb(result)
        // The extension declaration must survive while an image still needs it.
        assertTrue(rewritten.getJSONArray("extensionsRequired").getString(0) == "EXT_texture_webp")
        assertEquals("image/webp", rewritten.getJSONArray("images").getJSONObject(0).getString("mimeType"))
        assertEquals("image/png", rewritten.getJSONArray("images").getJSONObject(1).getString("mimeType"))
    }

    @Test
    fun `nothing decodable means the payload is passed through, but still reported`() {
        val json = JSONObject(
            """
            {
              "images": [{"mimeType": "image/webp", "uri": "external.webp"}],
              "textures": [{"source": 0}]
            }
            """.trimIndent()
        )
        var reported = -1
        val source = glb(json, ByteArray(0))
        val result = WebPTextureTranscoder.transcode(source, failingTranscoder) { reported = it }

        assertSame(source, result)
        assertEquals(1, reported)
    }

    @Test
    fun `gltf json data uris are re-encoded as png data uris`() {
        val webPDataUri = "data:image/webp;base64," + Base64.encodeToString(webPBytes, Base64.NO_WRAP)
        val json = """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "images": [{"mimeType": "image/webp", "uri": "$webPDataUri"}],
              "textures": [{"extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
        """.trimIndent()

        val result = transcode(ByteBuffer.wrap(json.toByteArray()))

        val rewritten = JSONObject(String(ByteArray(result.remaining()).also { result.get(it) }))
        val uri = rewritten.getJSONArray("images").getJSONObject(0).getString("uri")
        assertTrue(uri.startsWith("data:image/png;base64,"))
        assertArrayEquals(
            pngBytes,
            Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
        )
        assertEquals(0, rewritten.getJSONArray("textures").getJSONObject(0).getInt("source"))
        assertNull(rewritten.optJSONArray("extensionsUsed"))
    }

    @Test
    fun `a truncated glb is passed through rather than throwing`() {
        val truncated = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            rewind()
        }
        assertSame(truncated, transcode(truncated))
    }

    private fun transcode(buffer: ByteBuffer) =
        WebPTextureTranscoder.transcode(buffer, fakeTranscoder) {}

    private fun glb(json: JSONObject, bin: ByteArray): ByteBuffer {
        val jsonBytes = json.toString().toByteArray()
        val jsonPadding = (4 - jsonBytes.size % 4) % 4
        val binPadding = (4 - bin.size % 4) % 4
        val total = 12 + 8 + jsonBytes.size + jsonPadding +
                if (bin.isEmpty()) 0 else 8 + bin.size + binPadding
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            putInt(total)
            putInt(jsonBytes.size + jsonPadding)
            putInt(0x4E4F534A)
            put(jsonBytes)
            repeat(jsonPadding) { put(' '.code.toByte()) }
            if (bin.isNotEmpty()) {
                putInt(bin.size + binPadding)
                putInt(0x004E4942)
                put(bin)
                repeat(binPadding) { put(0) }
            }
            rewind()
        }
    }

    private fun parseGlb(buffer: ByteBuffer): Pair<JSONObject, ByteArray> {
        val glb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var json: JSONObject? = null
        var bin = ByteArray(0)
        while (offset + 8 <= glb.limit()) {
            val length = glb.getInt(offset)
            val type = glb.getInt(offset + 4)
            val chunk = ByteArray(length)
            glb.duplicate().apply { position(offset + 8) }.get(chunk)
            when (type) {
                0x4E4F534A -> json = JSONObject(String(chunk).trim())
                0x004E4942 -> bin = chunk
            }
            offset += 8 + length + (4 - length % 4) % 4
        }
        return json!! to bin
    }
}
