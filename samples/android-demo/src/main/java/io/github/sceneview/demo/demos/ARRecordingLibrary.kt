package io.github.sceneview.demo.demos

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.google.ar.core.Session
import io.github.sceneview.demo.demos.internal.PlacementTrack
import io.github.sceneview.demo.demos.internal.RecordingContents
import io.github.sceneview.demo.demos.internal.degreesOfSurfaceRotation
import io.github.sceneview.demo.demos.internal.recordingContentsOf
import io.github.sceneview.demo.demos.internal.recordingRotationDegrees
import io.github.sceneview.demo.demos.internal.surfaceRotationOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/*
 * The Android side of the AR Recording demo's library (#3831): what a recording file holds,
 * read with the platform media APIs rather than ARCore — a thumbnail, its length, its tracks
 * — plus sharing, exporting and deleting. The pure half (titles, contents line, sizes) lives
 * in `internal/ArRecordingModel.kt` and is unit-tested on the JVM.
 */

private const val TAG = "ARRecordingLibrary"

/** Width and height the gallery thumbnails are decoded at — 4:3, twice the row's size. */
private const val THUMBNAIL_WIDTH = 320
private const val THUMBNAIL_HEIGHT = 240

/** Upper bound on placement packets read to count them — a long take rewrites them each second. */
private const val MAX_PLACEMENT_SAMPLES = 20_000

/** What the gallery shows for one recording. */
internal data class RecordingDetails(
    val durationMillis: Long,
    val sizeBytes: Long,
    /** A frame from about one second in, `null` when the video could not be decoded. */
    val thumbnail: ImageBitmap?,
    val contents: RecordingContents,
    /** Distinct placements found on the placement track; `null` when it could not be read. */
    val placementCount: Int?,
)

/**
 * Reads [RecordingDetails] off the main thread and keeps them per file version, so scrolling
 * the gallery or coming back to it never decodes a frame twice.
 */
internal class RecordingLibrary {
    private val cache = ConcurrentHashMap<String, RecordingDetails>()

    fun cached(file: File): RecordingDetails? = cache[keyOf(file)]

    suspend fun details(file: File): RecordingDetails {
        val key = keyOf(file)
        cache[key]?.let { return it }
        val details = withContext(Dispatchers.IO) { read(file) }
        cache[key] = details
        return details
    }

    fun forget(file: File) {
        val prefix = file.absolutePath + "|"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    private fun keyOf(file: File) = "${file.absolutePath}|${file.lastModified()}|${file.length()}"

    private fun read(file: File): RecordingDetails {
        var durationMillis = 0L
        var thumbnail: ImageBitmap? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            durationMillis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // About a second in: the very first frame of a take is often the phone still
            // coming up from the table.
            val atMicros = minOf(1_000L, durationMillis / 2).coerceAtLeast(0L) * 1_000L
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    atMicros,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMBNAIL_WIDTH,
                    THUMBNAIL_HEIGHT,
                )
            } else {
                retriever.getFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            thumbnail = frame?.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "No thumbnail for ${file.name}: ${e.message}")
        } finally {
            runCatching { retriever.release() }
        }

        val mimeTypes = ArrayList<String>()
        var placementCount: Int? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var placementTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                mimeTypes += mime
                if (mime == PlacementTrack.MIME_TYPE) placementTrack = i
            }
            Log.d(TAG, "${file.name}: tracks $mimeTypes")
            if (placementTrack >= 0) placementCount = countPlacements(extractor, placementTrack)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the tracks of ${file.name}: ${e.message}")
        } finally {
            runCatching { extractor.release() }
        }

        return RecordingDetails(
            durationMillis = durationMillis,
            sizeBytes = file.length(),
            thumbnail = thumbnail,
            contents = recordingContentsOf(mimeTypes),
            placementCount = placementCount,
        )
    }

    /** Distinct placement indices on the track, or `null` when no packet decodes. */
    private fun countPlacements(extractor: MediaExtractor, track: Int): Int? {
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocate(PlacementTrack.PACKET_BYTES * 64)
        val indices = HashSet<Int>()
        var decoded = 0
        var samples = 0
        var more = true
        while (more && samples < MAX_PLACEMENT_SAMPLES) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size >= 0) {
                buffer.position(0)
                buffer.limit(size)
                PlacementTrack.decode(buffer)?.let {
                    indices += it.index
                    decoded++
                }
                samples++
            }
            more = size >= 0 && extractor.advance()
        }
        return if (decoded == 0) null else indices.size
    }
}

/** Newest first — the order of the gallery. */
internal fun listRecordings(dir: File): List<File> =
    dir.listFiles()
        ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

/**
 * Copies the recordings bundled in the debug build's `assets/ar-recordings/` into [dir] —
 * a known-good replay on a device that has never recorded, and on the emulator, which
 * cannot (#934). Skipped when a file of the same length is already there; written to a
 * `.tmp` first so a process death mid-copy cannot leave a truncated MP4 behind.
 */
internal suspend fun extractBundledRecordings(context: Context, dir: File) = withContext(Dispatchers.IO) {
    try {
        val assets = context.assets
        val bundled = assets.list("ar-recordings")
            ?.filter { it.endsWith(".mp4", ignoreCase = true) }
            .orEmpty()
        for (name in bundled) {
            val target = File(dir, name)
            val expectedBytes = assets.openFd("ar-recordings/$name").use { it.length }
            if (target.exists() && target.length() == expectedBytes) continue
            val tmp = File(dir, "$name.tmp")
            try {
                assets.open("ar-recordings/$name").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(target)) tmp.delete()
            } catch (e: CancellationException) {
                // Keep structured concurrency intact (#980): drop the partial file, rethrow.
                tmp.delete()
                throw e
            } catch (_: Exception) {
                tmp.delete()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A missing bundle only means an empty gallery until the first recording.
    }
}

/**
 * Hands the MP4 to the system share sheet through the app's FileProvider — the path
 * `ARRecordPlaybackShareTest` pins. Returns `false` when the provider is misconfigured.
 */
internal fun shareRecording(context: Context, file: File): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "FileProvider refused ${file.name}: ${e.message}")
        return false
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AR recording — SceneView")
        putExtra(
            Intent.EXTRA_TEXT,
            "An AR recording made with SceneView. It plays in any video app, and replays " +
                "in AR with ARSceneView(playbackDataset = file).",
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share AR recording"))
    return true
}

/**
 * The rotation to hand `ARRecorder.start`, as a `Surface.ROTATION_*` constant: the camera
 * sensor's orientation relative to the display, which is what ARCore's
 * `RecordingConfig.setRecordingRotation` expects so the MP4 — and its thumbnail — plays
 * upright (#3831). The display rotation alone, which this demo used to pass, is 0 in
 * portrait and left every portrait take lying on its side.
 */
internal fun recordingSurfaceRotation(context: Context, session: Session?): Int {
    val display = displayRotation(context)
    val sensor = session?.let { cameraSensorOrientation(context, it) } ?: return display
    return surfaceRotationOf(recordingRotationDegrees(sensor, degreesOfSurfaceRotation(display)))
}

private fun cameraSensorOrientation(context: Context, session: Session): Int? = try {
    val cameraId = session.cameraConfig.cameraId
    context.getSystemService(CameraManager::class.java)
        ?.getCameraCharacteristics(cameraId)
        ?.get(CameraCharacteristics.SENSOR_ORIENTATION)
} catch (e: Exception) {
    Log.w(TAG, "No sensor orientation: ${e.message}")
    null
}

private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.display?.rotation }.getOrNull() ?: 0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
