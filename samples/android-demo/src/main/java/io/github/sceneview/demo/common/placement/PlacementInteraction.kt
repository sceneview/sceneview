package io.github.sceneview.demo.common.placement

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.degrees
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.demo.AR_CAMERA_INIT_SCRIM_TIMEOUT_MS
import io.github.sceneview.math.Rotation
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The headless core of the tap-to-place *interaction* model
 * ([#3326](https://github.com/sceneview/sceneview/issues/3326)) — everything about
 * placing, moving, resizing and coaching that can be decided without Compose, Filament
 * or an ARCore session, so it is pinned by pure-JVM unit tests instead of by a device.
 *
 * The reference is Google Scene Viewer, which is what a user comparing SceneView to "what
 * everyone else does" is actually comparing against:
 *
 *  - the model is placed at its **real-world size**, and the pinch gesture reports a
 *    percentage of that size which **snaps back to 100 %** — [PlacementScale];
 *  - it **grows into place** rather than popping in at full size — [PlacementEntrance];
 *  - the screen says **one** short thing at a time, and stops talking once the user has
 *    understood the interaction — [placementCoaching];
 *  - a two-finger twist turns the object **on the floor**, never off it —
 *    [PlacementRotation] for the algebra, [PlacementHierarchy] for the split of roles
 *    across the two nodes that makes the algebra hold.
 */

// ── Rotation ─────────────────────────────────────────────────────────────────────────────

/**
 * Yaw-only rotation semantics for a placed model
 * ([#3735](https://github.com/sceneview/sceneview/issues/3735)).
 *
 * `NodeGestureDelegate` builds a two-finger twist as `Quaternion.fromAxisAngle(Y_AXIS, …)`
 * and applies it with `node.quaternion *= rotationDelta` — a **right**-multiplication, so
 * the delta is expressed in the **node's own local frame**. That is the correct and
 * documented SDK behaviour, and it is a yaw only for as long as the node's local Y still
 * points along the anchor's up axis.
 *
 * The demo used to break exactly that precondition. The per-asset placement correction
 * (`DemoMath.placementRotationFor` — `Rotation(x = -90f)` for the Z-up Khronos helmet) was
 * applied **to the editable node itself**, which lays that node's local Y flat: `Rx(-90)`
 * maps `(0, 1, 0)` onto `(0, 0, -1)`. Twisting then turned the helmet about a *horizontal*
 * axis — it tumbled instead of pivoting. Every other bundled model has a zero correction,
 * which is why only the helmet was ever reported.
 *
 * The fix is structural, not arithmetic: the editable node keeps an identity-then-pure-yaw
 * rotation, and the asset correction moves to a **non-editable content child** underneath
 * it. The two orientations then compose in the other order, and the twist is a yaw about
 * the anchor's up axis whatever the asset needed to stand up. See `PlacedModelNode`.
 *
 * The invariant every function here exists to pin: the rotation a twist applies **in the
 * anchor's frame** — `current ∘ rest⁻¹`, i.e. [appliedInAnchorFrame] — must be a pure yaw,
 * so a vector that was vertical before the twist is still vertical after it.
 */
object PlacementRotation {

    /** The anchor's up axis, and the only axis a twist may turn the object around. */
    val UP = Float3(y = 1f)

    /**
     * Reference direction used to read a yaw angle back out of a quaternion. Mirrors the
     * SDK's `quaternionYawDegrees`, which projects the rotated `(0, 0, 1)` onto the XZ
     * plane — meaningful for the pure-yaw quaternions this object deals in.
     */
    private val FORWARD = Float3(z = 1f)

    /** The rotation a twist of [degrees] contributes, about [UP]. */
    fun yawDelta(degrees: Float): Quaternion = Quaternion.fromAxisAngle(UP, degrees)

    /**
     * Accumulates one twist onto the editable node's local rotation.
     *
     * [pivot] starts at identity and only ever gains [yawDelta]s, so it stays a pure yaw
     * and its local Y stays the anchor's up axis — which is what makes the SDK's
     * right-multiplication the right thing here. Re-normalized each step so a long drag of
     * small deltas cannot accumulate float error into a non-unit quaternion.
     */
    fun twist(pivot: Quaternion, degrees: Float): Quaternion =
        normalize(pivot * yawDelta(degrees))

    /**
     * The content's orientation in the anchor's frame: the editable node's yaw, then the
     * asset's own standing-up correction.
     */
    fun contentOrientation(pivot: Quaternion, assetCorrection: Quaternion): Quaternion =
        normalize(pivot * assetCorrection)

    /**
     * The pre-#3735 composition, kept only so the tests can show what it did: the twist
     * multiplied a node that **already carried** [assetCorrection], putting the correction
     * on the left and the yaw on the right.
     */
    fun legacyContentOrientation(assetCorrection: Quaternion, degrees: Float): Quaternion =
        normalize(assetCorrection * yawDelta(degrees))

    /**
     * The rotation the twist actually applied **in the anchor's frame**: `current ∘ rest⁻¹`.
     *
     * Asset-agnostic on purpose — it never needs to know which axis the model was authored
     * up. Whatever the model's rest pose is, the residual must be a pure yaw.
     */
    fun appliedInAnchorFrame(current: Quaternion, rest: Quaternion): Quaternion =
        normalize(current * inverse(rest))

    /** The yaw of [quaternion], in degrees, in `-180..180`. */
    fun yawDegrees(quaternion: Quaternion): Float {
        val direction = quaternion * FORWARD
        return degrees(atan2(direction.x, direction.z))
    }

    /**
     * How far [quaternion] tips [UP] away from vertical, in degrees — pitch and roll in a
     * single number. Zero for any pure yaw; `0` is the whole contract of this file.
     */
    fun tiltDegrees(quaternion: Quaternion): Float {
        val up = quaternion * UP
        val cosine = (up.y / length(up)).coerceIn(-1f, 1f)
        return degrees(acos(cosine))
    }
}

/**
 * Which node in a placed model's hierarchy carries which editing right, and which one
 * carries the asset's standing-up correction
 * ([#3735](https://github.com/sceneview/sceneview/issues/3735)).
 *
 * [PlacementRotation] pins the *algebra* — that composing a yaw with a correction in this
 * order leaves the twist a yaw. This object pins the *wiring*, and it is the thing
 * `PivotedModelNode` actually reads to build the two nodes: the flags it sets and the rest
 * rotation it gives each node come from here and from nowhere else. Putting the correction
 * back on the node the twist turns is therefore not something a call site can do on its
 * own — it means editing [pivot] or [content], which is what
 * [PlacementHierarchyTest][io.github.sceneview.demo.common.placement.PlacementInteractionTest]
 * asserts against.
 *
 * What this cannot pin is that the composable calls it at all, or that Filament builds the
 * two nodes in that relationship — a Filament node needs an engine and cannot exist on the
 * JVM. That half is the device pass.
 */
object PlacementHierarchy {

    /**
     * The editable node: the one the finger's twist turns.
     *
     * It is deliberately geometry-free, so it is never the node a hit test returns; the
     * gesture reaches it by bubbling up from the content child, which declines rotation.
     * Its rest rotation is identity and it only ever accumulates yaw, so its local Y stays
     * the anchor's up axis — the precondition that makes the SDK's right-multiplied delta
     * a yaw (see [PlacementRotation]).
     *
     * Position is refused so a drag bubbles further up to the `AnchorNode`, the only node
     * that can move in AR; scale is refused so the pinch stops at the content below, which
     * owns the real-world-size percentage.
     */
    fun pivot(): PlacementNodeRole = PlacementNodeRole(
        isEditable = true,
        isPositionEditable = false,
        isRotationEditable = true,
        isScaleEditable = false,
        restRotation = Rotation(),
    )

    /**
     * The content node: the one that renders, and the only one with a collider — so it is
     * the node the finger actually touches, and it must stay `isEditable` for the touch to
     * be treated as an edit rather than leaking to the camera manipulator.
     *
     * It carries [assetCorrection] and refuses rotation, which is the whole fix: the
     * correction can lay this node's local Y flat as much as the asset needs, because no
     * twist is ever applied here.
     */
    fun content(assetCorrection: Rotation): PlacementNodeRole = PlacementNodeRole(
        isEditable = true,
        isPositionEditable = false,
        isRotationEditable = false,
        isScaleEditable = true,
        restRotation = assetCorrection,
    )
}

/**
 * One node's editing rights and rest rotation — see [PlacementHierarchy].
 *
 * Every field maps to a property the SDK's `Node` already has. Note that each `is*Editable`
 * flag is gated by [isEditable] (`get() = isEditable && field`), so `false` there makes the
 * other three moot; and that a `false` flag does not swallow the gesture, it *forwards* it
 * to the parent — which is how a node can absorb the touch and edit nothing.
 */
data class PlacementNodeRole(
    val isEditable: Boolean,
    val isPositionEditable: Boolean,
    val isRotationEditable: Boolean,
    val isScaleEditable: Boolean,
    val restRotation: Rotation,
) {
    /** [restRotation] as the SDK stores it — `Node.rotation`'s setter is `fromEuler`. */
    val restOrientation: Quaternion get() = Quaternion.fromEuler(restRotation)
}

// ── Scale ────────────────────────────────────────────────────────────────────────────────

/**
 * Pinch-to-resize semantics for a placed model.
 *
 * `base` throughout is the node scale that renders the model at its **real-world size**
 * (`PlacementSpec.realWorldSizeMeters` fed to `ModelNode(scaleToUnits = …)`), i.e. 100 %.
 * Everything else is expressed as a multiple of it, which is what makes a "100 %" readout
 * meaningful and what makes the snap possible at all.
 *
 * Before this existed, the demo used `Node.editableScaleRange` — a *fixed* `0.1f..10f`
 * band applied to the raw node scale. That band is meaningless once `scaleToUnits` has
 * already baked an arbitrary fit factor into the scale: a model whose fitted scale is
 * below `0.1` (any glTF authored large, e.g. the Khronos Lantern) had **every** pinch
 * rejected on the first event, because `newScale.x in 0.1f..10f` was already false at
 * rest. Anchoring the band to the model's own base scale makes the same gesture behave
 * identically on every asset.
 */
object PlacementScale {

    /** Smallest allowed size — a quarter of real-world size. */
    const val MIN_FACTOR = 0.25f

    /** Largest allowed size — four times real-world size. */
    const val MAX_FACTOR = 4.0f

    /**
     * Half-width of the band around 100 % inside which the scale is pulled back to exactly
     * real-world size. 6 % is wide enough to be reachable with a two-finger pinch on a
     * phone and narrow enough that a user deliberately sizing to ~110 % is not fought.
     */
    const val SNAP_TOLERANCE = 0.06f

    /** The absolute node-scale band a model may be pinched through, given its [base] scale. */
    fun rangeFor(base: Float): ClosedFloatingPointRange<Float> =
        (base * MIN_FACTOR)..(base * MAX_FACTOR)

    /**
     * The node scale after one pinch event.
     *
     * @param current the node's scale right now.
     * @param base the node scale that renders the model at real-world size (100 %).
     * @param rawFactor the raw `ScaleGestureDetector.scaleFactor` for this event.
     * @param sensitivity damping in `0..1` — the same `Node.scaleGestureSensitivity`
     *   semantics (`0.5` halves the per-event delta), so the feel matches the rest of the
     *   SDK rather than inventing a second curve.
     * @return the clamped, snapped scale to write on the node.
     */
    fun next(
        current: Float,
        base: Float,
        rawFactor: Float,
        sensitivity: Float = 0.5f,
    ): Float {
        if (base <= 0f) return current
        val damped = 1f + (rawFactor - 1f) * sensitivity
        val range = rangeFor(base)
        val clamped = (current * damped).coerceIn(range.start, range.endInclusive)
        // Snap last, so the band edges can never sit inside the snap window and trap the
        // model at 100 % when the user is trying to reach the extremes.
        return snap(clamped, base)
    }

    /**
     * Pulls [scale] to exactly [base] when it is within [SNAP_TOLERANCE] of it. This is the
     * "100 %" detent — the thing that makes returning a resized model to real-world size a
     * gesture rather than a guess.
     */
    fun snap(scale: Float, base: Float): Float {
        if (base <= 0f) return scale
        return if (abs(scale / base - 1f) <= SNAP_TOLERANCE) base else scale
    }

    /** Percentage of real-world size, rounded for display. `base` ⇒ `100`. */
    fun percent(scale: Float, base: Float): Int =
        if (base <= 0f) 100 else (scale / base * 100f).roundToInt()

    /**
     * Whether [scale] currently *is* real-world size. Drives both the "100 %" emphasis in
     * the readout and the one-shot haptic tick fired when the detent is entered.
     */
    fun isRealWorldSize(scale: Float, base: Float): Boolean =
        base > 0f && snap(scale, base) == base

    /**
     * Whether this pinch event should fire the detent haptic: only on the transition
     * *into* real-world size, never on every event while sitting inside the band (which
     * would buzz continuously) and never on the way out.
     */
    fun shouldTickHaptic(wasRealWorldSize: Boolean, isRealWorldSize: Boolean): Boolean =
        isRealWorldSize && !wasRealWorldSize
}

// ── Entrance ─────────────────────────────────────────────────────────────────────────────

/**
 * The "grows into place" animation applied to a freshly placed model.
 *
 * A model that appears at full size on the frame its textures land reads as a glitch —
 * there is no moment where the user sees it *arrive*, so the eye reports a pop. Scene
 * Viewer, IKEA Place and Reality Composer all ease the object up from a smaller scale over
 * roughly a quarter of a second, which is short enough to feel instant and long enough for
 * the arrival to register.
 */
object PlacementEntrance {

    /** Duration of the scale-in, milliseconds. */
    const val DURATION_MS = 260

    /** Scale fraction the model starts at — deliberately not 0, which reads as a flicker. */
    const val START_FRACTION = 0.55f

    /**
     * Eased scale fraction at animation [progress] (`0..1`), to multiply the model's base
     * scale by. Cubic ease-out: fast out of the gate, settling without overshoot — an
     * overshoot on a *physical-scale* object reads as the object being the wrong size, not
     * as bounce.
     */
    fun scaleFraction(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return START_FRACTION + (1f - START_FRACTION) * eased
    }
}

// ── Coaching ─────────────────────────────────────────────────────────────────────────────

/**
 * The one sentence the placement screen is allowed to show, as a value rather than a
 * string — so the decision of *which* is unit-testable and the wording stays in
 * `strings.xml` where it can be translated.
 */
enum class PlacementCoachingMessage {
    /**
     * ARCore never started: the flow is still in
     * [TapToPlaceUxState.INITIALIZING] long after [ARCameraInitScrim]
     * [gave up][AR_CAMERA_INIT_SCRIM_TIMEOUT_MS] and dismissed itself.
     *
     * Without this the screen is a dead end — a black viewport with no words on it,
     * because the scrim that was explaining the wait has removed itself and every other
     * coaching state is gated on a session that will never arrive. Saying so is not an
     * error surface; it is the coaching line telling the truth about the phase it is
     * already responsible for.
     */
    AR_UNAVAILABLE,

    /** Camera is up, a surface is tracked, but the reticle is not on one. */
    POINT_AT_SURFACE,

    /** The reticle is locked on a surface and nothing has been placed yet. */
    TAP_TO_PLACE,

    /** Just placed — the one-shot "drag / twist / pinch" hint. */
    GESTURE_HINT,
}

/**
 * How long the flow may sit in [TapToPlaceUxState.INITIALIZING] before the coaching line
 * concedes that AR is not going to start, milliseconds.
 *
 * Derived from the init scrim's own timeout rather than restated, so the two surfaces can
 * never both be talking (or both be silent) after someone tunes one of them: the scrim owns
 * the wait, this owns the second after it gives up.
 */
const val PLACEMENT_STARTUP_STALL_MS = AR_CAMERA_INIT_SCRIM_TIMEOUT_MS + 1_000L

/**
 * How long the post-placement gesture hint stays on screen, milliseconds. Long enough to
 * read three verbs, short enough that it is gone before the user's second placement.
 */
const val PLACEMENT_GESTURE_HINT_MS = 3_500L

/**
 * Picks the single coaching message for the current moment, or `null` for "say nothing".
 *
 * The screen used to show three overlapping things at once — the `PlaneDiscoveryGuide`
 * pill, a top status pill and an "Aim at a surface…" hint — two of which said the same
 * thing in different words at the same time. Consumer AR apps show exactly one line, and
 * remove it as soon as the user has demonstrated they no longer need it.
 *
 * The scanning and tracking-lost phases are deliberately **not** handled here: they belong
 * to `PlaneDiscoveryGuide` (the ARCore-Elements onboarding, with its animated hand hint),
 * and duplicating them in a second pill is the collision this function exists to end.
 *
 * The initialising phase belongs to `ARCameraInitScrim` on the same terms — with one
 * exception, which is [PlacementCoachingMessage.AR_UNAVAILABLE]. That scrim dismisses
 * itself after [AR_CAMERA_INIT_SCRIM_TIMEOUT_MS] whether or not a frame ever arrived, so on
 * a device where ARCore cannot start (session creation fails, ARCore missing or too old)
 * the screen would otherwise be left as a black viewport with nothing on it and no phase
 * willing to claim it. Delegation only works while the delegate is still on screen.
 *
 * @param uxState the camera/plane/reticle state machine value.
 * @param placedCount how many models are in the scene.
 * @param gestureHintVisible whether the post-placement hint window is still open.
 * @param startupStalled whether the flow has been stuck in
 *   [TapToPlaceUxState.INITIALIZING] for [PLACEMENT_STARTUP_STALL_MS]. Only consulted in
 *   that state, so a stale `true` can never surface once a session has started — the state
 *   machine leaves `INITIALIZING` on the first camera frame and never returns.
 */
fun placementCoaching(
    uxState: TapToPlaceUxState,
    placedCount: Int,
    gestureHintVisible: Boolean,
    startupStalled: Boolean = false,
): PlacementCoachingMessage? = when {
    // Nothing has started yet. The init scrim explains the wait; we only speak if it has
    // given up and the wait turned out to be permanent.
    uxState == TapToPlaceUxState.INITIALIZING ->
        if (startupStalled) PlacementCoachingMessage.AR_UNAVAILABLE else null

    // PlaneDiscoveryGuide owns the rest of the pre-surface phases.
    uxState == TapToPlaceUxState.TRACKING_LOST ||
        uxState == TapToPlaceUxState.SCANNING -> null

    gestureHintVisible -> PlacementCoachingMessage.GESTURE_HINT

    // Once the user has placed something they have proven they know how; stop coaching.
    placedCount > 0 -> null

    uxState == TapToPlaceUxState.AIMING -> PlacementCoachingMessage.POINT_AT_SURFACE

    else -> PlacementCoachingMessage.TAP_TO_PLACE
}
