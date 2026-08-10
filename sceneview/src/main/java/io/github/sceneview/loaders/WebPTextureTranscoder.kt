package io.github.sceneview.loaders

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites the WebP-encoded textures of a glTF/GLB payload to PNG before it reaches Filament.
 *
 * Filament's published Android artifacts are built with `FILAMENT_SUPPORTS_WEBP_TEXTURES=OFF`, so
 * `gltfio` registers no `image/webp` texture provider and any asset using
 * [`EXT_texture_webp`](https://github.com/KhronosGroup/glTF/tree/main/extensions/2.0/vendor/EXT_texture_webp)
 * — or a plain `image/webp` image — renders **untextured**, with only
 * `E Filament: Missing texture provider for image/webp` in logcat. There is no Java seam to plug a
 * decoder into `ResourceLoader` either: its three providers are wired in its constructor.
 *
 * Android itself decodes WebP natively ([BitmapFactory], since API 14), so the fix is applied one
 * step earlier: decode each WebP image and re-encode it losslessly to PNG, then hand Filament a
 * glTF that no longer mentions WebP. See #2305.
 *
 * Scope and cost:
 * - Only **embedded** images are transcoded — those stored in a GLB `bufferView` or in a
 *   `data:` URI. Images referenced by an external file URI are left untouched and reported through
 *   [onUnsupported], because their bytes are resolved later, by URI, inside Filament.
 * - A payload that contains no `image/webp` at all is returned **as-is** after a substring scan of
 *   the JSON chunk only, so the non-WebP path costs no parsing and no copy.
 */
internal object WebPTextureTranscoder {

    private const val TAG = "SceneView"

    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val GLB_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8

    private const val WEBP_MIME = "image/webp"

    /**
     * Cheap pre-parse gate. Deliberately just `webp`: it matches `image/webp`, the `image\/webp`
     * a slash-escaping JSON serializer emits, and `EXT_texture_webp` on an asset whose images
     * declare no `mimeType` at all — a narrower marker silently passes such files through.
     */
    private const val WEBP_MARKER = "webp"
    private const val PNG_MIME = "image/png"
    private const val WEBP_EXTENSION = "EXT_texture_webp"

    /** Decodes a WebP byte array and re-encodes it as PNG, or returns `null` if it cannot. */
    fun interface ImageTranscoder {
        fun webPToPng(webP: ByteArray): ByteArray?
    }

    /** Backed by [BitmapFactory]; PNG is lossless so no quality is lost beyond the source WebP. */
    val platformTranscoder = ImageTranscoder { webP ->
        runCatching {
            BitmapFactory.decodeByteArray(webP, 0, webP.size)?.let { bitmap ->
                ByteArrayOutputStream(webP.size).also { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bitmap.recycle()
                }.toByteArray()
            }
        }.getOrNull()
    }

    /**
     * Returns [buffer] with every embedded WebP texture re-encoded to PNG, or [buffer] itself when
     * the payload uses no WebP texture (the overwhelmingly common case) or cannot be rewritten.
     *
     * Never throws: a payload this function fails to understand is passed through untouched, so
     * Filament stays the single authority on whether a glTF is valid.
     *
     * @param onUnsupported called with the number of WebP images that could **not** be transcoded,
     * so the caller can report an actionable message instead of a silently untextured model.
     */
    fun transcode(
        buffer: ByteBuffer,
        transcoder: ImageTranscoder = platformTranscoder,
        onUnsupported: (count: Int) -> Unit = { untranscodableCount ->
            Log.e(
                TAG,
                "$untranscodableCount WebP texture(s) of this glTF could not be decoded, so they " +
                        "will render untextured: Filament's Android build ships no image/webp " +
                        "decoder, and only WebP images embedded in the file (GLB buffer view or " +
                        "data: URI) can be converted at load time. Re-encode the textures to PNG " +
                        "or JPEG, e.g. `npx @gltf-transform/cli optimize model.glb out.glb " +
                        "--texture-compress png`. " +
                        "See https://sceneview.github.io/docs/troubleshooting/"
            )
        }
    ): ByteBuffer = runCatching {
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (source.remaining() >= GLB_HEADER_SIZE &&
            source.getInt(source.position()) == GLB_MAGIC
        ) {
            transcodeGlb(source, transcoder, onUnsupported)
        } else {
            transcodeGltf(source, transcoder, onUnsupported)
        }
    }.getOrElse { throwable ->
        Log.w(TAG, "WebP texture transcoding skipped: ${throwable.message}")
        null
    } ?: buffer

    /** GLB container: rewrite the JSON chunk and append the PNG bytes to the BIN chunk. */
    private fun transcodeGlb(
        source: ByteBuffer,
        transcoder: ImageTranscoder,
        onUnsupported: (Int) -> Unit
    ): ByteBuffer? {
        val (json, bin) = readGlbChunks(source) ?: return null
        val jsonChunk = json.takeIf { it.contains(WEBP_MARKER, ignoreCase = true) } ?: return null

        val gltf = JSONObject(jsonChunk)
        val appended = ByteArrayOutputStream().apply { write(bin) }
        // A GLB may legitimately carry no BIN chunk (everything in `data:` URIs) or a buffer 0 that
        // is URI-backed. Appending a `buffer: 0` view there would describe bytes that do not exist,
        // so those PNGs go back the way they came instead.
        val binBacked = bin.isNotEmpty() &&
                gltf.optJSONArray("buffers")?.optJSONObject(0)?.has("uri") == false
        val rewritten = rewrite(
            gltf,
            transcoder,
            readImageBytes = { image -> readEmbeddedImage(gltf, bin, image) },
            storePng = { image, png ->
                if (binBacked) appendPngView(gltf, appended, image, png) else storeAsDataUri(image, png)
            }
        )
        onUnsupportedIfAny(rewritten, onUnsupported)
        if (rewritten.transcodedCount == 0) return null

        val newBin = appended.toByteArray()
        if (binBacked) gltf.optJSONArray("buffers")?.optJSONObject(0)?.put("byteLength", newBin.size)
        return buildGlb(gltf.toString().toByteArray(Charsets.UTF_8), newBin)
    }

    /** Splits a GLB into its JSON and BIN chunks, or `null` if its chunk table is not sane. */
    private fun readGlbChunks(source: ByteBuffer): Pair<String, ByteArray>? {
        val start = source.position()
        var offset = start + GLB_HEADER_SIZE
        var json: String? = null
        var bin = ByteArray(0)
        while (offset + CHUNK_HEADER_SIZE <= start + source.remaining()) {
            val chunkLength = source.getInt(offset)
            val chunkType = source.getInt(offset + 4)
            val chunkStart = offset + CHUNK_HEADER_SIZE
            // Long arithmetic on purpose: a crafted chunkLength near Int.MAX_VALUE would overflow
            // an Int sum back to a negative value, sail past this guard and reach ByteArray().
            if (chunkLength < 0 ||
                chunkStart.toLong() + chunkLength > start.toLong() + source.remaining()
            ) {
                return null
            }
            val chunk = ByteArray(chunkLength)
            source.duplicate().apply { position(chunkStart) }.get(chunk)
            when (chunkType) {
                CHUNK_JSON -> json = String(chunk, Charsets.UTF_8)
                CHUNK_BIN -> bin = chunk
            }
            offset = chunkStart + chunkLength + (4 - chunkLength % 4) % 4
        }
        return json?.let { it to bin }
    }

    /** The bytes of an image embedded in the BIN chunk or in a `data:` URI, if it is either. */
    private fun readEmbeddedImage(gltf: JSONObject, bin: ByteArray, image: JSONObject): ByteArray? =
        image.optInt("bufferView", -1)
            .takeIf { it >= 0 }
            ?.let { gltf.optJSONArray("bufferViews")?.optJSONObject(it) }
            ?.takeIf { it.optInt("buffer", 0) == 0 }
            ?.let { view ->
                val byteOffset = view.optInt("byteOffset", 0)
                val byteLength = view.optInt("byteLength", 0)
                if (byteOffset < 0 || byteLength < 0 ||
                    byteOffset.toLong() + byteLength > bin.size
                ) {
                    null
                } else {
                    bin.copyOfRange(byteOffset, byteOffset + byteLength)
                }
            }
            ?: image.optString("uri").takeIf { it.startsWith("data:") }?.let(::decodeDataUri)

    /** Appends the PNG to the BIN chunk under a fresh, 4-byte-aligned buffer view. */
    private fun appendPngView(
        gltf: JSONObject,
        appended: ByteArrayOutputStream,
        image: JSONObject,
        png: ByteArray
    ) {
        // Pad to the 4-byte alignment glTF requires of a buffer view holding image data.
        repeat((4 - appended.size() % 4) % 4) { appended.write(0) }
        val bufferViews = gltf.optJSONArray("bufferViews") ?: JSONArray().also {
            gltf.put("bufferViews", it)
        }
        bufferViews.put(
            JSONObject()
                .put("buffer", 0)
                .put("byteOffset", appended.size())
                .put("byteLength", png.size)
        )
        appended.write(png)
        image.put("bufferView", bufferViews.length() - 1)
        image.remove("uri")
    }

    private fun storeAsDataUri(image: JSONObject, png: ByteArray) {
        image.put("uri", "data:$PNG_MIME;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
        image.remove("bufferView")
    }

    /** Plain `.gltf` JSON: only `data:` URIs are embedded, so PNGs go back as `data:` URIs. */
    private fun transcodeGltf(
        source: ByteBuffer,
        transcoder: ImageTranscoder,
        onUnsupported: (Int) -> Unit
    ): ByteBuffer? {
        val bytes = ByteArray(source.remaining()).also { source.duplicate().get(it) }
        val text = String(bytes, Charsets.UTF_8)
            .takeIf { it.contains(WEBP_MARKER, ignoreCase = true) }
            ?: return null
        val gltf = JSONObject(text)
        val rewritten = rewrite(gltf, transcoder, readImageBytes = { image ->
            image.optString("uri").takeIf { it.startsWith("data:") }?.let(::decodeDataUri)
        }, storePng = ::storeAsDataUri)
        onUnsupportedIfAny(rewritten, onUnsupported)
        if (rewritten.transcodedCount == 0) return null
        return ByteBuffer.wrap(gltf.toString().toByteArray(Charsets.UTF_8))
    }

    private class Rewritten(val transcodedCount: Int, val untranscodableCount: Int)

    private fun onUnsupportedIfAny(rewritten: Rewritten, onUnsupported: (Int) -> Unit) {
        if (rewritten.untranscodableCount > 0) onUnsupported(rewritten.untranscodableCount)
    }

    /**
     * Walks `images`/`textures`, transcodes what [readImageBytes] can supply and [storePng] can
     * store back, and drops every trace of the WebP extension that is no longer needed.
     */
    private fun rewrite(
        gltf: JSONObject,
        transcoder: ImageTranscoder,
        readImageBytes: (JSONObject) -> ByteArray?,
        storePng: (JSONObject, ByteArray) -> Unit
    ): Rewritten {
        val images = gltf.optJSONArray("images") ?: return Rewritten(0, 0)
        val textures = gltf.optJSONArray("textures") ?: JSONArray()

        var untranscodable = 0
        val converted = mutableSetOf<Int>()
        collectWebPImages(images, textures).forEach { index ->
            val image = images.optJSONObject(index)
            val png = image?.let { readImageBytes(it) }?.let { transcoder.webPToPng(it) }
            if (png == null) {
                untranscodable++
            } else {
                storePng(image, png)
                image.put("mimeType", PNG_MIME)
                converted += index
            }
        }

        repointTextures(textures, converted)

        if (untranscodable == 0) {
            removeExtensionDeclaration(gltf, "extensionsUsed")
            removeExtensionDeclaration(gltf, "extensionsRequired")
        }
        return Rewritten(converted.size, untranscodable)
    }

    /** Indices of the images that hold WebP bytes, whether declared by `mimeType` or by extension. */
    private fun collectWebPImages(images: JSONArray, textures: JSONArray): Set<Int> {
        val webPImages = mutableSetOf<Int>()
        for (i in 0 until textures.length()) {
            val texture = textures.optJSONObject(i)
            val webPSource = texture?.webPSource() ?: -1
            // A texture keeping a non-WebP `source` already has a usable fallback: its extension
            // can be dropped without decoding anything.
            if (webPSource >= 0 && !texture.has("source")) webPImages += webPSource
        }
        for (i in 0 until images.length()) {
            if (images.optJSONObject(i)?.optString("mimeType") == WEBP_MIME) webPImages += i
        }
        return webPImages
    }

    /** Points every texture at its now-PNG image and drops the extension it no longer needs. */
    private fun repointTextures(textures: JSONArray, converted: Set<Int>) {
        for (i in 0 until textures.length()) {
            val texture = textures.optJSONObject(i) ?: continue
            val webPSource = texture.webPSource()
            // Keep the extension only when its image is still WebP and no fallback exists.
            if (webPSource >= 0 && (webPSource in converted || texture.has("source"))) {
                if (!texture.has("source")) texture.put("source", webPSource)
                texture.optJSONObject("extensions")?.let { extensions ->
                    extensions.remove(WEBP_EXTENSION)
                    if (extensions.length() == 0) texture.remove("extensions")
                }
            }
        }
    }

    private fun JSONObject.webPSource(): Int =
        optJSONObject("extensions")?.optJSONObject(WEBP_EXTENSION)?.optInt("source", -1) ?: -1

    private fun removeExtensionDeclaration(gltf: JSONObject, key: String) {
        val declared = gltf.optJSONArray(key) ?: return
        val kept = JSONArray()
        for (i in 0 until declared.length()) {
            declared.optString(i).takeIf { it != WEBP_EXTENSION }?.let { kept.put(it) }
        }
        if (kept.length() == 0) gltf.remove(key) else gltf.put(key, kept)
    }

    private fun decodeDataUri(uri: String): ByteArray? = runCatching {
        Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
    }.getOrNull()

    private fun buildGlb(json: ByteArray, bin: ByteArray): ByteBuffer {
        val jsonPadding = (4 - json.size % 4) % 4
        val binPadding = (4 - bin.size % 4) % 4
        val total = GLB_HEADER_SIZE +
                CHUNK_HEADER_SIZE + json.size + jsonPadding +
                if (bin.isEmpty()) 0 else CHUNK_HEADER_SIZE + bin.size + binPadding
        return ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(GLB_MAGIC)
            putInt(2)
            putInt(total)
            putInt(json.size + jsonPadding)
            putInt(CHUNK_JSON)
            put(json)
            repeat(jsonPadding) { put(' '.code.toByte()) }
            if (bin.isNotEmpty()) {
                putInt(bin.size + binPadding)
                putInt(CHUNK_BIN)
                put(bin)
                repeat(binPadding) { put(0) }
            }
            rewind()
        }
    }
}
