package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/** The verdict over the two event counts of a motion-sensor probe (#4522). */
class ARMotionSensorHealthTest {

    @Test
    fun `a gyroscope that delivers is delivering`() {
        assertEquals(ARMotionSensorHealth.DELIVERING, arMotionSensorHealth(50, 50, 1_000))
    }

    @Test
    fun `no gyroscope event while the accelerometer delivers is silent`() {
        // Measured in #4510: an accelerometer sample every 5 ms, a gyroscope silent for hours.
        assertEquals(ARMotionSensorHealth.SILENT, arMotionSensorHealth(0, 200, 1_000))
    }

    @Test
    fun `a single gyroscope event is enough to rule the sensor in`() {
        assertEquals(ARMotionSensorHealth.DELIVERING, arMotionSensorHealth(1, 200, 1_000))
    }

    @Test
    fun `a gyroscope that delivers is delivering whatever the accelerometer did`() {
        assertEquals(ARMotionSensorHealth.DELIVERING, arMotionSensorHealth(40, 0, 1_000))
    }

    @Test
    fun `both sensors silent is no verdict`() {
        // Nothing delivered at all: the sensor service had not started, or the app is in the
        // background. Not a reason to tell anyone to restart a phone.
        assertEquals(ARMotionSensorHealth.UNKNOWN, arMotionSensorHealth(0, 0, 1_000))
    }

    @Test
    fun `too few accelerometer events is no verdict`() {
        val below = AR_MOTION_SENSOR_MIN_REFERENCE_EVENTS - 1
        assertEquals(ARMotionSensorHealth.UNKNOWN, arMotionSensorHealth(0, below, 1_000))
        assertEquals(
            ARMotionSensorHealth.SILENT,
            arMotionSensorHealth(0, AR_MOTION_SENSOR_MIN_REFERENCE_EVENTS, 1_000),
        )
    }

    @Test
    fun `a window too short is no verdict`() {
        assertEquals(
            ARMotionSensorHealth.UNKNOWN,
            arMotionSensorHealth(0, 200, AR_MOTION_SENSOR_MIN_WINDOW_MS - 1),
        )
        assertEquals(
            ARMotionSensorHealth.SILENT,
            arMotionSensorHealth(0, 200, AR_MOTION_SENSOR_MIN_WINDOW_MS),
        )
    }

    @Test
    fun `nonsense counts are no verdict`() {
        assertEquals(ARMotionSensorHealth.UNKNOWN, arMotionSensorHealth(-1, 200, 1_000))
        assertEquals(ARMotionSensorHealth.UNKNOWN, arMotionSensorHealth(0, -3, 1_000))
        assertEquals(ARMotionSensorHealth.UNKNOWN, arMotionSensorHealth(0, 200, 0))
    }

    @Test
    fun `the sample carries its own verdict`() {
        assertEquals(ARMotionSensorHealth.SILENT, ARMotionSensorSample(0, 180, 1_000).health)
        assertEquals(ARMotionSensorHealth.DELIVERING, ARMotionSensorSample(190, 180, 1_000).health)
    }

    @Test
    fun `a sample is compared by what it measured`() {
        // Not a data class (it may gain a property), but still a value.
        assertEquals(ARMotionSensorSample(0, 180, 1_000), ARMotionSensorSample(0, 180, 1_000))
        assertEquals(
            ARMotionSensorSample(0, 180, 1_000).hashCode(),
            ARMotionSensorSample(0, 180, 1_000).hashCode(),
        )
        assert(ARMotionSensorSample(0, 180, 1_000) != ARMotionSensorSample(1, 180, 1_000))
        assertEquals(
            "ARMotionSensorSample(gyroscopeEvents=0, accelerometerEvents=180, windowMillis=1000)",
            ARMotionSensorSample(0, 180, 1_000).toString(),
        )
    }

    @Test
    fun `a sensor that was not listened to is no verdict`() {
        val refused = AR_MOTION_SENSOR_NOT_LISTENED
        assertEquals(ARMotionSensorHealth.UNKNOWN, ARMotionSensorSample(refused, 200, 1_000).health)
        assertEquals(ARMotionSensorHealth.UNKNOWN, ARMotionSensorSample(0, refused, 1_000).health)
    }

    @Test
    fun `the default probe window can reach a silent verdict`() {
        assert(AR_MOTION_SENSOR_PROBE_WINDOW_MS >= AR_MOTION_SENSOR_MIN_WINDOW_MS)
    }
}
