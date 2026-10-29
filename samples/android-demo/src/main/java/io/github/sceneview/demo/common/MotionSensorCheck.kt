package io.github.sceneview.demo.common

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARMotionSensorHealth
import io.github.sceneview.ar.ARMotionSensorSample
import io.github.sceneview.ar.probeARMotionSensors
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.telemetry.ArFunnelLog
import kotlinx.coroutines.delay

/**
 * Where an AR session stands on the way to its first tracked frame — everything
 * [arStartupMessage] and [motionSensorProbeDue] decide on (#4522).
 *
 * @property framesArriving the session has delivered at least one camera frame.
 * @property everTracked the camera reached `TrackingState.TRACKING` at least once.
 * @property tracking the camera is tracking now.
 * @property failureReason ARCore's own reason for not tracking; `null` and `NONE` both mean
 *   it gave none.
 * @property waitedMillis how long, in the foreground, the session has been delivering frames
 *   without tracking and without a reason.
 */
data class ArStartup(
    val framesArriving: Boolean,
    val everTracked: Boolean,
    val tracking: Boolean,
    val failureReason: TrackingFailureReason?,
    val waitedMillis: Long,
) {
    /**
     * The silent stall: a live camera image, no tracking ever, and nothing from ARCore to
     * explain it. A session that tracked and lost it is not this — its coaching card says what
     * to do — and neither is one ARCore gives a reason for (a white wall, a dark room).
     */
    val neverStartedUnexplained: Boolean
        get() = framesArriving && !everTracked && !tracking &&
            (failureReason == null || failureReason == TrackingFailureReason.NONE)

    /** [neverStartedUnexplained] for [AR_NEVER_TRACKED_MS] — a working phone tracks in about a second. */
    val stalled: Boolean
        get() = neverStartedUnexplained && waitedMillis >= AR_NEVER_TRACKED_MS
}

/** What the AR screen says instead of "Getting ready". */
enum class ArStartupMessage {
    /** Nothing of its own: the screen stays exactly as it is. */
    NONE,

    /** "Restart your phone — its motion sensor isn't responding, and AR can't start without it." */
    RESTART_PHONE,
}

/**
 * How long a session may deliver frames without ever tracking before the motion sensors are
 * checked. The same ten seconds after which the funnel files `ArTrackingStalled` (#4512).
 */
const val AR_NEVER_TRACKED_MS: Long = ArFunnelLog.TRACKING_STALL_MS

/**
 * How many times one session listens to the motion sensors at most. A first measurement that
 * says nothing — the sensor service refused the listener, or had not started delivering — is
 * taken again [AR_NEVER_TRACKED_MS] later; after this many, the screen stays as it is.
 */
const val MOTION_SENSOR_MAX_PROBES = 3

/**
 * Whether this session still has a measurement to wait for: it never tracked and ARCore gives
 * no reason, and either nothing was measured yet or the last measurement said nothing
 * ([ARMotionSensorHealth.UNKNOWN]) and [MOTION_SENSOR_MAX_PROBES] is not reached. A verdict —
 * silent or delivering — is final, and so is a phone with no gyroscope ([last] `null` after a
 * measurement). Never while tracking works, nor once it has.
 *
 * @param probes how many times the sensors were listened to in this session.
 * @param last the verdict of the latest measurement.
 */
fun motionSensorProbeDue(startup: ArStartup, probes: Int, last: ARMotionSensorHealth?): Boolean =
    startup.neverStartedUnexplained && probes < MOTION_SENSOR_MAX_PROBES &&
        (probes == 0 || last == ARMotionSensorHealth.UNKNOWN)

/**
 * The one decision of #4522: the restart message only for a session that is [ArStartup.stalled]
 * **and** whose gyroscope was measured silent while the accelerometer delivered. Every other
 * outcome — no measurement yet, a gyroscope that delivers, a measurement that says nothing —
 * leaves the screen as it is.
 */
fun arStartupMessage(startup: ArStartup, sensors: ARMotionSensorHealth?): ArStartupMessage =
    if (startup.stalled && sensors == ARMotionSensorHealth.SILENT) {
        ArStartupMessage.RESTART_PHONE
    } else {
        ArStartupMessage.NONE
    }

/**
 * The last motion-sensor measurement of this process, for the bug report: the report says how
 * many events each sensor delivered instead of leaving a maintainer to ask for
 * `dumpsys sensorservice`. `null` until an AR session stalled and was checked.
 *
 * The measurement carries its age, and it is dropped as soon as an AR session tracks: a report
 * filed later does not present an old count as the phone's present state.
 */
object MotionSensorReport {
    @Volatile
    private var last: ARMotionSensorSample? = null

    @Volatile
    private var measuredAtMillis: Long = 0L

    /** The bug report's key for [line]. */
    const val KEY = "gyroscope"

    /** Keeps [sample] (or forgets the previous one, on `null`) as measured at [nowMillis]. */
    fun record(sample: ARMotionSensorSample?, nowMillis: Long = SystemClock.elapsedRealtime()) {
        last = sample
        measuredAtMillis = nowMillis
    }

    /** An AR session tracked: whatever was measured before no longer describes this phone. */
    fun clear() {
        last = null
    }

    /**
     * `0 events in 1 s (accelerometer: 204), measured 12 s ago`, or `null` when nothing was
     * measured since an AR session last tracked.
     */
    fun line(nowMillis: Long = SystemClock.elapsedRealtime()): String? {
        val sample = last ?: return null
        val age = ((nowMillis - measuredAtMillis) / 1_000).coerceAtLeast(0)
        return "${line(sample)}, measured $age s ago"
    }

    /** `0 events in 1 s (accelerometer: 204)`; a sensor that was not listened to reads `not listened to`. */
    fun line(sample: ARMotionSensorSample): String {
        val seconds = sample.windowMillis / 1_000.0
        val window = if (seconds % 1.0 == 0.0) seconds.toLong().toString() else seconds.toString()
        return "${count(sample.gyroscopeEvents)} in $window s " +
            "(accelerometer: ${count(sample.accelerometerEvents, unit = "")})"
    }

    private fun count(events: Int, unit: String = " events"): String =
        if (events < 0) "not listened to" else "$events$unit"
}

/**
 * The `qa_state` id that pins an AR screen on the restart message, so the card can be captured
 * on an emulator: `--ez qa_mode true --es qa_state motion_sensor_silent`. Honoured only in a
 * debug build under [qaStateOverridesAllowed]: `qa_mode` is an intent extra any app can send,
 * and the store build must never tell a working phone to restart.
 */
const val MOTION_SENSOR_SILENT_QA_STATE = "motion_sensor_silent"

/**
 * Whether [MOTION_SENSOR_SILENT_QA_STATE] is honoured: only in a debug build **and** under
 * [qaStateOverridesAllowed].
 *
 * @param qaState the launch's `qa_state`.
 * @param overridesAllowed [qaStateOverridesAllowed].
 * @param debugBuild `BuildConfig.DEBUG`; a parameter so the release answer is tested.
 */
fun motionSensorSilentForced(
    qaState: String?,
    overridesAllowed: Boolean,
    debugBuild: Boolean = BuildConfig.DEBUG,
): Boolean = debugBuild && overridesAllowed && qaState == MOTION_SENSOR_SILENT_QA_STATE

/** Whether QA pinned this screen on the restart message. */
@Composable
fun motionSensorSilentForced(): Boolean =
    motionSensorSilentForced(DemoSettings.qaDemoState, qaStateOverridesAllowed())

/**
 * The `qa_state` id that makes a **working** phone take the real measurement, so the part of
 * #4522 a healthy phone never reaches can be proven on one:
 * `--ez qa_mode true --es qa_state motion_sensor_measure`.
 *
 * Only the precondition is forced: [rememberMotionSensorSilent] reads the session as never
 * tracked and unexplained while the real session tracks behind it. Everything after that is
 * real — the [AR_NEVER_TRACKED_MS] wait in the foreground, the listeners on the phone's own
 * sensors, [MotionSensorReport], the verdict. On a healthy phone that is one registration of a
 * second in `dumpsys sensorservice`, a delivering verdict, no card, and the `gyroscope:` line
 * in the bug report.
 *
 * The session's own tracking does not drop that line while this state is on — the watch does
 * not see it — so the report can be opened and read after the measurement. It is dropped as
 * usual by the next AR session that tracks without this state.
 */
const val MOTION_SENSOR_MEASURE_QA_STATE = "motion_sensor_measure"

/**
 * Whether [MOTION_SENSOR_MEASURE_QA_STATE] is honoured: only in a debug build **and** under
 * [qaStateOverridesAllowed]. A release build ignores it even in QA mode. Debug builds are
 * installable too (the debug APK attached to GitHub releases, the `.qa` variant): there, under
 * `qa_mode` and this state, the effect is one second of listening per session, and never a
 * card while the gyroscope delivers.
 *
 * @param qaState the launch's `qa_state`.
 * @param overridesAllowed [qaStateOverridesAllowed].
 * @param debugBuild `BuildConfig.DEBUG`; a parameter so the release answer is tested.
 */
fun motionSensorMeasureForced(
    qaState: String?,
    overridesAllowed: Boolean,
    debugBuild: Boolean = BuildConfig.DEBUG,
): Boolean = debugBuild && overridesAllowed && qaState == MOTION_SENSOR_MEASURE_QA_STATE

/**
 * What [rememberMotionSensorSilent] reads of the session: the session itself, or — with
 * [measureForced] — one that never tracked and that ARCore does not explain, whatever the real
 * one does. Frames, the wait and the measurement are never forced.
 */
fun watchedArStartup(
    framesArriving: Boolean,
    everTracked: Boolean,
    tracking: Boolean,
    failureReason: TrackingFailureReason?,
    waitedMillis: Long,
    measureForced: Boolean,
): ArStartup = if (measureForced) {
    ArStartup(framesArriving, everTracked = false, tracking = false, failureReason = null, waitedMillis)
} else {
    ArStartup(framesArriving, everTracked || tracking, tracking, failureReason, waitedMillis)
}

/**
 * Watches one AR session's start and returns `true` once it is known that the phone's
 * gyroscope delivers nothing (#4522) — the session then shows a restart message instead of a
 * "Getting ready" card that would never leave. It returns `false` again, for good, the moment
 * the session tracks.
 *
 * The sensors are listened to for one second, and only after the session has delivered frames
 * for [AR_NEVER_TRACKED_MS] in the foreground without tracking and without a reason from
 * ARCore. A session that tracks within those ten seconds — the normal case, within one —
 * never registers a sensor listener, and tracking that starts during the measurement cancels it
 * and unregisters. Time in the
 * background does not count: the wait starts over on return. A measurement that says nothing is
 * taken again after the same wait, [MOTION_SENSOR_MAX_PROBES] times at most.
 *
 * @param framesArriving the session delivered a camera frame.
 * @param tracking the camera is tracking now.
 * @param failureReason ARCore's reason for not tracking, if it gave one.
 * @param onSilent called when a measurement finds the gyroscope silent — the caller's funnel
 *   records it ([ArFunnelLog.motionSensorSilent], once per session).
 * @param probe the measurement itself; the tests replace it.
 */
@Composable
fun rememberMotionSensorSilent(
    framesArriving: Boolean,
    tracking: Boolean,
    failureReason: TrackingFailureReason?,
    onSilent: () -> Unit,
    probe: suspend (Context) -> ARMotionSensorSample? = { probeARMotionSensors(it) },
): Boolean {
    var everTracked by remember { mutableStateOf(false) }
    var waitedMillis by remember { mutableLongStateOf(0L) }
    var probes by remember { mutableIntStateOf(0) }
    var health by remember { mutableStateOf<ARMotionSensorHealth?>(null) }
    val measureForced = motionSensorMeasureForced(DemoSettings.qaDemoState, qaStateOverridesAllowed())
    SideEffect {
        // Not under the QA measurement: its session does track, and dropping the count here
        // would leave nothing to read in the bug report it exists to fill.
        if (tracking && !everTracked && !measureForced) {
            everTracked = true
            MotionSensorReport.clear()
        }
    }

    val startup =
        watchedArStartup(framesArriving, everTracked, tracking, failureReason, waitedMillis, measureForced)
    val context = LocalContext.current.applicationContext
    val currentProbe by rememberUpdatedState(probe)
    val currentOnSilent by rememberUpdatedState(onSilent)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val due = motionSensorProbeDue(startup, probes, health)
    LaunchedEffect(due, probes, lifecycle) {
        if (!due) return@LaunchedEffect
        // Foreground time only, and from scratch after a trip to the background: the session
        // itself pauses there, and a paused session is not a stalled one. The pause cancels the
        // wait and the measurement from the lifecycle callback itself, not from a recomposition
        // that a paused screen may never draw: no listener is left registered in the background.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(AR_NEVER_TRACKED_MS)
            val sample = currentProbe(context)
            MotionSensorReport.record(sample)
            if (sample?.health == ARMotionSensorHealth.SILENT) currentOnSilent()
            // Written last: a measurement that says nothing restarts this effect for the next one.
            waitedMillis = AR_NEVER_TRACKED_MS
            health = sample?.health
            probes++
        }
    }
    return motionSensorSilentForced() ||
        arStartupMessage(startup, health) == ArStartupMessage.RESTART_PHONE
}
