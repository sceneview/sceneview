package io.github.sceneview.demo.common

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.feedback.BugReportInfo
import io.github.sceneview.demo.feedback.formatReportFile
import io.github.sceneview.demo.feedback.ReportScreen
import io.github.sceneview.demo.feedback.captureBugReportMetadata
import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.ArFunnelLog
import io.github.sceneview.demo.telemetry.DemoAnalytics
import io.github.sceneview.demo.telemetry.LocalSampleId
import io.github.sceneview.demo.telemetry.UserProperty
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

/** Real sensor registrations through the common demo watch, without a native ARCore session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoMotionSensorsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val service = shadowOf(manager)
    private val sensors = DemoMotionSensors()
    private val events = mutableListOf<AnalyticsEvent>()
    private val sink = object : DemoAnalytics {
        override fun log(event: AnalyticsEvent) { events += event }
        override fun setUserProperty(property: UserProperty, value: String) = Unit
        override fun setCollectionEnabled(enabled: Boolean) = Unit
        override fun resetData() = Unit
    }
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var silent = false

    @Before fun setUp() {
        MotionSensorReport.clear()
        DemoSettings.qaMode = false
        DemoSettings.qaDemoState = null
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.mainClock.autoAdvance = false
        service.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
        service.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
    }

    @After fun tearDown() {
        DemoSettings.qaMode = false
        DemoSettings.qaDemoState = null
        MotionSensorReport.clear()
    }

    @Test fun `silent sensor delivery reaches the verdict funnel and report exactly once`() {
        watch()
        sensors.onFrame(TrackingState.PAUSED, TrackingFailureReason.NONE)
        advance(9_900)
        assertTrue(service.listeners.isEmpty())
        assertFalse(silent)
        advance(300)
        assertEquals(1, service.listeners.size)
        deliver(Sensor.TYPE_ACCELEROMETER, 200)
        advance(1_100)

        assertTrue(silent)
        assertTrue("listener outlived its one-second window", service.listeners.isEmpty())
        assertEquals(listOf(AnalyticsEvent.ArSessionFailed("ar-plane-node", "motion_sensor_silent")), events)
        val metadata = captureBugReportMetadata(context, ReportScreen(demoId = "ar-plane-node"))
        assertTrue(metadata.getValue("gyroscope").startsWith("0 events in 1 s (accelerometer: 200)"))
        val reportText = formatReportFile(BugReportInfo(metadata, emptyList()), "AR never starts")
        assertTrue(reportText.contains("gyroscope: 0 events in 1 s (accelerometer: 200)"))
        advance(60_000)
        assertTrue(silent)
        assertEquals(1, events.size)
        assertTrue(service.listeners.isEmpty())
    }

    @Test fun `a stationary phone that delivers gyro events leaves guidance alone`() {
        watch()
        sensors.onFrame(TrackingState.PAUSED, TrackingFailureReason.NONE)
        advance(10_200)
        deliver(Sensor.TYPE_ACCELEROMETER, 50)
        // A stationary gyroscope still sends zero-valued samples.
        deliver(Sensor.TYPE_GYROSCOPE, 50)
        advance(1_100)
        assertFalse(silent)
        assertTrue(events.isEmpty())
        assertTrue(service.listeners.isEmpty())
        assertTrue(MotionSensorReport.line()!!.startsWith("50 events in 1 s (accelerometer: 50)"))
    }

    @Test fun `one tracked frame between recompositions permanently rules out a silent startup`() {
        watch()
        sensors.onFrame(TrackingState.TRACKING, TrackingFailureReason.NONE)
        sensors.onFrame(TrackingState.PAUSED, TrackingFailureReason.NONE)
        advance(60_000)
        assertFalse(silent)
        assertTrue(events.isEmpty())
        assertTrue(service.listeners.isEmpty())
    }

    @Test fun `navigation lifecycle cancels a real registration without another composition frame`() {
        watch()
        sensors.onFrame(TrackingState.PAUSED, TrackingFailureReason.NONE)
        advance(10_200)
        assertEquals(1, service.listeners.size)
        owner.registry.currentState = Lifecycle.State.CREATED
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.listeners.isEmpty())
        assertTrue(events.isEmpty())
        assertNull(MotionSensorReport.line())
    }

    @Test fun `no camera frame never starts a probe`() {
        watch()
        advance(60_000)
        assertTrue(service.listeners.isEmpty())
        assertTrue(events.isEmpty())
        assertFalse(silent)
    }

    private fun watch() {
        val funnel = ArFunnelLog(analytics = { sink })
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalSampleId provides "ar-plane-node") {
                silent = rememberDemoMotionSensorSilent(sensors, funnel)
            }
        }
        compose.waitForIdle()
    }

    private fun deliver(type: Int, count: Int) {
        val sensor = manager.getDefaultSensor(type)
        repeat(count) {
            service.sendSensorEventToListeners(
                SensorEventBuilder.newBuilder(sensor, floatArrayOf(0f, 0f, 0f)).build(),
            )
        }
    }

    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(0)
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
}
