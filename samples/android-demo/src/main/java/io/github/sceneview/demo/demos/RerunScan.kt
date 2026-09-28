package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.KeyframeGate
import io.github.sceneview.demo.demos.internal.MediaSpan
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.ScanArchive
import io.github.sceneview.demo.demos.internal.ScanIntrinsics
import io.github.sceneview.demo.demos.internal.ScanProjection
import io.github.sceneview.demo.demos.internal.YuvFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * The Rerun demo's Record mode, Android side: ARCore's camera image handed to the pure scan
 * logic in internal/RoomScan.kt. Everything stays on the phone.
 */

/**
 * One room scan in progress. [ArDebugRecorder] fills [trace] as it always does — path, points,
 * planes, anchors — and asks this for the rest: the colour of each new feature point, read from
 * the camera image of the same frame, and a photo each time the camera has moved enough
 * ([KeyframeGate]), encoded off the main thread.
 *
 * [live] is the replay media as it grows, which the Record screen's 3D card draws; [finish]
 * builds the scan into the file the sessions list keeps and the replay opens.
 *
 * Main thread, like the recorder, except the photo encoding it launches in [scope].
 */
internal class ScanCapture private constructor(
    private val intrinsics: ScanIntrinsics,
    private val lens: ReplayLens,
    private val scope: CoroutineScope,
) {
    /** The scan's trace; its journal is what [finish] saves. */
    val trace = ArDebugTrace().apply {
        keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M
        journal = ArrayList()
    }

    private val gate = KeyframeGate()
    private val photoSize = ScanProjection.photoSize(lens, PHOTO_LONG_SIDE)
    private val jpegs = ConcurrentHashMap<String, ByteArray>()
    private val thumbnails = ConcurrentHashMap<String, Bitmap>()

    /** Where each photo was taken from, display-oriented: what its pixels are upright to. */
    private val poses = HashMap<String, DebugPose>()
    private val jobs = ArrayList<Job>()
    private val inFlight = AtomicInteger(0)

    /** The scan as it grows. Its thumbnails fill in as the photos are encoded. */
    val live = RerunReplayMedia(trace, manifest(emptyMap()), emptyMap(), thumbnails, ByteArray(0), growing = true)

    /** No more photos: the path, the points and the planes keep recording. */
    val isPhotoLimitReached: Boolean get() = gate.isFull

    /** Whether the camera at [display] should take a photo now. */
    fun wantsPhoto(display: DebugPose): Boolean = inFlight.get() < MAX_IN_FLIGHT && gate.wants(display)

    /** The camera image of [frame], or `null` when ARCore has none to give this frame. */
    fun acquire(frame: Frame): ScanImage? = runCatching {
        val image = frame.acquireCameraImage()
        runCatching { ScanImage(image, image.toYuvFrame(), frame.camera.pose.toScanPose()) }
            .onFailure { image.close() }
            .getOrNull()
    }.getOrNull()

    /** One colour per point of the flat `[x,y,z, …]` [points], `0` where [image] does not show it. */
    fun colors(image: ScanImage, points: FloatArray): IntArray =
        ScanProjection.colors(image.yuv, intrinsics, image.sensor, points)

    /**
     * Takes the photo of [image] as the camera at [display] saw it: the trace learns its path at
     * once, the pixels are copied out of ARCore's buffer here, and turned upright, encoded and
     * thumbnailed off the main thread.
     */
    fun takePhoto(nanos: Long, image: ScanImage, display: DebugPose) {
        val path = ScanArchive.photoPath(gate.count)
        val pixels = image.copy()
        val sensor = image.sensor
        gate.accept(display)
        poses[path] = display
        trace.addImage(nanos, path)
        inFlight.incrementAndGet()
        jobs += scope.launch(Dispatchers.Default) {
            try {
                val (width, height) = photoSize
                val argb = ScanProjection.uprightImage(pixels, intrinsics, sensor, display, lens, width, height)
                val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
                val jpeg = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                jpegs[path] = jpeg.toByteArray()
                thumbnails[path] = Bitmap.createScaledBitmap(bitmap, width / 2, height / 2, true)
                bitmap.recycle()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * Stops the scan, waits for the last photos, and builds it into the capture the sessions list
     * keeps — its planes painted from its photos. `null` for a scan that caught nothing. Call it
     * on the main thread, where the recorder writes: the journal is taken there, then let go.
     */
    suspend fun finish(): RerunCapturePack? {
        val events = trace.journal?.toList().orEmpty()
        trace.journal = null
        jobs.toList().joinAll()
        if (trace.isEmpty) return null
        val photos = events.filterIsInstance<ArDebugEvent.Image>().mapNotNull { image ->
            val jpeg = jpegs[image.path] ?: return@mapNotNull null
            val pose = poses[image.path] ?: return@mapNotNull null
            ScanPhoto(image.path, jpeg, pose)
        }
        return RerunCaptureBuilder.build(events, lens, photos)
    }

    private fun manifest(spans: Map<String, MediaSpan>) = ReplayManifest(
        lens = lens,
        frameRate = if (trace.duration > 0f) trace.imageCount / trace.duration else 0f,
        frameCount = trace.imageCount,
        floorY = null,
        textures = emptyList(),
        media = spans,
    )

    companion object {
        /**
         * A scan for the camera of [frame], or `null` while ARCore does not know its lens yet.
         * The photos keep the frame's orientation, portrait or landscape, for the whole scan.
         */
        fun start(frame: Frame, scope: CoroutineScope): ScanCapture? {
            val camera = frame.camera
            val intrinsics = camera.scanIntrinsics() ?: return null
            val lens = ScanProjection.displayLens(
                intrinsics,
                camera.pose.toScanPose(),
                camera.displayOrientedPose.toScanPose(),
            )
            return ScanCapture(intrinsics, lens, scope)
        }

        /** 240×320, the bundled replay's own frame size: ~15 KB of JPEG each. */
        private const val PHOTO_LONG_SIDE = 320
        private const val JPEG_QUALITY = 85

        /** Photos being encoded at once; past it a keyframe waits for the next frame. */
        private const val MAX_IN_FLIGHT = 3
    }
}

/**
 * ARCore's camera image for one frame, read in place, with the pose of the camera that took it.
 * Close it before the frame ends: ARCore's pool holds two or three.
 */
internal class ScanImage(private val image: Image, val yuv: YuvFrame, val sensor: DebugPose) : AutoCloseable {
    /** The pixels, copied out of ARCore's buffers so they outlive [close]. */
    fun copy(): YuvFrame {
        val planes = image.planes
        return YuvFrame(
            width = image.width,
            height = image.height,
            y = planes[0].buffer.copyOut(),
            yRowStride = planes[0].rowStride,
            u = planes[1].buffer.copyOut(),
            v = planes[2].buffer.copyOut(),
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
        )
    }

    override fun close() = image.close()
}

private fun Image.toYuvFrame(): YuvFrame {
    val planes = planes
    return YuvFrame(
        width = width,
        height = height,
        y = planes[0].buffer,
        yRowStride = planes[0].rowStride,
        u = planes[1].buffer,
        v = planes[2].buffer,
        uvRowStride = planes[1].rowStride,
        uvPixelStride = planes[1].pixelStride,
    )
}

private fun ByteBuffer.copyOut(): ByteBuffer {
    val source = duplicate().apply { rewind() }
    val bytes = ByteArray(source.remaining())
    source.get(bytes)
    return ByteBuffer.wrap(bytes)
}

private fun Camera.scanIntrinsics(): ScanIntrinsics? {
    val camera = imageIntrinsics
    val focal = camera.focalLength
    val centre = camera.principalPoint
    val size = camera.imageDimensions
    return ScanIntrinsics(focal[0], focal[1], centre[0], centre[1], size[0], size[1]).takeIf { it.isUsable }
}

private fun Pose.toScanPose() = DebugPose(tx(), ty(), tz(), qx(), qy(), qz(), qw())
