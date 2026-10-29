package io.github.sceneview.snapshot

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.github.sceneview.utils.FileCache
import io.github.sceneview.utils.fileCacheDir
import java.io.File
import java.io.InputStream

/** Positions stored as normalized integers: gltfio reports their bounding box un-dequantized. */
internal const val KHR_MESH_QUANTIZATION = "KHR_mesh_quantization"

private const val GLB_MAGIC = 0x46546C67 // "glTF", little-endian
private const val GLB_JSON_CHUNK = 0x4E4F534A // "JSON", little-endian

/** The JSON part of a glTF file this large or larger is not read: it is not worth the memory. */
private const val MAX_JSON_BYTES = 16 * 1024 * 1024

private val EXTENSIONS_USED = Regex("\"extensionsUsed\"\\s*:\\s*\\[([^\\]]*)\\]")
private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

/**
 * The names a glTF document lists in its top-level `extensionsUsed`, read from its JSON text.
 * Empty when it lists none.
 */
internal fun gltfExtensionsUsed(json: String): List<String> {
    val list = EXTENSIONS_USED.find(json)?.groupValues?.get(1) ?: return emptyList()
    return QUOTED.findAll(list).map { it.groupValues[1] }.toList()
}

/**
 * The JSON text of a glTF file read from [stream]: only the JSON chunk of a binary GLB (its
 * buffers are never read), or the whole text of a `.gltf`. `null` when the file is neither, or its
 * JSON is larger than 16 MB.
 */
internal fun readGltfJson(stream: InputStream): String? {
    val input = stream.buffered()
    val head = ByteArray(12)
    val read = input.readNBytesCompat(head)
    if (read >= 4 && head.intLe(0) == GLB_MAGIC) {
        if (read < 12) return null
        val chunk = ByteArray(8)
        if (input.readNBytesCompat(chunk) < 8 || chunk.intLe(4) != GLB_JSON_CHUNK) return null
        val length = chunk.intLe(0)
        if (length < 0 || length > MAX_JSON_BYTES) return null
        val json = ByteArray(length)
        if (input.readNBytesCompat(json) < length) return null
        return String(json, Charsets.UTF_8)
    }
    val rest = input.readBytesUpTo(MAX_JSON_BYTES - read) ?: return null
    return String(head.copyOf(read) + rest, Charsets.UTF_8).takeIf { it.trimStart().startsWith("{") }
}

/**
 * The `extensionsUsed` of the model at [fileLocation], read as
 * [io.github.sceneview.loaders.ModelLoader.loadModel] finds it: an asset path, a `file://` or
 * `content://` URI, an `android.resource://` URI by resource id, or an `http(s)` URL already in the
 * [FileCache]. `null` when it could not be checked: the file cannot be read this way (an `http(s)`
 * URL missing from the [FileCache] — the cache is disabled or its write failed —, an
 * `android.resource://` URI by name, which `loadModel` rejects before this is called, a read
 * error), or it is not glTF or GLB, or its JSON is larger than 16 MB. Blocking: call it off the main thread.
 */
internal fun readGltfExtensionsUsed(context: Context, fileLocation: String): List<String>? =
    runCatching {
        openModelStream(context, fileLocation)?.use { readGltfJson(it) }?.let(::gltfExtensionsUsed)
    }.getOrNull()

private fun openModelStream(context: Context, fileLocation: String): InputStream? {
    val uri = Uri.parse(fileLocation)
    return when (uri.scheme) {
        "http", "https" -> FileCache.fileOf(context.fileCacheDir, fileLocation)
            .takeIf { FileCache.enabled && it.isFile }?.inputStream()
        ContentResolver.SCHEME_FILE -> if (uri.pathSegments.firstOrNull() == "android_asset") {
            context.assets.open(uri.pathSegments.drop(1).joinToString("/"))
        } else {
            uri.path?.let { File(it).inputStream() }
        }
        ContentResolver.SCHEME_CONTENT -> context.contentResolver.openInputStream(uri)
        ContentResolver.SCHEME_ANDROID_RESOURCE ->
            uri.pathSegments.lastOrNull()?.toIntOrNull()?.let { context.resources.openRawResource(it) }
        else -> context.assets.open(fileLocation)
    }
}

private fun ByteArray.intLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)

/** Reads until [buffer] is full or the stream ends; returns the bytes read. */
private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

/** The rest of the stream, or `null` when it holds more than [limit] bytes. */
private fun InputStream.readBytesUpTo(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val n = read(chunk)
        if (n < 0) return out.toByteArray()
        out.write(chunk, 0, n)
        if (out.size() > limit) return null
    }
}
