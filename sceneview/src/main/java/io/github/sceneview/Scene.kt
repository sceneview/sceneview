package io.github.sceneview

import android.content.Context
import android.opengl.EGLContext
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.utils.Manipulator
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode2
import io.github.sceneview.utils.destroy

@Composable
fun Scene(
    modifier: Modifier = Modifier,
    /**
     * Provide your own instance if you want to share Filament resources between multiple views.
     */
    engine: Engine = rememberEngine(),
    /**
     * Consumes a blob of glTF 2.0 content (either JSON or GLB) and produces a [Model] object, which is
     * a bundle of Filament textures, vertex buffers, index buffers, etc.
     *
     * A [Model] is composed of 1 or more [ModelInstance] objects which contain entities and components.
     */
    modelLoader: ModelLoader = rememberModelLoader(engine),
    /**
     * A Filament Material defines the visual appearance of an object.
     *
     * Materials function as a templates from which [MaterialInstance]s can be spawned.
     */
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    /**
     * Utility for decoding an HDR file or consuming KTX1 files and producing Filament textures,
     * IBLs, and sky boxes.
     *
     * KTX is a simple container format that makes it easy to bundle miplevels and cubemap faces
     * into a single file.
     */
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    /**
     * Encompasses all the state needed for rendering a {@link Scene}.
     *
     * [View] instances are heavy objects that internally cache a lot of data needed for
     * rendering. It is not advised for an application to use many View objects.
     *
     * For example, in a game, a [View] could be used for the main scene and another one for the
     * game's user interface. More <code>View</code> instances could be used for creating special
     * effects (e.g. a [View] is akin to a rendering pass).
     */
    view: View = rememberView(engine),
    /**
     * Controls whether the render target (SurfaceView) is opaque or not.
     * The render target is considered opaque by default.
     */
    isOpaque: Boolean = true,
    /**
     * A [Renderer] instance represents an operating system's window.
     *
     * Typically, applications create a [Renderer] per window. The [Renderer] generates drawing
     * commands for the render thread and manages frame latency.
     */
    renderer: Renderer = rememberRenderer(engine),
    /**
     * Provide your own instance if you want to share [Node]s' scene between multiple views.
     */
    scene: Scene = rememberScene(engine),
    /**
     * Defines the lighting environment and the skybox of the scene.
     *
     * Environments are usually captured as high-resolution HDR equirectangular images and processed
     * by the cmgen tool to generate the data needed by IndirectLight.
     *
     * You can also process an hdr at runtime but this is more consuming.
     *
     * - Currently IndirectLight is intended to be used for "distant probes", that is, to represent
     * global illumination from a distant (i.e. at infinity) environment, such as the sky or distant
     * mountains.
     * Only a single IndirectLight can be used in a Scene. This limitation will be lifted in the
     * future.
     *
     * - When added to a Scene, the Skybox fills all untouched pixels.
     *
     * @see [EnvironmentLoader]
     */
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    /**
     * Always add a direct light source since it is required for shadowing.
     *
     * We highly recommend adding an [IndirectLight] as well.
     */
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    /**
     * Represents a virtual camera, which determines the perspective through which the scene is
     * viewed.
     *
     * All other functionality in Node is supported. You can access the position and rotation of the
     * camera, assign a collision shape to it, or add children to it.
     */
    cameraNode: CameraNode = rememberCameraNode(engine),
    /**
     * List of the scene's nodes that can be linked to a `mutableStateOf<List<Node>>()`
     */
    childNodes: List<Node> = rememberNodes(),
    /**
     * Physics system to handle collision between nodes, hit testing on a nodes,...
     */
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    /**
     * Helper that enables camera interaction similar to sketchfab or Google Maps.
     *
     * Needs to be a callable function because it can be reinitialized in case of viewport change
     * or camera node manual position changed.
     *
     * The first onTouch event will make the first manipulator build. So you can change the camera
     * position before any user gesture.
     *
     * Clients notify the camera manipulator of various mouse or touch events, then periodically
     * call its getTransform() method so that they can adjust their camera(s).
     */
    cameraManipulator: CameraGestureDetector.CameraManipulator? = rememberCameraManipulator(
        cameraNode.worldPosition
    ),
    /**
     * Used for Node's that can display an Android [View]
     *
     * Manages a [FrameLayout] that is attached directly to a [ViewNode2.WindowManager] that other views can be
     * added and removed from.
     *
     * To render a [View], the [View] must be attached to a [ViewNode2.WindowManager] so that it can be properly
     * drawn. This class encapsulates a [FrameLayout] that is attached to a [ViewNode2.WindowManager] that other
     * views can be added to as children. This allows us to safely and correctly draw the [View]
     * associated with a [RenderableManager] [Entity] and a [MaterialInstance] while keeping them
     * isolated from the rest of the activities View hierarchy.
     *
     * Additionally, this manages the lifecycle of the window to help ensure that the window is
     * added/removed from the WindowManager at the appropriate times.
     */
    viewNodeWindowManager: ViewNode2.WindowManager? = null,
    /**
     * The listener invoked for all the gesture detector callbacks.
     *
     * Detects various gestures and events.
     * The gesture listener callback will notify users when a particular motion event has occurred.
     * Responds to Android touch events with listeners.
     */
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    activity: ComponentActivity? = LocalContext.current as? ComponentActivity,
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    /**
     * Invoked when an frame is processed.
     *
     * Registers a callback to be invoked when a valid Frame is processing.
     *
     * The callback to be invoked once per frame **immediately before the scene is updated.
     *
     * The callback will only be invoked if the Frame is considered as valid.
     */
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
    onViewCreated: (SceneView.() -> Unit)? = null,
    onViewUpdated: (SceneView.() -> Unit)? = null
) {
    if (LocalInspectionMode.current) {
        ScenePreview(modifier)
    } else {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                SceneView(
                    context,
                    null,
                    0,
                    0,
                    engine,
                    modelLoader,
                    materialLoader,
                    environmentLoader,
                    scene,
                    view,
                    renderer,
                    cameraNode,
                    mainLightNode,
                    environment,
                    isOpaque,
                    collisionSystem,
                    cameraManipulator,
                    viewNodeWindowManager,
                    onGestureListener,
                    onTouchEvent,
                    activity,
                    lifecycle,
                ).also {
                    onViewCreated?.invoke(it)
                }
            },
            update = { sceneView ->
                sceneView.scene = scene
                sceneView.environment = environment
                sceneView.mainLightNode = mainLightNode
                sceneView.setCameraNode(cameraNode)
                sceneView.childNodes = childNodes
                sceneView.cameraManipulator = cameraManipulator
                sceneView.viewNodeWindowManager = viewNodeWindowManager
                sceneView.onGestureListener = onGestureListener
                sceneView.onTouchEvent = onTouchEvent
                sceneView.onFrame = onFrame

                onViewUpdated?.invoke(sceneView)
            },
            onReset = {},
            onRelease = { sceneView ->
                sceneView.destroy()
            }
        )
    }
}

/** ## Deprecated: Use [CameraGestureDetector.DefaultCameraManipulator]
 *
 * Replace `manipulator = Manipulator.Builder().build()` with
 * `cameraManipulator = CameraGestureDetector.DefaultCameraManipulator(manipulator =
 * Manipulator.Builder().build())`
 */
@Composable
@Deprecated("Use CameraGestureDetector.DefaultCameraManipulator")
fun Scene(
    modifier: Modifier = Modifier,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    view: View = rememberView(engine),
    isOpaque: Boolean = true,
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    cameraNode: CameraNode = rememberCameraNode(engine),
    childNodes: List<Node> = rememberNodes(),
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    manipulator: Manipulator,
    viewNodeWindowManager: ViewNode2.WindowManager? = null,
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    activity: ComponentActivity? = LocalContext.current as? ComponentActivity,
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
    onViewCreated: (SceneView.() -> Unit)? = null,
    onViewUpdated: (SceneView.() -> Unit)? = null
) {
    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        environmentLoader = environmentLoader,
        view = view,
        isOpaque = isOpaque,
        renderer = renderer,
        scene = scene,
        environment = environment,
        mainLightNode = mainLightNode,
        cameraNode = cameraNode,
        childNodes = childNodes,
        collisionSystem = collisionSystem,
        cameraManipulator = CameraGestureDetector.createDefaultCameraManipulator(manipulator),
        viewNodeWindowManager = viewNodeWindowManager,
        onGestureListener = onGestureListener,
        onTouchEvent = onTouchEvent,
        activity = activity,
        lifecycle = lifecycle,
        onFrame = onFrame,
        onViewCreated = onViewCreated,
        onViewUpdated = onViewUpdated
    )
}

@Composable
fun rememberEngine(
    eglContextCreator: () -> EGLContext = { SceneView.createEglContext() },
    engineCreator: (eglContext: EGLContext) -> Engine = { SceneView.createEngine(it) }
): Engine {
    val eglContext = remember(eglContextCreator)
    // Declared up front: what is destroyed on this engine before it, and the surface callbacks of
    // the views it is given to, can then leave the wait for the backend to the engine's destroy.
    val engine = remember(eglContext) { engineCreator(eglContext).also { it.deferTeardown() } }
    DisposableEffect(eglContext, engine) {
        onDispose {
            // Engine.destroy() joins the Filament driver thread once it has run everything queued.
            // On the main thread, when the composition leaves while the backend is still setting
            // the scene up, that join is an ANR: the backend is polled instead and the engine is
            // destroyed once it is idle — before this returns when it already is, the usual case.
            // The EGL context is shared with the engine: always released after it.
            engine.destroyWhenBackendIdle { eglContext.destroy() }
        }
    }
    return engine
}

@Composable
inline fun <reified T : Node> rememberNode(crossinline creator: () -> T) =
    remember(creator).also { node ->
        DisposableEffect(node) {
            onDispose {
                node.destroy()
            }
        }
    }

@Composable
fun rememberNode(engine: Engine, creator: Node.() -> Unit = {}) =
    rememberNode { Node(engine).apply(creator) }

@Composable
fun rememberNodes(creator: MutableList<Node>.() -> Unit = {}) = remember {
    buildList(creator).toMutableStateList()
}.also { nodes ->
    DisposableEffect(nodes) {
        onDispose {
            nodes.forEach { it.destroy() }
            nodes.clear()
        }
    }
}

@Composable
fun rememberScene(engine: Engine, creator: () -> Scene = { SceneView.createScene(engine) }) =
    remember(engine, creator).also { scene ->
        DisposableEffect(scene) {
            onDispose {
                engine.safeDestroyScene(scene)
            }
        }
    }

@Composable
fun rememberView(engine: Engine, creator: () -> View = { SceneView.createView(engine) }) =
    remember(engine, creator).also { view ->
        DisposableEffect(view) {
            onDispose {
                engine.safeDestroyView(view)
            }
        }
    }

@Composable
fun rememberRenderer(
    engine: Engine,
    creator: () -> Renderer = { SceneView.createRenderer(engine) }
) = remember(engine, creator).also { renderer ->
    DisposableEffect(renderer) {
        onDispose {
            // Engine.destroyRenderer() waits for everything queued on the backend. On an engine
            // created by rememberEngine() it runs once the backend is idle, before the engine
            // goes; on any other engine it runs now, as before.
            engine.teardownWhenBackendIdle { engine.safeDestroyRenderer(renderer) }
        }
    }
}

@Composable
fun rememberModelLoader(
    engine: Engine,
    context: Context = LocalContext.current,
    creator: () -> ModelLoader = {
        SceneView.createModelLoader(engine, context)
    }
) = remember(engine, context, creator).also { modelLoader ->
    DisposableEffect(modelLoader) {
        onDispose {
            engine.safeDestroyModelLoader(modelLoader)
        }
    }
}

@Composable
fun rememberMaterialLoader(
    engine: Engine,
    context: Context = LocalContext.current,
    creator: () -> MaterialLoader = {
        SceneView.createMaterialLoader(engine, context)
    }
) = remember(engine, context, creator).also { materialLoader ->
    DisposableEffect(materialLoader) {
        onDispose {
            engine.safeDestroyMaterialLoader(materialLoader)
        }
    }
}

@Composable
fun rememberEnvironmentLoader(
    engine: Engine,
    context: Context = LocalContext.current,
    creator: () -> EnvironmentLoader = {
        SceneView.createEnvironmentLoader(engine, context)
    }
) = remember(engine, context, creator).also { environmentLoader ->
    DisposableEffect(environmentLoader) {
        onDispose {
            if (!environmentLoader.iblPrefilter.isCreated) {
                environmentLoader.destroy()
            } else {
                // A prefilter that was used destroys a Filament renderer of its own, which waits
                // for everything queued on the backend: same rule as rememberRenderer. The loads
                // in flight and the environments stop now, as before.
                environmentLoader.clear()
                environmentLoader.engine.teardownWhenBackendIdle { environmentLoader.destroy() }
            }
        }
    }
}

@Composable
fun rememberCameraNode(
    engine: Engine,
    apply: CameraNode.() -> Unit = {},
) = rememberNode {
    SceneView.createCameraNode(engine).apply(apply)
}

@Composable
fun rememberMainLightNode(
    engine: Engine,
    apply: LightNode.() -> Unit = {}
) = rememberNode {
    SceneView.createMainLightNode(engine).apply(apply)
}

@Composable
fun rememberEnvironment(
    environmentLoader: EnvironmentLoader,
    isOpaque: Boolean = true,
    environment: () -> Environment = {
        SceneView.createEnvironment(environmentLoader, isOpaque)
    }
) = remember(environmentLoader, isOpaque, environment).also {
    DisposableEffect(it) {
        onDispose {
            environmentLoader.destroyEnvironment(it)
        }
    }
}

@Composable
fun rememberEnvironment(
    engine: Engine,
    isOpaque: Boolean = true,
    environment: () -> Environment = {
        SceneView.createEnvironment(engine, isOpaque)
    }
) = remember(engine, isOpaque, environment).also {
    DisposableEffect(it) {
        onDispose {
            engine.safeDestroyEnvironment(it)
        }
    }
}

@Composable
fun rememberCollisionSystem(
    view: View,
    creator: () -> CollisionSystem = {
        SceneView.createCollisionSystem(view)
    }
) = remember(view, creator).also { collisionSystem ->
    DisposableEffect(collisionSystem) {
        onDispose {
            collisionSystem.destroy()
        }
    }
}

@Composable
fun rememberOnGestureListener(
    onDown: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onShowPress: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onSingleTapUp: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onScroll: (e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) -> Unit = { _, _, _, _ -> },
    onLongPress: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onFling: (e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2) -> Unit = { _, _, _, _ -> },
    onSingleTapConfirmed: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onDoubleTap: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onDoubleTapEvent: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onContextClick: (e: MotionEvent, node: Node?) -> Unit = { _, _ -> },
    onMoveBegin: (detector: MoveGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onMove: (detector: MoveGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onMoveEnd: (detector: MoveGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onRotateBegin: (detector: RotateGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onRotate: (detector: RotateGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onRotateEnd: (detector: RotateGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onScaleBegin: (detector: ScaleGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onScale: (detector: ScaleGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    onScaleEnd: (detector: ScaleGestureDetector, e: MotionEvent, node: Node?) -> Unit = { _, _, _ -> },
    creator: () -> GestureDetector.OnGestureListener = {
        object : GestureDetector.OnGestureListener {
            override fun onDown(e: MotionEvent, node: Node?) = onDown(e, node)
            override fun onShowPress(e: MotionEvent, node: Node?) = onShowPress(e, node)
            override fun onSingleTapUp(e: MotionEvent, node: Node?) = onSingleTapUp(e, node)
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                node: Node?,
                distance: Float2
            ) = onScroll(e1, e2, node, distance)

            override fun onLongPress(e: MotionEvent, node: Node?) = onLongPress(e, node)
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2) =
                onFling(e1, e2, node, velocity)

            override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) =
                onSingleTapConfirmed(e, node)

            override fun onDoubleTap(e: MotionEvent, node: Node?) = onDoubleTap(e, node)
            override fun onDoubleTapEvent(e: MotionEvent, node: Node?) = onDoubleTapEvent(e, node)
            override fun onContextClick(e: MotionEvent, node: Node?) = onContextClick(e, node)
            override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                onMoveBegin(detector, e, node)

            override fun onMove(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                onMove(detector, e, node)

            override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                onMoveEnd(detector, e, node)

            override fun onRotateBegin(
                detector: RotateGestureDetector,
                e: MotionEvent,
                node: Node?
            ) = onRotateBegin(detector, e, node)

            override fun onRotate(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
                onRotate(detector, e, node)

            override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
                onRotateEnd(detector, e, node)

            override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                onScaleBegin(detector, e, node)

            override fun onScale(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                onScale(detector, e, node)

            override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                onScaleEnd(detector, e, node)
        }
    }
) = remember(creator)

@Composable
fun rememberCameraManipulator(
    orbitHomePosition: Position? = null,
    targetPosition: Position? = null,
    creator: () -> CameraGestureDetector.CameraManipulator = {
        SceneView.createDefaultCameraManipulator(orbitHomePosition, targetPosition)
    }
) = remember(creator).also { collisionSystem ->
    DisposableEffect(collisionSystem) {
        onDispose {
        }
    }
}

@Composable
fun rememberViewNodeManager(
    context: Context = LocalContext.current,
    creator: () -> ViewNode2.WindowManager = {
        SceneView.createViewNodeManager(context)
    }
) = remember(context, creator).also {
    DisposableEffect(it) {
        onDispose {
            it.destroy()
        }
    }
}

@Composable
private fun ScenePreview(modifier: Modifier) {
    Box(
        modifier = modifier
            .background(Color.DarkGray)
    )
}
