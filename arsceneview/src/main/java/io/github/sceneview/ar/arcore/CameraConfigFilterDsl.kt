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
 *         depthSensor = setOf(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)
 *         stereoCamera = setOf(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE)
 *     },
 * ) { /* DSL */ }
 * ```
 *
 * **All three filter knobs are `Set<…>?` (#1844)** — `targetFps`, `depthSensor`, `stereoCamera`
 * — to match the underlying ARCore `CameraConfigFilter.set*(EnumSet)` API symmetrically.
 * Pass a single-element `setOf(X)` for the common "require only X" case (singleton-style),
 * or a multi-element set for "any of X / Y" semantics. `null` (default) means "don't filter
 * on that axis".
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
     *
     * Must be either `null` or a non-empty set. An empty set is a programmer error: the
     * native [java.util.EnumSet.copyOf] call inside [build] would throw
     * [IllegalArgumentException], and historically the surrounding [runCatching] silently
     * fell back to the session's default camera config — which on Augmented Faces sessions
     * is FRONT-facing, so a developer requesting back-only got the front camera with no
     * signal (#1845). The non-empty invariant is enforced at session-creation time inside
     * [cameraConfigFilter].
     */
    var targetFps: Set<CameraConfig.TargetFps>? = null

    /**
     * Restrict to configs whose hardware depth-sensor usage matches **any of** the supplied
     * [CameraConfig.DepthSensorUsage] values. Default `null` — ARCore returns both with/without
     * depth-sensor configs and picks per-device defaults. Pass
     * `setOf(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)` on TOF-equipped devices (S20 Ultra,
     * Pixel 4 XL, etc.) for higher-quality depth maps.
     *
     * Always a `Set<…>?` (#1844) — symmetric with [targetFps] / [stereoCamera] and matches the
     * underlying ARCore `setDepthSensorUsage(EnumSet)` API. Pass `setOf(X)` for "only X".
     */
    var depthSensor: Set<CameraConfig.DepthSensorUsage>? = null

    /**
     * Restrict to configs whose hardware stereo-camera usage matches **any of** the supplied
     * [CameraConfig.StereoCameraUsage] values. Default `null` — ARCore picks per-device
     * defaults. Pass `setOf(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE)` for devices with
     * dual-camera AR (e.g. some Samsung S-series back cameras) when you need the wider FOV /
     * stereo-baselined depth.
     *
     * Always a `Set<…>?` (#1844) — symmetric with [targetFps] / [depthSensor] and matches the
     * underlying ARCore `setStereoCameraUsage(EnumSet)` API. Pass `setOf(X)` for "only X".
     */
    var stereoCamera: Set<CameraConfig.StereoCameraUsage>? = null

    /**
     * Internal: builds the native [CameraConfigFilter] for [session].
     *
     * Each unset property (`null`) is left at the ARCore default; set ones are forwarded via
     * the underlying setter. The filter is created from `session` because every native filter
     * is bound to its session — apps must not reuse a filter across sessions.
     *
     * **Validation (#1845):** every set property must be non-empty. `EnumSet.copyOf(emptySet)`
     * throws `IllegalArgumentException`, and silently swallowing that in [cameraConfigFilter]
     * would degrade to the session default — which on some devices is the FRONT-facing config,
     * a privacy concern when the developer asked for back-only. We fail fast here instead.
     */
    internal fun build(session: Session): CameraConfigFilter {
        require(targetFps == null || targetFps!!.isNotEmpty()) {
            "cameraConfigFilter: targetFps must be null or non-empty; emptySet would degrade " +
                "to the session default (potentially the front camera)."
        }
        require(depthSensor == null || depthSensor!!.isNotEmpty()) {
            "cameraConfigFilter: depthSensor must be null or non-empty; emptySet would degrade " +
                "to the session default."
        }
        require(stereoCamera == null || stereoCamera!!.isNotEmpty()) {
            "cameraConfigFilter: stereoCamera must be null or non-empty; emptySet would degrade " +
                "to the session default."
        }
        val filter = CameraConfigFilter(session)
        facing?.let { filter.setFacingDirection(it) }
        targetFps?.let { filter.setTargetFps(EnumSet.copyOf(it)) }
        depthSensor?.let { filter.setDepthSensorUsage(EnumSet.copyOf(it)) }
        stereoCamera?.let { filter.setStereoCameraUsage(EnumSet.copyOf(it)) }
        return filter
    }

    /**
     * Scalar convenience for [targetFps] — the common "require only this FPS" case (#1957).
     *
     * Equivalent to `targetFps = setOf(value, *rest)`. Pass extra values for "any of" semantics.
     * The [Set]-typed [targetFps] property stays the canonical API (#1844); this overload just
     * removes the `setOf(…)` boilerplate at single-sensor call sites.
     *
     * ```kotlin
     * cameraConfigFilter { targetFps(CameraConfig.TargetFps.TARGET_FPS_60) }
     * ```
     */
    fun targetFps(value: CameraConfig.TargetFps, vararg rest: CameraConfig.TargetFps) {
        targetFps = setOf(value, *rest)
    }

    /**
     * Scalar convenience for [depthSensor] — the common "require only this usage" case (#1957).
     *
     * Equivalent to `depthSensor = setOf(value, *rest)`. The [Set]-typed [depthSensor] property
     * stays the canonical API (#1844); this overload just removes the `setOf(…)` boilerplate at
     * single-sensor call sites.
     *
     * ```kotlin
     * cameraConfigFilter { depthSensor(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) }
     * ```
     */
    fun depthSensor(
        value: CameraConfig.DepthSensorUsage,
        vararg rest: CameraConfig.DepthSensorUsage
    ) {
        depthSensor = setOf(value, *rest)
    }

    /**
     * Scalar convenience for [stereoCamera] — the common "require only this usage" case (#1957).
     *
     * Equivalent to `stereoCamera = setOf(value, *rest)`. The [Set]-typed [stereoCamera] property
     * stays the canonical API (#1844); this overload just removes the `setOf(…)` boilerplate at
     * single-sensor call sites.
     *
     * ```kotlin
     * cameraConfigFilter { stereoCamera(CameraConfig.StereoCameraUsage.REQUIRE_AND_USE) }
     * ```
     */
    fun stereoCamera(
        value: CameraConfig.StereoCameraUsage,
        vararg rest: CameraConfig.StereoCameraUsage
    ) {
        stereoCamera = setOf(value, *rest)
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
    // Validate developer invariants once at builder time so the failure is observed at the call
    // site that wrote the bad DSL, not on every session-creation invocation (#1845).
    require(builder.targetFps == null || builder.targetFps!!.isNotEmpty()) {
        "cameraConfigFilter: targetFps must be either null or a non-empty set — " +
            "an empty set silently degrades to the session's default camera config, " +
            "which on Augmented Faces sessions is FRONT-facing."
    }
    return { session ->
        // Narrow the catch to the documented ARCore failure point —
        // [Session.getSupportedCameraConfigs] is JNI-backed and may legitimately fail on a
        // session in an odd state (#1845). Builder errors (e.g. EnumSet.copyOf on an empty set,
        // any IllegalArgumentException ARCore raises from the filter setters) must propagate so
        // misuse is caught at dev time, not silently degraded to the session default — which
        // on many devices is FRONT-facing or non-depth, a privacy regression. See also the
        // `targetFps` builder field's KDoc for the same rationale.
        val filter = builder.build(session)
        val configs = try {
            session.getSupportedCameraConfigs(filter)
        } catch (e: RuntimeException) {
            null
        }
        configs?.maxByOrNull {
            it.imageSize.width.toLong() * it.imageSize.height.toLong()
        } ?: session.cameraConfig
    }
}
