package io.github.sceneview.ar.scene

import com.google.ar.core.Config

/**
 * Use the configured mode, after ARSession's device-support downgrade and the host's config
 * callback. Disabled/unsupported depth and the plane-only opt-out must stop sampling depth,
 * including when a previous configuration had a depth texture. Never change the session's
 * depth mode or the camera stream's model-occlusion setting to occlude plane marks.
 */
internal fun shouldOccludePlanes(
    effectiveDepthMode: Config.DepthMode,
    enabled: Boolean = true,
): Boolean = enabled && when (effectiveDepthMode) {
    Config.DepthMode.AUTOMATIC, Config.DepthMode.RAW_DEPTH_ONLY -> true
    else -> false
}
