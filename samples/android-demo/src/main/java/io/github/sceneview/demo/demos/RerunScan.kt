package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.media.Image
import android.os.Build
import android.util.Log
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthAlignment
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.DepthFreshness
import io.github.sceneview.demo.demos.internal.IntervalGate
import io.github.sceneview.demo.demos.internal.KeyframeGate
import io.github.sceneview.demo.demos.internal.MediaSpan
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.ScanArchive
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.ScanIntrinsics
import io.github.sceneview.demo.demos.internal.ScanProjection
import io.github.sceneview.demo.demos.internal.YuvFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * With [rawDepth] (ARCore's `RAW_DEPTH_ONLY` on this phone: Rerun v2 tier `depth`) each new
 * raw-depth image is also back-projected and fused into a 2 cm surfel map ([DenseFusion]), which
 * [finish] saves as the scan's dense cloud. Without it the scan is the sparse v1 one.
 *
 * Main thread, like the recorder, except the photo encoding and the depth fusion it launches in
 * [scope].
 */
internal class ScanCapture private constructor(
    /** The CPU camera image's lens: the photos' and the sparse points' colours. */
    private val intrinsics: ScanIntrinsics,
    /** The GPU camera texture's lens, which ARCore aligns the raw depth with. */
    private val textureIntrinsics: ScanIntrinsics?,
    private val lens: ReplayLens,
    private val scope: CoroutineScope,
    /** Whether the session runs ARCore's raw depth: the scan's tier. */
    val rawDepth: Boolean,
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

    // The dense map: one fusion at a time on Dispatchers.Default; a depth image that arrives while
    // one runs is skipped, never queued, so a slow phone thins the depth instead of lagging.
    private val fusion = if (rawDepth) DenseFusion() else null
    private val fusing = AtomicBoolean(false)
    private var fuseJob: Job? = null
    // The final model's TSDF, fed the same depth copies on its own worker (RerunModelUi.kt).
    private val model = if (rawDepth) RerunLiveModel() else null
    private var lastDepthNanos: Long? = null
    private var loggedSizes = false
    private val logGate = IntervalGate(LOG_INTERVAL_NS)
    private val verdictCounts = IntArray(DepthFreshness.Verdict.entries.size)
    private val verdictLagMs = LongArray(DepthFreshness.Verdict.entries.size)
    private var unavailableCount = 0
    private var unavailableReason: String? = null
    // At most ten depth keyframes a second: ARCore's own raw-depth pace on a Pixel, and a bound
    // on the camera-image reads the fusion adds to the main thread.
    private val depthGate = IntervalGate(DEPTH_INTERVAL_NS)
    private val denseTotal = AtomicInteger(0)
    private val denseAdded = AtomicInteger(0)
    private val denseKept = AtomicInteger(0)
    private var recordedTotal = 0

    /** Surfels in the dense map so far: the HUD's "N surfaces" count. `0` without raw depth. */
    val denseCount: Int get() = denseTotal.get()

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
     * ARCore's raw depth of [frame] when it holds a new estimate, at most
     * [DepthFreshness.MAX_AGE_NANOS] behind the frame and not fused already ([DepthFreshness]),
     * and no fusion is running; `null` otherwise, or without [rawDepth]. Close it this frame.
     */
    fun acquireDepth(frame: Frame): ScanDepth? {
        if (fusion == null || fusing.get() || !depthGate.isDue(frame.timestamp)) return null
        val depth = runCatching { frame.acquireRawDepthImage16Bits() }
            .onFailure { logUnavailable(frame.timestamp, it) }
            .getOrNull() ?: return null
        // Before the gate, so a device log says what the scan sees even when it fuses nothing.
        logSizesOnce(depth, frame.timestamp)
        val verdict = DepthFreshness.judge(frame.timestamp, depth.timestamp, lastDepthNanos)
        logVerdict(verdict, frame.timestamp, depth.timestamp)
        if (verdict != DepthFreshness.Verdict.FRESH) {
            depth.close()
            return null
        }
        lastDepthNanos = depth.timestamp
        depthGate.mark(frame.timestamp)
        val confidence = runCatching { frame.acquireRawDepthConfidenceImage() }.getOrNull()
        return ScanDepth(depth, confidence, frame.camera.pose.toScanPose())
    }

    /** Which lens the depth is read with, once a scan: the proof on a device's log. */
    private fun logSizesOnce(depth: Image, frameNanos: Long) {
        if (loggedSizes) return
        loggedSizes = true
        val chosen = DepthAlignment.depthLens(textureIntrinsics, intrinsics, depth.width, depth.height)
        Log.i(
            LOG_TAG,
            "depth ${depth.width}x${depth.height}, cpu image ${intrinsics.width}x${intrinsics.height}, " +
                "texture ${textureIntrinsics?.width}x${textureIntrinsics?.height}, depth fy ${chosen?.fy}, " +
                "depth lag ${(frameNanos - depth.timestamp) / NANOS_PER_MS} ms",
        )
    }

    /**
     * Counts each depth verdict and, at most once a second of frame time, logs the counts since
     * the last line with the latest lag (frame minus depth timestamp) of each: why the dense map
     * grows, or does not, on a device's log.
     */
    private fun logVerdict(verdict: DepthFreshness.Verdict, frameNanos: Long, depthNanos: Long) {
        verdictCounts[verdict.ordinal]++
        verdictLagMs[verdict.ordinal] = (frameNanos - depthNanos) / NANOS_PER_MS
        flushVerdicts(frameNanos)
    }

    /** Counts a frame whose raw depth ARCore could not give, [error] saying why. */
    private fun logUnavailable(frameNanos: Long, error: Throwable) {
        unavailableCount++
        unavailableReason = error.javaClass.simpleName
        flushVerdicts(frameNanos)
    }

    private fun flushVerdicts(frameNanos: Long) {
        if (!logGate.isDue(frameNanos)) return
        logGate.mark(frameNanos)
        val counts = DepthFreshness.Verdict.entries.filter { verdictCounts[it.ordinal] > 0 }.map { v ->
            "${v.name.lowercase()} ${verdictCounts[v.ordinal]} (lag ${verdictLagMs[v.ordinal]} ms)"
        } + listOfNotNull(
            unavailableReason?.takeIf { unavailableCount > 0 }?.let { "unavailable $unavailableCount ($it)" },
        )
        Log.i(LOG_TAG, "depth verdicts: ${counts.joinToString()}; surfels ${denseTotal.get()}")
        verdictCounts.fill(0)
        unavailableCount = 0
    }

    /**
     * Fuses [depth] into the dense map, coloured from [image] (the same frame's camera image):
     * the depth, its confidence and one colour per kept pixel are copied here, on the main thread,
     * then back-projected and merged on [Dispatchers.Default].
     */
    fun fuseDepth(depth: ScanDepth, image: ScanImage) {
        val fusion = fusion ?: return
        val frame = depth.copy(textureIntrinsics, intrinsics, image.yuv) ?: return
        model?.offer(frame, scope)
        fusing.set(true)
        fuseJob = scope.launch(Dispatchers.Default) {
            try {
                val stats = fusion.add(DepthBackProjection.project(frame))
                denseAdded.addAndGet(stats.added)
                denseKept.addAndGet(stats.kept)
                denseTotal.set(stats.total)
            } finally {
                fusing.set(false)
            }
        }
    }

    /**
     * Writes into [trace] how far the dense map has grown since the last call, stamped [nanos] —
     * the frame that learns of it — so a replay reveals the cloud as it was found. Main thread.
     */
    fun recordDepthStats(nanos: Long) {
        val total = denseTotal.get()
        if (total == recordedTotal) return
        recordedTotal = total
        trace.addDepthStats(nanos, denseAdded.getAndSet(0), denseKept.getAndSet(0), total)
    }

    /**
     * Stops the scan, waits for the last photos, and builds it into the capture the sessions list
     * keeps — its planes painted from its photos. `null` for a scan that caught nothing. Call it
     * on the main thread, where the recorder writes: the journal is taken there, then let go.
     */
    suspend fun finish(): RerunCapturePack? {
        // The fusion under way finishes first, and its last growth goes on the timeline.
        fuseJob?.join()
        model?.join()
        trace.journal?.lastOrNull()?.let { recordDepthStats(it.nanos) }
        val events = trace.journal?.toList().orEmpty()
        trace.journal = null
        jobs.toList().joinAll()
        if (trace.isEmpty) return null
        val photos = events.filterIsInstance<ArDebugEvent.Image>().mapNotNull { image ->
            val jpeg = jpegs[image.path] ?: return@mapNotNull null
            val pose = poses[image.path] ?: return@mapNotNull null
            ScanPhoto(image.path, jpeg, pose)
        }
        val started = System.nanoTime()
        val dense = fusion?.takeIf { it.count > 0 }
            ?.let { f -> withContext(Dispatchers.Default) { f.cloud(minViews = DenseFusion.MIN_VIEWS) } }
            ?.takeIf { it.count > 0 }
        val denseMs = (System.nanoTime() - started) / 1_000_000
        // The tier the scan really reached: raw depth on but no surfel is a sparse scan, said so.
        val device = ScanDevice(
            platform = "android",
            model = Build.MODEL.orEmpty(),
            tier = if (dense != null) ScanDevice.TIER_DEPTH else ScanDevice.TIER_SPARSE,
            depthSource = if (dense != null) ScanDevice.SOURCE_RAW_DEPTH else ScanDevice.SOURCE_FEATURE_POINTS,
        )
        return RerunCaptureBuilder.build(events, lens, photos, device, dense, DenseFusion.VOXEL_M, denseMs)
            .also { model?.keepFor(it) }
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
        fun start(frame: Frame, scope: CoroutineScope, rawDepth: Boolean = false): ScanCapture? {
            val camera = frame.camera
            val intrinsics = camera.imageIntrinsics.toScan() ?: return null
            val lens = ScanProjection.displayLens(
                intrinsics,
                camera.pose.toScanPose(),
                camera.displayOrientedPose.toScanPose(),
            )
            return ScanCapture(intrinsics, camera.textureIntrinsics.toScan(), lens, scope, rawDepth)
        }

        /** 100 ms between two fused depth keyframes. */
        const val DEPTH_INTERVAL_NS = 100_000_000L

        private const val LOG_TAG = "RerunScan"

        /** One depth-verdict line a second of frame time at most. */
        private const val LOG_INTERVAL_NS = 1_000_000_000L
        private const val NANOS_PER_MS = 1_000_000L

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

/**
 * ARCore's raw depth for one frame — DEPTH16 millimetres and, when ARCore gives one, its Y8
 * confidence — with the pose of the camera that took it. Close it before the frame ends.
 */
internal class ScanDepth(private val depth: Image, private val confidence: Image?, val sensor: DebugPose) :
    AutoCloseable {
    /**
     * The depth copied out of ARCore's buffers, its lens the one of the image it is aligned with
     * ([DepthAlignment.depthLens]: the GPU [texture]'s, scaled to it), each pixel coloured from
     * [yuv] (the CPU camera image, lens [image]) along the same ray. `null` for a depth image that
     * cannot be read, that no lens frames, or that comes without its confidence image.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements") // one skip per unusable pixel
    fun copy(texture: ScanIntrinsics?, image: ScanIntrinsics, yuv: YuvFrame): DepthFrame? {
        val w = depth.width
        val h = depth.height
        if (w <= 1 || h <= 1) return null
        val lens = DepthAlignment.depthLens(texture, image, w, h) ?: return null
        // The CPU image's lens scaled to the pixels actually read, then depth pixel -> image pixel.
        val read = ScanIntrinsics(
            image.fx * yuv.width / image.width, image.fy * yuv.height / image.height,
            image.cx * yuv.width / image.width, image.cy * yuv.height / image.height,
            yuv.width, yuv.height,
        )
        val map = DepthAlignment.toImage(lens, read)
        val plane = depth.planes.firstOrNull() ?: return null
        val depthRow = plane.rowStride / 2
        val shorts = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mm = ShortArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val at = y * depthRow + x
                mm[y * w + x] = if (at < shorts.limit()) shorts.get(at) else 0
            }
        }
        val conf = confidence?.takeIf { it.width == w && it.height == h }?.planes?.firstOrNull()?.let { c ->
            val bytes = c.buffer.duplicate()
            val row = c.rowStride
            val pixel = c.pixelStride.coerceAtLeast(1)
            ByteArray(w * h) { i ->
                val at = (i / w) * row + (i % w) * pixel
                if (at < bytes.limit()) bytes.get(at) else 0
            }
        } ?: return null // unfiltered raw depth is too noisy to keep: skip the frame
        // Colour only the pixels the back-projection can keep: the others cost a YUV read for nothing.
        val colors = IntArray(w * h)
        for (i in 0 until w * h) {
            val d = mm[i].toInt() and 0xFFFF
            if (d == 0) continue
            if ((conf[i].toInt() and 0xFF) < DepthBackProjection.MIN_CONFIDENCE) continue
            val u = map[0] * (i % w) + map[1]
            val v = map[2] * (i / w) + map[3]
            colors[i] = yuv.argb((u + 0.5f).toInt(), (v + 0.5f).toInt())
        }
        return DepthFrame(
            width = w,
            height = h,
            depthMm = mm,
            confidence = conf,
            colors = colors,
            fx = lens.fx,
            fy = lens.fy,
            cx = lens.cx,
            cy = lens.cy,
            pose = sensor,
        )
    }

    override fun close() {
        depth.close()
        confidence?.close()
    }
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

private fun CameraIntrinsics.toScan(): ScanIntrinsics? {
    val focal = focalLength
    val centre = principalPoint
    val size = imageDimensions
    return ScanIntrinsics(focal[0], focal[1], centre[0], centre[1], size[0], size[1]).takeIf { it.isUsable }
}

private fun Pose.toScanPose() = DebugPose(tx(), ty(), tz(), qx(), qy(), qz(), qw())
