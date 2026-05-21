package io.github.sceneview

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.opengl.EGLContext
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.View.BlendMode
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
import io.github.sceneview.utils.readBuffer
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import io.github.sceneview.utils.destroy
import io.github.sceneview.utils.intervalSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.node.findActivity

/**
 * A Filament 3D scene declared as Compose UI.
 *
 * `SceneView` is a `@Composable` that embeds a Filament viewport. Its trailing [content] block is
 * a **[SceneScope]** DSL where every node — models, lights, cameras, geometry, Compose UI — is
 * itself a composable function. Nodes enter the scene on first composition and are automatically
 * destroyed when they leave, with no manual lifecycle management required.
 *
 * 3D content is reactive: pass Compose state into node parameters and the scene updates on the
 * next frame exactly like any other composable.
 *
 * ### Minimal usage
 * ```kotlin
 * SceneView(modifier = Modifier.fillMaxSize()) {
 *     rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let { instance ->
 *         ModelNode(modelInstance = instance, scaleToUnits = 0.5f)
 *     }
 * }
 * ```
 *
 * ### Composing nodes
 * ```kotlin
 * SceneView {
 *     // Nodes are composable functions — nest them to build a scene graph
 *     Node(position = Position(y = 0.5f)) {
 *         ModelNode(modelInstance = helmet)
 *         CubeNode(size = Size(0.05f))
 *     }
 *     LightNode(type = LightManager.Type.DIRECTIONAL)
 * }
 * ```
 *
 * ### AR variant
 * For AR use [io.github.sceneview.ar.ARScene] from the `arsceneview` module.
 *
 * @param modifier              Modifier for the underlying surface.
 * @param surfaceType           [SurfaceType.Surface] (SurfaceView, renders behind Compose layers,
 *                              best GPU performance) or [SurfaceType.TextureSurface] (TextureView,
 *                              renders inline, supports alpha blending). Default: [SurfaceType.Surface].
 * @param engine                Shared Filament [Engine]. Use [rememberEngine].
 * @param modelLoader           Loader for glTF/GLB models. Use [rememberModelLoader].
 * @param materialLoader        Loader for Filament material templates. Use [rememberMaterialLoader].
 * @param environmentLoader     Loader for HDR/KTX environments. Use [rememberEnvironmentLoader].
 * @param view                  Filament [View] (one per window). Use [rememberView].
 * @param isOpaque              Whether the render target is opaque. Default `true`.
 * @param renderQuality         One-line preset applied to `view` ([RenderQuality.Default],
 *                              [RenderQuality.Cinematic], or [RenderQuality.Performance]).
 * @param autoCenterContent     When `true` (default), the library translates all DSL [content]
 *                              nodes once — on the first frame their union bounding box is
 *                              non-empty — so the content centroid lands at the orbit pivot and
 *                              renders centred in the viewport without each node needing
 *                              `ModelNode(centerOrigin = …)`. Lights / camera are passed as
 *                              separate parameters, never DSL children, so they are unaffected.
 *                              Mirrors the iOS `autoCenterContent` feature (#1026). Pass `false`
 *                              for scenes with intentional off-centre placement.
 * @param autoFitContent        When `true`, the library moves [cameraNode] so the DSL [content]
 *                              fills the viewport — regardless of the model's intrinsic glTF size,
 *                              with no per-model `scaleToUnits` tuning (#1439). The pass re-frames
 *                              whenever an async model grows the content's union bounds and
 *                              latches once that union has settled, then leaves the camera alone
 *                              so subsequent user zoom / pan is never fought. Default `false` so
 *                              callers that position [cameraNode] explicitly keep full control;
 *                              opt in for model-viewer style scenes.
 * @param renderer              Filament [Renderer]. Use [rememberRenderer].
 * @param scene                 Filament [Scene] graph, shareable across views. Use [rememberScene].
 * @param environment           IBL + skybox environment. Use [rememberEnvironment].
 * @param mainLightNode         Primary directional light (required for shadows).
 * @param fillLightNode         Secondary fill light (softer ambient — opposite-side directional
 *                              at ~30% main intensity). Use [rememberFillLightNode] or pass `null`
 *                              for a single-light setup.
 * @param cameraNode            Active rendering camera. Use [rememberCameraNode].
 * @param collisionSystem       Hit-testing and collision system. Use [rememberCollisionSystem].
 * @param cameraManipulator     Orbit/pan/zoom camera controller. Use [rememberCameraManipulator].
 * @param viewNodeWindowManager Off-screen window manager required for [SceneScope.ViewNode].
 * @param onGestureListener     Gesture callbacks — tap, double-tap, drag, pinch, etc.
 * @param onTouchEvent          Raw touch event callback with optional hit-test result.
 * @param activity              Host [ComponentActivity] (auto-resolved from [LocalContext]).
 * @param lifecycle             Lifecycle that drives rendering resume/pause.
 * @param onFrame               Called once per rendered frame, immediately before rendering.
 * @param content               Declare 3D scene content using the [SceneScope] composable DSL.
 */
@Composable
fun SceneView(
    modifier: Modifier = Modifier,
    /**
     * Selects whether the backing surface is SurfaceView-based ([SurfaceType.Surface], renders
     * behind Compose, best performance) or TextureView-based ([SurfaceType.TextureSurface],
     * renders inline, supports alpha blending).
     */
    surfaceType: SurfaceType = SurfaceType.Surface,
    /**
     * Provide your own instance if you want to share Filament resources between multiple views.
     */
    engine: Engine = rememberEngine(),
    /**
     * Consumes a blob of glTF 2.0 content (either JSON or GLB) and produces a [Model] object,
     * which is a bundle of Filament textures, vertex buffers, index buffers, etc.
     */
    modelLoader: ModelLoader = rememberModelLoader(engine),
    /**
     * A Filament Material defines the visual appearance of an object.
     * Materials function as templates from which [MaterialInstance]s can be spawned.
     */
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    /**
     * Utility for decoding an HDR file or consuming KTX1 files and producing Filament textures,
     * IBLs, and sky boxes.
     */
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    /**
     * Encompasses all the state needed for rendering a [Scene].
     * [View] instances are heavy objects that internally cache a lot of data needed for rendering.
     */
    view: View = rememberView(engine),
    /**
     * Controls whether the render target is opaque or not. Default `true`.
     */
    isOpaque: Boolean = true,
    /**
     * One-line rendering quality preset applied to [view]. Default [RenderQuality.Default] matches
     * the out-of-the-box `SceneView` settings. Use [RenderQuality.Cinematic] for hero shots on
     * capable devices, or [RenderQuality.Performance] on low-end Android or AR backgrounds where
     * the GPU budget is constrained. Individual [view] settings can still be tweaked after the
     * preset is applied.
     */
    renderQuality: RenderQuality = RenderQuality.Default,
    /**
     * When `true` (default), all DSL [content] nodes are parented to an intermediate content-root
     * node which is translated once — on the first frame the content's union bounding box is
     * non-empty — so the content centroid lands at the orbit pivot and renders centred. Mirrors
     * the iOS library-level `autoCenterContent` feature (#1026 / PR #1038). Pass `false` to keep
     * strict per-node placement semantics for scenes with intentional off-centre composition.
     */
    autoCenterContent: Boolean = true,
    /**
     * When `true`, the library moves [cameraNode] each frame the DSL [content]'s union bounds
     * materially change so the content fills the viewport — regardless of the model's intrinsic
     * glTF size, no per-model `scaleToUnits` tuning (#1439). The auto-fit pass latches once the
     * content's union diagonal has settled across consecutive frames, then leaves the camera alone
     * so the user's zoom / pan is never fought. An async model that finishes loading after a
     * sibling already framed still triggers a re-frame. Default `false` so callers that position
     * [cameraNode] explicitly keep full control — opt in for model-viewer style scenes.
     */
    autoFitContent: Boolean = false,
    /**
     * A [Renderer] instance represents an operating system's window.
     * Typically, applications create a [Renderer] per window.
     */
    renderer: Renderer = rememberRenderer(engine),
    /**
     * Provide your own instance if you want to share [Node]s' scene between multiple views.
     */
    scene: Scene = rememberScene(engine),
    /**
     * Defines the lighting environment and the skybox of the scene.
     */
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    /**
     * Always add a direct light source since it is required for shadowing.
     * We highly recommend adding an [IndirectLight] as well.
     */
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    /**
     * Optional secondary "fill" directional light that softens the shadows produced by
     * [mainLightNode]. Default mirrors iOS RealityKit's two-light setup (main + fill at 30%).
     * Pass `null` for a single-light scene.
     */
    fillLightNode: LightNode? = rememberFillLightNode(engine),
    /**
     * Represents a virtual camera, which determines the perspective through which the scene is
     * viewed.
     */
    cameraNode: CameraNode = rememberCameraNode(engine),
    /**
     * Physics system to handle collision between nodes, hit testing on nodes, etc.
     */
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    /**
     * Helper that enables camera interaction similar to sketchfab or Google Maps.
     */
    cameraManipulator: CameraGestureDetector.CameraManipulator? = rememberCameraManipulator(
        cameraNode.worldPosition
    ),
    /**
     * Used for [SceneScope.ViewNode] composables — manages the off-screen window attachment.
     * Obtain with [rememberViewNodeManager].
     */
    viewNodeWindowManager: ViewNode.WindowManager? = null,
    /**
     * The listener invoked for all gesture detector callbacks.
     */
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    activity: ComponentActivity? = LocalContext.current as? ComponentActivity,
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    /**
     * Invoked once per frame immediately before the scene is updated and rendered.
     */
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
    /**
     * Declare scene nodes using the [SceneScope] DSL.
     */
    content: (@Composable SceneScope.() -> Unit)? = null
) {
    if (LocalInspectionMode.current) {
        ScenePreview(modifier)
        return
    }

    val context = LocalContext.current

    // ── Node DSL state ────────────────────────────────────────────────────────────────────────────

    val scopeChildNodes: SnapshotStateList<Node> = remember { mutableStateListOf() }

    // ── Scene / camera / environment setup ───────────────────────────────────────────────────────

    val nodeManager = remember(scene, collisionSystem) { SceneNodeManager(scene, collisionSystem) }

    SideEffect {
        scene.indirectLight = environment.indirectLight
        scene.skybox = environment.skybox
        view.scene = scene
        view.camera = cameraNode.camera
        cameraNode.collisionSystem = collisionSystem
        cameraNode.setView(view)
        // Pair with `uiHelper.isOpaque` set in SceneRenderer.attachToSurfaceView/
        // TextureView (#1077). Without this, the fragment pipeline blends opaque
        // even when the swap chain is CONFIG_TRANSPARENT — nothing under the
        // SceneView shows through.
        view.blendMode = if (isOpaque) BlendMode.OPAQUE else BlendMode.TRANSLUCENT
    }
    // Keyed `LaunchedEffect` so the preset is reapplied ONLY when `renderQuality`
    // actually changes (#1078). The previous unkeyed `SideEffect` ran on every
    // recomposition and silently overwrote any post-Scene `view.colorGrading`,
    // `view.bloomOptions.strength`, etc. tweaks — breaking the contract documented
    // at `RenderQuality.kt`'s "Apply additional View tweaks AFTER calling this —
    // they will not be undone".
    LaunchedEffect(view, renderQuality) {
        view.applyRenderQuality(renderQuality)
    }

    // ── Camera node — registered so children (HUD nodes) are tracked by the scene manager ─────────
    //
    // The cameraNode entity itself has no renderable component so adding it to the Filament scene
    // is harmless. What matters is that nodeManager.addNode() wires onChildAdded → ::addNode so
    // any node parented to the camera (e.g. a compass arrow) is automatically added to the scene
    // and rendered in camera/HUD space via Filament's TransformManager hierarchy.
    //
    // DisposableEffect (NOT SideEffect) so the camera is removed from the Filament Scene
    // both on (a) key change and (b) composition disposal. See #1143 — same leak shape as
    // #1122 lights for the documented "share scene between views" use case.

    DisposableEffect(cameraNode) {
        nodeManager.addNode(cameraNode)
        onDispose {
            nodeManager.removeNode(cameraNode)
        }
    }

    // ── Main light node ───────────────────────────────────────────────────────────────────────────
    //
    // DisposableEffect (NOT SideEffect) so the light is removed from the Filament Scene
    // both on (a) key change and (b) composition disposal. Pre-#1122 used SideEffect which
    // only swapped on key change — a SceneView leaving composition cleanly would leak the
    // 2 lights into a shared `rememberScene(engine)`. See #1122.

    DisposableEffect(mainLightNode) {
        mainLightNode?.let { nodeManager.addNode(it) }
        onDispose {
            mainLightNode?.let { nodeManager.removeNode(it) }
        }
    }

    // ── Fill light node ───────────────────────────────────────────────────────────────────────────

    DisposableEffect(fillLightNode) {
        fillLightNode?.let { nodeManager.addNode(it) }
        onDispose {
            fillLightNode?.let { nodeManager.removeNode(it) }
        }
    }

    // ── Auto-center content (#1026 — port of the iOS library-level autoCenterContent) ────────────
    //
    // Intermediate content-root node. When `autoCenterContent` is on, every DSL `content` node is
    // parented to it instead of being added to the Filament scene directly — the `nodeManager`
    // propagates the children automatically via its `onChildAdded` hook. Translating this single
    // node once recentres the whole scene without touching lights / camera (those are separate
    // `SceneView` parameters, never DSL children, so they stay put — exactly like iOS keeping
    // lights on `entities.root` rather than `contentRoot`). When `autoCenterContent` is off the
    // content root is unused and nodes register directly, preserving pre-#1051 behaviour.

    val contentRoot = remember(engine) { Node(engine) }
    val autoCenterState = remember { SceneAutoCenterState() }

    // ── Auto-fit camera framing (#1439 — drives the autoFitContent parameter) ────────────────────
    //
    // One-shot-per-content auto-fit: each frame the content's union bounds materially change the
    // camera is moved so the content fills the viewport, then the pass latches once that union has
    // settled (diagonal-stability gate — #1596). When `autoCenterContent` is on, framing measures
    // the single `contentRoot` subtree; when it is off the DSL nodes register directly so framing
    // unions every registered child node instead.
    val autoFitState = remember { SceneAutoFitState() }

    DisposableEffect(autoCenterContent, contentRoot) {
        if (autoCenterContent) {
            nodeManager.addNode(contentRoot)
        }
        onDispose {
            if (autoCenterContent) {
                nodeManager.removeNode(contentRoot)
            }
        }
    }

    // ── DSL nodes → Filament scene sync ──────────────────────────────────────────────────────────

    val childNodesRef = remember { AtomicReference(emptyList<Node>()) }

    LaunchedEffect(nodeManager, autoCenterContent, contentRoot) {
        var prevNodes = emptyList<Node>()
        snapshotFlow { scopeChildNodes.toList() }.collect { newNodes ->
            if (autoCenterContent) {
                // Parent / unparent under the content root — `nodeManager` follows via onChildAdded.
                (prevNodes - newNodes.toSet()).forEach { node ->
                    if (node.parent == contentRoot) node.parent = null
                }
                (newNodes - prevNodes.toSet()).forEach { node -> node.parent = contentRoot }
                // Content changed — re-arm centering / framing so a replaced (possibly smaller)
                // scene re-frames. A growing union already re-frames via the diagonal gate.
                autoCenterState.reset()
                autoFitState.reset()
            } else {
                (prevNodes - newNodes.toSet()).forEach { nodeManager.removeNode(it) }
                (newNodes - prevNodes.toSet()).forEach { nodeManager.addNode(it) }
                // Content changed — re-arm framing so a replaced scene re-frames.
                autoFitState.reset()
            }
            prevNodes = newNodes
            childNodesRef.set(newNodes)
        }
    }

    // ── Lifecycle-aware rendering ─────────────────────────────────────────────────────────────────

    val isResumed = remember {
        AtomicBoolean(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    // Reference to the owner View (SurfaceView/TextureView) that the scene is attached to.
    // Used by viewNodeWindowManager.resume() to locate the parent window for off-screen attachment.
    val ownerViewRef = remember { AtomicReference<android.view.View?>(null) }
    DisposableEffect(lifecycle, viewNodeWindowManager) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                isResumed.set(true)
                // Attach the ViewNode off-screen window on resume. Without this, ViewNode instances
                // render only a black rectangle because their backing Layout is never attached to
                // android.view.WindowManager, meaning onLayout is never called and the
                // SurfaceTexture stays at 0x0. See sceneview/sceneview#801.
                ownerViewRef.get()?.let { ownerView ->
                    viewNodeWindowManager?.resume(ownerView)
                }
            }
            override fun onPause(owner: LifecycleOwner) {
                isResumed.set(false)
                viewNodeWindowManager?.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewNodeWindowManager?.pause()
        }
    }

    // ── Gesture detection ────────────────────────────────────────────────────────────────────────

    val lastFrameTimeNanosRef = remember { AtomicLong(0L) }
    val gestureDetector = remember(context) { GestureDetector(context = context, listener = null) }
    val cameraGestureDetectorRef = remember { AtomicReference<CameraGestureDetector?>(null) }

    SideEffect {
        gestureDetector.listener = onGestureListener
        cameraGestureDetectorRef.get()?.cameraManipulator = cameraManipulator
    }

    // Common touch dispatcher — wired to both SurfaceView and TextureView via SceneRenderer.
    //
    // Gesture isolation: when the touch lands on an editable node, the camera gesture
    // detector is skipped so the gesture is fully absorbed by the node. Without this,
    // dragging/twisting/pinching an editable helmet would also orbit/pan/zoom the camera
    // simultaneously (because both detectors received every event), making per-node
    // editing feel "leaky" and unresponsive.
    val touchDispatcher: (MotionEvent) -> Unit = { event ->
        val hitResult = collisionSystem.hitTest(event).firstOrNull { it.node.isTouchable }
        if (onTouchEvent?.invoke(event, hitResult) != true &&
            hitResult?.node?.onTouchEvent(event, hitResult) != true
        ) {
            gestureDetector.onTouchEvent(event, hitResult)
            // Skip the camera detector when the touch is on an editable node — the node
            // owns the gesture. We check the hit node's master `isEditable` (and not the
            // per-axis flags) so a node that's "editable but with all axes locked" still
            // absorbs the touch — locking an axis should freeze the node, not divert the
            // gesture to the camera (that would surprise the user).
            val absorbedByEditableNode = hitResult?.node?.isEditable == true
            if (!absorbedByEditableNode) {
                cameraGestureDetectorRef.get()?.onTouchEvent(event)
            }
        }
    }

    // ── SceneRenderer — encapsulates surface lifecycle + swap chain + frame pipeline ─────────────

    @Suppress("DEPRECATION")
    val display = remember(context) {
        (context.getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
    }

    val sceneRenderer = remember(engine, view, renderer) {
        SceneRenderer(engine, view, renderer)
    }

    // Wire resize and surface callbacks.
    SideEffect {
        sceneRenderer.onSurfaceResized = { width, height ->
            cameraManipulator?.setViewport(width, height)
            cameraNode.updateProjection()
        }
        sceneRenderer.onSurfaceReady = { viewHeight ->
            if (cameraGestureDetectorRef.get() == null) {
                cameraGestureDetectorRef.set(
                    CameraGestureDetector(
                        viewHeight = viewHeight,
                        cameraManipulator = cameraManipulator
                    )
                )
            }
        }
        sceneRenderer.onSurfaceDestroyed = {
            cameraGestureDetectorRef.set(null)
        }
    }

    DisposableEffect(sceneRenderer) {
        onDispose { sceneRenderer.destroy() }
    }

    // ── Render loop ───────────────────────────────────────────────────────────────────────────────

    // Keep the caller's onFrame lambda live across recompositions. LaunchedEffect captures its
    // body at launch time and never re-runs while (engine, renderer, view, scene) are stable, so
    // reading `onFrame` directly would pin the lambda from the very first composition — any caller
    // that reads Compose state inside it (e.g. DebugOverlayDemo's `if (modelInstance != null) 1
    // else 0`) would see only the initial null state, forever. rememberUpdatedState solves this by
    // reading the ref inside the loop.
    val currentOnFrame = rememberUpdatedState(onFrame)
    // The frame loop captures `cameraManipulator` in its lambda. Without
    // [rememberUpdatedState] the closure freezes on the manipulator that was active
    // at LaunchedEffect launch time, so callers that swap manipulators at runtime
    // (e.g. AnimationDemo's scripted → Free hand-off, or any custom mode picker)
    // see grabBegin/grabUpdate land on the new manipulator via the gesture detector
    // SideEffect above, but `getTransform()` keeps reading the old one and the camera
    // never moves. Reading through a state ref here makes the frame loop pick up
    // every recomposition without restarting.
    val currentCameraManipulator = rememberUpdatedState(cameraManipulator)
    // Read through a state ref so toggling `autoCenterContent` at runtime is picked up by the
    // frame loop without restarting it (the loop's LaunchedEffect is keyed on engine/renderer/
    // view/scene only).
    val currentAutoCenterContent = rememberUpdatedState(autoCenterContent)
    // Same for `autoFitContent` — the auto-fit pass is read through this ref so toggling the
    // parameter at runtime is picked up by the frame loop without restarting it.
    val currentAutoFitContent = rememberUpdatedState(autoFitContent)

    LaunchedEffect(engine, renderer, view, scene) {
        while (true) {
            if (!isResumed.get()) {
                delay(100)
                continue
            }
            withFrameNanos { frameTimeNanos ->
                sceneRenderer.renderFrame(frameTimeNanos) {
                    modelLoader.updateLoad()
                    childNodesRef.get().forEach { it.onFrame(frameTimeNanos) }

                    // Library-level auto-center (#1026). No-op once the content union has settled
                    // and the gate latched, and skipped while the content bounds are still empty
                    // (async model loads not finished). Runs here so it sees post-`updateLoad`
                    // geometry, on the main render thread — Filament transform / renderable reads
                    // require it. The diagonal-stability gate (#1596) re-runs the pass when an
                    // async model grows the union, so deferred models still re-centre.
                    if (currentAutoCenterContent.value) {
                        autoCenterState.maybeCenter(contentRoot)
                    }

                    // Library-level auto-fit camera framing (#1439). Drives the `autoFitContent`
                    // parameter: moves the camera so the content fills the viewport. Only applied
                    // when there is NO camera manipulator — an active orbit manipulator owns the
                    // camera transform every frame (it is overwritten just below from
                    // `manipulator.getTransform()`), so a static auto-fit reposition cannot
                    // coexist with it without manipulator re-seeding. With no manipulator, this is
                    // the canonical model-viewer one-shot framing. Runs after auto-center so it
                    // frames the already-centred content; the diagonal-stability gate re-frames
                    // when a deferred async model grows the union (#1596).
                    if (currentAutoFitContent.value && currentCameraManipulator.value == null) {
                        if (currentAutoCenterContent.value) {
                            autoFitState.maybeFit(cameraNode, contentRoot)
                        } else {
                            autoFitState.maybeFit(cameraNode, childNodesRef.get())
                        }
                    }

                    currentCameraManipulator.value?.let { manipulator ->
                        val lastTime = lastFrameTimeNanosRef.get().takeIf { it != 0L }
                        manipulator.update(frameTimeNanos.intervalSeconds(lastTime).toFloat())
                        cameraNode.transform = manipulator.getTransform()
                    }

                    currentOnFrame.value?.invoke(frameTimeNanos)
                }

                lastFrameTimeNanosRef.set(frameTimeNanos)
            }
        }
    }

    // ── Surface view ──────────────────────────────────────────────────────────────────────────────

    when (surfaceType) {
        SurfaceType.Surface -> AndroidView(
            modifier = modifier,
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sceneRenderer.attachToSurfaceView(sv, isOpaque, ctx, display, touchDispatcher)
                    // Record the owner view so the ViewNode off-screen WindowManager can attach to
                    // its parent window once the lifecycle is RESUMED.
                    ownerViewRef.set(sv)
                    if (isResumed.get()) viewNodeWindowManager?.resume(sv)
                }
            },
            update = {}
        )

        SurfaceType.TextureSurface -> AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    sceneRenderer.attachToTextureView(tv, isOpaque, ctx, display, touchDispatcher)
                    ownerViewRef.set(tv)
                    if (isResumed.get()) viewNodeWindowManager?.resume(tv)
                }
            },
            update = {}
        )
    }

    // ── DSL content ───────────────────────────────────────────────────────────────────────────────

    if (content != null) {
        val scope = remember(engine, modelLoader, materialLoader, environmentLoader, nodeManager) {
            SceneScope(
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                _nodes = scopeChildNodes,
                nodeRemover = nodeManager::removeNode
            )
        }
        scope.content()
    }
}

// ── Async resource helpers ────────────────────────────────────────────────────────────────────────

/**
 * Asynchronously loads a glTF/GLB [ModelInstance] from [assetFileLocation].
 *
 * Returns `null` while loading is in progress, then triggers recomposition once the model is
 * ready. This makes it easy to use with conditional node declarations:
 * ```kotlin
 * SceneView {
 *     rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
 *         ModelNode(modelInstance = instance, scaleToUnits = 0.5f)
 *     }
 * }
 * ```
 *
 * @param modelLoader       The [ModelLoader] to use.
 * @param assetFileLocation Path to the GLB/glTF file relative to the `assets` folder.
 * @return                  `null` while loading; the loaded [ModelInstance] once ready.
 */
@Composable
fun rememberModelInstance(
    modelLoader: ModelLoader,
    assetFileLocation: String
): ModelInstance? {
    val context = LocalContext.current
    return produceState<ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = assetFileLocation
    ) {
        // Read file bytes on IO, then call Filament APIs back on Main (produceState's context).
        val buffer = withContext(Dispatchers.IO) {
            runCatching { context.assets.readBuffer(assetFileLocation) }.getOrNull()
        } ?: return@produceState
        value = runCatching { modelLoader.createModelInstance(buffer) }.getOrNull()
    }.value
}

/**
 * Creates and remembers a [ModelInstance] loaded from any file location including remote URLs.
 *
 * Supports:
 * - Asset paths: `"models/helmet.glb"`
 * - File URIs: `"file:///sdcard/model.glb"`
 * - HTTP/HTTPS URLs: `"https://example.com/model.glb"`
 *
 * For asset paths (no scheme), delegates to the faster asset-based overload.
 * For URLs, downloads the file on IO and creates the model on Main.
 *
 * @param modelLoader  The [ModelLoader] to use.
 * @param fileLocation Path, URI, or URL to the GLB/glTF file.
 * @return             `null` while loading; the loaded [ModelInstance] once ready.
 */
@Composable
fun rememberModelInstance(
    modelLoader: ModelLoader,
    fileLocation: String,
    resourceResolver: (resourceFileName: String) -> String = {
        ModelLoader.getFolderPath(fileLocation, it)
    }
): ModelInstance? {
    val uri = android.net.Uri.parse(fileLocation)
    // Fast path: plain asset file name (no scheme) → use synchronous asset reader
    if (uri.scheme == null) {
        return rememberModelInstance(modelLoader, assetFileLocation = fileLocation)
    }
    // URL / file URI / content URI → use suspend loadModelInstance which handles http(s)
    return produceState<ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = fileLocation
    ) {
        value = runCatching {
            modelLoader.loadModelInstance(fileLocation, resourceResolver)
        }.getOrNull()
    }.value
}

// ── Video helper ──────────────────────────────────────────────────────────────────────────────────

/**
 * Creates and remembers a [android.media.MediaPlayer] configured for the given [assetFileLocation].
 *
 * The player is prepared synchronously on the IO dispatcher and returned once ready. Returns
 * `null` while loading. The player is released automatically when the composition leaves the
 * tree.
 *
 * Use this with [SceneScope.VideoNode] for easy video playback in 3D:
 * ```kotlin
 * SceneView {
 *     val player = rememberMediaPlayer(context, assetFileLocation = "videos/promo.mp4")
 *     if (player != null) {
 *         VideoNode(player = player, position = Position(z = -2f))
 *     }
 * }
 * ```
 *
 * @param context            Android context for resolving the asset.
 * @param assetFileLocation  Path to the video file relative to the `assets` folder.
 * @param isLooping          Whether the video should loop. Default `true`.
 * @param autoStart          Whether to start playback immediately once prepared. Default `true`.
 * @return The prepared [android.media.MediaPlayer], or `null` while loading.
 */
@io.github.sceneview.ExperimentalSceneViewApi
@Composable
fun rememberMediaPlayer(
    context: android.content.Context = LocalContext.current,
    assetFileLocation: String,
    isLooping: Boolean = true,
    autoStart: Boolean = true
): android.media.MediaPlayer? {
    val player = remember(assetFileLocation) {
        runCatching {
            val afd = context.assets.openFd(assetFileLocation)
            android.media.MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                this.isLooping = isLooping
                prepare()
                if (autoStart) start()
            }
        }.getOrNull()
    }
    DisposableEffect(player) {
        onDispose {
            player?.release()
        }
    }
    return player
}

// ── Engine / resource lifecycle helpers ──────────────────────────────────────────────────────────

/**
 * Creates and remembers a Filament [Engine] and its backing EGL context.
 *
 * The engine is the root Filament object. It owns all other Filament resources and must outlive
 * them. Both the engine and its EGL context are destroyed automatically when the composition
 * leaves the tree.
 *
 * Only one engine per process is typically needed. Pass it explicitly to all `remember*` helpers
 * if you want to share Filament resources across multiple `SceneView` composables.
 *
 * @param eglContextCreator Factory for the EGL context. Override for custom EGL configurations.
 * @param engineCreator     Factory for the [Engine]. Override to customise engine flags.
 * @return A [Engine] that is destroyed with its EGL context on disposal.
 */
@Composable
fun rememberEngine(
    eglContextCreator: () -> EGLContext = { createEglContext() },
    engineCreator: (eglContext: EGLContext) -> Engine = { createEngine(it) }
): Engine {
    val eglContext = remember(eglContextCreator)
    val engine = remember(eglContext) { engineCreator(eglContext) }
    DisposableEffect(eglContext, engine) {
        onDispose {
            engine.safeDestroy()
            eglContext.destroy()
        }
    }
    return engine
}

/**
 * Creates and remembers a [Node] of type [T] using [creator], destroying it on disposal.
 *
 * Use this overload when you need a standalone node (e.g. a camera rig pivot) that lives
 * outside the `SceneView { }` content block and must be passed as a parameter to `SceneView`.
 *
 * ```kotlin
 * val centerNode = rememberNode(engine)
 * val cameraNode = rememberCameraNode(engine) {
 *     position = Position(z = 3.0f)
 *     centerNode.addChildNode(this)
 * }
 * SceneView(cameraNode = cameraNode) { ... }
 * ```
 *
 * @param creator Factory that produces the node. Called once and memoised.
 * @return The created node, destroyed when the composition leaves the tree.
 */
@Composable
inline fun <reified T : Node> rememberNode(crossinline creator: () -> T) =
    remember(creator).also { node ->
        DisposableEffect(node) {
            onDispose {
                node.destroy()
            }
        }
    }

/**
 * Creates and remembers a base [Node] using the Filament [engine].
 *
 * @param engine  The Filament engine to create the node with.
 * @param creator Optional configuration block applied to the node after creation.
 * @return A [Node] destroyed on disposal.
 */
@Composable
fun rememberNode(engine: Engine, creator: Node.() -> Unit = {}) =
    rememberNode { Node(engine).apply(creator) }

/**
 * Creates and remembers a Filament [Scene].
 *
 * A `Scene` is a flat container of Filament entities (renderables, lights). It can be shared
 * across multiple [View]s. Destroyed on disposal.
 *
 * You rarely need to call this directly — `SceneView { }` creates one by default.
 * Provide your own if you want to share the same scene graph across multiple composables.
 *
 * @param engine  The Filament [Engine] that owns this scene.
 * @param creator Factory for the scene. Override for custom scene flags.
 */
@Composable
fun rememberScene(engine: Engine, creator: () -> Scene = { createScene(engine) }) =
    remember(engine, creator).also { scene ->
        DisposableEffect(scene) {
            onDispose {
                engine.safeDestroyScene(scene)
            }
        }
    }

/**
 * Creates and remembers a Filament [View].
 *
 * A `View` is a heavy object that holds all rendering state for a single viewport — anti-aliasing,
 * shadows, post-processing, etc. One per window is recommended. Destroyed on disposal.
 *
 * You rarely need to call this directly — `SceneView { }` creates one by default.
 * Provide your own if you want to share the view with a [CollisionSystem] that is declared
 * outside the `SceneView { }` block.
 *
 * @param engine  The Filament [Engine] that owns this view.
 * @param creator Factory for the view. Override for custom view flags.
 */
@Composable
fun rememberView(engine: Engine, creator: () -> View = { createView(engine) }) =
    remember(engine, creator).also { view ->
        DisposableEffect(view) {
            onDispose {
                engine.safeDestroyView(view)
            }
        }
    }

/**
 * Creates and remembers a Filament [View] tuned for AR (used as the default in `ARScene`).
 *
 * Uses [createARView] instead of [createView] — the AR view keeps bloom and ambient occlusion off
 * so the camera background is not tinted, while still applying the Filmic tone mapper that the
 * camera-stream shader's `inverseTonemapSRGB` needs in order to round-trip back to the original
 * camera pixels.
 *
 * @see createARView for a full explanation of the AR camera-background tone-mapping pipeline.
 */
@Composable
fun rememberARView(engine: Engine, creator: () -> View = { createARView(engine) }) =
    remember(engine, creator).also { view ->
        DisposableEffect(view) {
            onDispose {
                engine.safeDestroyView(view)
            }
        }
    }

/**
 * Creates and remembers a Filament [Renderer].
 *
 * A `Renderer` represents an operating system window and drives the frame pipeline —
 * `beginFrame`, `render`, `endFrame`. One per window is recommended. Destroyed on disposal.
 *
 * You rarely need to call this directly — `SceneView { }` creates one by default.
 *
 * @param engine  The Filament [Engine] that owns this renderer.
 * @param creator Factory for the renderer.
 */
@Composable
fun rememberRenderer(
    engine: Engine,
    creator: () -> Renderer = { createRenderer(engine) }
) = remember(engine, creator).also { renderer ->
    DisposableEffect(renderer) {
        onDispose {
            engine.safeDestroyRenderer(renderer)
        }
    }
}

/**
 * Creates and remembers a [ModelLoader] for loading glTF/GLB assets.
 *
 * `ModelLoader` consumes glTF 2.0 content (JSON or binary GLB) and produces Filament textures,
 * vertex buffers, index buffers, and material instances. It also drives incremental async
 * loading via `updateLoad()`, which is called automatically every frame inside `SceneView`.
 *
 * Use [rememberModelInstance] to load a specific model file.
 *
 * @param engine  The Filament [Engine] that owns the loaded assets.
 * @param context Android context used to open asset files. Defaults to [LocalContext].
 * @param creator Factory for the loader.
 */
@Composable
fun rememberModelLoader(
    engine: Engine,
    context: Context = LocalContext.current,
    creator: () -> ModelLoader = {
        engine.createModelLoader(context)
    }
) = remember(engine, context, creator).also { modelLoader ->
    DisposableEffect(modelLoader) {
        onDispose {
            engine.safeDestroyModelLoader(modelLoader)
        }
    }
}

/**
 * Creates and remembers a [MaterialLoader] for building Filament material instances.
 *
 * `MaterialLoader` holds a set of compiled material templates (`.filamat` files bundled as
 * assets) and provides factory methods for creating `MaterialInstance`s — e.g.
 * `createColorInstance(color, metallic, roughness)` for a quick PBR material.
 *
 * The loader is required by geometry nodes (`CubeNode`, `SphereNode`, etc.) and `ImageNode`.
 *
 * @param engine  The Filament [Engine] that owns the material instances.
 * @param context Android context used to open bundled material assets. Defaults to [LocalContext].
 * @param creator Factory for the loader.
 */
@Composable
fun rememberMaterialLoader(
    engine: Engine,
    context: Context = LocalContext.current,
    creator: () -> MaterialLoader = {
        engine.createMaterialLoader(context)
    }
) = remember(engine, context, creator).also { materialLoader ->
    DisposableEffect(materialLoader) {
        onDispose {
            engine.safeDestroyMaterialLoader(materialLoader)
        }
    }
}

/**
 * Creates and remembers an [EnvironmentLoader] for decoding HDR and KTX1 environment assets.
 *
 * `EnvironmentLoader` turns an equirectangular HDR file (or a pair of pre-filtered KTX1 files)
 * into a Filament `IndirectLight` (image-based lighting) and optional `Skybox`. Use it with
 * [rememberEnvironment] to wire the result into a `SceneView`.
 *
 * @param engine  The Filament [Engine] that owns the produced textures.
 * @param context Android context used to open asset files. Defaults to [LocalContext].
 * @param creator Factory for the loader.
 */
@Composable
fun rememberEnvironmentLoader(
    engine: Engine,
    context: Context = LocalContext.current,
    creator: () -> EnvironmentLoader = {
        engine.createEnvironmentLoader(context)
    }
) = remember(engine, context, creator).also { environmentLoader ->
    DisposableEffect(environmentLoader) {
        onDispose {
            environmentLoader.destroy()
        }
    }
}

/**
 * Creates and remembers the main rendering [CameraNode].
 *
 * The camera node determines the viewpoint and projection of the scene. Pass it to
 * `SceneView(cameraNode = ...)` to set it as the active camera.
 *
 * ```kotlin
 * val cameraNode = rememberCameraNode(engine) {
 *     position = Position(z = 4.0f)
 *     lookAt(Position(0f, 0f, 0f))
 * }
 * SceneView(cameraNode = cameraNode) { ... }
 * ```
 *
 * @param engine The Filament [Engine] that owns the camera.
 * @param apply  Configuration block applied to the node after creation (position, FOV, etc.).
 * @return A [CameraNode] destroyed on disposal.
 */
@Composable
fun rememberCameraNode(
    engine: Engine,
    apply: CameraNode.() -> Unit = {},
) = rememberNode {
    createCameraNode(engine).apply(apply)
}

/**
 * Creates and remembers the primary directional [LightNode] (the sun).
 *
 * A direct light source is required for shadows. The default configuration creates a
 * `LightManager.Type.DIRECTIONAL` light with intensity suitable for outdoor scenes.
 * Combine with [rememberEnvironment] (IBL) for physically-based lighting.
 *
 * ```kotlin
 * SceneView(
 *     mainLightNode = rememberMainLightNode(engine) {
 *         intensity = 100_000.0f
 *     }
 * )
 * ```
 *
 * The [apply] block is **reactive**: it is re-invoked on every recomposition, so light
 * properties driven by Compose state (e.g. `intensity = animatedIntensity`) propagate to the
 * Filament scene without re-keying the [remember]. This mirrors the iOS `RealityView.update:`
 * reactive light contract (#1031) and closes the cross-platform parity gap of #1306. The
 * underlying [LightComponent] setters write straight to Filament's `LightManager`, so the
 * re-apply only touches properties the caller actually mutated.
 *
 * @param engine The Filament [Engine] that owns the light.
 * @param apply  Configuration block applied after creation and re-applied on every recomposition
 *               (intensity, direction, color, etc.).
 * @return A [LightNode] destroyed on disposal.
 */
@Composable
fun rememberMainLightNode(
    engine: Engine,
    apply: LightNode.() -> Unit = {}
) = rememberNode {
    createMainLightNode(engine)
}.also { node ->
    // Re-apply on every recomposition so Compose-state-driven light properties stay reactive.
    // SideEffect runs on the composition applier (main) thread — required for Filament JNI.
    SideEffect { node.apply(apply) }
}

/**
 * Creates and remembers a secondary "fill" [LightNode] that softens shadows produced by the
 * main directional light.
 *
 * Mirrors iOS RealityKit's default two-light setup (one bright sun + one soft fill at ~30%
 * intensity from the opposite side). Combine with [rememberMainLightNode] for a balanced look
 * with less contrast on the shadow side of objects.
 *
 * ```kotlin
 * SceneView(
 *     mainLightNode = rememberMainLightNode(engine),
 *     fillLightNode = rememberFillLightNode(engine) {
 *         intensity = 5_000.0f  // brighter fill if scene needs it
 *     }
 * )
 * ```
 *
 * The [apply] block is **reactive**: it is re-invoked on every recomposition, so light
 * properties driven by Compose state propagate to the Filament scene without re-keying the
 * [remember] — matching [rememberMainLightNode] and the iOS reactive light contract (#1306).
 *
 * @param engine The Filament [Engine] that owns the light.
 * @param apply  Configuration block applied after creation and re-applied on every recomposition
 *               (intensity, direction, color, etc.).
 * @return A [LightNode] destroyed on disposal.
 */
@Composable
fun rememberFillLightNode(
    engine: Engine,
    apply: LightNode.() -> Unit = {}
) = rememberNode {
    createFillLightNode(engine)
}.also { node ->
    // Re-apply on every recomposition so Compose-state-driven light properties stay reactive.
    // SideEffect runs on the composition applier (main) thread — required for Filament JNI.
    SideEffect { node.apply(apply) }
}

/**
 * Creates and remembers an [Environment] from an [EnvironmentLoader].
 *
 * An `Environment` bundles a Filament `IndirectLight` (image-based lighting) with an optional
 * `Skybox`. Pass the result to `SceneView(environment = ...)`.
 *
 * The [environment] factory lambda runs once and is memoised. Use it to load an HDR file:
 * ```kotlin
 * val environment = rememberEnvironment(environmentLoader) {
 *     environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
 *         ?: createEnvironment(environmentLoader)
 * }
 * ```
 *
 * @param environmentLoader The loader that produced the IBL textures.
 * @param isOpaque          If `false`, the skybox is cleared so the surface background shows through.
 * @param environment       Factory that produces the [Environment]. Memoised by the loader + opacity key.
 * @return An [Environment] destroyed on disposal.
 */
@Composable
fun rememberEnvironment(
    environmentLoader: EnvironmentLoader,
    isOpaque: Boolean = true,
    environment: () -> Environment = {
        createEnvironment(environmentLoader, isOpaque)
    }
) = remember(environmentLoader, isOpaque, environment).also {
    DisposableEffect(it) {
        onDispose {
            environmentLoader.destroyEnvironment(it)
        }
    }
}

/**
 * Creates and remembers an [Environment] directly from a Filament [Engine].
 *
 * Use this overload when you want to construct the [Environment] manually (e.g. from KTX
 * assets) without an [EnvironmentLoader].
 *
 * @param engine      The Filament [Engine] that owns the IBL and skybox textures.
 * @param isOpaque    If `false`, the skybox is cleared so the surface background shows through.
 * @param environment Factory that produces the [Environment]. Memoised by the engine + opacity key.
 * @return An [Environment] destroyed on disposal.
 */
@Composable
fun rememberEnvironment(
    engine: Engine,
    isOpaque: Boolean = true,
    environment: () -> Environment = {
        createEnvironment(engine, isOpaque)
    }
) = remember(engine, isOpaque, environment).also {
    DisposableEffect(it) {
        onDispose {
            engine.safeDestroyEnvironment(it)
        }
    }
}

/**
 * Creates and remembers a [CollisionSystem] for hit testing and node interaction.
 *
 * The collision system maps touch events to 3D nodes using the [View]'s projection and the
 * bounding boxes of all scene nodes. It is called automatically by the touch dispatcher inside
 * `SceneView`, so you only need to provide this explicitly if you declared the [View] yourself via
 * [rememberView].
 *
 * @param view    The Filament [View] whose projection is used for hit testing.
 * @param creator Factory for the collision system.
 * @return A [CollisionSystem] destroyed on disposal.
 */
@Composable
fun rememberCollisionSystem(
    view: View,
    creator: () -> CollisionSystem = {
        createCollisionSystem(view)
    }
) = remember(view, creator).also { collisionSystem ->
    DisposableEffect(collisionSystem) {
        onDispose {
            collisionSystem.destroy()
        }
    }
}

/**
 * Creates and remembers a [GestureDetector.OnGestureListener] from individual lambda callbacks.
 *
 * Provides a composable-friendly way to listen for gestures on scene nodes. Each callback
 * receives the triggering [MotionEvent] and the [Node] that was hit (or `null` for empty-space
 * gestures). Pass the result to `SceneView(onGestureListener = ...)`.
 *
 * The most commonly used callbacks:
 * - [onSingleTapConfirmed] — reliable single-tap, fired after double-tap window expires
 * - [onDoubleTap] — double-tap on a node or empty space
 * - [onMove] / [onMoveBegin] / [onMoveEnd] — drag gesture on a node
 * - [onRotate] / [onRotateBegin] / [onRotateEnd] — two-finger rotate gesture
 * - [onScale] / [onScaleBegin] / [onScaleEnd] — pinch-to-scale gesture
 *
 * ```kotlin
 * onGestureListener = rememberOnGestureListener(
 *     onDoubleTap  = { _, node -> node?.apply { scale *= 2.0f } },
 *     onScale      = { detector, _, node -> node?.apply { scale *= detector.scaleFactor } },
 *     onMove       = { _, e, node -> node?.apply { position += ... } }
 * )
 * ```
 */
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

/**
 * Creates and remembers a [CameraGestureDetector.CameraManipulator] for orbit/pan/zoom control.
 *
 * The manipulator translates touch gestures into camera transform updates — one-finger drag to
 * orbit, two-finger drag to pan, pinch to zoom. It is updated automatically every frame inside
 * `SceneView`.
 *
 * Pass `null` to `SceneView(cameraManipulator = null)` to disable camera interaction entirely.
 *
 * ```kotlin
 * val cameraManipulator = rememberCameraManipulator(
 *     orbitHomePosition = cameraNode.worldPosition,
 *     targetPosition    = centerNode.worldPosition
 * )
 * ```
 *
 * @param orbitHomePosition Camera's world position to return to on double-tap (optional).
 * @param targetPosition    Point in world space the camera orbits around (optional).
 * @param creator           Factory for the manipulator. Override to set a custom orbit speed, etc.
 */
@Composable
fun rememberCameraManipulator(
    orbitHomePosition: Position? = null,
    targetPosition: Position? = null,
    creator: () -> CameraGestureDetector.CameraManipulator = {
        createDefaultCameraManipulator(orbitHomePosition, targetPosition)
    }
) = remember(creator)

/**
 * Creates and remembers a [ViewNode.WindowManager] required by [SceneScope.ViewNode].
 *
 * `ViewNode` renders Compose UI content onto a 3D plane by attaching an off-screen `Window`
 * to the window manager. This helper creates that window manager and destroys it on disposal.
 *
 * ```kotlin
 * val windowManager = rememberViewNodeManager()
 *
 * SceneView {
 *     ViewNode(windowManager = windowManager) {
 *         Card { Text("Hello from 3D!") }
 *     }
 * }
 * ```
 *
 * @param context Android context used to attach the off-screen window. Defaults to [LocalContext].
 * @param creator Factory for the window manager.
 * @return A [ViewNode.WindowManager] destroyed on disposal.
 */
@Composable
fun rememberViewNodeManager(
    context: Context = LocalContext.current,
    creator: () -> ViewNode.WindowManager = {
        createViewNodeManager(context)
    }
): ViewNode.WindowManager {
    val activity = context.findActivity()
    val view = LocalView.current
    val windowManager = remember(context, creator) { creator() }

    LaunchedEffect(windowManager, activity, view) {
        activity?.lifecycle?.currentStateFlow?.collect { state ->
            when (state) {
                Lifecycle.State.RESUMED -> windowManager.resume(view)
                Lifecycle.State.CREATED -> windowManager.pause()
                else -> { /* STARTED, INITIALIZED, DESTROYED handled elsewhere */ }
            }
        }
    }

    DisposableEffect(windowManager) {
        onDispose {
            windowManager.destroy()
        }
    }

    return windowManager
}

/**
 * Placeholder displayed when [SceneView] is composed inside Android Studio's `@Preview` panel
 * (i.e. `LocalInspectionMode.current == true`).
 *
 * Filament's native libraries are Android-arch only and aren't loaded by AS LayoutLib, so we
 * cannot render the actual 3D scene in the IDE's preview pane. Instead we show a labelled
 * gradient panel with a hint pointing the developer at Android Studio's Live Edit feature —
 * which DOES iterate on the live device with the real Filament renderer.
 *
 * The placeholder also makes Roborazzi snapshot tests of demos containing a [SceneView]
 * deterministic in pure JVM (Robolectric inspection mode = true) — they capture this panel
 * instead of crashing on the missing Filament JNI.
 */
@Composable
private fun ScenePreview(modifier: Modifier) {
    Box(
        modifier = modifier
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFF1F2937), // slate-800
                        androidx.compose.ui.graphics.Color(0xFF0F172A), // slate-900
                    ),
                ),
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            androidx.compose.foundation.text.BasicText(
                text = "🧊  SceneView preview",
                style = androidx.compose.ui.text.TextStyle(
                    color = androidx.compose.ui.graphics.Color(0xFFE0E7FF),
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
            )
            androidx.compose.foundation.text.BasicText(
                text = "3D rendering needs Filament JNI which AS LayoutLib does not load.\n" +
                    "Use Android Studio Live Edit on a connected device for the real scene.",
                style = androidx.compose.ui.text.TextStyle(
                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 16.sp,
                ),
            )
        }
    }
}

/**
 * @deprecated Use [SceneView] instead. This function is a direct alias provided for backward
 * compatibility with code written against earlier SceneView versions.
 */
@Deprecated("Use SceneView instead", ReplaceWith("SceneView(modifier, surfaceType, engine, modelLoader, materialLoader, environmentLoader, view, isOpaque, renderer, scene, environment, mainLightNode, cameraNode, collisionSystem, cameraManipulator, viewNodeWindowManager, onGestureListener, onTouchEvent, activity, lifecycle, onFrame, content)"))
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
    renderQuality: RenderQuality = RenderQuality.Default,
    autoCenterContent: Boolean = true,
    autoFitContent: Boolean = false,
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    fillLightNode: LightNode? = rememberFillLightNode(engine),
    cameraNode: CameraNode = rememberCameraNode(engine),
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    cameraManipulator: CameraGestureDetector.CameraManipulator? = rememberCameraManipulator(
        cameraNode.worldPosition
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
    renderQuality = renderQuality,
    autoCenterContent = autoCenterContent,
    autoFitContent = autoFitContent,
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
    content = content
)
