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
import io.github.sceneview.demo.demos.internal.PlaneBake
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.ReplayPlaneTexture
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunFileKind
import io.github.sceneview.demo.demos.internal.RerunImportFailure
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.demos.internal.ScanArchive
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
     */
    suspend fun build(
        events: List<ArDebugEvent>,
        lens: ReplayLens,
        photos: List<ScanPhoto>,
    ): RerunCapturePack = withContext(Dispatchers.Default) {
        val trace = ArDebugTrace.of(events).apply { keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M }
        val whole = trace.frameAt(trace.duration)
        val decoded = HashMap<Int, BakePhoto?>()
        fun photo(index: Int): BakePhoto? = decoded.getOrPut(index) { decodePhoto(photos[index]) }

        val viewpoint = centroid(whole.trail)
        val baked = trace.lastPlanes().mapNotNull { plane -> bakePlane(plane, viewpoint, photos, lens, ::photo) }
        val (archive, spans) = ScanArchive.pack(
            photos.map { it.path to it.jpeg } + baked.map { (texture, jpeg) -> texture.path to jpeg },
        )
        val manifest = ReplayManifest(
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

/** "Room · Sep 28, 2:32 PM", in the phone's locale. */
internal fun recordingTitle(nowMillis: Long, locale: Locale = Locale.getDefault()): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd jmm")
    return RerunStoredSession.recordingTitle(SimpleDateFormat(pattern, locale).format(Date(nowMillis)))
}

/** "Sep 28, 2:32 PM · Recorded". */
internal fun sessionOrigin(session: RerunStoredSession, locale: Locale = Locale.getDefault()): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd jmm")
    val date = SimpleDateFormat(pattern, locale).format(Date(session.createdAt * MILLIS))
    return "$date · ${session.source.label}"
}

/**
 * Session [session] as a scan file in a fresh cache directory, named after its title, handed to the
 * system's share sheet. `false` when its capture is gone.
 */
internal suspend fun shareScanFile(context: Context, store: RerunSessionStore, session: RerunStoredSession): Boolean {
    val file = withContext(Dispatchers.IO) {
        val capture = store.capture(session.id) ?: return@withContext null
        val shareRoot = File(context.cacheDir, SHARE_DIR)
        // Only the latest shared file is kept: the share sheet has read it by the next share.
        shareRoot.deleteRecursively()
        val dir = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        File(dir, RerunScanFile.fileName(session.title)).apply { writeBytes(RerunScanFile.write(capture)) }
    } ?: return false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = SCAN_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, session.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return true
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
            store.import(name, bytes, readRrd = { throw RerunImportFailure.RrdNotYet(name) })
        }
    }

/**
 * A scan file handed over by another app ("Open with" / Share), recognised by its name or, for a
 * nameless one, by its first bytes — `null` for any other file, which goes on to the model viewer.
 * The file is only peeked at here; [importSession] reads it.
 */
internal object RerunInbox {
    const val DEMO_ID = "ar-rerun"

    fun accepts(context: Context, uri: Uri): Boolean {
        val name = OpenedModelIntent.displayName(context, uri)
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(HEAD_BYTES) }
        }.getOrNull() ?: return false
        return RerunFileKind.of(name, head) == RerunFileKind.Scan
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
private const val SHARE_DIR = "rerun-share"
private const val MILLIS = 1000L
private const val HEAD_BYTES = 64
private const val BUFFER_BYTES = 64 * 1024

/** Nothing a phone records comes near this; a file that claims more is not one of ours. */
private const val MAX_FILE_BYTES = 512L * 1024 * 1024
