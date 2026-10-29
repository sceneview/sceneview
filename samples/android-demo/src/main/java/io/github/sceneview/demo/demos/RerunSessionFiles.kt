package io.github.sceneview.demo.demos

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.OpenedModelIntent
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugLogWriter
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.BakeOcclusion
import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.BakePhotoCache
import io.github.sceneview.demo.demos.internal.DebugPlane
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.PlaneBake
import io.github.sceneview.demo.demos.internal.PlaneRect
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
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.ScanScopeCopy
import io.github.sceneview.demo.demos.internal.ScanArchive
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.ScanFootprint
import io.github.sceneview.demo.demos.internal.ScanPhotoPolicy
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
        camera: String? = null,
    ): RerunCapturePack = withContext(Dispatchers.Default) {
        val trace = ArDebugTrace.of(events).apply { keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M }
        val whole = trace.frameAt(trace.duration)
        val reads = ArrayList<ScanPhotoPolicy.BakeRead>()

        val viewpoint = centroid(whole.trail)
        val planes = trace.lastPlanes()
        // What stands in front of a plane hides it from a photo: its own dense cloud and the other planes.
        val bakeStart = System.nanoTime()
        val occlusion = BakeOcclusion.of(dense, planes)
        // Each photo decoded on the first plane that paints from it, and let go after the last one.
        val rects = planes.map { PlaneBake.rectOf(it, viewpoint) }
        val poses = photos.map { it.pose }
        val picks = rects.map { rect -> rect?.let { PlaneBake.pickPhotos(it, poses, lens) }.orEmpty() }
        val cache = BakePhotoCache(picks) { decodePhoto(photos[it], reads::add) }
        val baked = planes.indices.mapNotNull { i -> bakePlane(planes[i], rects[i], lens, occlusion, cache.photosFor(i)) }
        // `adb logcat -s RerunCaptureBuilder`: what the visibility test costs on a real phone (#4697),
        // and at what size the bake read the photos it painted from.
        // The JVM tests build scans too, and their android.util.Log is a stub that throws.
        val bakeMs = (System.nanoTime() - bakeStart) / NANOS_PER_MS
        runCatching {
            Log.i(BAKE_LOG_TAG, "planes baked: ${baked.size} of ${planes.size}, ${photos.size} photos, " +
                "${dense?.count ?: 0} dense points, $bakeMs ms; ${ScanPhotoPolicy.bakeReadsLog(reads)}; " +
                cache.logLine() + camera?.let { "; $it" }.orEmpty())
        }
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
            log = ArDebugLogWriter.writeBytes(events),
            media = archive,
        )
    }

    /** [plane]'s texture and its JPEG, painted from the photos that see it best; `null` if none do. */
    private fun bakePlane(
        plane: DebugPlane,
        rect: PlaneRect?,
        lens: ReplayLens,
        occlusion: BakeOcclusion?,
        picked: List<BakePhoto>,
    ): Pair<ReplayPlaneTexture, ByteArray>? {
        if (rect == null) return null
        val (width, height) = PlaneBake.sizeOf(rect)
        val baked = PlaneBake.bake(rect, width, height, picked, lens, occlusion, plane.id) ?: return null
        val bitmap = Bitmap.createBitmap(baked.argb, baked.width, baked.height, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        // Cropped to what the photos saw: the texture's own rectangle, not the polygon's.
        val area = baked.rect
        val texture = ReplayPlaneTexture(plane.id, "planes/plane-${plane.id}.jpg", area.origin, area.u, area.v)
        return texture to jpeg.toByteArray()
    }

    /**
     * [photo]'s pixels for the plane bake, read at [ScanPhotoPolicy.BAKE_LONG_SIDE] on the long side
     * rather than whole: the build holds every photo a plane still to bake paints from, and a sharp
     * photo read whole holds four times the pixels the texture can use. The bake samples a photo by
     * its normalized position, so a reduced one still lands where the full one would. [onRead] hears
     * each photo read, for the build's log line.
     */
    @VisibleForTesting
    internal fun decodePhoto(photo: ScanPhoto, onRead: (ScanPhotoPolicy.BakeRead) -> Unit = {}): BakePhoto? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, bounds)
        val sample = ScanPhotoPolicy.bakeSample(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, options) ?: return null
        onRead(ScanPhotoPolicy.BakeRead(bounds.outWidth, bounds.outHeight, sample, bitmap.width, bitmap.height))
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
    private const val BAKE_LOG_TAG = "RerunCaptureBuilder"
    private const val NANOS_PER_MS = 1_000_000L
}

/** The sessions kept on this phone: `filesDir/rerun/sessions/<id>/`. */
internal fun rerunSessionStore(context: Context) = RerunSessionStore(File(context.filesDir, "rerun/sessions"))

/**
 * A kept session as the landing shows it: its `session.json` and its first photo — or, for a scan
 * kept without photos, its [footprint] seen from above (#4680).
 */
internal class LandingSession(
    val info: RerunStoredSession,
    val thumbnail: ImageBitmap?,
    val footprint: ScanFootprint? = null,
) {
    val id: String get() = info.id
}

/** Every kept session with its thumbnail, newest first. IO. */
internal suspend fun RerunSessionStore.landingSessions(): List<LandingSession> = withContext(Dispatchers.IO) {
    runCatching { sweepOrphans() }
    list().map { info ->
        val thumbnail = thumbnail(info.id)?.let { file ->
            runCatching {
                // The first photo may be a sharp one: decoded at a card's size, never whole.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = ScanPhotoPolicy.previewSample(bounds.outWidth, bounds.outHeight)
                }
                BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
            }.getOrNull()
        }
        val footprint = if (thumbnail != null) null else footprintOf(info.id)
        LandingSession(info, thumbnail, footprint)
    }
}

/**
 * Session [id] from above, read once per process: a capture never changes once kept, and reading
 * one back (its log, its dense cloud) is too slow to repeat on every visit to the list.
 */
private fun RerunSessionStore.footprintOf(id: String): ScanFootprint? =
    footprints.getOrPut(File(root, id).path) {
        Optional(runCatching { capture(id)?.open()?.let { ScanFootprint.of(it) } }.getOrNull())
    }.value

private class Optional(val value: ScanFootprint?)
private val footprints = java.util.concurrent.ConcurrentHashMap<String, Optional>()

/**
 * A scan's date and time as every 3D Scan screen writes it (#4720): `Sep 28, 2:32 PM`. One
 * format, the locale's own pattern for it — [Locale.US], because the demo is in English whatever
 * the phone's language: a French phone wrote "Room · 30 sept., 13:56" into an English screen.
 */
internal fun scanDate(millis: Long, locale: Locale = Locale.US): String =
    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, SCAN_DATE_SKELETON), locale).format(Date(millis))

/**
 * [scanDate] with its year, for a document that outlives the day — the floor plan's PDF:
 * `Sep 28, 2026, 2:32 PM`.
 */
internal fun scanDateWithYear(millis: Long, locale: Locale = Locale.US): String =
    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "y$SCAN_DATE_SKELETON"), locale).format(Date(millis))

private const val SCAN_DATE_SKELETON = "MMMd jmm"

/**
 * "Room · Sep 28, 2:32 PM" ([scanDate]). Named after what was scanned (#4647): "Object · …",
 * "Room 2 · …" for the second room of a home.
 */
internal fun recordingTitle(
    nowMillis: Long,
    scope: ScanScope = ScanScope.Room,
    roomNumber: Int = 1,
    locale: Locale = Locale.US,
): String = ScanScopeCopy.sessionTitle(scope, roomNumber, scanDate(nowMillis, locale))

/**
 * "Sep 28, 2:32 PM · Recorded" ([scanDate]); "Recorded" alone when the title already says when
 * ("Room · Sep 28, 2:32 PM"), so a card never writes the same date twice (#4720).
 */
internal fun sessionOrigin(session: RerunStoredSession, locale: Locale = Locale.US): String {
    val date = scanDate(session.createdAt * MILLIS, locale)
    return if (session.title.contains(date)) session.source.label else "$date · ${session.source.label}"
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
 * The copy lives in its own directory of the share root ([RERUN_SHARE_SCAN]): the exports and the
 * plan are written next to it, never over it.
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
            val file = RerunShareCopy.write(capture, session.title, root, includePhotos, session.scope)
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
        shareCopyLock.withLock {
            // A copy sent to this very app ("Open in SceneView") is kept until it is read (#4680).
            if (shareCopyRequests.get() == asked && !RerunInbox.reading) root.deleteRecursively()
        }
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

    /**
     * Hands [uri] to the 3D Scan of [activity] — the activity the intent reached — and to no
     * other. The process can hold more than one MainActivity: "Open in SceneView" picked from the
     * demo's own share sheet starts a second one in its own task, the first one still showing
     * 3D Scan behind it. Both compose 3D Scan; with a process-wide inbox both read the file in the
     * same frame and it was imported twice (#4680).
     */
    fun post(activity: Activity, uri: Uri) {
        val delivery = RerunInboxDelivery(uri, System.identityHashCode(activity))
        // One file waits at a time: the one it replaces will never be read.
        DemoSettings.rerunInbox?.let { unread.remove(it) }
        unread += delivery
        DemoSettings.rerunInbox = delivery
    }

    /**
     * [delivery]'s file is read (or failed to read). Until then the scan copy this app shared
     * stays in the cache: the 3D Scan landing that opens to import it would otherwise clear the
     * share cache ([discardSharedScan]) under the import's feet.
     */
    fun read(delivery: RerunInboxDelivery) {
        unread.remove(delivery)
    }

    /** Whether a file handed over is still waiting to be read. */
    val reading: Boolean get() = unread.isNotEmpty()

    private val unread: MutableSet<RerunInboxDelivery> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * `true` once, for the 3D Scan of the activity [delivery] was posted to: it clears the inbox
     * and imports. Any other caller, or a second call, gets `false`. Main thread.
     */
    fun claim(delivery: RerunInboxDelivery, activity: Activity?): Boolean {
        if (activity == null || delivery.owner != System.identityHashCode(activity)) return false
        if (DemoSettings.rerunInbox !== delivery) return false
        DemoSettings.rerunInbox = null
        return true
    }
}

/** A scan file waiting for the 3D Scan of one activity ([RerunInbox.post]). */
class RerunInboxDelivery(val uri: Uri, val owner: Int)

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
/** One directory per writer under [RERUN_SHARE_DIR]: the scan copy, the exports, the plan. */
internal const val RERUN_SHARE_SCAN = "scan"
internal const val RERUN_SHARE_EXPORT = "export"
internal const val RERUN_SHARE_PLAN = "plan"

/** A scan that could not be saved on the phone, written out for the share sheet (#4815). */
internal const val RERUN_SHARE_UNSAVED = "unsaved"

private const val MILLIS = 1000L
private const val HEAD_BYTES = 64
private const val BUFFER_BYTES = 64 * 1024

/** Nothing a phone records comes near this; a file that claims more is not one of ours. */
private const val MAX_FILE_BYTES = 512L * 1024 * 1024
