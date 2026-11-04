package io.github.sceneview.demo.demos

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.EyeHandoff
import io.github.sceneview.demo.demos.internal.RescanGuidance
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.OrbitPose
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.ScanReview
import io.github.sceneview.demo.demos.internal.ScanReviewResult
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Review before you leave, for one capture: what the review at Stop found ([result], read once
 * per review by [review]), the part the 3D view is on ([focus]), and the part being fixed ([fix]).
 * Main thread; the reads run on [Dispatchers.Default]. [millis] and [nanoTime] are the clocks,
 * fake ones in tests.
 */
@Stable
internal class ScanReviewSession(
    private val millis: () -> Long = SystemClock::uptimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** What the last review found; `null` outside a review, [ScanReviewResult.Empty] without a dense map. */
    var result by mutableStateOf<ScanReviewResult?>(null)
        private set

    /** `true` while the capture is read. */
    var checking by mutableStateOf(false)
        private set

    /** The part the 3D view is on, of [result]'s. */
    var focus by mutableIntStateOf(0)
        private set

    /** `true` once the user has touched the card: the view stays on the part they are reading. */
    var held by mutableStateOf(false)
        private set

    /** The part Fix now is filling in, `null` when none. */
    var fix by mutableStateOf<SessionFix?>(null)
        private set

    /** The parts a fix was started on, this capture: found again, they are set aside ([ScanReview.untried]). */
    private val tried = ArrayList<RescanZone>()

    /** The part the review's 3D view flies to, `null` while there is none to show. */
    val shown: RescanZone? get() = if (checking) null else result?.zones?.getOrNull(focus)

    /** What the card shows: `null` when the capture had no dense map to read. */
    val found: ScanReviewResult? get() = result?.takeUnless { it === ScanReviewResult.Empty }

    /**
     * One review, at Stop and review: [read] gives the capture's dense map as it stands, read once.
     * Then, while [cycles] (motion on, no screen reader) and until the card is touched, the 3D view
     * moves from part to part every [ScanReview.DWELL_S] until the review is left (the caller's
     * effect is cancelled, and calls [leave]).
     */
    suspend fun review(cycles: () -> Boolean = { true }, read: suspend () -> DenseCloud?) {
        fix = null
        focus = 0
        held = false
        result = null
        checking = true
        val found = try {
            val cloud = read()
            withContext(Dispatchers.Default) {
                runCatching { ScanReview.of(cloud) }.onFailure { Log.w(TAG, "review failed", it) }.getOrNull()
            }
        } finally {
            checking = false
        }
        result = found?.without(tried) ?: ScanReviewResult.Empty
        found?.let { r -> Log.i(TAG, "review: ${r.zones.size} parts in ${r.millis} ms${r.zones.joinToString("") { "\n  $it" }}") }
        val count = result?.zones?.size ?: 0
        if (count < 2) return
        var last = millis()
        var dwelt = 0L
        while (true) {
            delay(FOCUS_TICK_MS)
            val now = millis()
            if (!held && cycles()) dwelt += now - last
            last = now
            if (!held) focus = ScanReview.focusAt(dwelt / MS_PER_S, count)
        }
    }

    /** The review is left: nothing of it is shown again until the next one is read. */
    fun leave() {
        result = null
        checking = false
        focus = 0
        held = false
    }

    /** A finger on the card: the view stays on the part being read, Fix now aims at it. */
    fun hold() {
        held = true
    }

    /** Next part: the view flies on to the next one, and stays there. */
    fun next() {
        val count = result?.zones?.size ?: return
        if (count == 0) return
        held = true
        focus = (focus + 1) % count
    }

    /**
     * Fix now on [zone]: set at once, on the tap, so a review that follows always sees it — its
     * fill measured on [live], the capture as it stands, as the review measured the part.
     */
    fun startFix(zone: RescanZone, live: DenseCloud?) {
        tried += zone
        fix = SessionFix.start(zone, live, nanoTime(), found, nanoTime)
    }

    /**
     * While [fix] is under way: once its part is filled in ([SessionFix.done]) while [scanning],
     * the line says so for a moment, then [onDone] — the review again. A pause or a Back in that
     * moment only waits: the fix is watched again once scanning resumes.
     */
    suspend fun watchFix(scanning: () -> Boolean, onDone: () -> Unit) {
        val current = fix ?: return
        while (fix === current) {
            delay(FIX_TICK_MS)
            val fill = (current.status as? RescanStatus.Lined)?.fill ?: continue
            if (!scanning() || !current.done(fill)) continue
            delay(COVERED_HOLD_MS)
            if (fix === current && scanning()) {
                onDone()
                return
            }
        }
    }

    /** The pose the 3D view looks at [zone] from, in a view [verticalFovDegrees] high. */
    fun poseFor(zone: RescanZone, verticalFovDegrees: Double): OrbitPose? =
        result?.let { ScanReview.poseFor(zone, it, verticalFovDegrees) }

    companion object {
        private const val TAG = "ScanReview"
        private const val FOCUS_TICK_MS = 250L
        const val FIX_TICK_MS = 500L

        /** How long "Covered" stays up before the review comes back, ms: long enough to read. */
        const val COVERED_HOLD_MS = 1_200L
        private const val MS_PER_S = 1000f
    }
}

/**
 * Whether the review's 3D view may move from part to part by itself: not under reduced motion
 * ([LocalMotionEnabled]), nor with a screen reader on, whose user moves on with Next part.
 */
@Composable
internal fun rememberReviewCycles(): Boolean {
    val motion = LocalMotionEnabled.current
    val context = LocalContext.current
    val reader = remember(context) {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    }
    return motion && !reader
}

/** The amber frame round the part the review's 3D view is on. */
internal const val SCAN_REVIEW_FRAME_TAG = "ar_rerun_scan_review_frame"

/**
 * Review before you leave: the part the 3D view has flown to ([zone]), framed in amber where it
 * stands in the room, as [orbit] — the live view's camera — sees it this frame. The same frame
 * Fix now draws over the camera ([RescanGuide]), so the part looks the same in both.
 */
@Composable
internal fun ScanReviewFrame(orbit: ArDebugOrbitCamera, zone: RescanZone, modifier: Modifier = Modifier) {
    // The orbit is not snapshot state: a frame tick redraws the frame as the camera flies.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(orbit) { while (true) withFrameNanos { tick = it } }
    val corners = remember(zone) { SessionFix.SAME_FRAME.corners(zone.box) }
    val eye = remember { FloatArray(MATRIX) }
    val lens = remember { FloatArray(MATRIX) }
    val amber = SceneViewTokens.ArOverlay.accentGuidance
    Canvas(modifier.fillMaxSize().testTag(SCAN_REVIEW_FRAME_TAG)) {
        if (tick == 0L || size.width <= 0f || size.height <= 0f) return@Canvas
        val m = orbit.getTransform()
        // Column-major, as `Pose.toMatrix` writes the phone's eye.
        val columns = arrayOf(m.x, m.y, m.z, m.w)
        for (c in columns.indices) {
            eye[c * 4] = columns[c].x
            eye[c * 4 + 1] = columns[c].y
            eye[c * 4 + 2] = columns[c].z
            eye[c * 4 + 3] = columns[c].w
        }
        if (!orbit.customProjection(lens)) {
            EyeHandoff.orbitProjection(orbit.verticalFovDegrees, size.width / size.height, lens)
        }
        val edges = RescanGuidance.outline(corners, eye, lens, size.width, size.height)
        val stroke = FRAME_STROKE.toPx()
        for (k in edges.indices step EDGE_FLOATS) {
            drawLine(amber, Offset(edges[k], edges[k + 1]), Offset(edges[k + 2], edges[k + 3]), stroke, StrokeCap.Round)
        }
    }
}

private const val MATRIX = 16
private const val EDGE_FLOATS = 4
private val FRAME_STROKE = 3.dp
