package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import io.github.sceneview.demo.demos.internal.RerunSessionFile
import io.github.sceneview.demo.demos.internal.RerunSessionFormat
import io.github.sceneview.demo.demos.internal.RerunSessionMeta
import io.github.sceneview.demo.demos.internal.ScanArchive
import io.github.sceneview.demo.demos.internal.ScanCover
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/*
 * The Rerun demo's saved scans, on Android: a finished scan built into one file — the log, the
 * manifest, the photos and the planes painted from them, the same parts the bundled replay ships
 * — and kept in the app's private storage, where it stays until the user deletes it.
 */

/** One photo of a scan: its [path] in the log, its JPEG, and the display-oriented camera [pose]. */
internal class ScanPhoto(val path: String, val jpeg: ByteArray, val pose: DebugPose)

/** Builds a finished scan into the file the sessions list keeps and the replay opens. */
internal object RerunSessionBuilder {
    /**
     * The scan of [events] (a trace's journal: every change it kept, in order), photographed by
     * [photos] through [lens], made at [createdAt] (epoch milliseconds). Off the main thread.
     */
    suspend fun build(
        events: List<ArDebugEvent>,
        lens: ReplayLens,
        photos: List<ScanPhoto>,
        createdAt: Long,
    ): RerunSessionFile = withContext(Dispatchers.Default) {
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
        RerunSessionFile(
            meta = RerunSessionMeta(
                createdAtMillis = createdAt,
                durationSeconds = trace.duration,
                points = whole.mapPointCount,
                surfaces = whole.planes.size,
                photos = trace.imageCount,
            ),
            cover = ScanCover.render(whole.mapPoints, whole.mapPointColors)?.let(::png),
            manifest = manifest.toJson(),
            log = ArDebugLogWriter.write(events),
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

    private fun png(pixels: IntArray): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, ScanCover.SIZE, ScanCover.SIZE, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
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

/** A saved scan as the list shows it: its [meta] and [cover], without the rest read. */
internal class StoredSession(val id: String, val meta: RerunSessionMeta, val cover: Bitmap?)

/**
 * The scans saved on this phone, one file each in the app's private storage: nothing leaves the
 * phone unless the user shares it. Every call is IO, off the main thread.
 */
internal class RerunSessionStore(context: Context) {
    private val dir = File(context.filesDir, DIR)

    /** Every saved scan, newest first. A file that does not read is left out, not deleted. */
    suspend fun list(): List<StoredSession> = withContext(Dispatchers.IO) {
        val files = dir.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty()
        files.mapNotNull { file ->
            val (meta, cover) = runCatching {
                file.inputStream().buffered().use(RerunSessionFormat::readHeader)
            }.getOrNull() ?: return@mapNotNull null
            val bitmap = cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            StoredSession(file.name.removeSuffix(SUFFIX), meta, bitmap)
        }.sortedByDescending { it.meta.createdAtMillis }
    }

    /** Saves [session]; its id, or `null` when the phone could not write it. */
    suspend fun save(session: RerunSessionFile): String? = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val id = "scan-${session.meta.createdAtMillis}"
            val partial = File(dir, "$id.partial")
            partial.outputStream().buffered().use { RerunSessionFormat.write(session, it) }
            // Written whole, then renamed: the list never sees half a file.
            check(partial.renameTo(fileOf(id))) { "rename failed" }
            id
        }.getOrNull()
    }

    /** Saved scan [id], whole; `null` when it is gone or does not read. */
    suspend fun read(id: String): RerunSessionFile? = withContext(Dispatchers.IO) {
        runCatching { fileOf(id).inputStream().buffered().use(RerunSessionFormat::read) }.getOrNull()
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) { fileOf(id).delete() }

    /** Where scan [id] is kept. */
    fun fileOf(id: String): File = File(dir, id + SUFFIX)

    private companion object {
        const val DIR = "rerun/sessions"
        const val SUFFIX = "." + RerunSessionFormat.EXTENSION
    }
}
