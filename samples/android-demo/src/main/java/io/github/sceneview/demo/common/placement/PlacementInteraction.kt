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
 * The headless core of the placement *interaction* model
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
 * the anchor's up axis whatever the asset needed to stand up. See `PivotedModelNode`, the one
 * composable every placement screen builds that hierarchy through.
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
 * [PlacementInteractionTest] asserts against.
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
     * that can move in AR. Scale is **accepted here**, not on the content: the pivot sits at
     * the anchor, i.e. at the object's grounded base, so a pinch grows the object up from
     * the floor instead of about the asset's own origin (which for a centre-origined asset
     * sinks it into the surface — plan §2.3/§2.4, "grounded pivot").
     */
    fun pivot(): PlacementNodeRole = PlacementNodeRole(
        isEditable = true,
        isPositionEditable = false,
        isRotationEditable = true,
        isScaleEditable = true,
        restRotation = Rotation(),
    )

    /**
     * The content node: the one that renders, and the only one with a collider — so it is
     * the node the finger actually touches, and it must stay `isEditable` for the touch to
     * be treated as an edit rather than leaking to the camera manipulator.
     *
     * It carries [assetCorrection] and refuses rotation, which is the whole fix: the
     * correction can lay this node's local Y flat as much as the asset needs, because no
     * twist is ever applied here. It refuses scale too, so a pinch bubbles to the grounded
     * pivot above it.
     */
    fun content(assetCorrection: Rotation): PlacementNodeRole = PlacementNodeRole(
        isEditable = true,
        isPositionEditable = false,
        isRotationEditable = false,
        isScaleEditable = false,
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
 * The one sentence the placement screen may show in its pill, as a value rather than a
 * string — so *which* one is unit-testable and the wording stays in `strings.xml`.
 *
 * Exact copy per plan §2.2; nothing here asks the user to tap anything.
 */
enum class PlacementCoachingMessage {
    /** Scanning: "Move slowly to find a surface." */
    MOVE_SLOWLY,

    /** Tracking lost: "Tracking paused. Move slowly." */
    TRACKING_PAUSED,

    /** Tracking lost in the dark: "Try a brighter area." */
    TRACKING_PAUSED_LOW_LIGHT,

    /** Camera back, anchor not yet: "Finding your placement…" */
    FINDING_PLACEMENT,

    /** Just placed: "Drag to move. Pinch or twist to adjust." — once. */
    GESTURE_HINT,

    /** A drag left every usable surface: "Keep the object on a surface." */
    KEEP_ON_SURFACE,
}

/** The help cards — the three phases that need a button, not a sentence (plan §2.2). */
enum class PlacementCard {
    /** "No surface found." — *View in 3D* / *Keep scanning*. */
    NO_SURFACE,

    /** "Couldn't recover this placement." — *Scan again*. */
    RECOVERY_FAILED,

    /** "Camera couldn't start." — *Try again*. */
    CAMERA_ERROR,
}

/**
 * How long the flow may sit in [PlacementPhase.INITIALIZING] before it concedes that AR is
 * not going to start, milliseconds. Derived from the init scrim's own timeout, so the scrim
 * owns the wait and this owns the second after it gives up.
 */
const val PLACEMENT_STARTUP_STALL_MS = AR_CAMERA_INIT_SCRIM_TIMEOUT_MS + 1_000L

/** How long the post-placement gesture hint stays on screen, milliseconds. */
const val PLACEMENT_GESTURE_HINT_MS = 3_500L

/**
 * Picks the single coaching sentence for the current moment, or `null` for "say nothing".
 *
 * A card phase ([placementCard]) never also speaks in the pill; the placed phase speaks
 * only for the one-shot hint or while a drag is off-surface. Scanning speaks the whole
 * time: it is the one instruction the flow needs, because moving the phone *is* the input.
 */
fun placementCoaching(
    phase: PlacementPhase,
    gestureHintVisible: Boolean,
    dragOffSurface: Boolean = false,
    lowLight: Boolean = false,
): PlacementCoachingMessage? = when (phase) {
    PlacementPhase.INITIALIZING,
    PlacementPhase.NO_SURFACE,
    PlacementPhase.RECOVERY_FAILED,
    PlacementPhase.CAMERA_ERROR -> null

    PlacementPhase.SCANNING -> PlacementCoachingMessage.MOVE_SLOWLY

    PlacementPhase.TRACKING_LOST ->
        if (lowLight) {
            PlacementCoachingMessage.TRACKING_PAUSED_LOW_LIGHT
        } else {
            PlacementCoachingMessage.TRACKING_PAUSED
        }

    PlacementPhase.RECOVERING -> PlacementCoachingMessage.FINDING_PLACEMENT

    PlacementPhase.PLACED -> when {
        dragOffSurface -> PlacementCoachingMessage.KEEP_ON_SURFACE
        gestureHintVisible -> PlacementCoachingMessage.GESTURE_HINT
        else -> null
    }
}

/** Which help card the phase shows, or `null` — the pill and the card never coexist. */
fun placementCard(phase: PlacementPhase): PlacementCard? = when (phase) {
    PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
    PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
    PlacementPhase.CAMERA_ERROR -> PlacementCard.CAMERA_ERROR
    else -> null
}
