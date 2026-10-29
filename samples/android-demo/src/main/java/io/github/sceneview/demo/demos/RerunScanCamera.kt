package io.github.sceneview.demo.demos

import android.util.Log
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import io.github.sceneview.demo.demos.internal.CameraStream
import io.github.sceneview.demo.demos.internal.CameraStreamPick
import java.util.EnumSet

/**
 * The 3D Scan's camera config: of the back streams that reach 30 fps, one that meets 1280 × 720,
 * then one fixed at 30 fps, then the largest CPU image ([CameraStreamPick]), so the photos and
 * the depth are read from the sharpest image the scan keeps up with. The session's default config
 * stays when ARCore offers none, or throws. [line] keeps the choice for the bake log, next to the
 * image size the scan actually received. During a playback ARCore serves the recording's own
 * stream and ignores the config asked for, so the line starts by saying so.
 */
internal object RerunScanCamera {
    @Volatile
    var line: String = CameraStreamPick.logLine(emptyList(), null)
        private set

    /** [playback] is whether the session replays a recording instead of the camera. */
    fun select(session: Session, playback: Boolean): CameraConfig = select(
        offered = {
            val filter = CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            session.getSupportedCameraConfigs(filter)
        },
        streamOf = ::streamOf,
        default = { session.cameraConfig },
        playback = playback,
    )

    /**
     * [select] on any config type, which the JVM tests drive without ARCore: [offered] is read
     * once, and the config whose stream [CameraStreamPick] chooses is returned, or [default]'s
     * when none qualifies or reading the offer throws. [line] says which, and why, and whether a
     * [playback] serves the recording's stream instead.
     */
    fun <C : Any> select(
        offered: () -> List<C>,
        streamOf: (C) -> CameraStream,
        default: () -> C,
        playback: Boolean = false,
    ): C {
        val chosen = runCatching {
            val configs = offered()
            val streams = configs.map(streamOf)
            val pick = CameraStreamPick.pickOf(configs.zip(streams))
            line = CameraStreamPick.logLine(streams, pick?.second, playback)
            pick?.first
        }.onFailure { line = CameraStreamPick.unreadLine(it, playback) }.getOrNull()
        // The JVM tests' android.util.Log is a stub that throws.
        runCatching { Log.i(LOG_TAG, line) }
        return chosen ?: default()
    }

    private fun streamOf(config: CameraConfig) = CameraStream(
        cpuWidth = config.imageSize.width,
        cpuHeight = config.imageSize.height,
        minFps = config.fpsRange.lower,
        maxFps = config.fpsRange.upper,
        back = config.facingDirection == CameraConfig.FacingDirection.BACK,
        depthSensor = config.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE,
        gpuWidth = config.textureSize.width,
        gpuHeight = config.textureSize.height,
    )

    private const val LOG_TAG = "RerunScanCamera"
}
