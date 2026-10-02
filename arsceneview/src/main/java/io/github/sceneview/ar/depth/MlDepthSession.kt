package io.github.sceneview.ar.depth

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Live figures of an [MlDepthSession], for a debug overlay. */
data class MlDepthStats(
    /** Duration of the last network run, in milliseconds. */
    val lastInferenceMs: Float,
    /** Median network run over the last 30 runs, in milliseconds. */
    val medianInferenceMs: Float,
    /** Depth maps published per second, over the last 30 maps. */
    val publishedHz: Float,
    /** Anchors projected into the last image (plane samples + feature points). */
    val anchors: Int,
    /** Anchors the last scale fit kept. */
    val inliers: Int,
    /** RMS relative error of the scale fit over its inliers (0.05 = 5 %). */
    val rmsRelativeError: Float,
    /** `true` while a refused fit is covered by the previous scale. */
    val holding: Boolean,
)

/** What an [MlDepthSession] is doing. */
sealed interface MlDepthState {
    /** Loading and compiling the model (and downloading it, the first time). */
    data object Preparing : MlDepthState

    /**
     * The model runs but cannot be scaled yet: ARCore has not tracked enough plane or feature
     * points, or they span too little depth. No depth is published in this state.
     */
    data class WaitingForAnchors(val anchors: Int) : MlDepthState

    /** Metric depth maps are being published on [MlDepthSession.depthFrames]. */
    data class Running(val stats: MlDepthStats) : MlDepthState

    /** The device is hot (thermal status SEVERE or worse): inference is paused. */
    data object Throttled : MlDepthState

    /**
     * The model could not be loaded, or failed three runs in a row (a single failed run only
     * skips that frame, as on iOS). The session publishes nothing more.
     */
    data class Failed(val error: Throwable) : MlDepthState
}

/**
 * Monocular depth for devices without ARCore's Depth API: runs a [MonocularDepthEstimator] on
 * the CPU camera image and scales its output to metres against ARCore's own plane hits and
 * feature points, then publishes an [ArDepthFrame] with [ArDepthFrame.Source.Ml].
 *
 * ```kotlin
 * val mlDepth = remember { MlDepthSession(DepthAnythingV2Estimator(context), context = context) }
 * DisposableEffect(mlDepth) { onDispose { mlDepth.close() } }
 * ARSceneView(onSessionUpdated = { session, frame -> mlDepth.onSessionUpdated(session, frame) })
 * val depth by mlDepth.depthFrames.collectAsState()
 * ```
 *
 * **Threads.** [onSessionUpdated] runs on the render thread: it only copies the camera image,
 * the anchors and the pose (a few hundred microseconds), at most [targetHz] times a second and
 * never while a previous image is still in the network. Everything else — colour conversion,
 * inference, fit, map writing — runs on one dedicated worker thread.
 *
 * **Scale.** The network gives affine-invariant inverse depth `d`. Each map is fitted with
 * `1 / z = s · d + t` on the anchors (robust, weighted, with a weak prior from the previous
 * map), and `(s, t)` is smoothed over time. Fewer than 12 consistent anchors, or anchors that
 * span less than ×1.5 in depth, refuse the fit: the previous scale is held for up to 5 maps,
 * then nothing is published until the anchors come back. No depth is ever guessed without
 * metric support.
 *
 * **Cost.** Each map is ~100–300 ms late on a mid-range phone. [ArDepthFrame.cameraPose] is the
 * pose of the image it was computed from. Inference halves its rate at thermal status MODERATE
 * and pauses at SEVERE, and is skipped while the camera holds still (< 2 cm and < 2°).
 *
 * @param estimator the network. Owned by the session: closed by [close], on the worker.
 * @param targetHz maximum maps per second.
 * @param context used only to read the device thermal status (API 29+); may be `null`.
 */
class MlDepthSession(
    private val estimator: MonocularDepthEstimator,
    private val targetHz: Float = 5f,
    context: Context? = null,
) : Closeable {

    private val _depthFrames = MutableStateFlow<ArDepthFrame?>(null)

    /** The latest metric depth map, or `null` while none is valid. Buffers are pooled. */
    val depthFrames: StateFlow<ArDepthFrame?> = _depthFrames.asStateFlow()

    private val _state = MutableStateFlow<MlDepthState>(MlDepthState.Preparing)

    /** What the session is doing, for UI and logs. */
    val state: StateFlow<MlDepthState> = _state.asStateFlow()

    private val powerManager = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val thread = HandlerThread("SceneView-MlDepth").apply { start() }
    private val worker = Handler(thread.looper)
    private val ready = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // Render-thread state.
    private var lastCaptureNs = 0L
    private var lastCapturedPose: Pose? = null
    private var nextSlot = 0
    private val captures = Array(CAPTURE_POOL) { Capture() }

    // Worker state.
    private var rgb = ByteArray(0)
    private var networkOut = FloatArray(0)
    private val anchorD = FloatArray(MAX_ANCHORS)
    private val anchorZ = FloatArray(MAX_ANCHORS)
    private val anchorConfidence = FloatArray(MAX_ANCHORS)
    private val smoother = DepthScaleSmoother()
    private val outputs = Array(OUTPUT_POOL) { Output() }
    private var nextOutput = 0
    private val inferenceMs = FloatArray(STATS_WINDOW)
    private val publishedAtMs = LongArray(STATS_WINDOW)
    private var inferenceCount = 0
    private var publishedCount = 0
    private var consecutiveErrors = 0

    @Volatile
    private var lastResultValid = false

    init {
        worker.post(::warmUp)
    }

    private fun warmUp() {
        try {
            estimator.warmUp()
            val pixels = estimator.outputWidth * estimator.outputHeight
            rgb = ByteArray(estimator.inputWidth * estimator.inputHeight * 3)
            networkOut = FloatArray(pixels)
            outputs.forEach { it.allocate(pixels) }
            ready.set(true)
            _state.value = MlDepthState.WaitingForAnchors(0)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Could not prepare the depth model", e)
            _state.value = MlDepthState.Failed(e)
        }
    }

    /**
     * Feed one ARCore frame. Call it from `ARSceneView(onSessionUpdated = …)`, on the render
     * thread, every frame: the session decides which frames to use.
     */
    fun onSessionUpdated(session: Session, frame: Frame) {
        if (!wantsFrame(frame)) return
        val pose = frame.camera.pose
        val previous = lastCapturedPose
        if (lastResultValid && previous != null && !hasMoved(previous, pose)) return

        Trace.beginSection("SV:mlDepth:capture")
        try {
            val capture = captures[nextSlot]
            if (!capture.copyFrom(session, frame, pose)) return
            nextSlot = (nextSlot + 1) % CAPTURE_POOL
            lastCaptureNs = frame.timestamp
            lastCapturedPose = pose
            inFlight.set(true)
            if (!worker.post { process(capture) }) inFlight.set(false)
        } finally {
            Trace.endSection()
        }
    }

    /** Ready, idle, tracking, and due at the current (thermal-adjusted) rate. */
    private fun wantsFrame(frame: Frame): Boolean {
        if (!ready.get() || closed.get() || inFlight.get()) return false
        if (frame.camera.trackingState != TrackingState.TRACKING) return false
        val hz = effectiveHz() ?: return false
        return frame.timestamp - lastCaptureNs >= (NANOS_PER_SECOND / hz).toLong()
    }

    /** `null` when inference is paused for heat; otherwise the rate to run at. */
    private fun effectiveHz(): Float? {
        val pm = powerManager ?: return targetHz
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return targetHz
        val status = pm.currentThermalStatus
        return when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                _state.value = MlDepthState.Throttled
                null
            }
            status >= PowerManager.THERMAL_STATUS_MODERATE -> targetHz / 2f
            else -> targetHz
        }
    }

    private fun process(capture: Capture) {
        try {
            if (!closed.get()) {
                runPipeline(capture)
                consecutiveErrors = 0
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // One bad frame (a transient GPU or driver error) skips that frame only; the session
            // gives up after MAX_CONSECUTIVE_ERRORS in a row — the same policy as iOS.
            consecutiveErrors++
            Log.e(TAG, "ML depth frame failed ($consecutiveErrors in a row)", e)
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                _state.value = MlDepthState.Failed(e)
                ready.set(false)
            }
        } finally {
            inFlight.set(false)
        }
    }

    private fun runPipeline(capture: Capture) {
        val inW = estimator.inputWidth
        val inH = estimator.inputHeight
        val outW = estimator.outputWidth
        val outH = estimator.outputHeight

        traced("SV:mlDepth:preprocess") { DepthImageMath.yuvToRgb(capture.yuv, rgb, inW, inH) }
        val started = SystemClock.elapsedRealtimeNanos()
        traced("SV:mlDepth:infer") { estimator.estimate(rgb, networkOut) }
        recordInference((SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MS)

        val intrinsics = DepthAnchorMath.scaleIntrinsics(
            capture.fx, capture.fy, capture.cx, capture.cy,
            capture.imageWidth, capture.imageHeight, outW, outH,
        )
        val metric = estimator.outputKind == MonocularDepthEstimator.OutputKind.Metric
        val anchors = DepthAnchorMath.project(
            capture.anchors, capture.viewMatrix, intrinsics, networkOut, outW, outH,
            anchorD, anchorZ, anchorConfidence,
        )
        val scale = if (metric) {
            null
        } else {
            traced("SV:mlDepth:fit") {
                smoother.update(DepthScaleFit.fit(anchorD, anchorZ, anchorConfidence, anchors, smoother.prior))
            }
        }
        if (!metric && scale == null) {
            lastResultValid = false
            _depthFrames.value = null
            _state.value = MlDepthState.WaitingForAnchors(anchors)
            return
        }
        traced("SV:mlDepth:publish") { publish(capture, intrinsics, scale, anchors, outW, outH) }
    }

    @Suppress("LongParameterList")
    private fun publish(
        capture: Capture,
        intrinsics: DepthIntrinsics,
        scale: DepthScale?,
        anchors: Int,
        width: Int,
        height: Int,
    ) {
        val output = outputs[nextOutput]
        nextOutput = (nextOutput + 1) % OUTPUT_POOL
        DepthImageMath.writeMetric(networkOut, width, height, scale, output.millimetres, output.confidence)
        _depthFrames.value = ArDepthFrame(
            timestampNs = capture.timestampNs,
            width = width,
            height = height,
            millimetres = output.millimetres.duplicate(),
            confidence = output.confidence.duplicate(),
            intrinsics = intrinsics,
            cameraPose = capture.pose,
            source = ArDepthFrame.Source.Ml,
        )
        lastResultValid = true
        publishedAtMs[publishedCount % STATS_WINDOW] = SystemClock.elapsedRealtime()
        publishedCount++
        _state.value = MlDepthState.Running(
            MlDepthStats(
                lastInferenceMs = inferenceMs[(inferenceCount - 1) % STATS_WINDOW],
                medianInferenceMs = medianInference(),
                publishedHz = publishedHz(),
                anchors = anchors,
                inliers = scale?.inliers ?: anchors,
                rmsRelativeError = scale?.rmsRelativeError?.toFloat() ?: 0f,
                holding = smoother.isHolding,
            ),
        )
    }

    private fun recordInference(ms: Float) {
        inferenceMs[inferenceCount % STATS_WINDOW] = ms
        inferenceCount++
    }

    private fun medianInference(): Float {
        val n = minOf(inferenceCount, STATS_WINDOW)
        if (n == 0) return 0f
        val sorted = inferenceMs.copyOf(n).apply { sort() }
        return sorted[n / 2]
    }

    private fun publishedHz(): Float {
        val n = minOf(publishedCount, STATS_WINDOW)
        if (n < 2) return 0f
        val newest = publishedAtMs[(publishedCount - 1) % STATS_WINDOW]
        val oldest = publishedAtMs[(publishedCount - n) % STATS_WINDOW]
        val span = newest - oldest
        return if (span <= 0) 0f else (n - 1) * MS_PER_SECOND / span
    }

    /** Stops inference and releases the model, on the worker. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.set(false)
        worker.post {
            try {
                estimator.close()
            } finally {
                thread.quitSafely()
            }
        }
    }

    /** One camera image and everything needed to scale it, copied on the render thread. */
    private class Capture {
        val yuv = YuvCopy()
        val anchors = DepthAnchorSet(MAX_ANCHORS)
        val viewMatrix = FloatArray(16)
        private val planeMatrix = FloatArray(16)
        private var polygon = FloatArray(0)
        var pose: Pose = Pose.IDENTITY
        var timestampNs = 0L
        var fx = 0f
        var fy = 0f
        var cx = 0f
        var cy = 0f
        var imageWidth = 0
        var imageHeight = 0

        /** `false` when the camera image is not available for this frame. */
        fun copyFrom(session: Session, frame: Frame, cameraPose: Pose): Boolean {
            if (!copyImage(frame)) return false
            val camera = frame.camera
            val intrinsics = camera.imageIntrinsics
            val focal = intrinsics.focalLength
            val principal = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions
            fx = focal[0]
            fy = focal[1]
            cx = principal[0]
            cy = principal[1]
            imageWidth = dims[0]
            imageHeight = dims[1]
            pose = cameraPose
            timestampNs = frame.timestamp
            cameraPose.inverse().toMatrix(viewMatrix, 0)
            anchors.clear()
            copyPlanes(session)
            copyPoints(frame)
            return true
        }

        private fun copyImage(frame: Frame): Boolean {
            val image = try {
                frame.acquireCameraImage()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.v(TAG, "Camera image not available: ${e.message}")
                return false
            }
            image.use {
                val planes = it.planes
                yuv.width = it.width
                yuv.height = it.height
                yuv.yRowStride = planes[0].rowStride
                yuv.uvRowStride = planes[1].rowStride
                yuv.uvPixelStride = planes[1].pixelStride
                yuv.y = yuv.copyPlane(planes[0].buffer, yuv.y)
                yuv.u = yuv.copyPlane(planes[1].buffer, yuv.u)
                yuv.v = yuv.copyPlane(planes[2].buffer, yuv.v)
            }
            return true
        }

        private fun copyPlanes(session: Session) {
            val tracked = session.getAllTrackables(Plane::class.java)
                .asSequence()
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                .take(MAX_PLANES)
            for (plane in tracked) {
                val buffer = plane.polygon
                val size = buffer.remaining()
                if (polygon.size != size) polygon = FloatArray(size)
                buffer.duplicate().get(polygon)
                plane.centerPose.toMatrix(planeMatrix, 0)
                DepthAnchorMath.samplePlane(polygon, planeMatrix, anchors)
            }
        }

        private fun copyPoints(frame: Frame) {
            frame.acquirePointCloud().use { cloud ->
                val points = cloud.points.duplicate()
                while (points.remaining() >= 4 && anchors.count < anchors.capacity) {
                    anchors.add(points.get(), points.get(), points.get(), points.get())
                }
            }
        }
    }

    /** One published map's buffers, recycled every [OUTPUT_POOL] maps. */
    private class Output {
        var millimetres: ShortBuffer = ShortBuffer.allocate(0)
        var confidence: ByteBuffer = ByteBuffer.allocate(0)

        fun allocate(pixels: Int) {
            millimetres = ByteBuffer.allocateDirect(pixels * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            confidence = ByteBuffer.allocateDirect(pixels)
        }
    }

    private companion object {
        const val TAG = "MlDepthSession"
        const val CAPTURE_POOL = 2
        const val OUTPUT_POOL = 3
        const val MAX_ANCHORS = 768
        const val MAX_PLANES = 8
        const val STATS_WINDOW = 30
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val NANOS_PER_MS = 1_000_000f
        const val MS_PER_SECOND = 1000f
        const val STILL_TRANSLATION_M = 0.02f
        const val STILL_ROTATION_RAD = 0.0349f // 2°

        inline fun <T> traced(section: String, block: () -> T): T {
            Trace.beginSection(section)
            try {
                return block()
            } finally {
                Trace.endSection()
            }
        }

        fun hasMoved(a: Pose, b: Pose): Boolean {
            val dx = a.tx() - b.tx()
            val dy = a.ty() - b.ty()
            val dz = a.tz() - b.tz()
            if (sqrt(dx * dx + dy * dy + dz * dz) >= STILL_TRANSLATION_M) return true
            val dot = abs(a.qx() * b.qx() + a.qy() * b.qy() + a.qz() * b.qz() + a.qw() * b.qw())
            return 2f * acos(dot.coerceAtMost(1f)) >= STILL_ROTATION_RAD
        }
    }
}
