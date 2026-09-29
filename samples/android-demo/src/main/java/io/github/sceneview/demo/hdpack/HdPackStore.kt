package io.github.sceneview.demo.hdpack

import io.github.sceneview.demo.sketchfab.SketchfabConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A download of the pack in flight: bytes on disk (completed files + the current one) over the
 * total, and which file is on the wire. The viewer pill narrates one model, so it reads
 * [fractionOf] its own asset rather than the pack-wide [fraction].
 */
data class HdTransfer(
    val doneBytes: Long,
    val totalBytes: Long,
    val assetId: String? = null,
    val assetDoneBytes: Long = 0L,
    val assetBytes: Long = 0L,
) {
    val fraction: Float get() = ratio(doneBytes, totalBytes)

    /** How far [id]'s own file is, or `null` while another file of the pack is on the wire. */
    fun fractionOf(id: String): Float? = if (id == assetId) ratio(assetDoneBytes, assetBytes) else null

    private fun ratio(done: Long, total: Long): Float =
        if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** The downloaded file failed its SHA-256 or size check. It is deleted, never kept. */
class HdPackIntegrityException(message: String) : IOException(message)

/**
 * Permanent on-disk store of the HD pack: `filesDir/hd-pack/<sha256>.<ext>`.
 *
 * Same streaming discipline as `NetworkModelDownloader` — OkHttp `executeAsync` tied to the
 * calling coroutine, a temp file, an atomic rename — with two differences the contract asks for:
 *
 * - **Resumable.** The temp file is `<file>.part` and survives a failed or cancelled transfer.
 *   The next attempt asks for `Range: bytes=<size>-` and appends on a `206`; a server that
 *   answers `200` restarts the file from zero.
 * - **Verified.** A finished file is hashed whole and compared with the manifest's `sha256` and
 *   `bytes` before the rename. A mismatch deletes the part file: a corrupt pack never reaches
 *   the renderer.
 *
 * Nothing here is ever evicted for space. [prune] deletes only names the manifest no longer
 * lists; [removeAll] is the user's own "Remove".
 */
class HdPackStore(
    val manifest: HdPackManifest,
    private val dir: File,
    private val client: OkHttpClient = defaultClient,
    private val baseUrl: String = HdPackManifest.BASE_URL,
) {
    private val _readyIds = MutableStateFlow(scanReady())
    /** Ids of the assets whose verified file is on disk. */
    val readyIds: StateFlow<Set<String>> = _readyIds.asStateFlow()

    private val _transfer = MutableStateFlow<HdTransfer?>(null)
    /** The download in flight, or `null` when none is running in this process. */
    val transfer: StateFlow<HdTransfer?> = _transfer.asStateFlow()

    private val downloadLock = Mutex()

    /** Where [asset] lives once downloaded. The file may not exist yet. */
    fun fileFor(asset: HdAsset): File = File(dir, asset.file)

    /** The verified local file of [id], or `null` while it is not on disk. */
    fun readyFile(id: String): File? =
        manifest.asset(id)?.takeIf { it.id in _readyIds.value }?.let(::fileFor)

    val isComplete: Boolean get() = _readyIds.value.size == manifest.assets.size

    /** Whether every asset in [ids] is on disk and verified. */
    fun isReady(ids: Set<String>): Boolean = _readyIds.value.containsAll(ids)

    /** Bytes the pack occupies on disk right now, part files included. */
    fun bytesOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Downloads the assets not yet on disk, one after the other, in [downloadOrder]. Safe to
     * call concurrently — a second caller waits for the first (its model reads "queued") and
     * finds the files already there.
     *
     * @param only ids to fetch — the one model the user tapped, or the prefetch set. `null`
     * fetches every missing asset (About → "Download now").
     * @throws IOException on a network failure (the part file is kept for the next attempt) or
     * an [HdPackIntegrityException] (the part file is deleted).
     */
    suspend fun downloadMissing(only: Set<String>? = null) = downloadLock.withLock {
        withContext(Dispatchers.IO) {
            prune()
            val total = manifest.totalBytes
            var completed = manifest.assets.filter { it.id in _readyIds.value }.sumOf { it.bytes }
            try {
                for (asset in downloadOrder()) {
                    if (asset.id in _readyIds.value || (only != null && asset.id !in only)) continue
                    val base = completed
                    val progress = { onDisk: Long ->
                        _transfer.value = HdTransfer(base + onDisk, total, asset.id, onDisk, asset.bytes)
                    }
                    progress(partFile(asset).length())
                    download(asset, progress)
                    completed += asset.bytes
                    _readyIds.value = _readyIds.value + asset.id
                }
            } finally {
                _transfer.value = null
            }
        }
    }

    /**
     * The order files are fetched in: [first] (the model on screen), then the smallest first —
     * a fresh install on Wi-Fi gets a model to show in seconds instead of after the biggest file.
     */
    fun downloadOrder(first: String? = null): List<HdAsset> =
        manifest.assets.sortedWith(compareBy<HdAsset>({ it.id != first }, { it.bytes }))

    /** Deletes every file of the pack — the user's "Remove". Returns the bytes freed. */
    suspend fun removeAll(): Long = downloadLock.withLock {
        withContext(Dispatchers.IO) {
            val freed = bytesOnDisk()
            dir.listFiles()?.forEach { it.delete() }
            _readyIds.value = emptySet()
            freed
        }
    }

    /**
     * Deletes every file whose name the manifest no longer lists — a hash an app update
     * replaced. Current files and their `.part` files are kept.
     */
    fun prune() {
        val keep = manifest.assets.flatMap { listOf(it.file, partName(it)) }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun scanReady(): Set<String> =
        manifest.assets.filter { fileFor(it).let { f -> f.isFile && f.length() == it.bytes } }
            .map { it.id }.toSet()

    private fun partName(asset: HdAsset) = "${asset.file}.part"

    private fun partFile(asset: HdAsset) = File(dir, partName(asset))

    private suspend fun download(asset: HdAsset, onProgress: (Long) -> Unit) {
        dir.mkdirs()
        val part = partFile(asset)
        if (part.length() > asset.bytes) part.delete()
        if (part.length() < asset.bytes) stream(asset, part, onProgress)
        val digest = sha256(part)
        if (part.length() != asset.bytes || digest != asset.sha256) {
            part.delete()
            throw HdPackIntegrityException(
                "HD asset ${asset.id}: got ${part.length()} B sha256=$digest, " +
                    "expected ${asset.bytes} B sha256=${asset.sha256}",
            )
        }
        Files.move(
            part.toPath(),
            fileFor(asset).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    @Suppress("NestedBlockDepth") // use{} + streaming loop, same shape as NetworkModelDownloader
    private suspend fun stream(asset: HdAsset, part: File, onProgress: (Long) -> Unit) {
        val resumeFrom = part.length()
        val request = Request.Builder()
            .url("$baseUrl/${asset.file}")
            .header("User-Agent", SketchfabConfig.USER_AGENT)
            .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
            .get()
            .build()
        val call = client.newCall(request)
        executeCancellably(call) {
            call.executeAsync().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HD asset ${asset.id}: HTTP ${response.code}")
                }
                // 206 → the server honoured the range: append. 200 → it sent the whole file.
                val append = resumeFrom > 0L && response.code == HTTP_PARTIAL
                var onDisk = if (append) resumeFrom else 0L
                val throttle = maxOf(asset.bytes / PROGRESS_STEPS, MIN_PROGRESS_BYTES)
                var nextReport = onDisk + throttle
                FileOutputStream(part, append).use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            currentCoroutineContext().ensureActive()
                            out.write(buffer, 0, n)
                            onDisk += n
                            if (onDisk > asset.bytes) {
                                throw HdPackIntegrityException(
                                    "HD asset ${asset.id}: more bytes than the manifest says",
                                )
                            }
                            if (onDisk >= nextReport) {
                                onProgress(onDisk)
                                nextReport = onDisk + throttle
                            }
                        }
                    }
                }
                onProgress(onDisk)
            }
        }
    }

    /** Ties [call] to the calling coroutine, as `NetworkModelDownloader.executeCancellably` does. */
    private suspend fun <T> executeCancellably(call: okhttp3.Call, block: suspend () -> T): T = coroutineScope {
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
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            tie.cancel()
        }
    }

    companion object {
        private const val HTTP_PARTIAL = 206
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEPS = 200L
        private const val MIN_PROGRESS_BYTES = 128 * 1024L

        /** Lowercase hex SHA-256 of [file]. */
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    md.update(buffer, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
