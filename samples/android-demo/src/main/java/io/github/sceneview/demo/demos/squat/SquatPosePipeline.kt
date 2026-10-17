package io.github.sceneview.demo.demos.squat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import io.github.sceneview.ar.arcore.cameraImage
import io.github.sceneview.ar.body.BodyPose
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What one pose inference produced, handed to the UI on the main thread. */
data class SquatFrame(
    /** The skeleton in **view-normalised** coordinates — multiply by the view size to draw. */
    val pose: BodyPose,
    val coach: SquatCoachState,
)

/**
 * The camera → pose → squat-count pipeline, built so it never holds the render thread.
 *
 * ### Why the old demo lagged
 * Every 160 ms the render thread converted the camera image YUV → NV21 → JPEG → Bitmap and
 * then ran `PoseLandmarker.detect` in `RunningMode.IMAGE`, which blocks its caller until the
 * model is done. `onSessionUpdated` runs on the thread that draws the frame, so the camera
 * feed froze for the whole conversion plus inference, six times a second.
 *
 * ### What it does now
 * - **Render thread** ([onFrame]): if no inference is in flight, copy the three YUV planes
 *   out of `Frame.acquireCameraImage` (a few hundred KB of `memcpy`), close the image, read
 *   ARCore's display transform, hand off. Otherwise **drop the frame** — never queue, never
 *   wait. No Filament call is made anywhere in this class.
 * - **Worker thread** (`squat-coach-pose`): convert YUV straight to an upright, half-size
 *   RGB buffer ([yuv420ToUprightRgbHalf]) — no JPEG, no Bitmap — and submit it with
 *   `detectAsync` to a landmarker in `RunningMode.LIVE_STREAM`.
 * - **MediaPipe's thread** (result listener): map the landmarks to view space, update the
 *   [SquatRepCounter], post one [SquatFrame] to the main thread, and free the slot.
 *
 * ### Proof on a phone
 * `adb logcat -s SquatCoachPerf` prints, every two seconds, the render frame rate and its
 * worst frame gap, what the render-thread hand-off cost, the inference latency, and how
 * many frames were dropped because the previous inference was still running. Each counted
 * rep is logged too.
 */
class SquatPosePipeline(
    context: Context,
    private val onResult: (SquatFrame) -> Unit,
) : AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "squat-coach-pose")
    }
    private val closed = AtomicBoolean(false)

    /** One inference at a time. Set by the render thread, cleared by the result/error listener. */
    private val busy = AtomicBoolean(false)

    @Volatile private var busySinceNs = 0L

    private val landmarker: PoseLandmarker? = runCatching {
        PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_PATH).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setResultListener(::onPose)
                .setErrorListener { error ->
                    Log.w(PERF_TAG, "pose inference failed", error)
                    busy.set(false)
                }
                .build(),
        )
    }.onFailure {
        Log.e(PERF_TAG, "PoseLandmarker init failed", it)
    }.getOrNull()

    /** `false` when the model could not be loaded — the screen says so instead of crashing. */
    val isAvailable: Boolean get() = landmarker != null

    private val counter = SquatRepCounter()

    // ── Buffers owned by the one request in flight (handed over through [busy]) ──────────
    private var yPlane = ByteArray(0)
    private var uPlane = ByteArray(0)
    private var vPlane = ByteArray(0)
    private var yRowStride = 0
    private var uvRowStride = 0
    private var uvPixelStride = 0
    private var rawWidth = 0
    private var rawHeight = 0
    private var requestFrame: UprightFrame? = null
    private var requestUprightW = 0
    private var requestUprightH = 0
    private var requestSubmitNs = 0L
    private var rgb = ByteArray(0)
    private var rgbDirect: ByteBuffer = ByteBuffer.allocateDirect(0)
    private val corners = FloatArray(6)

    // ── Measurements ─────────────────────────────────────────────────────────────────────
    private val frameGaps = PerfWindow()
    private val handOffCost = PerfWindow()
    private val convertCost = PerfWindow()
    private val inferenceLatency = PerfWindow()
    private val dropped = AtomicInteger()
    private var lastFrameNs = 0L
    private var lastLogNs = 0L
    private var lastTimestampMs = -1L

    /** Clears the rep count. Safe from any thread. */
    fun resetCount() {
        synchronized(counter) { counter.reset() }
    }

    /** Render thread, once per ARCore frame. Never blocks on inference. */
    fun onFrame(frame: Frame) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        if (lastFrameNs != 0L) frameGaps.add((startNs - lastFrameNs) / NS_PER_MS)
        lastFrameNs = startNs
        maybeLog(startNs)

        val detector = landmarker ?: return
        if (closed.get()) return
        val timestampMs = frame.timestamp / NS_PER_MS_LONG
        // Same camera frame redrawn: nothing new for the model, and LIVE_STREAM needs
        // strictly increasing timestamps.
        if (timestampMs <= lastTimestampMs) return

        if (!busy.compareAndSet(false, true)) {
            dropped.incrementAndGet()
            if (startNs - busySinceNs > STUCK_NS) {
                // A result that never came back (should not happen) must not freeze the coach.
                Log.w(PERF_TAG, "inference slot stuck for > 1 s, freeing it")
                busy.set(false)
            }
            return
        }
        busySinceNs = startNs

        val handedOff = runCatching { copyFrame(frame) }.getOrElse {
            Log.w(PERF_TAG, "camera frame copy failed", it)
            false
        }
        if (!handedOff) {
            busy.set(false)
            return
        }
        lastTimestampMs = timestampMs
        requestSubmitNs = startNs
        handOffCost.add((SystemClock.elapsedRealtimeNanos() - startNs) / NS_PER_MS)

        worker.execute { convertAndDetect(detector, timestampMs) }
    }

    /** Copies the CPU image planes and reads ARCore's display transform. Render thread. */
    private fun copyFrame(frame: Frame): Boolean {
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_NORMALIZED,
            IMAGE_CORNERS,
            Coordinates2d.VIEW_NORMALIZED,
            corners,
        )
        val upright = UprightFrame.fromTransformedCorners(corners) ?: return false
        val image = frame.cameraImage() ?: return false
        image.use {
            val planes = it.planes
            yPlane = planes[0].buffer.copyInto(yPlane)
            uPlane = planes[1].buffer.copyInto(uPlane)
            vPlane = planes[2].buffer.copyInto(vPlane)
            yRowStride = planes[0].rowStride
            uvRowStride = planes[1].rowStride
            uvPixelStride = planes[1].pixelStride
            rawWidth = it.width
            rawHeight = it.height
        }
        requestFrame = upright
        return true
    }

    /** Worker thread: YUV → upright RGB, then hand the frame to MediaPipe. */
    private fun convertAndDetect(detector: PoseLandmarker, timestampMs: Long) {
        if (closed.get()) return
        runCatching {
            val upright = requireNotNull(requestFrame)
            val t0 = SystemClock.elapsedRealtimeNanos()
            val needed = (rawWidth / 2) * (rawHeight / 2) * RGB_CHANNELS
            if (rgb.size != needed) {
                rgb = ByteArray(needed)
                rgbDirect = ByteBuffer.allocateDirect(needed)
            }
            val (w, h) = yuv420ToUprightRgbHalf(
                y = yPlane, yRowStride = yRowStride,
                u = uPlane, v = vPlane,
                uvRowStride = uvRowStride, uvPixelStride = uvPixelStride,
                rawWidth = rawWidth, rawHeight = rawHeight,
                frame = upright, out = rgb,
            )
            rgbDirect.clear()
            rgbDirect.put(rgb, 0, w * h * RGB_CHANNELS)
            rgbDirect.flip()
            requestUprightW = w
            requestUprightH = h
            convertCost.add((SystemClock.elapsedRealtimeNanos() - t0) / NS_PER_MS)
            val mpImage = ByteBufferImageBuilder(rgbDirect, w, h, MPImage.IMAGE_FORMAT_RGB).build()
            detector.detectAsync(mpImage, timestampMs)
        }.onFailure {
            Log.w(PERF_TAG, "pose submit failed", it)
            busy.set(false)
        }
    }

    /** MediaPipe's callback thread. */
    @Suppress("UNUSED_PARAMETER")
    private fun onPose(result: PoseLandmarkerResult, input: MPImage) {
        inferenceLatency.add((SystemClock.elapsedRealtimeNanos() - requestSubmitNs) / NS_PER_MS)
        val upright = requestFrame
        val aspect = if (requestUprightH > 0) requestUprightW.toFloat() / requestUprightH else 1f
        busy.set(false)
        if (closed.get() || upright == null) return

        val landmarks = result.landmarks().firstOrNull()
        val points = landmarks?.map { lm ->
            PosePoint(lm.x(), lm.y(), lm.visibility().orElse(0f))
        }
        val kneeAngle = points?.let {
            kneeAngleDegrees(
                left = it.leg(LEFT_HIP, LEFT_KNEE, LEFT_ANKLE),
                right = it.leg(RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE),
                aspect = aspect,
            )
        }
        val before: Int
        val coach = synchronized(counter) {
            before = counter.state.reps
            counter.onSample(kneeAngle)
        }
        if (coach.reps > before) {
            Log.i(
                PERF_TAG,
                String.format(
                    Locale.US,
                    "rep counted: reps=%d bottomKneeAngle=%.0f",
                    coach.reps,
                    coach.lastRepBottomAngle ?: -1f,
                ),
            )
        }
        val pose = BodyPose.fromMediaPipeLandmarks(
            points?.map { p ->
                BodyPose.RawLandmark(
                    x = upright.toViewX(p.x),
                    y = upright.toViewY(p.y),
                    z = 0f,
                    visibility = p.visibility,
                )
            },
        )
        val update = SquatFrame(pose, coach)
        mainHandler.post { if (!closed.get()) onResult(update) }
    }

    private fun maybeLog(nowNs: Long) {
        if (lastLogNs == 0L) {
            lastLogNs = nowNs
            return
        }
        if (nowNs - lastLogNs < LOG_EVERY_NS) return
        val seconds = (nowNs - lastLogNs) / NS_PER_S
        lastLogNs = nowNs
        val (frames, gapAvg, gapMax) = frameGaps.drain()
        val (handOffs, handOffAvg, handOffMax) = handOffCost.drain()
        val (_, convertAvg, convertMax) = convertCost.drain()
        val (inferences, latencyAvg, latencyMax) = inferenceLatency.drain()
        Log.i(
            PERF_TAG,
            String.format(
                Locale.US,
                "render fps=%.1f frameGap avg=%.1fms max=%.1fms | handOff n=%d avg=%.2fms max=%.2fms | " +
                    "convert avg=%.1fms max=%.1fms | " +
                    "inference n=%d (%.1f/s) latency avg=%.1fms max=%.1fms | dropped=%d",
                frames / seconds, gapAvg, gapMax,
                handOffs, handOffAvg, handOffMax,
                convertAvg, convertMax,
                inferences, inferences / seconds, latencyAvg, latencyMax,
                dropped.getAndSet(0),
            ),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Close the landmarker on the worker so it never races a conversion in progress.
        worker.execute { landmarker?.close() }
        worker.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val PERF_TAG = "SquatCoachPerf"
        const val MODEL_ASSET_PATH = "mediapipe/pose_landmarker_lite.task"
        const val RGB_CHANNELS = 3
        const val NS_PER_MS = 1_000_000.0
        const val NS_PER_MS_LONG = 1_000_000L
        const val NS_PER_S = 1_000_000_000.0
        const val LOG_EVERY_NS = 2_000_000_000L
        const val STUCK_NS = 1_000_000_000L

        // MediaPipe Pose Landmarker indices.
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28

        /** Raw image corners (0,0), (1,0), (0,1) in `IMAGE_NORMALIZED`. */
        val IMAGE_CORNERS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
    }
}

private fun List<PosePoint>.leg(hip: Int, knee: Int, ankle: Int): Leg? =
    if (size > ankle) Leg(this[hip], this[knee], this[ankle]) else null

/** Copies the buffer's remaining bytes into [reuse] when it is big enough, else a new array. */
private fun ByteBuffer.copyInto(reuse: ByteArray): ByteArray {
    val size = remaining()
    val target = if (reuse.size >= size) reuse else ByteArray(size)
    get(target, 0, size)
    return target
}
