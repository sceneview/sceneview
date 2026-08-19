package io.github.sceneview.compose

import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads a remote model with the guards a raw `URL.openStream()` does not have.
 *
 * Restricted to http/https (`java.net.URL` would happily open `file://` and turn a
 * forwarded deep link into a local-file read), with connect/read timeouts so a silent
 * server cannot hang the IO coroutine forever, and a size cap so a hostile or simply
 * huge response cannot exhaust the heap.
 *
 * Shared by the Android and desktop actuals — one implementation, one cap.
 */
internal fun fetchModelBytes(url: String): ByteArray {
    val uri = URI(url)
    require(uri.scheme?.lowercase() in setOf("http", "https")) {
        "ModelSource.Url only accepts http and https, got '${uri.scheme}'"
    }

    val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
    }

    return try {
        // Refuse an oversized body before reading a single byte, when the server
        // announces one. The streaming check below is the real guard (Content-Length
        // is a hint a hostile server can omit or lie about), but honouring it costs
        // nothing and turns the common case into an instant, allocation-free refusal.
        val announced = connection.contentLengthLong
        require(announced <= MAX_MODEL_BYTES) {
            "Model at $url declares $announced bytes, over the ${MAX_MODEL_BYTES shr 20} MB limit"
        }

        connection.inputStream.use { stream ->
            val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                require(out.size() + read <= MAX_MODEL_BYTES) {
                    "Model at $url exceeds the ${MAX_MODEL_BYTES shr 20} MB limit"
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

internal const val CONNECT_TIMEOUT_MS = 15_000
internal const val READ_TIMEOUT_MS = 30_000
internal const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

/**
 * Ceiling on a downloaded model, in bytes.
 *
 * Sized against the heap, not against what a model "could" be. The bytes accumulate in
 * a `ByteArrayOutputStream`, whose growth-by-doubling plus the final `toByteArray()`
 * copy peaks near twice this value — so a cap set at a nominally generous 256 MB would
 * OOM the app long before it ever tripped, protecting nothing. 64 MB peaks around
 * 128 MB, which a typical heap survives, so the limit actually fires.
 */
internal const val MAX_MODEL_BYTES = 64L * 1024 * 1024
