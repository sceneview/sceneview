package io.github.sceneview.demo.common

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARMotionSensorSample
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import java.util.concurrent.TimeUnit

/**
 * The stateful half of #4522, [rememberMotionSensorSilent], on the Compose test clock: when
 * the sensors are listened to, what cancels that, what a trip to the background does to the
 * wait, how often the funnel hears about it, and when the card leaves.
 *
 * The measurement is a fake that takes its one second on the same clock, except in the last
 * test, where the real one registers on Robolectric's sensor service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionSensorWatchTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var framesArriving by mutableStateOf(true)
    private var tracking by mutableStateOf(false)
    private var failureReason by mutableStateOf<TrackingFailureReason?>(null)

    /** What the composable last returned: whether the restart card is up. */
    private var cardShown = false
    private var silentEvents = 0
    private var probeCalls = 0
    private var probesCancelled = 0

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        MotionSensorReport.clear()
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        DemoSettings.qaMode = false
        DemoSettings.qaDemoState = null
        MotionSensorReport.clear()
    }

    // ── The wait and what cancels it ──────────────────────────────────────────────────

    @Test
    fun `a silent gyroscope puts the card up eleven seconds in, and is reported once`() {
        watch(measuring(SILENT))

        advance(9_900)
        assertEquals("the sensors were listened to before the ten seconds were up", 0, probeCalls)
        advance(200)
        assertEquals(1, probeCalls)
        assertFalse("the card is up before the measurement is over", cardShown)
        advance(1_100)
        assertTrue(cardShown)
        assertEquals(1, silentEvents)

        // A minute of the same stalled session: no second measurement, no second event.
        advance(60_000)
        assertEquals(1, probeCalls)
        assertEquals(1, silentEvents)
        assertTrue(cardShown)
    }

    @Test
    fun `tracking that starts at 9,9 seconds means the sensors are never listened to`() {
        watch(measuring(SILENT))
        advance(9_900)
        tracking = true
        advance(30_000)

        assertEquals(0, probeCalls)
        assertEquals(0, silentEvents)
        assertFalse(cardShown)
    }

    @Test
    fun `tracking that starts during the measurement cancels it`() {
        watch(measuring(SILENT))
        advance(10_500)
        assertEquals(1, probeCalls)
        tracking = true
        advance(5_000)

        assertEquals("the measurement ran on after tracking started", 1, probesCancelled)
        assertEquals(0, silentEvents)
        assertFalse(cardShown)
        assertNull("a cancelled measurement left a line for the bug report", MotionSensorReport.line())
    }

    @Test
    fun `a reason from ARCore during the wait means the sensors are not listened to`() {
        watch(measuring(SILENT))
        advance(5_000)
        failureReason = TrackingFailureReason.INSUFFICIENT_FEATURES
        advance(30_000)

        assertEquals(0, probeCalls)
        assertFalse(cardShown)
    }

    // ── The background ────────────────────────────────────────────────────────────────

    @Test
    fun `a trip to the background starts the wait over`() {
        watch(measuring(SILENT))
        advance(6_000)
        owner.registry.currentState = Lifecycle.State.STARTED
        // A minute away counts for nothing.
        advance(60_000)
        assertEquals(0, probeCalls)

        owner.registry.currentState = Lifecycle.State.RESUMED
        // Six seconds before the pause and nine after it are not ten in a row.
        advance(9_000)
        assertEquals(0, probeCalls)
        advance(1_200)
        assertEquals(1, probeCalls)
    }

    @Test
    fun `leaving during the measurement cancels it, and it is taken again on return`() {
        watch(measuring(SILENT))
        advance(10_500)
        owner.registry.currentState = Lifecycle.State.STARTED
        advance(5_000)
        assertEquals(1, probesCancelled)
        assertEquals(0, silentEvents)

        owner.registry.currentState = Lifecycle.State.RESUMED
        advance(11_500)
        assertEquals(2, probeCalls)
        assertEquals(1, silentEvents)
        assertTrue(cardShown)
    }

    @Test
    fun `the verdict survives the background, and is neither measured nor reported again`() {
        watch(measuring(SILENT))
        advance(11_500)
        assertTrue(cardShown)

        owner.registry.currentState = Lifecycle.State.STARTED
        advance(5_000)
        owner.registry.currentState = Lifecycle.State.RESUMED
        advance(60_000)

        assertTrue(cardShown)
        assertEquals(1, probeCalls)
        assertEquals(1, silentEvents)
    }

    // ── The QA measurement on a working phone ─────────────────────────────────────────

    @Test
    fun `the measure state takes the real path on a session that tracks, and keeps its line`() {
        // The state exists in debug builds only; the release unit tests run the next test.
        assumeTrue("the measure state is a debug-build seam", BuildConfig.DEBUG)
        DemoSettings.qaMode = true
        DemoSettings.qaDemoState = MOTION_SENSOR_MEASURE_QA_STATE
        tracking = true
        watch(measuring(DELIVERING))

        advance(9_900)
        assertEquals("the sensors were listened to before the ten seconds were up", 0, probeCalls)
        advance(1_600)
        assertEquals(1, probeCalls)
        assertFalse("a delivering gyroscope raised the card", cardShown)
        assertEquals(0, silentEvents)
        assertTrue(
            "tracking dropped the line the state exists to produce: ${MotionSensorReport.line()}",
            MotionSensorReport.line()?.startsWith("198 events in 1 s (accelerometer: 301)") == true,
        )

        // The verdict is final: no second measurement, and the line is still there to read.
        advance(60_000)
        assertEquals(1, probeCalls)
        assertTrue(MotionSensorReport.line() != null)
    }

    @Test
    fun `a release build ignores the measure state even in QA mode`() {
        assumeFalse("only a release build ignores the state under QA mode", BuildConfig.DEBUG)
        DemoSettings.qaMode = true
        DemoSettings.qaDemoState = MOTION_SENSOR_MEASURE_QA_STATE
        tracking = true
        watch(measuring(DELIVERING))

        advance(60_000)
        assertEquals("a release build measured a session that tracks", 0, probeCalls)
        assertNull(MotionSensorReport.line())
        assertFalse(cardShown)
    }

    @Test
    fun `the measure state does nothing without QA mode`() {
        DemoSettings.qaMode = false
        DemoSettings.qaDemoState = MOTION_SENSOR_MEASURE_QA_STATE
        tracking = true
        watch(measuring(DELIVERING))

        advance(60_000)
        assertEquals("a tracking session was measured outside QA mode", 0, probeCalls)
        assertNull(MotionSensorReport.line())
        assertFalse(cardShown)
    }

    // ── The pinned restart card ───────────────────────────────────────────────────────

    @Test
    fun `a debug build under QA mode pins the restart card without measuring`() {
        assumeTrue("the pinned card is a debug-build seam", BuildConfig.DEBUG)
        DemoSettings.qaMode = true
        DemoSettings.qaDemoState = MOTION_SENSOR_SILENT_QA_STATE
        watch(measuring(DELIVERING))

        advance(5_000)
        assertTrue("the pinned state did not raise the card", cardShown)
        assertEquals(0, probeCalls)
        assertNull(MotionSensorReport.line())
        assertEquals(0, silentEvents)
    }

    @Test
    fun `a release build ignores the pinned restart card even in QA mode`() {
        assumeFalse("only a release build ignores the state under QA mode", BuildConfig.DEBUG)
        DemoSettings.qaMode = true
        DemoSettings.qaDemoState = MOTION_SENSOR_SILENT_QA_STATE
        watch(measuring(DELIVERING))

        advance(5_000)
        assertFalse("a release build told a phone to restart on an intent extra", cardShown)
        assertNull(MotionSensorReport.line())
        // The real path is all that is left: a delivering gyroscope, and still no card.
        advance(60_000)
        assertFalse(cardShown)
        assertEquals(0, silentEvents)
    }

    @Test
    fun `the pinned restart card does nothing without QA mode`() {
        DemoSettings.qaMode = false
        DemoSettings.qaDemoState = MOTION_SENSOR_SILENT_QA_STATE
        watch(measuring(DELIVERING))

        advance(5_000)
        assertFalse(cardShown)
        assertNull(MotionSensorReport.line())
    }

    // ── The card leaves ───────────────────────────────────────────────────────────────

    @Test
    fun `the card leaves when tracking starts, and does not come back when it is lost`() {
        watch(measuring(SILENT))
        advance(11_500)
        assertTrue(cardShown)
        assertTrue(MotionSensorReport.line() != null)

        tracking = true
        advance(100)
        assertFalse("the card stayed up over a tracking session", cardShown)
        assertNull("the bug report still carries the count of a session that tracks", MotionSensorReport.line())

        tracking = false
        advance(60_000)
        assertFalse(cardShown)
        assertEquals(1, probeCalls)
        assertEquals(1, silentEvents)
    }

    // ── A measurement that says nothing ───────────────────────────────────────────────

    @Test
    fun `a measurement with no verdict is taken again, and the second one counts`() {
        watch(measuring(NOT_LISTENED, SILENT))
        advance(11_500)
        assertEquals(1, probeCalls)
        assertFalse("a refused listener put the restart card up", cardShown)
        assertEquals(0, silentEvents)

        advance(11_500)
        assertEquals(2, probeCalls)
        assertTrue(cardShown)
        assertEquals(1, silentEvents)
    }

    @Test
    fun `three measurements with no verdict, and the screen is left alone`() {
        watch(measuring(NOT_LISTENED))
        advance(120_000)

        assertEquals(MOTION_SENSOR_MAX_PROBES, probeCalls)
        assertFalse(cardShown)
        assertEquals(0, silentEvents)
    }

    @Test
    fun `a gyroscope that delivers is measured once and the screen is left alone`() {
        watch(measuring(ARMotionSensorSample(198, 204, 1_000)))
        advance(120_000)

        assertEquals(1, probeCalls)
        assertFalse(cardShown)
        assertEquals(0, silentEvents)
    }

    // ── The real measurement ──────────────────────────────────────────────────────────

    @Test
    fun `no sensor listener survives a session that starts tracking mid-measurement`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val sensors = shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))

        watch(probe = null)
        advance(9_900)
        assertTrue("a listener was registered during the wait", sensors.listeners.isEmpty())
        advance(600)
        assertEquals("the measurement did not register its listener", 1, sensors.listeners.size)

        tracking = true
        advance(100)
        assertTrue("a sensor listener outlived the measurement", sensors.listeners.isEmpty())
    }

    @Test
    fun `a pause with no frame after it leaves no sensor listener behind`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val sensors = shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))

        watch(probe = null)
        advance(10_500)
        assertEquals("the measurement did not register its listener", 1, sensors.listeners.size)

        // The pause, and then nothing: no frame, no recomposition, no tick of the Compose clock.
        owner.registry.currentState = Lifecycle.State.STARTED
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("a sensor listener is still registered in the background", sensors.listeners.isEmpty())

        owner.registry.currentState = Lifecycle.State.CREATED
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun `a pause with no frame after it cancels the wait and the measurement`() {
        watch(measuring(SILENT))
        advance(10_500)
        assertEquals(1, probeCalls)

        owner.registry.currentState = Lifecycle.State.STARTED
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the measurement ran on in the background", 1, probesCancelled)
    }

    // ── Harness ───────────────────────────────────────────────────────────────────────

    /** A measurement that takes its second on the test clock, then returns [samples] in turn. */
    private fun measuring(vararg samples: ARMotionSensorSample?): suspend (Context) -> ARMotionSensorSample? = {
        val call = probeCalls++
        try {
            delay(1_000)
        } catch (cancelled: CancellationException) {
            probesCancelled++
            throw cancelled
        }
        samples[minOf(call, samples.lastIndex)]
    }

    /** Composes the watch; a `null` [probe] leaves the real measurement in. */
    private fun watch(probe: (suspend (Context) -> ARMotionSensorSample?)?) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                cardShown = if (probe == null) {
                    rememberMotionSensorSilent(framesArriving, tracking, failureReason, onSilent = { silentEvents++ })
                } else {
                    rememberMotionSensorSilent(
                        framesArriving = framesArriving,
                        tracking = tracking,
                        failureReason = failureReason,
                        onSilent = { silentEvents++ },
                        probe = probe,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun advance(millis: Long) {
        // What the test wrote to `tracking` and its neighbours reaches the composition now, not
        // on a main-looper turn the paused clock never takes.
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeBy(0)
        // The wait and the measurement run under repeatOnLifecycle, on the main looper's clock;
        // the frames that carry their result to the screen run on Compose's.
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    private companion object {
        /** The measured Pixel 9: a gyroscope with nothing to say, an accelerometer at 200 Hz. */
        val SILENT = ARMotionSensorSample(0, 204, 1_000)

        /** A working phone: both sensors deliver. */
        val DELIVERING = ARMotionSensorSample(198, 301, 1_000)

        /** What the SDK returns when the sensor service refuses the gyroscope's listener. */
        val NOT_LISTENED = ARMotionSensorSample(-1, 0, 0)
    }
}
