package io.github.sceneview.web

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract of the `EXT_texture_webp` → PNG rewrite that makes WebP-textured glTF assets load on
 * Filament.js, which registers no `image/webp` texture provider (#3085).
 *
 * The container surgery is pure buffer/JSON work, so most of it is asserted with a fake
 * [WebPTextureTranscoder.ImageTranscoder] — deterministic PNG bytes in, deterministic layout out.
 * The **real** browser decode (`createImageBitmap` → canvas → `toBlob`) is exercised too, by
 * [`the platform transcoder really decodes a WebP through the browser`], because Karma runs this
 * suite in a headless Chrome that has the decoder Filament.js lacks.
 *
 * Every helper below (GLB build, GLB parse, UTF-8, base64) is written *independently* of the
 * production code, so a mutation in `WebPTextureTranscoder` cannot silently mutate the oracle.
 */
class WebPTextureTranscoderTest {

    /** "RIFF" — enough for the fake transcoder; the real decode test uses a real WebP. */
    private val webPBytes = bytesOf(0x52, 0x49, 0x46, 0x46)
    private val pngBytes = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private var decodeCount = 0

    private val fakeTranscoder = WebPTextureTranscoder.ImageTranscoder {
        decodeCount++
        Promise.resolve(pngBytes)
    }

    private val failingTranscoder = WebPTextureTranscoder.ImageTranscoder {
        Promise.resolve<Uint8Array?>(null)
    }

    private fun transcode(buffer: ArrayBuffer): Promise<ArrayBuffer> =
        WebPTextureTranscoder.transcode(buffer, fakeTranscoder) {}

    // --- The rewrite -------------------------------------------------------

    @Test
    fun glbWithEmbeddedWebPTexturesIsRewrittenToPng(): Promise<Unit> {
        val json = """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "extensionsRequired": ["EXT_texture_webp"],
              "buffers": [{"byteLength": 8}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 4}],
              "images": [{"mimeType": "image/webp", "bufferView": 0}],
              "textures": [{"sampler": 0, "extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
        """.trimIndent()
        val bin = concat(webPBytes, Uint8Array(4))

        return transcode(glb(json, bin)).then { result ->
            val parsed = parseGlb(result)
            assertEquals(1, decodeCount, "the one embedded WebP image must be decoded exactly once")
            assertFalse(
                parsed.jsonText.contains("image/webp"),
                "no image may still declare image/webp after the rewrite",
            )
            assertFalse(
                parsed.jsonText.contains("EXT_texture_webp"),
                "every trace of EXT_texture_webp must be gone once all images are converted",
            )
            assertNull(
                parsed.json.extensionsRequired,
                "extensionsRequired must be dropped, not left declaring an unused extension",
            )

            val image = parsed.json.images[0]
            assertEquals("image/png", image.mimeType as String, "the image mime must become PNG")
            val view = parsed.json.bufferViews[image.bufferView as Int]
            assertEquals(0, view.buffer as Int, "the appended view must reference buffer 0")
            assertEquals(
                pngBytes.length,
                view.byteLength as Int,
                "the appended view must be exactly as long as the PNG it describes",
            )
            val offset = view.byteOffset as Int
            assertEquals(
                0,
                offset % 4,
                "a buffer view holding image data must start on a 4-byte boundary",
            )
            assertBytesEqual(
                pngBytes,
                parsed.bin.subarray(offset, offset + pngBytes.length),
                "the appended view must point at the PNG bytes",
            )

            // The texture now points at the image directly, and the buffer length matches the BIN.
            val texture = parsed.json.textures[0]
            assertEquals(
                0,
                texture.source as Int,
                "the texture must be repointed at the converted image",
            )
            assertNull(
                texture.extensions,
                "an emptied `extensions` object must be removed from the texture",
            )
            assertEquals(
                parsed.bin.length,
                parsed.json.buffers[0].byteLength as Int,
                "buffer 0's byteLength must match the grown BIN chunk",
            )
        }
    }

    /**
     * Two images whose PNG re-encodings are **not** a multiple of four bytes long. The first
     * append lands on the already-aligned end of the BIN chunk whatever the code does; only the
     * second one proves the pad exists, and glTF requires a buffer view holding image data to
     * start on a 4-byte boundary.
     */
    @Test
    fun appendedViewsStayAlignedWhenAPngLengthIsNotAMultipleOfFour(): Promise<Unit> {
        val oddPng = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A) // 6 bytes — deliberately not 4|n
        val transcoder = WebPTextureTranscoder.ImageTranscoder { Promise.resolve(oddPng) }
        val json = """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "buffers": [{"byteLength": 8}],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 4},
                {"buffer": 0, "byteOffset": 4, "byteLength": 4}
              ],
              "images": [
                {"mimeType": "image/webp", "bufferView": 0},
                {"mimeType": "image/webp", "bufferView": 1}
              ],
              "textures": [
                {"extensions": {"EXT_texture_webp": {"source": 0}}},
                {"extensions": {"EXT_texture_webp": {"source": 1}}}
              ]
            }
        """.trimIndent()
        val source = glb(json, concat(webPBytes, webPBytes))

        return WebPTextureTranscoder.transcode(source, transcoder) {}.then { result ->
            val parsed = parseGlb(result)
            for (image in 0..1) {
                val view = parsed.json.bufferViews[parsed.json.images[image].bufferView as Int]
                val offset = view.byteOffset as Int
                assertEquals(
                    0,
                    offset % 4,
                    "appended image view $image must start on a 4-byte boundary",
                )
                assertBytesEqual(
                    oddPng,
                    parsed.bin.subarray(offset, offset + (view.byteLength as Int)),
                    "appended image view $image must point at its own PNG bytes",
                )
            }
            assertTrue(
                (parsed.json.buffers[0].byteLength as Int) <= parsed.bin.length,
                "buffer 0 must not claim more bytes than the padded BIN chunk holds",
            )
        }
    }

    @Test
    fun anImageDeclaringNoMimeTypeIsStillTranscodedThroughItsExtension(): Promise<Unit> {
        // Neither `image/webp` nor an unescaped slash appears anywhere here: the WebP-ness of the
        // asset is carried only by the extension name, which the pre-parse gate must still catch.
        val json = """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "buffers": [{"byteLength": 4}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 4}],
              "images": [{"bufferView": 0}],
              "textures": [{"extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
        """.trimIndent()

        return transcode(glb(json, webPBytes)).then { result ->
            val parsed = parseGlb(result)
            assertEquals(
                1,
                decodeCount,
                "an image whose only WebP marker is EXT_texture_webp must still be decoded",
            )
            assertEquals(
                0,
                parsed.json.textures[0].source as Int,
                "the texture must be repointed at the converted image",
            )
            assertEquals(
                "image/png",
                parsed.json.images[0].mimeType as String,
                "the converted image must declare image/png",
            )
        }
    }

    @Test
    fun aTextureKeepingAPngFallbackDropsTheExtensionWithoutRepointing(): Promise<Unit> {
        val json = """
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

        return transcode(glb(json, pngBytes)).then { result ->
            val parsed = parseGlb(result)
            // Image 1 is still transcoded (it is declared image/webp) but the texture keeps its
            // original PNG source rather than being repointed.
            assertEquals(1, decodeCount, "only the image/webp image may be decoded")
            val texture = parsed.json.textures[0]
            assertEquals(
                0,
                texture.source as Int,
                "a texture with a non-WebP fallback source must keep that source",
            )
            assertNull(
                texture.extensions,
                "the WebP extension must be dropped from a texture that has a fallback",
            )
        }
    }

    // --- Pass-through (the non-WebP path must never regress) ---------------

    @Test
    fun glbWithoutWebPTexturesIsReturnedUntouched(): Promise<Unit> {
        val json = """
            {
              "buffers": [{"byteLength": 4}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 4}],
              "images": [{"mimeType": "image/png", "bufferView": 0}],
              "textures": [{"source": 0}]
            }
        """.trimIndent()
        val source = glb(json, pngBytes.subarray(0, 4))

        return transcode(source).then { result ->
            assertSame(
                source,
                result,
                "a GLB with no WebP must come back as the very same ArrayBuffer instance, " +
                    "not a re-serialised copy",
            )
            assertEquals(0, decodeCount, "a GLB with no WebP must decode nothing")
        }
    }

    @Test
    fun aChunkLengthThatWouldOverflowTheBoundsCheckIsPassedThrough(): Promise<Unit> {
        val source = ArrayBuffer(20)
        val view = DataView(source)
        view.setInt32(0, 0x46546C67, littleEndian = true)
        view.setInt32(4, 2, littleEndian = true)
        view.setInt32(8, 20, littleEndian = true)
        // chunkLength: an Int sum would wrap negative and sail past a naive bounds check.
        view.setInt32(12, Int.MAX_VALUE, littleEndian = true)
        view.setInt32(16, 0x4E4F534A, littleEndian = true)

        return transcode(source).then { result ->
            assertSame(
                source,
                result,
                "a GLB whose chunk table overflows its own bounds must be passed through untouched",
            )
        }
    }

    @Test
    fun aTruncatedGlbIsPassedThroughRatherThanThrowing(): Promise<Unit> {
        val source = ArrayBuffer(8)
        val view = DataView(source)
        view.setInt32(0, 0x46546C67, littleEndian = true)
        view.setInt32(4, 2, littleEndian = true)

        return transcode(source).then { result ->
            assertSame(
                source,
                result,
                "a GLB too short to hold a chunk table must be passed through untouched",
            )
        }
    }

    // --- Reporting what could not be converted -----------------------------

    @Test
    fun anUndecodableWebPTextureIsReportedInsteadOfSilentlyUntextured(): Promise<Unit> {
        val json = """
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
        var reported = -1

        return WebPTextureTranscoder.transcode(glb(json, webPBytes), fakeTranscoder) {
            reported = it
        }.then { result ->
            // The external-URI image cannot be read here, the embedded one can.
            assertEquals(
                1,
                reported,
                "the one image that could not be converted must be reported to the caller",
            )
            val parsed = parseGlb(result)
            assertEquals(
                "EXT_texture_webp",
                parsed.json.extensionsRequired[0] as String,
                "the extension declaration must survive while an image still needs it",
            )
            assertEquals(
                "image/webp",
                parsed.json.images[0].mimeType as String,
                "an image that could not be decoded must keep its original mime type",
            )
            assertEquals(
                "image/png",
                parsed.json.images[1].mimeType as String,
                "the decodable image must still be converted",
            )
        }
    }

    @Test
    fun nothingDecodableMeansThePayloadIsPassedThroughButStillReported(): Promise<Unit> {
        val json = """
            {
              "images": [{"mimeType": "image/webp", "uri": "external.webp"}],
              "textures": [{"source": 0}]
            }
        """.trimIndent()
        val source = glb(json)
        var reported = -1

        return WebPTextureTranscoder.transcode(source, failingTranscoder) { reported = it }
            .then { result ->
                assertSame(
                    source,
                    result,
                    "a payload where nothing could be converted must be passed through untouched",
                )
                assertEquals(1, reported, "the untranscodable image must still be reported")
            }
    }

    // --- data: URIs --------------------------------------------------------

    @Test
    fun gltfJsonDataUrisAreReEncodedAsPngDataUris(): Promise<Unit> {
        val webPDataUri = "data:image/webp;base64," + toBase64(webPBytes)
        val json = """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "images": [{"mimeType": "image/webp", "uri": "$webPDataUri"}],
              "textures": [{"extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
        """.trimIndent()

        return transcode(toArrayBuffer(encode(json))).then { result ->
            val rewritten = JSON.parse<dynamic>(decode(Uint8Array(result)))
            val uri = rewritten.images[0].uri as String
            assertTrue(
                uri.startsWith("data:image/png;base64,"),
                "a converted data: URI must declare the PNG mime type, got: ${uri.take(32)}",
            )
            assertBytesEqual(
                pngBytes,
                fromBase64(uri.substringAfter("base64,")),
                "the data: URI must carry the PNG bytes",
            )
            assertEquals(
                0,
                rewritten.textures[0].source as Int,
                "the texture must be repointed at the converted image",
            )
            assertNull(
                rewritten.extensionsUsed,
                "extensionsUsed must be dropped once no image needs EXT_texture_webp",
            )
        }
    }

    @Test
    fun aGlbWithNoBinaryBufferKeepsItsPngInADataUri(): Promise<Unit> {
        // Everything lives in `data:` URIs here, so appending a `buffer: 0` view would describe
        // bytes no BIN chunk holds — a glTF Filament would then reject outright.
        val webPDataUri = "data:image/webp;base64," + toBase64(webPBytes)
        val json = """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "images": [{"mimeType": "image/webp", "uri": "$webPDataUri"}],
              "textures": [{"extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
        """.trimIndent()

        return transcode(glb(json)).then { result ->
            val parsed = parseGlb(result)
            assertEquals(1, decodeCount, "the data: URI image must be decoded")
            assertEquals(0, parsed.bin.length, "a GLB with no BIN chunk must not grow one")
            assertNull(
                parsed.json.bufferViews,
                "no buffer view may be appended when there is no binary buffer to hold it",
            )
            val image = parsed.json.images[0]
            assertNull(
                image.bufferView,
                "the image must stay URI-backed, not point at a non-existent buffer view",
            )
            val uri = image.uri as String
            assertTrue(
                uri.startsWith("data:image/png;base64,"),
                "the image must come back as a PNG data: URI, got: ${uri.take(32)}",
            )
            assertBytesEqual(
                pngBytes,
                fromBase64(uri.substringAfter("base64,")),
                "the data: URI must carry the PNG bytes",
            )
        }
    }

    // --- The real browser decode -------------------------------------------

    /**
     * The one test that runs the **production** [WebPTextureTranscoder.platformTranscoder]: a real
     * 2×2 lossless WebP goes in, and a real PNG of the same size must come out of the
     * `createImageBitmap` → canvas → `toBlob` chain. Everything above it fakes the decode.
     */
    @Test
    fun thePlatformTranscoderReallyDecodesAWebPThroughTheBrowser(): Promise<Unit> {
        val webP = fromBase64(TINY_2X2_WEBP_BASE64)
        val json = """
            {
              "extensionsUsed": ["EXT_texture_webp"],
              "extensionsRequired": ["EXT_texture_webp"],
              "buffers": [{"byteLength": ${webP.length}}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": ${webP.length}}],
              "images": [{"mimeType": "image/webp", "bufferView": 0}],
              "textures": [{"extensions": {"EXT_texture_webp": {"source": 0}}}]
            }
        """.trimIndent()
        val source = glb(json, webP)

        return WebPTextureTranscoder.transcode(source) {}.then { result ->
            assertNotSame(
                source,
                result,
                "a real WebP-textured GLB must be rewritten, not passed through",
            )
            val parsed = parseGlb(result)
            val image = parsed.json.images[0]
            assertEquals(
                "image/png",
                image.mimeType as String,
                "the browser-decoded image must be re-declared as image/png",
            )
            val view = parsed.json.bufferViews[image.bufferView as Int]
            val offset = view.byteOffset as Int
            val png = parsed.bin.subarray(offset, offset + (view.byteLength as Int))
            assertTrue(
                png.length > 8,
                "the re-encoded PNG must not be empty (got ${png.length} bytes)",
            )
            assertBytesEqual(
                bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
                png.subarray(0, 8),
                "the re-encoded image must carry the 8-byte PNG signature",
            )
            png
        }.then { png ->
            // Decode the produced PNG back: only a correctly sized canvas round-trip yields 2x2.
            decodeSize(png, "image/png").then { size ->
                assertEquals(
                    2 to 2,
                    size,
                    "the re-encoded PNG must keep the source WebP's 2x2 dimensions",
                )
            }
        }.unsafeCast<Promise<Unit>>()
    }

    // --- Helpers, written independently of the production code -------------

    private companion object {
        /** A 2×2 lossless WebP (red, green / blue, semi-transparent white). */
        const val TINY_2X2_WEBP_BASE64 =
            "UklGRjAAAABXRUJQVlA4TCMAAAAvAUAAEB8gEEjeHzqN+RcQFPwfnYCg6LrlImYPwg0YIvofAgA="
    }

    private fun bytesOf(vararg values: Int): Uint8Array {
        val bytes = Uint8Array(values.size)
        for (i in values.indices) bytes[i] = values[i].toByte()
        return bytes
    }

    private fun concat(first: Uint8Array, second: Uint8Array): Uint8Array {
        val out = Uint8Array(first.length + second.length)
        out.set(first, 0)
        out.set(second, first.length)
        return out
    }

    private fun encode(text: String): Uint8Array =
        js("new TextEncoder()").encode(text).unsafeCast<Uint8Array>()

    private fun decode(bytes: Uint8Array): String =
        js("new TextDecoder('utf-8')").decode(bytes) as String

    private fun toArrayBuffer(bytes: Uint8Array): ArrayBuffer =
        bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length)

    private fun fromBase64(base64: String): Uint8Array {
        val binary = js("globalThis").atob(base64) as String
        val bytes = Uint8Array(binary.length)
        for (i in binary.indices) bytes[i] = binary[i].code.toByte()
        return bytes
    }

    private fun toBase64(bytes: Uint8Array): String {
        val binary = StringBuilder()
        for (i in 0 until bytes.length) binary.append((bytes[i].toInt() and 0xFF).toChar())
        return js("globalThis").btoa(binary.toString()) as String
    }

    private fun assertBytesEqual(expected: Uint8Array, actual: Uint8Array, message: String) {
        assertEquals(expected.length, actual.length, "$message (length)")
        for (i in 0 until expected.length) {
            assertEquals(expected[i], actual[i], "$message (byte $i)")
        }
    }

    /** Decodes [bytes] with the browser and reports the image's `width to height`. */
    private fun decodeSize(bytes: Uint8Array, mime: String): Promise<Pair<Int, Int>> {
        val parts = js("([])")
        parts.push(bytes)
        val options = js("({})")
        options.type = mime
        val blob = js("Reflect").construct(js("globalThis").Blob, arrayOf(parts, options))
        return js("globalThis").createImageBitmap(blob)
            .unsafeCast<Promise<Any?>>()
            .then { decoded ->
                val bitmap = decoded.asDynamic()
                (bitmap.width as Int) to (bitmap.height as Int)
            }
    }

    // --- GLB container helpers ---------------------------------------------

    private fun glb(json: String, bin: Uint8Array = Uint8Array(0)): ArrayBuffer {
        val jsonBytes = encode(json)
        val jsonPadding = (4 - jsonBytes.length % 4) % 4
        val binPadding = (4 - bin.length % 4) % 4
        val total = 12 + 8 + jsonBytes.length + jsonPadding +
            if (bin.length == 0) 0 else 8 + bin.length + binPadding
        val out = ArrayBuffer(total)
        val view = DataView(out)
        val bytes = Uint8Array(out)
        view.setInt32(0, 0x46546C67, littleEndian = true)
        view.setInt32(4, 2, littleEndian = true)
        view.setInt32(8, total, littleEndian = true)
        view.setInt32(12, jsonBytes.length + jsonPadding, littleEndian = true)
        view.setInt32(16, 0x4E4F534A, littleEndian = true)
        bytes.set(jsonBytes, 20)
        for (i in 0 until jsonPadding) bytes[20 + jsonBytes.length + i] = 0x20.toByte()
        if (bin.length > 0) {
            val binHeader = 20 + jsonBytes.length + jsonPadding
            view.setInt32(binHeader, bin.length + binPadding, littleEndian = true)
            view.setInt32(binHeader + 4, 0x004E4942, littleEndian = true)
            bytes.set(bin, binHeader + 8)
        }
        return out
    }

    private class ParsedGlb(val json: dynamic, val bin: Uint8Array, val jsonText: String)

    private fun parseGlb(buffer: ArrayBuffer): ParsedGlb {
        val view = DataView(buffer)
        val bytes = Uint8Array(buffer)
        assertEquals(
            bytes.length,
            view.getInt32(8, littleEndian = true),
            "the GLB header length must equal the actual buffer size",
        )
        var offset = 12
        var jsonText: String? = null
        var bin = Uint8Array(0)
        while (offset + 8 <= bytes.length) {
            val length = view.getInt32(offset, littleEndian = true)
            val type = view.getInt32(offset + 4, littleEndian = true)
            val start = offset + 8
            assertEquals(0, length % 4, "every GLB chunk length must be 4-byte aligned")
            when (type) {
                0x4E4F534A -> jsonText = decode(bytes.subarray(start, start + length))
                0x004E4942 -> bin = bytes.subarray(start, start + length)
            }
            offset = start + length
        }
        val text = assertNotNull(jsonText, "the rewritten GLB must still carry a JSON chunk")
        return ParsedGlb(JSON.parse<dynamic>(text.trim()), bin, text)
    }
}
