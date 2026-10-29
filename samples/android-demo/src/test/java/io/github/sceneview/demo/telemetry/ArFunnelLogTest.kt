package io.github.sceneview.demo.telemetry

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import io.github.sceneview.ar.ARSessionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AR funnel events fed from ARSceneView's callbacks: what repeats and what logs once. */
class ArFunnelLogTest {

    private val events = mutableListOf<AnalyticsEvent>()
    private val nonFatals = mutableListOf<Throwable>()
    private var nowMillis = 0L
    private val sink = object : DemoAnalytics {
        override fun log(event: AnalyticsEvent) {
            events += event
        }
        override fun recordNonFatal(throwable: Throwable) {
            nonFatals += throwable
        }
        override fun setUserProperty(property: UserProperty, value: String) = Unit
        override fun setCollectionEnabled(enabled: Boolean) = Unit
        override fun resetData() = Unit
    }
    private val funnel =
        ArFunnelLog({ sink }, { nowMillis }, { "1.57.0" }).apply { sampleId = "ar-placement" }

    /** Delivers one frame every 16 ms for [millis], none of them tracking. */
    private fun untrackedFramesFor(millis: Long) {
        val until = nowMillis + millis
        while (nowMillis <= until) {
            funnel.frameTracked(TrackingState.PAUSED)
            nowMillis += 16
        }
    }

    @Test
    fun `ready and first placement log once, tracking losses every time`() {
        funnel.sessionCreated()
        funnel.frameTracked(TrackingState.PAUSED)
        funnel.trackingFailureChanged(TrackingFailureReason.INSUFFICIENT_LIGHT)
        funnel.frameTracked(TrackingState.TRACKING)
        funnel.trackingFailureChanged(null)
        funnel.placed()
        funnel.trackingFailureChanged(TrackingFailureReason.EXCESSIVE_MOTION)
        funnel.frameTracked(TrackingState.PAUSED)
        funnel.frameTracked(TrackingState.TRACKING)
        funnel.placed()
        assertEquals(
            listOf(
                AnalyticsEvent.ArSessionCreated("ar-placement"),
                AnalyticsEvent.ArTrackingLost("ar-placement", "insufficient_light"),
                AnalyticsEvent.ArTrackingReady("ar-placement"),
                AnalyticsEvent.ArFirstPlacement("ar-placement"),
                AnalyticsEvent.ArTrackingLost("ar-placement", "excessive_motion"),
            ),
            events,
        )
    }

    @Test
    fun `a session that tracks at once still logs ready - no failure callback needed`() {
        // onTrackingFailureChanged only fires on a change of reason: a session that is
        // TRACKING from its first frame never calls it, and ready must not depend on it.
        funnel.sessionCreated()
        repeat(120) { funnel.frameTracked(TrackingState.TRACKING) }
        assertEquals(
            listOf(AnalyticsEvent.ArSessionCreated("ar-placement"), AnalyticsEvent.ArTrackingReady("ar-placement")),
            events,
        )
    }

    @Test
    fun `session failures are reported as snake_case codes`() {
        val cases = mapOf(
            ARSessionFailure.DeviceNotCompatible(UnavailableDeviceNotCompatibleException()) to "device_not_compatible",
            ARSessionFailure.ArCoreNotInstalled(UnavailableArcoreNotInstalledException()) to "arcore_not_installed",
            ARSessionFailure.CameraNotAvailable(CameraNotAvailableException()) to "camera_not_available",
            ARSessionFailure.Other(IllegalStateException("boom")) to "unknown",
        )
        cases.forEach { (failure, code) -> assertEquals(code, ArFunnelLog.failureReason(failure)) }
        funnel.sessionFailed(cases.keys.first())
        assertEquals(AnalyticsEvent.ArSessionFailed("ar-placement", "device_not_compatible"), events.single())
    }

    @Test
    fun `a failure ARCore retries on resume logs once per reason`() {
        // create() fails at ON_CREATE, then resume() finds no session and fails again: one
        // attempt, one event. A different reason on a later retry is new information.
        repeat(3) { funnel.sessionFailed(ARSessionFailure.from(IllegalStateException("fatal"))) }
        funnel.sessionFailed(ARSessionFailure.from(CameraNotAvailableException()))
        funnel.sessionFailed(ARSessionFailure.from(CameraNotAvailableException()))
        assertEquals(
            listOf(
                AnalyticsEvent.ArSessionFailed("ar-placement", "unknown"),
                AnalyticsEvent.ArSessionFailed("ar-placement", "camera_not_available"),
            ),
            events,
        )
    }

    @Test
    fun `a silent gyroscope logs one session failure, however often it is reported`() {
        // #4522: the screen that measured it can be recomposed, paused and resumed.
        repeat(3) { funnel.motionSensorSilent() }
        assertEquals(
            listOf<AnalyticsEvent>(AnalyticsEvent.ArSessionFailed("ar-placement", "motion_sensor_silent")),
            events,
        )
        assertEquals(emptyList<Throwable>(), nonFatals)
        assertTrue(ArFunnelLog.MOTION_SENSOR_SILENT_REASON.matches(Regex("[a-z][a-z0-9_]*")))
    }

    @Test
    fun `every code fits GA4 - snake_case and at most 100 characters`() {
        val codes = listOf(
            ARSessionFailure.from(UnavailableDeviceNotCompatibleException()),
            ARSessionFailure.from(CameraNotAvailableException()),
            ARSessionFailure.from(IllegalArgumentException()),
        ).map(ArFunnelLog::failureReason)
        codes.forEach { assertTrue(it, it.matches(Regex("[a-z][a-z0-9_]*")) && it.length <= AnalyticsEvent.MAX_VALUE) }
    }

    @Test
    fun `a failure with no code of its own also files its exception, once`() {
        // #4510: `unknown` says an AR session failed and nothing about why.
        val cause = IllegalStateException("light estimation blew up")
        repeat(3) { funnel.sessionFailed(ARSessionFailure.from(cause)) }
        assertEquals(listOf<Throwable>(cause), nonFatals)
    }

    @Test
    fun `a failure the funnel already names files no exception`() {
        funnel.sessionFailed(ARSessionFailure.from(CameraNotAvailableException()))
        funnel.sessionFailed(ARSessionFailure.from(UnavailableDeviceNotCompatibleException()))
        assertEquals(emptyList<Throwable>(), nonFatals)
    }

    @Test
    fun `frames that never track are reported once, with ARCore's reason`() {
        // #4510: the camera image is live, the screen stays on "Getting ready".
        funnel.sessionCreated()
        funnel.trackingFailureChanged(TrackingFailureReason.INSUFFICIENT_FEATURES)
        untrackedFramesFor(ArFunnelLog.TRACKING_STALL_MS - 500)
        assertEquals(emptyList<Throwable>(), nonFatals)

        untrackedFramesFor(60_000)

        val stall = nonFatals.single() as ArFunnelLog.ArTrackingStalled
        assertEquals("ar-placement", stall.sampleId)
        assertEquals("insufficient_features", stall.reason)
        assertEquals(ArFunnelLog.TRACKING_STALL_MS / 1_000, stall.seconds)
        assertEquals("paused", stall.trackingState)
        assertEquals("1.57.0", stall.arCore)
        assertEquals(
            "AR never tracked in ar-placement after 10 s of frames, " +
                "state=paused reason=insufficient_features arcore=1.57.0",
            stall.message,
        )
    }

    @Test
    fun `a stall ARCore gives no reason for says none, next to the camera state`() {
        // The silent stall: no reason, so no coaching card and no `ar_tracking_lost` either.
        untrackedFramesFor(ArFunnelLog.TRACKING_STALL_MS + 100)
        val stall = nonFatals.single() as ArFunnelLog.ArTrackingStalled
        assertEquals("none", stall.reason)
        assertEquals("paused", stall.trackingState)
    }

    @Test
    fun `a resumed session that delivers no frame is reported once`() {
        // #4510: a frame loop that throws on every frame never reaches `onSessionUpdated`.
        funnel.sessionCreated()
        funnel.sessionResumed()
        nowMillis += ArFunnelLog.FRAME_STALL_MS - 1
        funnel.framesExpected()
        assertEquals(emptyList<Throwable>(), nonFatals)

        nowMillis += 1
        funnel.framesExpected()
        nowMillis += 60_000
        funnel.framesExpected()

        val stall = nonFatals.single() as ArFunnelLog.ArFramesStalled
        assertEquals("ar-placement", stall.sampleId)
        assertEquals(ArFunnelLog.FRAME_STALL_MS / 1_000, stall.seconds)
        assertEquals("AR delivered no frame in ar-placement for 10 s, arcore=1.57.0", stall.message)
    }

    @Test
    fun `frames that stop mid-session are reported too`() {
        funnel.sessionResumed()
        untrackedFramesFor(3_000)
        funnel.frameTracked(TrackingState.TRACKING)
        nowMillis += ArFunnelLog.FRAME_STALL_MS
        funnel.framesExpected()
        assertTrue(nonFatals.single() is ArFunnelLog.ArFramesStalled)
    }

    @Test
    fun `a session that keeps delivering frames owes nothing`() {
        funnel.sessionResumed()
        repeat(40) {
            untrackedFramesFor(1_000)
            funnel.framesExpected()
        }
        assertTrue(nonFatals.none { it is ArFunnelLog.ArFramesStalled })
    }

    @Test
    fun `a session not resumed yet, or paused, owes no frame`() {
        // Camera permission dialog, ARCore install: created or not, the session is not running.
        nowMillis += 60_000
        funnel.framesExpected()

        funnel.sessionResumed()
        nowMillis += 2_000
        funnel.sessionPaused()
        nowMillis += 60_000 // the app is in the background
        funnel.framesExpected()
        assertEquals(emptyList<Throwable>(), nonFatals)

        // Back in front: the wait starts again from the resume.
        funnel.sessionResumed()
        nowMillis += ArFunnelLog.FRAME_STALL_MS - 1
        funnel.framesExpected()
        assertEquals(emptyList<Throwable>(), nonFatals)
    }

    @Test
    fun `a session that tracks in time is not a stall, and never becomes one`() {
        untrackedFramesFor(4_000)
        funnel.frameTracked(TrackingState.TRACKING)
        // Tracking lost later is `ar_tracking_lost`, not "never tracked".
        untrackedFramesFor(60_000)
        assertEquals(emptyList<Throwable>(), nonFatals)
    }

    @Test
    fun `time spent in the background is not time without tracking`() {
        untrackedFramesFor(4_000)
        nowMillis += 120_000 // the app is left; no frame is delivered
        untrackedFramesFor(4_000)
        assertEquals(emptyList<Throwable>(), nonFatals)

        untrackedFramesFor(3_000)
        assertEquals(1, nonFatals.size)
    }
}
