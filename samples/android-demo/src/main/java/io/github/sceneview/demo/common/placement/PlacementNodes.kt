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
import io.github.sceneview.ar.AutoPlacementModel
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

/** Canonical app adapter for the SDK's grounded, surface-constrained model hierarchy. */
@Composable
internal fun ARSceneScope.PlacedModelNode(
    placed: PlacedModel,
    modelInstance: ModelInstance?,
    controller: io.github.sceneview.ar.AutoPlacementState,
    onScaleChanged: (Int, Boolean, Boolean) -> Unit,
    onDragOffSurface: (Boolean) -> Unit = {},
) {
    modelInstance?.let {
        AutoPlacementModel(
            placement = placed.placement,
            state = controller,
            modelInstance = it,
            scaleToUnits = placed.spec.realWorldSizeMeters,
            assetRotation = DemoMath.placementRotationFor(placed.spec.assetLocation),
            onInvalidMove = onDragOffSurface,
            onScaleChanged = onScaleChanged,
        )
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
 * axis, and an asset correction that tilts is precisely what stops that being true:
 * `Rotation(x = -90f)` maps the node's local Y onto `(0, 0, -1)` and every twist comes out
 * as a pitch. The model tumbles instead of pivoting. (The helmet carried exactly that
 * correction until #3735 found the correction itself was wrong — see
 * `DemoMath.placementRotationFor`, which now can only return a yaw.)
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
    /** The grounded pivot — the node the pinch scales. */
    var node: NodeImpl? = null

    /** The pivot scale that renders the model at real-world size, i.e. 100 % (`1f`). */
    var baseScale: Float = 0f
}
