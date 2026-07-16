package io.github.sceneview.animation

import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.equals
import io.github.sceneview.math.slerp
import kotlin.math.abs

/**
 * Platform-independent smooth transform interpolation state.
 *
 * Tracks a current transform being interpolated toward a target transform using
 * speed-scaled slerp/lerp. Pure data — no mutation, no side effects.
 *
 * **Hot-path note:** [updateSmoothTransform] feeds `current`/`target` to the
 * `slerp(start: Transform, end: Transform, …)` overload, which decomposes both operands
 * into position/quaternion/scale internally — 6 `Mat4` decompositions per tick. Callers
 * that already hold (or can cache) the decomposed components — e.g. a
 * [io.github.sceneview.node.Node]'s cached local TRS (#2187) — should prefer
 * [SmoothTransformTRSState] instead, which calls the pre-decomposed `slerp` overload
 * directly and pays zero decompositions per tick.
 *
 * @param current Current transform value.
 * @param target Target transform to interpolate toward (null = no active interpolation).
 * @param speed Interpolation speed multiplier. Higher = faster convergence.
 * @param convergenceDelta Threshold below which the transform is considered "arrived".
 */
data class SmoothTransformState(
    val current: Transform,
    val target: Transform? = null,
    val speed: Float = 10f,
    val convergenceDelta: Float = 0.001f
)

/**
 * Target position/quaternion/scale for a [SmoothTransformTRSState] interpolation.
 */
data class SmoothTransformTRSTarget(
    val position: Position,
    val quaternion: Quaternion,
    val scale: Scale
)

/**
 * Pre-decomposed TRS-tuple variant of [SmoothTransformState] — tracks the interpolated
 * position/quaternion/scale directly instead of a [Transform] (`Mat4`).
 *
 * [SmoothTransformState] stores `current`/`target` as `Transform`, so every
 * [updateSmoothTransform] tick round-trips through the `Transform` overload of
 * [io.github.sceneview.math.slerp], which decomposes both operands into position/
 * quaternion/scale internally (a polar decomposition plus 3 sqrt-based column-length
 * extractions, twice — 6 decompositions per tick). [SmoothTransformTRSState] stores the
 * components directly so [updateSmoothTransform] can call the pre-decomposed
 * `slerp(startPosition, startQuaternion, startScale, …)` overload with **zero**
 * decompositions per tick — the same pattern [io.github.sceneview.node.Node] uses for its
 * cached local TRS (#2187).
 *
 * Prefer this over [SmoothTransformState] on a per-frame hot path when the caller already
 * holds (or can cache) position/quaternion/scale. [SmoothTransformState] remains available
 * — unchanged — for callers that naturally work in `Transform`/`Mat4` terms.
 *
 * @param position Current position.
 * @param quaternion Current rotation.
 * @param scale Current scale.
 * @param target Target position/quaternion/scale to interpolate toward (null = no active
 * interpolation).
 * @param speed Interpolation speed multiplier. Higher = faster convergence.
 * @param convergenceDelta Threshold below which the transform is considered "arrived".
 */
data class SmoothTransformTRSState(
    val position: Position,
    val quaternion: Quaternion = Quaternion(),
    val scale: Scale = Scale(1f),
    val target: SmoothTransformTRSTarget? = null,
    val speed: Float = 10f,
    val convergenceDelta: Float = 0.001f
)

/**
 * Result of a smooth transform update step.
 *
 * @param state The updated smooth transform state.
 * @param arrived True if the interpolation just completed (target was reached).
 */
data class SmoothTransformResult(
    val state: SmoothTransformState,
    val arrived: Boolean
)

/**
 * Advance the smooth transform interpolation by [deltaSeconds].
 *
 * Pure function — no mutation, no side effects.
 *
 * - If no target is set, returns the state unchanged with `arrived = false`.
 * - If the interpolated transform is within [SmoothTransformState.convergenceDelta]
 *   of the target, snaps to the target and clears it (`arrived = true`).
 * - Otherwise, returns the slerp'd intermediate transform.
 *
 * @param state Current interpolation state.
 * @param deltaSeconds Time step in seconds since last frame.
 * @return Updated state and whether the target was just reached.
 */
fun updateSmoothTransform(
    state: SmoothTransformState,
    deltaSeconds: Double
): SmoothTransformResult {
    val target = state.target ?: return SmoothTransformResult(state, arrived = false)

    if (target == state.current) {
        return SmoothTransformResult(
            state = state.copy(target = null),
            arrived = true
        )
    }

    val interpolated = slerp(
        start = state.current,
        end = target,
        deltaSeconds = deltaSeconds,
        speed = state.speed
    )

    return if (interpolated.equals(state.current, delta = state.convergenceDelta)) {
        // Close enough — snap to target
        SmoothTransformResult(
            state = state.copy(current = target, target = null),
            arrived = true
        )
    } else {
        SmoothTransformResult(
            state = state.copy(current = interpolated),
            arrived = false
        )
    }
}

/**
 * Set a new target for smooth interpolation.
 *
 * @param state Current state.
 * @param target New target transform.
 * @param speed Optional new speed (keeps current speed if null).
 * @return Updated state with the new target.
 */
fun setSmoothTarget(
    state: SmoothTransformState,
    target: Transform,
    speed: Float? = null
): SmoothTransformState = state.copy(
    target = target,
    speed = speed ?: state.speed
)

/**
 * Cancel any active smooth interpolation without snapping to the target.
 */
fun cancelSmooth(state: SmoothTransformState): SmoothTransformState =
    state.copy(target = null)

/**
 * Result of a [SmoothTransformTRSState] update step.
 *
 * @param state The updated smooth transform TRS state.
 * @param arrived True if the interpolation just completed (target was reached).
 */
data class SmoothTransformTRSResult(
    val state: SmoothTransformTRSState,
    val arrived: Boolean
)

/**
 * Advance the smooth transform interpolation by [deltaSeconds] — TRS-tuple variant.
 *
 * Pure function — no mutation, no side effects. Behaviourally equivalent to
 * [updateSmoothTransform] (Transform overload) — same frame-rate-independent slerp/lerp
 * factor, same convergence semantics — but never builds or decomposes a [Transform], so it
 * pays zero `Mat4` decompositions per tick (see [SmoothTransformTRSState]).
 *
 * - If no target is set, returns the state unchanged with `arrived = false`.
 * - If the interpolated position/quaternion/scale are within
 *   [SmoothTransformTRSState.convergenceDelta] of the current ones, snaps to the target and
 *   clears it (`arrived = true`).
 * - Otherwise, returns the slerp'd/lerp'd intermediate components.
 *
 * @param state Current interpolation state.
 * @param deltaSeconds Time step in seconds since last frame.
 * @return Updated state and whether the target was just reached.
 */
fun updateSmoothTransform(
    state: SmoothTransformTRSState,
    deltaSeconds: Double
): SmoothTransformTRSResult {
    val target = state.target ?: return SmoothTransformTRSResult(state, arrived = false)

    if (target.position == state.position &&
        target.quaternion == state.quaternion &&
        target.scale == state.scale
    ) {
        return SmoothTransformTRSResult(
            state = state.copy(target = null),
            arrived = true
        )
    }

    val (position, quaternion, scale) = slerp(
        startPosition = state.position,
        startQuaternion = state.quaternion,
        startScale = state.scale,
        endPosition = target.position,
        endQuaternion = target.quaternion,
        endScale = target.scale,
        deltaSeconds = deltaSeconds,
        speed = state.speed
    )

    val converged = position.equals(state.position, state.convergenceDelta) &&
        scale.equals(state.scale, state.convergenceDelta) &&
        quaternion.convergesTo(state.quaternion, state.convergenceDelta)

    return if (converged) {
        // Close enough — snap to target
        SmoothTransformTRSResult(
            state = state.copy(
                position = target.position,
                quaternion = target.quaternion,
                scale = target.scale,
                target = null
            ),
            arrived = true
        )
    } else {
        SmoothTransformTRSResult(
            state = state.copy(position = position, quaternion = quaternion, scale = scale),
            arrived = false
        )
    }
}

/**
 * Set a new target for smooth interpolation — TRS-tuple variant.
 *
 * @param state Current state.
 * @param target New target position/quaternion/scale.
 * @param speed Optional new speed (keeps current speed if null).
 * @return Updated state with the new target.
 */
fun setSmoothTarget(
    state: SmoothTransformTRSState,
    target: SmoothTransformTRSTarget,
    speed: Float? = null
): SmoothTransformTRSState = state.copy(
    target = target,
    speed = speed ?: state.speed
)

/**
 * Cancel any active smooth interpolation without snapping to the target — TRS-tuple variant.
 */
fun cancelSmooth(state: SmoothTransformTRSState): SmoothTransformTRSState =
    state.copy(target = null)

/**
 * Approximate quaternion equality via `|dot| ≈ 1`, tolerating the double-cover sign
 * ambiguity (`q` and `-q` represent the same rotation) — the same comparison approach used
 * elsewhere for TRS-tuple-produced quaternions (see `MathTest`'s TRS-tuple `slerp` checks).
 */
private fun Quaternion.convergesTo(other: Quaternion, delta: Float): Boolean {
    val dot = x * other.x + y * other.y + z * other.z + w * other.w
    return abs(dot) >= 1f - delta
}
