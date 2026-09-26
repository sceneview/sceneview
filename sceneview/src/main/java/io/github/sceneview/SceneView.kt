// File renamed Scene.kt -> SceneView.kt (the primary composable is `SceneView`; the bare `Scene`
// is a deprecated backward-compat alias). `@file:JvmName("SceneKt")` pins the published JVM facade
// class name so the rename is SOURCE-only — pre-compiled Kotlin consumers that reference the
// `SceneKt` facade in their bytecode keep working without a recompile (no binary-compat break).
@file:JvmName("SceneKt")

package io.github.sceneview

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.opengl.EGLContext
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.sceneview.gesture.CameraViewportSeed
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
import io.github.sceneview.model.model
import io.github.sceneview.utils.readBuffer
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import io.github.sceneview.utils.SurfaceMirrorer
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
 * @param frameRatePolicy       How often the scene presents. Default [FrameRatePolicy.OnDemand]:
 *                              full cadence while anything moves, a short settle tail, then the
 *                              loop parks. [FrameRatePolicy.Continuous] restores the pre-1.0
 *                              "draw every vsync" behaviour, and [FrameRatePolicy.maxFps] caps
 *                              either of them. See the parameter's own KDoc.
 * @param renderInvalidator     Escape hatch for scene changes the library cannot observe — a
 *                              Filament material or texture written directly. Use
 *                              [rememberRenderInvalidator] and call `requestRender()` after.
 * @param renderQuality         One-line preset applied to `view` ([RenderQuality.Default],
 *                              [RenderQuality.Cinematic], or [RenderQuality.Performance]).
 * @param autoCenterContent     When `true` (default), the library translates all DSL [content]
 *                              nodes once — on the first frame their union bounding box is
 *                              non-empty — so the content centroid lands on the **world origin**
 *                              and renders centred in the viewport without each node needing
 *                              `ModelNode(centerOrigin = …)`. It lands on the origin, not on the
 *                              manipulator's `targetPosition`; the two coincide only for the
 *                              default target, which is why the camera-to-subject distance is
 *                              `|orbitHomePosition|` — see [rememberCameraManipulator]. Lights /
 *                              camera are passed as separate parameters, never DSL children, so
 *                              they are unaffected. Mirrors the iOS `autoCenterContent` feature
 *                              (#1026). Pass `false` for scenes with intentional off-centre
 *                              placement — authored world positions then survive.
 * @param autoFitContent        When `true`, the library moves [cameraNode] so the DSL [content]
 *                              fills the viewport — regardless of the model's intrinsic glTF size,
 *                              with no per-model `scaleToUnits` tuning (#1439). The pass re-frames
 *                              whenever an async model grows the content's union bounds and
 *                              latches once that union has settled, then leaves the camera alone
 *                              so subsequent user zoom / pan is never fought. Default `false` so
 *                              callers that position [cameraNode] explicitly keep full control;
 *                              opt in for model-viewer style scenes.
 * @param framingPadding        Extra air the [autoFitContent] pass leaves around the content, as a
 *                              *fraction* of the fit distance — `0.15` (the default,
 *                              [DEFAULT_FRAMING_PADDING]) adds 15% of distance, `0` frames the
 *                              bounds exactly tangent. Clamped to `>= 0`. Per-scene counterpart of
 *                              `CameraNode.frameToContent(padding = …)` (#2946). Not the iOS
 *                              `framingMargin` *multiplier*: `margin == 1 + padding`, so iOS
 *                              `1.15` is `0.15` here. No effect when [autoFitContent] is `false`.
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
 * @param surfaceMirrorer       Mirrors every rendered frame to additional [android.view.Surface]s
 *                              — attach a `MediaRecorder` input surface for clean in-app video
 *                              recording (no MediaProjection). Use [rememberSurfaceMirrorer].
 * @param onGestureListener     Gesture callbacks — tap, double-tap, drag, pinch, etc.
 * @param onTouchEvent          Raw touch event callback with optional hit-test result.
 * @param activity              Host [ComponentActivity] (auto-resolved from [LocalContext]).
 * @param lifecycle             Lifecycle that drives rendering resume/pause.
 * @param onFrame               Called once per **presented** frame, right after it reached the
 *                              surface. A tick on which `Renderer.beginFrame` refused the frame —
 *                              which is most of them while the GPU warms a heavy material up — is
 *                              not a rendered frame and does not call this back (#3444), so "I was
 *                              called" is a sound signal that there are pixels on screen. Use it to
 *                              drop a loading cover or drive per-frame logic; it does not fire
 *                              while the render loop is parked on a settled scene.
 *                              **It therefore cannot be what keeps the loop awake.** A callback
 *                              that advances a clock or steps a simulation, and relies on nothing
 *                              but its own next invocation to run again, stops the first time the
 *                              scene parks — and never restarts. If your screen wants every vsync,
 *                              say so with [FrameRatePolicy.Continuous]; if it wants one more frame
 *                              after a change the library cannot see, push it through
 *                              [renderInvalidator], *before* `onFrame` rather than from inside it:
 *                              this fires after the frame it is named for was already presented, so
 *                              what you write here lands in the next one.
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
     * How often the scene presents a frame. Default [FrameRatePolicy.OnDemand].
     *
     * **The default is render-on-demand, and that is a behaviour change**: a `SceneView` no longer
     * draws every vsync for as long as it is composed. It draws at the display's full cadence
     * while something is happening — a finger on the screen, a camera fling, a playing animation,
     * a video, a model still uploading its textures — then draws a short tail of settle frames and
     * parks the loop until the next change. An idle 3D screen then costs neither GPU frames nor a
     * periodic CPU wake-up (#3108), which on the reporting foldables was most of what made it run
     * hot. The loop *suspends*; it does not spin.
     *
     * Nothing is asked of the caller for this to be correct. The library invalidates itself from
     * every change it can observe: touch and gesture streams, every [Node] transform write (which
     * covers the camera manipulators, physics and glTF animation), nodes added to or removed from
     * the DSL, recomposition of this call, surface creation and resize, and lifecycle resume. A
     * change it *cannot* observe — a parameter written straight onto a Filament
     * `MaterialInstance`, a texture swapped behind its back — is what [renderInvalidator] is for.
     *
     * Choose another policy when the scene is driven from outside the library and a late frame is
     * worse than the power it saves:
     *
     * The two questions the type asks — *when may a frame be drawn* and *how fast at most* — are
     * independent, and [FrameRatePolicy.maxFps] answers the second one on either mode:
     *
     * ```kotlin
     * // The pre-1.0 default, verbatim:
     * SceneView(frameRatePolicy = FrameRatePolicy.Continuous()) { … }
     * // A background scene drawn every vsync but held at 30 fps while the UI runs at 120:
     * SceneView(frameRatePolicy = FrameRatePolicy.Continuous(maxFps = 30)) { … }
     * // Render-on-demand, and even when it wakes, never faster than 30 fps:
     * SceneView(frameRatePolicy = FrameRatePolicy.OnDemand(maxFps = 30)) { … }
     * ```
     */
    frameRatePolicy: FrameRatePolicy = FrameRatePolicy.OnDemand(),
    /**
     * Escape hatch for scene changes [frameRatePolicy] cannot observe. A no-op under
     * [FrameRatePolicy.Continuous], where every vsync is drawn anyway.
     *
     * Everything the library owns already invalidates itself — see [frameRatePolicy]. This is for
     * the frontier where you write to Filament directly: a `MaterialInstance` parameter, a texture
     * swapped under a renderable, a light edited through its Filament entity. Under
     * [FrameRatePolicy.OnDemand] such a write changes what *would* be drawn without touching
     * anything the library watches, so on a settled scene it would sit invisible until the next
     * touch. Call [RenderInvalidator.requestRender] after it.
     *
     * A call made before the scene is attached is not lost: it is replayed on attach.
     *
     * ```kotlin
     * val invalidator = rememberRenderInvalidator()
     * SceneView(renderInvalidator = invalidator) { … }
     *
     * materialInstance.setParameter("baseColorFactor", color)
     * invalidator.requestRender()
     * ```
     */
    renderInvalidator: RenderInvalidator? = null,
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
     * non-empty — so the content centroid lands on the **world origin** and renders centred. Not
     * on the manipulator's `targetPosition`: the two coincide only for the default target, which
     * is why the camera-to-subject distance is `|orbitHomePosition|` (see
     * [rememberCameraManipulator]). Mirrors the iOS library-level `autoCenterContent` feature
     * (#1026 / PR #1038). Pass `false` to keep strict per-node placement semantics for scenes with
     * intentional off-centre composition — authored world positions then survive.
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
     * Extra air the [autoFitContent] pass leaves around the content, as a *fraction* of the fit
     * distance: `0.15` (the default, [DEFAULT_FRAMING_PADDING]) adds 15% of distance, `0` frames
     * the bounds exactly tangent. Clamped to `>= 0`. Same unit as
     * `CameraNode.frameToContent(padding = …)` — and **not** the iOS `framingMargin` multiplier
     * (`margin == 1 + padding`, so iOS `1.15` is `0.15` here) (#2946). Ignored unless
     * [autoFitContent] is `true`.
     */
    framingPadding: Float = DEFAULT_FRAMING_PADDING,
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
        orbitHomePosition = cameraNode.worldPosition
    ),
    /**
     * Used for [SceneScope.ViewNode] composables — manages the off-screen window attachment.
     * Obtain with [rememberViewNodeManager].
     */
    viewNodeWindowManager: ViewNode.WindowManager? = null,
    /**
     * Mirrors every rendered frame to additional [android.view.Surface]s — the clean way to
     * video-record the scene in-app (attach a `MediaRecorder` input surface; no MediaProjection
     * consent dialog, no foreground service, no overlay UI in the frame).
     * Obtain with [rememberSurfaceMirrorer], then call
     * [SurfaceMirrorer.startMirroring][io.github.sceneview.utils.SurfaceMirrorer.startMirroring] /
     * [SurfaceMirrorer.stopMirroring][io.github.sceneview.utils.SurfaceMirrorer.stopMirroring].
     *
     * Wiring a non-null `surfaceMirrorer` costs nothing while nothing is being mirrored: the
     * window swap chain is untouched, and each mirrored surface is rendered a second time only
     * between `startMirroring` and `stopMirroring`. Pass it unconditionally.
     */
    surfaceMirrorer: SurfaceMirrorer? = null,
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

    // ── The render-on-demand gate ────────────────────────────────────────────────────────────────
    //
    // "Does the surface still owe a frame?", in one object shared by every invalidation source. Its
    // dirty flag is snapshot state, not a plain boolean, because the parked render loop suspends on
    // it — see [awaitRenderingEnabled] and the `shouldRender` gate below (#3108, #3109). Declared
    // first, above everything that writes Filament state, because every one of those writes is an
    // invalidation source and reaches the gate from here down.
    //
    // Why it starts dirty, and why it owes a *run* of frames rather than one: a swap chain holds
    // no pixels of its own. `SceneRenderer`'s `onNativeWindowChanged` creates a brand-new one and
    // presents nothing into it, so every surface generation — first attach, app foregrounded,
    // foldable folded or unfolded, split-screen resize — starts blank, and a gate that parked
    // straight away would leave it blank (black, or transparent with `isOpaque = false`) until
    // something unrelated woke it. One frame is not enough either: Filament finalises texture
    // uploads and compiles material variants from inside the frame loop, so the first frame after
    // a change is routinely not yet the finished picture — the Materials demo needs ~4 presented
    // frames over 6.3 s before the ToyCar's clearcoat variants are warm. [SETTLE_DURATION_NANOS] is
    // that tail, and it is what makes "stops drawing" mean "stops drawing the finished picture".
    val frameRateGate = remember(engine, view, renderer) { FrameRateGate() }

    // Publish the gate to every [Node] in this scene. Nodes reach it through a registry keyed on
    // the Filament [Scene] they are attached to (see [SceneRenderInvalidators]) rather than a
    // back-reference threaded down the tree, because glTF sub-nodes are created by the loader and
    // never see the composable — `Node.attachedScene` is the one handle they all have.
    val sceneInvalidator = remember(engine, view, renderer) { RenderInvalidator() }
    DisposableEffect(frameRateGate, sceneInvalidator, scene) {
        sceneInvalidator.attach(frameRateGate)
        SceneRenderInvalidators.register(scene, sceneInvalidator)
        onDispose {
            SceneRenderInvalidators.unregister(scene)
            sceneInvalidator.detach(frameRateGate)
        }
    }
    // The caller's own escape hatch, wired to the same gate.
    DisposableEffect(frameRateGate, renderInvalidator) {
        renderInvalidator?.attach(frameRateGate)
        onDispose { renderInvalidator?.detach(frameRateGate) }
    }

    // ── Filament wiring — one keyed effect per parameter, each asking for the frame it needs ──────
    //
    // These six writes used to be a single *unkeyed* `SideEffect`, paired with an equally unkeyed
    // `SideEffect { frameRateGate.requestRender() }` a few hundred lines below. That pair is the
    // reason an idle scene stayed awake: it made "a recomposition happened" an invalidation source
    // in its own right, so any state change anywhere in the enclosing composition — a text field, a
    // slider label, a clock ticking in a corner — asked the scene for a frame whether or not
    // anything in it had moved. It also hid every genuinely missing invalidation behind itself,
    // which is why removing it is the correction and not merely an optimisation.
    //
    // The rule they now follow: **an invalidation comes from what changed, never from the fact that
    // a recomposition happened.** Each parameter that writes Filament state gets a `LaunchedEffect`
    // keyed on exactly that parameter (the pattern `renderQuality` already used since #1078), and
    // each asks for the frame its own write needs. Parameters that write nothing visible ask for
    // nothing, and are listed — with the reason — in the pull request's parameter table.
    LaunchedEffect(scene, environment) {
        scene.indirectLight = environment.indirectLight
        scene.skybox = environment.skybox
        // The first frozen image anyone would have hit: a dark/light toggle that swaps the
        // environment while the scene is parked. The IBL and skybox are replaced in Filament and
        // nothing else reports it, so without this the old sky stays on screen until something
        // unrelated wakes the loop.
        frameRateGate.requestRender()
    }
    LaunchedEffect(view, scene) {
        view.scene = scene
        frameRateGate.requestRender()
    }
    LaunchedEffect(view, cameraNode, collisionSystem) {
        view.camera = cameraNode.camera
        cameraNode.collisionSystem = collisionSystem
        cameraNode.setView(view)
        // A different camera is a different picture — and the swap happens without anyone moving
        // the new camera, so `onTransformChanged` never fires for it.
        frameRateGate.requestRender()
    }
    LaunchedEffect(view, isOpaque) {
        // Pair with `uiHelper.isOpaque` set in SceneRenderer.attachToSurfaceView/
        // TextureView (#1077). Without this, the fragment pipeline blends opaque
        // even when the swap chain is CONFIG_TRANSPARENT — nothing under the
        // SceneView shows through.
        view.blendMode = if (isOpaque) BlendMode.OPAQUE else BlendMode.TRANSLUCENT
        frameRateGate.requestRender()
    }
    // Keyed `LaunchedEffect` so the preset is reapplied ONLY when `renderQuality`
    // actually changes (#1078). The previous unkeyed `SideEffect` ran on every
    // recomposition and silently overwrote any post-Scene `view.colorGrading`,
    // `view.bloomOptions.strength`, etc. tweaks — breaking the contract documented
    // at `RenderQuality.kt`'s "Apply additional View tweaks AFTER calling this —
    // they will not be undone".
    LaunchedEffect(view, renderQuality) {
        view.applyRenderQuality(renderQuality)
        // MSAA, FXAA, bloom, dynamic resolution: the same scene renders differently from now on.
        frameRateGate.requestRender()
    }

    // Force a per-frame color-buffer clear so stale renderable pixels never survive
    // a scene change (#2400). Filament defaults to `Renderer.ClearOptions.clear =
    // false` and relies on the skybox to repaint the background every frame. A
    // SceneView whose environment has NO skybox (the model-viewer / gallery demos
    // use `createSkybox = false` so the model floats on the surface background) then
    // never clears the swap chain: when the rendered footprint SHRINKS — swapping a
    // large model for a smaller one in a single slot — the previously-rendered
    // pixels the new model does not cover are left on screen, so the old model
    // appears "stacked" behind the new one even though its renderable entities were
    // correctly removed from the Scene (verified on device: `Scene.getRenderableCount()`
    // drops to the new model's count, yet the old pixels linger). Clearing every
    // frame fixes it for both opaque (clear to opaque black) and translucent (clear
    // to transparent so the surface background shows through) views; when a skybox
    // IS present it simply overdraws the clear, so this is a no-op for skybox scenes.
    //
    // Keyed on (renderer, isOpaque) — the only inputs the clear depends on — so it
    // applies once per change instead of allocating a `ClearOptions` + issuing a JNI
    // `setClearOptions` on every recomposition (mirrors the `renderQuality` effect
    // above rather than the per-recomposition `view.*` SideEffect).
    LaunchedEffect(renderer, isOpaque) {
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, if (isOpaque) 1.0 else 0.0)
        }
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
        frameRateGate.requestRender()
        onDispose {
            nodeManager.removeNode(cameraNode)
            frameRateGate.requestRender()
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
        // Adding or removing the sun relights every surface in the scene, and neither the node nor
        // Filament reports it — `addNode` moves no transform, so `onTransformChanged` never fires.
        frameRateGate.requestRender()
        onDispose {
            mainLightNode?.let { nodeManager.removeNode(it) }
            frameRateGate.requestRender()
        }
    }

    // ── Fill light node ───────────────────────────────────────────────────────────────────────────

    DisposableEffect(fillLightNode) {
        fillLightNode?.let { nodeManager.addNode(it) }
        frameRateGate.requestRender()
        onDispose {
            fillLightNode?.let { nodeManager.removeNode(it) }
            frameRateGate.requestRender()
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
        // Turning centring on or off re-parents the whole content subtree and re-arms the framing
        // pass; either way the content is about to be somewhere else than it is on screen.
        autoCenterState.reset()
        frameRateGate.requestRender()
        onDispose {
            if (autoCenterContent) {
                nodeManager.removeNode(contentRoot)
            }
        }
    }

    // Enabling auto-fit at runtime has to wake the loop too: the fit pass only runs from inside a
    // presented frame, so a parked scene would keep the old framing until something else woke it.
    LaunchedEffect(autoFitContent, framingPadding) {
        autoFitState.reset()
        frameRateGate.requestRender()
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
            // A node was attached to (or detached from) the live scene — invalidate the same way
            // a surface resize does (#3560). Under [FrameRatePolicy.OnDemand] the frame loop below
            // parks on `shouldRender`, and only the gate can wake it. Before #3560 only
            // `onSurfaceResized`/`onSurfaceReady` did, so a node added to a parked scene loaded
            // and reported bounds but was never drawn until an unrelated resize (even 1px)
            // incidentally woke the loop — recomposition alone never reached `withFrameNanos`.
            frameRateGate.requestRender()
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
                // Coming back to the foreground is a push invalidation, and it has to be written
                // here: `isResumed` is an `AtomicBoolean`, deliberately — it is polled from the
                // loop's inner wait and must not recompose the whole call sixty times a second.
                // So nothing about the resume reaches the gate on its own. The blanket
                // `SideEffect` used to cover this by accident (the activity usually recomposes on
                // resume) and the surface usually gets re-created on top, which is why it never
                // showed; on the paths where neither happens — a dialog dismissed over a still
                // -attached surface — the scene stayed parked on a stale frame.
                frameRateGate.requestRender()
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
    // Timestamp of the last frame that actually reached the surface — the phase reference for
    // [FrameRatePolicy.maxFps]. `0L` means "none yet". Distinct from `lastFrameTimeNanosRef`,
    // which tracks every *tick* and feeds the manipulator's delta.
    val lastPresentNanosRef = remember { AtomicLong(0L) }
    val gestureDetector = remember(context) { GestureDetector(context = context, listener = null) }
    val cameraGestureDetectorRef = remember { AtomicReference<CameraGestureDetector?>(null) }

    // Node that consumed the current gesture's ACTION_DOWN, and therefore owns the whole stream
    // until it ends (#2845). See `touchDispatcher` below.
    val capturedTouchNodeRef = remember { AtomicReference<Node?>(null) }

    // Last surface size pushed to the manipulator, packed as (width.toLong() shl 32) or height.
    // `0L` means "not sized yet". A manipulator's pan math is only correct once it has the real
    // viewport (Filament's ORBIT pan divides screen pixels by the viewport: a stale 1×1 viewport
    // explodes the pan delta by ~3 orders of magnitude, throwing the model off-screen on the
    // slightest two-finger drag — #2514). The surface is sized once, but the manipulator can be
    // swapped at runtime (e.g. the demo Explore viewer rebuilds it on auto-fit after the model
    // loads). `onSurfaceResized` won't re-fire for a same-size surface, so a freshly-swapped
    // manipulator would never receive a `setViewport`. We cache the last size here and re-apply
    // it whenever the manipulator instance changes.
    val lastViewportRef = remember { AtomicLong(CameraViewportSeed.UNSET) }
    // The manipulator instance the cached viewport was last applied to. Used to re-seed only when
    // the manipulator actually changes, so the per-recomposition SideEffect doesn't make a
    // redundant JNI setViewport call every frame.
    val seededManipulatorRef = remember { AtomicReference<CameraGestureDetector.CameraManipulator?>(null) }

    // True while the live touch stream is absorbed by an editable node. Double-tap recognition
    // happens inside `gestureDetector`, which — unlike the camera detector — is fed even for
    // editable nodes, so the built-in zoom needs this flag to honour the same gesture isolation
    // the rest of the camera gestures get below (#3608).
    val cameraGesturesAbsorbedRef = remember { AtomicBoolean(false) }

    // True between ACTION_DOWN and ACTION_UP/ACTION_CANCEL. A *pull* source for the frame gate
    // (see [isSceneFrameActive]): a finger held still on the screen sends no MotionEvent at all,
    // so the per-event `requestRender()` below would let the scene settle under a motionless
    // finger and then have to wake on the next move — visible as a hitch at the start of a drag.
    val gestureInFlightRef = remember { AtomicBoolean(false) }

    SideEffect {
        gestureDetector.listener = onGestureListener
        gestureDetector.onDoubleTapCamera = { event ->
            if (!cameraGesturesAbsorbedRef.get()) {
                cameraGestureDetectorRef.get()?.onDoubleTap(event)
            }
        }
        cameraGestureDetectorRef.get()?.cameraManipulator = cameraManipulator
        // Re-seed the (newly-swapped) manipulator with the current viewport so its pan/raycast
        // math is correct from the first gesture, without waiting for a surface resize. Only when
        // the instance changed — the surface-resize path keeps the same instance in sync.
        if (cameraManipulator !== seededManipulatorRef.get()) {
            if (CameraViewportSeed.seed(cameraManipulator, lastViewportRef.get())) {
                seededManipulatorRef.set(cameraManipulator)
            }
        }
    }

    // A manipulator swapped at runtime (the Explore viewer rebuilds one after the model loads)
    // holds its own camera transform, and the loop only reads it from inside a frame — so a parked
    // scene would keep the old framing until a touch woke it. Keyed on the instance, not folded
    // into the `SideEffect` above: that block runs on every recomposition by design (it rewires
    // callbacks that are re-created each time), and asking for a frame from inside it would be the
    // removed blanket `SideEffect` under another name.
    LaunchedEffect(cameraManipulator) { frameRateGate.requestRender() }

    // Common touch dispatcher — wired to both SurfaceView and TextureView via SceneRenderer.
    //
    // Gesture isolation: when the touch lands on an editable node, the camera gesture
    // detector is skipped so the gesture is fully absorbed by the node. Without this,
    // dragging/twisting/pinching an editable helmet would also orbit/pan/zoom the camera
    // simultaneously (because both detectors received every event), making per-node
    // editing feel "leaky" and unresponsive.
    val touchDispatcher: (MotionEvent) -> Unit = { event ->
        val hitResult = collisionSystem.hitTest(event).firstOrNull { it.node.isTouchable }
        val hitNode = hitResult?.node
        // Touch-target capture (#2845): the node that consumed the ACTION_DOWN keeps the rest of
        // the stream, even the events whose ray misses it. Without this, a press dragged off an
        // interactive ViewNode would never receive its UP and would stay stuck pressed. Delivery
        // only — `Node.onCapturedTouchEvent` defaults to `false`, so a node that does not opt in
        // falls through to the untouched path below.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A new gesture supersedes a stream still held by an earlier one (it ended off-node,
            // so no UP ever reached its owner). Let the old owner release its press, but never
            // let it consume this DOWN: the new gesture belongs to whatever it hits now.
            capturedTouchNodeRef.getAndSet(null)?.takeIf { it !== hitNode }?.onCapturedTouchEvent(event)
        }
        val capturedNode = capturedTouchNodeRef.get()?.takeIf { it !== hitNode }
        var consumedByNode = false
        // The raw callback keeps its absolute priority, capture or not.
        if (onTouchEvent?.invoke(event, hitResult) != true) {
            consumedByNode = capturedNode?.onCapturedTouchEvent(event) == true ||
                    (hitResult != null && hitNode?.onTouchEvent(event, hitResult) == true)
            if (!consumedByNode) {
                // Skip the camera detector when the touch is on an editable node — the node
                // owns the gesture. We check the hit node's master `isEditable` (and not the
                // per-axis flags) so a node that's "editable but with all axes locked" still
                // absorbs the touch — locking an axis should freeze the node, not divert the
                // gesture to the camera (that would surprise the user).
                val absorbedByEditableNode = hitNode?.isEditable == true
                // Published before `gestureDetector` runs: its double-tap callback fires from
                // inside that call and reads this to apply the same isolation (#3608).
                cameraGesturesAbsorbedRef.set(absorbedByEditableNode)
                gestureDetector.onTouchEvent(event, hitResult)
                if (!absorbedByEditableNode) {
                    cameraGestureDetectorRef.get()?.onTouchEvent(event)
                }
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                capturedTouchNodeRef.set(hitNode.takeIf { consumedByNode })
                gestureInFlightRef.set(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                capturedTouchNodeRef.set(null)
                gestureInFlightRef.set(false)
            }
        }
        // Every touch event invalidates, whatever it turned out to hit: the camera detector may
        // have started a fling, a node may have moved, a listener may have changed a material.
        // This is the cheapest possible over-approximation and it is the right one — a touch the
        // user made is never a frame worth saving.
        frameRateGate.requestRender()
    }

    // ── SceneRenderer — encapsulates surface lifecycle + swap chain + frame pipeline ─────────────

    @Suppress("DEPRECATION")
    val display = remember(context) {
        (context.getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
    }

    val sceneRenderer = remember(engine, view, renderer) {
        SceneRenderer(engine, view, renderer)
    }

    // `frameRateGate` (the render-on-demand gate) is declared at the top of this function, above
    // everything that writes Filament state — see its doc comment there (#3108, #3560).

    // A mirrorer is a *pull* source (`isMirroring` in [isSceneFrameActive]), and a pull source is
    // only read from inside a frame. Attaching one to a parked scene therefore has to wake it once,
    // or the recording starts on a scene that is not drawing and never will.
    LaunchedEffect(surfaceMirrorer) { frameRateGate.requestRender() }

    // Wire resize and surface callbacks.
    SideEffect {
        sceneRenderer.surfaceMirrorer = surfaceMirrorer
        sceneRenderer.onSurfaceResized = { width, height ->
            // Remember the size so a manipulator swapped in later (without a surface resize)
            // can inherit it — see [lastViewportRef] (#2514).
            lastViewportRef.set(CameraViewportSeed.packViewport(width, height))
            cameraManipulator?.setViewport(width, height)
            cameraNode.updateProjection()
            // The viewport just changed: whatever was on screen is the wrong size, and a parked
            // loop would keep it. Both callbacks run on the main thread (UiHelper), so writing
            // snapshot state here is safe and wakes the park through the normal apply path.
            frameRateGate.requestRender()
        }
        sceneRenderer.onSurfaceReady = { viewHeight ->
            frameRateGate.requestRender()
            if (cameraGestureDetectorRef.get() == null) {
                cameraGestureDetectorRef.set(
                    CameraGestureDetector(
                        viewHeight = viewHeight,
                        cameraManipulator = cameraManipulator
                    ).apply {
                        // The same slop `gestureDetector` uses to tell a tap from a scroll, so a
                        // tap never starts an orbit that cancels its own double-tap zoom (#3641).
                        orbitTouchSlop =
                            ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    }
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
    // Read through a state ref so swapping `frameRatePolicy` at runtime is picked up by the
    // frame loop without restarting it.
    val currentFrameRatePolicy = rememberUpdatedState(frameRatePolicy)
    // Read through a state ref so toggling `autoCenterContent` at runtime is picked up by the
    // frame loop without restarting it (the loop's LaunchedEffect is keyed on engine/renderer/
    // view/scene only).
    val currentAutoCenterContent = rememberUpdatedState(autoCenterContent)
    // Same for `autoFitContent` — the auto-fit pass is read through this ref so toggling the
    // parameter at runtime is picked up by the frame loop without restarting it.
    val currentAutoFitContent = rememberUpdatedState(autoFitContent)
    // And `framingPadding` — a per-scene change re-arms the pass so the new air is applied
    // instead of staying latched on the previous framing.
    val currentFramingPadding = rememberUpdatedState(framingPadding)
    // The `reset()` and the wake-up for both of these live in one keyed effect next to the
    // auto-fit state above.

    // The loop's wake condition: the policy draws unconditionally, *or* something invalidated the
    // gate. Derived state so the park below observes both through a single snapshot read.
    //
    // Only the gate's *dirty* flag is snapshot state; its settle debt is a plain counter, read
    // directly at the park site below. That split is deliberate. The debt is written once per
    // frame from inside the loop, so making it observable would apply a snapshot 60 times a second
    // on every rendering scene — the exact per-frame cost this whole change exists to remove —
    // and it would buy nothing, because the loop is by definition awake while it owes frames.
    // Only the wake-up needs to be observable, and that is the dirty flag.
    val shouldRender = remember(currentFrameRatePolicy, frameRateGate) {
        derivedStateOf {
            currentFrameRatePolicy.value !is FrameRatePolicy.OnDemand || frameRateGate.isDirty
        }
    }

    LaunchedEffect(engine, renderer, view, scene) {
        while (true) {
            when {
                // Not resumed — poll the lifecycle flag the DisposableEffect below flips, and
                // stop asking the panel for a cadence first. A paused view that is still attached
                // never reaches `onDetachedFromSurface`, so without this its last vote — the
                // display maximum, if it paused while something was moving — stays standing for as
                // long as the app is in the background. `setFrameRateVote` deduplicates, so the
                // repeat at 10 Hz costs nothing after the first pass.
                !isResumed.get() -> {
                    sceneRenderer.setFrameRateVote(0f)
                    delay(100)
                }

                // Rendering paused and the surface is not owed a frame — park, don't poll. A
                // `delay(16)` spin would stop the GPU work and still wake the CPU ~60x/s on a
                // scene that is idle by definition: on the Samsung foldables in #3108 that wake-up
                // is a measurable share of the drain this parameter exists to remove, and it
                // survives even with the GPU work gone. Suspending on the snapshot instead lets
                // the thread idle until a recomposition flips the flag back — or until a new
                // surface arrives and sets the debt. Both gates re-enter the loop from the top, so
                // a park that lasts minutes still re-reads the lifecycle before the next frame.
                !shouldRender.value && frameRateGate.isSettled -> {
                    // Don't hand the first frame after a long park a delta covering the whole
                    // park: `manipulator.update()` reads this as elapsed time. Zero means "no
                    // previous frame", which is what a resumed loop actually has.
                    lastFrameTimeNanosRef.set(0L)
                    awaitRenderingEnabled(shouldRender)
                }

                else -> withFrameNanos { frameTimeNanos ->
                    val policy = currentFrameRatePolicy.value
                    var cameraMoved = false
                    var cameraPending = false
                    val presented = sceneRenderer.renderFrame(
                        frameTimeNanos,
                        // Evaluated *after* the update block below has run, so every pull source
                        // reports post-tick state: an animation that ended on this very tick is
                        // already inactive, and the settle tail starts from here rather than one
                        // frame late. Gates the GPU submit only — `renderFrame` still drains the
                        // engine destroy queue when it returns false, and the update block above
                        // it always runs, so loads, node ticks and the manipulator keep advancing
                        // whether or not the frame reaches the screen.
                        shouldPresent = {
                            val active = isSceneFrameActive(
                                gestureInFlight = gestureInFlightRef.get(),
                                cameraMoved = cameraMoved,
                                cameraPending = cameraPending,
                                hasActiveNode = childNodesRef.get().any { it.isFrameActive },
                                // Not `progress < 1f`: Filament reports 0 for a loader that was
                                // never asked for an async load, which read as "loading" for the
                                // lifetime of every procedural scene. See [isAsyncLoadPending].
                                isLoading = modelLoader.isLoading,
                                isMirroring = surfaceMirrorer?.mirroredSurfaces?.isNotEmpty() == true,
                                // Exactly the condition of the work this guard waits for — the
                                // same `if`s the update block above runs the passes under. See
                                // [isFramingPending] for the two ways a looser guard held every
                                // default scene at full cadence with no visible symptom.
                                framingPending = isFramingPending(
                                    autoCenterContent = currentAutoCenterContent.value,
                                    autoCenterPending = autoCenterState.isFramingPending,
                                    autoFitContent = currentAutoFitContent.value,
                                    hasCameraManipulator =
                                        currentCameraManipulator.value != null,
                                    autoFitPending = autoFitState.isFramingPending
                                )
                            )
                            // The vote is a hint to the display, not a gate on this frame: it asks
                            // the panel for the cadence the next few frames will want. Recomputed
                            // every frame but only pushed across JNI on a change — see
                            // [SceneRenderer.setFrameRateVote].
                            sceneRenderer.setFrameRateVote(
                                frameRateVote(policy, active, sceneRenderer.maxRefreshRate)
                            )
                            // Both questions of [FrameRatePolicy] — the mode and the optional cap —
                            // in the order that keeps them independent. See [shouldPresentFrame].
                            shouldPresentFrame(
                                policy = policy,
                                frameTimeNanos = frameTimeNanos,
                                lastPresentNanos = lastPresentNanosRef.get(),
                                // The panel's *current* mode, not a constant and not its
                                // ceiling: the cap can only be met on whole vsyncs, and on a
                                // VRR panel which vsyncs those are changes under us.
                                vsyncPeriodNanos = vsyncPeriodNanos(sceneRenderer.refreshRate)
                            ) { frameRateGate.shouldRender(active, frameTimeNanos) }
                        }
                    ) {
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
                                autoFitState.maybeFit(
                                    cameraNode, contentRoot, padding = currentFramingPadding.value
                                )
                            } else {
                                autoFitState.maybeFit(
                                    cameraNode, childNodesRef.get(),
                                    padding = currentFramingPadding.value
                                )
                            }
                        }

                        currentCameraManipulator.value?.let { manipulator ->
                            val lastTime = lastFrameTimeNanosRef.get().takeIf { it != 0L }
                            manipulator.update(frameTimeNanos.intervalSeconds(lastTime).toFloat())
                            val transform = manipulator.getTransform()
                            // A fling sends no MotionEvent while it decelerates, and Filament's
                            // manipulator exposes no "is still moving". Comparing the transform it
                            // produces against the previous frame's is the only signal there is —
                            // and it is exact: the fling ends precisely when the camera stops
                            // moving. `cameraNode.transform` also invalidates the gate through
                            // `Node.onTransformChanged`, but that fires one frame *after* the
                            // motion it reports, so the settle tail is what keeps the last frame
                            // of a fling on screen rather than a frame behind.
                            cameraMoved = transform != cameraNode.transform
                            // The manipulator's own answer for what motion cannot show: an ease in
                            // flight, or a turntable counting down to take the camera back three
                            // seconds after the last gesture. That countdown advances from
                            // `update()` — which only runs while the loop runs — so without this
                            // the scene would settle at ~0.5 s, park, and the automatic orbit
                            // would never resume.
                            cameraPending = manipulator.isFrameActive
                            // Write only on a real change. `Node.transform`'s setter is itself a
                            // *push* invalidation source (`onTransformChanged` → `requestRender`),
                            // so re-writing an identical matrix every tick re-dirties the gate
                            // from inside the render loop: the gate re-arms its whole settle
                            // budget on the next tick, which writes again, forever. No scene
                            // holding a camera manipulator — i.e. the default scene — could
                            // settle, and render-on-demand degraded to render-always everywhere
                            // while every unit test still passed, because the loop that closes
                            // the cycle is the one thing they cannot run. Measured on
                            // emulator-5554 before this line: 1201 frames presented over 20.02 s
                            // of a scene nobody touched, on a screen whose turntable was paused
                            // (`dumpsys SurfaceFlinger --latency` on the Filament BLAST layer).
                            // Skipping the write also spares a `setTransform` JNI call and three
                            // decompositions per frame on a camera that is standing still.
                            if (cameraMoved) {
                                cameraNode.transform = transform
                            }
                        }
                    }

                    // `onFrame` fires only for a frame that actually reached the surface (#3444).
                    // Everything above runs on every tick — `updateLoad`, the node ticks, the
                    // framing passes and the manipulator must keep advancing or a stalled surface
                    // would never recover — but the CALLER's callback means "a frame was
                    // rendered", and `Renderer.beginFrame` refuses frames while the GPU is behind.
                    // On emulator-5554 the Materials demo presents 4 frames in its first 6.3 s
                    // (Filament warming the ToyCar's clearcoat / sheen / transmission variants)
                    // and then runs at 60 fps; firing `onFrame` on the refused attempts told the
                    // demo scaffold the scene was up while the surface was still black, so it
                    // dropped its loading cover and every capture in that window — the Maestro
                    // screenshot included — recorded a blank viewport.
                    if (presented) {
                        currentOnFrame.value?.invoke(frameTimeNanos)
                    }

                    // Pay down the settle debt only if a frame was really presented. `renderFrame`
                    // returns early when no swap chain is ready, and `Renderer.beginFrame` can
                    // refuse a frame for pacing — settling on the *attempt* would park with a
                    // blank surface, which is the bug this guards (#3109).
                    if (presented) {
                        frameRateGate.didRender(frameTimeNanos)
                        lastPresentNanosRef.set(frameTimeNanos)
                    }

                    lastFrameTimeNanosRef.set(frameTimeNanos)
                }
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
 * **Lifecycle & ownership.** This composable owns the [ModelInstance] it produces (and the backing
 * glTF [Model] — a bundle of Filament textures, vertex/index buffers and materials). When
 * [assetFileLocation] changes, the previously produced instance's `Model` is destroyed
 * (`modelLoader.destroyModel(it.model)`) and the new asset is loaded; the old `Model` is also
 * destroyed when this composable leaves the composition. Disposal order relative to a consuming
 * [SceneScope.ModelNode] is **not** guaranteed: Compose forgets effects in reverse registration
 * order only within one composition, and a node declared in a child composition (a
 * `SubcomposeLayout` slot such as Material3's `Scaffold`, the common case) may detach *after* the
 * `Model` is destroyed. Either order is safe — `Node.destroy()` only touches entity ids and
 * `destroyModel` tolerates already-freed assets — so the renderables are never left dangling.
 * A key change right after the load returns, while the textures are still decoding, is safe too:
 * `destroyModel` cancels that model's pending texture load before freeing it.
 * Only a model that finished loading is disposed here: a load cancelled by a key change after
 * `ModelLoader` registered the `Model` but before it was produced stays resident until the
 * loader is cleared. The [ModelLoader]
 * does **not** dedupe by path — each call creates a fresh, independent `Model`; re-loading the same
 * path is a new GPU allocation, not a cache hit. For a model you manage imperatively (outside this
 * composable's keyed lifecycle), use [ModelLoader.loadModelInstanceAsync] and call
 * [ModelLoader.destroyModel] yourself.
 *
 * @param modelLoader       The [ModelLoader] to use.
 * @param assetFileLocation Path to the GLB/glTF file relative to the `assets` folder.
 * @return                  `null` while the first load is in progress; the loaded
 *                         [ModelInstance] once ready. When [assetFileLocation] changes,
 *                         the previous value is kept until the new one is ready —
 *                         `produceState` retains its last value across key changes and
 *                         only its producer coroutine is restarted — so callers that
 *                         show a loading state on `null` must wrap the call in
 *                         `key(location) { rememberModelInstance(...) }` to observe
 *                         `null` during a switch (see #3900).
 */
@Composable
fun rememberModelInstance(
    modelLoader: ModelLoader,
    assetFileLocation: String
): ModelInstance? {
    val context = LocalContext.current
    val instance = produceState<ModelInstance?>(
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
    // `produceState` only cancels the producer coroutine on a key change — it never destroys the
    // previously produced [ModelInstance]/[Model], which otherwise stays in `ModelLoader.models`
    // (GPU-resident) until the whole loader is torn down (#2459). Keying a [DisposableEffect] on the
    // produced value fires `onDispose` for the *previous* instance on a key swap and on
    // leave-composition. No ordering with the consuming `ModelNode`'s `NodeLifecycle.onDispose` is
    // relied on: reverse-registration forgetting holds within ONE composition, and the node usually
    // lives in a child one (a `SubcomposeLayout` slot), so it may detach after `destroyModel` ran.
    // Safe either way — `Node.destroy()` is entity-id arithmetic and `safeDestroyModel` is
    // `runCatching`-guarded (#2954). `onDispose` runs on the composition (main) thread, satisfying
    // the Filament JNI contract.
    DisposableEffect(instance) {
        onDispose { instance?.let { modelLoader.destroyModel(it.model) } }
    }
    return instance
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
 * **Lifecycle & ownership.** Like the asset overload, this composable owns the produced
 * [ModelInstance] and its backing [Model]: the previous model is destroyed when [fileLocation]
 * changes and on leave-composition, in no guaranteed order relative to a consuming
 * [SceneScope.ModelNode] — safe either way. The [ModelLoader] does not dedupe by path — each
 * distinct [fileLocation] is a fresh
 * GPU allocation. See the asset-path overload for details.
 *
 * @param modelLoader  The [ModelLoader] to use.
 * @param fileLocation Path, URI, or URL to the GLB/glTF file.
 * @return             `null` while the first load is in progress; the loaded
 *                    [ModelInstance] once ready. When [fileLocation] changes, the previous
 *                    value is kept until the new one is ready (see the asset-path overload:
 *                    `produceState` retains its last value across key changes), so callers
 *                    that show a loading state on `null` must wrap the call in
 *                    `key(location) { rememberModelInstance(...) }` to observe `null`
 *                    during a switch (see #3900).
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
    // Fast path: plain asset file name (no scheme) → use synchronous asset reader. The delegate
    // owns its own per-key disposal, so no extra DisposableEffect is added on this branch.
    if (uri.scheme == null) {
        return rememberModelInstance(modelLoader, assetFileLocation = fileLocation)
    }
    // URL / file URI / content URI → use suspend loadModelInstance which handles http(s)
    val instance = produceState<ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = fileLocation
    ) {
        value = runCatching {
            modelLoader.loadModelInstance(fileLocation, resourceResolver)
        }.getOrNull()
    }.value
    // See the asset-path overload: `produceState` skips per-key disposal, so destroy the previous
    // [Model] on a key swap and on leave-composition (#2459). Disposal is ordered after the
    // consuming `ModelNode`'s detach, respecting #2424's render-loop coupling.
    DisposableEffect(instance) {
        onDispose { instance?.let { modelLoader.destroyModel(it.model) } }
    }
    return instance
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
 * leaves the tree — right away when its backend is idle, otherwise as soon as the backend has
 * drained the work already queued (a new scene's shader compiles, say), without blocking the main
 * thread on that drain.
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
            // Not `safeDestroy()` inline: Engine.destroy() joins the backend thread after it has
            // run every queued command, and a scene disposed right after it appeared still has
            // its shader compiles queued — seconds on the main thread, an ANR (#3799). Destroy
            // once the backend is idle instead, polled from the main looper; the usual idle case
            // still destroys before onDispose returns.
            engine.destroyWhenBackendIdle { eglContext.destroy() }
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
 * camera-stream shader's `Inverse_Tonemap_Filmic` needs in order to round-trip back to the
 * original camera pixels.
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
 * `beginFrame`, `render`, `endFrame`. One per window is recommended. Destroyed on disposal —
 * right away when the backend is idle, otherwise as soon as it has drained the work already
 * queued, without blocking the main thread on that drain.
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
            // Not `safeDestroyRenderer()` inline: Filament's renderer teardown waits for every
            // queued backend command, and an activity destroyed right after a scene appeared still
            // has its shader programs linking — seconds on the main thread, an ANR (#3799).
            engine.destroyRendererWhenBackendIdle(renderer)
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
            // Not `destroy()` inline: the IBL prefilter's context destroys its own Renderer, whose
            // teardown waits for every queued backend command — an ANR when the scene is left
            // right after it appeared (#3885). The engine outlives the deferred release.
            environmentLoader.destroyWhenBackendIdle()
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
    //
    // The re-apply writes straight to Filament's `LightManager`, which reports nothing, so under
    // [FrameRatePolicy.OnDemand] it has to ask for its own frame — a relit scene that is parked
    // keeps the old lighting on screen. It asks only when the block itself changed: Compose hands
    // back a *new* lambda instance exactly when the values it captures change, so a constant block
    // (`rememberMainLightNode(engine)` with no arguments, the common case) never invalidates and a
    // block reading an animated intensity invalidates once per value.
    val lastApply = remember { AtomicReference<(LightNode.() -> Unit)?>(null) }
    SideEffect {
        node.apply(apply)
        if (lastApply.getAndSet(apply) !== apply) {
            node.requestRender()
        }
    }
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
    //
    // The re-apply writes straight to Filament's `LightManager`, which reports nothing, so under
    // [FrameRatePolicy.OnDemand] it has to ask for its own frame — a relit scene that is parked
    // keeps the old lighting on screen. It asks only when the block itself changed: Compose hands
    // back a *new* lambda instance exactly when the values it captures change, so a constant block
    // (`rememberMainLightNode(engine)` with no arguments, the common case) never invalidates and a
    // block reading an animated intensity invalidates once per value.
    val lastApply = remember { AtomicReference<(LightNode.() -> Unit)?>(null) }
    SideEffect {
        node.apply(apply)
        if (lastApply.getAndSet(apply) !== apply) {
            node.requestRender()
        }
    }
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
 * The [environment] factory lambda is captured as a stable key by Compose, so a factory that
 * closes over a changing value (e.g. an HDR path that depends on a time-of-day slider) is **not**
 * re-run on its own. Pass that changing value as [key] so the [Environment] is rebuilt — and the
 * old one disposed — whenever the key changes:
 * ```kotlin
 * val environment = rememberEnvironment(environmentLoader, key = hdrPath) {
 *     environmentLoader.createHDREnvironment(hdrPath)!!
 * }
 * ```
 *
 * @param environmentLoader The loader that produced the IBL textures.
 * @param isOpaque          If `false`, the skybox is cleared so the surface background shows through.
 * @param key               Extra memoisation key. When it changes the [Environment] is rebuilt and
 *                          the previous one destroyed. Pass the value the [environment] factory
 *                          depends on (e.g. the HDR file path); leave `null` for a static environment.
 * @param environment       Factory that produces the [Environment]. Memoised by the loader + opacity + [key].
 * @return An [Environment] destroyed on disposal.
 */
@Composable
fun rememberEnvironment(
    environmentLoader: EnvironmentLoader,
    isOpaque: Boolean = true,
    key: Any? = null,
    environment: () -> Environment = {
        createEnvironment(environmentLoader, isOpaque)
    }
) = remember(environmentLoader, isOpaque, key, environment).also {
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
 * @param key         Extra memoisation key. When it changes the [Environment] is rebuilt and the
 *                    previous one destroyed. Pass the value the [environment] factory depends on;
 *                    leave `null` for a static environment.
 * @param environment Factory that produces the [Environment]. Memoised by the engine + opacity + [key].
 * @return An [Environment] destroyed on disposal.
 */
@Composable
fun rememberEnvironment(
    engine: Engine,
    isOpaque: Boolean = true,
    key: Any? = null,
    environment: () -> Environment = {
        createEnvironment(engine, isOpaque)
    }
) = remember(engine, isOpaque, key, environment).also {
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
    creator: (() -> GestureDetector.OnGestureListener)? = null
): GestureDetector.OnGestureListener {
    // A custom `creator` is the explicit escape hatch — honour it verbatim (the caller owns the
    // listener instance and its capture semantics).
    if (creator != null) return remember(creator)

    // Default path: the listener instance must stay stable across recompositions (the
    // GestureDetector keeps a single reference), but the callbacks must NOT be frozen at first
    // composition. The historical `remember(creator)` captured every lambda once, so any caller
    // whose callback closed over a derived `val` got permanently-stale behaviour with no warning
    // (#2506 / #2476). Route each callback through `rememberUpdatedState` and let the remembered
    // object read the always-current snapshot — the idiomatic Compose pattern.
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnShowPress by rememberUpdatedState(onShowPress)
    val currentOnSingleTapUp by rememberUpdatedState(onSingleTapUp)
    val currentOnScroll by rememberUpdatedState(onScroll)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnFling by rememberUpdatedState(onFling)
    val currentOnSingleTapConfirmed by rememberUpdatedState(onSingleTapConfirmed)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnDoubleTapEvent by rememberUpdatedState(onDoubleTapEvent)
    val currentOnContextClick by rememberUpdatedState(onContextClick)
    val currentOnMoveBegin by rememberUpdatedState(onMoveBegin)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnMoveEnd by rememberUpdatedState(onMoveEnd)
    val currentOnRotateBegin by rememberUpdatedState(onRotateBegin)
    val currentOnRotate by rememberUpdatedState(onRotate)
    val currentOnRotateEnd by rememberUpdatedState(onRotateEnd)
    val currentOnScaleBegin by rememberUpdatedState(onScaleBegin)
    val currentOnScale by rememberUpdatedState(onScale)
    val currentOnScaleEnd by rememberUpdatedState(onScaleEnd)
    return remember {
        object : GestureDetector.OnGestureListener {
            override fun onDown(e: MotionEvent, node: Node?) = currentOnDown(e, node)
            override fun onShowPress(e: MotionEvent, node: Node?) = currentOnShowPress(e, node)
            override fun onSingleTapUp(e: MotionEvent, node: Node?) = currentOnSingleTapUp(e, node)
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                node: Node?,
                distance: Float2
            ) = currentOnScroll(e1, e2, node, distance)

            override fun onLongPress(e: MotionEvent, node: Node?) = currentOnLongPress(e, node)
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2) =
                currentOnFling(e1, e2, node, velocity)

            override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) =
                currentOnSingleTapConfirmed(e, node)

            override fun onDoubleTap(e: MotionEvent, node: Node?) = currentOnDoubleTap(e, node)
            override fun onDoubleTapEvent(e: MotionEvent, node: Node?) =
                currentOnDoubleTapEvent(e, node)

            override fun onContextClick(e: MotionEvent, node: Node?) = currentOnContextClick(e, node)
            override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                currentOnMoveBegin(detector, e, node)

            override fun onMove(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                currentOnMove(detector, e, node)

            override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
                currentOnMoveEnd(detector, e, node)

            override fun onRotateBegin(
                detector: RotateGestureDetector,
                e: MotionEvent,
                node: Node?
            ) = currentOnRotateBegin(detector, e, node)

            override fun onRotate(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
                currentOnRotate(detector, e, node)

            override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
                currentOnRotateEnd(detector, e, node)

            override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                currentOnScaleBegin(detector, e, node)

            override fun onScale(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                currentOnScale(detector, e, node)

            override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
                currentOnScaleEnd(detector, e, node)
        }
    }
}

/**
 * Creates and remembers a [CameraGestureDetector.CameraManipulator] for orbit/pan/zoom control.
 *
 * The manipulator translates touch gestures into camera transform updates — one-finger drag to
 * orbit, two-finger drag to pan, pinch to zoom. It is updated automatically every frame inside
 * `SceneView`.
 *
 * Pass `null` to `SceneView(cameraManipulator = null)` to disable camera interaction entirely.
 *
 * `orbitHomePosition` is the camera's **eye position** — not a "home" it returns to: no built-in
 * gesture does that, `onDoubleTap` is a plain callback `SceneView` forwards to your code and
 * never wires to the camera. For the common case of framing a subject from a known distance,
 * prefer [rememberCameraManipulator] with `orbitRadius` instead — it sidesteps the vector-length
 * reasoning below entirely.
 *
 * ```kotlin
 * val cameraManipulator = rememberCameraManipulator(
 *     // Eye 2.5 m from an auto-centred subject — it is the LENGTH of this vector that frames.
 *     orbitHomePosition = Position(x = 0f, y = 0.5f, z = 2.45f),
 *     targetPosition    = Position(x = 0f, y = 0f, z = 0f)
 * )
 * ```
 *
 * ### How far away the camera actually ends up
 *
 * `orbitHomePosition` is the eye's **absolute world position**: Filament's `OrbitManipulator`
 * assigns it verbatim (`mEye = mProps.orbitHomePosition`, defaulting to `(0, 0, 1)`) and never
 * re-bases it on `targetPosition`. `targetPosition` sets the orbit pivot and the direction the
 * camera initially looks in — it does not set the distance.
 *
 * What makes that easy to get wrong is the interaction with auto-centring. Under `SceneView`'s
 * default `autoCenterContent = true` the DSL content is translated so its bounding-box centre
 * lands on the **world origin**, so the distance your subject is framed from is
 * **`|orbitHomePosition|`** — the coordinates you gave your nodes do not survive, and
 * `targetPosition` does not enter into it:
 *
 * ```kotlin
 * // Nodes authored at z = -1.5 are auto-centred back onto the origin, so this frames the
 * // subject from |(0, 0.2, 1.2)| ≈ 1.22 m — NOT from |(0, 0.2, 1.2) − (0, 0, -1.5)| ≈ 2.7 m.
 * rememberCameraManipulator(
 *     orbitHomePosition = Position(0f, 0.2f, 1.2f),
 *     targetPosition    = Position(0f, 0f, -1.5f)
 * )
 * ```
 *
 * Aiming `targetPosition` at where the content was authored does not push the subject away from
 * the camera; it only tilts the view. Both readings coincide whenever the target is the origin,
 * which is why every doc example looks fine and why this cost issue #2873 its diagnosis (the demo
 * was framed from 1.22 m while its own comment claimed 2.7 m). Pass `autoCenterContent = false`
 * to `SceneView` when authored world positions should survive; the framing distance is then
 * `|orbitHomePosition − contentCentre|`. The `orbitRadius` overload sidesteps all of this for the
 * common case: with the default (origin) target its value *is* the subject distance (#2932).
 *
 * @param orbitHomePosition Camera's initial eye position in **world space** (optional). Its
 *                          *length* is the framing distance under the default
 *                          `autoCenterContent = true` — see above. Omitting it does **not** give
 *                          you Filament's `(0, 0, 1)`: `SceneView`'s own default manipulator
 *                          passes `cameraNode.worldPosition`, i.e. `(0, 0.4, 2.75)` ≈ 2.78 m for
 *                          the default [CameraNode]. No built-in gesture returns the camera to
 *                          this position either; `onDoubleTap` is a plain callback that
 *                          `SceneView` forwards to your code and never wires to the camera.
 * @param targetPosition    Point in world space the camera orbits around and initially looks at
 *                          (optional; defaults to the origin). Does not affect the distance.
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
    frameRatePolicy: FrameRatePolicy = FrameRatePolicy.OnDemand(),
    renderQuality: RenderQuality = RenderQuality.Default,
    autoCenterContent: Boolean = true,
    autoFitContent: Boolean = false,
    framingPadding: Float = DEFAULT_FRAMING_PADDING,
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    fillLightNode: LightNode? = rememberFillLightNode(engine),
    cameraNode: CameraNode = rememberCameraNode(engine),
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    cameraManipulator: CameraGestureDetector.CameraManipulator? = rememberCameraManipulator(
        orbitHomePosition = cameraNode.worldPosition
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
