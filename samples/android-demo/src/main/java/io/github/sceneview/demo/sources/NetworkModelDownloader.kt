package io.github.sceneview.demo.sources

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Streams model binaries to an on-disk LRU cache for the CC / CC0 sources
 * (Icosa Gallery, Poly Haven). Sketchfab keeps its own richer download path in
 * [io.github.sceneview.demo.sketchfab.SketchfabService]; this is the shared
 * fetcher the keyless JSON sources reuse so each new source doesn't re-implement
 * cancellable streaming, atomic writes, and cache eviction (#2645).
 *
 * Cache layout: `cacheDir/gallery/<sourceSlug>/…`. Single-file sources land a
 * flat `<id>.glb`; multi-file glTF sources get a per-id directory so the root
 * `.gltf` and its resources stay co-located (SceneView's `ModelLoader` resolves
 * external glTF URIs relative to the file).
 *
 * All streaming goes through OkHttp's [executeAsync] with the calling
 * coroutine's lifecycle tied to the call for its whole duration, mirroring the
 * cancellation guarantee documented on `SketchfabService.executeCancellably` —
 * cancelling a superseded download aborts the socket instead of leaking a
 * background transfer.
 */
class NetworkModelDownloader @VisibleForTesting internal constructor(
    context: Context,
    private val client: OkHttpClient = defaultClient,
) {
    private val root: File = File(context.cacheDir, CACHE_DIR_NAME)

    /** Per-source subdirectory under the gallery cache root. */
    @VisibleForTesting
    internal fun sourceDir(sourceId: ModelSourceId): File =
        File(root, sourceId.slug).also { it.mkdirs() }

    /**
     * Download a self-contained single file (e.g. a GLB) to
     * `gallery/<source>/<name>` and return it. Re-touches an existing cached
     * copy (LRU marker) without hitting the network.
     */
    suspend fun downloadSingle(
        sourceId: ModelSourceId,
        fileName: String,
        url: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val destination = File(sourceDir(sourceId), fileName)
        if (destination.exists() && destination.length() > 0L) {
            destination.setLastModified(System.currentTimeMillis())
            return@withContext destination
        }
        streamTo(url, destination, onProgress)
        pruneCacheIfNeeded()
        destination
    }

    /**
     * Download a multi-file asset: the [rootRelativePath] plus every entry in
     * [resources] (relative-path → absolute-URL) into a per-id directory, then
     * return the local root file. Progress is reported for the (usually
     * dominant) root file only; resources are typically small textures/buffers.
     *
     * The returned root sits alongside its resources so `ModelLoader` resolves
     * the glTF's external URIs from the same folder.
     */
    suspend fun downloadBundle(
        sourceId: ModelSourceId,
        assetId: String,
        rootRelativePath: String,
        rootUrl: String,
        resources: Map<String, String>,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val assetDir = File(sourceDir(sourceId), sanitize(assetId)).also { it.mkdirs() }
        val rootFile = File(assetDir, sanitizeRelative(rootRelativePath))
        // A complete bundle is trusted on re-open: the root exists non-empty and
        // every declared resource is present. A partial cache re-downloads.
        val complete = rootFile.exists() && rootFile.length() > 0L &&
            resources.keys.all { rel -> File(assetDir, sanitizeRelative(rel)).let { it.exists() && it.length() > 0L } }
        if (complete) {
            rootFile.setLastModified(System.currentTimeMillis())
            return@withContext rootFile
        }
        for ((relativePath, url) in resources) {
            val resourceFile = File(assetDir, sanitizeRelative(relativePath))
            if (resourceFile.exists() && resourceFile.length() > 0L) continue
            streamTo(url, resourceFile, onProgress = null)
        }
        streamTo(rootUrl, rootFile, onProgress)
        pruneCacheIfNeeded()
        rootFile
    }

    // ── Core streaming ────────────────────────────────────────────────────

    @Suppress("NestedBlockDepth") // use{} + temp-file try/catch + streaming loop is inherently nested
    private suspend fun streamTo(
        url: String,
        destination: File,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SketchfabConfig.USER_AGENT)
            .get()
            .build()
        val call = client.newCall(request)
        executeCancellably(call) {
            call.executeAsync().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed: HTTP ${response.code} for $url")
                }
                val body = response.body
                val totalBytes = body.contentLength() // -1 when unknown
                destination.parentFile?.mkdirs()
                // Stream into a per-call unique temp file, then atomically rename
                // onto `destination` so a cancelled / failed transfer never
                // leaves a truncated file that would poison the cache.
                val temp = File.createTempFile("${destination.nameWithoutExtension}-", ".tmp", destination.parentFile)
                try {
                    var bytesRead = 0L
                    val throttle = if (totalBytes > 0L) maxOf(totalBytes / 100L, 256 * 1024L) else 256 * 1024L
                    var nextReport = throttle
                    val buffer = ByteArray(8 * 1024)
                    temp.outputStream().use { out ->
                        body.byteStream().use { input ->
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                coroutineContext.ensureActive()
                                out.write(buffer, 0, n)
                                bytesRead += n
                                if (onProgress != null && bytesRead >= nextReport) {
                                    onProgress(bytesRead, totalBytes)
                                    nextReport = bytesRead + throttle
                                }
                            }
                            onProgress?.invoke(bytesRead, totalBytes)
                        }
                    }
                    if (!temp.renameTo(destination)) {
                        temp.copyTo(destination, overwrite = true)
                    }
                } finally {
                    if (temp.exists()) temp.delete()
                }
            }
        }
    }

    /**
     * Tie [call]'s lifecycle to the calling coroutine for the WHOLE stream, and
     * re-canonicalise the socket-abort IOException back to the caller's
     * CancellationException — the same guarantee documented at length on
     * `SketchfabService.executeCancellably` (#2644/#2665).
     */
    private suspend fun <T> executeCancellably(
        call: okhttp3.Call,
        block: suspend () -> T,
    ): T = coroutineScope {
        val tie = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            tie.cancel()
        }
    }

    // ── Cache management ──────────────────────────────────────────────────

    /**
     * Evict the oldest cache entries (files and per-id bundle directories) once
     * the gallery cache exceeds [CACHE_MAX_BYTES].
     */
    @VisibleForTesting
    internal fun pruneCacheIfNeeded() {
        if (!root.exists()) return
        // Prune at bundle-directory / flat-file granularity so a multi-file
        // glTF is never left half-evicted (which would render nothing).
        val entries = root.listFiles()?.flatMap { it.listFiles()?.toList() ?: listOf(it) } ?: return
        var total = entries.sumOf { it.sizeRecursive() }
        if (total <= CACHE_MAX_BYTES) return
        for (entry in entries.sortedBy { it.lastModified() }) {
            if (total <= CACHE_MAX_BYTES) break
            val size = entry.sizeRecursive()
            if (entry.deleteRecursively()) total -= size
        }
    }

    private fun File.sizeRecursive(): Long =
        if (isDirectory) walkBottomUp().filter { it.isFile }.sumOf { it.length() } else length()

    companion object {
        /** Subdirectory under `Context.cacheDir` for the keyless-source binaries. */
        const val CACHE_DIR_NAME: String = "gallery"

        /** Cache cap shared across the CC sources (250 MB), matching the samples-side Sketchfab budget. */
        const val CACHE_MAX_BYTES: Long = 250L * 1024 * 1024

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                // Force HTTP/1.1 + explicit UA for the same WAF-friendliness reason
                // as SketchfabService — CDNs flag OkHttp's default HTTP/2 fingerprint.
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                // No overall call ceiling: large models legitimately stream for
                // minutes; the per-read timeout catches a genuinely dead transfer.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        /** Reject path traversal / absolute paths from an untrusted resource map. */
        private fun sanitizeRelative(path: String): String =
            path.replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
                .ifEmpty { "resource" }

        /** Flatten an id into a single safe path segment for the bundle directory. */
        private fun sanitize(id: String): String =
            id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
                .ifEmpty { "asset" }
    }
}
