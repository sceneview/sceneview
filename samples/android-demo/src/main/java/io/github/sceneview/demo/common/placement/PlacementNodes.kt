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
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.node.ModelNode as ModelNodeImpl

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
 *    [PlacementScale]. The stock `editableScaleRange` band cannot do this; it is a fixed
 *    `0.1f..10f` window on the raw node scale, which for any model whose fitted scale
 *    falls below `0.1` rejects the very first pinch event.
 *
 * @param placed the committed placement (anchor + spec).
 * @param modelLoader loader for the model bytes.
 * @param snapToPlane whether a drag may only land on detected planes (#1883 parity with
 *   the tap policy). Read live, so the demo's dev toggle applies mid-session.
 * @param onScaleChanged reports every pinch step as `(percent, isRealWorldSize,
 *   crossedIntoRealWorldSize)` so the host can drive the read-out and the detent haptic.
 */
@Composable
internal fun ARSceneScope.PlacedModelNode(
    placed: PlacedModel,
    modelLoader: ModelLoader,
    snapToPlane: Boolean,
    onScaleChanged: (percent: Int, isRealWorldSize: Boolean, crossedIntoRealWorldSize: Boolean) -> Unit,
) {
    // Read live inside the gesture lambdas below, which are captured once at node
    // creation — the #2476 discipline applied to a non-tap gesture.
    val currentSnapToPlane by rememberUpdatedState(snapToPlane)
    val currentOnScaleChanged by rememberUpdatedState(onScaleChanged)

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
            // Constrain the drag to the same surfaces a tap would accept, so "where can I
            // put this?" has one answer whether the user taps or drags (#1883 / #3326).
            moveHitTest = { frame, event ->
                frame.hitTest(event).firstOrNull { result ->
                    val trackable = result.trackable
                    PlacementHitPolicy.accept(
                        isPlane = trackable is Plane,
                        isPoseInPolygon = trackable is Plane &&
                            trackable.isPoseInPolygon(result.hitPose),
                        isTrackableTracking = trackable.trackingState == TrackingState.TRACKING,
                        distanceMeters = result.distance,
                        snapToPlane = currentSnapToPlane,
                    )
                }
            }
            // Translation only. Returning `false` tells PoseNode.onMove not to apply the
            // raw hit pose — we write the pose ourselves, keeping the rotation the object
            // already has so it slides across the floor instead of pivoting to face the
            // cast ray on every event.
            onMove = { _, _, worldPosition ->
                pose = Pose(
                    floatArrayOf(worldPosition.x, worldPosition.y, worldPosition.z),
                    pose.rotationQuaternion,
                )
                false
            }
        },
    ) {
        // `fileLocation =` forces the URL-capable overload (handles both the `file://`
        // streamed URI and the bundled asset path). See the #2302 overload trap.
        val instance = rememberModelInstance(modelLoader, fileLocation = placed.spec.assetLocation)
        // Gate visibility until Filament finishes uploading the model's textures, so it
        // doesn't flash black on placement (#1435).
        val textured = rememberTexturesSettled(ready = instance != null)

        // The yaw pivot (#3735). A bare node with no geometry, interposed between the anchor
        // and the model for exactly one reason: it is the node the twist gesture edits, and
        // its local rotation is only ever a pure yaw, so its local Y axis stays the anchor's
        // up axis. `NodeGestureDelegate.onRotate` applies its delta with `quaternion *=` —
        // a right-multiplication, i.e. the delta expressed in the node's OWN frame — so a
        // node whose Y is upright yaws, while a node whose Y an asset correction has laid
        // flat tumbles instead. That is the whole of #3735: the correction used to sit on
        // the very node the twist edited, so the helmet's −90° X turned every twist into a
        // pitch. See [PlacementRotation] for the algebra and its unit tests.
        //
        // No finger ever lands on this node — it has no collider. The touch lands on the
        // model child, which stays `isEditable = true` so `SceneView`'s touch dispatcher
        // still counts it as an edit rather than leaking the gesture to the camera
        // manipulator, but locks the two axes it must not own. `NodeGestureDelegate`
        // forwards a gesture to `node.parent` whenever the matching `is*Editable` flag is
        // off, so rotation stops here and movement carries on up to the anchor.
        Node(
            isEditable = true,
            apply = {
                // The per-axis defaults are asymmetric on `Node` — position starts `false`,
                // rotation and scale start `true` — so spell out what this node owns rather
                // than inherit a mix. Rotation, and only rotation.
                isPositionEditable = false
                // Scale belongs to the model child, which expresses it as a percentage of
                // real-world size; locking it here means a pinch can never be claimed by the
                // pivot on the way up.
                isScaleEditable = false
            },
        ) {
            instance?.let {
                ModelNode(
                    modelInstance = it,
                    // Real-world size, not a uniform 0.3 m "demo size" (#3326).
                    scaleToUnits = placed.spec.realWorldSizeMeters,
                    // Per-asset placement correction (#1477). `rotationOverride` wins when
                    // supplied; otherwise fall back to the shared helmet −90° X correction.
                    // This is the rotation that must never sit on an edited node (#3735):
                    // it is the model's own business of standing up, not the user's yaw.
                    rotation = placed.spec.rotationOverride
                        ?: DemoMath.placementRotationFor(placed.spec.assetLocation),
                    isVisible = textured,
                    isEditable = true,
                    apply = {
                        handle.node = this
                        // The move gesture belongs to the anchor, the twist to the pivot
                        // just above — see this composable's KDoc. Scale stays here, on the
                        // object itself, because it is expressed against this node's fitted
                        // real-world scale.
                        isPositionEditable = false
                        isRotationEditable = false

                        // `scaleToUnits` has already run in the constructor, so this IS the
                        // 100 % scale.
                        val base = scale.x
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
                            // We applied the scale ourselves, with the clamp and the detent
                            // the stock path has no way to express.
                            false
                        }
                    },
                )
            }
        }

        // Scale-in on arrival. Keyed on the moment the model becomes visible, and latched,
        // so a recomposition (a picker change, a second placement) never replays it on a
        // model already standing in the room.
        LaunchedEffect(handle, textured) {
            val node = handle.node ?: return@LaunchedEffect
            if (!textured || handle.entrancePlayed) return@LaunchedEffect
            handle.entrancePlayed = true
            val base = handle.baseScale
            if (base <= 0f) return@LaunchedEffect
            node.scale = Scale(base * PlacementEntrance.scaleFraction(0f))
            // Linear driver, cubic ease inside `scaleFraction` — the easing is the pure,
            // unit-tested function, not an animation-spec detail nothing can assert on.
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
 * Mutable handle onto the placed model's runtime node.
 *
 * Deliberately a plain class and not snapshot state — see the `remember` call site.
 */
internal class PlacedModelHandle {
    var node: ModelNodeImpl? = null

    /** The node scale that renders the model at real-world size, i.e. 100 %. */
    var baseScale: Float = 0f

    /** Latch so the arrival animation plays once per placement, not once per recomposition. */
    var entrancePlayed: Boolean = false
}
