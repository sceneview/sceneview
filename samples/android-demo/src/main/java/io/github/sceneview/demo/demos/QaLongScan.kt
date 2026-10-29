package io.github.sceneview.demo.demos

import android.os.Debug
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.ScanDetail
import io.github.sceneview.demo.demos.internal.ScanWalkthrough
import io.github.sceneview.demo.demos.internal.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * QA only (#4629, `--es qa_state record-long`): the capture screen's dense map fed a whole flat
 * ([ScanWalkthrough]) at the phone's depth pace, since the emulator has no depth camera. It runs
 * the real [ScanDenseMap] on the real budget, so the HUD's count, the coaching line and the live
 * 3D card show what a long scan does, and `adb logcat -s QaLongScan` gives its figures.
 *
 * `record-long-fixed` runs the map as it was before #4629 — one fixed 500 k cap, no coarsening —
 * for a before/after on the same walk.
 *
 * `record-low-texture` (#4717) starts the walk on a plain surface: its first [plainFrames] depth
 * frames are all [PLAIN_CONFIDENCE], under the usual threshold, so the capture says "Move closer"
 * and relaxes until they pass. Both long states reach the photo limit at [PHOTO_LIMIT_FRAME], as a
 * scan of a flat does, though the emulator takes no photo.
 */
internal class QaLongScan(fixed: Boolean, origin: Vec3, private val plainFrames: Int = 0) {
    val dense = if (fixed) {
        ScanDenseMap(maxPoints = DenseFusion.MAX_POINTS, maxLevel = 0)
    } else {
        ScanDenseMap()
    }
    private val walk = ScanWalkthrough(origin = origin)

    /** Frames of the walk merged so far. */
    var played = 0
        private set

    /**
     * What the HUD shows of [dense], as snapshot state: the bundled replay behind the QA scan
     * holds on its end after a few seconds, and nothing else recomposes the capture screen then.
     */
    var shownCount by mutableIntStateOf(0)
        private set

    /** The walk's own clock, a depth frame every 0.1 s: the replay's stops with it. */
    var shownSeconds by mutableFloatStateOf(0f)
        private set

    /** The coaching line's [ScanDetail], kept current like [shownCount]. */
    var shownDetail by mutableStateOf(ScanDetail.Fine)
        private set

    /** Whether the surface in view gives no confident depth: "Move closer" (#4717). */
    var shownLowTexture by mutableStateOf(false)
        private set

    /** Whether the walk has gone past the photo limit a real scan would have reached. */
    var shownPhotoLimit by mutableStateOf(false)
        private set

    /**
     * Plays the walk, one depth frame every [ScanCapture.DEPTH_INTERVAL_NS] while [integrates]
     * says the capture scans, holding on its end. Logs the map's figures every [LOG_EVERY] frames.
     */
    suspend fun run(integrates: () -> Boolean) = withContext(Dispatchers.Default) {
        Log.i(LOG_TAG, "start: budget ${dense.fusion.maxPoints} points, ${walk.frameCount} frames")
        while (played < walk.frameCount) {
            if (!integrates()) {
                show()
                delay(PAUSED_POLL_MS)
                continue
            }
            val started = System.nanoTime()
            val nanos = played * ScanCapture.DEPTH_INTERVAL_NS
            dense.add(dense.project(frameAt(played), nanos))
            played++
            show()
            if (played % LOG_EVERY == 0 || played == walk.frameCount) log()
            val spent = (System.nanoTime() - started) / NANOS_PER_MS
            delay((ScanCapture.DEPTH_INTERVAL_NS / NANOS_PER_MS - spent).coerceAtLeast(0L))
        }
        val rooms = walk.pointsPerRoom(dense.savedCloud()).joinToString("/")
        Log.i(LOG_TAG, "done: saved ${dense.count} points, per room $rooms")
        // The walk is over; "Adjusting detail" still has to time out on screen.
        while (dense.detail() == ScanDetail.Adjusting) {
            delay(PAUSED_POLL_MS)
            show()
        }
        show()
    }

    /** Frame [index] of the walk, its confidence flattened on the plain surface at the start. */
    private fun frameAt(index: Int): DepthFrame {
        val frame = walk.frame(index)
        if (index >= plainFrames) return frame
        val plain = ByteArray(frame.width * frame.height) { PLAIN_CONFIDENCE.toByte() }
        return DepthFrame(
            frame.width, frame.height, frame.depthMm, plain, frame.colors,
            frame.fx, frame.fy, frame.cx, frame.cy, frame.pose,
        )
    }

    private fun show() {
        // The HUD's count never drops when the map coarsens (#4717).
        shownCount = dense.captured
        shownLowTexture = dense.confidence.lowTexture
        shownPhotoLimit = played >= PHOTO_LIMIT_FRAME
        val detail = dense.detail()
        if (detail != shownDetail) Log.i(LOG_TAG, "detail $shownDetail -> $detail at frame $played")
        shownDetail = detail
        shownSeconds = played * ScanCapture.DEPTH_INTERVAL_NS / NANOS_PER_S
    }

    private fun log() {
        val runtime = Runtime.getRuntime()
        val javaMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        val nativeMb = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB
        Log.i(
            LOG_TAG,
            "frame $played room ${walk.roomAt(played - 1)}: ${dense.logLine()} " +
                "detail ${dense.detail()} java $javaMb MB native $nativeMb MB",
        )
    }

    companion object {
        /** The walk laid out from the middle of [sample]'s room, on its floor, so the 3D card frames it. */
        fun over(sample: ArDebugTrace, fixed: Boolean, plain: Boolean = false): QaLongScan {
            val b = ArDebugGeometry.contentBounds(sample.frameAt(sample.duration))
            val origin = if (b == null) Vec3(0f, 0f, 0f) else Vec3((b[0] + b[3]) / 2f, b[1], (b[2] + b[5]) / 2f)
            return QaLongScan(fixed, origin, plainFrames = if (plain) PLAIN_FRAMES else 0)
        }

        /** A plain wall's raw-depth confidence: under the usual 128, over the relaxed floor of 64. */
        const val PLAIN_CONFIDENCE = 90

        /** Twenty seconds of the walk on the plain surface. */
        const val PLAIN_FRAMES = 200

        /** Half a minute into the walk: a real flat's scan has taken its photos by then. */
        const val PHOTO_LIMIT_FRAME = 300

        private const val LOG_TAG = "QaLongScan"
        private const val LOG_EVERY = 30
        private const val PAUSED_POLL_MS = 100L
        private const val NANOS_PER_MS = 1_000_000L
        private const val NANOS_PER_S = 1e9f
        private const val BYTES_PER_MB = 1_048_576L
    }
}
