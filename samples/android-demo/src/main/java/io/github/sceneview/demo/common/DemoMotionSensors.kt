package io.github.sceneview.demo.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.replay.ARSessionProbe
import io.github.sceneview.demo.telemetry.AR_VIEW_SAMPLE_ID
import io.github.sceneview.demo.telemetry.ArFunnelLog
import io.github.sceneview.demo.telemetry.LocalSampleId
import io.github.sceneview.node.Node

/** Lets scene-owned coaching yield to the scaffold's restart message, over the live camera. */
internal val LocalMotionSensorSilent = staticCompositionLocalOf { false }

/** One rear-camera session's input to the shared startup watch, independent of placement state. */
internal class DemoMotionSensors {
    private var session: Session? = null
    private var eligible = false
    var generation by mutableIntStateOf(0)
        private set
    var startup by mutableStateOf(ArStartup(false, false, false, null, 0))
        private set

    fun onFrame(session: Session, frame: Frame) {
        if (this.session !== session) {
            this.session = session
            // Read configuration only once, not on every frame. Replays contain recorded sensor
            // data; front-camera face tracking is outside this world-tracking diagnosis.
            eligible = session.playbackStatus == PlaybackStatus.NONE &&
                session.config.augmentedFaceMode != Config.AugmentedFaceMode.MESH3D
            generation++
            startup = ArStartup(false, false, false, null, 0)
        }
        if (!eligible || startup.everTracked) return
        val camera = frame.camera
        onFrame(camera.trackingState, camera.trackingFailureReason)
    }

    fun onFrame(state: TrackingState, reason: TrackingFailureReason?) {
        val tracking = state == TrackingState.TRACKING
        startup = ArStartup(
            framesArriving = state != TrackingState.STOPPED,
            // Latch in the frame callback: even one tracked frame rules this diagnosis out.
            everTracked = startup.everTracked || tracking,
            tracking = tracking,
            failureReason = reason,
            waitedMillis = 0,
        )
    }
}

/** Adds live diagnostics while preserving the enclosing observer and its replay dataset. */
internal class MotionSensorSessionProbe(
    private val sensors: DemoMotionSensors,
    private val delegate: ARSessionProbe?,
) : ARSessionProbe {
    override val playbackDataset get() = delegate?.playbackDataset

    override fun onSessionFrame(session: Session, frame: Frame, nodes: List<Node>) {
        try {
            sensors.onFrame(session, frame)
        } finally {
            delegate?.onSessionFrame(session, frame, nodes)
        }
    }
}

/** Uses the same watch, report and funnel reason as the AR tab; never a session-failure callback. */
@Composable
internal fun rememberDemoMotionSensorSilent(
    sensors: DemoMotionSensors,
    funnel: ArFunnelLog = remember(sensors, sensors.generation) { ArFunnelLog() },
): Boolean = key(sensors.generation) {
    val sampleId = LocalSampleId.current ?: AR_VIEW_SAMPLE_ID
    funnel.sampleId = sampleId
    val startup = sensors.startup
    rememberMotionSensorSilent(
        framesArriving = startup.framesArriving,
        tracking = startup.everTracked || startup.tracking,
        failureReason = startup.failureReason,
        onSilent = funnel::motionSensorSilent,
    )
}
