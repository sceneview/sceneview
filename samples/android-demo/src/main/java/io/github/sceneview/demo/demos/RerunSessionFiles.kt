package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import io.github.sceneview.demo.OpenedModelIntent
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugLogWriter
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPlane
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.PlaneBake
import io.github.sceneview.demo.demos.internal.ReplayDense
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.ReplayPlaneTexture
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunFileKind
import io.github.sceneview.demo.demos.internal.RerunImportFailure
import io.github.sceneview.demo.demos.internal.RerunRrdReader
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunShareCopy
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.demos.internal.ScanArchive
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.SvpcCodec
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/*
 * The Rerun demo's sessions, on Android: a finished scan built into the recorder's three files —
 * the log, the manifest, the photos and the planes painted from them, the same parts the bundled
 * replay ships — kept in the app's private storage in the iOS demo's layout
 * (internal/RerunSessions.kt), shared as a `.svscan`, and read back from one.
 */

/** One photo of a scan: its [path] in the log, its JPEG, and the display-oriented camera [pose]. */
internal class ScanPhoto(val path: String, val jpeg: ByteArray, val pose: DebugPose)

/** Builds a finished scan into the recorder's three files. */
internal object RerunCaptureBuilder {
    /**
     * The scan of [events] (a trace's journal: every change it kept, in order), photographed by
     * [photos] through [lens]. Off the main thread.
     *
     * With a [device] the scan is a `.svscan` v2: its [dense] cloud (tier `depth`), when it has a
     * surfel, goes into the archive as `dense/points.bin` (SVPC, [SvpcCodec]) — never an empty
     * blob; a sparse-tier v2 carries no `dense` key and reads exactly like a v1 scan.
     */
    suspend fun build(
        events: List<ArDebugEvent>,
        lens: ReplayLens,
        photos: List<ScanPhoto>,
        device: ScanDevice? = null,
        dense: DenseCloud? = null,
        denseVoxelM: Float = DenseFusion.VOXEL_M,
        denseMs: Long = 0L,
    ): RerunCapturePack = withContext(Dispatchers.Default) {
        val trace = ArDebugTrace.of(events).apply { keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M }
        val whole = trace.frameAt(trace.duration)
        val decoded = HashMap<Int, BakePhoto?>()
        fun photo(index: Int): BakePhoto? = decoded.getOrPut(index) { decodePhoto(photos[index]) }

        val viewpoint = centroid(whole.trail)
        val baked = trace.lastPlanes().mapNotNull { plane -> bakePlane(plane, viewpoint, photos, lens, ::photo) }
        val denseCloud = dense?.takeIf { device != null && it.count > 0 }
        val denseBlob = denseCloud?.let { listOf(ReplayDense.PATH to SvpcCodec.encode(it)) }.orEmpty()
        val (archive, spans) = ScanArchive.pack(
            photos.map { it.path to it.jpeg } + baked.map { (texture, jpeg) -> texture.path to jpeg } + denseBlob,
        )
        val manifest = ReplayManifest(
            version = if (device != null) 2 else 1,
            device = device,
            dense = denseCloud?.let {
                ReplayDense(ReplayDense.PATH, it.count, denseVoxelM, it.normals != null, it.bounds())
            },
            denseMs = denseMs,
            lens = lens,
            frameRate = if (trace.duration > 0f) trace.imageCount / trace.duration else 0f,
            frameCount = trace.imageCount,
            floorY = trace.lastPlanes().filter { it.kind == DebugPlaneKind.Floor }.minOfOrNull(::lowestY),
            textures = baked.map { it.first },
            media = spans,
        )
        RerunCapturePack(
            manifest = manifest.toJson().toByteArray(),
            log = ArDebugLogWriter.write(events).toByteArray(),
            media = archive,
        )
    }

    /** [plane]'s texture and its JPEG, painted from the photos that see it best; `null` if none do. */
    private fun bakePlane(
        plane: DebugPlane,
        viewpoint: Vec3?,
        photos: List<ScanPhoto>,
        lens: ReplayLens,
        photo: (Int) -> BakePhoto?,
    ): Pair<ReplayPlaneTexture, ByteArray>? {
        val rect = PlaneBake.rectOf(plane, viewpoint) ?: return null
        val (width, height) = PlaneBake.sizeOf(rect)
        val picked = PlaneBake.pickPhotos(rect, photos.map { it.pose }, lens).mapNotNull(photo)
        val texels = PlaneBake.bake(rect, width, height, picked, lens) ?: return null
        val bitmap = Bitmap.createBitmap(texels, width, height, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        val texture = ReplayPlaneTexture(plane.id, "planes/plane-${plane.id}.jpg", rect.origin, rect.u, rect.v)
        return texture to jpeg.toByteArray()
    }

    private fun decodePhoto(photo: ScanPhoto): BakePhoto? {
        val bitmap = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return BakePhoto(photo.pose, bitmap.width, bitmap.height, argb).also { bitmap.recycle() }
    }

    /** Where the scan was walked from: the middle of the camera's path, `null` without one. */
    private fun centroid(trail: FloatArray): Vec3? {
        val n = trail.size / 3
        if (n == 0) return null
        var x = 0f
        var y = 0f
        var z = 0f
        for (i in 0 until n) {
            x += trail[i * 3]
            y += trail[i * 3 + 1]
            z += trail[i * 3 + 2]
        }
        return Vec3(x / n, y / n, z / n)
    }

    private fun lowestY(plane: DebugPlane): Float =
        (0 until plane.polygon.size / 3).minOf { plane.polygon[it * 3 + 1] }

    private const val JPEG_QUALITY = 85
}

/** The sessions kept on this phone: `filesDir/rerun/sessions/<id>/`. */
internal fun rerunSessionStore(context: Context) = RerunSessionStore(File(context.filesDir, "rerun/sessions"))

/** A kept session as the landing shows it: its `session.json` and its first photo. */
internal class LandingSession(val info: RerunStoredSession, val thumbnail: ImageBitmap?) {
    val id: String get() = info.id
}

/** Every kept session with its thumbnail, newest first. IO. */
internal suspend fun RerunSessionStore.landingSessions(): List<LandingSession> = withContext(Dispatchers.IO) {
    list().map { info ->
        val thumbnail = thumbnail(info.id)?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
        LandingSession(info, thumbnail)
    }
}

/**
 * "Room · Sep 28, 2:32 PM": in English like the rest of the demo, whatever the phone's language —
 * a French phone wrote "Room · 30 sept., 13:56" into an English screen.
 */
internal fun recordingTitle(nowMillis: Long, locale: Locale = Locale.US): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd jmm")
    return RerunStoredSession.recordingTitle(SimpleDateFormat(pattern, locale).format(Date(nowMillis)))
}

/** "Sep 28, 2:32 PM · Recorded", in English like [recordingTitle]. */
internal fun sessionOrigin(session: RerunStoredSession, locale: Locale = Locale.US): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd jmm")
    val date = SimpleDateFormat(pattern, locale).format(Date(session.createdAt * MILLIS))
    return "$date · ${session.source.label}"
}

/** A scan file ready to share: the [file] in the cache and its weight, read off the main thread. */
internal class PreparedScan(val includePhotos: Boolean, val file: File, val bytes: Long)

/**
 * Session [session] as the scan file the share sheet is about to send — with its photos, or
 * without them ([RerunShareCopy]) — in a fresh cache directory, named after its title. The size
 * the sheet shows is this very file's. `null` when the session's capture is gone.
 *
 * One copy at a time: each call replaces the previous one, and a call made while another is still
 * writing waits for it, so a switch flipped twice in a row never deletes a file being written.
 * The copy lives in its own directory of the share root ([RERUN_SHARE_SCAN]): the model and the
 * exports are written next to it, never over it.
 */
internal suspend fun prepareSharedScan(
    context: Context,
    store: RerunSessionStore,
    session: RerunStoredSession,
    includePhotos: Boolean,
): PreparedScan? {
    // Counted before the copy starts: a clean-up asked earlier never removes this copy.
    shareCopyRequests.incrementAndGet()
    return withContext(Dispatchers.IO) {
        shareCopyLock.withLock {
            val capture = store.capture(session.id) ?: return@withContext null
            val root = rerunShareDirectory(context, RERUN_SHARE_SCAN)
            val file = RerunShareCopy.write(capture, session.title, root, includePhotos)
            PreparedScan(includePhotos, file, file.length())
        }
    }
}

/**
 * Removes the scan copy written for the share sheet: a sheet closed without sending, or a deleted
 * scan, leaves no photo of the room behind in the cache. It waits for a copy still being written,
 * outlives the screen that asked for it, and leaves alone a copy asked for after it.
 */
internal fun discardSharedScan(context: Context) {
    val root = rerunShareDirectory(context, RERUN_SHARE_SCAN)
    val asked = shareCopyRequests.get()
    shareCleanup.launch {
        shareCopyLock.withLock { if (shareCopyRequests.get() == asked) root.deleteRecursively() }
    }
}

/** The directory of the share root one writer owns: a writer only ever empties its own. */
internal fun rerunShareDirectory(context: Context, use: String): File =
    File(File(context.cacheDir, RERUN_SHARE_DIR), use)

private val shareCopyLock = Mutex()
private val shareCopyRequests = AtomicInteger()
private val shareCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Hands the prepared [file] to the system's share sheet: the bytes sent are the bytes weighed. */
internal fun sharePreparedScan(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = SCAN_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Reads the file at [uri] — a scan file, from the picker or another app — and keeps it as a new
 * session. IO. The failure says why, in [ScanCopy.importFailure]'s words.
 */
internal suspend fun importSession(context: Context, store: RerunSessionStore, uri: Uri): Result<RerunStoredSession> =
    withContext(Dispatchers.IO) {
        val name = OpenedModelIntent.displayName(context, uri) ?: uri.lastPathSegment ?: "file"
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded() }
                ?: throw RerunImportFailure.Unreadable(name)
            store.import(name, bytes, readRrd = { RerunRrdReader.capturePack(it, name, BitmapRerunImageCodec) })
        }
    }

/**
 * A scan file or a Rerun recording handed over by another app ("Open with" / Share), recognised
 * by its name or, for a nameless one, by its first bytes — `false` for any other file, which goes
 * on to the model viewer.
 * The file is only peeked at here; [importSession] reads it.
 */
internal object RerunInbox {
    const val DEMO_ID = "ar-rerun"

    fun accepts(context: Context, uri: Uri): Boolean {
        val name = OpenedModelIntent.displayName(context, uri)
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(HEAD_BYTES) }
        }.getOrNull() ?: return false
        // Both kinds: a scan file, and a Rerun recording (this demo's export, or one from Rerun).
        return RerunFileKind.of(name, head) != null
    }
}

private fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(out, read, count - read)
        if (n < 0) break
        read += n
    }
    return out.copyOf(read)
}

/** The whole stream, or [RerunImportFailure.Unreadable] past the size no scan comes near. */
private fun InputStream.readBounded(): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > MAX_FILE_BYTES) throw RerunImportFailure.Unreadable("file")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** A share sheet reads a `.svscan` as any binary file: nothing on Android knows the type. */
private const val SCAN_MIME_TYPE = "application/octet-stream"
/** Scan files and exports handed to the share sheet; the FileProvider exposes only this. */
internal const val RERUN_SHARE_DIR = "rerun-share"
/** One directory per writer under [RERUN_SHARE_DIR]: the scan copy, the model, the exports. */
internal const val RERUN_SHARE_SCAN = "scan"
internal const val RERUN_SHARE_MODEL = "model"
internal const val RERUN_SHARE_EXPORT = "export"
private const val MILLIS = 1000L
private const val HEAD_BYTES = 64
private const val BUFFER_BYTES = 64 * 1024

/** Nothing a phone records comes near this; a file that claims more is not one of ours. */
private const val MAX_FILE_BYTES = 512L * 1024 * 1024
