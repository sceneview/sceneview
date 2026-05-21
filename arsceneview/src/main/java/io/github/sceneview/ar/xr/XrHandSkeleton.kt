package io.github.sceneview.ar.xr

import io.github.sceneview.math.Position
import kotlin.math.sqrt

/**
 * Pure-Kotlin hand-skeleton topology and joint math for [XrHandNode].
 *
 * This file deliberately contains **no Android, Filament or `androidx.xr.arcore`
 * references** — every symbol operates on SceneView's own [Position] (a
 * `Float3`) and plain primitives. That keeps the joint geometry unit-testable
 * on a stock JVM with no XR runtime on the classpath: the JVM tests in
 * `XrHandSkeletonTest` exercise it directly.
 *
 * The node wrapper ([XrHandNode]) is the only place that touches the upstream
 * preview `Hand` / `HandJointType` / `Pose` types — it converts the per-joint
 * poses into [Position]s and hands them to the functions here to derive the
 * bone segments and the rendered skeleton.
 *
 * Preview status: the joint enumeration mirrors `androidx.xr.arcore.HandJointType`
 * (`1.0.0-alpha14`); the upstream is alpha and may add / rename joints. See
 * [arsceneview/docs/JETPACK-XR-INTEGRATION.md](https://github.com/sceneview/sceneview/blob/main/arsceneview/docs/JETPACK-XR-INTEGRATION.md).
 */

/**
 * Stable identifiers for the hand joints SceneView's skeleton renderer draws.
 *
 * These mirror the subset of `androidx.xr.arcore.HandJointType` that has a
 * defined position in the bone topology below. The upstream enum carries ~26
 * joints per hand; [XrHandSkeleton.BONES] connects the ones that form the
 * visible finger / palm structure.
 *
 * The [ordinal] of each entry is also its index in the contiguous joint array
 * produced by [XrHandSkeleton.jointCount], so callers can use a flat
 * `Array<Position?>` keyed by `joint.ordinal` instead of a map.
 */
enum class XrHandJoint {
    WRIST,
    PALM,

    THUMB_METACARPAL,
    THUMB_PROXIMAL,
    THUMB_DISTAL,
    THUMB_TIP,

    INDEX_METACARPAL,
    INDEX_PROXIMAL,
    INDEX_INTERMEDIATE,
    INDEX_DISTAL,
    INDEX_TIP,

    MIDDLE_METACARPAL,
    MIDDLE_PROXIMAL,
    MIDDLE_INTERMEDIATE,
    MIDDLE_DISTAL,
    MIDDLE_TIP,

    RING_METACARPAL,
    RING_PROXIMAL,
    RING_INTERMEDIATE,
    RING_DISTAL,
    RING_TIP,

    LITTLE_METACARPAL,
    LITTLE_PROXIMAL,
    LITTLE_INTERMEDIATE,
    LITTLE_DISTAL,
    LITTLE_TIP,
}

/**
 * Which hand a [XrHandNode] tracks. Mirrors the upstream `Hand.left(session)` /
 * `Hand.right(session)` accessor split without exposing the preview type in the
 * public enum.
 */
enum class XrHandedness { LEFT, RIGHT }

/**
 * A directed bone segment between two [XrHandJoint]s, rendered as a single line
 * in the skeleton overlay.
 */
data class XrHandBone(val from: XrHandJoint, val to: XrHandJoint)

/**
 * Pure-logic helpers describing the hand skeleton: the bone topology and the
 * small amount of vector math [XrHandNode] needs to lay joints / bones out.
 *
 * Stateless `object` — everything here is referentially transparent and
 * trivially unit-testable.
 */
object XrHandSkeleton {

    /** Number of distinct joints the renderer tracks — `XrHandJoint.entries.size`. */
    val jointCount: Int = XrHandJoint.entries.size

    /**
     * The bone topology: each finger is a chain from its metacarpal joint out
     * to the tip, plus a palm fan connecting the wrist to every metacarpal.
     *
     * Ordered finger-by-finger (thumb → little) so a renderer that colours
     * bones per finger can slice this list deterministically.
     */
    val BONES: List<XrHandBone> = buildList {
        // Palm fan — wrist out to each finger's metacarpal root.
        add(XrHandBone(XrHandJoint.WRIST, XrHandJoint.THUMB_METACARPAL))
        add(XrHandBone(XrHandJoint.WRIST, XrHandJoint.INDEX_METACARPAL))
        add(XrHandBone(XrHandJoint.WRIST, XrHandJoint.MIDDLE_METACARPAL))
        add(XrHandBone(XrHandJoint.WRIST, XrHandJoint.RING_METACARPAL))
        add(XrHandBone(XrHandJoint.WRIST, XrHandJoint.LITTLE_METACARPAL))

        // Thumb — 3 segments (no intermediate phalanx).
        addChain(
            XrHandJoint.THUMB_METACARPAL,
            XrHandJoint.THUMB_PROXIMAL,
            XrHandJoint.THUMB_DISTAL,
            XrHandJoint.THUMB_TIP,
        )
        // Index, middle, ring, little — 4 segments each.
        addChain(
            XrHandJoint.INDEX_METACARPAL,
            XrHandJoint.INDEX_PROXIMAL,
            XrHandJoint.INDEX_INTERMEDIATE,
            XrHandJoint.INDEX_DISTAL,
            XrHandJoint.INDEX_TIP,
        )
        addChain(
            XrHandJoint.MIDDLE_METACARPAL,
            XrHandJoint.MIDDLE_PROXIMAL,
            XrHandJoint.MIDDLE_INTERMEDIATE,
            XrHandJoint.MIDDLE_DISTAL,
            XrHandJoint.MIDDLE_TIP,
        )
        addChain(
            XrHandJoint.RING_METACARPAL,
            XrHandJoint.RING_PROXIMAL,
            XrHandJoint.RING_INTERMEDIATE,
            XrHandJoint.RING_DISTAL,
            XrHandJoint.RING_TIP,
        )
        addChain(
            XrHandJoint.LITTLE_METACARPAL,
            XrHandJoint.LITTLE_PROXIMAL,
            XrHandJoint.LITTLE_INTERMEDIATE,
            XrHandJoint.LITTLE_DISTAL,
            XrHandJoint.LITTLE_TIP,
        )
    }

    private fun MutableList<XrHandBone>.addChain(vararg joints: XrHandJoint) {
        for (i in 0 until joints.size - 1) {
            add(XrHandBone(joints[i], joints[i + 1]))
        }
    }

    /**
     * Euclidean distance between two joint positions, in meters.
     *
     * Returns `0f` when either endpoint is `null` (joint not currently tracked)
     * so callers can hide the bone rather than draw a degenerate segment.
     */
    fun boneLength(from: Position?, to: Position?): Float {
        if (from == null || to == null) return 0f
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Midpoint between two joint positions — the natural anchor for a bone's
     * geometry (a capsule/line centred between its endpoints).
     *
     * Returns `null` when either endpoint is `null`.
     */
    fun boneMidpoint(from: Position?, to: Position?): Position? {
        if (from == null || to == null) return null
        return Position(
            x = (from.x + to.x) * 0.5f,
            y = (from.y + to.y) * 0.5f,
            z = (from.z + to.z) * 0.5f,
        )
    }

    /**
     * Linearly interpolates between two joint positions by [t].
     *
     * Used to smooth a joint's rendered position across frames: feeding the
     * previous rendered position and the freshly tracked position with a small
     * `t` (e.g. `0.5f`) damps the per-frame jitter the preview tracker emits.
     *
     * [t] is clamped to `0f..1f`; `t = 0f` returns [from], `t = 1f` returns [to].
     * Returns `null` when either endpoint is `null` so an untracked joint never
     * produces a phantom interpolated position.
     */
    fun lerp(from: Position?, to: Position?, t: Float): Position? {
        if (from == null || to == null) return null
        val k = t.coerceIn(0f, 1f)
        return Position(
            x = from.x + (to.x - from.x) * k,
            y = from.y + (to.y - from.y) * k,
            z = from.z + (to.z - from.z) * k,
        )
    }

    /**
     * Sum of every tracked bone's length — a cheap proxy for hand "size" /
     * tracking confidence. A fully tracked adult hand is roughly `0.9f..1.4f`
     * meters of total bone; a near-zero total means almost nothing is tracked.
     *
     * [joints] is indexed by [XrHandJoint.ordinal]; a `null` slot is an
     * untracked joint and its incident bones contribute `0f`.
     */
    fun totalBoneLength(joints: Array<Position?>): Float {
        require(joints.size == jointCount) {
            "joints array must have $jointCount slots, got ${joints.size}"
        }
        var total = 0f
        for (bone in BONES) {
            total += boneLength(joints[bone.from.ordinal], joints[bone.to.ordinal])
        }
        return total
    }

    /**
     * Counts how many of the [jointCount] slots are currently tracked
     * (non-`null`). `0` means the hand is fully untracked; [jointCount] means
     * every joint has a pose this frame.
     */
    fun trackedJointCount(joints: Array<Position?>): Int =
        joints.count { it != null }
}
