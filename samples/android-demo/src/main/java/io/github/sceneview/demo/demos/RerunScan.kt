package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import io.github.sceneview.demo.demos.internal.AdaptiveConfidence
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthAlignment
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.DepthFreshness
import io.github.sceneview.demo.demos.internal.DepthIntake
import io.github.sceneview.demo.demos.internal.DepthWindow
import io.github.sceneview.demo.demos.internal.DepthIntake.Outcome
import io.github.sceneview.demo.demos.internal.IntervalGate
import io.github.sceneview.demo.demos.internal.KeyframeGate
import io.github.sceneview.demo.demos.internal.MediaSpan
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.ScanArchive
import io.github.sceneview.demo.demos.internal.ScanClock
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.ScanIntrinsics
import io.github.sceneview.demo.demos.internal.ScanDetail
import io.github.sceneview.demo.demos.internal.ScanCutoff
import io.github.sceneview.demo.demos.internal.ScanPhotoMotion
import io.github.sceneview.demo.demos.internal.ScanPhotoPolicy
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanProjection
import io.github.sceneview.demo.demos.internal.ScanSharpPicker
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
    /** What is scanned decides the photo spacing, the depth kept and the model's voxel (#4647). */
    val preset: ScanPreset = ScanPreset.ROOM,
) {
    /** The scan's trace; its journal is what [finish] saves. */
    val trace = ArDebugTrace().apply {
        keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M
        journal = ArrayList()
        // #4683: an object keeps arm's length in its sparse points and planes too, depth or not.
        cutoff = ScanCutoff.of(preset)
        // #4717: without depth, the feature points are the capture; a plain surface relaxes them.
        if (!rawDepth) pointConfidence = AdaptiveConfidence.sparse()
    }

    private val gate = KeyframeGate(preset.photoStepM, preset.photoTurnDegrees, preset.maxPhotos)
    private val photoMotion = ScanPhotoMotion()
    private val sharpPicker = ScanSharpPicker()

    /** Whether the camera was calm at the latest tracked frame: what a sharp photo waits for. */
    private var calm = false
    private val jpegs = ConcurrentHashMap<String, ByteArray>()
    private val thumbnails = ConcurrentHashMap<String, Bitmap>()

    /** Where each photo was taken from, display-oriented: what its pixels are upright to. */
    private val poses = HashMap<String, DebugPose>()
    private val jobs = ArrayList<Job>()
    private val inFlight = AtomicInteger(0)

    // The dense map: one fusion at a time on Dispatchers.Default; a depth image that arrives while
    // one runs is skipped, never queued, so a slow phone thins the depth instead of lagging.
    // The map keeps a whole flat or house within this phone's budget: past it, it coarsens the
    // regions the camera has left instead of dropping what is new (#4629).
    private val dense = if (rawDepth) ScanDenseMap() else null
    private val fusing = AtomicBoolean(false)
    private var fuseJob: Job? = null
    // The final model's TSDF, fed the same depth copies on its own worker (RerunModelUi.kt).
    private val model = if (rawDepth) RerunLiveModel(preset) else null
    // Which depth estimate was taken last, and how long the scan has waited for one.
    private val depthWindow = DepthWindow()
    private var loggedSizes = false
    private val logGate = IntervalGate(LOG_INTERVAL_NS)
    private val verdictCounts = IntArray(DepthFreshness.Verdict.entries.size)
    private val verdictLagMs = LongArray(DepthFreshness.Verdict.entries.size)
    private var unavailableCount = 0
    private var unavailableReason: String? = null
    // At most ten depth keyframes a second: ARCore's own raw-depth pace on a Pixel, and a bound
    // on the camera-image reads the fusion adds to the main thread.
    private val depthGate = IntervalGate(DEPTH_INTERVAL_NS)
    private val denseAdded = AtomicInteger(0)
    private val denseKept = AtomicInteger(0)
    private var recordedTotal = 0

    // The dense frame count each DepthStats on the timeline was written at, in order: what
    // [finish] turns into the saved cloud's prefix found by then (ScanDenseMap.revealed).
    private val recordedFrames = ArrayList<Int>()

    // Why depth frames did or did not grow the map, logged every two seconds for a device run.
    private val intake = DepthIntake()

    /**
     * The HUD's clock, from when Record started on the monotonic clock (see [elapsedSeconds]). It
     * stops while the scan is paused or in review (#4627).
     */
    private val clock = ScanClock(SystemClock.elapsedRealtimeNanos())

    /** The journal [finish] took, kept so Try again rebuilds from it after a build that threw. */
    private var finishedEvents: List<ArDebugEvent>? = null

    /**
     * Points the saved scan keeps so far: the HUD's count, the same figure the saved scan shows.
     * `0` without raw depth.
     */
    val denseCount: Int get() = dense?.count ?: 0

    /**
     * The HUD's count on a depth scan (#4717): the points confirmed so far, which never drops
     * when the map coarsens. `0` without raw depth.
     */
    val denseCaptured: Int get() = dense?.captured ?: 0

    /**
     * Whether the surface in view gives nothing at the usual confidence, or only relaxed samples
     * (#4717): the coaching line says to move closer.
     */
    val lowTexture: Boolean get() = !depthStarved && (dense?.confidence ?: trace.pointConfidence)?.lowTexture == true

    /**
     * A depth scan that has had no depth to fuse for [DepthFreshness.STARVED_AFTER_NANOS] (#4540):
     * ARCore gives none, so nothing is fused whatever the phone points at. The real cause of a
     * count frozen at zero — which read as "this surface has little texture" in front of a parquet
     * floor, because a starved confidence gate looks like a blank surface. Depth that ARCore hands
     * over late no longer starves the scan ([DepthWindow]).
     */
    val depthStarved: Boolean
        get() = dense != null && depthWindow.starved

    /** What the coaching line says of the dense map now: fine, adjusting its detail, or full. */
    val denseDetail: ScanDetail get() = dense?.detail() ?: ScanDetail.Fine

    /**
     * Seconds since Record at [nowNanos] (`SystemClock.elapsedRealtimeNanos`). The HUD's clock:
     * the trace records nothing while tracking is lost, and a clock read off it froze for 2.4 s at
     * "Not enough detail" on a Pixel 9.
     */
    fun elapsedSeconds(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Float = clock.elapsedSeconds(nowNanos)

    /**
     * The clock runs while [running] — scanning with tracking held — and stops otherwise: paused,
     * in review, or with tracking lost. It runs on from where it stopped. Idempotent.
     */
    fun followClock(running: Boolean, nowNanos: Long = SystemClock.elapsedRealtimeNanos()) =
        clock.follow(running, nowNanos)

    /** The scan as it grows. Its thumbnails fill in as the photos are encoded. */
    val live = RerunReplayMedia(
        trace, manifest(emptyMap()), emptyMap(), thumbnails, ByteArray(0), growing = true,
        liveDense = dense?.let { map -> { map.live } },
    )

    /** No more photos: the path, the points and the planes keep recording. */
    val isPhotoLimitReached: Boolean get() = gate.isFull

    /** Whether the camera at [display] should take a photo now. */
    fun wantsPhoto(nanos: Long, display: DebugPose): Boolean {
        // Measured on every tracked frame, so the motion between two consecutive frames is always
        // known. It only decides whether the photo is a sharp one: the cadence is the gate's alone.
        calm = photoMotion.isCalm(nanos, display)
        return inFlight.get() < MAX_IN_FLIGHT && gate.wants(display)
    }

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
        val sharp = sharpPicker.nextIsSharp(calm)
        sharpPicker.accept(sharp)
        val pixels = image.copy()
        val sensor = image.sensor
        gate.accept(display)
        poses[path] = display
        trace.addImage(nanos, path)
        inFlight.incrementAndGet()
        jobs += scope.launch(Dispatchers.Default) {
            try {
                val size = ScanPhotoPolicy.targetSize(sharp, lens, pixels.width, pixels.height)
                val (width, height) = size
                val argb = ScanProjection.uprightImage(pixels, intrinsics, sensor, display, lens, width, height)
                val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
                try {
                    val encoded = ScanPhotoPolicy.encode(sharp, size) { w, h, quality ->
                        val scaled = if (w == width && h == height) bitmap
                        else Bitmap.createScaledBitmap(bitmap, w, h, true)
                        try {
                            val out = ByteArrayOutputStream()
                            if (scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
                        } finally {
                            if (scaled !== bitmap) scaled.recycle()
                        }
                    } ?: return@launch
                    jpegs[path] = encoded.bytes
                    // Same small live thumbnails as before, even when the stored photo is sharp.
                    val (thumbWidth, thumbHeight) = ScanPhotoPolicy.thumbnailSize(encoded.width, encoded.height)
                    thumbnails[path] = Bitmap.createScaledBitmap(bitmap, thumbWidth, thumbHeight, true)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * ARCore's raw depth of [frame] when it holds a new estimate, at most
     * [DepthFreshness.MAX_AGE_NANOS] behind the frame and not fused already ([DepthFreshness]) —
     * or past that window once none has come for a while ([DepthWindow]) — and no fusion is
     * running; `null` otherwise, or without [rawDepth]. Close it this frame.
     */
    fun acquireDepth(frame: Frame): ScanDepth? {
        depthWindow.frame(frame.timestamp)
        if (dense == null || !depthGate.isDue(frame.timestamp)) return null
        if (fusing.get()) {
            intake.count(Outcome.Busy)
            return null
        }
        val depth = runCatching { frame.acquireRawDepthImage16Bits() }
            .onFailure { logUnavailable(frame.timestamp, it) }
            .getOrNull()
        if (depth == null) {
            intake.count(Outcome.NoDepth)
            return null
        }
        // Before the gate, so a device log says what the scan sees even when it fuses nothing.
        logSizesOnce(depth, frame.timestamp)
        val verdict = DepthFreshness.judge(frame.timestamp, depth.timestamp, depthWindow.lastTakenNanos)
        logVerdict(verdict, frame.timestamp, depth.timestamp)
        if (!depthWindow.take(verdict, frame.timestamp, depth.timestamp)) {
            intake.count(Outcome.Stale)
            depth.close()
            return null
        }
        depthGate.mark(frame.timestamp)
        val confidence = runCatching { frame.acquireRawDepthConfidenceImage() }.getOrNull()
        return ScanDepth(depth, confidence, frame.camera.pose.toScanPose(), frame.timestamp)
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
        Log.i(
            LOG_TAG,
            "depth verdicts: ${counts.joinToString()}; late taken ${depthWindow.lateTaken}" +
                "${if (depthWindow.late) " (late now)" else ""}; points $denseCount",
        )
        verdictCounts.fill(0)
        unavailableCount = 0
    }

    /**
     * Fuses [depth] into the dense map, coloured from [image] (the same frame's camera image):
     * the depth, its confidence and one colour per kept pixel are copied here, on the main thread,
     * then back-projected and merged on [Dispatchers.Default]. Without an [image] this frame, the
     * depth is let go, and counted as such.
     */
    fun fuseDepth(depth: ScanDepth, image: ScanImage?) {
        val dense = dense ?: return
        if (image == null) {
            intake.count(Outcome.NoImage)
            return
        }
        val frame = depth.copy(textureIntrinsics, intrinsics, image.yuv, dense.confidence.threshold.toInt())
        if (frame == null) {
            intake.count(Outcome.Unreadable)
            return
        }
        model?.offer(frame, scope)
        fusing.set(true)
        fuseJob = scope.launch(Dispatchers.Default) {
            try {
                val samples = dense.project(frame, depth.nanos, farM = preset.farM)
                val stats = dense.add(samples)
                intake.count(
                    when {
                        samples.count == 0 -> Outcome.NoSamples
                        stats.added > 0 -> Outcome.Grew
                        else -> Outcome.NothingNew
                    },
                )
                denseAdded.addAndGet(stats.added)
                denseKept.addAndGet(stats.kept)
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
        val dense = dense ?: return
        val total = dense.count
        intake.lineIfDue(nanos, total)?.let { Log.i(LOG_TAG, "$it; ${dense.logLine()}") }
        if (total == recordedTotal) return
        recordedTotal = total
        recordedFrames += dense.frames
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
        // The journal is taken once and kept: a build that threw (out of memory, painting the
        // planes) is retried from the same events, not from a journal already let go.
        val journal = finishedEvents ?: run {
            trace.journal?.lastOrNull()?.let { last ->
                recordDepthStats(last.nanos)
                // The last fusion's floor cells, which no frame observed before the stop (#4734):
                // the result's m² reads the cells journaled, so they go in with the rest.
                dense?.live?.coveredCells?.takeIf { it.isNotEmpty() }?.let { trace.addFloorCells(last.nanos, it) }
            }
            trace.journal?.toList().orEmpty().also { finishedEvents = it }
        }
        trace.journal = null
        jobs.toList().joinAll()
        if (trace.isEmpty) return null
        // A photo whose encoding failed has no media: its image event does not reach the saved scan.
        val events = ScanPhotoPolicy.savedEvents(journal) { jpegs[it] != null }
        val photos = events.filterIsInstance<ArDebugEvent.Image>().mapNotNull { image ->
            val jpeg = jpegs[image.path] ?: return@mapNotNull null
            val pose = poses[image.path] ?: return@mapNotNull null
            ScanPhoto(image.path, jpeg, pose)
        }
        val started = System.nanoTime()
        val map = dense
        val cloud = map?.takeIf { it.fusion.count > 0 }
            ?.let { m -> withContext(Dispatchers.Default) { m.savedCloud() } }
            ?.takeIf { it.count > 0 }
        // The live counts were the confirmed points as they stood; a later compaction merged some
        // of them, so the replay's reveal is recomputed against the cloud really saved.
        val timeline = if (map != null && cloud != null) {
            withContext(Dispatchers.Default) { map.revealed(events, recordedFrames.toIntArray()) }
        } else {
            events
        }
        val denseMs = (System.nanoTime() - started) / 1_000_000
        // The tier the scan really reached: raw depth on but no surfel is a sparse scan, said so.
        val device = ScanDevice(
            platform = "android",
            model = Build.MODEL.orEmpty(),
            tier = if (cloud != null) ScanDevice.TIER_DEPTH else ScanDevice.TIER_SPARSE,
            depthSource = if (cloud != null) ScanDevice.SOURCE_RAW_DEPTH else ScanDevice.SOURCE_FEATURE_POINTS,
        )
        val camera = "${RerunScanCamera.line}; images ${intrinsics.width}x${intrinsics.height}"
        return RerunCaptureBuilder.build(timeline, lens, photos, device, cloud, DenseFusion.VOXEL_M, denseMs, camera)
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
        fun start(
            frame: Frame,
            scope: CoroutineScope,
            rawDepth: Boolean = false,
            preset: ScanPreset = ScanPreset.ROOM,
        ): ScanCapture? {
            val camera = frame.camera
            val intrinsics = camera.imageIntrinsics.toScan() ?: return null
            val lens = ScanProjection.displayLens(
                intrinsics,
                camera.pose.toScanPose(),
                camera.displayOrientedPose.toScanPose(),
            )
            return ScanCapture(intrinsics, camera.textureIntrinsics.toScan(), lens, scope, rawDepth, preset)
        }

        /** 100 ms between two fused depth keyframes. */
        const val DEPTH_INTERVAL_NS = 100_000_000L


        /** `adb logcat -s RerunScan`: the depth verdicts every second, the intake every two. */
        private const val LOG_TAG = "RerunScan"

        /** One depth-verdict line a second of frame time at most. */
        private const val LOG_INTERVAL_NS = 1_000_000_000L
        private const val NANOS_PER_MS = 1_000_000L

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
internal class ScanDepth(
    private val depth: Image,
    private val confidence: Image?,
    val sensor: DebugPose,
    /** The frame's timestamp: the clock [AdaptiveConfidence] waits on. */
    val nanos: Long,
) : AutoCloseable {
    /**
     * The depth copied out of ARCore's buffers, its lens the one of the image it is aligned with
     * ([DepthAlignment.depthLens]: the GPU [texture]'s, scaled to it), each pixel coloured from
     * [yuv] (the CPU camera image, lens [image]) along the same ray. `null` for a depth image that
     * cannot be read, that no lens frames, or that comes without its confidence image.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements") // one skip per unusable pixel
    fun copy(
        texture: ScanIntrinsics?,
        image: ScanIntrinsics,
        yuv: YuvFrame,
        minConfidence: Int = DepthBackProjection.MIN_CONFIDENCE,
    ): DepthFrame? {
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
            if ((conf[i].toInt() and 0xFF) < minConfidence) continue
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
