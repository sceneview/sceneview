package io.github.sceneview.utils

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.net.Uri
import androidx.annotation.RawRes
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.coroutines.awaitByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object FileLoader {

    private const val ASSET_FILE_PATH_ROOT = "android_asset"

    /**
     * Pass your own [FuelManager] and change it properties for specific http/https configuration.
     */
    var fuelManager: FuelManager = FuelManager()

    /**
     * Load a file content buffer from different sources.
     *
     * The file location can be:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     */
    suspend fun loadFileBuffer(context: Context, fileLocation: String): ByteBuffer? =
        loadFileBuffer(context, fileLocation, onProgress = null)

    /**
     * [loadFileBuffer], reporting the fraction read so far to [onProgress] when the size is known
     * (a file, an asset, a raw resource, a download with a `Content-Length`). Called from the
     * reading thread; a file already in [FileCache] reports nothing.
     */
    internal suspend fun loadFileBuffer(
        context: Context,
        fileLocation: String,
        onProgress: ((Float) -> Unit)?,
    ): ByteBuffer? {
        val uri = Uri.parse(fileLocation)
        return withContext(Dispatchers.IO) {
            when (uri.scheme) {
                "http", "https" -> FileCache.getOrPut(context.fileCacheDir, fileLocation) {
                    fuelManager.get(fileLocation)
                        .apply {
                            if (onProgress != null) {
                                responseProgress { read, total -> if (total > 0) onProgress(minOf(1f, read.toFloat() / total)) }
                            }
                        }
                        .awaitByteArray()
                }

                else -> {
                    readFileBuffer(context, fileLocation, onProgress)
                }
            }
        }
    }

    /**
     * Load a file content buffer from a local source.
     *
     * The file location can be:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     */
    fun readFileBuffer(context: Context, fileLocation: String): ByteBuffer? =
        readFileBuffer(context, fileLocation, onProgress = null)

    // Each stream is read into one array of its size (see `readFully`), not grown then copied.
    internal fun readFileBuffer(context: Context, fileLocation: String, onProgress: ((Float) -> Unit)?): ByteBuffer? {
        val uri = Uri.parse(fileLocation)
        val stream = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> if (uri.firstPathSegment == ASSET_FILE_PATH_ROOT) {
                context.assets.open(
                    uri.pathSegments.drop(1).joinToString("/")
                )
            } else {
                File(uri.path ?: throw IllegalArgumentException("Invalid file URI: $uri")).inputStream()
            }

            ContentResolver.SCHEME_CONTENT -> {
                context.contentResolver.openInputStream(uri)
            }

            ContentResolver.SCHEME_ANDROID_RESOURCE -> {
                // Expected format: android.resource://example.package.name/12345678
                context.resources.openRawResource(uri.pathSegments.lastOrNull()?.toIntOrNull() ?: throw IllegalArgumentException("Invalid resource URI: $uri"))
            }

            else -> context.assets.open(fileLocation)
        }
        return stream?.use { it.readFully(onProgress = onProgress) }?.let { byteArray ->
            ByteBuffer.wrap(byteArray)
        }
    }

    @JvmStatic
    private val Uri.firstPathSegment: String?
        get() = pathSegments.firstOrNull()
}


/**
 * @see FileLoader.loadFileBuffer
 */
suspend fun Context.loadFileBuffer(fileLocation: String): ByteBuffer? =
    FileLoader.loadFileBuffer(this, fileLocation)

fun AssetManager.readBuffer(fileLocation: String): ByteBuffer = open(fileLocation).readBuffer()
fun Resources.readBuffer(@RawRes rawResId: Int): ByteBuffer = openRawResource(rawResId).readBuffer()
fun File.readBuffer(): ByteBuffer = inputStream().use { it.readBuffer() }

fun InputStream.readBuffer(): ByteBuffer = toByteArray().let {
    ByteBuffer.wrap(it)
}

/**
 * Retrieve a android resource uri from a res id.
 */
fun Context.getResourceUri(resId: Int): String {
    return "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/$resId"
}

/**
 * Directory backing the [FileCache] for remote files downloaded over http(s).
 *
 * Lives under the app cache directory, so the OS may reclaim it under storage pressure.
 */
val Context.fileCacheDir: File
    get() = File(cacheDir, FileCache.DIRECTORY_NAME)

/**
 * Deletes every remote file cached by [FileCache] for this app.
 *
 * @return the number of cached files removed.
 */
fun Context.clearFileCache(): Int = FileCache.clear(fileCacheDir)

/** Reads the whole stream, then closes it. See [readFully]: a file or an asset is held once. */
fun InputStream.toByteArray(): ByteArray = use { it.readFully() }

private const val READ_CHUNK = 64 * 1024

/**
 * Reads the rest of the stream into one array sized from [sizeHint] — by default `available()`,
 * which is the exact remaining length for a file, an asset or a raw resource — filled in place and
 * returned as is. `readBytes()` grows a buffer and then copies it out, so a 50 MB model briefly
 * held 100 MB of Java heap. A wrong hint still reads everything, at the cost of one copy.
 *
 * [onProgress] gets the fraction read so far, after each chunk, when [sizeHint] is known (> 0).
 * Does not close the stream.
 */
internal fun InputStream.readFully(sizeHint: Int = available(), onProgress: ((Float) -> Unit)? = null): ByteArray {
    val expected = sizeHint.coerceAtLeast(0)
    var buffer = ByteArray(if (expected > 0) expected else DEFAULT_BUFFER_SIZE)
    var size = 0
    while (true) {
        if (size == buffer.size) {
            // Full: either the hint was exact (the next read says -1) or the stream is longer.
            val next = read()
            if (next < 0) break
            buffer = buffer.copyOf(maxOf(buffer.size * 2, DEFAULT_BUFFER_SIZE))
            buffer[size++] = next.toByte()
        }
        val n = read(buffer, size, minOf(buffer.size - size, READ_CHUNK))
        if (n < 0) break
        size += n
        if (onProgress != null && expected > 0) onProgress(minOf(1f, size.toFloat() / expected))
    }
    return if (size == buffer.size) buffer else buffer.copyOf(size)
}

fun ByteBuffer.clone() = ByteBuffer.allocate(this@clone.capacity()).apply {
    order(ByteOrder.nativeOrder())
    // Copy from the beginning
    this@clone.rewind()
    put(this@clone)
    this@clone.rewind()
    flip()
}

fun FloatBuffer.clone() = FloatBuffer.allocate(this@clone.capacity()).apply {
    // Copy from the beginning
    this@clone.rewind()
    put(this@clone)
    this@clone.rewind()
    flip()
}
