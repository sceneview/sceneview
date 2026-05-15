// ARScene lives in the separate `arsceneview` module intentionally: apps that only need 3D
// can depend on `sceneview` alone without pulling in ARCore.
//
//  Migration checklist:
//  1. Move ar/ package contents into sceneview/src/main/java/io/github/sceneview/ar/
//  2. Change ARCore from `api` to `compileOnly` in sceneview/build.gradle
//  3. Guard ARCore usage with runtime classpath checks (Class.forName or similar)
//  4. Update build.gradle consumers: replace `arsceneview` dependency with `sceneview`
//  5. Update llms.txt and docs to reflect single-module architecture

package io.github.sceneview.ar

import android.content.Context.WINDOW_SERVICE
import android.util.Size
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager as AndroidWindowManager
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.utils.readBuffer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.PlaybackFailedException
import io.github.sceneview.SceneNodeManager
import io.github.sceneview.SceneRenderer
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.arcore.configure
import io.github.sceneview.ar.arcore.isTracking
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.light.LightEstimator
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import io.github.sceneview.node.ViewNode.WindowManager
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberFillLightNode
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberARView
import io.github.sceneview.safeDestroyEnvironment
import io.github.sceneview.safeDestroyIndirectLight
import kotlinx.coroutines.delay
import java.io.File
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * An ARCore session declared as Compose UI.
 *
 * `ARSceneView` is a `@Composable` that embeds a Filament + ARCore viewport. Its trailing [content]
 * block is an **[ARSceneScope]** DSL where AR-tracked nodes — anchors, augmented images, face
 * meshes, cloud anchors, hit-result cursors — are composable functions that follow the same
 * Compose lifecycle as any other UI element.
 *
 * Drive AR state with ordinary Compose `mutableStateOf`: when state changes, the composition
 * updates and the 3D scene reflects it on the next frame.
 *
 * ### Minimal usage
 * ```kotlin
 * var anchor by remember { mutableStateOf<Anchor?>(null) }
 *
 * ARSceneView(
 *     modifier = Modifier.fillMaxSize(),
 *     onSessionUpdated = { _, frame ->
 *         if (anchor == null) {
 *             anchor = frame.getUpdatedPlanes()
 *                 .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
 *                 ?.let { frame.createAnchorOrNull(it.centerPose) }
 *         }
 *     }
 * ) {
 *     anchor?.let { a ->
 *         AnchorNode(anchor = a) {
 *             ModelNode(
 *                 modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb"),
 *                 scaleToUnits = 0.5f
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * When `anchor` is set, `AnchorNode` enters the composition and the model appears in AR.
 * When cleared, both are removed and destroyed automatically — no cleanup code needed.
 *
 * @param modifier                 Modifier for the underlying AR surface.
 * @param surfaceType              [SurfaceType.Surface] (SurfaceView, best GPU performance) or
 *                                 [SurfaceType.TextureSurface] (TextureView, supports alpha blending).
 * @param engine                   Shared Filament [Engine]. Use [rememberEngine].
 * @param modelLoader              Loader for glTF/GLB models. Use [rememberModelLoader].
 * @param materialLoader           Loader for Filament material templates. Use [rememberMaterialLoader].
 * @param environmentLoader        Loader for HDR environments. Use [rememberEnvironmentLoader].
 * @param sessionFeatures          ARCore [Session.Feature]s to enable (e.g. front camera).
 * @param playbackDataset          Optional MP4 dataset [File] previously written via
 *                                 [Session.startRecording][com.google.ar.core.Session.startRecording].
 *                                 When non-null, ARCore replays the dataset instead of using the
 *                                 live camera — the session re-runs as if you were there.
 *                                 Useful for record-replay debugging, deterministic AR tests, and
 *                                 sharing reproducers between developers without needing the
 *                                 original device or location.
 * @param sessionCameraConfig      Selects the ARCore [CameraConfig] for the session. Defaults to
 *                                 [highestResolutionCameraConfig], which picks the highest-resolution
 *                                 BACK-facing 30 FPS config the device exposes — so [ARRecorder]
 *                                 recordings capture at full camera resolution instead of ARCore's
 *                                 low-res 640×480 CPU-stream default (#1065). Pass `null` to keep
 *                                 ARCore's stock default config.
 * @param sessionConfiguration     Callback to configure the ARCore [Session] and [Config].
 *                                 SceneView pre-sets `config.lightEstimationMode = ENVIRONMENTAL_HDR`
 *                                 (replacing ARCore's `AMBIENT_INTENSITY` default) BEFORE invoking
 *                                 this callback, so the IBL baseline shipped by [rememberAREnvironment]
 *                                 is replaced by ARCore's real-environment estimate once stable.
 *                                 Front-camera sessions still force `DISABLED` regardless. Override
 *                                 inside this callback to choose a different mode if needed (#1063).
 * @param planeRenderer            Whether to render the AR plane grid overlay.
 * @param cameraStream             [ARCameraStream] for camera texture rendering and occlusion.
 * @param view                     Filament [View] for this scene. Use [rememberARView] (default)
 *                                 which applies [ToneMapper.Linear] to prevent the camera
 *                                 background from being over-processed by Filamic tone mapping.
 * @param isOpaque                 Whether the render target is opaque. Default `true`.
 * @param renderer                 Filament [Renderer]. Use [rememberRenderer].
 * @param scene                    Filament [SceneView] graph. Use [rememberScene].
 * @param environment              IBL + skybox environment. Use [rememberAREnvironment].
 * @param mainLightNode            Primary directional light. Use [rememberMainLightNode].
 * @param fillLightNode            Secondary fill light (softer ambient — opposite-side directional
 *                                 at ~30% main intensity). Mirrors the 3D [SceneView] v4.1.0 two-light
 *                                 setup so the AR scene baseline matches the 3D demos. Use
 *                                 [rememberFillLightNode] or pass `null` for a single-light setup.
 *                                 ARCore light estimation still drives `mainLightNode`; `fillLightNode`
 *                                 keeps its baseline color/intensity untouched (#1063).
 * @param cameraNode               AR camera node. Use [rememberARCameraNode].
 * @param cameraExposure           Optional **absolute exposure scaling** for the AR camera
 *                                 (Filament's single-`Float` `setExposure` overload — 1.0 ≈ ISO 100 ≈
 *                                 EV 0). **Not a signed EV-stop bias**: negative values clamp to zero
 *                                 (full black, #1179). Realistic range ~0.05 - ~16. Prefer leaving
 *                                 `null` — the [ARDefaultCameraNode] default (f/12, 1/200 s, ISO 200 ≈
 *                                 EV 11.6) is correct for both back- and front-camera sessions after
 *                                 the #1088 + #1067 + #1063 / #1075 realignment.
 * @param collisionSystem          Hit-testing and collision system. Use [rememberCollisionSystem].
 * @param viewNodeWindowManager    Off-screen window manager required for [SceneScope.ViewNode].
 * @param onSessionCreated         Called once when the ARCore [Session] is ready.
 * @param onSessionResumed         Called each time the session is resumed.
 * @param onSessionPaused          Called each time the session is paused.
 * @param onSessionFailed          Called if ARCore fails to initialize (missing ARCore or permission).
 * @param onSessionUpdated         Called once per AR frame before the scene is updated.
 * @param onTrackingFailureChanged Called when the camera [TrackingFailureReason] changes.
 * @param onGestureListener        Gesture callbacks — tap, double-tap, drag, pinch, etc.
 * @param onTouchEvent             Raw touch event callback with optional hit result.
 * @param permissionHandler        [ARPermissionHandler] for camera permission and ARCore install
 *                                 checks. Auto-created from the host [ComponentActivity][androidx.activity.ComponentActivity]
 *                                 when available. Pass `null` to skip permission checks.
 * @param lifecycle                Lifecycle that binds the AR session resume/pause cycle.
 * @param content                  Declare AR scene content using the [ARSceneScope] composable DSL.
 */
@Composable
fun ARSceneView(
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
     * Fundamental session features that can be requested.
     * @see Session.Feature
     */
    sessionFeatures: Set<Session.Feature> = setOf(),
    /**
     * Optional MP4 dataset [File] to play back instead of the live camera feed.
     *
     * When non-null, ARCore is configured for **playback** mode: the session re-runs the
     * recorded camera frames, IMU data, planes, anchors and depth from the dataset, exactly
     * as captured by a previous call to
     * [Session.startRecording][com.google.ar.core.Session.startRecording] (or via the
     * [io.github.sceneview.ar.recording.ARRecorder] helper). This is the standard ARCore
     * record-replay workflow — capture an outdoor session once, iterate at the desk against
     * the recording, share the MP4 with teammates to reproduce bugs deterministically.
     *
     * The file must be passed before the session resumes; SceneView wires it on session
     * creation. Switching between live and playback at runtime requires the [ARSceneView]
     * to be fully recreated (e.g. via Compose `key(playbackDataset) { … }`), because ARCore
     * binds the playback source to the [Session] for its entire lifetime.
     *
     * Default `null` (live camera mode).
     */
    playbackDataset: File? = null,
    /**
     * Selects the camera config to use. The returned config must be one returned by
     * [Session.getSupportedCameraConfigs].
     *
     * Defaults to [highestResolutionCameraConfig] so every AR scene — and in particular every
     * [ARRecorder] recording — runs at the device's full back-camera resolution rather than
     * ARCore's low-res 640×480 CPU-stream default ([#1065](https://github.com/sceneview/sceneview/issues/1065)).
     * Pass `null` to keep ARCore's stock default config, or supply a custom selector.
     */
    sessionCameraConfig: ((Session) -> CameraConfig)? = ::highestResolutionCameraConfig,
    /**
     * Configures the session and verifies that the enabled features in the specified session
     * config are supported with the currently set camera config.
     */
    sessionConfiguration: ((session: Session, Config) -> Unit)? = null,
    /**
     * Enable the plane renderer.
     */
    planeRenderer: Boolean = true,
    /**
     * The [ARCameraStream] to render the camera texture.
     */
    cameraStream: ARCameraStream? = rememberARCameraStream(materialLoader),
    /**
     * Encompasses all the state needed for rendering a [SceneView].
     */
    view: View = rememberARView(engine),
    /**
     * Controls whether the render target is opaque or not. Default `true`.
     */
    isOpaque: Boolean = true,
    /**
     * A [Renderer] instance represents an operating system's window.
     */
    renderer: Renderer = rememberRenderer(engine),
    /**
     * Provide your own instance if you want to share [Node]s' scene between multiple views.
     */
    scene: Scene = rememberScene(engine),
    /**
     * Defines the lighting environment and the skybox of the scene.
     *
     * Defaults to [rememberAREnvironment] with an [EnvironmentLoader], which ships
     * the neutral IBL baseline so PBR materials reflect something sensible until
     * ARCore's `ENVIRONMENTAL_HDR` light estimate stabilises (#1063).
     */
    environment: Environment = rememberAREnvironment(engine),
    /**
     * Always add a direct light source since it is required for shadowing.
     */
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    /**
     * Optional secondary "fill" directional light that softens the shadows produced by
     * [mainLightNode]. Default mirrors the 3D `Scene` two-light setup (main + fill at 30%)
     * shipped in v4.1.0 — AR scenes now match the 3D demo baseline (#1063). Pass `null` for
     * a single-light AR scene. ARCore's `ENVIRONMENTAL_HDR` estimate only drives
     * [mainLightNode]; [fillLightNode] keeps its baseline color/intensity each frame.
     */
    fillLightNode: LightNode? = rememberFillLightNode(engine),
    cameraNode: ARCameraNode = rememberARCameraNode(engine),
    /**
     * Optional **absolute exposure scaling** applied to the AR camera.
     *
     * When non-null, this value is forwarded to Filament's
     * [io.github.sceneview.components.CameraComponent.setExposure] (single-`Float`
     * overload), which sets the aperture to 1.0, the shutter speed to 1.2 s, and
     * derives the sensitivity to match the requested **linear exposure value**
     * (1.0 ≈ ISO 100 ≈ EV 0). This is **not** a signed EV-stop bias — negative
     * values clamp the framebuffer to zero (full black, see #1179). Realistic
     * values land between ~0.05 (very dark) and ~16 (very bright).
     *
     * In practice, prefer leaving this `null`. The default exposure set by
     * [ARDefaultCameraNode] (f/12, 1/200 s, ISO 200 ≈ EV 11.6, after #1088)
     * matches the v4.1.0 main+fill light setup (10k + 3k lux) and is correct
     * for both back- and front-camera sessions on every device tested through
     * Pixel 9. Override only when porting a legacy AR scene that depends on
     * the linear-gain form.
     *
     * To darken / brighten by a number of stops instead, build a fresh
     * [ARCameraNode] and call the 3-arg `setExposure(aperture, shutter, ISO)`
     * on it (see [ARDefaultCameraNode] for the parity-correct values).
     */
    cameraExposure: Float? = null,
    /**
     * Physics system to handle collision between nodes, hit testing on nodes, etc.
     */
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    /**
     * Used for [io.github.sceneview.node.ViewNode]s that can display an Android [android.view.View].
     */
    viewNodeWindowManager: WindowManager? = null,
    /**
     * The session is ready to be accessed.
     */
    onSessionCreated: ((session: Session) -> Unit)? = null,
    /**
     * The session has been resumed.
     */
    onSessionResumed: ((session: Session) -> Unit)? = null,
    /**
     * The session has been paused.
     */
    onSessionPaused: ((session: Session) -> Unit)? = null,
    /**
     * Invoked when an ARCore error occurred during session creation / resume / configuration
     * (missing ARCore install, permission denied, device-unsupported, etc.).
     *
     * Playback-dataset failures (`PlaybackFailedException` or any exception thrown by
     * [Session.setPlaybackDataset]) are routed here ONLY when [onPlaybackFailed] is `null`.
     * Set [onPlaybackFailed] for fine-grained handling of "bad MP4 path" vs "AR unavailable".
     */
    onSessionFailed: ((exception: Exception) -> Unit)? = null,
    /**
     * Optional dedicated callback for failures that originate from the
     * [playbackDataset] binding — typically `PlaybackFailedException` from ARCore when the
     * MP4 cannot be opened, parsed, or already has an active recording.
     *
     * When `null` (default), playback failures fall through to [onSessionFailed] for
     * backwards compatibility.
     */
    onPlaybackFailed: ((exception: Exception) -> Unit)? = null,
    /**
     * Updates of the state of the ARCore system.
     * Invoked once per [Frame] immediately before the Scene is updated.
     */
    onSessionUpdated: ((session: Session, frame: Frame) -> Unit)? = null,
    /**
     * Listen for camera tracking failure.
     */
    onTrackingFailureChanged: ((trackingFailureReason: TrackingFailureReason?) -> Unit)? = null,
    /**
     * The listener invoked for all the gesture detector callbacks.
     */
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    permissionHandler: ARPermissionHandler? = (LocalContext.current as? androidx.activity.ComponentActivity)?.let { activity ->
        remember(activity) { ActivityARPermissionHandler(activity) }
    },
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    /**
     * DSL block for declaring AR nodes via [ARSceneScope].
     */
    content: (@Composable ARSceneScope.() -> Unit)? = null
) {
    if (LocalInspectionMode.current) {
        ARScenePreview(modifier)
        return
    }

    val context = LocalContext.current

    // ── AR subsystems ─────────────────────────────────────────────────────────────────────────────

    val arPlaneRenderer = remember(engine, materialLoader, scene) {
        PlaneRenderer(engine, materialLoader, scene)
    }
    val lightEstimator = remember(engine, environmentLoader) {
        LightEstimator(engine, environmentLoader.iblPrefilter)
    }
    DisposableEffect(arPlaneRenderer, lightEstimator) {
        onDispose {
            arPlaneRenderer.destroy()
            lightEstimator.destroy()
        }
    }

    // ── ARCore session lifecycle ──────────────────────────────────────────────────────────────────

    // Mutable refs for callbacks — updated each recomposition so lambdas are always fresh.
    val onSessionCreatedRef = remember { AtomicReference(onSessionCreated) }
    val onSessionResumedRef = remember { AtomicReference(onSessionResumed) }
    val onSessionPausedRef = remember { AtomicReference(onSessionPaused) }
    val onSessionFailedRef = remember { AtomicReference(onSessionFailed) }
    val onPlaybackFailedRef = remember { AtomicReference(onPlaybackFailed) }
    val onSessionUpdatedRef = remember { AtomicReference(onSessionUpdated) }
    val onTrackingFailureChangedRef = remember { AtomicReference(onTrackingFailureChanged) }
    val sessionConfigurationRef = remember { AtomicReference(sessionConfiguration) }
    val sessionCameraConfigRef = remember { AtomicReference(sessionCameraConfig) }

    SideEffect {
        onSessionCreatedRef.set(onSessionCreated)
        onSessionResumedRef.set(onSessionResumed)
        onSessionPausedRef.set(onSessionPaused)
        onSessionFailedRef.set(onSessionFailed)
        onPlaybackFailedRef.set(onPlaybackFailed)
        onSessionUpdatedRef.set(onSessionUpdated)
        onTrackingFailureChangedRef.set(onTrackingFailureChanged)
        sessionConfigurationRef.set(sessionConfiguration)
        sessionCameraConfigRef.set(sessionCameraConfig)
    }

    val prevTrackingFailureRef = remember { AtomicReference<TrackingFailureReason?>(null) }
    val isFrontFaceWindingInvertedRef = remember { AtomicBoolean(false) }

    // Baseline mainLight color + intensity captured on the first frame the lightEstimator
    // produces an estimate. Without this, ARScene's per-frame
    // `light.color = light.color * estimate` reads back the PREVIOUS frame's value and
    // multiplies by an already-absolute estimate → exponential decay to black within
    // ~15 frames (#1062). With the baseline cached, the multiplication is
    // `baseline * estimate` each frame, which is what the LightEstimator's docstring
    // intends.
    //
    // Keyed on `mainLightNode` identity so swapping the light (e.g. via #1017's reactive
    // `LightSlot` pattern) resets the baseline to the new light's defaults, not stale
    // values from the previous light.
    val baselineMainLightColorRef = remember(mainLightNode) {
        AtomicReference<io.github.sceneview.math.Color?>(null)
    }
    val baselineMainLightIntensityRef = remember(mainLightNode) {
        AtomicReference<Float?>(null)
    }

    val arCore = remember {
        // Snapshotted at first composition. ARCore requires setPlaybackDataset() to be called
        // BEFORE the first resume(), so we capture the param value once when the session is
        // built and ignore later recompositions that mutate it — toggling between live/playback
        // at runtime requires the caller to recreate the ARSceneView (typically via
        // `key(playbackDataset) { ARSceneView(...) }`).
        val initialPlaybackDataset = playbackDataset
        ARCore(
            onSessionCreated = { session ->
                cameraStream?.let { session.setCameraTextureNames(it.cameraTextureIds) }
                // Bind the playback source first — ARCore mandates the dataset is set before
                // resume(), and configure() happens here, then resume() runs immediately
                // after this callback returns.
                initialPlaybackDataset?.let { file ->
                    try {
                        session.setPlaybackDataset(file.absolutePath)
                    } catch (e: PlaybackFailedException) {
                        // Prefer the dedicated playback callback when wired (audit #876),
                        // fall back to onSessionFailed for backwards compatibility.
                        (onPlaybackFailedRef.get() ?: onSessionFailedRef.get())?.invoke(e)
                    } catch (e: Exception) {
                        // Defensive — ARCore may throw IllegalStateException if the session
                        // has already been resumed elsewhere. Don't crash; surface to caller.
                        (onPlaybackFailedRef.get() ?: onSessionFailedRef.get())?.invoke(e)
                    }
                }
                sessionCameraConfigRef.get()?.let { session.cameraConfig = it(session) }
                session.configure { config ->
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // Default to ENVIRONMENTAL_HDR (#1063 acceptance). ARCore's stock default is
                    // AMBIENT_INTENSITY which only returns a pixel-intensity scalar, so the IBL
                    // baseline shipped by `rememberAREnvironment` would never be replaced by the
                    // real environment estimate — PBR metals stay locked on the neutral baseline
                    // even after the user pans across a real scene. ENVIRONMENTAL_HDR returns
                    // mainLightDirection + spherical-harmonics irradiance + the HDR cubemap so
                    // `onARFrame` can swap in the real-environment IBL. Front-camera sessions
                    // still force DISABLED inside `ARSession.configure()` regardless. Set BEFORE
                    // invoking the user callback so callers can opt back into another mode.
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    sessionConfigurationRef.get()?.invoke(session, config)
                }
                cameraStream?.let { scene.addEntity(it.entity) }
                onSessionCreatedRef.get()?.invoke(session)
            },
            onSessionResumed = { session ->
                session.configure { config -> config.focusMode = Config.FocusMode.AUTO }
                onSessionResumedRef.get()?.invoke(session)
            },
            onSessionPaused = { session ->
                onSessionPausedRef.get()?.invoke(session)
            },
            onArSessionFailed = { exception ->
                onSessionFailedRef.get()?.invoke(exception)
            },
            onSessionConfigChanged = { session, _ ->
                isFrontFaceWindingInvertedRef.set(
                    session.cameraConfig.facingDirection == CameraConfig.FacingDirection.FRONT
                )
            }
        )
    }

    DisposableEffect(lifecycle) {
        arCore.create(context, permissionHandler, sessionFeatures)

        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { arCore.resume(context, permissionHandler) }
            override fun onPause(owner: LifecycleOwner) { arCore.pause() }
        }
        lifecycle.addObserver(observer)

        onDispose {
            lifecycle.removeObserver(observer)
            arCore.destroy()
        }
    }

    // ── Scene / camera / environment setup ───────────────────────────────────────────────────────

    val nodeManager = remember(scene, collisionSystem) { SceneNodeManager(scene, collisionSystem) }

    SideEffect {
        scene.indirectLight = environment.indirectLight
        scene.skybox = environment.skybox
        view.scene = scene
        view.camera = cameraNode.camera
        cameraNode.collisionSystem = collisionSystem
        cameraNode.setView(view)
        cameraExposure?.let { cameraNode.setExposure(it) }
    }

    // ── Main light node ───────────────────────────────────────────────────────────────────────────

    val prevMainLightRef = remember { AtomicReference<LightNode?>(null) }
    SideEffect {
        val prev = prevMainLightRef.get()
        if (prev != mainLightNode) {
            prev?.let { nodeManager.removeNode(it) }
            mainLightNode?.let { nodeManager.addNode(it) }
            prevMainLightRef.set(mainLightNode)
        }
    }

    // ── Fill light node ───────────────────────────────────────────────────────────────────────────
    //
    // Mirrors the 3D `Scene` v4.1.0 two-light setup (main + fill at 30%) so the AR scene
    // baseline matches the 3D demos. The light estimator only mutates `mainLightNode`
    // (`onARFrame` reads back its color/intensity from `baselineMainLightColorRef`); the fill
    // light keeps its `createFillLightNode` defaults each frame regardless of ARCore (#1063).
    //
    // `DisposableEffect` (not `SideEffect`) — same pattern as the 3D `Scene.kt` `mainLight` /
    // `fillLight` wiring landed in #1131. Removes the light from the Filament scene both on
    // (a) key change AND (b) composable disposal so a shared `rememberScene(engine)` doesn't
    // leak duplicates when the AR view leaves composition.
    //
    // If a caller passes the SAME `LightNode` instance for `mainLightNode` and `fillLightNode`,
    // the duplicate `addNode` is a no-op (`SceneNodeManager.managedNodes` is a `Set`), and the
    // shared instance is removed cleanly when whichever of the two effects disposes last.
    DisposableEffect(fillLightNode) {
        fillLightNode?.let { nodeManager.addNode(it) }
        onDispose {
            fillLightNode?.let { nodeManager.removeNode(it) }
        }
    }

    // ── DSL nodes → Filament scene sync ──────────────────────────────────────────────────────────

    val scopeChildNodes: SnapshotStateList<Node> = remember { mutableStateListOf() }
    val childNodesRef = remember { AtomicReference(emptyList<Node>()) }

    LaunchedEffect(nodeManager) {
        var prevNodes = emptyList<Node>()
        snapshotFlow { scopeChildNodes.toList() }.collect { newNodes ->
            (prevNodes - newNodes.toSet()).forEach { nodeManager.removeNode(it) }
            (newNodes - prevNodes.toSet()).forEach { nodeManager.addNode(it) }
            prevNodes = newNodes
            childNodesRef.set(newNodes)
        }
    }

    // ── Camera stream lifecycle ───────────────────────────────────────────────────────────────────

    // Keep a thread-safe ref so the render loop always uses the latest camera stream instance,
    // even if it was recreated by a recomposition.
    val cameraStreamRef = remember { AtomicReference<ARCameraStream?>(cameraStream) }
    val prevCameraStreamRef = remember { AtomicReference<ARCameraStream?>(null) }
    SideEffect {
        cameraStreamRef.set(cameraStream)
        val prev = prevCameraStreamRef.get()
        if (prev != cameraStream) {
            prev?.let { scene.removeEntity(it.entity) }
            cameraStream?.let { stream ->
                arCore.session?.let {
                    it.setCameraTextureNames(stream.cameraTextureIds)
                    scene.addEntity(stream.entity)
                }
            }
            prevCameraStreamRef.set(cameraStream)
        }
    }

    // ── Plane renderer state ──────────────────────────────────────────────────────────────────────

    SideEffect {
        arPlaneRenderer.isEnabled = planeRenderer
    }

    // ── Lifecycle-aware rendering ─────────────────────────────────────────────────────────────────

    val isResumed = remember {
        AtomicBoolean(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { isResumed.set(true) }
            override fun onPause(owner: LifecycleOwner) { isResumed.set(false) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // ── Gesture detection ────────────────────────────────────────────────────────────────────────

    val gestureDetector = remember(context) { GestureDetector(context = context, listener = null) }
    val cameraGestureDetectorRef = remember { AtomicReference<CameraGestureDetector?>(null) }

    SideEffect { gestureDetector.listener = onGestureListener }

    val touchDispatcher: (MotionEvent) -> Unit = { event ->
        val hitResult = collisionSystem.hitTest(event).firstOrNull { it.node.isTouchable }
        if (onTouchEvent?.invoke(event, hitResult) != true &&
            hitResult?.node?.onTouchEvent(event, hitResult) != true
        ) {
            gestureDetector.onTouchEvent(event, hitResult)
            cameraGestureDetectorRef.get()?.onTouchEvent(event)
        }
    }

    // ── SceneRenderer — encapsulates surface lifecycle + swap chain + frame pipeline ─────────────

    @Suppress("DEPRECATION")
    val display = remember(context) {
        (context.getSystemService(WINDOW_SERVICE) as AndroidWindowManager).defaultDisplay
    }

    val sceneRenderer = remember(engine, view, renderer) {
        SceneRenderer(engine, view, renderer)
    }

    // Wire resize and surface callbacks — AR needs additional display geometry + plane renderer.
    SideEffect {
        sceneRenderer.onSurfaceResized = { width, height ->
            cameraNode.updateProjection()
            arCore.session?.setDisplayGeometry(display.rotation, width, height)
            arPlaneRenderer.viewSize = Size(width, height)
        }
        sceneRenderer.onSurfaceReady = { viewHeight ->
            if (cameraGestureDetectorRef.get() == null) {
                cameraGestureDetectorRef.set(
                    CameraGestureDetector(
                        viewHeight = viewHeight,
                        cameraManipulator = null  // AR mode — no orbit camera
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

    LaunchedEffect(engine, renderer, view, scene) {
        while (true) {
            if (!isResumed.get()) {
                delay(16)
                continue
            }
            withFrameNanos { frameTimeNanos ->
                sceneRenderer.renderFrame(frameTimeNanos) {
                    view.isFrontFaceWindingInverted = isFrontFaceWindingInvertedRef.get()

                    val childNodes = childNodesRef.get()

                    // AR frame update — feed ARCore data into camera, lights, planes.
                    arCore.session?.let { session ->
                        try {
                            session.updateOrNull()?.let { frame ->
                                onARFrame(
                                    engine = engine,
                                    scene = scene,
                                    view = view,
                                    cameraNode = cameraNode,
                                    cameraStream = cameraStreamRef.get(),
                                    lightEstimator = lightEstimator,
                                    mainLightNode = mainLightNode,
                                    environment = environment,
                                    arPlaneRenderer = arPlaneRenderer,
                                    childNodes = childNodes,
                                    prevTrackingFailureRef = prevTrackingFailureRef,
                                    onTrackingFailureChangedRef = onTrackingFailureChangedRef,
                                    onSessionUpdatedRef = onSessionUpdatedRef,
                                    baselineMainLightColorRef = baselineMainLightColorRef,
                                    baselineMainLightIntensityRef = baselineMainLightIntensityRef,
                                    session = session,
                                    frame = frame
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e(
                                "SceneView",
                                "ARCore session update failed",
                                e
                            )
                        }
                    }

                    modelLoader.updateLoad()
                    childNodes.forEach { it.onFrame(frameTimeNanos) }
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
                }
            },
            update = {}
        )

        SurfaceType.TextureSurface -> AndroidView(
            modifier = modifier,
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    sceneRenderer.attachToTextureView(tv, isOpaque, ctx, display, touchDispatcher)
                }
            },
            update = {}
        )
    }

    // ── DSL content ───────────────────────────────────────────────────────────────────────────────

    if (content != null) {
        val scope = remember(engine, modelLoader, materialLoader, environmentLoader, nodeManager) {
            ARSceneScope(
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                _nodes = scopeChildNodes,
                // Synchronous detach — see ARSceneScope KDoc on `nodeRemover`.
                nodeRemover = nodeManager::removeNode
            )
        }
        scope.content()
    }
}

// ── AR frame update helpers ───────────────────────────────────────────────────────────────────────

private fun onARFrame(
    engine: Engine,
    scene: Scene,
    view: View,
    cameraNode: ARCameraNode,
    cameraStream: ARCameraStream?,
    lightEstimator: LightEstimator?,
    mainLightNode: LightNode?,
    environment: Environment,
    arPlaneRenderer: PlaneRenderer,
    childNodes: List<Node>,
    prevTrackingFailureRef: AtomicReference<TrackingFailureReason?>,
    onTrackingFailureChangedRef: AtomicReference<((TrackingFailureReason?) -> Unit)?>,
    onSessionUpdatedRef: AtomicReference<((Session, Frame) -> Unit)?>,
    baselineMainLightColorRef: AtomicReference<io.github.sceneview.math.Color?>,
    baselineMainLightIntensityRef: AtomicReference<Float?>,
    session: Session,
    frame: Frame
) {
    val camera = frame.camera
    val isCameraTracking = camera.isTracking

    cameraStream?.update(session, frame)
    cameraNode.update(session, frame)

    lightEstimator?.update(session, frame, cameraNode.camera)?.let { estimation ->
        mainLightNode?.let { light ->
            // Capture the baseline light color + intensity on the first frame the
            // estimator produces a value, so subsequent frames multiply
            // `baseline * estimate` (NOT `previous-frame * estimate`, which causes
            // exponential decay to black within ~15 frames — see #1062).
            //
            // `compareAndSet(null, …)` is used so a future move to multi-frame
            // dispatch can't double-snapshot — only one writer wins on the
            // null→value transition. Today `onARFrame` is single-threaded
            // (rendering thread), so this is belt + braces.
            baselineMainLightColorRef.compareAndSet(null, light.color)
            baselineMainLightIntensityRef.compareAndSet(null, light.intensity)
            val baselineColor = baselineMainLightColorRef.get() ?: light.color
            val baselineIntensity = baselineMainLightIntensityRef.get() ?: light.intensity
            estimation.mainLightColor?.let { light.color = baselineColor * it }
            estimation.mainLightIntensity?.let { light.intensity = baselineIntensity * it }
            estimation.mainLightDirection?.let { light.lightDirection = it }
        }
        val indirectLight = environment.indirectLight
        val previousIbl = scene.indirectLight
        IndirectLight.Builder().apply {
            estimation.irradiance?.let { irradiance(3, it) }
                ?: indirectLight?.irradianceTexture?.let { irradiance(it) }
            estimation.reflections?.let { reflections(it) }
                ?: indirectLight?.reflectionsTexture?.let { reflections(it) }
            indirectLight?.intensity?.let { intensity(it) }
            indirectLight?.getRotation(null)?.let { rotation(it) }
        }.build(engine).also { newIbl ->
            scene.indirectLight = newIbl
            // Destroy the previous per-frame IBL to avoid a native memory leak.
            // Don't destroy the environment's own IBL — only the ones we built here.
            if (previousIbl != null && previousIbl != indirectLight) {
                engine.safeDestroyIndirectLight(previousIbl)
            }
        }
    }

    arPlaneRenderer.update(session, frame)

    childNodes.filterIsInstance<PoseNode>().forEach { it.update(session, frame) }

    val newTrackingFailure = if (!isCameraTracking) {
        camera.trackingFailureReason.takeIf { it != TrackingFailureReason.NONE }
    } else null

    if (prevTrackingFailureRef.get() != newTrackingFailure) {
        prevTrackingFailureRef.set(newTrackingFailure)
        onTrackingFailureChangedRef.get()?.invoke(newTrackingFailure)
    }

    onSessionUpdatedRef.get()?.invoke(session, frame)
}

// ── Camera config selection ─────────────────────────────────────────────────────────────────────

/**
 * Picks the highest-resolution BACK-facing, 30 FPS [CameraConfig] the [session] exposes.
 *
 * This is the default value of [ARSceneView]'s `sessionCameraConfig`. ARCore's stock default
 * camera config is the **lowest** CPU-image resolution the device supports (often 640×480 on
 * Pixel-class devices) — fine for the tracking pipeline, but it means [ARRecorder] recordings,
 * which write the CPU image stream into the MP4, were capped at 640×480 regardless of the
 * physical camera ([#1065](https://github.com/sceneview/sceneview/issues/1065)). Selecting the
 * highest-resolution config makes every AR scene — and every recording — run at full camera
 * resolution without per-demo opt-in.
 *
 * Falls back to the session's current [CameraConfig][Session.getCameraConfig] when ARCore
 * exposes no matching config (degenerate device) or when [Session.getSupportedCameraConfigs]
 * throws — the call must never crash session creation.
 *
 * @param session The ARCore [Session] being configured.
 * @return The chosen [CameraConfig]; never throws.
 */
fun highestResolutionCameraConfig(session: Session): CameraConfig =
    runCatching {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
            .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        session.getSupportedCameraConfigs(filter).maxByOrNull {
            it.imageSize.width.toLong() * it.imageSize.height.toLong()
        }
    }.getOrNull() ?: session.cameraConfig

// ── Remember helpers ──────────────────────────────────────────────────────────────────────────────

/**
 * Creates and remembers an [ARCameraNode] configured for AR rendering.
 *
 * Unlike the standard [rememberCameraNode], the AR camera node's transform and projection are
 * updated every frame by ARCore to match the physical device camera. Its exposure is set to
 * match ARCore's light estimation output so that virtual objects blend naturally with the
 * real world.
 *
 * Pass this to `ARSceneView(cameraNode = ...)` — it should not be used with a plain `SceneView`.
 *
 * @param engine  The Filament [Engine] that owns the camera.
 * @param creator Factory for the AR camera node.
 * @return An [ARCameraNode] destroyed on disposal.
 */
@Composable
fun rememberARCameraNode(
    engine: Engine,
    creator: () -> ARCameraNode = {
        createARCameraNode(engine)
    }
) = rememberNode(creator)

/**
 * Creates and remembers an [ARCameraStream] for rendering the device camera feed.
 *
 * The camera stream owns the OpenGL external texture that receives frames from ARCore, and
 * the Filament renderable that draws that texture as the scene background. It also provides
 * depth occlusion when depth mode is enabled.
 *
 * Pass the result to `ARSceneView(cameraStream = ...)`. Without a camera stream the AR background
 * will be black instead of showing the live camera image.
 *
 * @param materialLoader The [MaterialLoader] used to create the camera background material.
 * @param creator        Factory for the camera stream.
 * @return An [ARCameraStream] destroyed on disposal.
 */
@Composable
fun rememberARCameraStream(
    materialLoader: MaterialLoader,
    creator: () -> ARCameraStream = {
        createARCameraStream(materialLoader)
    }
) = remember(materialLoader) { creator() }.also { cameraStream ->
    DisposableEffect(cameraStream) {
        onDispose {
            cameraStream.destroy()
        }
    }
}

/**
 * Creates and remembers an AR-optimised [Environment] with the bundled neutral IBL baseline.
 *
 * Loads `assets/environments/neutral/neutral_ibl.ktx` so PBR materials have something
 * sensible to reflect in the first frames before ARCore's `ENVIRONMENTAL_HDR` light
 * estimate stabilises (#1063). ARCore replaces the IBL each frame in [ARScene]'s update
 * loop once the estimate is available; without this baseline metals show up jet-black
 * until ARCore has had a few frames of camera motion to learn the environment.
 *
 * The environment also has no skybox (transparent background so the camera feed shows
 * through).
 *
 * @param engine The Filament [Engine] that owns the IBL texture.
 * @param apply  Optional configuration block applied after creation.
 * @return An [Environment] destroyed on disposal.
 */
@Composable
fun rememberAREnvironment(
    engine: Engine,
    apply: Environment.() -> Unit = {}
): Environment {
    val context = LocalContext.current
    // Read the bundled neutral IBL. `runCatching` so a missing asset (only possible
    // if the consumer drops the `sceneview` AAR's assets) downgrades to no-IBL
    // behaviour instead of crashing — same behaviour as the pre-#1063 path.
    val iblBuffer = remember(context) {
        runCatching {
            context.assets.readBuffer("environments/neutral/neutral_ibl.ktx")
        }.getOrNull()
    }
    val environment = remember(engine, iblBuffer) {
        createAREnvironment(engine, iblBuffer).apply(apply)
    }
    DisposableEffect(environment) {
        onDispose { engine.safeDestroyEnvironment(environment) }
    }
    return environment
}

/**
 * Placeholder displayed when [ARSceneView] is composed inside Android Studio's `@Preview`
 * panel (i.e. `LocalInspectionMode.current == true`).
 *
 * ARCore + Filament are JNI-only and can't run in AS LayoutLib, so the preview pane shows
 * an informative gradient panel pointing the developer at Live Edit on a connected device
 * for the real AR session. Same rationale as the 3D-only `ScenePreview` in `Scene.kt`.
 */
@Composable
private fun ARScenePreview(modifier: Modifier) {
    Box(
        modifier = modifier
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFF1E3A8A), // blue-900
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
                text = "📷  ARSceneView preview",
                style = androidx.compose.ui.text.TextStyle(
                    color = androidx.compose.ui.graphics.Color(0xFFDBEAFE),
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
            )
            androidx.compose.foundation.text.BasicText(
                text = "AR rendering needs ARCore + Filament JNI, neither loaded by AS LayoutLib.\n" +
                    "Use Android Studio Live Edit on an ARCore-supported device for the live session.",
                style = androidx.compose.ui.text.TextStyle(
                    color = androidx.compose.ui.graphics.Color(0xFF93C5FD),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 16.sp,
                ),
            )
        }
    }
}

/**
 * @deprecated Use [ARSceneView] instead. This function is a direct alias provided for backward
 * compatibility with code written against earlier SceneView versions.
 */
@Deprecated("Use ARSceneView instead", ReplaceWith("ARSceneView(modifier, surfaceType, engine, modelLoader, materialLoader, environmentLoader, sessionFeatures, playbackDataset, sessionCameraConfig, sessionConfiguration, planeRenderer, cameraStream, view, isOpaque, renderer, scene, environment, mainLightNode, fillLightNode, cameraNode, cameraExposure, collisionSystem, viewNodeWindowManager, onSessionCreated, onSessionResumed, onSessionPaused, onSessionFailed, onPlaybackFailed, onSessionUpdated, onTrackingFailureChanged, onGestureListener, onTouchEvent, permissionHandler, lifecycle, content)"))
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
    permissionHandler: ARPermissionHandler? = (LocalContext.current as? androidx.activity.ComponentActivity)?.let { activity ->
        remember(activity) { ActivityARPermissionHandler(activity) }
    },
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
    content = content
)
