package io.github.sceneview.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/**
 * What [probeARMotionSensors] does to the sensor service (#4522): what it counts, what it
 * reports when a registration is refused, and that no listener outlives the call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ARMotionSensorProbeTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val shadow: ShadowSensorManager = shadowOf(manager)

    @Before
    fun bothSensorsPresent() {
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
    }

    private fun deliver(sensorType: Int, times: Int) {
        val sensor = manager.getDefaultSensor(sensorType)
        repeat(times) {
            val event = SensorEventBuilder.newBuilder(sensor, floatArrayOf(0f, 0f, 0f)).build()
            shadow.sendSensorEventToListeners(event)
        }
    }

    @Test
    fun `counts each sensor's events over the window, then unregisters`() = runTest {
        val probe = async { probeARMotionSensors(context, 1_000) }
        runCurrent()
        assertEquals("one listener while the window is open", 1, shadow.listeners.size)
        deliver(Sensor.TYPE_ACCELEROMETER, 200)
        deliver(Sensor.TYPE_GYROSCOPE, 190)
        advanceUntilIdle()

        assertEquals(ARMotionSensorSample(190, 200, 1_000), probe.await())
        assertEquals(ARMotionSensorHealth.DELIVERING, probe.await()!!.health)
        assertTrue("a listener outlived the window", shadow.listeners.isEmpty())
    }

    @Test
    fun `a gyroscope that delivers nothing next to an accelerometer that does is silent`() = runTest {
        val probe = async { probeARMotionSensors(context, 1_000) }
        runCurrent()
        deliver(Sensor.TYPE_ACCELEROMETER, 200)
        advanceUntilIdle()

        assertEquals(ARMotionSensorHealth.SILENT, probe.await()!!.health)
        assertTrue(shadow.listeners.isEmpty())
    }

    @Test
    fun `a refused gyroscope registration is no verdict, whatever the accelerometer delivers`() = runTest {
        // The registration the reviewer of #4522 pointed at: gyroscope refused, accelerometer
        // accepted. Counted as 0 against N it would read as a silent gyroscope.
        val probe = async {
            probeARMotionSensors(manager, 1_000) { listener, sensor ->
                sensor.type != Sensor.TYPE_GYROSCOPE &&
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        runCurrent()
        deliver(Sensor.TYPE_ACCELEROMETER, 200)
        advanceUntilIdle()

        val sample = probe.await()!!
        assertTrue("the gyroscope count must say it was not listened to", sample.gyroscopeEvents < 0)
        assertEquals(ARMotionSensorHealth.UNKNOWN, sample.health)
        assertNotEquals(ARMotionSensorHealth.SILENT, sample.health)
        assertTrue("the accepted listener stayed registered", shadow.listeners.isEmpty())
    }

    @Test
    fun `a refused accelerometer registration is no verdict either`() = runTest {
        val sample = probeARMotionSensors(manager, 1_000) { listener, sensor ->
            sensor.type != Sensor.TYPE_ACCELEROMETER &&
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }!!
        assertTrue(sample.accelerometerEvents < 0)
        assertEquals(ARMotionSensorHealth.UNKNOWN, sample.health)
        assertTrue(shadow.listeners.isEmpty())
    }

    @Test
    fun `a sensor service that refuses every registration is no verdict`() = runTest {
        shadow.setForceListenersToFail(true)
        val sample = probeARMotionSensors(context, 1_000)!!
        assertTrue(sample.gyroscopeEvents < 0 && sample.accelerometerEvents < 0)
        assertEquals(ARMotionSensorHealth.UNKNOWN, sample.health)
        assertTrue(shadow.listeners.isEmpty())
    }

    @Test
    fun `cancelling the caller unregisters the listener`() = runTest {
        val probe = async { probeARMotionSensors(context, 1_000) }
        advanceTimeBy(400)
        assertEquals(1, shadow.listeners.size)

        probe.cancelAndJoin()

        assertTrue("a listener survived the cancellation", shadow.listeners.isEmpty())
    }

    @Test
    fun `a phone without a gyroscope has nothing to measure`() = runTest {
        shadow.removeSensor(manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE))
        assertNull(probeARMotionSensors(context, 1_000))
        assertTrue(shadow.listeners.isEmpty())
    }
}
