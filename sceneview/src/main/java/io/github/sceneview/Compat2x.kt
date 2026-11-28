// SceneView 2.x source compatibility (#5216).
//
// AI coding agents still write SceneView 2.x code from memory: a node list built with
// `rememberNodes { add(ModelNode(...)) }` and handed to `Scene(childNodes = ...)`. 4.x declares
// nodes inside the `SceneView { }` content block instead. The shims below keep that 2.x code
// compiling and working, and their deprecation warnings give the 4.x spelling to whoever reads
// the build log — usually the agent that wrote the code.
//
// Additions only: nothing here changes a 4.x signature. The code they must keep compiling lives
// in `src/test/java/io/github/sceneview/agentcompat/`.
package io.github.sceneview

import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.annotation.RestrictTo
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode

/**
 * SceneView 2.x helper: a remembered, observable list of nodes for the 2.x `childNodes`
 * parameter of `Scene` / `ARScene`. Every node still in the list is destroyed when the
 * composition leaves, as in 2.x.
 *
 * In 4.x there is no node list: declare the nodes in the content block of [SceneView] (or
 * `ARSceneView`), where they are created, attached and destroyed with the composition:
 * ```kotlin
 * SceneView(engine = engine, modelLoader = modelLoader) {
 *     rememberModelInstance(modelLoader, "models/product.glb")?.let { instance ->
 *         ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
 *     }
 * }
 * ```
 *
 * @param creator Fills the initial list, e.g. `add(ModelNode(...))`.
 * @return A [SnapshotStateList]: adding or removing a node updates the scene.
 */
@Deprecated(
    "SceneView 2.x API. In 4.x there is no node list: declare each node in the content block, " +
        "SceneView(engine = engine, modelLoader = modelLoader) { " +
        "rememberModelInstance(modelLoader, \"models/model.glb\")?.let { ModelNode(modelInstance = it) } }. " +
        "For AR placement: ARSceneView { AnchorNode(anchor) { ModelNode(...) } }.",
    level = DeprecationLevel.WARNING,
)
@Composable
fun rememberNodes(creator: MutableList<Node>.() -> Unit = {}): SnapshotStateList<Node> {
    val nodes = remember { buildList(creator).toMutableStateList() }
    DisposableEffect(nodes) {
        onDispose {
            nodes.toList().forEach { it.destroy() }
            nodes.clear()
        }
    }
    return nodes
}

/**
 * Attaches the externally owned [nodes] of the SceneView 2.x `childNodes` parameter to this
 * scope's scene, and detaches them when they leave the list. It never destroys them: their
 * owner ([rememberNodes], [rememberNode] or the caller) does.
 *
 * Reading [nodes] during composition subscribes to a [SnapshotStateList], so `nodes += node`
 * and `nodes -= node` from a gesture callback update the scene, as they did in 2.x. A node
 * listed twice is attached once.
 *
 * With `autoCenterContent = true` (the default), `SceneView` parents each attached node to its
 * internal content root. A node removed while the scene is shown gets that parent cleared, but
 * when the whole scene leaves, a caller-owned node keeps the departed scene's content root as
 * its parent: the scope cannot tell that root from a parent the caller set. It does no harm in
 * the 2.x pattern. Listing the node in a new scene re-parents it to that scene's root, and
 * destroying it clears the parent.
 *
 * Public for the `ARScene` shim of `arsceneview` only; not for application code.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
@Composable
fun SceneScope.Compat2xChildNodes(nodes: List<Node>) {
    nodes.distinct().forEach { node ->
        key(node) {
            DisposableEffect(node) {
                attach(node)
                onDispose { detach(node) }
            }
        }
    }
}

/**
 * SceneView 2.x signature of `Scene`, with its `childNodes` node list. The nodes are attached to
 * the scene and follow the list; everything else is [SceneView].
 *
 * There is no `ReplaceWith`: the 4.x form moves each node of the list into the content block,
 * which no mechanical rewrite can do without dropping the nodes. The warning message spells it.
 */
@Deprecated(
    "SceneView 2.x API: Scene(childNodes = ...) is SceneView { ... } in 4.x. Drop childNodes and " +
        "rememberNodes, and declare each node in the content block: " +
        "SceneView(modifier = modifier, engine = engine, modelLoader = modelLoader, " +
        "cameraManipulator = rememberCameraManipulator()) { " +
        "rememberModelInstance(modelLoader, \"models/model.glb\")?.let { " +
        "ModelNode(modelInstance = it, scaleToUnits = 1.0f) } }.",
    level = DeprecationLevel.WARNING,
)
@Suppress("LongParameterList")
@Composable
fun Scene(
    modifier: Modifier = Modifier,
    surfaceType: SurfaceType = SurfaceType.Surface,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    view: View = rememberView(engine),
    isOpaque: Boolean = true,
    frameRatePolicy: FrameRatePolicy = FrameRatePolicy.OnDemand(),
    renderQuality: RenderQuality = RenderQuality.Default,
    autoCenterContent: Boolean = true,
    autoFitContent: Boolean = false,
    framingPadding: Float = DEFAULT_FRAMING_PADDING,
    fitMode: FitMode? = null,
    animationEnvelope: AnimationEnvelope? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    clipToContentPadding: Boolean = false,
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    fillLightNode: LightNode? = rememberFillLightNode(engine),
    cameraNode: CameraNode = rememberCameraNode(engine),
    // No default: a call without `childNodes` resolves to the 4.x-shaped `Scene` alias.
    childNodes: List<Node>,
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    cameraManipulator: CameraGestureDetector.CameraManipulator? = rememberDefaultCameraManipulator(
        initialEyePosition = cameraNode.worldPosition
    ),
    viewNodeWindowManager: ViewNode.WindowManager? = null,
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    activity: ComponentActivity? = LocalContext.current as? ComponentActivity,
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
    content: (@Composable SceneScope.() -> Unit)? = null
) = SceneView(
    modifier = modifier,
    surfaceType = surfaceType,
    engine = engine,
    modelLoader = modelLoader,
    materialLoader = materialLoader,
    environmentLoader = environmentLoader,
    view = view,
    isOpaque = isOpaque,
    frameRatePolicy = frameRatePolicy,
    renderQuality = renderQuality,
    autoCenterContent = autoCenterContent,
    autoFitContent = autoFitContent,
    framingPadding = framingPadding,
    fitMode = fitMode,
    animationEnvelope = animationEnvelope,
    contentPadding = contentPadding,
    clipToContentPadding = clipToContentPadding,
    renderer = renderer,
    scene = scene,
    environment = environment,
    mainLightNode = mainLightNode,
    fillLightNode = fillLightNode,
    cameraNode = cameraNode,
    collisionSystem = collisionSystem,
    cameraManipulator = cameraManipulator,
    viewNodeWindowManager = viewNodeWindowManager,
    onGestureListener = onGestureListener,
    onTouchEvent = onTouchEvent,
    activity = activity,
    lifecycle = lifecycle,
    onFrame = onFrame,
) {
    Compat2xChildNodes(childNodes)
    content?.invoke(this)
}
