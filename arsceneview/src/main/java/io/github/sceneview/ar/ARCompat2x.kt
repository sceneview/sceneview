// SceneView 2.x source compatibility for AR (#5216).
//
// AI coding agents still write SceneView 2.x AR code from memory: a node list from
// `rememberNodes()`, filled with `AnchorNode`s on tap and handed to `ARScene(childNodes = ...)`.
// 4.x declares nodes inside the `ARSceneView { }` content block instead. The shim below keeps
// that 2.x code compiling and working, and its deprecation warning gives the 4.x spelling to
// whoever reads the build log — usually the agent that wrote the code.
//
// Additions only: nothing here changes a 4.x signature. The code it must keep compiling lives in
// `src/test/java/io/github/sceneview/agentcompat/`.
package io.github.sceneview.ar

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.Compat2xChildNodes
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberFillLightNode
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import java.io.File

/**
 * SceneView 2.x signature of `ARScene`, with its `childNodes` node list. The nodes — typically
 * `AnchorNode`s created on tap — are attached to the AR scene, receive its frames and follow
 * the list; everything else is [ARSceneView].
 *
 * There is no `ReplaceWith`: the 4.x form moves each node of the list into the content block,
 * driven by state (a list of anchors), which no mechanical rewrite can do without dropping the
 * nodes. The warning message spells it.
 */
@Deprecated(
    "SceneView 2.x API: ARScene(childNodes = ...) is ARSceneView { ... } in 4.x. Drop childNodes and " +
        "rememberNodes: keep the placed anchors in state " +
        "(val anchors = remember { mutableStateListOf<Anchor>() }, anchors += hitResult.createAnchor() " +
        "in onGestureListener) and declare the nodes in the content block: " +
        "ARSceneView(...) { anchors.forEach { anchor -> AnchorNode(anchor) { " +
        "rememberModelInstance(modelLoader, \"models/model.glb\")?.let { ModelNode(modelInstance = it) } } } }.",
    level = DeprecationLevel.WARNING,
)
@Suppress("LongParameterList")
@Composable
fun ARScene(
    modifier: Modifier = Modifier,
    surfaceType: SurfaceType = SurfaceType.Surface,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    sessionFeatures: Set<Session.Feature> = setOf(),
    playbackDataset: File? = null,
    sessionCameraConfig: ((Session) -> CameraConfig)? = ::highestResolutionCameraConfig,
    flashMode: Config.FlashMode = Config.FlashMode.OFF,
    sessionConfiguration: ((session: Session, Config) -> Unit)? = null,
    planeRenderer: Boolean = true,
    cameraStream: ARCameraStream? = rememberARCameraStream(materialLoader),
    view: View = rememberARView(engine),
    isOpaque: Boolean = true,
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberAREnvironment(engine),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    fillLightNode: LightNode? = rememberFillLightNode(engine),
    cameraNode: ARCameraNode = rememberARCameraNode(engine),
    // No default: a call without `childNodes` resolves to the 4.x-shaped `ARScene` alias.
    childNodes: List<Node>,
    cameraExposure: Float? = null,
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    viewNodeWindowManager: ViewNode.WindowManager? = null,
    onSessionCreated: ((session: Session) -> Unit)? = null,
    onSessionResumed: ((session: Session) -> Unit)? = null,
    onSessionPaused: ((session: Session) -> Unit)? = null,
    onSessionFailed: ((exception: Exception) -> Unit)? = null,
    onPlaybackFailed: ((exception: Exception) -> Unit)? = null,
    onSessionUpdated: ((session: Session, frame: Frame) -> Unit)? = null,
    onTrackingFailureChanged: ((trackingFailureReason: TrackingFailureReason?) -> Unit)? = null,
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    permissionHandler: ARPermissionHandler? = rememberActivityARPermissionHandler(),
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    content: (@Composable ARSceneScope.() -> Unit)? = null
) = ARSceneView(
    modifier = modifier,
    surfaceType = surfaceType,
    engine = engine,
    modelLoader = modelLoader,
    materialLoader = materialLoader,
    environmentLoader = environmentLoader,
    sessionFeatures = sessionFeatures,
    playbackDataset = playbackDataset,
    sessionCameraConfig = sessionCameraConfig,
    flashMode = flashMode,
    sessionConfiguration = sessionConfiguration,
    planeRenderer = planeRenderer,
    cameraStream = cameraStream,
    view = view,
    isOpaque = isOpaque,
    renderer = renderer,
    scene = scene,
    environment = environment,
    mainLightNode = mainLightNode,
    fillLightNode = fillLightNode,
    cameraNode = cameraNode,
    cameraExposure = cameraExposure,
    collisionSystem = collisionSystem,
    viewNodeWindowManager = viewNodeWindowManager,
    onSessionCreated = onSessionCreated,
    onSessionResumed = onSessionResumed,
    onSessionPaused = onSessionPaused,
    onSessionFailed = onSessionFailed,
    onPlaybackFailed = onPlaybackFailed,
    onSessionUpdated = onSessionUpdated,
    onTrackingFailureChanged = onTrackingFailureChanged,
    onGestureListener = onGestureListener,
    onTouchEvent = onTouchEvent,
    permissionHandler = permissionHandler,
    lifecycle = lifecycle,
) {
    Compat2xARChildNodes(childNodes)
    content?.invoke(this)
}

/**
 * [Compat2xChildNodes] for an AR scene: the listed nodes also give their session objects back
 * while the session is still open.
 *
 * The 2.x node list outlives the scene: its owner ([io.github.sceneview.rememberNodes]) destroys
 * its `AnchorNode`s only after `ARSceneView` has left. By then the nodes have left the scene's
 * root list, so the session close released nothing, and `AnchorNode.destroy()` would call
 * `Anchor.detach()` on a closed session — the native crash of #4026. Leaving the composition
 * comes before the session close, so the nodes still listed are released here first; their later
 * destroy() then skips the native call.
 */
@Composable
internal fun ARSceneScope.Compat2xARChildNodes(nodes: List<Node>) {
    val current by rememberUpdatedState(nodes)
    DisposableEffect(Unit) {
        onDispose { releaseSessionNodes(current.toList()) }
    }
    Compat2xChildNodes(nodes)
}
