package io.github.sceneview.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise

/**
 * Rewrites the WebP-encoded textures of a glTF/GLB payload to PNG before it reaches Filament.js.
 *
 * Filament.js registers no `image/webp` texture provider — exactly like Filament's Android
 * prebuilt, which is built with `FILAMENT_SUPPORTS_WEBP_TEXTURES=OFF`. An asset using
 * [`EXT_texture_webp`](https://github.com/KhronosGroup/glTF/tree/main/extensions/2.0/vendor/EXT_texture_webp)
 * — or a plain `image/webp` image — therefore renders **untextured**, with only
 * `Missing texture provider for image/webp` on the console. See #3085 (web) and #2305 (Android).
 *
 * Every browser that can run Filament.js decodes WebP natively, so the fix is applied one step
 * earlier: decode each WebP image and re-encode it to PNG, then hand `gltfio` a glTF that no
 * longer mentions WebP.
 *
 * **This is the async twin of Android's `io.github.sceneview.loaders.WebPTextureTranscoder`.**
 * The container surgery is identical; only the decode differs. Android's `BitmapFactory` is
 * synchronous, whereas the browser's `createImageBitmap` → canvas → `toBlob` chain is not — so
 * this returns a [Promise] and `SceneView.loadModel` awaits it before `createAsset`.
 *
 * Scope and cost:
 * - Only **embedded** images are transcoded — those stored in a GLB `bufferView` or in a
 *   `data:` URI. Images referenced by an external file URI are left untouched and reported
 *   through `onUnsupported`, because their bytes are resolved later, by URI, inside Filament.
 * - A payload that contains no `image/webp` at all is resolved as **the very same [ArrayBuffer]
 *   instance** after a substring scan, with no JSON parsing and no copy of the binary payload.
 *   For a GLB the scan reads the JSON chunk alone.
 * - **Alpha is not bit-exact.** A canvas 2D context stores premultiplied colour and
 *   `toBlob("image/png")` un-premultiplies again, so a texture with *partial* transparency comes
 *   back with rounding error in its low-alpha texels. The web has no unpremultiplied 2D encode
 *   path (Android's `BitmapFactory` has `inPremultiplied = false`). Fully opaque textures — the
 *   overwhelming majority — round-trip exactly.
 *
 * All JSON here is manipulated as `dynamic`, so **no `?.let` / `?.takeIf` on a dynamic
 * receiver**: Kotlin/JS compiles an extension call on a dynamic receiver to a JS *method* call,
 * which a plain JSON object does not have.
 */
internal object WebPTextureTranscoder {

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

    /**
     * Largest texture edge this will decode. A few-KB WebP may declare a canvas up to 16383²,
     * which would ask for ~1 GB of RGBA pixels; 8192 is the texture size WebGL implementations
     * commonly cap at anyway, so a legitimate model never trips this. An image above it is left
     * untranscoded and reported, exactly like one that fails to decode.
     */
    private const val MAX_TEXTURE_EDGE = 8192

    /**
     * Most WebP images this will decode for one payload. Bounds a hostile asset's *count* the way
     * [MAX_TEXTURE_EDGE] bounds its size — the two together cap the work a crafted file can buy
     * with a few KB. Far above any real model (a texture-heavy glTF uses a few dozen images), and
     * anything past it is reported through `onUnsupported` rather than dropped in silence.
     *
     * Web-only hardening: the Android twin runs on a payload the app itself packaged or fetched,
     * whereas a browser tab is a shared, user-visible resource. Mirroring it on Android is #3136.
     */
    private const val MAX_TRANSCODED_IMAGES = 256

    /**
     * Decodes a WebP byte array and re-encodes it as PNG, resolving `null` if it cannot.
     *
     * Async by construction — the browser has no synchronous image decoder. Injectable so the
     * container surgery is testable without a real WebP round-trip.
     */
    fun interface ImageTranscoder {
        fun webPToPng(webP: Uint8Array): Promise<Uint8Array?>
    }

    /** Backed by `createImageBitmap` + a canvas PNG encode. */
    val platformTranscoder = ImageTranscoder { webP -> decodeWebPToPng(webP) }

    /** The default reporter for WebP images this cannot convert — mirrors Android's logcat error. */
    private val logUnsupported: (Int) -> Unit = { untranscodableCount ->
        console.error(
            "SceneView: $untranscodableCount WebP texture(s) of this glTF could not be decoded, " +
                "so they will render untextured: Filament.js ships no image/webp decoder, and " +
                "only WebP images embedded in the file (GLB buffer view or data: URI) can be " +
                "converted at load time. Re-encode the textures to PNG or JPEG, e.g. " +
                "`npx @gltf-transform/cli optimize model.glb out.glb --texture-compress png`. " +
                "See https://sceneview.github.io/docs/troubleshooting/",
        )
    }

    /**
     * Resolves [buffer] with every embedded WebP texture re-encoded to PNG, or **the same
     * [buffer] instance** when the payload uses no WebP texture (the overwhelmingly common case)
     * or cannot be rewritten.
     *
     * Never rejects: a payload this function fails to understand is passed through untouched, so
     * Filament stays the single authority on whether a glTF is valid.
     *
     * @param onUnsupported called with the number of WebP images that could **not** be
     * transcoded, so the caller can report an actionable message instead of a silently
     * untextured model.
     */
    fun transcode(
        buffer: ArrayBuffer,
        transcoder: ImageTranscoder = platformTranscoder,
        onUnsupported: (count: Int) -> Unit = logUnsupported,
    ): Promise<ArrayBuffer> {
        val started = try {
            val bytes = Uint8Array(buffer)
            if (bytes.length >= GLB_HEADER_SIZE &&
                DataView(buffer).getInt32(0, littleEndian = true) == GLB_MAGIC
            ) {
                transcodeGlb(buffer, bytes, transcoder, onUnsupported)
            } else {
                transcodeGltf(buffer, bytes, transcoder, onUnsupported)
            }
        } catch (throwable: Throwable) {
            console.warn("SceneView: WebP texture transcoding skipped: ${throwable.message}")
            Promise.resolve(buffer)
        }
        return started.catch { throwable ->
            console.warn("SceneView: WebP texture transcoding skipped: ${throwable.message}")
            buffer
        }
    }

    // --- GLB container ------------------------------------------------------

    /** The JSON chunk of a GLB, plus **where** its BIN chunk is — deliberately not its bytes. */
    private class GlbChunks(val json: String, val binOffset: Int, val binLength: Int)

    /** GLB container: rewrite the JSON chunk and append the PNG bytes to the BIN chunk. */
    private fun transcodeGlb(
        buffer: ArrayBuffer,
        bytes: Uint8Array,
        transcoder: ImageTranscoder,
        onUnsupported: (Int) -> Unit,
    ): Promise<ArrayBuffer> {
        val chunks = readGlbChunks(buffer, bytes) ?: return Promise.resolve(buffer)
        // Gate BEFORE touching the BIN chunk: a non-WebP model must not pay a full-model-size
        // copy, and must come back as the identical ArrayBuffer instance.
        if (!chunks.json.contains(WEBP_MARKER, ignoreCase = true)) return Promise.resolve(buffer)
        val bin = bytes.subarray(chunks.binOffset, chunks.binOffset + chunks.binLength)

        val gltf = JSON.parse<dynamic>(chunks.json)
        val appended = ByteSink()
        appended.write(bin)
        // A GLB may legitimately carry no BIN chunk (everything in `data:` URIs) or a buffer 0
        // that is URI-backed. Appending a `buffer: 0` view there would describe bytes that do not
        // exist, so those PNGs go back the way they came instead.
        val firstBuffer = index(gltf.buffers, 0)
        val binBacked = bin.length > 0 && firstBuffer != null && !hasKey(firstBuffer, "uri")
        return rewrite(
            gltf,
            transcoder,
            readImageBytes = { image -> readEmbeddedImage(gltf, bin, image) },
            storePng = { image, png ->
                if (binBacked) {
                    appendPngView(gltf, appended, image, png)
                } else {
                    storeAsDataUri(image, png)
                }
            },
        ).then { rewritten ->
            onUnsupportedIfAny(rewritten, onUnsupported)
            if (rewritten.transcodedCount == 0) {
                buffer
            } else {
                val newBin = appended.toUint8Array()
                // The sink was seeded with the whole BIN *chunk*, so if the source declared
                // `buffers[0].byteLength` shorter than its padded chunk, buffer 0 now also spans
                // those pre-existing pad bytes. That is legal — no accessor or buffer view points
                // into the gap — and it is what keeps every pre-existing byteOffset valid, which
                // re-basing onto the declared length would not.
                if (binBacked) firstBuffer.byteLength = newBin.length
                buildGlb(encodeUtf8(JSON.stringify(gltf)), newBin)
            }
        }
    }

    /** Locates a GLB's JSON and BIN chunks, or returns `null` if its chunk table is not sane. */
    private fun readGlbChunks(buffer: ArrayBuffer, bytes: Uint8Array): GlbChunks? {
        val view = DataView(buffer)
        val total = bytes.length
        var offset = GLB_HEADER_SIZE
        var json: String? = null
        var binOffset = 0
        var binLength = 0
        while (offset + CHUNK_HEADER_SIZE <= total) {
            val chunkLength = view.getInt32(offset, littleEndian = true)
            val chunkType = view.getInt32(offset + 4, littleEndian = true)
            val chunkStart = offset + CHUNK_HEADER_SIZE
            // Double arithmetic on purpose. On Kotlin/JS an `Int` is a JS number and `+` does not
            // wrap, so this is currently equivalent to the Int sum — but the same expression on
            // the JVM twin (and on any future Wasm target, where Int arithmetic *does* wrap) turns
            // a crafted chunkLength near Int.MAX_VALUE into a negative sum that sails past the
            // guard and reaches subarray(). Kept explicit so the check cannot rot into that.
            if (chunkLength < 0 || chunkStart.toDouble() + chunkLength > total.toDouble()) {
                return null
            }
            when (chunkType) {
                CHUNK_JSON -> json = decodeUtf8(bytes.subarray(chunkStart, chunkStart + chunkLength))
                // Only its bounds: the bytes are copied later, and only for a WebP-bearing model.
                CHUNK_BIN -> {
                    binOffset = chunkStart
                    binLength = chunkLength
                }
            }
            offset = chunkStart + chunkLength + (4 - chunkLength % 4) % 4
        }
        return if (json == null) null else GlbChunks(json, binOffset, binLength)
    }

    /** The bytes of an image embedded in the BIN chunk or in a `data:` URI, if it is either. */
    private fun readEmbeddedImage(gltf: dynamic, bin: Uint8Array, image: dynamic): Uint8Array? {
        val viewIndex = asInt(image.bufferView) ?: -1
        if (viewIndex >= 0) {
            val view = index(gltf.bufferViews, viewIndex)
            if (view != null && (asInt(view.buffer) ?: 0) == 0) {
                val byteOffset = asInt(view.byteOffset) ?: 0
                val byteLength = asInt(view.byteLength) ?: 0
                val inBounds = byteOffset >= 0 && byteLength >= 0 &&
                    byteOffset.toDouble() + byteLength <= bin.length.toDouble()
                // An out-of-bounds view falls through to the `data:` URI branch, exactly like the
                // Android twin's elvis chain — it does not abort the image.
                if (inBounds) return bin.subarray(byteOffset, byteOffset + byteLength)
            }
        }
        val uri = image.uri as? String
        return if (uri != null && uri.startsWith("data:")) decodeDataUri(uri) else null
    }

    /** Appends the PNG to the BIN chunk under a fresh, 4-byte-aligned buffer view. */
    private fun appendPngView(gltf: dynamic, appended: ByteSink, image: dynamic, png: Uint8Array) {
        // 4-byte-align every appended view. glTF 2.0 does NOT require this of a buffer view that
        // holds image data — that alignment rule is about accessors — so this is an invariant we
        // keep rather than one we owe: it matches the Android twin's output byte for byte, and it
        // keeps every offset in the rewritten JSON on the same grid as the GLB chunks themselves,
        // which is what a reader eyeballing a diff of the two containers expects.
        appended.pad((4 - appended.size % 4) % 4)
        var bufferViews = gltf.bufferViews
        if (bufferViews == null) {
            bufferViews = newArray()
            gltf.bufferViews = bufferViews
        }
        val view = newObject()
        view.buffer = 0
        view.byteOffset = appended.size
        view.byteLength = png.length
        bufferViews.push(view)
        appended.write(png)
        image.bufferView = length(bufferViews) - 1
        deleteKey(image, "uri")
    }

    private fun storeAsDataUri(image: dynamic, png: Uint8Array) {
        image.uri = "data:$PNG_MIME;base64," + toBase64(png)
        deleteKey(image, "bufferView")
    }

    // --- Plain .gltf --------------------------------------------------------

    /** Plain `.gltf` JSON: only `data:` URIs are embedded, so PNGs go back as `data:` URIs. */
    private fun transcodeGltf(
        buffer: ArrayBuffer,
        bytes: Uint8Array,
        transcoder: ImageTranscoder,
        onUnsupported: (Int) -> Unit,
    ): Promise<ArrayBuffer> {
        val text = decodeUtf8(bytes)
        if (!text.contains(WEBP_MARKER, ignoreCase = true)) return Promise.resolve(buffer)
        val gltf = JSON.parse<dynamic>(text)
        return rewrite(
            gltf,
            transcoder,
            readImageBytes = { image ->
                val uri = image.uri as? String
                if (uri != null && uri.startsWith("data:")) decodeDataUri(uri) else null
            },
            storePng = ::storeAsDataUri,
        ).then { rewritten ->
            onUnsupportedIfAny(rewritten, onUnsupported)
            if (rewritten.transcodedCount == 0) {
                buffer
            } else {
                toArrayBuffer(encodeUtf8(JSON.stringify(gltf)))
            }
        }
    }

    // --- The rewrite itself -------------------------------------------------

    private class Rewritten(val transcodedCount: Int, val untranscodableCount: Int)

    private fun onUnsupportedIfAny(rewritten: Rewritten, onUnsupported: (Int) -> Unit) {
        if (rewritten.untranscodableCount > 0) onUnsupported(rewritten.untranscodableCount)
    }

    /**
     * Walks `images`/`textures`, transcodes what [readImageBytes] can supply and [storePng] can
     * store back, and drops every trace of the WebP extension that is no longer needed.
     *
     * The decodes run **sequentially**: each appended buffer view's `byteOffset` depends on the
     * bytes already in the sink, so a parallel `Promise.all` would make the output layout depend
     * on decode timing.
     */
    private fun rewrite(
        gltf: dynamic,
        transcoder: ImageTranscoder,
        readImageBytes: (dynamic) -> Uint8Array?,
        storePng: (dynamic, Uint8Array) -> Unit,
    ): Promise<Rewritten> {
        val images = gltf.images ?: return Promise.resolve(Rewritten(0, 0))
        val textures = gltf.textures ?: newArray()

        var untranscodable = 0
        val converted = mutableSetOf<Int>()
        var chain: Promise<Unit> = Promise.resolve(Unit)
        val webPImages = collectWebPImages(images, textures)
        // Everything past the budget is reported, not decoded. Per-image cost is already bounded
        // by MAX_TEXTURE_EDGE, but the image COUNT comes from the file: a few KB of crafted JSON
        // can declare thousands of images all pointing at one tiny WebP that inflates to 8192²,
        // and the decodes run sequentially, so the tab spends minutes on it. Same contract as an
        // oversized image — untranscoded, and named in the console error rather than silent.
        val budgeted = webPImages.take(MAX_TRANSCODED_IMAGES)
        untranscodable += webPImages.size - budgeted.size
        budgeted.forEach { imageIndex ->
            chain = chain.then<Any?> {
                val image = index(images, imageIndex)
                val webP = if (image == null) null else readImageBytes(image)
                if (webP == null) {
                    untranscodable++
                    Promise.resolve(Unit)
                } else {
                    // A rejected decode (corrupt image, browser refusal) is a FAILED image, not a
                    // failed load: swallow it here so the rest of the model is still rewritten.
                    // The explicit type keeps the types honest rather than the runtime correct:
                    // `Promise<T>.catch { }` is declared `fun <S> catch((Throwable) -> S):
                    // Promise<S>`, so an inferred `S` collapses to `Nothing?`. `png` is then
                    // statically-always-null — the null check still works at runtime, but it warns
                    // as "always true" and every later use of `png` type-checks against `Nothing?`,
                    // i.e. against nothing at all.
                    val decoded: Promise<Uint8Array?> = transcoder.webPToPng(webP).catch { null }
                    decoded.then { png ->
                        if (png == null) {
                            untranscodable++
                        } else {
                            storePng(image, png)
                            image.mimeType = PNG_MIME
                            converted += imageIndex
                        }
                    }
                }
            }.unsafeCast<Promise<Unit>>()
        }

        return chain.then {
            repointTextures(textures, converted)

            if (untranscodable == 0) {
                removeExtensionDeclaration(gltf, "extensionsUsed")
                removeExtensionDeclaration(gltf, "extensionsRequired")
            }
            Rewritten(converted.size, untranscodable)
        }
    }

    /**
     * Indices of the images that hold WebP bytes, whether declared by `mimeType` or by extension.
     * Sorted so the appended buffer views land at deterministic offsets.
     */
    private fun collectWebPImages(images: dynamic, textures: dynamic): List<Int> {
        val webPImages = mutableSetOf<Int>()
        for (i in 0 until length(textures)) {
            val texture = index(textures, i) ?: continue
            val webPSource = webPSource(texture)
            // A texture keeping a non-WebP `source` already has a usable fallback: its extension
            // can be dropped without decoding anything.
            if (webPSource >= 0 && !hasKey(texture, "source")) webPImages += webPSource
        }
        for (i in 0 until length(images)) {
            val image = index(images, i)
            if (image != null && image.mimeType as? String == WEBP_MIME) webPImages += i
        }
        return webPImages.sorted()
    }

    /** Points every texture at its now-PNG image and drops the extension it no longer needs. */
    private fun repointTextures(textures: dynamic, converted: Set<Int>) {
        for (i in 0 until length(textures)) {
            val texture = index(textures, i) ?: continue
            val webPSource = webPSource(texture)
            // Keep the extension only when its image is still WebP and no fallback exists.
            if (webPSource >= 0 && (webPSource in converted || hasKey(texture, "source"))) {
                if (!hasKey(texture, "source")) texture.source = webPSource
                val extensions = texture.extensions
                if (extensions != null) {
                    deleteKey(extensions, WEBP_EXTENSION)
                    if (keyCount(extensions) == 0) deleteKey(texture, "extensions")
                }
            }
        }
    }

    private fun webPSource(texture: dynamic): Int {
        val extensions = texture.extensions ?: return -1
        val webP = extensions[WEBP_EXTENSION] ?: return -1
        return asInt(webP.source) ?: -1
    }

    private fun removeExtensionDeclaration(gltf: dynamic, key: String) {
        val declared = gltf[key] ?: return
        val kept = newArray()
        for (i in 0 until length(declared)) {
            val value = index(declared, i)
            if (value as? String != WEBP_EXTENSION) kept.push(value)
        }
        if (length(kept) == 0) deleteKey(gltf, key) else gltf[key] = kept
    }

    // --- Browser decode -----------------------------------------------------

    private fun decodeWebPToPng(webP: Uint8Array): Promise<Uint8Array?> {
        // `catch` below is typed explicitly for the same reason as in `rewrite`: an inferred `S`
        // would make the resolved value statically `Nothing?`.
        val blobParts = newArray()
        blobParts.push(webP)
        val blobOptions = newObject()
        blobOptions.type = WEBP_MIME
        val blob = js("Reflect").construct(global().Blob, arrayOf(blobParts, blobOptions))
        return global().createImageBitmap(blob)
            .unsafeCast<Promise<Any?>>()
            .then<Any?> { decoded ->
                val bitmap = decoded.asDynamic()
                val width = asInt(bitmap.width) ?: 0
                val height = asInt(bitmap.height) ?: 0
                if (width !in 1..MAX_TEXTURE_EDGE || height !in 1..MAX_TEXTURE_EDGE) {
                    bitmap.close()
                    Promise.resolve(null)
                } else {
                    encodePng(bitmap, width, height)
                }
            }
            .unsafeCast<Promise<Uint8Array?>>()
            .catch<Uint8Array?> { null }
    }

    /** Draws [bitmap] onto a canvas and reads it back as PNG bytes. */
    private fun encodePng(bitmap: dynamic, width: Int, height: Int): Promise<Uint8Array?> =
        Promise { resolve, _ ->
            // OffscreenCanvas keeps this off the DOM (and works in a worker); the
            // HTMLCanvasElement fallback covers the browsers that do not have it.
            val offscreen = jsTypeOf(global().OffscreenCanvas) != "undefined"
            val canvas: dynamic = if (offscreen) {
                js("Reflect").construct(global().OffscreenCanvas, arrayOf(width, height))
            } else {
                (document.createElement("canvas") as HTMLCanvasElement).apply {
                    this.width = width
                    this.height = height
                }
            }
            val context = canvas.getContext("2d")
            if (context == null) {
                bitmap.close()
                resolve(null)
            } else {
                context.drawImage(bitmap, 0, 0)
                bitmap.close()
                val onBlob: (dynamic) -> Unit = { blob ->
                    if (blob == null) {
                        resolve(null)
                    } else {
                        blob.arrayBuffer()
                            .then { arrayBuffer ->
                                resolve(Uint8Array(arrayBuffer.unsafeCast<ArrayBuffer>()))
                            }
                            .catch { resolve(null) }
                    }
                }
                if (offscreen) {
                    val options = newObject()
                    options.type = PNG_MIME
                    canvas.convertToBlob(options).then(onBlob).catch { resolve(null) }
                } else {
                    canvas.toBlob(onBlob, PNG_MIME)
                }
            }
        }

    // --- Byte plumbing ------------------------------------------------------

    /** A growable byte buffer — the `ByteArrayOutputStream` of the Android twin. */
    private class ByteSink {
        private val chunks = mutableListOf<Uint8Array>()

        var size: Int = 0
            private set

        fun write(bytes: Uint8Array) {
            if (bytes.length > 0) {
                chunks += bytes
                size += bytes.length
            }
        }

        /** Writes [count] zero bytes. */
        fun pad(count: Int) {
            if (count > 0) write(Uint8Array(count))
        }

        fun toUint8Array(): Uint8Array {
            val out = Uint8Array(size)
            var offset = 0
            chunks.forEach {
                out.set(it, offset)
                offset += it.length
            }
            return out
        }
    }

    private fun buildGlb(json: Uint8Array, bin: Uint8Array): ArrayBuffer {
        val jsonPadding = (4 - json.length % 4) % 4
        val binPadding = (4 - bin.length % 4) % 4
        val total = GLB_HEADER_SIZE +
            CHUNK_HEADER_SIZE + json.length + jsonPadding +
            if (bin.length == 0) 0 else CHUNK_HEADER_SIZE + bin.length + binPadding
        val out = ArrayBuffer(total)
        val view = DataView(out)
        val bytes = Uint8Array(out)
        view.setInt32(0, GLB_MAGIC, littleEndian = true)
        view.setInt32(4, 2, littleEndian = true)
        view.setInt32(8, total, littleEndian = true)
        view.setInt32(12, json.length + jsonPadding, littleEndian = true)
        view.setInt32(16, CHUNK_JSON, littleEndian = true)
        bytes.set(json, GLB_HEADER_SIZE + CHUNK_HEADER_SIZE)
        // The JSON chunk pads with SPACES, the BIN chunk with zeros (glTF 2.0 §3.3). A fresh
        // ArrayBuffer is already zero-filled, so only the spaces have to be written.
        val jsonEnd = GLB_HEADER_SIZE + CHUNK_HEADER_SIZE + json.length
        for (i in 0 until jsonPadding) bytes[jsonEnd + i] = 0x20.toByte()
        if (bin.length > 0) {
            val binHeader = jsonEnd + jsonPadding
            view.setInt32(binHeader, bin.length + binPadding, littleEndian = true)
            view.setInt32(binHeader + 4, CHUNK_BIN, littleEndian = true)
            bytes.set(bin, binHeader + CHUNK_HEADER_SIZE)
        }
        return out
    }

    /** The exact bytes of [bytes] as a standalone [ArrayBuffer] — never the whole backing store. */
    private fun toArrayBuffer(bytes: Uint8Array): ArrayBuffer =
        bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length)

    private fun decodeDataUri(uri: String): Uint8Array? = try {
        fromBase64(uri.substringAfter("base64,"))
    } catch (throwable: Throwable) {
        null
    }

    private fun fromBase64(base64: String): Uint8Array {
        val binary = window.asDynamic().atob(base64) as String
        val bytes = Uint8Array(binary.length)
        for (i in binary.indices) bytes[i] = binary[i].code.toByte()
        return bytes
    }

    private fun toBase64(bytes: Uint8Array): String {
        val binary = StringBuilder()
        // `String.fromCharCode.apply` blows the JS argument-count limit on a large image, so the
        // bytes go through it in 32 KB slices.
        val chunkSize = 0x8000
        var offset = 0
        while (offset < bytes.length) {
            val end = minOf(offset + chunkSize, bytes.length)
            binary.append(
                js("String").fromCharCode.apply(null, bytes.subarray(offset, end)) as String,
            )
            offset = end
        }
        return window.asDynamic().btoa(binary.toString()) as String
    }

    private fun decodeUtf8(bytes: Uint8Array): String =
        js("new TextDecoder('utf-8')").decode(bytes) as String

    private fun encodeUtf8(text: String): Uint8Array =
        js("new TextEncoder()").encode(text).unsafeCast<Uint8Array>()

    // --- Dynamic-JSON helpers ----------------------------------------------

    private fun global(): dynamic = js("globalThis")

    private fun newObject(): dynamic = js("({})")

    private fun newArray(): dynamic = js("([])")

    private fun length(array: dynamic): Int = if (array == null) 0 else asInt(array.length) ?: 0

    /** JS index access — `array?.get(i)` would compile to a `.get()` *method* call on dynamic. */
    private fun index(array: dynamic, i: Int): dynamic = if (array == null) null else array[i]

    /** `Int` when [value] really is an integral JS number, `null` otherwise. */
    private fun asInt(value: dynamic): Int? = value as? Int

    /** `JSONObject.has` — present, even when the value is `0` or `null`. */
    private fun hasKey(obj: dynamic, key: String): Boolean =
        obj != null && js("Reflect").has(obj, key) as Boolean

    private fun deleteKey(obj: dynamic, key: String) {
        js("Reflect").deleteProperty(obj, key)
    }

    private fun keyCount(obj: dynamic): Int = length(js("Object").keys(obj))
}
