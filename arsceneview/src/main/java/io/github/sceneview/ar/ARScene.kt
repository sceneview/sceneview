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
import io.github.sceneview.ar.node.DepthMeshNode
import io.github.sceneview.ar.node.PointCloudNode
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.ar.scene.PlaneRendererBase
import io.github.sceneview.ar.scene.PlaneRendererV2
import io.github.sceneview.ar.scene.SceneUnderstanding
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
 * @param playbackDatasetUri       Scoped-storage equivalent of [playbackDataset] (#1770).
 *                                 Accepts `content://` URIs on Android 10+ — mutually exclusive
 *                                 with [playbackDataset].
 * @param sessionCameraConfig      Selects the ARCore [CameraConfig] for the session. Defaults to
 *                                 [highestResolutionCameraConfig], which picks the highest-resolution
 *                                 BACK-facing 30 FPS config the device exposes — so [ARRecorder]
 *                                 recordings capture at full camera resolution instead of ARCore's
 *                                 low-res 640×480 CPU-stream default (#1065). Pass `null` to keep
 *                                 ARCore's stock default config.
 * @param flashMode                ARCore v1.45+ Flash Mode — drives the device torch during the AR
 *                                 session for low-light tracking ([Config.FlashMode.OFF] /
 *                                 [Config.FlashMode.TORCH]). Default `OFF`. Support-gated —
 *                                 unsupported devices / front-camera configs silently downgrade
 *                                 to `OFF` (#1732).
 * @param planeFindingMode         Typed [Config.PlaneFindingMode] (#1766). Default
 *                                 `HORIZONTAL_AND_VERTICAL`.
 * @param depthMode                Typed [Config.DepthMode] (#1766). Default `DISABLED`.
 *                                 Support-gated.
 * @param instantPlacementMode     Typed [Config.InstantPlacementMode] (#1766). Default `DISABLED`.
 * @param geospatialMode           Typed [Config.GeospatialMode] (#1766). Default `DISABLED`.
 *                                 Requires ARCore Cloud API key + `ACCESS_FINE_LOCATION`.
 * @param streetscapeGeometryMode  Typed [Config.StreetscapeGeometryMode] (#1766). Default
 *                                 `DISABLED`. Requires `geospatialMode = ENABLED`.
 * @param cloudAnchorMode          Typed [Config.CloudAnchorMode] (#1766). Default `DISABLED`.
 *                                 Requires ARCore Cloud API key.
 * @param augmentedFaceMode        Typed [Config.AugmentedFaceMode] (#1766). Default `DISABLED`.
 *                                 Requires `Session.Feature.FRONT_CAMERA`.
 * @param imageStabilizationMode   Typed [Config.ImageStabilizationMode] (#1766). Default `OFF`.
 * @param semanticMode             Typed [Config.SemanticMode] (#1766). Default `DISABLED`.
 * @param updateMode               Typed [Config.UpdateMode] (#1766). Default `LATEST_CAMERA_IMAGE`.
 * @param focusMode                Typed [Config.FocusMode] (#1766). Default `AUTO`.
 * @param sessionConfiguration     Callback to configure the ARCore [Session] and [Config].
 *                                 SceneView pre-sets `config.lightEstimationMode = ENVIRONMENTAL_HDR`
 *                                 (replacing ARCore's `AMBIENT_INTENSITY` default) BEFORE invoking
 *                                 this callback, so the IBL baseline shipped by [rememberAREnvironment]
 *                                 is replaced by ARCore's real-environment estimate once stable.
 *                                 Front-camera sessions still force `DISABLED` regardless. Override
 *                                 inside this callback to choose a different mode if needed (#1063).
 * @param planeRenderer            Whether to render the AR plane grid overlay.
 * @param planeRendererVersion     Selects which plane-renderer implementation backs the AR
 *                                 session — see [PlaneRendererBase.Version]. **Default is
 *                                 [PlaneRendererBase.Version.V1]** as of v4.16.1. V2 ships
 *                                 in this release as an experimental opt-in: on-device QA
 *                                 in v4.16.0 showed its output not matching the design
 *                                 intent, so the default was reverted while V2 is polished.
 *                                 Opt in via `Version.V2`. See
 *                                 [#2203](https://github.com/sceneview/sceneview/issues/2203).
 * @param cameraStream             [ARCameraStream] for camera texture rendering and occlusion.
 * @param view                     Filament [View] for this scene. Use [rememberARView] (default),
 *                                 which is tuned so the live camera background round-trips back to
 *                                 the original camera pixels (see `createARView`).
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
 *                                 Receives a raw [Exception]. **Soft-deprecated (#1844)** in favour
 *                                 of [onSessionFailure] for typed, exhaustive `when` matching
 *                                 (#1759). The legacy callback stays available indefinitely for
 *                                 backwards compatibility — both fire when set — but new code
 *                                 should wire [onSessionFailure] only.
 * @param onSessionFailure         Typed [ARSessionFailure] callback (#1759). Fires alongside
 *                                 [onSessionFailed]; pick the one that matches your codebase.
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
/**
 * Allowed URI schemes for [playbackDatasetUri] (#1845).
 *
 * ARCore's [com.google.ar.core.Session.setPlaybackDatasetUri] is documented for
 * `content://` URIs from `MediaStore`, Storage Access Framework picker output, and
 * `FileProvider`s. `file://` is also accepted for symmetry with the legacy
 * [playbackDataset] [java.io.File] path. Anything else (`https://`, `data:`, custom schemes)
 * is rejected at the SceneView boundary — passing those silently to ARCore either no-ops or
 * fails with an opaque [com.google.ar.core.exceptions.PlaybackFailedException] from
 * `session.resume()`.
 *
 * Exposed as `internal` (not `private`) so the unit-test suite can pin the scheme set
 * without instantiating a full Composable host.
 */
internal val PLAYBACK_DATASET_URI_ALLOWED_SCHEMES: Set<String> = setOf("content", "file")

/**
 * Returns true if [uri] is acceptable as a `playbackDatasetUri` (#1845).
 *
 * Extracted as a top-level pure function from the `require` inside [ARSceneView] so it can
 * be unit-tested without a Compose host. Mirrors the Composable's contract: `null` is OK
 * (no playback requested); a non-null URI must carry a [PLAYBACK_DATASET_URI_ALLOWED_SCHEMES]
 * scheme.
 */
internal fun isAllowedPlaybackDatasetUri(uri: android.net.Uri?): Boolean =
    uri == null || uri.scheme in PLAYBACK_DATASET_URI_ALLOWED_SCHEMES

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
     * Optional scoped-storage [android.net.Uri] dataset to play back instead of the live camera
     * feed (Android 10+ / API 29+ alternative to [playbackDataset]).
     *
     * Wraps ARCore's [com.google.ar.core.Session.setPlaybackDatasetUri] (#1770), which accepts
     * `content://` URIs (`MediaStore`, Storage Access Framework picker output, app-private
     * file providers). The legacy `playbackDataset: File?` only accepts an absolute filesystem
     * path — which on Android 10+ scoped storage means the app must copy the user-picked MP4
     * into its sandbox before replay. With `playbackDatasetUri` the replay reads the original
     * `Uri` directly.
     *
     * **Scheme allowlist (#1845).** Only `content://` and `file://` URIs are accepted —
     * passing e.g. `https://`, `data:`, or any custom scheme triggers an
     * `IllegalArgumentException` at session creation. ARCore would silently fail or hand the
     * URI off to an arbitrary `ContentResolver` registration; we surface the misuse at the
     * SceneView boundary where the caller can see it.
     *
     * **Caller-side permission requirement.** A `content://` URI is only readable by ARCore
     * if the calling process has been granted read access to it. With a Storage Access Framework
     * picker (`ACTION_OPEN_DOCUMENT`), the grant is implicit for the lifetime of the activity;
     * to outlive process restarts call
     * [ContentResolver.takePersistableUriPermission][android.content.ContentResolver.takePersistableUriPermission].
     * When forwarding the URI to another component via [android.content.Intent], set
     * [android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION] on the launcher intent. Without
     * a read grant ARCore raises [PlaybackFailedException] which is routed to
     * [onPlaybackFailed].
     *
     * **Mutually exclusive with [playbackDataset].** Setting both is a programming error and
     * triggers an `IllegalArgumentException` at session creation. Default `null`.
     *
     * Same snapshot semantics as [playbackDataset] — the value is captured at first composition
     * (ARCore requires the playback source to be set before the first resume). Switching
     * between live and playback at runtime requires wrapping the `ARSceneView` in
     * `key(playbackDatasetUri) { … }` so Compose rebuilds the session.
     */
    playbackDatasetUri: android.net.Uri? = null,
    /**
     * Selects the camera config to use. The returned config must be one returned by
     * [Session.getSupportedCameraConfigs].
     *
     * Defaults to [highestResolutionCameraConfig] so every AR scene — and in particular every
     * [ARRecorder] recording — runs at the device's full back-camera resolution rather than
     * ARCore's low-res 640×480 CPU-stream default ([#1065](https://github.com/sceneview/sceneview/issues/1065)).
     * Pass `null` to keep ARCore's stock default config, [::frontCameraConfig] for an Augmented
     * Faces session, or supply a custom selector (e.g. `cameraConfigFilter { … }`).
     *
     * **⚠️ Swapping the camera config mid-session is disruptive.** This selector is reactive —
     * changing it (e.g. BACK→FRONT for Augmented Faces) is applied to the live [Session], but
     * ARCore restarts the camera pipeline: a frame drop / black flash, total tracking loss
     * (anchors invalidated, planes + world frame re-acquired, plane discontinuities), and a
     * depth-buffer format change (front-camera configs expose no ARCore Depth, so occlusion
     * silently downgrades). For a clean swap, wrap the `ARSceneView` in `key(facing) { … }` and
     * show a spinner over the flash — see the "Camera config swap" example in `llms.txt`.
     */
    sessionCameraConfig: ((Session) -> CameraConfig)? = ::highestResolutionCameraConfig,
    /**
     * Drives the device torch during the AR session — ARCore v1.45+ Flash Mode API (#1732).
     *
     * - [Config.FlashMode.OFF] (default): no torch.
     * - [Config.FlashMode.TORCH]: ARCore keeps the back-camera LED on while the session is
     *   running. Helps tracking in low-light scenes; battery cost is significant — toggle off
     *   once the user re-enters daylight.
     *
     * **Support-gated.** Flash Mode requires (a) ARCore 1.45+ runtime on-device, and (b) a
     * back-camera config — front-camera sessions never expose a torch. If the device or the
     * current camera config does not support the requested mode, SceneView silently downgrades
     * the session to `OFF` (matching the auto-fallback used for unsupported `depthMode`). Apps
     * that want to surface the support state to the user can call
     * [com.google.ar.core.Session.isSupported] with a config carrying the desired
     * [Config.FlashMode] directly.
     */
    flashMode: Config.FlashMode = Config.FlashMode.OFF,
    /**
     * ARCore [Config.PlaneFindingMode] — controls which plane orientations the session tracks.
     * Defaults to [Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL] (ARCore's recommended value
     * for surface-tap demos). Applied BEFORE [sessionConfiguration], so the callback still wins
     * (#1766).
     */
    planeFindingMode: Config.PlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
    /**
     * ARCore [Config.DepthMode] — enables motion-stereo depth so [Frame.acquireDepthImage16Bits],
     * `Frame.hitTestDepth`, [io.github.sceneview.ar.node.DepthMeshNode] and ARCameraStream
     * occlusion all see real-world depth. Defaults to [Config.DepthMode.DISABLED] (ARCore's
     * stock default — the depth pipeline is opt-in for power reasons). Support-gated — if
     * the device does not support the requested mode, [ARSession.configure] silently downgrades
     * to `DISABLED`. Pair with [Config.GeospatialMode.ENABLED] +
     * [Config.StreetscapeGeometryMode.ENABLED] to unlock ARCore 1.54's Geospatial Depth (~65 m,
     * see #1731). Applied BEFORE [sessionConfiguration] (#1766).
     */
    depthMode: Config.DepthMode = Config.DepthMode.DISABLED,
    /**
     * ARCore [Config.InstantPlacementMode] — places anchors before a plane has been found by
     * estimating the surface from screen-space cues. Defaults to
     * [Config.InstantPlacementMode.DISABLED]. Applied BEFORE [sessionConfiguration] (#1766).
     */
    instantPlacementMode: Config.InstantPlacementMode = Config.InstantPlacementMode.DISABLED,
    /**
     * ARCore [Config.GeospatialMode] — unlocks Earth-anchored tracking (Terrain/Rooftop
     * anchors, [GeospatialPose], the VPS-backed coordinate frame). Requires the ARCore Cloud
     * API key and `ACCESS_FINE_LOCATION` permission — without those, ARCore throws
     * `FineLocationPermissionNotGrantedException` on resume. Defaults to
     * [Config.GeospatialMode.DISABLED]. Applied BEFORE [sessionConfiguration] (#1766).
     */
    geospatialMode: Config.GeospatialMode = Config.GeospatialMode.DISABLED,
    /**
     * ARCore [Config.StreetscapeGeometryMode] — exposes Geospatial Streetscape Geometry
     * (buildings + terrain meshes around the user). Requires
     * `geospatialMode = Config.GeospatialMode.ENABLED`; otherwise ARCore rejects the config.
     * Defaults to [Config.StreetscapeGeometryMode.DISABLED]. Applied BEFORE
     * [sessionConfiguration] (#1766).
     */
    streetscapeGeometryMode: Config.StreetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED,
    /**
     * ARCore [Config.CloudAnchorMode] — enables host/resolve of [CloudAnchorNode]s. Requires
     * the ARCore Cloud API key. Defaults to [Config.CloudAnchorMode.DISABLED]. Applied BEFORE
     * [sessionConfiguration] (#1766).
     */
    cloudAnchorMode: Config.CloudAnchorMode = Config.CloudAnchorMode.DISABLED,
    /**
     * ARCore [Config.AugmentedFaceMode] — enables face mesh tracking through
     * [AugmentedFaceNode][io.github.sceneview.ar.node.AugmentedFaceNode]. Requires a
     * front-facing camera session feature (`Session.Feature.FRONT_CAMERA`). Defaults to
     * [Config.AugmentedFaceMode.DISABLED]. Applied BEFORE [sessionConfiguration] (#1766).
     */
    augmentedFaceMode: Config.AugmentedFaceMode = Config.AugmentedFaceMode.DISABLED,
    /**
     * ARCore [Config.ImageStabilizationMode] — enables EIS-aligned camera-stream rendering.
     * Defaults to [Config.ImageStabilizationMode.OFF]. Applied BEFORE [sessionConfiguration]
     * (#1766).
     */
    imageStabilizationMode: Config.ImageStabilizationMode = Config.ImageStabilizationMode.OFF,
    /**
     * ARCore [Config.SemanticMode] — exposes Scene Semantics segmentation labels via
     * `Frame.acquireSemanticImage` / `acquireSemanticConfidenceImage`. Defaults to
     * [Config.SemanticMode.DISABLED]. Applied BEFORE [sessionConfiguration] (#1766).
     */
    semanticMode: Config.SemanticMode = Config.SemanticMode.DISABLED,
    /**
     * ARCore [Config.UpdateMode] — controls whether [Session.update] blocks until a new camera
     * frame is available (`LATEST_CAMERA_IMAGE`) or returns immediately (`BLOCKING`). Defaults
     * to [Config.UpdateMode.LATEST_CAMERA_IMAGE], the value SceneView's render loop is built
     * for. Applied BEFORE [sessionConfiguration] (#1766).
     */
    updateMode: Config.UpdateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE,
    /**
     * ARCore [Config.FocusMode] — `FIXED` to disable auto-focus (best for sharp far-field
     * tracking — what ARCore recommends for most AR experiences), `AUTO` to let the camera
     * driver autofocus. SceneView defaults to [Config.FocusMode.AUTO] for parity with previous
     * behaviour. Applied BEFORE [sessionConfiguration] (#1766).
     */
    focusMode: Config.FocusMode = Config.FocusMode.AUTO,
    /**
     * Configures the session and verifies that the enabled features in the specified session
     * config are supported with the currently set camera config.
     *
     * **Precedence (#1766):** all typed `*Mode` params above are applied to the [Config]
     * BEFORE this callback runs, so the callback still wins. Use the typed params for the
     * common cases (AI codegen, demo boilerplate) and this callback as the escape hatch for
     * any [Config] property without a dedicated param.
     *
     * **⚠️ Mid-session config changes are accepted but not free.** This callback is reactive —
     * editing it re-runs [Session.configure] on the live session. Most `Config` knobs
     * reconfigure cheaply, but some force a camera-pipeline restart with a visible frame drop
     * and brief tracking loss — notably toggling [Config.DepthMode] or any change that switches
     * the camera config (see [sessionCameraConfig]). When in doubt, wrap the `ARSceneView` in
     * `key(...) { … }` keyed on the changing value so Compose rebuilds the session cleanly.
     */
    sessionConfiguration: ((session: Session, Config) -> Unit)? = null,
    /**
     * Enable the plane renderer.
     */
    planeRenderer: Boolean = true,
    /**
     * Selects which plane-renderer implementation backs the AR session — see
     * [PlaneRendererBase.Version].
     *
     * **Default is [PlaneRendererBase.Version.V1]** as of v4.16.1. V2 (depth-driven PBR mesh
     * lit by ARCore's HDR estimate, type-aware shading, 800 ms scan-in ring) ships in this
     * release as an **opt-in experimental** renderer — on-device QA on a Pixel 9 in v4.16.0
     * showed the V2 visual output not matching the design intent, so the default was reverted
     * to V1 in v4.16.1 while V2 is polished. See
     * [#2203](https://github.com/sceneview/sceneview/issues/2203) for the umbrella.
     *
     * To opt in to V2 (and feed back on the polish work):
     * `ARScene(planeRendererVersion = PlaneRendererBase.Version.V2)`.
     *
     * Changing this value triggers a renderer rebuild (it is wired into the surrounding
     * `remember(...)` keys), so toggling is safe but **not free** — pick once at the
     * composition root.
     */
    planeRendererVersion: PlaneRendererBase.Version = PlaneRendererBase.Version.V1,
    /**
     * Grouped scene-understanding flags (#1767) — mirrors RealityKit's
     * `ARView.environment.sceneUnderstanding.options`. When non-null, the four
     * inner flags (`occlusion`, `lighting`, `physics`, `planeVisualization`)
     * are applied in tandem, overriding the individual scattered flags
     * (`planeRenderer`, [ARCameraStream.isDepthOcclusionEnabled],
     * [io.github.sceneview.ar.light.LightEstimator.isEnabled]).
     *
     * Default `null` keeps every individual flag at its pre-#1767 default —
     * the grouped knob is purely additive and opt-in. Use it when you want a
     * single discoverable point of configuration; keep it null and mutate the
     * individual flags directly if you need fine-grained control.
     */
    sceneUnderstanding: SceneUnderstanding? = null,
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
     *
     * **Soft-deprecated (#1844):** prefer [onSessionFailure] which delivers a sealed
     * [ARSessionFailure] subtype so the compiler enforces exhaustive `when` matching
     * (#1759). The raw callback is kept un-deprecated at the Kotlin level for
     * backwards compatibility — both fire when set — but new code should wire only
     * [onSessionFailure].
     */
    onSessionFailed: ((exception: Exception) -> Unit)? = null,
    /**
     * Typed equivalent of [onSessionFailed] (#1759). Receives an [ARSessionFailure] sealed-class
     * instance instead of a raw [Exception], so the callback can do an exhaustive `when` over
     * every ARCore failure mode (install, permission, camera, cloud-anchor, …) — the compiler
     * catches the day a new failure category is added and the app forgot to handle it.
     *
     * **Both callbacks fire** when set — [onSessionFailed] runs first (raw `Exception`),
     * [onSessionFailure] runs second (mapped). Apps migrating off the raw form can wire the
     * typed callback alongside and gradually delete the legacy one; setting only
     * [onSessionFailure] is the new recommended path.
     */
    onSessionFailure: ((failure: ARSessionFailure) -> Unit)? = null,
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
     * Fires when a requested AR session capability is **not supported on the device** and
     * SceneView silently downgraded it to a working fallback (#2096).
     *
     * Several `Config` knobs are support-gated inside the session pipeline — e.g. an unsupported
     * [depthMode] is reconfigured to [Config.DepthMode.DISABLED], and a [sessionCameraConfig]
     * selector returning a config absent from
     * [Session.getSupportedCameraConfigs][com.google.ar.core.Session.getSupportedCameraConfigs]
     * is ignored in favour of ARCore's default. That keeps the app running on every device, but
     * without this callback the consumer has no way to detect that its request was not honoured —
     * behaviour silently diverges across devices.
     *
     * Each callback delivers an [ARConfigDowngrade] subtype carrying both the `requested` and the
     * `effective` value, so the app can adapt its UI (hide an occlusion toggle, warn the user,
     * report to analytics). It fires once per downgrade detected, right after the session is
     * configured at creation time, on the main thread (consistent with the sibling
     * `onSession*` callbacks). When `null` (default) downgrades stay silent — the callback is
     * purely additive and non-breaking.
     */
    onConfigDowngraded: ((downgrade: ARConfigDowngrade) -> Unit)? = null,
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

    // V1 is the default plane renderer again as of v4.16.1 (#2203). V2 stays as an
    // opt-in experimental renderer pending visual polish.
    val arPlaneRenderer: PlaneRendererBase = remember(engine, materialLoader, scene, planeRendererVersion) {
        when (planeRendererVersion) {
            PlaneRendererBase.Version.V1 -> PlaneRenderer(engine, materialLoader, scene)
            PlaneRendererBase.Version.V2 -> PlaneRendererV2(engine, materialLoader, scene)
        }
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
    val onSessionFailureRef = remember { AtomicReference(onSessionFailure) }
    val onPlaybackFailedRef = remember { AtomicReference(onPlaybackFailed) }
    val onConfigDowngradedRef = remember { AtomicReference(onConfigDowngraded) }
    val onSessionUpdatedRef = remember { AtomicReference(onSessionUpdated) }
    val onTrackingFailureChangedRef = remember { AtomicReference(onTrackingFailureChanged) }
    val sessionConfigurationRef = remember { AtomicReference(sessionConfiguration) }
    val sessionCameraConfigRef = remember { AtomicReference(sessionCameraConfig) }
    val flashModeRef = remember { AtomicReference(flashMode) }

    // Typed Config.*Mode params (#1766) — applied BEFORE sessionConfiguration so the callback
    // still wins. Held in AtomicReferences for the same reactive update pattern as flashMode.
    val planeFindingModeRef = remember { AtomicReference(planeFindingMode) }
    val depthModeRef = remember { AtomicReference(depthMode) }
    val instantPlacementModeRef = remember { AtomicReference(instantPlacementMode) }
    val geospatialModeRef = remember { AtomicReference(geospatialMode) }
    val streetscapeGeometryModeRef = remember { AtomicReference(streetscapeGeometryMode) }
    val cloudAnchorModeRef = remember { AtomicReference(cloudAnchorMode) }
    val augmentedFaceModeRef = remember { AtomicReference(augmentedFaceMode) }
    val imageStabilizationModeRef = remember { AtomicReference(imageStabilizationMode) }
    val semanticModeRef = remember { AtomicReference(semanticMode) }
    val updateModeRef = remember { AtomicReference(updateMode) }
    val focusModeRef = remember { AtomicReference(focusMode) }

    SideEffect {
        onSessionCreatedRef.set(onSessionCreated)
        onSessionResumedRef.set(onSessionResumed)
        onSessionPausedRef.set(onSessionPaused)
        onSessionFailedRef.set(onSessionFailed)
        onSessionFailureRef.set(onSessionFailure)
        onPlaybackFailedRef.set(onPlaybackFailed)
        onConfigDowngradedRef.set(onConfigDowngraded)
        onSessionUpdatedRef.set(onSessionUpdated)
        onTrackingFailureChangedRef.set(onTrackingFailureChanged)
        sessionConfigurationRef.set(sessionConfiguration)
        sessionCameraConfigRef.set(sessionCameraConfig)
        flashModeRef.set(flashMode)
        planeFindingModeRef.set(planeFindingMode)
        depthModeRef.set(depthMode)
        instantPlacementModeRef.set(instantPlacementMode)
        geospatialModeRef.set(geospatialMode)
        streetscapeGeometryModeRef.set(streetscapeGeometryMode)
        cloudAnchorModeRef.set(cloudAnchorMode)
        augmentedFaceModeRef.set(augmentedFaceMode)
        imageStabilizationModeRef.set(imageStabilizationMode)
        semanticModeRef.set(semanticMode)
        updateModeRef.set(updateMode)
        focusModeRef.set(focusMode)
    }

    val prevTrackingFailureRef = remember { AtomicReference<TrackingFailureReason?>(null) }
    val isFrontFaceWindingInvertedRef = remember { AtomicBoolean(false) }

    // Tracks the per-frame [IndirectLight] this composable builds from
    // light-estimation updates so the previous one can be destroyed
    // independently of whatever currently sits on `scene.indirectLight` (#1756).
    //
    // The pre-fix path captured `previousIbl = scene.indirectLight` inside the
    // rebuild block and destroyed it unless it equalled the environment's base
    // IBL. That logic relied on the invariant "scene.indirectLight is either
    // the environment's base or *our* previously-built IBL". A third party
    // (custom node, app code) overwriting `scene.indirectLight` between two
    // estimation updates broke that invariant — the IBL we previously built
    // was then orphaned in native heap with no reference left to destroy it.
    //
    // Caching the IBL we own here makes destruction self-contained: each
    // rebuild destroys exactly the IBL the previous rebuild produced, regardless
    // of what `scene.indirectLight` looks like now. On dispose the cached IBL
    // is freed too — closing the lifecycle leak that the umbrella audit flagged
    // for long AR sessions with intermittent estimation.
    val builtIndirectLightRef = remember { AtomicReference<IndirectLight?>(null) }
    DisposableEffect(engine, builtIndirectLightRef, scene) {
        onDispose {
            // Defensive ordering (#1814): clear the scene's [IndirectLight] reference BEFORE
            // freeing it. The window between [Engine.destroyIndirectLight] and Compose teardown
            // could otherwise have an in-flight `onARFrame` (still queued on the GL thread) walk
            // [scene.indirectLight] and dereference a freed native handle. Setting `null` first
            // unblocks the destroy: Filament's renderer simply skips IBL sampling when the slot
            // is null. If a third party overwrote `scene.indirectLight` with their own resource
            // after we last set it, this null-out replaces *their* reference too — and that is
            // fine: we're tearing the scene down, the slot owner is leaving anyway.
            scene.indirectLight = null
            builtIndirectLightRef.getAndSet(null)?.let {
                engine.safeDestroyIndirectLight(it)
            }
        }
    }

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

    // Mutually exclusive playback inputs (#1770): only one of File or Uri may be set.
    require(playbackDataset == null || playbackDatasetUri == null) {
        "ARSceneView: pass either playbackDataset (File) OR playbackDatasetUri (Uri), not both."
    }
    // Scheme allowlist (#1845) — defense-in-depth. Only content:// and file:// URIs name a
    // playable dataset. Reject any other scheme (https://, data:, …) at the SceneView
    // boundary rather than handing it to ARCore where it would either silently fail or raise
    // an opaque PlaybackFailedException after session.resume(). See the [playbackDatasetUri]
    // KDoc for the caller-side permission requirements (FLAG_GRANT_READ_URI_PERMISSION /
    // takePersistableUriPermission).
    require(isAllowedPlaybackDatasetUri(playbackDatasetUri)) {
        "ARSceneView: playbackDatasetUri scheme '${playbackDatasetUri?.scheme}' is not " +
            "allowed — expected one of $PLAYBACK_DATASET_URI_ALLOWED_SCHEMES."
    }

    val arCore = remember {
        // Snapshotted at first composition. ARCore requires setPlaybackDataset() to be called
        // BEFORE the first resume(), so we capture the param value once when the session is
        // built and ignore later recompositions that mutate it — toggling between live/playback
        // at runtime requires the caller to recreate the ARSceneView (typically via
        // `key(playbackDataset) { ARSceneView(...) }`).
        val initialPlaybackDataset = playbackDataset
        val initialPlaybackDatasetUri = playbackDatasetUri
        ARCore(
            onSessionCreated = { session ->
                cameraStream?.let { session.setCameraTextureNames(it.cameraTextureIds) }
                // Bind the playback source first — ARCore mandates the dataset is set before
                // resume(), and configure() happens here, then resume() runs immediately
                // after this callback returns. File path takes precedence over Uri (the
                // mutually-exclusive `require` above prevents both from being set).
                val dispatchPlaybackFailure: (Exception) -> Unit = { e ->
                    // Prefer the dedicated playback callback when wired (audit #876).
                    // Otherwise fan out to onSessionFailed (raw) + onSessionFailure (typed, #1759).
                    if (onPlaybackFailedRef.get() != null) {
                        onPlaybackFailedRef.get()?.invoke(e)
                    } else {
                        onSessionFailedRef.get()?.invoke(e)
                        onSessionFailureRef.get()?.invoke(ARSessionFailure.from(e))
                    }
                }
                val bindFile: () -> Unit = {
                    initialPlaybackDataset?.let { file ->
                        try {
                            session.setPlaybackDataset(file.absolutePath)
                        } catch (e: PlaybackFailedException) {
                            dispatchPlaybackFailure(e)
                        } catch (e: Exception) {
                            // Defensive — ARCore may throw IllegalStateException if the session
                            // has already been resumed elsewhere. Don't crash; surface to caller.
                            dispatchPlaybackFailure(e)
                        }
                    }
                }
                val bindUri: () -> Unit = {
                    initialPlaybackDatasetUri?.let { uri ->
                        try {
                            session.setPlaybackDatasetUri(uri)
                        } catch (e: PlaybackFailedException) {
                            dispatchPlaybackFailure(e)
                        } catch (e: Exception) {
                            dispatchPlaybackFailure(e)
                        }
                    }
                }
                bindFile()
                bindUri()
                // Capture the requested camera config + depth mode so that — once the session is
                // configured — we can diff them against the values ARCore actually applied and
                // surface any silent support-gated downgrade to `onConfigDowngraded` (#2096).
                val requestedCameraConfig = sessionCameraConfigRef.get()?.invoke(session)
                requestedCameraConfig?.let { session.cameraConfig = it }
                // Snapshot the requested depth mode AFTER the user `sessionConfiguration`
                // callback runs, so a callback-driven override is captured (#2122 / #2096 gap 1).
                // Initialise from the typed param; the configure lambda overwrites this after the
                // callback executes.
                var effectiveRequestedDepthMode: Config.DepthMode? = depthModeRef.get()
                session.configure { config ->
                    // Apply the typed `*Mode` params (#1766) BEFORE the user callback so the
                    // callback still wins. Each param defaults to ARCore's recommended value
                    // (or SceneView's existing default for `updateMode` / `lightEstimationMode`
                    // / `focusMode`), so passing nothing keeps existing behaviour.
                    config.updateMode = updateModeRef.get() ?: Config.UpdateMode.LATEST_CAMERA_IMAGE
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
                    // Apply the caller-requested Flash Mode (#1732). Support is verified inside
                    // ArSession.configure (silently downgraded to OFF if unsupported). Set BEFORE
                    // the user callback so callers can opt back into a different mode.
                    config.flashMode = flashModeRef.get() ?: Config.FlashMode.OFF
                    config.planeFindingMode = planeFindingModeRef.get()
                        ?: Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    // depthMode support-gating is already centralised inside ArSession.configure
                    // (auto-downgrades unsupported requests to DISABLED).
                    config.depthMode = effectiveRequestedDepthMode ?: Config.DepthMode.DISABLED
                    config.instantPlacementMode = instantPlacementModeRef.get()
                        ?: Config.InstantPlacementMode.DISABLED
                    config.geospatialMode = geospatialModeRef.get() ?: Config.GeospatialMode.DISABLED
                    config.streetscapeGeometryMode = streetscapeGeometryModeRef.get()
                        ?: Config.StreetscapeGeometryMode.DISABLED
                    config.cloudAnchorMode = cloudAnchorModeRef.get() ?: Config.CloudAnchorMode.DISABLED
                    config.augmentedFaceMode = augmentedFaceModeRef.get()
                        ?: Config.AugmentedFaceMode.DISABLED
                    config.imageStabilizationMode = imageStabilizationModeRef.get()
                        ?: Config.ImageStabilizationMode.OFF
                    config.semanticMode = semanticModeRef.get() ?: Config.SemanticMode.DISABLED
                    config.focusMode = focusModeRef.get() ?: Config.FocusMode.AUTO
                    sessionConfigurationRef.get()?.invoke(session, config)
                    // Capture the post-callback depth mode — this is what the consumer
                    // ultimately requested (the callback may have overridden the typed param).
                    effectiveRequestedDepthMode = config.depthMode.takeIf {
                        it != Config.DepthMode.DISABLED
                    }
                }
                // Surface any silent support-gated downgrade (#2096). The session has just been
                // configured: diff the requested camera config / depth mode against what ARCore
                // actually applied and notify the consumer once per downgrade. Runs on the same
                // (main) thread as the sibling `onSessionCreated` dispatch below.
                //
                // `effectiveRequestedDepthMode` now reflects the post-callback requested value
                // (#2122 gap 1). `session.cameraConfig` is what ARCore last committed; comparing
                // it against `requestedCameraConfig` (the selector's return value, set before
                // configure) detects a camera-config mismatch (#2122 gap 2 — limited to the
                // selector-driven path; callback-driven camera config changes are not tracked
                // because ARCore validates on assignment, not silently post-configure).
                onConfigDowngradedRef.get()?.let { onDowngraded ->
                    detectConfigDowngrades(
                        requestedDepthMode = effectiveRequestedDepthMode,
                        effectiveDepthMode = session.config.depthMode,
                        requestedCameraConfig = requestedCameraConfig,
                        effectiveCameraConfig = session.cameraConfig
                    ).forEach(onDowngraded)
                }
                cameraStream?.let { scene.addEntity(it.entity) }
                onSessionCreatedRef.get()?.invoke(session)
            },
            onSessionResumed = { session ->
                // Honour the typed `focusMode` param (#1766) — previously this was force-set
                // to AUTO on every resume; that overrode any caller opt-in to FIXED for sharp
                // far-field tracking. Falls back to AUTO for parity with the prior behaviour.
                session.configure { config ->
                    config.focusMode = focusModeRef.get() ?: Config.FocusMode.AUTO
                }
                onSessionResumedRef.get()?.invoke(session)
            },
            onSessionPaused = { session ->
                onSessionPausedRef.get()?.invoke(session)
            },
            onArSessionFailed = { exception ->
                // Dual dispatch (#1759): raw callback first for backwards compat, then the
                // typed one so apps can wire either or both.
                onSessionFailedRef.get()?.invoke(exception)
                onSessionFailureRef.get()?.invoke(ARSessionFailure.from(exception))
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

    // ── Flash mode reactivity (#1732) ─────────────────────────────────────────────────────────────
    //
    // Allow apps to toggle the torch by recomposing with a new `flashMode` value. The session is
    // reconfigured only when the actual value changes, so flipping unrelated state does not pay
    // for an ARCore `configure()` call. Support gating + front-camera downgrade is centralised
    // inside `ArSession.configure()`.
    LaunchedEffect(flashMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.flashMode != flashMode) {
            session.configure { config -> config.flashMode = flashMode }
        }
    }

    // ── Typed Config.*Mode reactivity (#1766) ─────────────────────────────────────────────────────
    //
    // Apps that flip these via Compose state (e.g. a "toggle depth occlusion" switch) get a live
    // reconfigure without having to recreate the ARSceneView. Each effect keys on a single param,
    // and the equality guard skips a JNI `configure()` round-trip when the param re-emits with the
    // same value (idempotent recompose).
    LaunchedEffect(planeFindingMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.planeFindingMode != planeFindingMode) {
            session.configure { config -> config.planeFindingMode = planeFindingMode }
        }
    }
    LaunchedEffect(depthMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.depthMode != depthMode) {
            session.configure { config -> config.depthMode = depthMode }
        }
    }
    LaunchedEffect(instantPlacementMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.instantPlacementMode != instantPlacementMode) {
            session.configure { config -> config.instantPlacementMode = instantPlacementMode }
        }
    }
    LaunchedEffect(geospatialMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.geospatialMode != geospatialMode) {
            session.configure { config -> config.geospatialMode = geospatialMode }
        }
    }
    LaunchedEffect(streetscapeGeometryMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.streetscapeGeometryMode != streetscapeGeometryMode) {
            session.configure { config -> config.streetscapeGeometryMode = streetscapeGeometryMode }
        }
    }
    LaunchedEffect(cloudAnchorMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.cloudAnchorMode != cloudAnchorMode) {
            session.configure { config -> config.cloudAnchorMode = cloudAnchorMode }
        }
    }
    LaunchedEffect(augmentedFaceMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.augmentedFaceMode != augmentedFaceMode) {
            session.configure { config -> config.augmentedFaceMode = augmentedFaceMode }
        }
    }
    LaunchedEffect(imageStabilizationMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.imageStabilizationMode != imageStabilizationMode) {
            session.configure { config -> config.imageStabilizationMode = imageStabilizationMode }
        }
    }
    LaunchedEffect(semanticMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.semanticMode != semanticMode) {
            session.configure { config -> config.semanticMode = semanticMode }
        }
    }
    LaunchedEffect(updateMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.updateMode != updateMode) {
            session.configure { config -> config.updateMode = updateMode }
        }
    }
    LaunchedEffect(focusMode) {
        val session = arCore.session ?: return@LaunchedEffect
        if (session.config.focusMode != focusMode) {
            session.configure { config -> config.focusMode = focusMode }
        }
    }

    // ── Scene / camera / environment setup ───────────────────────────────────────────────────────

    val nodeManager = remember(scene, collisionSystem) { SceneNodeManager(scene, collisionSystem) }

    // Baseline IBL + skybox setup. Applied ONCE per `environment` instance change,
    // not on every recomposition (#1611). The per-frame `onARFrame` rebuild path
    // overwrites `scene.indirectLight` with a fresh IBL each time ARCore surfaces
    // a new light estimate; running this initialiser inside `SideEffect` instead
    // would reset the rebuilt IBL on the *next* recomposition (which fires every
    // frame in demos that surface `latestFrame` / `isTracking` to UI state) and
    // collapse the scene back to the neutral baseline — which on KTX1-loaded IBLs
    // is reflections-mostly with limited diffuse SH, producing visible-but-dim
    // metals at best and flat-black models at worst. `LaunchedEffect(environment)`
    // restores the baseline only when the env instance actually changes (initial
    // composition, environment swap by the caller).
    LaunchedEffect(environment, scene) {
        scene.indirectLight = environment.indirectLight
        scene.skybox = environment.skybox
    }

    SideEffect {
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

    // ── Grouped scene-understanding flags (#1767) ────────────────────────────────────────────────
    //
    // When `sceneUnderstanding` is provided, fan out its four flags to the four
    // scattered owners in tandem. Applied AFTER the individual `planeRenderer`
    // SideEffect above so the grouped knob wins on every recomposition — that
    // matches the data class contract documented in [SceneUnderstanding].
    //
    // `physics` is reserved (no current backing implementation, see KDoc on
    // [SceneUnderstanding.physics]). When the underlying ARCore Scene Semantics
    // / Mesh path lands (#1760, #1761), this is the canonical place to wire it.
    SideEffect {
        sceneUnderstanding?.let { su ->
            arPlaneRenderer.isEnabled = su.planeVisualization
            lightEstimator.isEnabled = su.lighting
            cameraStream?.isDepthOcclusionEnabled = su.occlusion
            // su.physics: no-op today; wired when #1760/#1761 land.
        }
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
                                    builtIndirectLightRef = builtIndirectLightRef,
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
    arPlaneRenderer: PlaneRendererBase,
    childNodes: List<Node>,
    prevTrackingFailureRef: AtomicReference<TrackingFailureReason?>,
    onTrackingFailureChangedRef: AtomicReference<((TrackingFailureReason?) -> Unit)?>,
    onSessionUpdatedRef: AtomicReference<((Session, Frame) -> Unit)?>,
    baselineMainLightColorRef: AtomicReference<io.github.sceneview.math.Color?>,
    baselineMainLightIntensityRef: AtomicReference<Float?>,
    builtIndirectLightRef: AtomicReference<IndirectLight?>,
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
        // #1756: only rebuild the per-frame IBL when the estimator actually
        // surfaced new data this frame. `LightEstimator.update()` already
        // returns `null` when ARCore's `LightEstimate.timestamp` hasn't
        // advanced, so we're inside the `?.let { estimation ->` block —
        // therefore the estimation IS fresh. The pure decision logic for
        // "which texture/SH source do we use" is centralised in
        // [pickIndirectLightSources] so it can be exercised without a
        // Filament engine (see `IndirectLightRebuildDecisionTest`).
        //
        // #1611: skip the rebuild entirely when the resulting IBL would be
        // incomplete — i.e. either irradiance or reflections cannot be sourced
        // from estimation AND a baseline fallback is not available. Baseline
        // KTX1-loaded IBLs typically expose SH coefficients via the native
        // handle (no `getIrradianceTexture()`) so the legacy fallback
        // `baseline.irradianceTexture` returned null on them — and the rebuilt
        // IBL ended up with an explicit empty irradiance source, which
        // Filament treats as "no diffuse IBL". The visible symptom on Pixel 9
        // was placed PBR models rendering as flat-black silhouettes during
        // every transient frame where ARCore surfaced reflections only or
        // irradiance only. The new gate keeps `scene.indirectLight` on the
        // last good build (which falls back to `environment.indirectLight`
        // until the first FULL estimate lands), so partial estimations no
        // longer collapse the scene to black.
        val indirectLight = environment.indirectLight
        if (shouldRebuildIndirectLight(estimation, indirectLight)) {
            val sources = pickIndirectLightSources(estimation, indirectLight)
            val newIbl = IndirectLight.Builder().apply {
                if (sources.useEstimationIrradiance) {
                    estimation.irradiance?.let { irradiance(3, it) }
                } else {
                    indirectLight?.irradianceTexture?.let { irradiance(it) }
                }
                if (sources.useEstimationReflections) {
                    estimation.reflections?.let { reflections(it) }
                } else {
                    indirectLight?.reflectionsTexture?.let { reflections(it) }
                }
                indirectLight?.intensity?.let { intensity(it) }
                indirectLight?.getRotation(null)?.let { rotation(it) }
            }.build(engine)
            scene.indirectLight = newIbl
            // #1756: destroy the IBL we built on the previous estimation update
            // (tracked via [builtIndirectLightRef]) — independent of what
            // `scene.indirectLight` happens to be now. The old path captured
            // `scene.indirectLight` and destroyed it unless it matched the
            // environment's base IBL; a third party overwriting `scene.indirectLight`
            // between updates orphaned our previously-built IBL in native heap.
            // Self-contained ownership tracking closes that leak (the previous
            // IBL is always destroyed exactly once, when superseded or on dispose).
            builtIndirectLightRef.getAndSet(newIbl)?.let { previousBuiltIbl ->
                engine.safeDestroyIndirectLight(previousBuiltIbl)
            }
        }
    }

    arPlaneRenderer.update(session, frame)

    // Single-pass dispatch (#1810): the previous `filterIsInstance<PoseNode>().forEach { }` +
    // `filterIsInstance<DepthMeshNode>().forEach { }` allocated two fresh ArrayLists every frame
    // (~240 list allocations/sec at 60 fps on the render thread). A single `for` loop with a
    // `when` type-check is zero-allocation and walks the child list once.
    for (n in childNodes) when (n) {
        is PoseNode -> n.update(session, frame)
        is DepthMeshNode -> n.update(session, frame)
        is PointCloudNode -> n.update(session, frame)
    }

    val newTrackingFailure = if (!isCameraTracking) {
        camera.trackingFailureReason.takeIf { it != TrackingFailureReason.NONE }
    } else null

    if (prevTrackingFailureRef.get() != newTrackingFailure) {
        prevTrackingFailureRef.set(newTrackingFailure)
        onTrackingFailureChangedRef.get()?.invoke(newTrackingFailure)
    }

    onSessionUpdatedRef.get()?.invoke(session, frame)
}

/**
 * Pure decision-logic helper for the per-frame `IndirectLight` rebuild (#1756).
 *
 * Given a fresh [LightEstimator.Estimation] and the environment's base
 * [IndirectLight] (may be null), picks whether each IBL source — irradiance and
 * reflections — should come from the live ARCore estimate or fall back to the
 * baked environment baseline.
 *
 * Extracted out of `onARFrame` so the (otherwise Filament-laden) rebuild block
 * has a pure-Kotlin core that can be exercised under JVM unit tests — see
 * `IndirectLightRebuildDecisionTest`. The actual Filament `IndirectLight.Builder`
 * call still lives in `onARFrame` because the builder needs a live engine.
 *
 * Rules:
 *  - **Irradiance**: use the estimation's spherical-harmonics coefficients if
 *    present; otherwise fall back to the base IBL's irradiance texture.
 *  - **Reflections**: use the estimation's cubemap if present; otherwise fall
 *    back to the base IBL's reflections texture.
 *
 * @param estimation the current frame's estimate (never null inside the
 *   rebuild path — `LightEstimator.update` already returned non-null).
 * @param baseIndirectLight the environment's static IBL or null.
 * @return [IndirectLightSources] flagging which source to use per channel.
 */
internal fun pickIndirectLightSources(
    estimation: LightEstimator.Estimation,
    baseIndirectLight: IndirectLight?
): IndirectLightSources {
    val hasIrradiance = estimation.irradiance != null
    val hasReflections = estimation.reflections != null
    return IndirectLightSources(
        useEstimationIrradiance = hasIrradiance,
        useEstimationReflections = hasReflections,
        hasBaseIndirectLight = baseIndirectLight != null
    )
}

/**
 * Returns `true` when the per-frame `IndirectLight` rebuild has enough source
 * data to produce a visually complete IBL (#1611).
 *
 * The rebuild path at [onARFrame] composes a new IBL from a mix of ARCore's
 * `LightEstimate` (irradiance SH + reflections cubemap) and the environment's
 * baseline `IndirectLight`. When neither side provides usable data for one of
 * the two channels, the resulting IBL has an empty source for that channel,
 * which Filament renders as "no IBL contribution" — diffuse base color goes
 * black on PBR materials and the placed model reads as a flat silhouette
 * against the camera feed.
 *
 * Skip the rebuild instead. `scene.indirectLight` then keeps whatever the
 * last good rebuild produced (or the environment baseline, set once per
 * `environment` change inside [ARSceneView]). The next ARCore estimate that
 * brings either channel back to a workable state triggers a fresh, complete
 * rebuild.
 *
 * Specifically:
 *  - **Irradiance is sourceable** when the estimation has SH coefficients, OR
 *    the baseline exposes an `irradianceTexture`. KTX1-loaded IBLs typically
 *    expose SH via the native handle (no `irradianceTexture`), so this gates
 *    fallback only on data Filament can actually consume.
 *  - **Reflections is sourceable** when the estimation has a freshly-uploaded
 *    cubemap, OR the baseline exposes a `reflectionsTexture` (the KTX1
 *    cubemap mip chain, present in the default `rememberAREnvironment`).
 *
 * Pure decision logic; extracted out of [onARFrame] so the rule can be pinned
 * by `IndirectLightRebuildDecisionTest` without spinning up an engine. The
 * texture nullity check requires a real `IndirectLight`, so the JVM matrix
 * exercises the "no base IBL" path only — the "base IBL with one texture"
 * branches are pinned by an instrumented test on a live engine.
 *
 * @param estimation the current frame's estimate (must be non-null — caller
 *   already entered the `?.let { estimation ->` block).
 * @param baseIndirectLight the environment's baseline IBL, may be null.
 * @return `true` if a rebuild would produce a complete IBL.
 */
internal fun shouldRebuildIndirectLight(
    estimation: LightEstimator.Estimation,
    baseIndirectLight: IndirectLight?
): Boolean {
    val irradianceAvailable = estimation.irradiance != null ||
        baseIndirectLight?.irradianceTexture != null
    val reflectionsAvailable = estimation.reflections != null ||
        baseIndirectLight?.reflectionsTexture != null
    // Require at least one fresh estimation channel — otherwise rebuilding
    // simply duplicates the baseline, wasting an `IndirectLight.Builder.build()`
    // call per frame plus the destroy on the next update.
    val anyFresh = estimation.irradiance != null || estimation.reflections != null
    return anyFresh && irradianceAvailable && reflectionsAvailable
}

/**
 * Decision record returned by [pickIndirectLightSources] (#1756).
 *
 * Plain data so unit tests can assert exact flag values without spinning up a
 * Filament engine. [hasBaseIndirectLight] mirrors the env-IBL presence so the
 * test can pin the precedence rules (estimation > base > unset).
 */
internal data class IndirectLightSources(
    val useEstimationIrradiance: Boolean,
    val useEstimationReflections: Boolean,
    val hasBaseIndirectLight: Boolean
)

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

/**
 * Picks a FRONT-facing [CameraConfig] for the [session].
 *
 * **Required for Augmented Faces.** Passing `Session.Feature.FRONT_CAMERA` to the ARCore
 * [Session] constructor only makes the front camera *eligible* — it does NOT switch the camera.
 * ARCore keeps the session on its default BACK config until [Session.setCameraConfig] is called
 * with a config whose [CameraConfig.getFacingDirection] is
 * [FRONT][CameraConfig.FacingDirection.FRONT]. [ARSceneView]'s default `sessionCameraConfig` is
 * [highestResolutionCameraConfig], which is **BACK-facing** — so an Augmented Faces scene that
 * only sets `sessionFeatures = setOf(Session.Feature.FRONT_CAMERA)` ends up running the back
 * camera, `AugmentedFaceMode.MESH3D` produces no trackables, and no face mesh ever appears.
 *
 * Pass this helper as `sessionCameraConfig` whenever the session enables the front camera:
 *
 * ```kotlin
 * ARSceneView(
 *     sessionFeatures = setOf(Session.Feature.FRONT_CAMERA),
 *     sessionCameraConfig = ::frontCameraConfig,
 *     sessionConfiguration = { _, config ->
 *         config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
 *     },
 * )
 * ```
 *
 * Falls back to the session's current [CameraConfig][Session.getCameraConfig] when ARCore
 * exposes no FRONT-facing config (e.g. a device without a front camera) or when
 * [Session.getSupportedCameraConfigs] throws — the call must never crash session creation.
 *
 * @param session The ARCore [Session] being configured. Must have been created with
 *                [Session.Feature.FRONT_CAMERA] for a FRONT config to be available.
 * @return The chosen FRONT-facing [CameraConfig]; never throws.
 */
fun frontCameraConfig(session: Session): CameraConfig =
    runCatching {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.FRONT)
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
@Deprecated("Use ARSceneView instead", ReplaceWith("ARSceneView(modifier, surfaceType, engine, modelLoader, materialLoader, environmentLoader, sessionFeatures, playbackDataset, sessionCameraConfig, flashMode, sessionConfiguration, planeRenderer, planeRendererVersion, cameraStream, view, isOpaque, renderer, scene, environment, mainLightNode, fillLightNode, cameraNode, cameraExposure, collisionSystem, viewNodeWindowManager, onSessionCreated, onSessionResumed, onSessionPaused, onSessionFailed, onPlaybackFailed, onSessionUpdated, onTrackingFailureChanged, onGestureListener, onTouchEvent, permissionHandler, lifecycle, content)"))
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
    planeRendererVersion: PlaneRendererBase.Version = PlaneRendererBase.Version.V1,
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
    flashMode = flashMode,
    sessionConfiguration = sessionConfiguration,
    planeRenderer = planeRenderer,
    planeRendererVersion = planeRendererVersion,
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
