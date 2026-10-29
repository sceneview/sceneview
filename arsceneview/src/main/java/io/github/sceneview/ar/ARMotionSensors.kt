package io.github.sceneview.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Whether the phone's motion sensors deliver what ARCore needs to start tracking — the verdict
 * of [ARMotionSensorSample.health].
 *
 * ARCore fuses the camera image with the gyroscope and the accelerometer. A gyroscope that
 * delivers nothing leaves the camera in `TrackingState.PAUSED` with `TrackingFailureReason.NONE`
 * on every frame: no exception, no `onSessionFailure`, and a camera image that looks alive
 * (#4522). The sensor stack recovers when the phone restarts, and nothing an app does helps.
 */
enum class ARMotionSensorHealth {
    /** The gyroscope delivered samples: whatever keeps AR from tracking, it is not this. */
    DELIVERING,

    /**
     * The accelerometer delivered samples and the gyroscope, over the same window, delivered
     * none. AR cannot track until the phone is restarted.
     */
    SILENT,

    /**
     * No verdict: the window was too short, or the accelerometer delivered too little to tell a
     * silent gyroscope from a sensor stack that has not started delivering yet. Treat it as
     * [DELIVERING] — never tell a person to restart their phone on this.
     */
    UNKNOWN,
}

/**
 * How many events each motion sensor delivered over one listening window — what
 * [probeARMotionSensors] measures.
 *
 * A plain class, not a `data class`: it can gain a property without breaking a caller.
 *
 * @property gyroscopeEvents `Sensor.TYPE_GYROSCOPE` events received, or a negative number when
 *   the gyroscope was not listened to at all — the sensor service refused the registration.
 * @property accelerometerEvents `Sensor.TYPE_ACCELEROMETER` events received over the same
 *   window, or a negative number when that registration was refused.
 * @property windowMillis how long both sensors were listened to; `0` when a registration was
 *   refused.
 */
class ARMotionSensorSample(
    val gyroscopeEvents: Int,
    val accelerometerEvents: Int,
    val windowMillis: Long,
) {
    /**
     * The verdict for this sample:
     *
     * - any gyroscope event → [ARMotionSensorHealth.DELIVERING];
     * - no gyroscope event while the accelerometer delivered at least 5 over a window of at
     *   least 500 ms → [ARMotionSensorHealth.SILENT];
     * - anything else — a negative count included — → [ARMotionSensorHealth.UNKNOWN].
     */
    val health: ARMotionSensorHealth
        get() = arMotionSensorHealth(gyroscopeEvents, accelerometerEvents, windowMillis)

    override fun equals(other: Any?): Boolean = other is ARMotionSensorSample &&
        gyroscopeEvents == other.gyroscopeEvents &&
        accelerometerEvents == other.accelerometerEvents &&
        windowMillis == other.windowMillis

    override fun hashCode(): Int =
        (gyroscopeEvents * 31 + accelerometerEvents) * 31 + windowMillis.hashCode()

    override fun toString(): String =
        "ARMotionSensorSample(gyroscopeEvents=$gyroscopeEvents, " +
            "accelerometerEvents=$accelerometerEvents, windowMillis=$windowMillis)"
}

/**
 * The verdict behind [ARMotionSensorSample.health], over two event counts and the window they
 * were counted in. Pure: no Android object is read.
 *
 * - any gyroscope event → [ARMotionSensorHealth.DELIVERING];
 * - no gyroscope event while the accelerometer delivered at least 5 over a window of at least
 *   500 ms → [ARMotionSensorHealth.SILENT];
 * - anything else — a negative count included, which is how a sensor that was never listened
 *   to is reported — → [ARMotionSensorHealth.UNKNOWN].
 *
 * The accelerometer is the control: it sits in the same IMU and goes through the same sensor
 * service, so a window in which it delivered is a window in which a working gyroscope would
 * have delivered too. A phone lying still on a desk is not a silent gyroscope — a resting
 * gyroscope still reports its (near-zero) rotation rate at the requested rate.
 */
internal fun arMotionSensorHealth(
    gyroscopeEvents: Int,
    accelerometerEvents: Int,
    windowMillis: Long,
): ARMotionSensorHealth = when {
    gyroscopeEvents > 0 -> ARMotionSensorHealth.DELIVERING
    gyroscopeEvents < 0 -> ARMotionSensorHealth.UNKNOWN
    windowMillis < AR_MOTION_SENSOR_MIN_WINDOW_MS -> ARMotionSensorHealth.UNKNOWN
    accelerometerEvents < AR_MOTION_SENSOR_MIN_REFERENCE_EVENTS -> ARMotionSensorHealth.UNKNOWN
    else -> ARMotionSensorHealth.SILENT
}

/** The shortest window [arMotionSensorHealth] accepts a [ARMotionSensorHealth.SILENT] verdict on. */
internal const val AR_MOTION_SENSOR_MIN_WINDOW_MS: Long = 500L

/**
 * The fewest accelerometer events that prove the sensor service was delivering during the
 * window. A handful, not one: a single event can be the value the service replays on
 * registration.
 */
internal const val AR_MOTION_SENSOR_MIN_REFERENCE_EVENTS: Int = 5

/** The default listening window of [probeARMotionSensors]: one second. */
internal const val AR_MOTION_SENSOR_PROBE_WINDOW_MS: Long = 1_000L

/** The count [probeARMotionSensors] reports for a sensor whose registration was refused. */
internal const val AR_MOTION_SENSOR_NOT_LISTENED: Int = -1

/**
 * Listens to the gyroscope and the accelerometer for [windowMillis] and returns how many events
 * each delivered, or `null` when the phone has no gyroscope or no accelerometer (such a phone
 * is not ARCore-supported in the first place).
 *
 * Call it when an AR session has been delivering camera frames for a while — some ten seconds —
 * without ever tracking and without a `TrackingFailureReason`; read
 * [ARMotionSensorSample.health] and, on [ARMotionSensorHealth.SILENT], tell the person to
 * restart their phone. SceneView draws nothing for it and reports no session failure: ARCore
 * itself reports none.
 *
 * Wait and listen in the foreground only — a paused session is not a stalled one, and a
 * backgrounded app is delivered no sensor event:
 *
 * ```kotlin
 * val lifecycle = LocalLifecycleOwner.current.lifecycle
 * LaunchedEffect(neverTracked, lifecycle) {
 *     if (!neverTracked) return@LaunchedEffect
 *     lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
 *         delay(10_000)
 *         val sample = probeARMotionSensors(context) ?: return@repeatOnLifecycle
 *         restartPhoneCard = sample.health == ARMotionSensorHealth.SILENT
 *     }
 * }
 * ```
 *
 * Both listeners are unregistered when the window ends, and when the calling coroutine is
 * cancelled: no sensor registration outlives the call. Do not run it while tracking works —
 * it has nothing to say then.
 *
 * When the sensor service refuses either registration, nothing is listened to and the sample
 * comes back at once with a negative count for the refused sensor: its
 * [health][ARMotionSensorSample.health] is [ARMotionSensorHealth.UNKNOWN], never
 * [ARMotionSensorHealth.SILENT] — a gyroscope that was not listened to has not been heard silent.
 *
 * @param context any context; its application context is used.
 * @param windowMillis how long to listen, one second by default. Shorter than 500 ms can only
 *   ever yield [ARMotionSensorHealth.UNKNOWN] or [ARMotionSensorHealth.DELIVERING].
 */
suspend fun probeARMotionSensors(
    context: Context,
    windowMillis: Long = AR_MOTION_SENSOR_PROBE_WINDOW_MS,
): ARMotionSensorSample? {
    val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        ?: return null
    return probeARMotionSensors(manager, windowMillis) { listener, sensor ->
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }
}

/**
 * [probeARMotionSensors] over a [manager], with the registration call as a parameter so a test
 * can refuse one sensor and accept the other.
 */
internal suspend fun probeARMotionSensors(
    manager: SensorManager,
    windowMillis: Long,
    register: (SensorEventListener, Sensor) -> Boolean,
): ARMotionSensorSample? {
    val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
    val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
    val gyroscopeEvents = AtomicInteger()
    val accelerometerEvents = AtomicInteger()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> gyroscopeEvents.incrementAndGet()
                Sensor.TYPE_ACCELEROMETER -> accelerometerEvents.incrementAndGet()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    try {
        // The accelerometer first, so the control never listens for less time than the sensor
        // it vouches for.
        val accelerometerListened = register(listener, accelerometer)
        val gyroscopeListened = register(listener, gyroscope)
        if (!accelerometerListened || !gyroscopeListened) {
            // A refused gyroscope next to a listened accelerometer would count 0 against N —
            // the very signature of a silent gyroscope, on a phone that may be healthy.
            return ARMotionSensorSample(
                gyroscopeEvents = if (gyroscopeListened) 0 else AR_MOTION_SENSOR_NOT_LISTENED,
                accelerometerEvents = if (accelerometerListened) 0 else AR_MOTION_SENSOR_NOT_LISTENED,
                windowMillis = 0L,
            )
        }
        delay(windowMillis)
    } finally {
        manager.unregisterListener(listener)
    }
    return ARMotionSensorSample(gyroscopeEvents.get(), accelerometerEvents.get(), windowMillis)
}
