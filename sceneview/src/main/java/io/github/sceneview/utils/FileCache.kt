package io.github.sceneview.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk cache for remote files (glTF/GLB models, KTX environments, textures…) fetched over
 * http(s).
 *
 * The first time a URL is loaded its bytes are persisted under a cache directory and reused on
 * every subsequent load — no repeated downloads, instant offline reuse. Entries are keyed by the
 * full URL; since 3D asset files are immutable there is no invalidation to worry about. Call
 * [clear] to reclaim disk space.
 *
 * It is wired into [FileLoader.loadFileBuffer], so every http(s) model, environment and texture
 * load goes through it automatically. Set [enabled] to `false` to bypass it entirely.
 *
 * This object is intentionally [Context][android.content.Context]-free so its logic is unit
 * testable on a plain JVM — the `cacheDir` is supplied by the caller. App code resolves it via
 * [Context.fileCacheDir].
 */
object FileCache {

    /** When `false`, http(s) loads always hit the network and nothing is persisted. */
    @Volatile
    var enabled: Boolean = true

    /** Sub-directory name used under the host app cache directory. */
    const val DIRECTORY_NAME: String = "sceneview-http-cache"

    /** Per-URL locks de-duplicating concurrent downloads of the same file. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** SHA-256 hex of [url] — a filesystem-safe, collision-free cache key. */
    fun keyOf(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The cache file backing [url] inside [cacheDir], whether or not it currently exists. */
    fun fileOf(cacheDir: File, url: String): File = File(cacheDir, keyOf(url))

    /**
     * Returns the cached bytes for [url], or downloads, persists and returns them on a miss.
     *
     * Concurrent requests for the same [url] are de-duplicated: only the first triggers
     * [download], the rest await its result and read it back from disk. The on-disk write is
     * atomic (temp file + rename), so an interrupted download never leaves a corrupt entry.
     *
     * @param cacheDir directory the entry is stored in; created on demand.
     * @param url      the http(s) URL identifying the entry.
     * @param download fetches the raw bytes when the cache has no entry for [url].
     * @return the file bytes, or `null` when [download] yields `null`.
     */
    suspend fun getOrPut(
        cacheDir: File,
        url: String,
        download: suspend () -> ByteArray?
    ): ByteBuffer? {
        if (!enabled) return download()?.let { ByteBuffer.wrap(it) }
        val file = fileOf(cacheDir, url)
        readEntry(file)?.let { return it }
        val mutex = locks.getOrPut(url) { Mutex() }
        try {
            return mutex.withLock {
                // Another coroutine may have populated the entry while we waited for the lock.
                readEntry(file) ?: download()?.let { bytes ->
                    writeEntry(file, bytes)
                    ByteBuffer.wrap(bytes)
                }
            }
        } finally {
            // Drop the per-URL lock once nobody holds it, so `locks` cannot grow without bound
            // as distinct URLs are streamed. A lost race here only costs a redundant download —
            // never a corrupt entry, since writeEntry publishes atomically.
            if (!mutex.isLocked) locks.remove(url, mutex)
        }
    }

    /**
     * Deletes every cached entry in [cacheDir]. Returns the number of files removed.
     *
     * Not synchronized against in-flight [getOrPut] downloads — a download that publishes
     * concurrently may survive the clear. That is harmless for a cache.
     */
    fun clear(cacheDir: File): Int =
        cacheDir.listFiles()?.count { it.isFile && it.delete() } ?: 0

    /** Total size of the cache under [cacheDir], in bytes. */
    fun size(cacheDir: File): Long =
        cacheDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Number of per-URL download locks currently retained — test hook for the no-leak invariant. */
    internal fun pendingLockCount(): Int = locks.size

    private fun readEntry(file: File): ByteBuffer? =
        if (file.isFile) runCatching { ByteBuffer.wrap(file.readBytes()) }.getOrNull() else null

    private fun writeEntry(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeBytes(bytes)
            // Atomic publish: a reader sees either no entry or the complete file, never a partial.
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }.onFailure { tmp.delete() }
    }
}
