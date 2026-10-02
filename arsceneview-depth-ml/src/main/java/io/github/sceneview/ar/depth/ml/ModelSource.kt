package io.github.sceneview.ar.depth.ml

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where a depth model file comes from. */
sealed interface ModelSource {

    /** A `.tflite` file in the app's `assets/` (adds its size to the APK). */
    data class Asset(val path: String) : ModelSource

    /** A `.tflite` file already on disk. */
    data class LocalFile(val file: File) : ModelSource

    /**
     * A `.tflite` file downloaded once into the app's no-backup directory and verified against
     * [sha256] before use. A file whose digest does not match is deleted, never loaded.
     *
     * @param url an immutable URL — pin a revision, never a branch.
     * @param sha256 lowercase hex SHA-256 of the file.
     * @param sizeBytes expected size, for progress when the server sends no length.
     */
    data class Url(
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val fileName: String = url.substringAfterLast('/'),
    ) : ModelSource

    companion object {
        /**
         * Depth Anything V2 **Small** (Apache-2.0), LiteRT conversion with INT8 weights and FP32
         * activations: 27.7 MB, input `[1, 3, 518, 686]`, output `[1, 518, 686]` relative
         * inverse depth. Pinned to revision `178427e` of
         * [litert-community/depth-anything-v2-small](https://huggingface.co/litert-community/depth-anything-v2-small).
         *
         * Only the Small variant is Apache-2.0: Base, Large and Giant are CC-BY-NC-4.0 and must
         * not be used in a commercial app.
         */
        val DepthAnythingV2Small = Url(
            url = "https://huggingface.co/litert-community/depth-anything-v2-small/resolve/" +
                "178427e448dbf4da93b1e7b1b2abc103ad329bd6/tflite/depth_anything_v2_small_wi8_afp32.tflite",
            sha256 = "f74509422e4a9270a354b249a9193abdd4903354be63701262238a7f4b869611",
            sizeBytes = 27_733_680L,
        )
    }
}

/** Downloads a [ModelSource.Url] once and verifies it. Blocking: call it off the main thread. */
object ModelDownloader {

    private const val BUFFER_SIZE = 64 * 1024
    private const val TIMEOUT_MS = 30_000

    /**
     * Returns the verified local copy of [source] in [directory], downloading it first if it is
     * missing or does not match its digest.
     *
     * @param onProgress called with `0..1` while downloading; not called when the file is cached.
     * @throws IOException on a network error or a digest mismatch.
     */
    fun ensure(source: ModelSource.Url, directory: File, onProgress: (Float) -> Unit = {}): File {
        val target = File(directory, source.fileName)
        if (target.isFile && sha256(target).equals(source.sha256, ignoreCase = true)) return target
        directory.mkdirs()
        val part = File(directory, source.fileName + ".part")
        part.delete()
        val digest = download(source, part, onProgress)
        if (!digest.equals(source.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("Model digest mismatch for ${source.fileName}: expected ${source.sha256}, got $digest")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
        return target
    }

    private fun download(source: ModelSource.Url, part: File, onProgress: (Float) -> Unit): String {
        val connection = URL(source.url).openConnection().apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        (connection as? HttpURLConnection)?.instanceFollowRedirects = true
        try {
            val code = (connection as? HttpURLConnection)?.responseCode
            if (code != null && code !in 200..299) throw IOException("HTTP $code for ${source.url}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: source.sizeBytes
            val md = MessageDigest.getInstance("SHA-256")
            connection.getInputStream().use { input ->
                part.outputStream().use { output -> copyHashing(input, output, md, total, onProgress) }
            }
            return md.digest().toHex()
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    private fun copyHashing(
        input: InputStream,
        output: OutputStream,
        md: MessageDigest,
        total: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var read = 0L
        var n = input.read(buffer)
        while (n >= 0) {
            output.write(buffer, 0, n)
            md.update(buffer, 0, n)
            read += n
            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
            n = input.read(buffer)
        }
    }

    /** Lowercase hex SHA-256 of [file]. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
