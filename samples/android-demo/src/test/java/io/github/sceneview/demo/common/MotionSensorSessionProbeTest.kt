package io.github.sceneview.demo.common

import com.google.ar.core.Camera
import com.google.ar.core.Config as ArConfig
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.replay.ARSessionProbe
import io.github.sceneview.node.Node
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/** Observe the real SDK hook without loading ARCore's native session on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    instrumentedPackages = ["com.google.ar.core"],
    shadows = [MotionSensorSessionProbeTest.TestSession::class, MotionSensorSessionProbeTest.TestConfig::class,
        MotionSensorSessionProbeTest.TestFrame::class, MotionSensorSessionProbeTest.TestCamera::class],
)
class MotionSensorSessionProbeTest {
    private val session = Shadow.newInstanceOf(Session::class.java)
    private val frame = Shadow.newInstanceOf(Frame::class.java)
    private val sensors = DemoMotionSensors()

    @Test fun `the SDK observer feeds startup and forwards the enclosing observer and dataset`() {
        val dataset = File("qa.mp4")
        var forwarded = 0
        val delegate = object : ARSessionProbe {
            override val playbackDataset = dataset
            override fun onSessionFrame(session: Session, frame: Frame, nodes: List<Node>) {
                assertSame(this@MotionSensorSessionProbeTest.session, session)
                assertSame(this@MotionSensorSessionProbeTest.frame, frame)
                forwarded++
            }
        }
        val probe = MotionSensorSessionProbe(sensors, delegate)
        repeat(3) { probe.onSessionFrame(session, frame, emptyList()) }
        assertTrue(sensors.startup.neverStartedUnexplained)
        assertEquals(3, forwarded)
        assertSame(dataset, probe.playbackDataset)
        assertEquals("native configuration must not be read at frame rate", 1, Shadow.extract<TestSession>(session).configReads)
    }

    @Test fun `a new session forgets the previous sessions tracked frame`() {
        val probe = MotionSensorSessionProbe(sensors, null)
        val camera = Shadow.extract<TestCamera>(Shadow.extract<TestFrame>(frame).cameraValue)
        camera.state = TrackingState.TRACKING
        probe.onSessionFrame(session, frame, emptyList())
        assertTrue(sensors.startup.everTracked)
        val generation = sensors.generation
        camera.state = TrackingState.PAUSED
        probe.onSessionFrame(Shadow.newInstanceOf(Session::class.java), frame, emptyList())
        assertTrue(sensors.startup.neverStartedUnexplained)
        assertEquals(generation + 1, sensors.generation)
    }

    @Test fun `a replay supplies no input to the live sensor watch`() {
        Shadow.extract<TestSession>(session).playback = PlaybackStatus.OK
        MotionSensorSessionProbe(sensors, null).onSessionFrame(session, frame, emptyList())
        assertFalse(sensors.startup.framesArriving)
    }

    @Test fun `front camera face tracking supplies no input to the live sensor watch`() {
        val config = Shadow.extract<TestSession>(session).sessionConfig
        Shadow.extract<TestConfig>(config).faces = ArConfig.AugmentedFaceMode.MESH3D
        MotionSensorSessionProbe(sensors, null).onSessionFrame(session, frame, emptyList())
        assertFalse(sensors.startup.framesArriving)
    }

    @Implements(Session::class)
    class TestSession {
        var playback = PlaybackStatus.NONE
        val sessionConfig = Shadow.newInstanceOf(ArConfig::class.java)
        var configReads = 0
        @Implementation protected fun getPlaybackStatus(): PlaybackStatus = playback
        @Implementation protected fun getConfig(): ArConfig { configReads++; return sessionConfig }
    }

    @Implements(ArConfig::class)
    class TestConfig {
        var faces = ArConfig.AugmentedFaceMode.DISABLED
        @Implementation protected fun getAugmentedFaceMode(): ArConfig.AugmentedFaceMode = faces
    }

    @Implements(Frame::class)
    class TestFrame {
        val cameraValue = Shadow.newInstanceOf(Camera::class.java)
        @Implementation protected fun getCamera(): Camera = cameraValue
    }

    @Implements(Camera::class)
    class TestCamera {
        var state = TrackingState.PAUSED
        @Implementation protected fun getTrackingState(): TrackingState = state
        @Implementation protected fun getTrackingFailureReason(): TrackingFailureReason = TrackingFailureReason.NONE
    }
}
