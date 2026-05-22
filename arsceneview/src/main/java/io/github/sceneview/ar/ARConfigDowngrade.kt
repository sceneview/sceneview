package io.github.sceneview.ar

import com.google.ar.core.CameraConfig
import com.google.ar.core.Config

/**
 * Reports a requested AR session capability that the device could not honour and that
 * [ARSceneView][io.github.sceneview.ar.ARSceneView] **silently downgraded** to a supported
 * fallback (#2096).
 *
 * Several `Config` knobs are support-gated inside SceneView's session pipeline — an unsupported
 * request is quietly replaced with a safe default rather than crashing the session
 * (`UnsupportedConfigurationException`). That keeps the app running on every device, but the
 * consumer previously had no way to *know* the request was not honoured, so behaviour silently
 * diverged across devices (a depth-occlusion app shipping no occlusion, a high-res recording
 * captured at 640×480, …).
 *
 * `ARConfigDowngrade` makes each downgrade observable: wire
 * [ARSceneView][io.github.sceneview.ar.ARSceneView]'s `onConfigDowngraded` callback to react —
 * hide a UI affordance, warn the user, or report to analytics.
 *
 * ### Usage
 *
 * ```kotlin
 * ARSceneView(
 *     depthMode = Config.DepthMode.AUTOMATIC,
 *     onConfigDowngraded = { downgrade ->
 *         when (downgrade) {
 *             is ARConfigDowngrade.DepthMode ->
 *                 // device has no motion-stereo depth — hide the occlusion toggle
 *                 occlusionAvailable = false
 *             is ARConfigDowngrade.CameraConfig ->
 *                 // the requested camera config is not in getSupportedCameraConfigs()
 *                 toast("Recording at ${downgrade.effective.imageSize}")
 *         }
 *     },
 * ) { /* … */ }
 * ```
 */
public sealed class ARConfigDowngrade {

    /**
     * The requested [Config.DepthMode] is not supported by the device — ARCore's motion-stereo
     * depth pipeline is unavailable — so the session was reconfigured with [effective]
     * (always [Config.DepthMode.DISABLED]).
     *
     * `Frame.acquireDepthImage16Bits`, `Frame.hitTestDepth`,
     * [DepthMeshNode][io.github.sceneview.ar.node.DepthMeshNode] and ARCameraStream depth
     * occlusion all see no real-world depth on this device.
     *
     * @property requested the [Config.DepthMode] the app asked for.
     * @property effective the [Config.DepthMode] actually applied to the live session.
     */
    public data class DepthMode(
        public val requested: Config.DepthMode,
        public val effective: Config.DepthMode
    ) : ARConfigDowngrade()

    /**
     * The [CameraConfig] selected by `sessionCameraConfig` was not honoured — typically because
     * the selector returned a config that is not in [com.google.ar.core.Session.getSupportedCameraConfigs],
     * so ARCore kept its stock default config instead.
     *
     * The app may, for example, have requested the highest-resolution back-camera config for a
     * recording but the session is running at ARCore's low-res default.
     *
     * @property requested the [CameraConfig] the `sessionCameraConfig` selector returned.
     * @property effective the [CameraConfig] actually applied to the live session.
     */
    public data class CameraConfig(
        public val requested: com.google.ar.core.CameraConfig,
        public val effective: com.google.ar.core.CameraConfig
    ) : ARConfigDowngrade()
}

/**
 * Pure-Kotlin diff used by [ARSceneView][io.github.sceneview.ar.ARSceneView] to detect which
 * requested capabilities a device silently downgraded (#2096).
 *
 * Extracted as a free function so the detection contract can be unit-tested without a live,
 * JNI-bound ARCore `Session` — both [Config] and [CameraConfig] equality are compared by the
 * caller-supplied values, never probed against native state here.
 *
 * @param requestedDepthMode the [Config.DepthMode] the app asked for, or `null` when it did not
 *   request one (no downgrade is possible).
 * @param effectiveDepthMode the [Config.DepthMode] actually applied to the session after
 *   `configure()`.
 * @param requestedCameraConfig the [CameraConfig] the `sessionCameraConfig` selector returned, or
 *   `null` when no selector was supplied (no downgrade is possible).
 * @param effectiveCameraConfig the [CameraConfig] actually applied to the session, or `null` when
 *   unknown.
 * @return every downgrade detected — empty when every request was honoured.
 */
internal fun detectConfigDowngrades(
    requestedDepthMode: Config.DepthMode?,
    effectiveDepthMode: Config.DepthMode,
    requestedCameraConfig: CameraConfig?,
    effectiveCameraConfig: CameraConfig?
): List<ARConfigDowngrade> = buildList {
    if (requestedDepthMode != null &&
        requestedDepthMode != Config.DepthMode.DISABLED &&
        effectiveDepthMode != requestedDepthMode
    ) {
        add(ARConfigDowngrade.DepthMode(requestedDepthMode, effectiveDepthMode))
    }
    if (requestedCameraConfig != null &&
        effectiveCameraConfig != null &&
        effectiveCameraConfig != requestedCameraConfig
    ) {
        add(ARConfigDowngrade.CameraConfig(requestedCameraConfig, effectiveCameraConfig))
    }
}
