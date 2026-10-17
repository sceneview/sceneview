package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ResourceExhaustedException
import io.github.sceneview.ar.arcore.ARSession
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthAlignment
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.DepthFreshness
import io.github.sceneview.demo.demos.internal.DepthIntake
import io.github.sceneview.demo.demos.internal.DepthIntake.Outcome
import io.github.sceneview.demo.demos.internal.IntervalGate
import io.github.sceneview.demo.demos.internal.KeyframeGate
import io.github.sceneview.demo.demos.internal.MediaSpan
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunAnchoredFrames
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
 * the scan shows as it grows. The images are kept, each tied to an ARCore anchor, and [finish]
 * fuses them all again with the poses ARCore holds by then ([RerunAnchoredFrames]): that map is
 * the scan's dense cloud, and the model's TSDF. Without raw depth the scan is the sparse v1 one.
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
    // This one is what the scan shows while it runs; [refuse] replaces it when the scan stops.
    private var fusion = if (rawDepth) DenseFusion() else null
    private val fusing = AtomicBoolean(false)
    private var fuseJob: Job? = null
    // The final model's TSDF, which [refuse] builds when the scan stops (RerunModelUi.kt).
    private val model = if (rawDepth) RerunLiveModel() else null
    // The same depth copies, kept for [refuse] with an anchor each, and ARCore's anchors by id.
    private val kept = if (rawDepth) RerunAnchoredFrames() else null
    private val anchors = HashMap<Int, Anchor>()
    private var anchorSession: Session? = null
    private var anchorRefused = false
    // Room was made for an anchor ARCore still refused: not again until it grants one.
    private var roomMadeInVain = false
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

    // Why depth frames did or did not grow the map, logged every two seconds for a device run.
    private val intake = DepthIntake()

    /** When Record started, on the monotonic clock: the HUD's clock runs from it (see [elapsedSeconds]). */
    private val startedNanos = SystemClock.elapsedRealtimeNanos()

    /** Surfels in the dense map so far: the HUD's "N surfaces" count. `0` without raw depth. */
    val denseCount: Int get() = denseTotal.get()

    /** The share of the final fusion done, 0 to 1, while [finish] runs it: the bar of the Stop wait. */
    var finishProgress: Float by mutableFloatStateOf(0f)
        private set

    /**
     * The dense map as the live 3D card draws it: a snapshot taken after a fusion at most every
     * [LIVE_DENSE_INTERVAL_NS], its first [LIVE_DENSE_MAX_SURFELS] surfels at least two depth
     * frames saw, meshed and coloured off the main thread. `null` until the first one.
     */
    @Volatile
    private var liveDense: ReplayDenseLayer? = null
    private var liveDenseAtNanos = Long.MIN_VALUE

    /**
     * Seconds since Record at [nowNanos] (`SystemClock.elapsedRealtimeNanos`). The HUD's clock:
     * the trace records nothing while tracking is lost, and a clock read off it froze for 2.4 s at
     * "Not enough detail" on a Pixel 9.
     */
    fun elapsedSeconds(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Float =
        ((nowNanos - startedNanos).coerceAtLeast(0L) / NANOS_PER_SECOND).toFloat()

    /** The scan as it grows. Its thumbnails fill in as the photos are encoded. */
    val live = RerunReplayMedia(
        trace, manifest(emptyMap()), emptyMap(), thumbnails, ByteArray(0), growing = true,
        liveDense = if (rawDepth) ({ liveDense }) else null,
    )

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
        if (fusion == null || !depthGate.isDue(frame.timestamp)) return null
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
        val verdict = DepthFreshness.judge(frame.timestamp, depth.timestamp, lastDepthNanos)
        logVerdict(verdict, frame.timestamp, depth.timestamp)
        if (verdict != DepthFreshness.Verdict.FRESH) {
            intake.count(Outcome.Stale)
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
     * then back-projected and merged on [Dispatchers.Default]. Without an [image] this frame, the
     * depth is let go, and counted as such. The copy is also kept for the final fusion ([keep]),
     * tied to an anchor of [session] when there is one.
     */
    fun fuseDepth(depth: ScanDepth, image: ScanImage?, session: Session? = null) {
        val fusion = fusion ?: return
        if (image == null) {
            intake.count(Outcome.NoImage)
            return
        }
        val frame = depth.copy(textureIntrinsics, intrinsics, image.yuv)
        if (frame == null) {
            intake.count(Outcome.Unreadable)
            return
        }
        keep(frame, session)
        fusing.set(true)
        fuseJob = scope.launch(Dispatchers.Default) {
            try {
                val samples = DepthBackProjection.project(frame)
                val stats = fusion.add(samples)
                intake.count(
                    when {
                        samples.count == 0 -> Outcome.NoSamples
                        stats.added > 0 -> Outcome.Grew
                        else -> Outcome.NothingNew
                    },
                )
                denseAdded.addAndGet(stats.added)
                denseKept.addAndGet(stats.kept)
                denseTotal.set(stats.total)
                snapshotLiveDense(fusion)
            } finally {
                fusing.set(false)
            }
        }
    }

    /**
     * Keeps [frame] for the final fusion ([refuse]), tied to an ARCore anchor: ARCore corrects an
     * anchor's pose as its map improves, never a camera pose it has already given. A new anchor
     * is dropped where the camera stands once it has moved or turned enough since the last one
     * ([RerunAnchoredFrames.anchorDue]), or when ARCore has stopped tracking that one. When
     * ARCore has no room for it, every other anchor is let go and it is asked once more; an
     * anchor still refused leaves the frame on the previous one. Main thread.
     */
    private fun keep(frame: DepthFrame, session: Session?) {
        val kept = kept ?: return
        if (session != null) forgetDeadAnchors(session)
        refreshAnchors(kept)
        val lost = anchors[kept.latestAnchor]?.trackingState == TrackingState.STOPPED
        if (session != null && (lost || kept.anchorDue(frame.pose))) {
            val at = frame.pose
            val pose = Pose(floatArrayOf(at.x, at.y, at.z), floatArrayOf(at.qx, at.qy, at.qz, at.qw))
            // An anchor the store lets go is handed back to ARCore at once.
            val release: (Int) -> Unit = { gone -> anchors.remove(gone)?.detach() }
            runCatching { session.createAnchor(pose) }
                .recoverCatching { refusal ->
                    // ARCore's ceiling came before the store's: without room made here no anchor
                    // would ever be granted again, and the rest of the scan would hang from the
                    // last one. Once per refusal — room that does not cure it must not cost the
                    // anchors one half at a time.
                    val full = refusal is ResourceExhaustedException && !roomMadeInVain
                    if (!full || !kept.makeRoom(release)) throw refusal
                    roomMadeInVain = true
                    session.createAnchor(pose)
                }
                .onSuccess { anchor ->
                    val id = kept.addAnchor(at, release)
                    anchors[id] = anchor
                    anchorSession = session
                    roomMadeInVain = false
                }
                .onFailure {
                    // Said once: a refusal is asked again at every frame an anchor is due.
                    val why = it.javaClass.simpleName
                    if (!anchorRefused) Log.w(LOG_TAG, "anchor refused, ${anchors.size} live: $why")
                    anchorRefused = true
                }
        }
        kept.offer(frame)
    }

    /**
     * Forgets, untouched, the anchors of a session that is closed or is no longer the [current]
     * one: they went with it, and a call on such a handle is a native use-after-free, not an
     * exception (#4026). Their frames keep the last pose read from them.
     */
    private fun forgetDeadAnchors(current: Session? = anchorSession) {
        val owner = anchorSession ?: return
        if (owner !== current || (owner as? ARSession)?.isClosed == true) {
            anchors.clear()
            anchorSession = null
            roomMadeInVain = false
        }
    }

    /** ARCore's latest pose of each anchor it tracks; one it has paused keeps its last good pose. */
    private fun refreshAnchors(kept: RerunAnchoredFrames) {
        for ((id, anchor) in anchors) {
            if (anchor.trackingState == TrackingState.TRACKING) kept.moveAnchor(id, anchor.pose.toScanPose())
        }
    }

    /** Takes [liveDense] when it is due. On the fusion's thread, while it holds [fusing]. */
    private fun snapshotLiveDense(fusion: DenseFusion) {
        val now = System.nanoTime()
        if (liveDenseAtNanos != Long.MIN_VALUE && now - liveDenseAtNanos < LIVE_DENSE_INTERVAL_NS) return
        liveDenseAtNanos = now
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS, maxPoints = LIVE_DENSE_MAX_SURFELS)
        if (cloud.count == 0) return
        liveDense = ReplayDenseLayer.of(cloud, fusion.voxelM)
    }

    /**
     * Writes into [trace] how far the dense map has grown since the last call, stamped [nanos] —
     * the frame that learns of it — so a replay reveals the cloud as it was found. Main thread.
     */
    fun recordDepthStats(nanos: Long) {
        val total = denseTotal.get()
        intake.lineIfDue(nanos, total)?.let { Log.i(LOG_TAG, it) }
        if (total == recordedTotal) return
        recordedTotal = total
        trace.addDepthStats(nanos, denseAdded.getAndSet(0), denseKept.getAndSet(0), total)
    }

    /**
     * The final fusion: every kept depth frame fused again, off the main thread, into a new surfel
     * map and into the model's TSDF, each with the camera pose its anchor gives it now — ARCore's
     * map as the whole scan corrected it, where the live map fused each frame with the pose of its
     * moment and showed twice a wall the scan came back to. The anchors are read one last time,
     * then let go. [finishProgress] follows it. Main thread, like [finish].
     */
    private suspend fun refuse() {
        val kept = kept ?: return
        forgetDeadAnchors()
        refreshAnchors(kept)
        anchors.values.forEach { it.detach() }
        anchors.clear()
        anchorSession = null
        if (kept.frameCount == 0) {
            finishProgress = 1f
            return
        }
        val live = denseTotal.get()
        val started = System.nanoTime()
        // The live map is let go before the new one grows: only its last snapshot is still drawn.
        val fresh = DenseFusion().also { fusion = it }
        val done = withContext(Dispatchers.Default) {
            kept.drainInto(fresh, model?.tsdf) { finishProgress = it }
        }
        denseTotal.set(fresh.count)
        val tsdf = model?.tsdf
        Log.i(
            LOG_TAG,
            "final fusion: ${done.frames} frames (one in ${kept.frameStride}) on ${done.anchors} anchors " +
                "(spacing x${kept.anchorSpacing}) in ${(System.nanoTime() - started) / NANOS_PER_MS} ms; " +
                "cameras moved ${(done.meanShiftM * MM_PER_M).toInt()} mm on average, " +
                "${(done.maxShiftM * MM_PER_M).toInt()} mm at most; surfels $live -> ${fresh.count}; " +
                "tsdf ${tsdf?.blockCount} blocks, ${tsdf?.bytes?.div(BYTES_PER_MB)} MB, " +
                "dropped ${tsdf?.droppedBlocks}",
        )
    }

    /**
     * Stops the scan, waits for the last photos, and builds it into the capture the sessions list
     * keeps — its planes painted from its photos. `null` for a scan that caught nothing. Call it
     * on the main thread, where the recorder writes: the journal is taken there, then let go.
     */
    suspend fun finish(): RerunCapturePack? {
        // The fusion under way finishes first; the final one then redoes the map on ARCore's
        // corrected poses, and what it comes to goes on the timeline.
        fuseJob?.join()
        refuse()
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

        /** `adb logcat -s RerunScan`: the depth verdicts every second, the intake every two. */
        private const val LOG_TAG = "RerunScan"

        /** One depth-verdict line a second of frame time at most. */
        private const val LOG_INTERVAL_NS = 1_000_000_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val NANOS_PER_SECOND = 1e9
        private const val MM_PER_M = 1000f
        private const val BYTES_PER_MB = 1024 * 1024

        /** A second between two snapshots of the dense map for the live 3D card. */
        const val LIVE_DENSE_INTERVAL_NS = 1_000_000_000L

        /**
         * Surfels the live card draws at most: 600 k vertices, a seventh of what the replay may
         * draw, so the card keeps its frame rate while the camera and the fusion run.
         */
        const val LIVE_DENSE_MAX_SURFELS = 150_000

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
