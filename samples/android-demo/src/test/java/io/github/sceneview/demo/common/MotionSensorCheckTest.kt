package io.github.sceneview.demo.common

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARMotionSensorHealth
import io.github.sceneview.ar.ARMotionSensorSample
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.demo.common.placement.placementCard
import io.github.sceneview.demo.feedback.BugReportInfo
import io.github.sceneview.demo.feedback.formatShareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which message an AR screen that is not tracking shows (#4522): the restart message only for
 * a session that never tracked, for ten seconds, with no reason from ARCore, on a phone whose
 * gyroscope was measured silent. Everything else stays as it is.
 */
class MotionSensorCheckTest {

    private fun startup(
        framesArriving: Boolean = true,
        everTracked: Boolean = false,
        tracking: Boolean = false,
        failureReason: TrackingFailureReason? = null,
        waitedMillis: Long = AR_NEVER_TRACKED_MS,
    ) = ArStartup(framesArriving, everTracked, tracking, failureReason, waitedMillis)

    private val silent = ARMotionSensorHealth.SILENT

    // ── The message ──────────────────────────────────────────────────────────────────────

    @Test
    fun `never tracked for ten seconds on a silent gyroscope says restart`() {
        assertEquals(ArStartupMessage.RESTART_PHONE, arStartupMessage(startup(), silent))
        // ARCore's `NONE` is the same as no reason at all.
        assertEquals(
            ArStartupMessage.RESTART_PHONE,
            arStartupMessage(startup(failureReason = TrackingFailureReason.NONE), silent),
        )
    }

    @Test
    fun `a session that has only just started says nothing, whatever the sensors`() {
        // A working phone reaches tracking in about a second: nine seconds in is still starting.
        listOf(0L, 1_000L, AR_NEVER_TRACKED_MS - 1).forEach { waited ->
            assertEquals(
                "after $waited ms",
                ArStartupMessage.NONE,
                arStartupMessage(startup(waitedMillis = waited), silent),
            )
        }
    }

    @Test
    fun `tracking lost after it had started never says restart`() {
        // The coaching card owns this one: "Keep looking around", "Point back at your object".
        val lost = startup(everTracked = true, waitedMillis = 60_000)
        assertEquals(ArStartupMessage.NONE, arStartupMessage(lost, silent))
        assertFalse(lost.neverStartedUnexplained)
    }

    @Test
    fun `a reason from ARCore keeps its own message`() {
        // A white wall, a dark room, a camera another app holds: ARCore says so, and the
        // coaching card already tells the person what to change.
        TrackingFailureReason.values().filter { it != TrackingFailureReason.NONE }.forEach { reason ->
            assertEquals(
                reason.name,
                ArStartupMessage.NONE,
                arStartupMessage(startup(failureReason = reason, waitedMillis = 60_000), silent),
            )
        }
    }

    @Test
    fun `a gyroscope that delivers says nothing, however long the surface takes`() {
        // Sensors alive, no surface yet — or a phone lying still on a desk.
        val waiting = startup(waitedMillis = 120_000)
        assertEquals(ArStartupMessage.NONE, arStartupMessage(waiting, ARMotionSensorHealth.DELIVERING))
    }

    @Test
    fun `no verdict says nothing`() {
        assertEquals(ArStartupMessage.NONE, arStartupMessage(startup(), ARMotionSensorHealth.UNKNOWN))
        // Not measured yet, or a phone with no gyroscope at all.
        assertEquals(ArStartupMessage.NONE, arStartupMessage(startup(), null))
    }

    @Test
    fun `a session that is tracking says nothing`() {
        val tracking = startup(everTracked = true, tracking = true)
        assertEquals(ArStartupMessage.NONE, arStartupMessage(tracking, silent))
    }

    @Test
    fun `no camera frame is the camera error's business, not this one's`() {
        assertEquals(ArStartupMessage.NONE, arStartupMessage(startup(framesArriving = false), silent))
    }

    // ── When the sensors are listened to ─────────────────────────────────────────────────

    @Test
    fun `a session that never tracked has a measurement to wait for, until a verdict falls`() {
        assertTrue(motionSensorProbeDue(startup(waitedMillis = 0), probes = 0, last = null))
        // A verdict is final, either way.
        assertFalse(motionSensorProbeDue(startup(), probes = 1, last = silent))
        assertFalse(motionSensorProbeDue(startup(), probes = 1, last = ARMotionSensorHealth.DELIVERING))
        // A phone with no gyroscope at all: measured once, nothing to measure again.
        assertFalse(motionSensorProbeDue(startup(), probes = 1, last = null))
    }

    @Test
    fun `a measurement that says nothing is taken again, three times at most`() {
        // A refused listener, or an IMU that had not started delivering: not the last word.
        val unknown = ARMotionSensorHealth.UNKNOWN
        assertTrue(motionSensorProbeDue(startup(), probes = 1, last = unknown))
        assertTrue(motionSensorProbeDue(startup(), probes = MOTION_SENSOR_MAX_PROBES - 1, last = unknown))
        assertFalse(motionSensorProbeDue(startup(), probes = MOTION_SENSOR_MAX_PROBES, last = unknown))
        assertEquals(3, MOTION_SENSOR_MAX_PROBES)
    }

    @Test
    fun `the sensors are never listened to while tracking works, or once it has`() {
        assertFalse(motionSensorProbeDue(startup(tracking = true, everTracked = true), probes = 0, last = null))
        assertFalse(motionSensorProbeDue(startup(everTracked = true), probes = 0, last = null))
    }

    @Test
    fun `the sensors are not listened to when ARCore explains the wait, or when no frame came`() {
        val explained = startup(failureReason = TrackingFailureReason.INSUFFICIENT_FEATURES)
        assertFalse(motionSensorProbeDue(explained, probes = 0, last = null))
        assertFalse(motionSensorProbeDue(startup(framesArriving = false), probes = 0, last = null))
    }

    @Test
    fun `the ten second mark is the one the funnel reports a stalled session at`() {
        assertEquals(10_000L, AR_NEVER_TRACKED_MS)
    }

    // ── The card ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the restart card outranks every phase, and is absent without the verdict`() {
        PlacementPhase.values().forEach { phase ->
            assertEquals(
                phase.name,
                PlacementCard.MOTION_SENSOR_SILENT,
                placementCard(phase, motionSensorSilent = true),
            )
        }
        assertNull(placementCard(PlacementPhase.INITIALIZING, motionSensorSilent = false))
        assertEquals(placementCard(PlacementPhase.NO_SURFACE), placementCard(PlacementPhase.NO_SURFACE, false))
    }

    // ── The bug report ───────────────────────────────────────────────────────────────────

    @Test
    fun `the report line carries both counts and the window`() {
        assertEquals(
            "0 events in 1 s (accelerometer: 204)",
            MotionSensorReport.line(ARMotionSensorSample(0, 204, 1_000)),
        )
        assertEquals(
            "198 events in 1.5 s (accelerometer: 301)",
            MotionSensorReport.line(ARMotionSensorSample(198, 301, 1_500)),
        )
    }

    @Test
    fun `a sensor that was not listened to says so instead of a count`() {
        assertEquals(
            "not listened to in 0 s (accelerometer: 0)",
            MotionSensorReport.line(ARMotionSensorSample(-1, 0, 0)),
        )
    }

    @Test
    fun `nothing measured, no line`() {
        MotionSensorReport.clear()
        assertNull(MotionSensorReport.line(nowMillis = 5_000))
    }

    @Test
    fun `the line of a kept measurement says how old it is`() {
        MotionSensorReport.record(ARMotionSensorSample(0, 204, 1_000), nowMillis = 100_000)
        assertEquals(
            "0 events in 1 s (accelerometer: 204), measured 0 s ago",
            MotionSensorReport.line(nowMillis = 100_400),
        )
        // A report filed ten minutes later carries the same count, and says how stale it is.
        assertEquals(
            "0 events in 1 s (accelerometer: 204), measured 600 s ago",
            MotionSensorReport.line(nowMillis = 700_000),
        )
        MotionSensorReport.clear()
    }

    @Test
    fun `a session that tracks drops the measurement`() {
        MotionSensorReport.record(ARMotionSensorSample(0, 204, 1_000), nowMillis = 100_000)
        MotionSensorReport.clear()
        assertNull(MotionSensorReport.line(nowMillis = 100_400))
    }

    // ── The QA measurement on a working phone ────────────────────────────────────────────

    @Test
    fun `the measure state is honoured only in a debug build under QA mode`() {
        val state = MOTION_SENSOR_MEASURE_QA_STATE
        assertEquals("motion_sensor_measure", state)
        assertTrue(motionSensorMeasureForced(state, overridesAllowed = true, debugBuild = true))
        // No `qa_mode`: an intent extra alone is never enough.
        assertFalse(motionSensorMeasureForced(state, overridesAllowed = false, debugBuild = true))
        // A release build ignores it, QA mode or not.
        assertFalse(motionSensorMeasureForced(state, overridesAllowed = true, debugBuild = false))
        assertFalse(motionSensorMeasureForced(state, overridesAllowed = false, debugBuild = false))
        // Another state, or none, is not this one.
        val other = MOTION_SENSOR_SILENT_QA_STATE
        assertFalse(motionSensorMeasureForced(other, overridesAllowed = true, debugBuild = true))
        assertFalse(motionSensorMeasureForced(null, overridesAllowed = true, debugBuild = true))
    }

    @Test
    fun `the pinned restart card is honoured only in a debug build under QA mode`() {
        val state = MOTION_SENSOR_SILENT_QA_STATE
        assertEquals("motion_sensor_silent", state)
        assertTrue(motionSensorSilentForced(state, overridesAllowed = true, debugBuild = true))
        // The store build never tells a working phone to restart, whatever the intent says.
        assertFalse(motionSensorSilentForced(state, overridesAllowed = true, debugBuild = false))
        assertFalse(motionSensorSilentForced(state, overridesAllowed = false, debugBuild = true))
        assertFalse(motionSensorSilentForced(state, overridesAllowed = false, debugBuild = false))
        val other = MOTION_SENSOR_MEASURE_QA_STATE
        assertFalse(motionSensorSilentForced(other, overridesAllowed = true, debugBuild = true))
        assertFalse(motionSensorSilentForced(null, overridesAllowed = true, debugBuild = true))
    }

    @Test
    fun `the measure state makes a measurement due on a session that tracks`() {
        val reason = TrackingFailureReason.INSUFFICIENT_LIGHT
        val forced = watchedArStartup(
            framesArriving = true, everTracked = true, tracking = true, failureReason = reason,
            waitedMillis = 0, measureForced = true,
        )
        assertTrue(forced.neverStartedUnexplained)
        assertTrue(motionSensorProbeDue(forced, probes = 0, last = null))
        // A delivering gyroscope is a final verdict and never a card, forced or not.
        val measured = forced.copy(waitedMillis = AR_NEVER_TRACKED_MS)
        assertFalse(motionSensorProbeDue(measured, probes = 1, last = ARMotionSensorHealth.DELIVERING))
        assertEquals(ArStartupMessage.NONE, arStartupMessage(measured, ARMotionSensorHealth.DELIVERING))
        // Frames are never forced: no camera image, no measurement.
        val noFrame = watchedArStartup(false, false, true, null, 0, measureForced = true)
        assertFalse(motionSensorProbeDue(noFrame, probes = 0, last = null))
    }

    @Test
    fun `without the measure state the watch reads the session as it is`() {
        val tracking = watchedArStartup(true, false, true, null, 0, measureForced = false)
        assertEquals(ArStartup(true, true, true, null, 0), tracking)
        assertFalse(motionSensorProbeDue(tracking, probes = 0, last = null))
        val lost = watchedArStartup(true, true, false, null, 0, measureForced = false)
        assertFalse(motionSensorProbeDue(lost, probes = 0, last = null))
    }

    @Test
    fun `the report text reads gyroscope N events in 1 s`() {
        val line = MotionSensorReport.line(ARMotionSensorSample(0, 204, 1_000))
        val info = BugReportInfo(
            metadata = linkedMapOf("Device" to "Google Pixel 9", MotionSensorReport.KEY to line),
            logcat = emptyList(),
        )
        assertTrue(
            formatShareText(info, note = "AR never starts")
                .lines()
                .contains("gyroscope: 0 events in 1 s (accelerometer: 204)"),
        )
    }
}
