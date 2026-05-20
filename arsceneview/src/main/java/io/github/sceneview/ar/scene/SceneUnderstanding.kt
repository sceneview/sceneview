package io.github.sceneview.ar.scene

import com.google.ar.core.Config
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.light.LightEstimator

/**
 * Grouped scene-understanding flags for [io.github.sceneview.ar.ARSceneView] —
 * mirrors RealityKit's `ARView.environment.sceneUnderstanding.options` (#1767).
 *
 * SceneView ships four independent toggles for "what the AR engine understands
 * about the real world" — each living on a different object:
 *
 *  - depth occlusion → [ARCameraStream.isDepthOcclusionEnabled]
 *  - light estimation → [LightEstimator.isEnabled] + [Config.LightEstimationMode]
 *  - plane visualisation → [PlaneRenderer.isEnabled]
 *  - physics / scene semantics → reserved for future expansion (see [physics]'s KDoc)
 *
 * Discovering the right knob (especially for an AI assistant generating SceneView
 * code from natural-language prompts) requires four separate file lookups and a
 * mental model of which class owns what. Grouping the four under one
 * `SceneUnderstanding(...)` knob — pass-through to [ARSceneView]'s new
 * `sceneUnderstanding` parameter — gives apps a single discoverable handle and
 * an obvious 1:1 mapping to RealityKit's grouping for cross-platform docs.
 *
 * ## Compatibility
 *
 * This is a purely additive API. The existing fine-grained flags
 * ([ARSceneView]'s `planeRenderer: Boolean`, manual mutation of
 * `cameraStream.isDepthOcclusionEnabled` / `lightEstimator.isEnabled`) keep
 * working unchanged. [ARSceneView]'s `sceneUnderstanding` parameter defaults to
 * `null`, meaning the grouped knob is opt-in — when null, the four individual
 * flags retain their pre-#1767 defaults verbatim.
 *
 * ## Defaults
 *
 * The data class defaults represent the most-useful "full understanding"
 * configuration (occlusion + lighting + plane viz on, physics off because the
 * underlying ARCore Scene Semantics API is not yet wired — #1761/#1760). Apps
 * that want a different default tree pass an explicit `SceneUnderstanding(...)`.
 *
 * @property occlusion Enable depth occlusion. Maps to
 *   [ARCameraStream.isDepthOcclusionEnabled]. Requires the ARCore [Config]'s
 *   depth mode to be either [Config.DepthMode.AUTOMATIC] or
 *   [Config.DepthMode.RAW_DEPTH_ONLY]; otherwise the camera stream falls back
 *   to the flat (non-depth) material. SceneView does NOT auto-enable the depth
 *   `Config.DepthMode` — set that in your `sessionConfiguration` block.
 *
 * @property lighting Enable AR light estimation. Maps to
 *   [LightEstimator.isEnabled]. Works in tandem with the ARCore [Config]'s
 *   `lightEstimationMode` (defaults to `ENVIRONMENTAL_HDR` for back-camera
 *   sessions, #1063). Setting this to `false` makes the AR scene fall back to
 *   the static environment IBL baked into [ARSceneView]'s `environment`.
 *
 * @property physics Reserved for future expansion (#1761 People Occlusion,
 *   #1760 Scene Mesh). Today this flag is a no-op — the underlying ARCore
 *   Scene Semantics / Mesh APIs are not yet wired through SceneView. The flag
 *   is part of the surface today so apps that opt into [SceneUnderstanding]
 *   now don't break later when the implementation lands.
 *
 * @property planeVisualization Render the AR plane grid overlay. Maps to
 *   [PlaneRenderer.isEnabled]. The renderer itself is always available — plane
 *   `Trackable`s are still detected and surfaced via `frame.getUpdatedPlanes()`
 *   for hit-tests even when this is `false`. Only the visual grid drawn over
 *   detected planes is toggled.
 */
data class SceneUnderstanding(
    val occlusion: Boolean = true,
    val lighting: Boolean = true,
    val physics: Boolean = false,
    val planeVisualization: Boolean = true
) {
    companion object {
        /**
         * Everything on (modulo [physics], which is reserved). Identical to the
         * no-arg [SceneUnderstanding] constructor; spelled out as a named
         * constant so apps that prefer a constant reference over a constructor
         * call have an obvious anchor.
         */
        val Full: SceneUnderstanding = SceneUnderstanding()

        /**
         * Lightweight configuration — light estimation on (low CPU cost),
         * everything else off. Useful on low-end devices that lack a depth
         * sensor and want to avoid the plane-grid draw overhead.
         */
        val Minimal: SceneUnderstanding = SceneUnderstanding(
            occlusion = false,
            lighting = true,
            physics = false,
            planeVisualization = false
        )

        /**
         * Everything off — pass-through camera feed, no AR overlays. Equivalent
         * to building a plain 3D scene on top of the camera image, useful for
         * AR demos that handle their own occlusion / lighting / plane viz.
         */
        val None: SceneUnderstanding = SceneUnderstanding(
            occlusion = false,
            lighting = false,
            physics = false,
            planeVisualization = false
        )
    }
}
