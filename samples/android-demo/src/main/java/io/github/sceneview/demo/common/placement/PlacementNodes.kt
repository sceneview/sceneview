package io.github.sceneview.demo.common.placement

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.SceneScope
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.node.Node as NodeImpl

/**
 * One committed placement, with the full Scene-Viewer-parity interaction model attached
 * ([#3326](https://github.com/sceneview/sceneview/issues/3326)).
 *
 * This is the composable that turns "an anchor exists" into "an object you can believe is
 * in the room". Four things it does that the previous inline block did not:
 *
 * 1. **One finger drags the anchor, not the model.** The model node is explicitly
 *    `isPositionEditable = false`, which is what routes the move gesture up to the
 *    [AnchorNode][io.github.sceneview.ar.node.AnchorNode] — the only node in the trio that
 *    knows how to move in AR (it detaches its anchor on move-begin, follows a per-frame
 *    ARCore hit test, and re-anchors on move-end). Left editable, the model swallowed the
 *    gesture and then failed to move at all: `NodeGestureDelegate.onMove` resolves the drag
 *    against *the parent's collider*, and an `AnchorNode` is a pose with no geometry, so
 *    the hit test returned nothing and every drag was a silent no-op. Dragging a placed
 *    model in AR has therefore never worked; this is the line that fixes it.
 * 1-bis. **The two-finger twist turns the object on the floor, never off it** (#3735). The
 *    node the twist edits is a bare [pivot][io.github.sceneview.SceneScope.Node] that holds
 *    a pure yaw, and the model — with whatever rotation the asset needs to stand up — is a
 *    **non-editable child** of it. See [PlacementRotation] for why the order matters, and
 *    the note on the pivot below for how a gesture that lands on the child reaches it.
 * 2. **The drag stays on the surface and keeps the object's facing.** The node's `onMove`
 *    hook writes a translation-only pose instead of letting the raw hit pose through: an
 *    ARCore plane hit's rotation is defined relative to *the cast ray*, so applying it
 *    verbatim spins the object as the finger sweeps — the classic Sceneform artefact.
 * 3. **It grows into place** rather than appearing at full size the frame its textures
 *    land — see [PlacementEntrance].
 * 4. **Pinch is expressed in percent of real-world size**, with a detent at 100 % — see
 *    [PlacementScale] — and applied to the **grounded pivot**, so the object grows up from
 *    the floor. The model child is bottom-aligned to that pivot (`centerOrigin`), which is
 *    what makes the twist and the pinch both act about the base (§2.3).
 *
 * @param placed the committed placement (anchor + spec).
 * @param modelLoader loader for the model bytes.
 * @param onScaleChanged reports every pinch step as `(percent, isRealWorldSize,
 *   crossedIntoRealWorldSize)` so the host can drive the read-out and the detent haptic.
 * @param onDragOffSurface `true` while a one-finger drag finds no usable surface under the
 *   finger (the pose is kept; the overlay says so), `false` again when it does or the
 *   finger lifts.
 */
@Composable
internal fun ARSceneScope.PlacedModelNode(
    placed: PlacedModel,
    modelLoader: ModelLoader,
    onScaleChanged: (percent: Int, isRealWorldSize: Boolean, crossedIntoRealWorldSize: Boolean) -> Unit,
    onDragOffSurface: (offSurface: Boolean) -> Unit = {},
) {
    // Read live inside the gesture lambdas below, which are captured once at node
    // creation — the #2476 discipline applied to a non-tap gesture.
    val currentOnScaleChanged by rememberUpdatedState(onScaleChanged)
    val currentOnDragOffSurface by rememberUpdatedState(onDragOffSurface)

    // Plain holder, not snapshot state: `apply` runs inside the node's `remember`
    // initialiser, and writing a MutableState there would schedule a recomposition from
    // inside composition. The handle is populated before any effect can read it.
    val handle = remember { PlacedModelHandle() }

    // visibleTrackingStates includes PAUSED so a placed model survives transient plane
    // loss — it holds its last known pose instead of vanishing when ARCore briefly stops
    // tracking the anchor (#1435).
    AnchorNode(
        anchor = placed.anchor,
        visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES,
        apply = {
            // The anchor drag below only works while the node is editable: every
            // `is*Editable` flag — `isPositionEditable` included — is gated by
            // `isEditable`, which defaults to false.
            isEditable = true
            // A drag may only land where the automatic placement would have (§2.4): the
            // same usable-surface contract, so "where can this stand?" has one answer.
            // Off every usable surface the pose is simply kept (PoseNode applies nothing
            // for a null hit) and the overlay says "Keep the object on a surface."
            moveHitTest = { frame, event ->
                val hit = frame.hitTest(event).firstOrNull { result ->
                    val trackable = result.trackable
                    UsableSurfacePolicy.accept(
                        isUpwardHorizontalPlane = trackable is Plane &&
                            trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING,
                        isTrackableTracking = trackable.trackingState == TrackingState.TRACKING,
                        isPoseInPolygon = trackable is Plane &&
                            trackable.isPoseInPolygon(result.hitPose),
                        distanceMeters = result.distance,
                    )
                }
                currentOnDragOffSurface(hit == null)
                hit
            }
            // Translation only. Returning `false` tells PoseNode.onMove not to apply the
            // raw hit pose — we write the pose ourselves, keeping the rotation the object
            // already has so it slides across the floor instead of pivoting to face the
            // cast ray on every event (yaw preserved, §2.4).
            onMove = { _, _, worldPosition ->
                pose = Pose(
                    floatArrayOf(worldPosition.x, worldPosition.y, worldPosition.z),
                    pose.rotationQuaternion,
                )
                false
            }
            onMoveEnd = { _, _ -> currentOnDragOffSurface(false) }
        },
    ) {
        // `fileLocation =` forces the URL-capable overload (handles both the `file://`
        // streamed URI and the bundled asset path). See the #2302 overload trap.
        val instance = rememberModelInstance(modelLoader, fileLocation = placed.spec.assetLocation)
        // Gate visibility until Filament finishes uploading the model's textures, so it
        // doesn't flash black on placement (#1435).
        val textured = rememberTexturesSettled(ready = instance != null)

        instance?.let {
            PivotedModelNode(
                modelInstance = it,
                // Per-asset placement correction (#1477). `rotationOverride` wins when
                // supplied; otherwise fall back to the shared helmet −90° X correction.
                assetRotation = placed.spec.rotationOverride
                    ?: DemoMath.placementRotationFor(placed.spec.assetLocation),
                // Real-world size, not a uniform 0.3 m "demo size" (#3326).
                scaleToUnits = placed.spec.realWorldSizeMeters,
                groundBase = true,
                isVisible = textured,
                applyPivot = {
                    handle.node = this
                    // The pivot's rest scale IS 100 % — the child carries `scaleToUnits`.
                    val base = 1f
                    handle.baseScale = base
                    editableScaleRange = PlacementScale.rangeFor(base)
                    onScale = { _, _, factor ->
                        val was = PlacementScale.isRealWorldSize(scale.x, base)
                        val next = PlacementScale.next(
                            current = scale.x,
                            base = base,
                            rawFactor = factor,
                            sensitivity = scaleGestureSensitivity,
                        )
                        scale = Scale(next)
                        val now = PlacementScale.isRealWorldSize(next, base)
                        currentOnScaleChanged(
                            PlacementScale.percent(next, base),
                            now,
                            PlacementScale.shouldTickHaptic(was, now),
                        )
                        // We applied the scale ourselves, with the clamp and the detent the
                        // stock path has no way to express.
                        false
                    }
                },
            )
        }

        // Scale-in on arrival — the object reveal (`motion-fade` timing). Keyed on the
        // moment the model becomes visible, and latched, so a recomposition (a picker
        // change, a swap) never replays it on a model already standing in the room.
        LaunchedEffect(handle, textured) {
            val node = handle.node ?: return@LaunchedEffect
            if (!textured || handle.entrancePlayed) return@LaunchedEffect
            handle.entrancePlayed = true
            val base = handle.baseScale
            if (base <= 0f) return@LaunchedEffect
            node.scale = Scale(base * PlacementEntrance.scaleFraction(0f))
            Animatable(0f).animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PlacementEntrance.DURATION_MS,
                    easing = LinearEasing,
                ),
            ) {
                node.scale = Scale(base * PlacementEntrance.scaleFraction(value))
            }
            node.scale = Scale(base)
        }
    }
}

/**
 * The two-node hierarchy every **editable** placed model in this app is built from
 * ([#3735](https://github.com/sceneview/sceneview/issues/3735)): a geometry-free yaw pivot,
 * with the model as its non-rotatable content child.
 *
 * ## Why two nodes
 *
 * `NodeGestureDelegate.onRotate` applies a two-finger twist with `node.quaternion *= delta`
 * — a **right**-multiplication, so the delta is expressed in the node's **own** frame. That
 * is a yaw for exactly as long as the node's local Y still points along the anchor's up
 * axis, and an asset's standing-up correction is precisely what stops that being true: the
 * Khronos helmet is authored Z-up, so `Rotation(x = -90f)` maps its local Y onto
 * `(0, 0, -1)` and every twist came out as a pitch. The model tumbled instead of pivoting.
 *
 * Splitting the two jobs fixes it whatever the asset: the pivot only ever accumulates yaw
 * and stays upright, the correction rides a child that no twist is applied to, and the two
 * compose in the order that leaves the twist a yaw in the anchor's frame.
 *
 * No finger ever lands on the pivot — it has no collider. The touch lands on the model,
 * which stays `isEditable` so `SceneView`'s dispatcher counts it as an edit rather than
 * leaking the gesture to the camera manipulator, and which declines the axes it must not
 * own. A declined axis does not swallow the gesture: `NodeGestureDelegate` forwards it to
 * `node.parent`. So the twist stops at the pivot, a drag carries on past it to the
 * `AnchorNode` — the only node that can move in AR — and a pinch is claimed by the model
 * before it ever reaches the pivot.
 *
 * ## Why it is a composable and not a snippet
 *
 * [#3735](https://github.com/sceneview/sceneview/issues/3735) is as much about the defect
 * being fixed in one place and left standing in another as about the defect itself. Every
 * editable node in this app that carries a `DemoMath.placementRotationFor` correction goes
 * through here — `PlacedModelNode` and `PointAndAskDemo` today — so there is one place to
 * get it right. The nodes' flags and rest rotations are not written here either: they are
 * read from [PlacementHierarchy], which is pure and unit-tested, so moving the correction
 * back onto the node the twist turns means editing an object that has tests pointed at it.
 *
 * @param assetRotation the model's own standing-up correction — its business, never the
 *   user's yaw. Pass `Rotation()` for an asset authored Y-up.
 * @param applyContent imperative configuration for the **model** node, applied after its
 *   role's flags so a caller can add a custom `onScale` or capture the node, but late
 *   enough that overriding `isRotationEditable` here would be visible as exactly that.
 */
@Composable
internal fun SceneScope.PivotedModelNode(
    modelInstance: ModelInstance,
    assetRotation: Rotation,
    scaleToUnits: Float? = null,
    /**
     * Bottom-align the model's bounds to the pivot, so the pivot — which the twist and the
     * pinch both act about — is the object's grounded base rather than the asset's authored
     * origin (§2.3). Off by default for callers whose assets already sit on `y = 0`.
     */
    groundBase: Boolean = false,
    isVisible: Boolean = true,
    applyPivot: NodeImpl.() -> Unit = {},
    applyContent: ModelNodeImpl.() -> Unit = {},
) {
    val pivotRole = PlacementHierarchy.pivot()
    val contentRole = PlacementHierarchy.content(assetRotation)

    Node(
        rotation = pivotRole.restRotation,
        isEditable = pivotRole.isEditable,
        apply = {
            // The per-axis defaults are asymmetric on `Node` — position starts `false`,
            // rotation and scale start `true` — so every axis is spelled out from the role
            // rather than half-inherited.
            isPositionEditable = pivotRole.isPositionEditable
            isRotationEditable = pivotRole.isRotationEditable
            isScaleEditable = pivotRole.isScaleEditable
            applyPivot()
        },
    ) {
        ModelNode(
            modelInstance = modelInstance,
            scaleToUnits = scaleToUnits,
            centerOrigin = if (groundBase) Position(x = 0f, y = -1f, z = 0f) else null,
            rotation = contentRole.restRotation,
            isVisible = isVisible,
            isEditable = contentRole.isEditable,
            apply = {
                isPositionEditable = contentRole.isPositionEditable
                isRotationEditable = contentRole.isRotationEditable
                isScaleEditable = contentRole.isScaleEditable
                applyContent()
            },
        )
    }
}

/**
 * Mutable handle onto the placed model's runtime node.
 *
 * Deliberately a plain class and not snapshot state — see the `remember` call site.
 */
internal class PlacedModelHandle {
    /** The grounded pivot — the node the pinch and the entrance animation scale. */
    var node: NodeImpl? = null

    /** The pivot scale that renders the model at real-world size, i.e. 100 % (`1f`). */
    var baseScale: Float = 0f

    /** Latch so the arrival animation plays once per placement, not once per recomposition. */
    var entrancePlayed: Boolean = false
}
