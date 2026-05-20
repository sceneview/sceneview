package io.github.sceneview.ar.arcore

import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import java.util.EnumSet

/**
 * DSL builder for [CameraConfigFilter] (#1733, #1772).
 *
 * Wraps the verbose ARCore `CameraConfigFilter.set*(EnumSet.of(...))` ceremony into a single
 * property-based block. Backed by ARCore's existing filter — every property maps 1:1 to a
 * setter on the underlying [CameraConfigFilter].
 *
 * ```kotlin
 * ARSceneView(
 *     sessionCameraConfig = cameraConfigFilter {
 *         facing = CameraConfig.FacingDirection.BACK
 *         targetFps = setOf(CameraConfig.TargetFps.TARGET_FPS_60)
 *         depthSensor = CameraConfig.DepthSensorUsage.REQUIRE_AND_USE
 *         stereoCamera = CameraConfig.StereoCameraUsage.REQUIRE_AND_USE
 *     },
 * ) { /* DSL */ }
 * ```
 *
 * The builder is mutable — every `var` is optional. Setting `null` (the default) means "don't
 * filter on that axis"; ARCore returns every config matching the remaining constraints.
 *
 * @see Session.getSupportedCameraConfigs
 * @see CameraConfigFilter
 */
class CameraConfigFilterBuilder {
    /**
     * Restrict to a single camera facing direction (front or back). Default `null` — both
     * facings are eligible. Most scenes want [CameraConfig.FacingDirection.BACK]; Augmented
     * Faces requires [CameraConfig.FacingDirection.FRONT].
     */
    var facing: CameraConfig.FacingDirection? = null

    /**
     * Restrict to configs supporting at least one of the given target FPS values. Default
     * `null` — ARCore returns configs at every FPS it supports (typically 30 and 60).
     *
     * Pass `setOf(CameraConfig.TargetFps.TARGET_FPS_60)` for a 60 FPS-only filter, or
     * `setOf(TARGET_FPS_30, TARGET_FPS_60)` for either.
     */
    var targetFps: Set<CameraConfig.TargetFps>? = null

    /**
     * Restrict to configs that either require ([CameraConfig.DepthSensorUsage.REQUIRE_AND_USE])
     * or skip ([CameraConfig.DepthSensorUsage.DO_NOT_USE]) the hardware depth sensor. Default
     * `null` — ARCore returns both with/without depth-sensor configs and picks per-device
     * defaults. Set `REQUIRE_AND_USE` on TOF-equipped devices (S20 Ultra, Pixel 4 XL, etc.)
     * for higher-quality depth maps.
     */
    var depthSensor: CameraConfig.DepthSensorUsage? = null

    /**
     * Restrict to configs that either require ([CameraConfig.StereoCameraUsage.REQUIRE_AND_USE])
     * or skip ([CameraConfig.StereoCameraUsage.DO_NOT_USE]) hardware stereo cameras. Default
     * `null` — ARCore picks per-device defaults. Set `REQUIRE_AND_USE` for devices with
     * dual-camera AR (e.g. some Samsung S-series back cameras) when you need the wider FOV /
     * stereo-baselined depth.
     */
    var stereoCamera: CameraConfig.StereoCameraUsage? = null

    /**
     * Internal: builds the native [CameraConfigFilter] for [session].
     *
     * Each unset property (`null`) is left at the ARCore default; set ones are forwarded via
     * the underlying setter. The filter is created from `session` because every native filter
     * is bound to its session — apps must not reuse a filter across sessions.
     */
    internal fun build(session: Session): CameraConfigFilter {
        val filter = CameraConfigFilter(session)
        facing?.let { filter.setFacingDirection(it) }
        targetFps?.let { filter.setTargetFps(EnumSet.copyOf(it)) }
        depthSensor?.let { filter.setDepthSensorUsage(EnumSet.of(it)) }
        stereoCamera?.let { filter.setStereoCameraUsage(EnumSet.of(it)) }
        return filter
    }
}

/**
 * Builds a [CameraConfig] selection function from a [CameraConfigFilterBuilder] DSL block
 * (#1733, #1772).
 *
 * The returned lambda is shaped for `ARSceneView(sessionCameraConfig = …)`. At session-creation
 * time it:
 *
 *  1. Builds the [CameraConfigFilter] from the DSL block against the live session.
 *  2. Enumerates [Session.getSupportedCameraConfigs] through that filter.
 *  3. Picks the highest-resolution match (largest `imageSize.width * height`) — same policy as
 *     [highestResolutionCameraConfig] so apps get the highest pixel count consistent with their
 *     filter constraints (e.g. 60 FPS, depth sensor required, etc.).
 *  4. Falls back to the session's current [Session.getCameraConfig] when no config matches —
 *     guaranteeing the call never crashes session creation on degenerate / unsupported devices.
 *
 * ```kotlin
 * ARSceneView(
 *     sessionCameraConfig = cameraConfigFilter {
 *         facing = CameraConfig.FacingDirection.BACK
 *         targetFps = setOf(CameraConfig.TargetFps.TARGET_FPS_60)
 *     },
 * ) { /* … */ }
 * ```
 *
 * Pass `null` (or `::highestResolutionCameraConfig`) to keep the SceneView default. Pass a
 * raw `(Session) -> CameraConfig` lambda if you need imperative selection beyond what the DSL
 * exposes.
 *
 * @param block Builder block configuring the filter knobs.
 * @return A function suitable for `ARSceneView(sessionCameraConfig = …)`.
 */
fun cameraConfigFilter(
    block: CameraConfigFilterBuilder.() -> Unit
): (Session) -> CameraConfig {
    val builder = CameraConfigFilterBuilder().apply(block)
    return { session ->
        runCatching {
            val filter = builder.build(session)
            session.getSupportedCameraConfigs(filter).maxByOrNull {
                it.imageSize.width.toLong() * it.imageSize.height.toLong()
            }
        }.getOrNull() ?: session.cameraConfig
    }
}
