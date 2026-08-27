package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.scale

/**
 * Platform-independent local↔world coordinate conversions.
 *
 * These functions convert positions, rotations, scales, and full transforms
 * between a node's local space and world space using the node's world transform
 * matrix. No renderer dependency — works with any engine that provides a
 * 4×4 world transform.
 *
 * Usage pattern:
 * ```
 * val worldToLocal = inverse(node.worldTransform)
 * val localPos = worldToLocalPosition(worldPos, worldToLocal)
 * ```
 */

// --- Position ---

/**
 * Convert a world-space position to this node's local space.
 *
 * @param worldPosition Position in world space.
 * @param worldToLocal Inverse of the node's world transform (`inverse(worldTransform)`).
 */
fun worldToLocalPosition(worldPosition: Position, worldToLocal: Transform): Position =
    worldToLocal * worldPosition

/**
 * Convert a local-space position to world space.
 *
 * @param localPosition Position in this node's local space.
 * @param worldTransform The node's world transform matrix.
 */
fun localToWorldPosition(localPosition: Position, worldTransform: Transform): Position =
    worldTransform * localPosition

// --- Direction ---

/**
 * Convert a world-space **direction** to this node's local space.
 *
 * A direction is a free vector, not a point: it must be transformed with `w = 0` so the
 * matrix translation is dropped. Using the `Mat4 * Float3` point operator instead would
 * add the node's world position to the vector, which is wrong for a ray direction and
 * gives a nonsense answer for anything that reads its sign (facing tests, back-face
 * culling, …).
 *
 * The result is **not normalised**: a non-uniform parent scale stretches it. Every
 * component keeps its sign under a positive scale, so a facing test on one axis stays
 * valid without the normalise.
 *
 * @param worldDirection Direction in world space.
 * @param worldToLocal Inverse of the node's world transform (`inverse(worldTransform)`).
 */
fun worldToLocalDirection(worldDirection: Direction, worldToLocal: Transform): Direction =
    (worldToLocal * Float4(worldDirection, 0.0f)).xyz

/**
 * Convert a local-space **direction** to world space. See [worldToLocalDirection] for why
 * this is not the position conversion.
 *
 * @param localDirection Direction in this node's local space.
 * @param worldTransform The node's world transform matrix.
 */
fun localToWorldDirection(localDirection: Direction, worldTransform: Transform): Direction =
    (worldTransform * Float4(localDirection, 0.0f)).xyz

// --- Quaternion ---

/**
 * Convert a world-space quaternion to this node's local space using the parent's
 * cached world quaternion — the **direct, allocation-light** path.
 *
 * A rotation-only conversion never needs a 4×4 matrix: the local rotation is simply
 * `inverse(parentWorldRotation) * worldRotation`. This overload therefore skips the
 * Mat4 polar decomposition that the [worldToLocal: Transform][worldToLocalQuaternion]
 * overload pays on every call (#2267). Prefer it whenever the parent's world
 * quaternion is already known — e.g. it is cached on the node post-#2264.
 *
 * @param parentWorldQuaternion The parent node's world-space quaternion (cached).
 * @param worldQuaternion Quaternion in world space.
 */
fun worldToLocalQuaternion(
    parentWorldQuaternion: Quaternion,
    worldQuaternion: Quaternion
): Quaternion = inverse(parentWorldQuaternion) * worldQuaternion

/**
 * Convert a local-space quaternion to world space using the parent's cached world
 * quaternion — the **direct, allocation-light** path.
 *
 * The world rotation is `parentWorldRotation * localRotation`, so no 4×4 matrix or
 * polar decomposition is required (#2267). Prefer it whenever the parent's world
 * quaternion is already known — e.g. it is cached on the node post-#2264.
 *
 * @param parentWorldQuaternion The parent node's world-space quaternion (cached).
 * @param localQuaternion Quaternion in this node's local space.
 */
fun localToWorldQuaternion(
    parentWorldQuaternion: Quaternion,
    localQuaternion: Quaternion
): Quaternion = parentWorldQuaternion * localQuaternion

/**
 * Convert a world-space quaternion to this node's local space.
 *
 * Runs a Mat4 polar decomposition (`worldToLocal.quaternion`) on every call. Prefer
 * the [worldToLocalQuaternion] overload taking the parent's cached world quaternion,
 * which skips the decomposition (#2267).
 *
 * @param worldQuaternion Quaternion in world space.
 * @param worldToLocal Inverse of the node's world transform.
 */
@Deprecated(
    "Decomposes a Mat4 on every call. Use worldToLocalQuaternion(parentWorldQuaternion, " +
        "worldQuaternion) with the parent's cached world quaternion instead (#2267).",
    ReplaceWith("worldToLocalQuaternion(parentWorldQuaternion, worldQuaternion)")
)
fun worldToLocalQuaternion(
    worldQuaternion: Quaternion,
    worldToLocal: Transform
): Quaternion = worldToLocal.quaternion * worldQuaternion

/**
 * Convert a local-space quaternion to world space.
 *
 * Runs a Mat4 polar decomposition (`worldTransform.quaternion`) on every call. Prefer
 * the [localToWorldQuaternion] overload taking the parent's cached world quaternion,
 * which skips the decomposition (#2267).
 *
 * @param localQuaternion Quaternion in this node's local space.
 * @param worldTransform The node's world transform matrix.
 */
@Deprecated(
    "Decomposes a Mat4 on every call. Use localToWorldQuaternion(parentWorldQuaternion, " +
        "localQuaternion) with the parent's cached world quaternion instead (#2267).",
    ReplaceWith("localToWorldQuaternion(parentWorldQuaternion, localQuaternion)")
)
fun localToWorldQuaternion(
    localQuaternion: Quaternion,
    worldTransform: Transform
): Quaternion = worldTransform.quaternion * localQuaternion

// --- Rotation (Euler angles) ---

/**
 * Convert a world-space rotation (Euler angles) to this node's local space using the
 * parent's cached world quaternion — skips the Mat4 polar decomposition (#2267).
 *
 * @param parentWorldQuaternion The parent node's world-space quaternion (cached).
 * @param worldRotation Rotation in degrees (world space).
 */
fun worldToLocalRotation(parentWorldQuaternion: Quaternion, worldRotation: Rotation): Rotation =
    worldToLocalQuaternion(parentWorldQuaternion, worldRotation.toQuaternion()).toRotation()

/**
 * Convert a local-space rotation (Euler angles) to world space using the parent's
 * cached world quaternion — skips the Mat4 polar decomposition (#2267).
 *
 * @param parentWorldQuaternion The parent node's world-space quaternion (cached).
 * @param localRotation Rotation in degrees (local space).
 */
fun localToWorldRotation(parentWorldQuaternion: Quaternion, localRotation: Rotation): Rotation =
    localToWorldQuaternion(parentWorldQuaternion, localRotation.toQuaternion()).toRotation()

/**
 * Convert a world-space rotation (Euler angles) to this node's local space.
 *
 * Decomposes a Mat4 on every call. Prefer the [worldToLocalRotation] overload taking
 * the parent's cached world quaternion (#2267).
 *
 * @param worldRotation Rotation in degrees (world space).
 * @param worldToLocal Inverse of the node's world transform.
 */
@Deprecated(
    "Decomposes a Mat4 on every call. Use worldToLocalRotation(parentWorldQuaternion, " +
        "worldRotation) with the parent's cached world quaternion instead (#2267).",
    ReplaceWith("worldToLocalRotation(parentWorldQuaternion, worldRotation)")
)
@Suppress("DEPRECATION")
fun worldToLocalRotation(worldRotation: Rotation, worldToLocal: Transform): Rotation =
    worldToLocalQuaternion(worldRotation.toQuaternion(), worldToLocal).toRotation()

/**
 * Convert a local-space rotation (Euler angles) to world space.
 *
 * Decomposes a Mat4 on every call. Prefer the [localToWorldRotation] overload taking
 * the parent's cached world quaternion (#2267).
 *
 * @param localRotation Rotation in degrees (local space).
 * @param worldTransform The node's world transform matrix.
 */
@Deprecated(
    "Decomposes a Mat4 on every call. Use localToWorldRotation(parentWorldQuaternion, " +
        "localRotation) with the parent's cached world quaternion instead (#2267).",
    ReplaceWith("localToWorldRotation(parentWorldQuaternion, localRotation)")
)
@Suppress("DEPRECATION")
fun localToWorldRotation(localRotation: Rotation, worldTransform: Transform): Rotation =
    localToWorldQuaternion(localRotation.toQuaternion(), worldTransform).toRotation()

// --- Scale ---

/**
 * Convert a world-space scale to this node's local space.
 *
 * A scale is a magnitude triple, not a homogeneous point: it must compose with the
 * transform's **basis-vector lengths** (`Mat4.scale`), never via the `Mat4 * Float3`
 * point operator (which folds in the matrix translation and rotation — wrong for a
 * scale). Because `worldToLocal.scale == 1 / parentWorldScale`, multiplying the world
 * scale by it divides out the parent's world scale.
 *
 * @param worldScale Scale in world space.
 * @param worldToLocal Inverse of the node's world transform.
 */
fun worldToLocalScale(worldScale: Scale, worldToLocal: Transform): Scale =
    worldScale * worldToLocal.scale

/**
 * Convert a local-space scale to world space.
 *
 * Composes the local scale with the world transform's **basis-vector lengths**
 * (`Mat4.scale`), never via the `Mat4 * Float3` point operator (which would leak the
 * matrix translation and rotation into the result — wrong for a scale).
 *
 * @param localScale Scale in this node's local space.
 * @param worldTransform The node's world transform matrix.
 */
fun localToWorldScale(localScale: Scale, worldTransform: Transform): Scale =
    worldTransform.scale * localScale

// --- Full Transform ---

/**
 * Convert a world-space transform to this node's local space.
 *
 * @param worldTransformToConvert Transform matrix in world space to convert.
 * @param worldToLocal Inverse of the node's world transform.
 */
fun worldToLocalTransform(
    worldTransformToConvert: Transform,
    worldToLocal: Transform
): Transform = worldToLocal * worldTransformToConvert

/**
 * Convert a local-space transform to world space.
 *
 * @param localTransform Transform matrix in this node's local space.
 * @param worldTransform The node's world transform matrix.
 */
fun localToWorldTransform(
    localTransform: Transform,
    worldTransform: Transform
): Transform = worldTransform * localTransform

// --- Convenience: compute worldToLocal from worldTransform ---

/**
 * Compute the inverse world transform (world-to-local matrix).
 *
 * @param worldTransform The node's world transform matrix.
 * @return The inverse matrix for converting world→local coordinates.
 */
fun computeWorldToLocal(worldTransform: Transform): Transform = inverse(worldTransform)
