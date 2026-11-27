package io.github.sceneview.ar.scene

import com.google.ar.core.Config

/**
 * Use the configured mode, after ARSession's device-support downgrade and the host's config
 * callback. Disabled/unsupported depth must select the flat camera material, including when
 * a previous configuration had a depth texture (which would otherwise keep occluding).
 */
internal fun shouldOccludePlanes(effectiveDepthMode: Config.DepthMode): Boolean = when (effectiveDepthMode) {
    Config.DepthMode.AUTOMATIC, Config.DepthMode.RAW_DEPTH_ONLY -> true
    else -> false
}
