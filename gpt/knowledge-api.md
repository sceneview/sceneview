<!--
  GENERATED FILE — DO NOT EDIT.
  Source of truth: /llms.txt  (SceneView 4.25.0)
  Regenerate:      node tools/generate-gpt-knowledge.js
  Drift is caught in CI (ci.yml -> repo-hygiene). Edit llms.txt instead.
  See issue #2724.
-->

# SceneView — API Reference

> Composables, node types, resource loading, camera, math, and per-platform APIs.
> Auto-generated from `llms.txt` (SceneView 4.25.0). This is a slice of the machine-readable API reference — the same content an AI reads to generate SceneView code.

## Core Composables

### SceneView — 3D viewport

Full signature:
```kotlin
@Composable
fun SceneView(
    modifier: Modifier = Modifier,
    surfaceType: SurfaceType = SurfaceType.Surface,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    view: View = rememberView(engine),
    isOpaque: Boolean = true,
    renderQuality: RenderQuality = RenderQuality.Default,   // Cinematic / Default / Performance — see "Render Quality"
    autoCenterContent: Boolean = true,   // library-level auto-center — see note below
    autoFitContent: Boolean = false,     // auto-frame camera to content — see "Auto-fit camera framing"
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    fillLightNode: LightNode? = rememberFillLightNode(engine),  // dual-light default — soft opposite fill
    cameraNode: CameraNode = rememberCameraNode(engine),
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    cameraManipulator: CameraGestureDetector.CameraManipulator? = rememberCameraManipulator(cameraNode.worldPosition),
    viewNodeWindowManager: ViewNode.WindowManager? = null,
    surfaceMirrorer: SurfaceMirrorer? = null,   // In-app video recording (no MediaProjection) — see "Record the scene to MP4"
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    activity: ComponentActivity? = LocalContext.current as? ComponentActivity,
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
    content: (@Composable SceneScope.() -> Unit)? = null
)
```

**Defaults changed in v4.0.10+:** shadows ON (mainLight + fillLight), SSAO + bloom enabled, neutral exposure (~1.0), dual-light setup out-of-the-box. No more "flat-lit chrome look" — drop a model in and it renders cinematic by default.

**`autoCenterContent` (default `true`):** all DSL `content` nodes are parented to an intermediate content-root node which the library translates once — on the first frame their union bounding box is non-empty — so the content centroid lands on the **world origin** and renders centred without per-node `ModelNode(centerOrigin = …)`. It lands on the origin, **not** on the camera manipulator's `targetPosition` — the two coincide only for the default target, which is why the camera-to-subject distance is `\|orbitHomePosition\|` (see "Camera"). Lights / camera are `SceneView` parameters (never DSL children) so they stay put. Pass `autoCenterContent = false` for scenes with intentional off-centre placement — authored world positions then survive. Mirrors the iOS `autoCenterContent` modifier.

Minimal usage:
```kotlin
@Composable
fun My3DScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator(),
        environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
                ?: createEnvironment(environmentLoader)
        },
        mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f }
    ) {
        rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
            ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
        }
    }
}
```

### ARSceneView — AR viewport

Full signature:
```kotlin
@Composable
fun ARSceneView(
    modifier: Modifier = Modifier,
    surfaceType: SurfaceType = SurfaceType.Surface,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    sessionFeatures: Set<Session.Feature> = setOf(),
    playbackDataset: File? = null,                 // Replay an MP4 recorded via ARRecorder. See "AR Recording & Playback".
    playbackDatasetUri: android.net.Uri? = null,   // Scoped-storage replay (content:// or file://) — mutually exclusive with playbackDataset (#1845).
    sessionCameraConfig: ((Session) -> CameraConfig)? = ::highestResolutionCameraConfig,  // Picks the highest-res 30fps BACK config so ARRecorder records at full resolution (#1065). Pass null for ARCore's stock default, ::frontCameraConfig for Augmented Faces, or `cameraConfigFilter { ... }` for a DSL-built selector (#1733).
    flashMode: Config.FlashMode = Config.FlashMode.OFF,  // ARCore v1.45+ Flash Mode — OFF/TORCH. Reactive; unsupported devices/front-camera silently downgrade to OFF (#1732).
    // Typed Config.*Mode DSL params (#1766) — applied BEFORE sessionConfiguration so the callback still wins. All reactive.
    planeFindingMode: Config.PlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
    depthMode: Config.DepthMode = Config.DepthMode.DISABLED,                                   // Support-gated; auto-downgrades to DISABLED.
    instantPlacementMode: Config.InstantPlacementMode = Config.InstantPlacementMode.DISABLED,
    geospatialMode: Config.GeospatialMode = Config.GeospatialMode.DISABLED,                    // Needs Cloud key + ACCESS_FINE_LOCATION.
    streetscapeGeometryMode: Config.StreetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED,  // Needs geospatialMode = ENABLED.
    cloudAnchorMode: Config.CloudAnchorMode = Config.CloudAnchorMode.DISABLED,                 // Needs Cloud key.
    augmentedFaceMode: Config.AugmentedFaceMode = Config.AugmentedFaceMode.DISABLED,           // Needs Session.Feature.FRONT_CAMERA.
    imageStabilizationMode: Config.ImageStabilizationMode = Config.ImageStabilizationMode.OFF,
    semanticMode: Config.SemanticMode = Config.SemanticMode.DISABLED,
    updateMode: Config.UpdateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE,
    focusMode: Config.FocusMode = Config.FocusMode.AUTO,
    sessionConfiguration: ((session: Session, Config) -> Unit)? = null,  // Escape hatch — runs AFTER all typed params above.
    planeRenderer: Boolean = true,
    planeRendererVersion: PlaneRendererBase.Version = PlaneRendererBase.Version.V1,  // v4.16.1: V1 restored as default — V2 (#2203) shipped briefly as default in v4.16.0 but visual output did not match design intent on real devices. V2 stays available as experimental opt-in (`Version.V2`) while it's polished.
    sceneUnderstanding: SceneUnderstanding? = null,  // v4.10.0+ — grouped occlusion/lighting/physics/planeVisualization (#1767, RealityKit parity). null = use the individual flags.
    cameraStream: ARCameraStream? = rememberARCameraStream(materialLoader),
    view: View = rememberARView(engine),
    renderQuality: RenderQuality? = null,   // v4.19.0+ (#2524) — one-line preset; NULLABLE: null keeps createARView's camera-feed-tuned defaults (no SSAO/bloom). Pass Performance/Cinematic to opt in. Filmic AR tone mapper preserved.
    isOpaque: Boolean = true,
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberAREnvironment(engine),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    fillLightNode: LightNode? = rememberFillLightNode(engine),  // v4.3.0+ — dual-light AR baseline; pass null for single-light
    cameraNode: ARCameraNode = rememberARCameraNode(engine),
    cameraExposure: Float? = null,  // Filament absolute exposure scale (1.0 ≈ ISO 100). NOT EV stops; negative = black framebuffer (#1179). Prefer null.
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    viewNodeWindowManager: ViewNode.WindowManager? = null,
    onSessionCreated: ((session: Session) -> Unit)? = null,
    onSessionResumed: ((session: Session) -> Unit)? = null,
    onSessionPaused: ((session: Session) -> Unit)? = null,
    onSessionFailed: ((exception: Exception) -> Unit)? = null,
    onSessionFailure: ((failure: ARSessionFailure) -> Unit)? = null,  // Typed sealed-class equivalent — see "Error Handling" (#1759).
    onPlaybackFailed: ((exception: Exception) -> Unit)? = null,       // Dataset-replay failures (bad MP4 path / unreadable dataset) — see "AR Recording & Playback".
    onConfigDowngraded: ((downgrade: ARConfigDowngrade) -> Unit)? = null,  // A requested capability was silently downgraded — see "ARConfigDowngrade" (#2096).
    onSessionUpdated: ((session: Session, frame: Frame) -> Unit)? = null,
    onTrackingFailureChanged: ((trackingFailureReason: TrackingFailureReason?) -> Unit)? = null,
    surfaceMirrorer: SurfaceMirrorer? = null,   // In-app video recording of the composited AR scene — see "Record the scene to MP4"
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    permissionHandler: ARPermissionHandler? = null,   // omit for auto-detect from ComponentActivity; passing null explicitly SKIPS permission checks
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    content: (@Composable ARSceneScope.() -> Unit)? = null
)
```

**Note — `renderQuality` on `ARSceneView` is NULLABLE (#2524, v4.19.0+).** Unlike the 3D
`SceneView` (where it defaults to `RenderQuality.Default`), the AR composable defaults
`renderQuality` to **`null`** so existing AR scenes keep the camera-feed-tuned defaults from
`createARView` (minimal post-processing — no SSAO/bloom — over the live feed). Pass a preset
explicitly to opt in, e.g. `ARSceneView(renderQuality = RenderQuality.Performance) { }` for
battery-sensitive overlays. The Filmic AR tone mapper that round-trips the camera background
(#1434) survives every preset. You can still tune imperatively via
`view.applyRenderQuality(...)` on the `rememberARView(engine)` instance (see "Render Quality").

Minimal usage:
```kotlin
@Composable
fun MyARScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        // Typed Config.*Mode DSL params (#1766) — preferred over a sessionConfiguration callback
        // for the common cases. Each is reactive; flipping them via Compose state recomposes
        // the AR session config.
        depthMode = Config.DepthMode.AUTOMATIC,
        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
        // ENVIRONMENTAL_HDR is the v4.3.0+ default LightEstimationMode — front-camera sessions
        // are clamped to DISABLED inside ARSession.configure regardless. The sessionConfiguration
        // callback remains the escape hatch for any Config property without a typed param.
        // sessionConfiguration = { session, config -> /* raw access here if needed */ },
        onSessionCreated = { session -> /* ARCore session ready */ },
        onSessionResumed = { session -> /* session resumed */ },
        onSessionFailed = { exception -> /* ARCore init error — show fallback UI */ },
        onSessionUpdated = { session, frame -> /* per-frame AR logic */ },
        onTrackingFailureChanged = { reason -> /* camera tracking lost/restored */ }
    ) {
        // ARSceneScope DSL here — AnchorNode, AugmentedImageNode, etc.
    }
}
```

### Tracking-failure messages (user-facing strings)

`TrackingFailureReason` carries the *why* of a tracking loss — surface it to the user with an actionable hint, not a silent black screen. This snippet is self-contained (the `android-demo` sample ships the same mapping, localised, as `demo/common/TrackingFailureMessages.kt` — copy that file if you want string resources):

```kotlin
var failure by remember { mutableStateOf<TrackingFailureReason?>(null) }

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    onTrackingFailureChanged = { failure = it },
)

// In a status banner — map every reason to an actionable hint:
val hint = when (failure) {
    TrackingFailureReason.BAD_STATE -> "AR session error — try restarting"
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Not enough light — try a brighter area"
    TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast — slow down"
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Not enough detail — point at a more textured surface"
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable — close other camera apps"
    TrackingFailureReason.NONE, null -> null
}
Text(hint ?: "Point your camera at a flat surface")
```

| Reason                     | User-facing hint                                          |
|----------------------------|-----------------------------------------------------------|
| `BAD_STATE`                | AR session error — try restarting the demo                |
| `INSUFFICIENT_LIGHT`       | Not enough light — try a brighter area                    |
| `EXCESSIVE_MOTION`         | Moving too fast — slow down                               |
| `INSUFFICIENT_FEATURES`    | Not enough detail — point at a more textured surface      |
| `CAMERA_UNAVAILABLE`       | Camera unavailable — close other camera apps              |
| `NONE` / `null`            | (helper returns `null` — caller picks the idle hint)      |

Ported from arcore-android-sdk's `common/helpers/TrackingStateHelper.java`.

### PlaneDiscoveryGuide — plane-discovery onboarding overlay (#2241, v4.19+)

The built-in, user-tested onboarding for the "move your phone until a plane is found" phase —
a port of ARCore Elements' `PlaneDiscoveryGuide` state machine. Prefer it over hand-rolling
banners from `onTrackingFailureChanged`: it layers the timed hand-hint, help affordance and
contextual failure copy for you, and auto-dismisses on the first tracked plane.

Timeline (defaults, all tunable via `PlaneDiscoveryGuideDurations`): 0–3 s silent → animated
hand hint + "Move your phone to find a surface" pill → "Need help?" button at 8 s (built-in tip
card, or your `onHelp`) → 750 ms fade-out latched on the first tracked plane (never re-onboards
within the session). While tracking is lost with an actionable reason it shows the contextual
copy from the table above instead.

`PlaneDiscoveryGuide` is a `BoxScope` composable — declare it as a SIBLING of `ARSceneView`
inside a `Box`, feeding it plain booleans (no ARCore objects → previewable without a session):

```kotlin
var cameraReady by remember { mutableStateOf(false) }
var isTracking by remember { mutableStateOf(false) }
var anyPlaneTracked by remember { mutableStateOf(false) }
var failure by remember { mutableStateOf<TrackingFailureReason?>(null) }

Box {
    ARSceneView(
        onSessionUpdated = { session, frame ->
            cameraReady = true
            isTracking = frame.camera.trackingState == TrackingState.TRACKING
            anyPlaneTracked = session.getAllTrackables(Plane::class.java)
                .any { it.trackingState == TrackingState.TRACKING }
        },
        onTrackingFailureChanged = { failure = it },
    ) { /* nodes */ }

    PlaneDiscoveryGuide(
        cameraReady = cameraReady,
        isTracking = isTracking,
        anyPlaneTracked = anyPlaneTracked,
        trackingFailureReason = failure,
    )
}
```

Hoisted-state escape hatch: drive `rememberPlaneDiscoveryGuideState()` yourself and render
`PlaneDiscoveryGuideOverlay(phase = state.phase, durations = state.durations, …)` — pass the
STATE's durations, not a fresh copy, or the visual fade desynchronizes from the state machine.

Platform matrix: **iOS** — use `ARSceneView(showCoachingOverlay: true)` (Apple's first-party
`ARCoachingOverlayView` IS the onboarding; no port needed). **Web** — coming soon.

### Scene Understanding — grouped AR rendering flags

`SceneUnderstanding` (`io.github.sceneview.ar.scene.SceneUnderstanding`, v4.10.0+, #1767) groups four scattered AR rendering flags into a single discoverable knob — mirrors RealityKit's `ARView.environment.sceneUnderstanding.options`. Pass it via `ARSceneView(sceneUnderstanding = ...)`; null (default) keeps every individual flag at its current default.

```kotlin
data class SceneUnderstanding(
    val occlusion: Boolean = true,        // ARCameraStream.isDepthOcclusionEnabled
    val lighting: Boolean = true,         // LightEstimator.isEnabled
    val physics: Boolean = false,         // Reserved — Scene Semantics / Mesh, #1760/#1761
    val planeVisualization: Boolean = true // PlaneRenderer.isEnabled
)
```

Named constants: `SceneUnderstanding.Full` (everything on), `SceneUnderstanding.Minimal` (lighting only, low-CPU / no depth), `SceneUnderstanding.None` (everything off — pass-through camera feed).

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    sceneUnderstanding = SceneUnderstanding.Full,
    sessionConfiguration = { _, config ->
        // Occlusion still needs DepthMode opt-in — SceneView does NOT auto-set this.
        config.depthMode = Config.DepthMode.AUTOMATIC
    }
) { /* ... */ }
```

Equivalent fine-grained mutation (still works — `sceneUnderstanding` is purely additive):

```kotlin
val cameraStream = rememberARCameraStream(materialLoader)
ARSceneView(
    cameraStream = cameraStream,
    planeRenderer = true,
) { /* ... */ }
// Imperative: cameraStream.isDepthOcclusionEnabled = true
```

When `sceneUnderstanding` is non-null, its four flags override the individual ones on every recomposition. When null, the individual flags retain their pre-#1767 defaults verbatim.

### Plane rendering — V1 default (proven) + V2 opt-in experimental (#2203)

Two plane-renderer implementations ship side-by-side, selectable via the `planeRendererVersion` parameter on `ARSceneView` (and on the legacy `ARScene` alias). **V1 is the default** — flat polygon textured with a procedural soft grid, battle-tested. **V2 is opt-in experimental**: v4.16.0 briefly shipped V2 as the default but on-device QA showed the visual output not matching the design intent (washed-out grid sheet on real surfaces, missing the promised HDR reflection + relief). v4.16.1 reverted the default to V1 while V2 is polished. The V2 code remains in the codebase for early adopters who want to help shape the redesign.

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    // Default is V1 — this line is redundant, shown for clarity.
    planeRendererVersion = PlaneRendererBase.Version.V1,
) { /* ... */ }

// Opt in to the experimental V2:
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    planeRendererVersion = PlaneRendererBase.Version.V2,
) { /* ... */ }
```

The V2 path (depth-driven mesh + PBR + HDR cubemap reflection + type-aware shading + scan-in) is implemented but its visual output does not yet match a polished AR product. See [#2203](https://github.com/sceneview/sceneview/issues/2203) for the umbrella + research notes (`.claude/plans/v2-references-study.md`, `v2-google-ar-catalog.md`, `v2-non-google-catalog.md` — the comparative study of how Google ARCore Depth Lab, Apple ARKit / RoomPlan, Niantic Lightship, Snap Lens Studio handle plane visualization). The honest finding: the industry minimizes plane decoration in favour of *making virtual content respect the real geometry*. The V2 redesign will likely follow that path rather than pushing PBR onto the plane itself.

Showcase demo: `sceneview://demo/ar-plane-renderer-v2` (live V1 ↔ V2 toggle) — kept so contributors can see the current V2 state and compare against V1.

### ARFogNode — environment-aware AR fog (v4.10.0+, #1717)

`ARFogNode` (`io.github.sceneview.ar.node.ARFogNode`) blends the live camera passthrough toward a fog colour using the ARCore depth image, so distant real-world geometry fades into a coloured haze while near surfaces stay crisp. It applies to the **real world** — pair it with a `FogNode` to fog **virtual** geometry consistently.

Inspired by ARCore Depth Lab's *AR Fog* sample. Opt-in, off by default; collapses to a no-op when `enabled = false` (no shader cost).

```kotlin
ARSceneView(
    cameraStream = rememberARCameraStream(materialLoader, creator = {
        createARCameraStream(materialLoader).apply { isDepthOcclusionEnabled = true }
    }),
    sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
    },
) {
    ARFogNode(
        cameraStream = cameraStream,
        density = 0.08f,          // 1/m, range [0, 1]
        start = 0.5f,             // metres — closer than this stays crisp
        end = 6.0f,               // metres — fully fogged past this distance
        color = Color(0xFFCCDDFF),
        enabled = true,
    )
    // Match the same haze on virtual geometry:
    FogNode(view = view, density = 0.08f, color = Color(0xFFCCDDFF))
}
```

**Requirements:**
- `Config.DepthMode.AUTOMATIC` (or `RAW_DEPTH_ONLY`) and `cameraStream.isDepthOcclusionEnabled = true`. Without depth pixels the per-pixel fog factor has nothing to drive it.
- Devices without Depth API support fall back to virtual-only `FogNode` cleanly — surface that to your user with `session.isDepthModeSupported(...)`.

**Parameter parity with `FogNode`:** `density`, `color`, `enabled` carry the same meaning as the virtual `FogNode` defaults so the same numbers fog both real and virtual content visually. `start`/`end` are AR-only — Filament's volumetric fog (`FogNode`) bakes them differently.

**Shader formula** (pure-Kotlin reference: `computeARFogFactor`): `factor = 1 - exp(-density * max(depth - start, 0))`, clamped to `[0, 1]`. Depth pixels with `depth_mm == 0` (ARCore "no depth available") produce `factor = 0` so the camera passthrough stays crisp on regions the depth API can't see.

---

## SceneScope — Node DSL

All content inside `SceneView { }` or `ARSceneView { }` is a `SceneScope`. Available properties:
- `engine: Engine`
- `modelLoader: ModelLoader`
- `materialLoader: MaterialLoader`
- `environmentLoader: EnvironmentLoader`

### Node — empty pivot/group
```kotlin
@Composable fun Node(
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    isVisible: Boolean = true,
    isEditable: Boolean = false,
    apply: Node.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```
Usage — group nodes:
```kotlin
SceneView(...) {
    Node(position = Position(y = 1f)) {
        ModelNode(modelInstance = instance, position = Position(x = -1f))
        CubeNode(size = Size(0.1f), position = Position(x = 1f))
    }
}
```

### ModelNode — 3D model
```kotlin
@Composable fun ModelNode(
    modelInstance: ModelInstance,
    autoAnimate: Boolean = true,
    animationName: String? = null,
    animationLoop: Boolean = true,
    animationSpeed: Float = 1f,
    scaleToUnits: Float? = null,
    centerOrigin: Position? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    isVisible: Boolean = true,
    isEditable: Boolean = false,
    apply: ModelNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

Key behaviors:
- `scaleToUnits`: uniformly scales to fit within a cube of this size (meters). `null` = original size.
- `centerOrigin`: aligns the model's bounding box with the node origin — the AABB point selected by the normalized origin (`-1..1` per axis, `0` = AABB center) lands on the node origin, whatever the asset's authored pivot. `Position(0,0,0)` = center model. `Position(0,-1,0)` = center horizontal, bottom-aligned (model sits on the origin). `null` = keep the authored pivot. Composes **additively** with `position` — the alignment offset (`-(center + origin * halfExtent) * scale`) is added to `position`, so a non-zero `centerOrigin` takes effect even when `position` is left at its default, and the two can be combined (bottom-align *and* place at a point). Applied once on creation (not reactive, like `scaleToUnits`).
- `autoAnimate = true` + `animationName = null`: plays ALL animations.
- `animationName = "Walk"`: plays only that named animation (stops previous). Reactive to Compose state.

Reactive animation example:
```kotlin
var isWalking by remember { mutableStateOf(false) }

SceneView(...) {
    instance?.let {
        ModelNode(
            modelInstance = it,
            autoAnimate = false,
            animationName = if (isWalking) "Walk" else "Idle",
            animationLoop = true,
            animationSpeed = 1f
        )
    }
}
// When animationName changes, the previous animation stops and the new one starts.
```

ModelNode class properties (available via `apply` block):
- `renderableNodes: List<RenderableNode>` — submesh nodes
- `lightNodes: List<LightNode>` — embedded lights
- `cameraNodes: List<CameraNode>` — embedded cameras
- `boundingBox: Box` — glTF AABB
- `animationCount: Int`
- `isShadowCaster: Boolean`
- `isShadowReceiver: Boolean`
- `materialVariantNames: List<String>`
- `skinCount: Int`, `skinNames: List<String>`
- `playAnimation(index: Int, speed: Float = 1f, loop: Boolean = true)`
- `playAnimation(name: String, speed: Float = 1f, loop: Boolean = true)`
- `stopAnimation(index: Int)`, `stopAnimation(name: String)`
- `setAnimationSpeed(index: Int, speed: Float)`
- `scaleToUnitCube(units: Float = 1.0f)`
- `centerOrigin(origin: Position = Position(0f, 0f, 0f))`
- `onFrameError: ((Exception) -> Unit)?` — callback for frame errors (default: logs via Log.e)

### LightNode — light source
**CRITICAL: `apply` is a named parameter (`apply = { ... }`), NOT a trailing lambda.**

```kotlin
@Composable fun LightNode(
    type: LightManager.Type,
    intensity: Float? = null,              // lux (directional/sun) or candela (point/spot)
    direction: Direction? = null,          // for directional/spot/sun
    position: Position = Position(x = 0f),
    color: Color? = null,                  // linear RGB light colour (null = white)
    apply: LightManager.Builder.() -> Unit = {},   // advanced: falloff, spotLightCone, sunAngularRadius, etc.
    nodeApply: LightNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

`LightManager.Type` values: `DIRECTIONAL`, `POINT`, `SPOT`, `FOCUSED_SPOT`, `SUN`.

```kotlin
SceneView(...) {
    // Simple — use explicit params (recommended):
    LightNode(
        type = LightManager.Type.SUN,
        intensity = 100_000f,
        direction = Direction(0f, -1f, 0f),
        apply = { castShadows(true) }
    )
    // Advanced — use apply for full Builder access:
    LightNode(
        type = LightManager.Type.SPOT,
        intensity = 50_000f,
        position = Position(2f, 3f, 0f),
        apply = { falloff(5.0f); spotLightCone(0.1f, 0.5f) }
    )
}
```

### CubeNode — box geometry
```kotlin
@Composable fun CubeNode(
    size: Size = Cube.DEFAULT_SIZE,        // Size(1f, 1f, 1f)
    center: Position = Cube.DEFAULT_CENTER, // Position(0f, 0f, 0f)
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: CubeNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### SphereNode — sphere geometry
```kotlin
@Composable fun SphereNode(
    radius: Float = Sphere.DEFAULT_RADIUS,  // 1.0f
    center: Position = Sphere.DEFAULT_CENTER,
    stacks: Int = Sphere.DEFAULT_STACKS,    // 24
    slices: Int = Sphere.DEFAULT_SLICES,    // 24
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: SphereNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### CylinderNode — cylinder geometry
```kotlin
@Composable fun CylinderNode(
    radius: Float = Cylinder.DEFAULT_RADIUS, // 1.0f
    height: Float = Cylinder.DEFAULT_HEIGHT, // 2.0f
    center: Position = Cylinder.DEFAULT_CENTER,
    sideCount: Int = Cylinder.DEFAULT_SIDE_COUNT, // 24
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: CylinderNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### ConeNode — cone geometry
```kotlin
@Composable fun ConeNode(
    radius: Float = Cone.DEFAULT_RADIUS,           // 1.0f
    height: Float = Cone.DEFAULT_HEIGHT,           // 2.0f
    center: Position = Cone.DEFAULT_CENTER,
    sideCount: Int = Cone.DEFAULT_SIDE_COUNT,      // 24
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: ConeNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### TorusNode — torus (donut) geometry
```kotlin
@Composable fun TorusNode(
    majorRadius: Float = Torus.DEFAULT_MAJOR_RADIUS,   // 1.0f (ring centre)
    minorRadius: Float = Torus.DEFAULT_MINOR_RADIUS,   // 0.3f (tube thickness)
    center: Position = Torus.DEFAULT_CENTER,
    majorSegments: Int = Torus.DEFAULT_MAJOR_SEGMENTS,  // 32
    minorSegments: Int = Torus.DEFAULT_MINOR_SEGMENTS,  // 16
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: TorusNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### CapsuleNode — capsule (cylinder + hemisphere caps)
```kotlin
@Composable fun CapsuleNode(
    radius: Float = Capsule.DEFAULT_RADIUS,         // 0.5f
    height: Float = Capsule.DEFAULT_HEIGHT,         // 2.0f (cylinder section; total = h + 2r)
    center: Position = Capsule.DEFAULT_CENTER,
    capStacks: Int = Capsule.DEFAULT_CAP_STACKS,    // 8
    sideSlices: Int = Capsule.DEFAULT_SIDE_SLICES,  // 24
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: CapsuleNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### PlaneNode — flat quad
```kotlin
import io.github.sceneview.geometries.Plane // the geometry, not com.google.ar.core.Plane
import io.github.sceneview.node.PlaneNode   // the 3D quad node, not io.github.sceneview.ar.node.PlaneNode

@Composable fun PlaneNode(
    size: Size = Plane.DEFAULT_SIZE,
    center: Position = Plane.DEFAULT_CENTER,
    normal: Direction = Plane.DEFAULT_NORMAL,
    uvScale: UvScale = UvScale(1.0f),
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: PlaneNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### Geometry nodes — material creation
Geometry nodes accept `materialInstance: MaterialInstance?`. Create materials via `materialLoader`:
```kotlin
SceneView(...) {
    val redMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color.Red, metallic = 0f, roughness = 0.6f)
    }
    // Unlit (flat colour, ignores scene lighting) — for HUD overlays, debug
    // gizmos, billboards, stylized rendering. No metallic/roughness/reflectance.
    val unlitGreen = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color.Green)
    }
    CubeNode(size = Size(0.5f), center = Position(0f, 0.25f, 0f), materialInstance = redMaterial)
    SphereNode(radius = 0.3f, materialInstance = blueMaterial)
    CylinderNode(radius = 0.2f, height = 1.0f, materialInstance = greenMaterial)
    ConeNode(radius = 0.3f, height = 0.8f, materialInstance = yellowMaterial)
    TorusNode(majorRadius = 0.5f, minorRadius = 0.15f, materialInstance = purpleMaterial)
    CapsuleNode(radius = 0.2f, height = 0.6f, materialInstance = orangeMaterial)
    PlaneNode(size = Size(5f, 5f), materialInstance = greyMaterial)
}
```

### ImageNode — image on plane (3 overloads)
```kotlin
import io.github.sceneview.geometries.Plane // the geometry defaults, not com.google.ar.core.Plane

// From Bitmap
@Composable fun ImageNode(
    bitmap: Bitmap,
    size: Size? = null,      // null = auto from aspect ratio
    center: Position = Plane.DEFAULT_CENTER,
    normal: Direction = Plane.DEFAULT_NORMAL,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: ImageNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)

// From asset file path
@Composable fun ImageNode(
    imageFileLocation: String,
    size: Size? = null,
    center: Position = Plane.DEFAULT_CENTER,
    normal: Direction = Plane.DEFAULT_NORMAL,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: ImageNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)

// From drawable resource
@Composable fun ImageNode(
    @DrawableRes imageResId: Int,
    size: Size? = null,
    center: Position = Plane.DEFAULT_CENTER,
    normal: Direction = Plane.DEFAULT_NORMAL,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: ImageNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### TextNode — 3D text label (faces camera)
```kotlin
@Composable fun TextNode(
    text: String,
    fontSize: Float = 48f,
    textColor: Int = android.graphics.Color.WHITE,
    backgroundColor: Int = 0xCC000000.toInt(),
    widthMeters: Float = 0.6f,
    heightMeters: Float = 0.2f,
    position: Position = Position(x = 0f),
    scale: Scale = Scale(1f),
    cameraPositionProvider: (() -> Position)? = null,
    apply: TextNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```
Reactive: `text`, `fontSize`, `textColor`, `backgroundColor`, `position`, `scale` update on recomposition.

### BillboardNode — always-facing-camera sprite
```kotlin
@Composable fun BillboardNode(
    bitmap: Bitmap,
    widthMeters: Float? = null,
    heightMeters: Float? = null,
    position: Position = Position(x = 0f),
    scale: Scale = Scale(1f),
    cameraPositionProvider: (() -> Position)? = null,
    apply: BillboardNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### SplatNode — 3D Gaussian Splatting (radiance-field captures) (#2646)
Renders a `SplatCloud` (the KMP data model: flat `positions`/`scales`/`rotations`/`colors`/`opacities`
arrays) as camera-facing gaussian discs — instanced quads, per-splat data in RGBA16F textures,
premultiplied-alpha blending. Clouds above 65535 splats are split into multiple draws internally.
```kotlin
@Composable fun SplatNode(
    splatCloud: SplatCloud,          // io.github.sceneview.core.splat.SplatCloud (must be non-empty)
    cameraPositionProvider: (() -> Position)? = null,  // enables the back-to-front painter's sort
    splatCount: Int = splatCloud.count,  // render only the first N texels — LOD / reveal hook
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: SplatNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)

@Composable fun rememberSplatCloud(vararg keys: Any?, creator: () -> SplatCloud): SplatCloud
```

Usage:
```kotlin
var cameraPosition by remember { mutableStateOf(Position()) }
SceneView(
    cameraNode = cameraNode,
    onFrame = { cameraPosition = cameraNode.worldPosition }
) {
    SplatNode(
        splatCloud = cloud,                            // e.g. from rememberSplatCloud { ... }
        cameraPositionProvider = { cameraPosition }    // re-sorts off the main thread on camera motion
    )
}
```

Current scope (P1): isotropic billboards (max-axis scale — anisotropic screen-space ellipses are
planned) and SH degree 0 color. Without a `cameraPositionProvider` the compositing order stays
as loaded (expect popping when orbiting). Build a `SplatCloud` from arrays, or parse a file with
the KMP `SplatParser.fromPly(bytes)` / `.fromSpz(bytes)` / `.parse(bytes)` (auto-detect) in
`io.github.sceneview.core.splat`.

**Web (#2646 P2):** the same rendering ships in `sceneview-web` (Kotlin/JS + Filament.js, WebGL2).
Plain JS: `viewer.addSplatNode(url)` (`.ply` / `.spz`) → `Promise<NodeHandle>`. Kotlin/JS:
`SceneView.addSplatNode(url, onLoaded = …)` or `addSplatNode(splatCloud)` for an already-parsed
cloud. Same isotropic-P1 scope; the painter's sort re-runs on camera motion automatically (the web
node feeds it the live camera position). iOS is tracked separately under #2646.

### VideoNode — video on 3D plane
```kotlin
// Simple — asset path (recommended):
@ExperimentalSceneViewApi
@Composable fun VideoNode(
    videoPath: String,              // e.g. "videos/promo.mp4"
    autoPlay: Boolean = true,
    isLooping: Boolean = true,
    chromaKeyColor: Int? = null,
    size: Size? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: VideoNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)

// Advanced — bring your own MediaPlayer:
@Composable fun VideoNode(
    player: MediaPlayer,
    chromaKeyColor: Int? = null,
    size: Size? = null,    // null = auto-sized from video aspect ratio
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: VideoNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

Usage (simple):
```kotlin
SceneView {
    VideoNode(videoPath = "videos/promo.mp4", position = Position(z = -2f))
}
```

Usage (advanced — custom MediaPlayer):
```kotlin
val player = rememberMediaPlayer(context, assetFileLocation = "videos/promo.mp4")

SceneView(...) {
    player?.let { VideoNode(player = it, position = Position(z = -2f)) }
}
```

### ViewNode — Compose UI in 3D
**Requires `viewNodeWindowManager` on the parent `Scene`.**
```kotlin
@Composable fun ViewNode(
    windowManager: ViewNode.WindowManager,
    unlit: Boolean = false,
    invertFrontFaceWinding: Boolean = false,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    apply: ViewNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
    viewContent: @Composable () -> Unit    // the Compose UI to render
)
```

Usage:
```kotlin
val windowManager = rememberViewNodeManager()
SceneView(viewNodeWindowManager = windowManager) {
    ViewNode(windowManager = windowManager) {
        Card { Text("Hello 3D World!") }
    }
}
```

Three gotchas that bite every `ViewNode`:

- **`viewContent` composes in its own off-screen window, so it inherits NONE of
  your `CompositionLocal`s** — `MaterialTheme`, `LocalContentColor`, your own
  locals. Without re-applying them inside, a themed card silently falls back to
  Material 3 defaults and stops matching the rest of your UI. Wrap the content:
  `ViewNode(windowManager) { MyAppTheme { Card { … } } }`.
- **The window is `WRAP_CONTENT`**, so the content is measured `AT_MOST(display)`:
  `fillMaxWidth()` resolves to the whole display width and puts a metres-wide quad
  in the scene — it does not collapse to zero. Give the content an explicit size
  (`Modifier.width(320.dp)`).
- **One `WindowManager` sizes itself to its LARGEST child.** When several
  `ViewNode`s share one manager, every quad is re-measured to the biggest content,
  so a node whose content grows silently resizes and shifts all the others (a
  streaming text panel drags its neighbours around while it types). Give every node
  sharing a manager the same explicit size — width *and* height — or give each node
  its own manager, at the cost of one system window per node.
- **The rendered UI is NOT interactive** — see immediately below.

**The rendered UI is NOT interactive.** A `ViewNode` draws its Compose content into a texture;
the hosting window is `FLAG_NOT_TOUCHABLE` and touch events are never dispatched into it, so a
`Button` placed inside **will render but its `onClick` will never fire** (no ripple, no press
state, no inner scrolling either). Do not generate clickable controls inside a `ViewNode`.

To react to a tap, pick the node from the scene instead:
```kotlin
val windowManager = rememberViewNodeManager()
var tapCount by remember { mutableStateOf(0) }
SceneView(
    viewNodeWindowManager = windowManager,
    onGestureListener = rememberOnGestureListener(
        // `node` is the ray-cast hit — null when the tap missed every node.
        onSingleTapUp = { _, node -> if (node is ViewNode) tapCount++ }
    )
) {
    ViewNode(windowManager = windowManager) {
        Card { Text("Tapped $tapCount times") }
    }
}
```
Real touch forwarding is tracked by [#2845](https://github.com/sceneview/sceneview/issues/2845).

### LineNode — single line segment
```kotlin
@Composable fun LineNode(
    start: Position = Line.DEFAULT_START,
    end: Position = Line.DEFAULT_END,
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: LineNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### PathNode — polyline through points
```kotlin
@Composable fun PathNode(
    points: List<Position> = Path.DEFAULT_POINTS,
    closed: Boolean = false,
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: PathNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### MeshNode — custom geometry
```kotlin
@Composable fun MeshNode(
    primitiveType: RenderableManager.PrimitiveType,
    vertexBuffer: VertexBuffer,
    indexBuffer: IndexBuffer,
    boundingBox: Box? = null,
    materialInstance: MaterialInstance? = null,
    apply: MeshNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### ShapeNode — 2D polygon shape
```kotlin
@Composable fun ShapeNode(
    polygonPath: List<Position2> = listOf(),
    polygonHoles: List<Int> = listOf(),
    delaunayPoints: List<Position2> = listOf(),
    normal: Direction = Shape.DEFAULT_NORMAL,
    uvScale: UvScale = UvScale(1.0f),
    color: Color? = null,
    materialInstance: MaterialInstance? = null,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    apply: ShapeNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```
Renders a triangulated 2D polygon in 3D space. Supports holes, Delaunay refinement, and vertex colors.

### PhysicsNode — simple rigid-body physics
```kotlin
@Composable fun PhysicsNode(
    node: Node,
    restitution: Float = 0.6f,
    linearVelocity: Position = Position(0f, 0f, 0f),
    floorY: Float = 0f,
    radius: Float = 0f,
    floorProvider: FloorProvider? = null   // dynamic floor source — e.g. rememberDepthCollider() (AR)
)
```
Attaches gravity + floor bounce to an existing node. Does NOT add the node to the scene — the node
must already exist. Uses Euler integration at 9.8 m/s² with configurable restitution and floor.
Note: a `mass` overload exists but is `@Deprecated` — gravity is mass-independent, so `mass` is
a no-op until a force/impulse API lands.

```kotlin
SceneView {
    val sphere = remember(engine) { SphereNode(engine, radius = 0.15f) }
    PhysicsNode(node = sphere, restitution = 0.7f, linearVelocity = Position(0f, 3f, 0f), radius = 0.15f)
}
```

### DynamicSkyNode — time-of-day sun lighting

```kotlin
@Composable fun SceneScope.DynamicSkyNode(
    timeOfDay: Float = 12f,          // 0-24: 0=midnight, 6=sunrise, 12=noon, 18=sunset
    turbidity: Float = 2f,           // atmospheric haze [1.0, 10.0]
    sunIntensity: Float = 110_000f   // lux at solar noon
)
```

Creates a SUN light whose colour, intensity and direction update with `timeOfDay`.
Sun rises at 6h, peaks at 12h, sets at 18h. Colour: cool blue (night) → warm orange (horizon) → white-yellow (noon).

```kotlin
SceneView {
    DynamicSkyNode(timeOfDay = 14.5f)
    ModelNode(modelInstance = instance!!)
}
```

### SecondaryCamera — secondary camera (formerly CameraNode)
```kotlin
@Composable fun SecondaryCamera(
    apply: CameraNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```
**Note:** Does NOT become the active rendering camera. The main camera is set via `SceneView(cameraNode = ...)`.
`CameraNode()` composable is deprecated — use `SecondaryCamera()` instead.

### ReflectionProbeNode — local IBL override
```kotlin
@Composable fun ReflectionProbeNode(
    filamentScene: FilamentScene,
    environment: Environment,
    position: Position = Position(0f, 0f, 0f),
    radius: Float = 0f,       // 0 = global (always active)
    priority: Int = 0,
    cameraPosition: Position = Position(0f, 0f, 0f)
)
```

---

## ARSceneScope — AR Node DSL

`ARSceneScope` extends `SceneScope` with AR-specific composables. All `SceneScope` nodes (ModelNode, CubeNode, etc.) are also available.

### PlacementScene — one-line tap-to-place (v4.10.0+, #1765)

**Start here for AR placement.** `PlacementScene` is the high-level composable that bundles the
whole tap-to-place pipeline — Sceneform `ArFragment` parity in one call. It wires an `ARSceneView`
with plane rendering, a built-in centre-screen **ring reticle** that brightens when a surface is
ready, tap-to-place anchor creation, and an instant-placement fallback. Opt in to `coaching` for
the animated onboarding guide and `groundShadows` for contact shadows under placed models. You
only declare *what* rides each placed anchor:

```kotlin
import io.github.sceneview.ar.PlacementScene

@Composable
fun MyArScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    PlacementScene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        // NAMED parameter — the trailing lambda position belongs to `content`,
        // which receives a PlacementController, not the Anchor.
        onPlaced = { anchor ->               // invoked once per tap-created Anchor
            AnchorNode(anchor = anchor) {
                val instance = rememberModelInstance(modelLoader, "models/helmet.glb")
                instance?.let {
                    ModelNode(modelInstance = it, scaleToUnits = 0.3f)
                }
            }
        },
    )
}
```

Signature:
```kotlin
@Composable fun PlacementScene(
    modifier: Modifier = Modifier,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    planeRenderer: Boolean = true,
    planeFindingMode: Config.PlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
    instantPlacement: Boolean = true,        // place before a plane converges (ArFragment parity)
    showReticle: Boolean = true,             // built-in centre-screen placement reticle
    reticleStyle: PlacementReticleStyle = PlacementReticleStyle.RING,  // RING (default) or DISC
    reticleColor: Color = RETICLE_TINT,      // DESIGN.md primary cyan; opacity varies searching↔ready
    fadePlaneOnFirstPlacement: Boolean = true,  // hide the plane grid after the first model lands
    coaching: Boolean = false,               // opt-in PlaneDiscoveryGuide onboarding overlay
    groundShadows: Boolean = false,          // opt-in contact shadow under placed models — auto-gated
                                             // against the plane grid's own receiver: exactly one
                                             // shadow receiver is ever live on a plane (#2657)
    playbackDataset: File? = null,
    sessionConfiguration: ((Session, Config) -> Unit)? = null,
    onPlaced: @Composable ARSceneScope.(anchor: Anchor) -> Unit,   // required — what to place
    content: (@Composable ARSceneScope.(controller: PlacementController) -> Unit)? = null,
)
```

The optional `content` block receives a `PlacementController` — call `controller.clear()` to
detach every placed anchor (e.g. a "Clear All" button), or read `controller.count` /
`controller.anchors` to drive a placement counter. Both are Compose-observable.

`PlacementScene` accepts plane hits (inside the polygon) and — when `instantPlacement = true` —
instant-placement hits. For placement against arbitrary real geometry (sofas, slopes) use
`DepthHitResultNode`; for full manual control drop down to `ARSceneView` + `HitResultNode`.

### WallPlacementScene — place on a wall, aligned to the floor↔wall edge (#2740)

**Use this instead of `PlacementScene` for vertical-surface products** — a TV, framed art, a mirror,
a shelf. Placing on a wall is harder than dropping a model on the floor: ARCore converges vertical
planes slowly and noisily, so anchoring at the raw hit pose leaves the object tilted and floating.
`WallPlacementScene` decouples the two axes the way Amazon "AR View" / IKEA Place do it — orientation
comes from the **wall** (the object is made flush and upright, never inheriting hit-pose noise) and
height comes from the **floor** (seated at `floorY + mountHeight`, stable while the wall jitters). It
tracks the live floor↔wall seam (the "orange alignment line") and surfaces it via `onSeamChanged`.

```kotlin
import io.github.sceneview.ar.WallPlacementScene

@Composable
fun WallTvScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    WallPlacementScene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        mountHeight = 1.2f,                      // TV centre height above the floor, meters
        // NAMED parameter — the trailing lambda position belongs to `content`
        // (PlacementController), same contract as PlacementScene.
        onPlaced = { anchor ->                   // invoked once per tap-created wall Anchor
            AnchorNode(anchor = anchor) {
                val instance = rememberModelInstance(modelLoader, "models/tv.glb")
                instance?.let { ModelNode(modelInstance = it, scaleToUnits = 1.4f) }
            }
        },
    )
}
```

Signature:
```kotlin
@Composable fun WallPlacementScene(
    modifier: Modifier = Modifier,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    mountHeight: Float = 1.2f,                   // anchor height above the floor (base-on-floor:
                                                 // pass the object half-height; hung: centre height)
    planeRenderer: Boolean = true,
    onSeamChanged: ((FloorWallSeam?) -> Unit)? = null,   // live floor↔wall edge — draw the guide
    onPhaseChanged: ((WallPlacementPhase) -> Unit)? = null, // FINDING_FLOOR→FINDING_WALL→ALIGNING_EDGE→PLACED
    playbackDataset: File? = null,
    sessionConfiguration: ((Session, Config) -> Unit)? = null,
    onPlaced: @Composable ARSceneScope.(anchor: Anchor) -> Unit,
    content: (@Composable ARSceneScope.(controller: PlacementController) -> Unit)? = null,
)
```

It enables `HORIZONTAL_AND_VERTICAL` plane finding, accepts only vertical-plane hits inside the
polygon, and shares `PlacementController` with `PlacementScene` (`controller.clear()` /
`controller.count`). The placement math is exposed as pure functions for custom flows:
`wallFacingRotation(wallNormal)`, `roomFacingNormal(wallNormal, towardViewer)` (flips an ARCore
plane normal toward the camera — its sign is not guaranteed), `floorWallSeam(wallNormal, wallPoint,
floorY)`, `wallAnchorPose(wallHit, wallNormal, floorY, mountHeight)`. This first increment surfaces the seam +
phase via callbacks so an app draws its own guide; an in-scene 3D seam line and a gizmo/D-pad
fine-adjust UI are tracked follow-ups on #2740.

**⚠️ Important nesting rule:** AR composables (`AnchorNode`, `CloudAnchorNode`, `AugmentedImageNode`, etc.) can only be declared at the `ARSceneView { }` root level — they are NOT available inside `Node { content }` or other node's `content` blocks. To nest models under an anchor, use `AnchorNode(anchor) { ModelNode(...) }` — the `content` block of `AnchorNode` provides a regular `NodeScope`.

**⚠️ ARCore Cloud API key — required for `CloudAnchorNode`, `StreetscapeGeometryNode`, and any `Config.GeospatialMode.ENABLED` session.** When generating code that uses these features, you MUST include both:

1. The manifest meta-data:
```xml
<meta-data
    android:name="com.google.android.ar.API_KEY"
    android:value="${arcoreApiKey}" />
```
2. The `manifestPlaceholders["arcoreApiKey"] = ...` injection in `app/build.gradle` (read from env var `ARCORE_API_KEY` or `local.properties` — never hardcoded).
3. `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` and a runtime `RequestMultiplePermissions` flow asking for `CAMERA + ACCESS_FINE_LOCATION` BEFORE mounting `ARSceneView`. Geospatial throws `FineLocationPermissionNotGrantedException` otherwise.

Plain plane-finding / hit-testing / face mesh / image detection does NOT require the API key — only Cloud Anchors / Geospatial / Streetscape do. Setup guide with Cloud Console steps: `samples/android-demo/ARCORE_CLOUD_SETUP.md`.

### AnchorNode — pin to real world
```kotlin
@Composable fun AnchorNode(
    anchor: Anchor,
    updateAnchorPose: Boolean = true,
    visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onAnchorChanged: ((Anchor) -> Unit)? = null,
    onUpdated: ((Anchor) -> Unit)? = null,
    apply: AnchorNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

Usage:
```kotlin
var anchor by remember { mutableStateOf<Anchor?>(null) }
ARSceneView(
    onSessionUpdated = { _, frame ->
        if (anchor == null) {
            anchor = frame.getUpdatedPlanes()
                .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                ?.let { plane -> plane.createAnchorOrNull(plane.centerPose) }
        }
    }
) {
    anchor?.let { a ->
        AnchorNode(anchor = a) {
            ModelNode(modelInstance = instance!!, scaleToUnits = 0.5f, isEditable = true)
        }
    }
}
```

### PoseNode — position at ARCore Pose
```kotlin
@Composable fun PoseNode(
    pose: Pose = Pose.IDENTITY,
    visibleCameraTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    onPoseChanged: ((Pose) -> Unit)? = null,
    apply: PoseNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### HitResultNode — surface cursor (2 overloads)

**Recommended — screen-coordinate hit test** (most common for placement cursors):
```kotlin
@Composable fun HitResultNode(
    xPx: Float,                         // screen X in pixels (use viewWidth / 2f for center)
    yPx: Float,                         // screen Y in pixels (use viewHeight / 2f for center)
    planeTypes: Set<Plane.Type> = Plane.Type.entries.toSet(),
    point: Boolean = false,             // plane-only by default (#1891)
    depthPoint: Boolean = false,        // plane-only by default (#1891)
    instantPlacementPoint: Boolean = false,
    minCameraDistance: Float? = 0.3f,   // defensive floor — rejects hits closer than 30 cm
    // ... other filters with sensible defaults ...
    apply: HitResultNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

**Defaults are plane-only ([#1891](https://github.com/sceneview/sceneview/issues/1891)).**
Depth / feature-point hits before motion-stereo convergence return positions extremely
close to the camera (often <10 cm), which causes a child placement disc to render as a
fullscreen overlay that blanks the camera feed on session start. Opt each filter back
in explicitly once your scene is tracking-stable (e.g. `point = true, depthPoint = true`
inside a "Free placement" toggle). `minCameraDistance` is a defensive camera-to-hit
floor in meters — pass `null` to disable.

**Custom hit test** (full control):
```kotlin
@Composable fun HitResultNode(
    hitTest: HitResultNode.(Frame) -> HitResult?,
    apply: HitResultNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

Typical center-screen placement cursor:
```kotlin
ARSceneView(modifier = Modifier.fillMaxSize()) {
    // Place a cursor at screen center — plane-only, hidden until motion stereo converges
    HitResultNode(xPx = viewWidth / 2f, yPx = viewHeight / 2f) {
        CubeNode(size = Size(0.05f))  // small indicator cube
    }
}
```

**Rate-limit the ARCore hit test (perf, [#2328](https://github.com/sceneview/sceneview/issues/2328)).**
`HitResultNode.refreshIntervalMs` (default `0` = `Frame.hitTest` every frame) throttles the
raycast the same way `PointCloudNode` / `DepthMeshNode` rate-limit their rebuilds: a positive
value (e.g. `100` = 10 Hz) keeps the node's last pose between hit tests, cutting hit-test load
on scenes with several cursors. Set it in the `apply` block:
```kotlin
HitResultNode(xPx = viewWidth / 2f, yPx = viewHeight / 2f, apply = { refreshIntervalMs = 100 }) {
    CubeNode(size = Size(0.05f))
}
```

### ReticleNode — placement reticle with auto-hide

`ReticleNode` is a **thin wrapper** over `HitResultNode` for "tap to place" UX. It is a
`HitResultNode` subclass — it delegates the screen-coordinate hit test (including the
plane-only defaults and the 30 cm `minCameraDistance` floor) to `HitResultNode` and
adds only the `onHitResultChanged` callback. **Auto-hide on no-hit comes for free**:
a `null` hit clears the trackable, so a child marker stops rendering with no manual
visibility juggling. Use `ReticleNode` when you want a one-call callback on every hit
change (to drive an "aim at a surface" hint and capture the last-known hit on
tap-to-place); use `HitResultNode` directly otherwise. New in v4.x (#1882).

```kotlin
@Composable fun ReticleNode(
    xPx: Float,                                                // screen X (usually viewWidth / 2f)
    yPx: Float,                                                // screen Y (usually viewHeight / 2f)
    planeTypes: Set<Plane.Type> = Plane.Type.entries.toSet(),
    point: Boolean = false,                                    // plane-only by default (#1891)
    depthPoint: Boolean = false,                               // plane-only by default (#1891)
    instantPlacementPoint: Boolean = false,                    // off by default — visible markers stick to real geometry
    trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    pointOrientationModes: Set<Point.OrientationMode> = setOf(Point.OrientationMode.ESTIMATED_SURFACE_NORMAL),
    planePoseInPolygon: Boolean = true,
    minCameraDistance: Float? = 0.3f,                          // 30 cm floor — hits closer are dropped (#1891)
    minCameraDistanceFromPlane: Pair<Camera, Float>? = null,   // legacy plane-only gate
    predicate: ((HitResult) -> Boolean)? = null,
    onHitResultChanged: ((HitResult?) -> Unit)? = null,        // null when the ray misses every trackable
    apply: ReticleNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null        // visual marker (e.g. a thin disc)
)
```

Typical "what-you-see-is-what-you-get" placement:
```kotlin
var reticleHit by remember { mutableStateOf<HitResult?>(null) }
val placedAnchors = remember { mutableStateListOf<Anchor>() }
val reticleMaterial = remember(materialLoader) {
    materialLoader.createUnlitColorInstance(Color.Cyan)
}
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { _, _ ->
            // The model lands exactly where the user saw the reticle.
            reticleHit?.createAnchor()?.let { placedAnchors.add(it) }
        }
    )
) {
    ReticleNode(
        xPx = viewWidth / 2f,
        yPx = viewHeight / 2f,
        onHitResultChanged = { reticleHit = it }
    ) {
        // Visual marker — a thin cyan disc parallel to the detected surface.
        CylinderNode(radius = 0.04f, height = 0.002f, materialInstance = reticleMaterial)
    }
}
```

### PlacementReticle — smoothed placement cursor (#2241, v4.19+)

`ReticleNode`'s Sprint-1 upper layer — a port of ARCore Depth Lab's `OrientedReticle`. Same
auto-hide + change-callback contract, plus: **orientation smoothing** (per-frame slerp toward
the hit's surface normal, default `0.75` — kills the disc jitter as ARCore refines the normal;
`1.0f` = raw) and **depth-capable acceptance** (`depthPoint = true` lands on arbitrary geometry;
needs depth mode enabled, default off). Ships a built-in thin cyan disc when `content` is null.

```kotlin
var reticleHit by remember { mutableStateOf<HitResult?>(null) }
ARSceneView(
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { _, _ -> reticleHit?.createAnchor()?.let { /* place */ } }
    )
) {
    PlacementReticle(
        xPx = viewWidth / 2f,
        yPx = viewHeight / 2f,
        onHitResultChanged = { reticleHit = it },
    )
}
```

Semantics: `snapToPlane = true` (default) is plane-only (#1891 contract);
`snapToPlane = false` is FREE PLACEMENT — feature-point hits accepted, plane hits still
in-polygon. Project acceptance policies (max distance, custom rules) go through `predicate`
(construction-time). The smoothing knob and callback are live-updatable on recomposition.

Platform matrix: **iOS** — ships (v4.20+, #894): `ARSceneView(showPlacementReticle: true)` runs
the tap-to-place raycast every frame and drives a surface-snapped disc (orientation slerp `0.75`,
hidden while the ray misses); tap-to-place itself already ships. **Web** — coming soon.

### ShadowReceiverPlane — invisible AR shadow catcher (#2241, v4.19+)

An invisible surface bound to a detected `Plane` that renders NOTHING except the darkening of
shadows cast onto it — placed models read as grounded on the real floor instead of floating.
Filament `shadowMultiplier` material (the AR shadow-catcher feature; port of Depth Lab's
`ShadowReceiverMeshShader`). Follows the plane's center pose and refined extents; receives
shadows, never casts; `shadowIntensity` in `0..1` (default 0.6 = Depth Lab) tunable live.

```kotlin
ARSceneView(
    planeRenderer = false, // its own shadow receiver would stack with the catchers (#2657)
    onSessionCreated = { arSession = it },
) {
    val detectedPlanes by rememberDetectedPlanes(session = arSession)
    detectedPlanes.forEach { plane ->
        key(plane) { ShadowReceiverPlane(plane = plane) }
    }
    // Placed ModelNodes cast the shadow (castShadows defaults on).
}
```

Never keep `ShadowReceiverPlane`s AND `planeRenderer = true` live on the same plane — the plane
renderer attaches its own coplanar shadow receiver, so stacking both z-fights and double-darkens
shadows (#2657). Gate them mutually exclusively (grid while scanning, shadow receivers after
placement), or just use `PlacementScene(groundShadows = true)`, which enforces the exclusion
for you.

Shadows require a shadow-casting directional light — `ARSceneView`'s default
`ENVIRONMENTAL_HDR` light estimation provides one; diagnose the estimated main light's
`isShadowCaster` before suspecting the catcher. This is NOT a plane visualisation (no grid,
no fill — that is `PlaneNode`, which carries no shadow receiver); declare `PlaneNode` and
`ShadowReceiverPlane` on the same plane if you want a visible overlay and grounded shadows.

Platform matrix: **iOS** — ships (v4.20+, #894): entities placed synchronously in `onTapOnPlane`
get `GroundingShadowComponent(castsShadow: true)` automatically (RealityKit renders the contact
shadow itself — no catcher geometry; opt out with `ARSceneView(groundingShadows: false)`;
async-anchored content sets the component itself). **Web** — coming soon.

**Non-AR 3D scenes** want the same contact shadow under a model but have no ARCore `Plane` to
bind to. Catch it with a plain `PlaneNode` at the model's feet using the shadow-only material,
and apply the SAME flat-quad hardening `ShadowReceiverPlaneNode` uses — a flat (zero-Y) shadow
receiver otherwise crashes the FL2+ cascaded-shadow build on-device (the FL1 emulator never hits
it, #2620/#2699):

```kotlin
val planeSize = 2f            // quad side, ≈2× the model footprint
val groundY = 0f              // world-space floor height
val halfHeight = 0.5f         // half the model's height, for the AABB top

PlaneNode(
    size = Size(x = planeSize, y = 0f, z = planeSize),   // XZ quad, never XY
    materialInstance = shadowMaterial,                   // materials/plane_renderer_shadow.filamat
    position = Position(y = groundY),
    apply = {
        isShadowCaster = false
        isShadowReceiver = true
        setCulling(false)                                // zero-thickness quad → skip culling
        axisAlignedBoundingBox = FilamentBox(            // non-degenerate: floor → model top
            0f, halfHeight, 0f, planeSize / 2f, halfHeight, planeSize / 2f,
        )
    },
)
```

Full recipe (Android + iOS): `samples/recipes/ground-shadow-catcher.md`.

### ContactShadow — procedural grounding pool, works on walls (#2740)

`ShadowReceiverPlane` catches a **real** shadow, so it needs a shadow-casting light pointing at
the surface. Indoors that light comes from the ceiling: it faces the floor and merely *grazes* a
wall, so a TV mounted flat against a wall casts essentially nothing onto it and reads as
floating. `ContactShadow` instead draws its own elliptical gradient in the shader — no shadow
map, no light dependency, deterministic at any angle, on any surface orientation. Same trade
Amazon "AR View" makes with its baked per-context shadow textures, done procedurally so no
texture ships.

It lives in `sceneview` (not `arsceneview`) — nothing about it is AR-specific, and a plain 3D
scene grounds a model exactly the same way.

```kotlin
ContactShadow(
    size = Size(x = 2.4f, y = 1.6f, z = 0f),   // XY quad → a WALL
    context = ContactShadowContext.Wall,
    normal = Direction(z = 1f),
    position = Position(x = 0f, y = 1.3f, z = -1.99f),
)
```

**Pick the context, not the numbers.** `ContactShadowContext` carries the gradient shape for each
situation: `Floor` (centred, dense — light faces the surface), `Wall` (fainter, wider than tall,
pushed *below* the object — light grazes the surface), `TableTop` (tight and crisp — the object
sits close). `intensity` overrides the peak opacity if you need to.

**Match `size` to `normal`.** `Plane` does NOT rotate its geometry to match its `normal`
parameter, so the quad's plane is decided by which `size` component is zero:

| Surface | `size` | Plane | `normal` |
|---|---|---|---|
| Floor / tabletop | `Size(x, 0f, z)` | XZ | `Direction(y = 1f)` |
| Wall | `Size(x, y, 0f)` | XY | `Direction(z = 1f)` |

Getting this pair wrong is the one real footgun: the node lifts itself off the surface along
`normal`, so a mismatched pair either z-fights with the wall or floats visibly off it. Size the
quad generously — the gradient fades out well before the edge.

Use `ShadowReceiverPlane` when you want a **real** shadow with correct light-driven shape on a
floor; use `ContactShadow` when you need grounding that survives any light direction — always on
a wall, and as a cheap deterministic fallback elsewhere. They compose: nothing stops a floor from
catching a real shadow while a wall gets a procedural one.

Platform matrix: **iOS** — has RealityKit's own `GroundingShadowComponent` (via
`ARSceneView(groundingShadows:)`), a projected grounding shadow — but NOT this procedural,
light-independent, per-context pool; not mirrored yet. **Web** — coming soon. Non-AR preview
demo (runs on any emulator, no ARCore): `ContactShadowPreviewDemo`.

### Depth hit test — place on any real surface

`HitResultNode` / `Frame.hitTest` resolve only against **detected planes / feature points**.
`Frame.hitTestDepth` instead raycasts the **ARCore depth image**, so it lands on *any* real-world
geometry (a sofa, a slope, a cluttered desk) without waiting for a plane to grow there — and it
returns the **surface normal**, so a placed object can be aligned to whatever it lands on.

Requires the session depth mode set to `Config.DepthMode.AUTOMATIC` or `RAW_DEPTH_ONLY`.
Returns `null` when depth is unavailable (not supported, not yet computed, camera not tracking,
or no valid depth at that pixel). Cheap enough for tap-driven placement — do not call it per-pixel.

**Threading:** call from the AR frame / GL-main thread — typically from
`onSessionUpdated`, `onFrame` or a UI gesture callback that already runs there.
The extension calls `Frame.acquireDepthImage16Bits()` and **must not** be invoked
from a background coroutine. See also: `DepthMeshNode` (depth → renderable mesh)
and `rememberDepthCollider` (depth → physics collider).

**Cross-platform:** Android-only. The iOS counterpart is `ARView.raycast(from:allowing:.any)` — see [cheatsheet-ios.md "AR Depth & Cloud Anchors"](docs/docs/cheatsheet-ios.md) (#1813). Web tracked in #1778.

```kotlin
data class DepthHitResult(
    val position: Position,   // world-space point on the real surface
    val normal: Direction,    // unit surface normal, facing the camera
    val distance: Float       // camera-to-point distance, meters
)

// Single result (not a list) — depth at one pixel is unique, unlike Frame.hitTest's ray cast.
fun Frame.hitTestDepth(xPx: Float, yPx: Float): DepthHitResult?
```

```kotlin
var latestFrame: Frame? = null
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    sessionConfiguration = { session, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
    },
    onSessionUpdated = { _, frame -> latestFrame = frame },
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { e, _ ->
            latestFrame?.hitTestDepth(e.x, e.y)?.let { hit ->
                // hit.position / hit.normal — place & orient a node on the real surface
            }
        }
    )
)
```

### DepthHitResultNode — Compose-idiomatic depth-hit placement

For continuous, declarative placement against any real-world surface, use `DepthHitResultNode` —
the depth-image mirror of `HitResultNode`. Each frame it re-runs `Frame.hitTestDepth` at the
configured pixel and moves to the resulting world-space point. When depth is unavailable, the
node keeps its last known pose (same fallback contract as `HitResultNode`).

```kotlin
@Composable fun ARSceneScope.DepthHitResultNode(
    xPx: Float,
    yPx: Float,
    apply: DepthHitResultNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
)
```

```kotlin
ARSceneView(
    sessionConfiguration = { _, config -> config.depthMode = Config.DepthMode.AUTOMATIC }
) {
    DepthHitResultNode(xPx = viewWidth / 2f, yPx = viewHeight / 2f) {
        CubeNode(size = Float3(0.05f))
    }
}
```

### Depth visualization — false-color depth overlay

ARCore's depth image is an 8-bit-millimetre signal that's normally invisible. Calling
`Frame.acquireDepthImage16Bits()` returns a `Y_16` image (little-endian, row-strided) where
each pixel is depth in millimetres (`0` = "no data"). Turning that into a false-color overlay
is a stock recipe for "is depth working on this device?" debugging — see the
`ar-depth-visualization` demo for the canonical implementation in the sample app: it copies
the 16-bit buffer into an ARGB bitmap with a 3-segment red→yellow→green→cyan→blue ramp
(near = warm, far = cool), draws it as an `Image` overlay above the `ARSceneView`, and uses
a Compose `Slider` for `alpha` to blend camera ↔ depth from 0 to 1.

```kotlin notest app-side pattern — depthBufferToArgb is demo code (ar-depth-visualization), not SDK API
// Acquire and colorize a depth frame; render via Image(alpha = blend).
onSessionUpdated = { _, frame ->
    val depthImage = runCatching { frame.acquireDepthImage16Bits() }.getOrNull() ?: return@onSessionUpdated
    val plane = depthImage.planes[0]
    val pixels = depthBufferToArgb(
        depthBytes = plane.buffer,
        width = depthImage.width,
        height = depthImage.height,
        rowStrideBytes = plane.rowStride,
    )
    // Upload pixels into a recycled Bitmap; close the depth image when done.
    depthImage.close()
}
```

Rules of thumb: the depth image is typically ~240×180 — far smaller than the camera feed,
so `ContentScale.Crop` upscaling is correct. Pixels with depth `0` should render fully
transparent (no datum) instead of mapping to the near color. Always show a "warming up"
banner until the first depth frame lands and an explicit "depth not supported" banner when
`Session.isDepthModeSupported(...)` returns `false` — never leave the user staring at a
black screen.

### Raw depth point cloud — accumulated depth visualization

`Config.DepthMode.RAW_DEPTH_ONLY` gives access to raw per-pixel depth **plus** a companion
confidence image. Unprojecting each high-confidence depth pixel into world space, accumulating
the points across frames into a bounded buffer, and rendering them as small colored points
yields a "what does the device actually see" scan view — useful for evaluating depth coverage
or as a building block for reconstruction tooling. See the `ar-raw-depth-point-cloud` demo:
the depth + confidence images are fetched on every frame, points below the confidence
threshold are dropped, the rest are unprojected with the depth-image intrinsics and shown in
a Compose overlay. A confidence slider lets the user watch noise drop out as the threshold
rises.

Notes: raw depth is computed asynchronously and may not be available every frame
(`acquireRawDepthImage16Bits()` returns `null` if not yet ready — handle it). Per-pixel
unprojection requires the depth-image intrinsics (`Frame.getCameraTextureIntrinsics()` or
the depth-image dimensions divided over the camera intrinsics). Cap the accumulated cloud at
a few thousand points or the UI thread chokes — Depth Lab's `RAW_DEPTH_ONLY` resolution is
~160×120 which is already ~19 200 points per frame.

### Low-level helpers — direct ARCore access

Thin Kotlin wrappers around raw ARCore APIs that apps reach for repeatedly (#1771). Each is
1–5 LOC and additive — the underlying ARCore call works just the same. Use these for
custom occlusion / physics raycasts / ML pipelines / orientation-aware UI placement.

```kotlin
// HitResult — distance is already accessible as a Kotlin synthetic property from
// HitResult.getDistance(). Documented here for parity with the rest of this section.
val hitDistance: Float = hitResult.distance   // camera-to-hit distance, meters

// Camera — displayOrientedPose accounts for current device orientation (use for
// rendering / billboard UI), whereas .pose is the raw sensor pose (use for IMU work).
val pose: Pose = camera.displayOrientedPose
```

```kotlin
// Camera intrinsics — focal length + principal point + image dimensions for either
// the GPU texture stream (default) or the CPU image stream. Used by ML pipelines that
// project 2D detections back into 3D world coords via the pinhole model.
data class CameraIntrinsicsSnapshot(
    val focalLength: Float2,              // fx, fy in pixels
    val principalPoint: Float2,           // cx, cy in pixels
    val imageWidth: Int,
    val imageHeight: Int
)
fun Camera.intrinsics(useTexture: Boolean = true): CameraIntrinsicsSnapshot

// Plane — polygon outline (plane-local X-Z coords) + subsumedBy (the surviving plane
// when ARCore merges two overlapping planes; re-anchor your nodes when non-null).
val Plane.polygon: FloatBuffer            // [x1, z1, x2, z2, ...] — owned by ARCore, don't retain
val Plane.subsumedBy: Plane?              // null if this plane is still alive

// Frame — public depth-image accessors (caller-owned — close in use { }).
// Requires Config.DepthMode set; return null if depth not yet available.
fun Frame.depthImage(): Image?                 // smoothed full-res 16-bit depth
fun Frame.rawDepthImage(): Image?              // unsmoothed 16-bit depth
fun Frame.rawDepthConfidenceImage(): Image?    // Y8 confidence map matching rawDepthImage()

// Frame — Scene Semantics accessors (#1730). Requires Config.SemanticMode.ENABLED.
// Outdoor only — the 12-class ML model has no indoor training data. Caller-owned Images
// (close in use { }) for the per-pixel rasters; semanticLabelFraction is a cheap
// GPU-backed query returning 0f when semantics not yet available.
fun Frame.semanticImage(): Image?                              // R8 — per-pixel label ordinal
fun Frame.semanticConfidenceImage(): Image?                    // R8 — per-pixel 0..255 confidence
fun Frame.semanticLabelFraction(label: SemanticLabel): Float   // 0f..1f — pixel-share for `label`

// Frame — CPU camera image (YUV_420_888). Caller-owned — close in use { } — the pool is only
// ~2–3 slots deep; leaking a handle for 3 frames throws ResourceExhaustedException. Resolution
// follows the active CameraConfig (see cameraConfigFilter DSL below). Returns null only on the
// first frame after resume or while paused. The standard ARCore entry-point for ML Kit / OpenCV
// pipelines. (#1733)
fun Frame.cameraImage(): Image?

// ArCoreApk — suspend-friendly availability check (the sync variant can ANR briefly).
suspend fun ArCoreApk.awaitAvailability(context: Context): ArCoreApk.Availability
```

**Usage**:

```kotlin
// Gate placement on distance — the live Frame arrives via onSessionUpdated
ARSceneView(
    onSessionUpdated = { _, frame ->
        frame.hitTest(xPx, yPx)
            .firstOrNull { it.distance < 2f }   // accept hits within 2 m only
            ?.createAnchorOrNull()
            ?.let { /* place node */ }
    }
)

// Project a 2D ML detection into 3D using a known depth
val pxX = 320f; val pxY = 240f   // detection center, camera-image pixels
val depthMeters = 1.5f           // known depth at that pixel
val k = camera.intrinsics(useTexture = false)
val xWorld = (pxX - k.principalPoint.x) * depthMeters / k.focalLength.x
val yWorld = (pxY - k.principalPoint.y) * depthMeters / k.focalLength.y

// Custom depth occlusion (caller-owned image, use { } closes it)
ARSceneView(
    onSessionUpdated = { _, frame ->
        frame.depthImage()?.use { depth ->
            // sample depth.planes[0].buffer (DEPTH16 16-bit per pixel)
        }
    }
)

// Non-blocking ARCore availability probe
var showArEntryButton by remember { mutableStateOf(false) }
LaunchedEffect(Unit) {
    val availability = ArCoreApk.getInstance().awaitAvailability(context)
    if (availability.isSupported) showArEntryButton = true
}
```

```kotlin notest external ML Kit dependency (com.google.mlkit) — not on the SDK classpath
// ML Kit object detection on the CPU image — see ARMLObjectLabelDemo
// (samples/android-demo/.../demos/ARMLObjectLabelDemo.kt) for the full pattern:
// throttled per-frame dispatch, anchor de-dup, label bitmap caching, anchor cleanup.
onSessionUpdated = { _, frame ->
    val cameraImage = frame.cameraImage() ?: return@onSessionUpdated
    val rotation = mapDisplayRotationDegrees(context.display.rotation)
    val input = InputImage.fromMediaImage(cameraImage, rotation)
    detector.process(input)
        .addOnSuccessListener { results -> /* spawn anchors; close image */ }
        .addOnFailureListener { /* close image */ }
}
```

### CameraConfig selection — `cameraConfigFilter { … }` DSL

ARCore's `Session.getSupportedCameraConfigs(filter)` returns every `CameraConfig` matching a
filter (facing direction, FPS, depth-sensor usage, stereo-camera usage). `ARSceneView` ships
two ready-to-use selectors — `::highestResolutionCameraConfig` (the default, BACK + 30 FPS) and
`::frontCameraConfig` (FRONT, for Augmented Faces) — and a DSL builder for everything else
(#1733, #1772):

```kotlin
import io.github.sceneview.ar.arcore.cameraConfigFilter
import com.google.ar.core.CameraConfig

// 60 FPS BACK config, depth sensor required (S20 Ultra / Pixel 4 XL / etc.):
ARSceneView(
    sessionCameraConfig = cameraConfigFilter {
        facing = CameraConfig.FacingDirection.BACK
        targetFps = setOf(CameraConfig.TargetFps.TARGET_FPS_60)
        depthSensor = setOf(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)
    },
    sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
    },
) { /* DSL */ }
```

All four DSL fields default to `null` ("don't filter on this axis"). Among matching configs the
selector picks the **highest-resolution** one (same policy as `highestResolutionCameraConfig`),
falling back to `session.cameraConfig` when no match exists so session creation never crashes.
Programmer errors (`emptySet()` on any of the three enum knobs) `throw IllegalArgumentException`
from `cameraConfigFilter` builder at session start — surfaced, not silently degraded to the
session default (which on some devices is the FRONT camera, a privacy concern when back-only
was requested) (#1844, #1845).

Available knobs:

```kotlin
class CameraConfigFilterBuilder {
    var facing: CameraConfig.FacingDirection?                // BACK / FRONT
    var targetFps: Set<CameraConfig.TargetFps>?              // {TARGET_FPS_30}, {TARGET_FPS_60}, or both
    var depthSensor: Set<CameraConfig.DepthSensorUsage>?     // {REQUIRE_AND_USE} / {DO_NOT_USE} / both
    var stereoCamera: Set<CameraConfig.StereoCameraUsage>?   // {REQUIRE_AND_USE} / {DO_NOT_USE} / both
}
fun cameraConfigFilter(block: CameraConfigFilterBuilder.() -> Unit): (Session) -> CameraConfig
```

Pair this with `frame.cameraImage()` (above) for custom CV pipelines: a 60 FPS / low-res config
keeps the ML model fed with fresh frames while leaving CPU headroom for inference.

### Camera config swap mid-session (back ↔ front)

`sessionCameraConfig` is **reactive** — changing it (e.g. flipping from a BACK config to
`::frontCameraConfig` to enter Augmented Faces) is applied to the live `Session`. ARCore
accepts this, but it forces a camera-pipeline restart with real, visible side effects:

- A frame drop / black flash while the new camera opens.
- Total loss of tracking — all `Anchor`s become invalid, planes and the world coordinate
  frame are re-acquired from scratch, and newly found planes will not line up with the
  pre-swap world.
- Depth-buffer format changes — front-camera configs do not expose ARCore Depth, so any
  `depthMode` / occlusion path silently downgrades when the session moves to FRONT.

**Recommended pattern — wrap the whole `ARSceneView` in `key(...)` and show a spinner**
so Compose rebuilds the session cleanly instead of mutating it live:

```kotlin
@Composable
fun CameraSwapDemo() {
    var useFrontCamera by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // Re-keying on the facing direction tears down + rebuilds the session — the
        // boring, correct way to swap cameras. The lambda below toggles a spinner.
        key(useFrontCamera) {
            ARSceneView(
                sessionFeatures = if (useFrontCamera) {
                    setOf(Session.Feature.FRONT_CAMERA)
                } else {
                    emptySet()
                },
                // BACK uses the default highestResolutionCameraConfig; FRONT needs
                // ::frontCameraConfig or the session stays on the back camera.
                sessionCameraConfig = if (useFrontCamera) ::frontCameraConfig else null,
                sessionConfiguration = { _, config ->
                    config.augmentedFaceMode = if (useFrontCamera) {
                        Config.AugmentedFaceMode.MESH3D
                    } else {
                        Config.AugmentedFaceMode.DISABLED
                    }
                },
                // First frame of the rebuilt session — the swap is done.
                onSessionUpdated = { _, _ -> switching = false }
            ) { /* nodes */ }
        }
        Button(onClick = { switching = true; useFrontCamera = !useFrontCamera }) {
            Text(if (useFrontCamera) "Use back camera" else "Use front camera")
        }
        // Cover the black flash with a loading spinner.
        if (switching) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
```

### Raw depth point cloud — confidence-filtered visualization

`Config.DepthMode.RAW_DEPTH_ONLY` exposes raw per-pixel depth **plus** a companion
single-channel **confidence image** (`Frame.acquireRawDepthConfidenceImage()` — 0 = unreliable,
255 = high confidence). Combining the two yields a "what does the device actually see" cloud
that you can filter to drop noise at object edges. See the `ar-raw-depth-point-cloud` demo
for the canonical implementation in the sample app: it acquires both images per frame, walks
the raw-depth buffer at a configurable sub-sample stride, drops every pixel below the
confidence threshold, false-colors the survivors with a warm-near / cool-far ramp, and
renders them as a screen-space cloud over the live camera feed using a Compose `Canvas`. A
`Slider` exposes the confidence threshold so the user can watch the noise drop out in real
time.

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
    },
    onSessionUpdated = { _, frame ->
        val depthImage = runCatching { frame.acquireRawDepthImage16Bits() }.getOrNull()
        val confidenceImage = runCatching { frame.acquireRawDepthConfidenceImage() }.getOrNull()
        if (depthImage != null && confidenceImage != null) {
            try {
                // depthImage.planes[0] is Y_16 (LE); confidenceImage.planes[0] is 1-byte/pixel.
                // Filter, colorize, and feed to a Compose Canvas overlay or a Filament POINTS mesh.
            } finally {
                depthImage.close()
                confidenceImage.close()
            }
        }
    },
)
```

Notes: `acquireRawDepthImage16Bits()` may return `null` for the first few frames after a
session start (depth is computed asynchronously) — handle it. Raw depth typically runs at
`~160×120`, which is already ~19 200 candidate points per frame — sub-sample with a stride of
2 or 3, and cap the cloud to a few thousand points so a Compose `Canvas` redraw stays under
the 16 ms frame budget at 60 fps. Always show an explicit "depth not supported" banner when
`Session.isDepthModeSupported(...)` is false — never leave the user staring at a black screen.

### DepthMeshNode — reify ARCore depth as a renderable Filament mesh

`rememberDepthMesh()` + `DepthMeshNode` turn the live ARCore environment depth image into an
actual Filament mesh in the scene, with **edge-discontinuity culling** so triangles don't stretch
across depth jumps. This is the foundational primitive that unblocks: shadows cast by virtual
objects onto the real world, scan / pulse / x-ray materials over real geometry, and depth-driven
physics colliders.

Requires the session depth mode set to `Config.DepthMode.AUTOMATIC` or `RAW_DEPTH_ONLY`. The mesh
stays empty until depth is available and the camera is tracking. The rebuild is costly — it runs
on an interval (default 200 ms / 5 Hz), not every frame.

**Threading:** the rebuild reads `Frame.acquireDepthImage16Bits()` and updates Filament — both
must run on the AR frame / GL-main thread. The composable handles this for you (the internal
`LaunchedEffect` posts the work to the right thread); **do not** call `node.rebuild()` or
`node.latestSnapshot` consumers from a background coroutine. See also: `Frame.hitTestDepth`
(raycast against the same depth image) and `rememberDepthCollider` (depth → physics collider).

**Cross-platform:** Android-only. iOS equivalent is `ARMeshAnchor` via `ARWorldTrackingConfiguration.sceneReconstruction = .mesh` (LiDAR-only). SceneViewSwift wrapper tracked in #1860 — see [cheatsheet-ios.md "AR Depth & Cloud Anchors"](docs/docs/cheatsheet-ios.md) (#1813). Web tracked in #1778.

```kotlin
@Composable fun ARSceneScope.rememberDepthMesh(
    refreshIntervalMs: Long = 200L,            // min ms between rebuilds; 5 Hz by default
    stride: Int = 4,                            // pixel stride between vertices (higher = coarser)
    edgeThresholdMeters: Float = 0.10f,         // depth-jump above which triangles are culled
    materialInstance: MaterialInstance? = null, // e.g. a transparent shadow-receiver material
    builder: RenderableManager.Builder.() -> Unit = {},
    onMeshRebuilt: ((DepthMeshSnapshot) -> Unit)? = null,
): DepthMeshNode

@Composable fun ARSceneScope.DepthMeshNode(
    node: DepthMeshNode,
    apply: DepthMeshNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
)

data class DepthMeshSnapshot(
    val positions: FloatArray,   // camera-space, flat-packed [x0,y0,z0, x1,y1,z1, ...]
    val indices: IntArray,       // triangle indices (3 ints per triangle)
    val width: Int,              // grid width in vertices
    val height: Int,             // grid height in vertices
    val worldTransform: Transform, // camera pose at rebuild time
    val timestampMs: Long,
)
```

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
    }
) {
    val depthMesh = rememberDepthMesh()  // 5 Hz refresh, stride 4, 10 cm edge threshold
    DepthMeshNode(depthMesh)             // attaches it to the scene as a renderable
}
```

The `onMeshRebuilt` callback (and `node.latestSnapshot`) expose the freshly-computed vertex /
index buffers in camera space so downstream consumers — a depth-driven physics collider, a
debug-overlay point cloud, an exporter — can read the geometry without poking Filament
internals.

### PointCloudNode — render ARCore feature points as a scene point cloud

`rememberPointCloud()` + `PointCloudNode` render ARCore's live tracking feature points
(`Frame.acquirePointCloud()`) as a real Filament `POINTS` primitive in the 3D scene — the
SceneView equivalent of AR Foundation's `ARPointCloudManager`. Use it for debug overlays,
"scanning" feedback, procedural-art demos, or surfacing tracking quality to the user.

The points are the **sparse, world-space** feature points ARCore uses for motion tracking
(distinct from the dense *depth image* sampled by `DepthMeshNode`). Each point carries a
`[0, 1]` confidence value; points below `confidenceThreshold` are filtered out. The node stays
at the scene origin since the points are already in world space.

**Threading:** the per-frame update reads `Frame.acquirePointCloud()` and uploads Filament
buffers on the AR frame / GL-main thread. The composable handles this for you; do not poke the
node from a background coroutine.

**Point size:** Filament renders `POINTS` at a fixed 1-pixel size with the default unlit
material — variable per-point size needs a custom `gl_PointSize` shader and is a deliberate
non-goal. Color is fully configurable via `materialInstance` (use
`materialLoader.createUnlitColorInstance(...)`).

**Cross-platform:** Android-only. iOS equivalent is `ARPointCloud` from `ARFrame.rawFeaturePoints`.

```kotlin
@Composable fun ARSceneScope.rememberPointCloud(
    confidenceThreshold: Float = 0.2f,          // min ARCore confidence in [0, 1]
    refreshIntervalMs: Long = 0L,               // 0 = rebuild every frame; >0 rate-limits (e.g. 200 = 5 Hz)
    materialInstance: MaterialInstance? = null, // e.g. createUnlitColorInstance(Color.Cyan)
    builder: RenderableManager.Builder.() -> Unit = {},
    onPointCloudUpdated: ((pointCount: Int) -> Unit)? = null,
): PointCloudNode

@Composable fun ARSceneScope.PointCloudNode(
    node: PointCloudNode,
    apply: PointCloudNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
)
```

```kotlin
val materialLoader = rememberMaterialLoader(engine)
ARSceneView(modifier = Modifier.fillMaxSize()) {
    val pointCloud = rememberPointCloud(
        materialInstance = materialLoader.createUnlitColorInstance(Color.Cyan),
    )
    PointCloudNode(pointCloud)
}
```

### rememberDepthCollider — bounce virtual bodies off the real world

`rememberDepthCollider()` builds a static physics collider from the live ARCore depth mesh so
`PhysicsNode` bodies bounce off the **real** floor / table / wall (Depth Lab "Collider", #1713).
Thin physics wrapper over `DepthMeshNode` (#1739) — same edge-discontinuity culling, no curtain
triangles across depth jumps. See also: `DepthMeshNode` (the underlying renderable mesh) and
`Frame.hitTestDepth` (single-ray raycast against the same depth image).

**Cross-platform:** Android-only. iOS equivalent rebuilds `CollisionComponent`s per `ARMeshAnchor` (LiDAR-only, requires Scene Reconstruction). SceneViewSwift wrapper tracked in #1860 — see [cheatsheet-ios.md "AR Depth & Cloud Anchors"](docs/docs/cheatsheet-ios.md) (#1813). Web tracked in #1778.

```kotlin
@Composable fun ARSceneScope.rememberDepthCollider(
    refreshIntervalMs: Long = 200L,  // mesh rebuild rate; 200 ms = 5 Hz
    stride: Int = 4,                 // pixel stride between depth samples
    edgeThresholdMeters: Float = 0.10f,
    renderMesh: Boolean = false,     // also render the underlying mesh (debug / shadow receiver)
): DepthCollider
```

`DepthCollider` implements `FloorProvider` — pass it as the `floorProvider` parameter on
`PhysicsNode`:

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
    }
) {
    val depthCollider = rememberDepthCollider(refreshIntervalMs = 200L)
    val ballMaterial = remember(materialLoader) { materialLoader.createColorInstance(Color.Red) }
    var ballNode by remember { mutableStateOf<SphereNode?>(null) }
    SphereNode(
        radius = 0.05f,
        materialInstance = ballMaterial,
        position = Position(0f, 0.5f, -0.3f),
        apply = { ballNode = this },
    )
    ballNode?.let { node ->
        PhysicsNode(
            node = node,
            restitution = 0.7f,
            radius = 0.05f,
            floorY = -1f,                 // fallback if depth unavailable
            floorProvider = depthCollider, // bounce off real geometry when depth is on
        )
    }
}
```

Optional region-near-bodies culling reduces the per-frame, per-body triangle test set when many
bodies are clustered and the mesh is dense:
```kotlin
depthCollider.setBodiesRegion(
    centres = floatArrayOf(node.worldPosition.x, node.worldPosition.y, node.worldPosition.z),
    padding = 0.3f,
)
```

`floorYAt(x, y, z, radius)` returns the world-space Y of the highest depth-mesh triangle under
the body's XZ, or `null` when no surface is available — `PhysicsBody` then falls back to its
static `floorY` plane. Requires `Config.DepthMode.AUTOMATIC` (or `RAW_DEPTH_ONLY`); without it
the collider stays empty.

### AR depth-of-field — Filament bokeh driven by ARCore depth (#1716)

`arDepthOfField(view, camera, options)` turns on Filament's native depth-of-field post-pass and
points its focus distance at any meters value — tapping a near object throws the far background
out of focus and vice-versa, because `ARCameraStream`'s depth-occlusion material already writes
real-world depth into Filament's z-buffer (`gl_FragDepth` in `camera_stream_depth.mat`). Filament
samples the same z-buffer for DoF, so the bokeh blur kicks in for both the virtual scene and the
camera background without any new render pass — no `.filamat` recompile required.

Inspired by Google's [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab)
"Depth-of-Field" sample.

**Hard requirements** (callers must satisfy both, otherwise the effect degrades silently):
1. `Config.depthMode` is `AUTOMATIC` or `RAW_DEPTH_ONLY` (without it, no depth image → background
   never blurs).
2. `ARCameraStream.isDepthOcclusionEnabled = true` (that's the flag that swaps in the
   `gl_FragDepth`-writing camera material — see "Depth occlusion" for the longer story).

**Off by default; zero cost when disabled.** Filament fully skips the DoF post-pass when
`View.DepthOfFieldOptions.enabled` is `false`, which is the state `arDepthOfField` lands in when
`ARDepthOfFieldOptions.enabled = false`. No measurable frame-budget impact on disabled frames.

**Cross-platform:** Android-only. iOS equivalent is `RealityKit`'s camera DoF via
`PerspectiveCameraComponent.depthOfFieldOptions` — porting tracked in the iOS parity umbrella.
Web tracked separately.

```kotlin
data class ARDepthOfFieldOptions(
    val focusDepth: Float,             // meters; pair with Frame.depthFocusDistance() for tap-to-focus
    val blurStrength: Float = 1.0f,    // 0 = effectively off, 1 = stock cinematic, clamped to [0, 8]
    val enabled: Boolean = true,
)

@Composable fun arDepthOfField(
    view: View,
    camera: CameraComponent,
    options: ARDepthOfFieldOptions,
)

// Returns the real-world distance from the camera to the tapped pixel, or null when depth
// is unavailable. Internally Frame.hitTestDepth(...) — same requirements.
fun Frame.depthFocusDistance(xPx: Float, yPx: Float): Float?
```

Typical tap-to-focus loop:

```kotlin
var focusDepth by remember { mutableStateOf(1.0f) }
var blurStrength by remember { mutableStateOf(2.0f) }
var latestFrame by remember { mutableStateOf<Frame?>(null) }

val view = rememberARView(engine)
val cameraNode = rememberARCameraNode(engine)

arDepthOfField(
    view = view,
    camera = cameraNode,
    options = ARDepthOfFieldOptions(focusDepth, blurStrength),
)

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    view = view,
    cameraNode = cameraNode,
    sessionConfiguration = { _, config -> config.depthMode = Config.DepthMode.AUTOMATIC },
    cameraStream = rememberARCameraStream(materialLoader = materialLoader, creator = {
        createARCameraStream(materialLoader).apply { isDepthOcclusionEnabled = true }
    }),
    onSessionUpdated = { _, frame -> latestFrame = frame },
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { e, _ ->
            latestFrame?.depthFocusDistance(e.x, e.y)?.let { focusDepth = it }
        }
    )
)
```

`blurStrength` maps to Filament's `View.DepthOfFieldOptions.cocScale` (identity at `1.0`), so
existing Filament tuning advice (cocScale, ring counts, max-CoC bounds) still applies — drop in
`view.depthOfFieldOptions = view.depthOfFieldOptions.apply { … }` alongside `arDepthOfField` for
fine-grained control. See the `ar-depth-of-field` demo for the canonical wiring.

### Scene Semantics — per-pixel outdoor classification (#1730)

ARCore's Scene Semantics labels every pixel of an **outdoor** camera frame with one of 12 classes
through an on-device ML model (`SKY`, `BUILDING`, `TREE`, `ROAD`, `SIDEWALK`, `TERRAIN`,
`STRUCTURE`, `OBJECT`, `VEHICLE`, `PERSON`, `WATER`, `UNLABELED`). The AI-first use case is
semantic placement rules — "place models only on `TERRAIN`, never on `SKY`/`PERSON`/`VEHICLE`" —
that an assistant can generate without reading per-pixel rasters.

```kotlin
import com.google.ar.core.Config
import com.google.ar.core.SemanticLabel
import io.github.sceneview.ar.arcore.semanticImage
import io.github.sceneview.ar.arcore.semanticConfidenceImage
import io.github.sceneview.ar.arcore.semanticLabelFraction

ARSceneView(
    sessionConfiguration = { session, config ->
        // Capability gate — the on-device ML model ships only on a subset of Google Play
        // Services for AR devices. ARSceneView's internal resolveSemanticMode gate applies
        // the same fallback if you forget to test for support yourself.
        if (session.isSemanticModeSupported(Config.SemanticMode.ENABLED)) {
            config.semanticMode = Config.SemanticMode.ENABLED
        }
    },
    onSessionUpdated = { _, frame ->
        // Cheap GPU-backed query — answers "is there a lot of sky / road / terrain in view?"
        // without acquiring the per-pixel raster.
        val groundFraction = frame.semanticLabelFraction(SemanticLabel.TERRAIN)
        if (groundFraction > 0.3f) { /* enough ground in view to place a model */ }

        // Per-pixel raster (R8, label ordinal per pixel — `SemanticLabel.forNumber(byte)`).
        // Caller-owned Image — close in `use { }` or ARCore will throw ResourceExhausted
        // after 2-3 leaked frames. Returns null while semantics are warming up.
        frame.semanticImage()?.use { image -> /* walk image.planes[0].buffer */ }

        // Per-pixel confidence (R8, 0..255). Same lifecycle as semanticImage().
        frame.semanticConfidenceImage()?.use { image -> /* filter low-confidence pixels */ }
    },
)
```

**Outdoor only.** The model has no indoor training data — pointing at a living room returns
mostly `UNLABELED`. **Support-gated.** `Session.isSemanticModeSupported()` is the canonical
probe; on unsupported devices `ARSceneView` silently downgrades to `DISABLED` rather than
throwing. **`semanticLabelFraction` returns 0f** when semantics are disabled or not yet
available — making "place on TERRAIN" rules naturally wait for the first usable frame.

**Visualizing the segmentation (#1868).** To render the per-pixel labels as a tinted overlay
instead of just reading them, use the built-in `semantics_overlay.filamat` material. Upload the
`semanticImage()` R8 raster into a Filament `R8` `Texture`, then paint a quad with the overlay
material instance — the shader maps each of the 12 label ordinals to a fixed colour (`UNLABELED`
stays transparent):

```kotlin
import io.github.sceneview.material.setSemanticsOpacity

// `opacity` blends camera (0f) ↔ semantic overlay (1f); re-upload the texture each frame.
val overlay = materialLoader.createSemanticsOverlayInstance(semanticTexture, opacity = 0.6f)
overlay.setSemanticsOpacity(blendSlider)   // MaterialInstance.setSemanticsTexture also exists
```

Sample: `ARSceneSemanticsDemo` (Android demo registry id `ar-scene-semantics`) — a camera ↔
semantic blend slider over the overlay material, plus the top-3 label HUD.

### People Occlusion — virtual content hides behind real people (#1761)

`ARCameraStream.isPersonOcclusionEnabled = true` occludes virtual objects behind **real
people** using the Scene Semantics `PERSON`-class segmentation mask — the flagship
cross-ecosystem AR effect (ARKit `ARFrame.segmentationBuffer`, AR Foundation
`AROcclusionManager`, Niantic Lightship). When a real person walks in front of a placed
virtual object, the object is correctly hidden.

Enabling it selects the `camera_stream_person_occlusion.filamat` camera material — a strict
superset of the depth-occlusion material: it keeps the depth path (static real-world geometry
still occludes) and additionally pushes the camera fragment to the near clip plane wherever
the per-frame `PERSON` mask is set. People occlusion therefore **implies depth occlusion**.

```kotlin
import com.google.ar.core.Config
import io.github.sceneview.ar.createARCameraStream
import io.github.sceneview.ar.rememberARCameraStream

ARSceneView(
    sessionConfiguration = { session, config ->
        // People occlusion is driven by Scene Semantics — enable it (support-gated).
        if (session.isSemanticModeSupported(Config.SemanticMode.ENABLED)) {
            config.semanticMode = Config.SemanticMode.ENABLED
        }
        // Keep depth on so static geometry still occludes (the people material needs it).
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        }
    },
    cameraStream = rememberARCameraStream(materialLoader = materialLoader, creator = {
        createARCameraStream(materialLoader).apply { isPersonOcclusionEnabled = true }
    }),
)
// Imperative toggle also works: cameraStream.isPersonOcclusionEnabled = true
```

**Requirements & honest limits.** Requires `Config.SemanticMode.ENABLED` — SceneView does NOT
auto-enable it. Scene Semantics is ARCore's only segmentation surface and carries real limits:
**outdoor scenes only** (the on-device model has no indoor training data; devices without the
model silently no-op), **coarse resolution** (the mask is typically 256×144, so the cutout
edge is blockier than ARKit's dedicated person model), and a **frame or two of latency** on
fast motion. For a hard cutout against authored geometry use `createOcclusionInstance()`
instead.

Sample: `ARPeopleOcclusionDemo` (Android demo registry id `ar-people-occlusion`) — place a
model, walk a person in front of it; a HUD shows the live `PERSON`-pixel fraction.

### AugmentedImageNode — image tracking
```kotlin
@Composable fun AugmentedImageNode(
    augmentedImage: AugmentedImage,
    applyImageScale: Boolean = false,
    visibleTrackingMethods: Set<TrackingMethod> = setOf(TrackingMethod.FULL_TRACKING, TrackingMethod.LAST_KNOWN_POSE),
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onTrackingMethodChanged: ((TrackingMethod) -> Unit)? = null,
    onUpdated: ((AugmentedImage) -> Unit)? = null,
    apply: AugmentedImageNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### RuntimeAugmentedImageDatabase — register reference images on-device at runtime
```kotlin
// Grows an AugmentedImageDatabase at runtime — no pre-bundled `arcoreimg` database needed.
@Composable fun rememberRuntimeAugmentedImageDatabase(): RuntimeAugmentedImageDatabase

class RuntimeAugmentedImageDatabase {
    val size: Int
    val imageNames: List<String>
    fun bind(session: Session)                          // call from onSessionCreated
    fun unbind()                                        // call from onSessionPaused/teardown
    fun applyTo(config: Config, session: Session)       // MAIN thread — call from sessionConfiguration
    suspend fun addImage(name: String, bitmap: Bitmap,  // bitmap must be ARGB_8888
                         widthInMeters: Float? = null): AddImageResult
    suspend fun clear()
}
// addImage runs ARCore feature extraction off the main thread, then re-applies the session
// config ON the main thread itself. Returns a typed result:
sealed class AddImageResult { object Added; object LowQuality; data class Error(val cause: Throwable) }

// Grab the live AR camera frame as an upright ARGB_8888 bitmap (run OFF the main thread):
fun Frame.captureCameraBitmap(jpegQuality: Int = 90): Bitmap?
fun Image.toArgbBitmap(rotationDegrees: Int = 0, jpegQuality: Int = 90): Bitmap?
```
Usage — capture a photo on-device and start tracking it (closes #1553):
```kotlin
val runtimeDb = rememberRuntimeAugmentedImageDatabase()
var latestFrame: Frame? = null
ARSceneView(
    sessionConfiguration = { session, config -> runtimeDb.applyTo(config, session) },
    onSessionCreated = { runtimeDb.bind(it) },
    onSessionUpdated = { _, frame -> latestFrame = frame }
) { /* AugmentedImageNode per detected image */ }
// From a "Capture" button:
scope.launch {
    val photo = withContext(Dispatchers.Default) { latestFrame?.captureCameraBitmap() }
    when (runtimeDb.addImage("snapshot", photo!!)) {
        is AddImageResult.Added      -> { /* now tracked */ }
        is AddImageResult.LowQuality -> { /* show retry hint — textured, high-contrast scene needed */ }
        is AddImageResult.Error      -> { /* show error */ }
    }
}
```
Sample: `ARImageDemo` (Android demo registry id `ar-image`) — "Capture this view" button.

### AugmentedFaceNode — face mesh
```kotlin
@Composable fun AugmentedFaceNode(
    augmentedFace: AugmentedFace,
    meshMaterialInstance: MaterialInstance? = null,
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((AugmentedFace) -> Unit)? = null,
    apply: AugmentedFaceNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### Body tracking — BodyPose + Joint (image-space, MediaPipe Pose) (#1763)
ARCore has **no native body-tracking API** (unlike ARKit's `ARBodyTrackingConfiguration`
+ `BodyTrackedEntity`). SceneView's Android body-tracking path feeds the AR CPU camera
image to Google's on-device **MediaPipe Pose Landmarker** and exposes the result through
two renderer-agnostic value types in `io.github.sceneview.ar.body`:

```kotlin notest type-shape listing — mirrors the shipped io.github.sceneview.ar.body types (RawLandmark is nested in the real BodyPose)
// 17 ARKit-parity joint names (ARSkeleton.JointName upper tier) — reads the same on
// Android and SceneViewSwift. ROOT/SPINE/NECK are synthesised midpoints.
enum class Joint { ROOT, SPINE, NECK, HEAD,
    LEFT_SHOULDER, LEFT_ELBOW, LEFT_HAND, RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_HAND,
    LEFT_HIP, LEFT_KNEE, LEFT_FOOT, RIGHT_HIP, RIGHT_KNEE, RIGHT_FOOT }

// One detected pose. x/y are normalised [0,1] in IMAGE space; z is a RELATIVE depth.
data class BodyPose(val landmarks: Map<Joint, BodyLandmark>) {
    val isTracked: Boolean
    operator fun get(joint: Joint): BodyLandmark?
    companion object {
        fun fromMediaPipeLandmarks(landmarks: List<RawLandmark>?): BodyPose
    }
}
data class BodyLandmark(val joint: Joint, val x: Float, val y: Float, val z: Float,
                        val inFrameLikelihood: Float)

// Bone topology for drawing a skeleton overlay.
val SKELETON_BONES: List<Pair<Joint, Joint>>
```

**⚠️ Parity caveat — read before relying on this.** This is **image-space** tracking, not
ARKit's world-anchored 3D skeleton. `x`/`y` are normalised pixel coordinates and `z` is a
relative depth with no absolute scale or AR-world anchoring. It is ideal for 2D skeleton
overlays, fitness/gesture detection and on-screen AR filters, but is **not** a drop-in
replacement for `BodyTrackedEntity` — you cannot reliably weld a 3D model to a limb in
world space from MediaPipe landmarks. The MediaPipe runtime + `.task` model are a
**sample-only dependency**: the published `arsceneview` artifact carries only the
`BodyPose` / `Joint` value types, not `com.google.mediapipe`. See the `ar-body-tracker`
demo for the full camera-feed → `BodyPose` → skeleton-overlay pipeline.

### CloudAnchorNode — cross-device persistent anchors
```kotlin
@Composable fun CloudAnchorNode(
    anchor: Anchor,
    cloudAnchorId: String? = null,
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((Anchor?) -> Unit)? = null,
    onHosted: ((cloudAnchorId: String?, state: Anchor.CloudAnchorState) -> Unit)? = null,
    apply: CloudAnchorNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)

// Imperative host call with explicit ttl (1..365 days, ARCore persistence limit):
cloudAnchorNode.host(session, ttlDays = 7) { cloudAnchorId, state ->
    if (cloudAnchorId != null && !state.isError) {
        // Persist the ID locally so we can re-resolve across launches:
        registry.add(CloudAnchorEntry(
            name = "Living room marker",
            cloudAnchorId = cloudAnchorId,
            hostedAtEpochMs = System.currentTimeMillis(),
            ttlDays = 7
        ))
    }
}

// host / resolve return the underlying ARCore Future — cancel pending requests on dispose
// to avoid running the network round-trip (and accruing Google Cloud billing) after the
// user has navigated away. (#1768)
val future: HostCloudAnchorFuture = cloudAnchorNode.host(session, ttlDays = 1)
DisposableEffect(cloudAnchorNode) { onDispose { future.cancel() } }

val resolveFuture: ResolveCloudAnchorFuture = CloudAnchorNode.resolve(
    engine, session, cloudAnchorId
) { state, node -> /* ... */ }
DisposableEffect(cloudAnchorId) { onDispose { resolveFuture.cancel() } }
```

**Persistence — `ttlDays`.** `CloudAnchorNode.host(session, ttlDays = 1)` accepts any value in `CloudAnchorNode.TTL_DAYS_RANGE` (= `1..365`, inclusive). Values outside this range throw `IllegalArgumentException`. Pick a short ttl for ephemeral collaborative sessions, a longer one (e.g. 7..30) for "leave behind" multi-day experiences.

**Privacy disclosure (required).** ARCore Cloud Anchors upload feature points from the user's surroundings to Google. Surface a clear in-app disclosure and link to the ARCore data policy (https://developers.google.com/ar/data-privacy) before hosting.

**Cross-platform:** iOS ships the **Future + cancel-on-dispose lifecycle** as `SceneViewSwift.CloudAnchorNode.host(ttlDays:completion:operation:)` / `.resolve(cloudAnchorId:completion:operation:)`, each returning a cancellable `CloudAnchorFuture` — cancel it from SwiftUI `.onDisappear` (the analogue of Android's `DisposableEffect { onDispose { future.cancel() } }`), #1859. To keep the core library dependency-free, the actual `GARSession.hostCloudAnchor` round-trip (Google's `arcore-ios-sdk` Swift Package) is supplied by the app through the `operation` closure; `CloudAnchorFuture` owns only the portable, single-fire cancellation gate (never fires after `cancel()`/dealloc). See [cheatsheet-ios.md "AR Depth & Cloud Anchors"](docs/docs/cheatsheet-ios.md) (#1813). Web has no Cloud Anchor runtime.

### CloudAnchorRegistry — local persistence helper for hosted Cloud Anchor IDs

The library does NOT manage Cloud Anchor lifetime — that lives server-side on Google's ARCore API. But apps that host long-ttl anchors typically want a small client-side index keyed by a user-meaningful name so the IDs can be passed back to `CloudAnchorNode.resolve(...)` on the next launch. `CloudAnchorRegistry` is exactly that helper.

```kotlin
data class CloudAnchorEntry(
    val name: String,
    val cloudAnchorId: String,
    val hostedAtEpochMs: Long,
    val ttlDays: Int                       // validated against 1..365 at construction
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean
    val expiresAtEpochMs: Long
}
```

```kotlin
interface CloudAnchorRegistry {
    fun add(entry: CloudAnchorEntry)        // insert or replace by name
    fun remove(name: String): Boolean
    fun get(name: String): CloudAnchorEntry?
    fun list(): List<CloudAnchorEntry>
    fun clear()
    fun purgeExpired(now: Long = System.currentTimeMillis()): Int
}
```

Default implementation — SharedPreferences-backed, non-blocking writes (`apply()`):

```kotlin notest type-shape listing — the real class implements every CloudAnchorRegistry member and the secondary constructor delegates internally
class SharedPreferencesCloudAnchorRegistry(
    prefs: SharedPreferences
) : CloudAnchorRegistry {
    constructor(context: Context, prefsName: String = "sceneview_cloud_anchors")
}
```

**Re-resolve across launches:**
```kotlin
val registry = SharedPreferencesCloudAnchorRegistry(context)
registry.list().filterNot { it.isExpired() }.forEach { entry ->
    CloudAnchorNode.resolve(engine, session, entry.cloudAnchorId) { state, node -> /* attach */ }
}
```

Threading: safe to call from the main thread — `SharedPreferences.edit().apply()` is non-blocking.

### Collaborative AR — multi-user sessions (`io.github.sceneview.ar.collaborative`)

Two or more devices see the **same** anchored content and each other's live camera poses.

**ARCore reality check.** ARCore has NO `collaborationData` / `ParticipantManager` (unlike ARKit's `ARSession.collaborationData`). Its entire shared-AR story is Cloud Anchors. SceneView's collaborative layer is therefore built on the two pieces that DO exist: a shared coordinate frame via the existing `CloudAnchorNode` (one device hosts, every other resolves the same id), plus a **pluggable transport** relaying app state. SceneView does NOT pick a networking stack — it ships the interface plus an in-process reference impl.

```kotlin
// Pluggable network layer — app supplies one, or uses the loopback reference impl.
interface CollaborativeTransport {
    val localPeerId: String
    val peers: StateFlow<Set<String>>                 // remote peers, never includes self
    fun send(message: ByteArray)                      // non-blocking — may be called from the render loop
    fun incoming(handler: (peerId: String, ByteArray) -> Unit): AutoCloseable
    fun close()
}
```

```kotlin
// In-process reference transport — always available, no networking, no permissions.
// Perfect for unit tests + single-device demos. Swap for Nearby Connections /
// Firebase / WebRTC in production.
val hub = LoopbackCollaborativeTransport.LoopbackHub()
val transport = hub.join("my-peer-id")               // join one per simulated device

// Orchestrator over CloudAnchorNode + the transport. Mirrors RerunBridge:
// supervisor IO scope, CONFLATED outbox, lifecycle-bound remember* helper.
@Composable fun rememberCollaborativeSession(
    transport: CollaborativeTransport,
    displayName: String = transport.localPeerId,
    poseRateHz: Int = 10,                             // camera-pose broadcast rate
    closeTransportOnDispose: Boolean = true
): CollaborativeSession

class CollaborativeSession {
    val participants: List<Participant>               // Compose-observable, remote peers only
    val placedNodes: List<PlacedNode>                 // every peer's placed objects, shared-anchor space
    val sharedAnchorNode: CloudAnchorNode?            // the shared coordinate frame
    val sharedCloudAnchorId: String?

    fun start()
    fun stop()
    fun host(session: Session, anchor: Anchor, engine: Engine,
             ttlDays: Int = 1, name: String = "session",
             onHosted: ((cloudAnchorId: String?, success: Boolean) -> Unit)? = null)
    fun resolve(engine: Engine, session: Session, cloudAnchorId: String,
                onResolved: ((node: CloudAnchorNode?) -> Unit)? = null)
    fun onFrame(frame: Frame)                         // call from onSessionUpdated — broadcasts camera pose
    fun placeNode(nodeKey: String, modelKey: String,
                  translation: FloatArray, quaternion: FloatArray,
                  scale: FloatArray = floatArrayOf(1f, 1f, 1f))
}
```

**Usage** — one device hosts the shared anchor, the rest resolve it; every device broadcasts its camera pose each frame and mirrors `placedNodes`:

```kotlin
@Composable
fun MultiplayerARScreen() {
    val transport = remember { LoopbackCollaborativeTransport.LoopbackHub().join("me") }
    val session = rememberCollaborativeSession(transport, displayName = "Alice")
    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        onSessionUpdated = { _, frame -> session.onFrame(frame) },
    ) {
        // Parent collaborative content to session.sharedAnchorNode (the shared
        // Cloud Anchor) and reconcile your scene against session.placedNodes
        // and session.participants every frame.
    }
}
```

- **Wire format** — `CollaborativeWireFormat`: JSON-lines (`hello` / `anchor` / `pose` / `node` / `bye`), pure Kotlin, zero new runtime deps, fully unit-tested. All transforms are in the shared anchor's local space so they are comparable across devices.
- **Conflict policy** — last-writer-wins per node key (`PlacedNode`); stale (out-of-order) poses are dropped by epoch.
- **Threading** — `host`/`resolve` are main-thread only (ARCore + Filament JNI). `onFrame`/`placeNode` are non-blocking (conflated channel) so they are safe in the AR render callback. All merge work runs on a supervisor IO scope.
- **Privacy** — the shared anchor is an ARCore Cloud Anchor; the same disclosure requirement as `CloudAnchorNode.host` applies (feature points uploaded to Google).

- **Production transport — `NearbyCollaborativeTransport`** (`io.github.sceneview.ar.collaborative`). A real peer-to-peer `CollaborativeTransport` backed by Google's Nearby Connections API — offline, same-room, no backend, no API keys. Uses the `P2P_CLUSTER` strategy (every device advertises *and* discovers, so N peers form one mesh) and frames messages as the same JSON-lines wire format. `LoopbackCollaborativeTransport` stays the unit-test / single-device transport; this is the cross-device one. Play Services Nearby is a `compileOnly` dependency of `arsceneview` (same pattern as `androidx.xr.arcore`) — an app that uses this class adds `implementation("com.google.android.gms:play-services-nearby:19.3.0")` itself, and must request the nearby-device runtime permissions (`NearbyCollaborativeTransport.REQUIRED_PERMISSIONS_API_31_PLUS` / `REQUIRED_PERMISSIONS_PRE_API_31`) before `start()`.

```kotlin
// After the nearby-device permissions are granted:
val transport = NearbyCollaborativeTransport(context, serviceId = "my.ar.app")
val session = CollaborativeSession(transport, displayName = "Alice")
transport.start()   // begin advertising + discovering
session.start()
// teardown: session.stop(); transport.close()
```

**Trust model & hardening:** by default the transport auto-accepts every device advertising the same `serviceId` (the id is broadcast in cleartext — a rendezvous key, not a secret). For a real trust boundary pass the optional `shouldAcceptConnection: (peerId, authenticationDigits) -> Boolean` constructor parameter and compare the digits out of band; returning `false` rejects the connection before any payload flows. Defense in depth is built in: inbound messages are bound to the *connection-bound* transport peer id (a body claiming another `peer` is dropped as spoofed; a second live connection claiming an already-connected peer id is rejected — but peer ids are self-advertised names, so real identity assurance still requires the digits comparison), `CollaborativeState` rosters are capped (`MAX_PARTICIPANTS` = 64, `MAX_NODES` = 1024, overridable via its constructor), and `PlacedNode.modelKey` arrives from an untrusted peer — validate it against an allow-list, never use it directly in an asset path.

**Cross-platform:** Android-only today. The `CollaborativeTransport` abstraction maps cleanly onto RealityKit's `MultipeerConnectivityService` on iOS — the interface shape is kept cross-platform-friendly. The `ar-collaborative` sample demo proves the full sync end-to-end on one device via the loopback transport.

### TrackableNode — generic trackable
```kotlin
@Composable fun <T : Trackable> TrackableNode(
    trackable: T,
    visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((T) -> Unit)? = null,
    apply: TrackableNode<T>.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### PlaneNode — detected plane + lifecycle (AR Foundation `ARPlaneManager` parity, #1774)

A node anchored to a detected ARCore `Plane`. It follows the plane's `centerPose` every frame so child content rides the plane as ARCore refines its extents. Pair it with `rememberDetectedPlanes` — the SceneView equivalent of AR Foundation's `ARPlaneManager.planesChanged` — to declare one node per detected plane reactively, with no `frame.getUpdatedTrackables(Plane::class.java)` loop.

```kotlin
import io.github.sceneview.ar.node.PlaneNode  // the AR trackable node, not io.github.sceneview.node.PlaneNode

@Composable fun ARSceneScope.PlaneNode(
    plane: Plane,
    visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((Plane) -> Unit)? = null,
    apply: PlaneNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)

// ARPlaneManager.planesChanged equivalent — live State + add/update/remove callbacks.
@Composable fun rememberDetectedPlanes(
    session: Session?,
    onAdded: ((List<Plane>) -> Unit)? = null,
    onUpdated: ((List<Plane>) -> Unit)? = null,
    onRemoved: ((List<Plane>) -> Unit)? = null
): State<List<Plane>>
```

`rememberDetectedPlanes` re-reads `session.getAllTrackables(Plane::class.java)` each Compose frame (`withFrameNanos`, main thread), filters to `TrackingState.TRACKING`, and fires the three callbacks on every change. A plane subsumed into a larger coplanar one (`Plane.subsumedBy`) naturally surfaces through `onRemoved`.

```kotlin
var detectedCount by remember { mutableStateOf(0) }
val markerMat = remember(materialLoader) { materialLoader.createColorInstance(Color.Cyan) }
ARSceneView(onSessionCreated = { arSession = it }) {
    val planes by rememberDetectedPlanes(
        session = arSession,
        onAdded = { added -> detectedCount += added.size }
    )
    planes.forEach { plane ->
        PlaneNode(plane = plane) {
            CubeNode(size = Size(0.08f), materialInstance = markerMat)
        }
    }
}
```

`PlaneNode` exposes `type` (`Plane.Type`), `extentX`, `extentZ`. Demo: `samples/android-demo/.../ARPlaneNodeDemo.kt`.

### Geospatial accessors — `rememberCameraGeospatialPose`, `rememberEarthState`, `awaitVpsAvailability`

Composable State + suspend helpers around ARCore's Geospatial API (#1769). Lets apps read the live device lat/lng/altitude, observe the `Earth.EarthState`, and gate Terrain anchor placement on actual VPS coverage — all from regular Compose code.

```kotlin
// Camera lat/lng/altitude — re-read each frame, null until Earth is TRACKING.
@Composable fun rememberCameraGeospatialPose(session: Session?): State<GeospatialPose?>

// Snapshot every field of a GeospatialPose into a data class (the raw GeospatialPose handle
// becomes invalid as soon as the next frame advances — snapshot to retain).
data class GeospatialPoseSnapshot(
    val latitude: Double, val longitude: Double, val altitude: Double,
    val heading: Double,
    val horizontalAccuracy: Double, val verticalAccuracy: Double,
    val orientationYawAccuracy: Double
)
fun GeospatialPose.snapshot(): GeospatialPoseSnapshot

// EarthState as Compose State — react in UI to ERROR_NOT_AUTHORIZED (missing Cloud key),
// ERROR_RESOURCE_EXHAUSTED (out of quota), ERROR_APK_VERSION_TOO_OLD, etc.
@Composable fun rememberEarthState(session: Session?): State<Earth.EarthState?>

// VPS availability — single Cloud round-trip, surfaces "is Terrain anchor placement viable
// at this lat/lng" so apps don't fire ResolveAnchorOnTerrainFuture into the void.
suspend fun Session.awaitVpsAvailability(latitude: Double, longitude: Double): VpsAvailability
```

```kotlin
ARSceneView(
    sessionConfiguration = { _, config ->
        config.geospatialMode = Config.GeospatialMode.ENABLED
    },
    onSessionCreated = { arSession = it },
) {
    val pose by rememberCameraGeospatialPose(arSession)
    val state by rememberEarthState(arSession)

    LaunchedEffect(arSession) {
        val available = arSession?.awaitVpsAvailability(48.8584, 2.2945)
        if (available == VpsAvailability.AVAILABLE) {
            // place Terrain anchor at the Eiffel Tower
        }
    }
}
```

Requires `Config.GeospatialMode.ENABLED`, the ARCore Cloud API key + `ACCESS_FINE_LOCATION` permission documented in the API key warning above. `rememberCameraGeospatialPose` returns `null` until ARCore acquires a GPS lock (the EarthState transitions to `ENABLED` *and* `Earth.trackingState` reaches `TRACKING`); `rememberEarthState` surfaces every error code so the UI can show the right "missing Cloud key" / "out of quota" / "APK too old" message rather than a generic spinner.

### TerrainAnchorNode — Geospatial anchor pinned to ground at lat/lng

**Imperative API only — async resolve via `Earth`.** Pin a node to Google's outdoor terrain at any GPS coordinate. No plane detection required — works wherever VPS / Street View has coverage.

```kotlin
import io.github.sceneview.ar.node.TerrainAnchorNode

val anchorNodes = remember { mutableStateListOf<TerrainAnchorNode>() }
// Inside `onSessionUpdated = { session, frame -> ... }` once Earth is TRACKING:
if (session.earth?.trackingState == TrackingState.TRACKING) {
    val future: ResolveAnchorOnTerrainFuture? = TerrainAnchorNode.resolve(
        engine = engine,
        session = session,                 // resolve internally reads session.earth
        latitude = 48.8584,                // Eiffel Tower
        longitude = 2.2945,
        altitudeAboveTerrain = 1.5,        // metres above terrain (0 = on the ground)
        eusQuaternion = Quaternion()       // east-up-south rotation
    ) { state: TerrainAnchorState, anchorNode: TerrainAnchorNode? ->
        if (state == TerrainAnchorState.SUCCESS && anchorNode != null) {
            // anchorNode IS-A AnchorNode — pin children via the standard composable:
            anchorNodes.add(anchorNode)
        }
    }
    // Cancel the pending resolve on dispose to avoid running the network
    // round-trip (and accruing Google Cloud billing) after the user has
    // navigated away (#1768). Matches the CloudAnchorNode pattern.
    DisposableEffect(future) { onDispose { future?.cancel() } }
}
```

Then declare it inside the scene tree using the standard `AnchorNode` composable (since `TerrainAnchorNode extends AnchorNode`):

```kotlin
ARSceneView(...) {
    anchorNodes.forEach { node ->
        AnchorNode(anchor = node.anchor) {
            ModelNode(modelInstance = signpost, scaleToUnits = 0.5f)
        }
    }
}
```

Requires:
- `Config.GeospatialMode.ENABLED`
- ARCore Cloud API key (see API key warning above)
- `ACCESS_FINE_LOCATION` permission
- Internet connection (resolve calls Google Cloud)
- Outdoor environment with VPS / Street View coverage

100-anchor cap per session (Terrain + Rooftop combined). Demo: `samples/android-demo/.../ARTerrainAnchorDemo.kt`.

### RooftopAnchorNode — Geospatial anchor pinned to a building rooftop

Same async-resolve API as `TerrainAnchorNode`, but the altitude is interpreted relative to the rooftop of the building at the given lat/lng (or the terrain if no building is detected).

```kotlin
val future: ResolveAnchorOnRooftopFuture? = RooftopAnchorNode.resolve(
    engine = engine,
    session = session,              // resolve internally reads session.earth
    latitude = 48.8738,             // Arc de Triomphe
    longitude = 2.2950,
    altitudeAboveRooftop = 0.0,
    eusQuaternion = Quaternion()
) { state: RooftopAnchorState, anchorNode: RooftopAnchorNode? -> /* ... */ }
// Cancel on dispose to drop the network round-trip + Google Cloud billing
// after the user navigates away (#1768).
DisposableEffect(future) { onDispose { future?.cancel() } }
```

Same requirements as `TerrainAnchorNode`. Demo: `samples/android-demo/.../ARRooftopAnchorDemo.kt`.

### StreetscapeGeometryNode — building / terrain mesh (filtered)

Renders an ARCore Geospatial **Streetscape Geometry** mesh — full polygonal buildings and terrain meshes that the device's tracker reconstructs around the user. Requires `Config.GeospatialMode.ENABLED` + `Config.StreetscapeGeometryMode.ENABLED` and the same Cloud API key + location permission as `TerrainAnchorNode` (see the API key warning above).

```kotlin
@Composable fun StreetscapeGeometryNode(
    streetscapeGeometry: StreetscapeGeometry,
    types: Set<StreetscapeGeometry.Type> = setOf(StreetscapeGeometry.Type.BUILDING, StreetscapeGeometry.Type.TERRAIN),  // #1772
    minQuality: StreetscapeGeometry.Quality = StreetscapeGeometry.Quality.NONE,  // NONE < BUILDING_LOD_1 < BUILDING_LOD_2 (#1772)
    meshMaterialInstance: MaterialInstance? = null,
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((StreetscapeGeometry) -> Unit)? = null,
    apply: StreetscapeGeometryNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

Pass `types = setOf(StreetscapeGeometry.Type.BUILDING)` to drop the (often noisy) ground terrain in dense urban scenes. Pass `minQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2` to render only the higher-LOD buildings and save a frame-rate cliff on low-end devices. The filter is a no-op early-return on the composable side, so unmatched geometries never allocate Filament buffers.

```kotlin
onSessionUpdated = { _, frame ->
    geometries = frame.getUpdatedTrackables(StreetscapeGeometry::class.java).toList()
}

ARSceneView(...) {
    geometries.forEach { geo ->
        StreetscapeGeometryNode(
            streetscapeGeometry = geo,
            types = setOf(StreetscapeGeometry.Type.BUILDING),
            minQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2,
            meshMaterialInstance = buildingMat
        )
    }
}
```

Demo: `samples/android-demo/.../ARStreetscapeDemo.kt`. Setup guide (Cloud project + API key): `samples/android-demo/ARCORE_CLOUD_SETUP.md`.

### SceneMeshNode — classified building / terrain mesh (ARKit ARMeshAnchor parity)

A `StreetscapeGeometryNode` subclass that exposes a unified `MeshClassification` enum for every face of the underlying mesh, providing ARKit `ARMeshAnchor` parity on Android. On ARCore the label is coarse (one classification per geometry — `TERRAIN` or `BUILDING`); on ARKit it is per-face (fine-grained indoor labels). The `onClassifiedFace` callback uses the same per-face signature on both platforms so classification-driven rendering code compiles unchanged.

`MeshClassification` values: `FLOOR`, `WALL`, `CEILING`, `TABLE`, `SEAT`, `WINDOW`, `DOOR`, `TERRAIN`, `BUILDING`, `UNLABELED`.

```kotlin
@Composable fun SceneMeshNode(
    streetscapeGeometry: StreetscapeGeometry,
    types: Set<StreetscapeGeometry.Type> = setOf(StreetscapeGeometry.Type.BUILDING, StreetscapeGeometry.Type.TERRAIN),
    minQuality: StreetscapeGeometry.Quality = StreetscapeGeometry.Quality.NONE,
    meshMaterialInstance: MaterialInstance? = null,
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((StreetscapeGeometry) -> Unit)? = null,
    onClassifiedFace: ((faceIndex: Int, classification: MeshClassification) -> Unit)? = null,
    apply: SceneMeshNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

Use `SceneMeshNode` instead of `StreetscapeGeometryNode` when you need per-face classification for colour coding, physics layer masks, or audio-zone filters. Use `onClassifiedFace` to build a per-face map at construction time (called once, not every frame):

```kotlin
val classificationColors = mapOf(
    MeshClassification.TERRAIN to terrainMat,
    MeshClassification.BUILDING to buildingMat,
)

ARSceneView(...) {
    geometries.forEach { geo ->
        SceneMeshNode(
            streetscapeGeometry = geo,
            meshMaterialInstance = classificationColors[geo.type.toMeshClassification()]
        )
    }
}
```

Demo: `samples/android-demo/.../ARSceneMeshDemo.kt`.

### Geospatial Depth — extend environment depth accuracy to ~65 m

Standard ARCore motion-stereo depth (`Config.DepthMode.AUTOMATIC`) is reliable to roughly **8 m**. When the session also has Geospatial enabled, ARCore **automatically** fuses motion depth with Streetscape Geometry + device sensors and extends accurate depth out to **~65 m** — essential for outdoor or large-scale AR (placing virtual signage on a far building façade, occluding a virtual character behind a distant wall, depth-aware hit-tests across a city block). This is the **Geospatial Depth** feature shipped in ARCore 1.54.

There is **no separate `Config.GeospatialDepthMode` knob** — the fusion is opt-in by enabling all three modes together:

```kotlin
ARSceneView(
    sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC          // motion-stereo depth (always required)
        config.geospatialMode = Config.GeospatialMode.ENABLED   // unlocks the Geospatial fusion
        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED  // far-field mesh source
    }
) { /* … */ }
```

Once the three modes are on **and** the Earth state has reached `TrackingState.TRACKING` with VPS coverage, every `Frame.acquireDepthImage16Bits()` call returns a depth image where pixels beyond ~8 m carry valid values out to ~65 m. **No API change** is required from app code — `ARCameraStream` depth occlusion, `Frame.hitTestDepth` (#1712), `DepthMeshNode` and `rememberDepthCollider` all benefit transparently from the extended range. Outside VPS-covered areas (no Streetscape geometry available), depth falls back to motion-stereo and the ~8 m cap re-applies.

Requirements:
- ARCore Cloud API key + `ACCESS_FINE_LOCATION` permission (same as the rest of the Geospatial API — see the API key warning above).
- Device with a back-facing camera config that supports `DepthMode` (most ARCore-supported devices since 2020).
- A VPS-covered location (city centres of supported regions: https://developers.google.com/ar/coverage).

See #1731.

---

## Jetpack XR Extensions (preview, Android XR only)

SceneView ships an opt-in layer for **ARCore for Jetpack XR** (`androidx.xr.arcore`) — the runtime that powers hand tracking and face tracking on Android XR headsets and glasses. It is **separate** from mobile ARCore (`com.google.ar.core`) and only meaningful on devices that expose the Jetpack XR Perception runtime.

**Status: preview.** Tracking issue [#1738](https://github.com/sceneview/sceneview/issues/1738). The upstream `androidx.xr.arcore` is at `1.0.0-alpha14` and subject to breaking changes. SceneView wraps it as an additive, opt-in surface — phone-only apps pay nothing for it. Integration approach: `arsceneview/docs/JETPACK-XR-INTEGRATION.md`.

**Scope this release:** Slice 1 — dependency declared (`libs.versions.toml` → `jetpackXrArCore = "1.0.0-alpha14"`, alias `androidx-xr-arcore`), `XrFeatures` runtime availability check, design + slicing recorded. Slice 2 ([#1902](https://github.com/sceneview/sceneview/issues/1902)) — `XrHandNode` hand-tracking node + `ar-hand-tracking` demo. Slice 3 ([#1903](https://github.com/sceneview/sceneview/issues/1903)) — `XrFaceNode` face-tracking node + `ar-xr-face` demo.

**Opt-in dependency** (consumers add this themselves; `arsceneview/` declares it `compileOnly`):

```kotlin
// app/build.gradle.kts — only if you target Android XR
dependencies {
    implementation(libs.androidx.xr.arcore)   // androidx.xr.arcore:arcore:1.0.0-alpha14
}
```

### XrFeatures — runtime gate

Before invoking any `io.github.sceneview.ar.xr.*` API, check that the Jetpack XR runtime is reachable. The check uses reflection so it is safe on a stock mobile build:

```kotlin
import io.github.sceneview.ar.xr.XrFeatures

val context = LocalContext.current
val xrAvailable = remember { XrFeatures.isAvailable(context) }

SceneView(modifier = Modifier.fillMaxSize()) {
    if (xrAvailable) {
        // Slice 2: XrHandNode(hand = …, handedness = …) { … }
        // Slice 3: XrFaceNode(face = …) { … }
    } else {
        // Fall back to mobile ARCore: AugmentedFaceNode, AugmentedImageNode, etc.
    }
}
```

**What `XrFeatures.isAvailable(context)` returns:**

- `true` when the consumer has declared `androidx.xr.arcore:arcore` (or any artifact pulling `androidx.xr.runtime`) on the runtime classpath — i.e. the consumer has explicitly opted into the XR path.
- `false` on a default `arsceneview/` consumer (no XR dep on classpath).

Classpath presence is the foundation gate; the device-capability check (XR headset vs mobile phone) is layered on top by Slices 2 / 3 via the upstream `androidx.xr.runtime.Session.create(activity)` outcome.

Mobile ARCore tracking (`AugmentedFaceNode`, `AugmentedImageNode`, `AnchorNode`, etc.) keeps working unchanged on phones — the Jetpack XR layer is strictly additive.

### XrHandNode — hand tracking (Slice 2)

`XrHandNode` mirrors a hand tracked by ARCore for Jetpack XR (`androidx.xr.arcore.Hand`) as a SceneView scene-graph node — the Jetpack XR sibling of `AugmentedFaceNode`. It exposes one child node per skeleton joint (~26 joints: wrist, palm, and 4–5 joints per finger); attach a `SphereNode` per joint and a `LineNode` per bone to render a hand skeleton, or anchor a model to a single joint.

**Preview API.** Every `XrHandNode` symbol carries `@XrPreviewApi` (a `@RequiresOptIn` marker) because `androidx.xr.arcore` is alpha — opt in with `@OptIn(XrPreviewApi::class)`.

Declarative form (a `@Composable SceneScope` extension — usable inside `SceneView { }` / `ARSceneView { }`):

```kotlin
import io.github.sceneview.ar.xr.XrFeatures
import io.github.sceneview.ar.xr.XrHandNode
import io.github.sceneview.ar.xr.XrHandedness
import io.github.sceneview.ar.xr.XrPreviewApi
import androidx.xr.arcore.Hand

@OptIn(XrPreviewApi::class)
@Composable
fun HandSkeleton(session: androidx.xr.runtime.Session) {
    SceneView {
        // Mirror the live right hand; one joint child node per HandJointType.
        XrHandNode(hand = Hand.right(session), handedness = XrHandedness.RIGHT) {
            // `this` is a NodeScope rooted at the hand node — declare per-joint
            // geometry. Each joint's transform follows the tracked pose.
            SphereNode(radius = 0.008f)
        }
    }
}
```

The composable refreshes the skeleton in a `SideEffect` (per recomposition) — recompose once per XR frame and the hand follows for free. For an update cadence decoupled from recomposition, use the imperative node directly: collect `Hand.state` on the main dispatcher and call `handNode.update(...)`.

**Threading:** `XrHandNode.update()` writes only Filament `Node` transforms (no JNI resource creation) but, like every node mutation, MUST run on the main thread. Drive it from a Compose effect on `Dispatchers.Main`.

**Pure joint math** (`XrHandSkeleton`) is JVM-testable and runtime-free: `XrHandSkeleton.BONES` (the bone topology), `boneLength`, `boneMidpoint`, `lerp`, `totalBoneLength`, `trackedJointCount`. The `XrHandJoint` / `XrHandedness` enums and `XrHandSkeleton` carry no `androidx.xr.arcore` dependency, so they are safe to use on a phone-only build.

Sample: the `ar-hand-tracking` demo renders a static reference hand skeleton on phones (no public Android XR emulator yet) and points the developer at `XrHandNode` for the live integration on an Android XR headset.

### XrFaceNode — face tracking (Slice 3)

`XrFaceNode` mirrors a face tracked by ARCore for Jetpack XR (`androidx.xr.arcore.Face`) as a SceneView scene-graph node — the Jetpack XR sibling of `AugmentedFaceNode`. The headset's user-facing cameras track the face, unlike the phone-only `AugmentedFaceNode` which is restricted to the front (selfie) camera. The two coexist: phones keep using `AugmentedFaceNode`, Android XR devices get `XrFaceNode`.

It exposes one child node per named anchor region (`XrFaceRegion`: `CENTER`, `NOSE_TIP`, `FOREHEAD_LEFT`, `FOREHEAD_RIGHT`) whose transform follows the live pose — anchor glasses to `NOSE_TIP`, a hat above the forehead — plus the decoded dense mesh (`XrFaceMeshData`: vertex / normal / index buffers) so a consumer can upload its own `MeshNode` skin overlay. `XrFaceNode` creates no Filament mesh resource itself; building geometry stays a deliberate, main-thread step the consumer owns.

**Preview API.** Every `XrFaceNode` symbol carries `@XrPreviewApi` (a `@RequiresOptIn` marker) because `androidx.xr.arcore` is alpha — opt in with `@OptIn(XrPreviewApi::class)`.

Declarative form (a `@Composable SceneScope` extension — usable inside `SceneView { }` / `ARSceneView { }`):

```kotlin
import io.github.sceneview.ar.xr.XrFaceNode
import io.github.sceneview.ar.xr.XrFeatures
import io.github.sceneview.ar.xr.XrPreviewApi
import androidx.xr.arcore.Face

@OptIn(XrPreviewApi::class)
@Composable
fun FaceOverlay(session: androidx.xr.runtime.Session) {
    SceneView {
        // Mirror the live tracked face; one child node per XrFaceRegion.
        XrFaceNode(face = Face.getUserFace(session)) {
            // `this` is a NodeScope rooted at the face node — declare per-region geometry.
        }
    }
}
```

The composable refreshes the face in a `SideEffect` (per recomposition) — recompose once per XR frame and the face follows for free. For an update cadence decoupled from recomposition, use the imperative node directly: collect `Face.state` on the main dispatcher and call `faceNode.update(...)`.

**Threading:** `XrFaceNode.update()` writes only Filament `Node` transforms and decodes the mesh into plain arrays (no JNI resource creation) but, like every node mutation, MUST run on the main thread.

**Pure mesh math** (`XrFaceMesh`) is JVM-testable and runtime-free: `XrFaceMesh.isValid` (buffer-layout sanity check), `vertexAt`, `centroid`, `extent`, `regionDistance`, `lerp`, `trackedRegionCount`. The `XrFaceRegion` enum, `XrFaceMeshData` and `XrFaceMesh` carry no `androidx.xr.arcore` dependency, so they are safe on a phone-only build. Sample: the `ar-xr-face` demo renders a static reference face mesh on phones (no public Android XR emulator yet) and points the developer at `XrFaceNode` for the live integration.

**Cross-platform parity:** hand tracking on visionOS is covered by `HandTrackingProvider` in `SceneViewSwift`, face mesh by `ARFaceTrackingConfiguration`; web covers WebXR `hand-tracking` under issue #1778. Updates to `docs/docs/cheatsheet-ios.md` mirror this section ([#1904](https://github.com/sceneview/sceneview/issues/1904)).

---

## Node Properties & Interaction

All composable node types share these properties (settable via `apply` block or the parameters):

```kotlin
// Transform
node.position = Position(x = 1f, y = 0f, z = -2f)  // meters
node.rotation = Rotation(x = 0f, y = 45f, z = 0f)   // degrees
node.scale    = Scale(x = 1f, y = 1f, z = 1f)
node.quaternion = Quaternion(...)
node.transform  = Transform(position, quaternion, scale)

// World-space transforms (read/write)
node.worldPosition, node.worldRotation, node.worldScale, node.worldQuaternion, node.worldTransform

// Visibility
node.isVisible = true       // also hides all children when false

// Interaction
node.isTouchable = true
node.isEditable = true      // pinch-scale, drag-move, two-finger-rotate
node.isPositionEditable = false   // requires isEditable = true
node.isRotationEditable = true    // requires isEditable = true
node.isScaleEditable = true       // requires isEditable = true
node.editableScaleRange = 0.1f..10.0f
node.scaleGestureSensitivity = 0.5f

// Smooth transform
node.isSmoothTransformEnabled = false
node.smoothTransformSpeed = 5.0f

// Hit testing
node.isHittable = true

// Naming
node.name = "myNode"

// Orientation
node.lookAt(targetWorldPosition, upDirection)
node.lookTowards(lookDirection, upDirection)

// Animation utilities (on any Node)
node.animatePositions(...)
node.animateRotations(...)
```

### Editable nodes — Sceneform `TransformableNode` parity

SceneView's gesture-editing API is the direct replacement for Sceneform's
`TransformableNode`. It is already shipped on every `Node` (and therefore every
`ModelNode`) — there is no separate node type to instantiate, you just flip flags:

```kotlin
ModelNode(
    modelInstance = instance,
    scaleToUnits = 0.5f,
    isEditable = true,              // master switch — enables drag / pinch / twist
    apply = {
        // Per-axis locks — each one ALSO requires isEditable = true to take effect.
        isPositionEditable = true   // one-finger drag moves the node
        isRotationEditable = true   // two-finger twist rotates the node
        isScaleEditable = true      // pinch scales the node
        editableScaleRange = 0.1f..10.0f   // clamp pinch-to-scale (default 0.1..10)
        scaleGestureSensitivity = 0.5f     // 0 = no scaling, 1 = raw pinch factor
    }
)
```

Key facts an AI codegen pass must get right:

- `isEditable = false` (the default) makes the node a pure observed object — all three
  per-axis gestures are blocked regardless of the sub-flags.
- The three sub-flags (`isPositionEditable` / `isRotationEditable` / `isScaleEditable`)
  are gated: their getter returns `isEditable && field`, so setting `isRotationEditable
  = true` while `isEditable = false` still yields `false`. Default sub-flag values:
  position `false`, rotation `true`, scale `true`.
- When a touch lands on an editable node, the camera orbit/pan/zoom manipulator is
  skipped for the duration of that touch — otherwise the same drag would move the node
  AND orbit the camera.
- These are plain `Node` properties, not composable parameters — to change them
  reactively after creation, re-push them from a `LaunchedEffect` keyed on the new
  values (the `apply` block runs only once at node creation).

The `camera-gestures` demo in `samples/android-demo` (Node Gestures tab) is the
canonical end-to-end example: it wires every flag to a UI switch, a
scale-sensitivity slider, and a live-transform overlay.

---

## Resource Loading

### rememberModelInstance (composable, async)
```kotlin
// Load from local asset
@Composable
fun rememberModelInstance(
    modelLoader: ModelLoader,
    assetFileLocation: String
): ModelInstance?

// Load from any location (local asset, file path, or HTTP/HTTPS URL)
@Composable
fun rememberModelInstance(
    modelLoader: ModelLoader,
    fileLocation: String,
    resourceResolver: (resourceFileName: String) -> String = { ModelLoader.getFolderPath(fileLocation, it) }
): ModelInstance?
```
Returns `null` while loading, recomposes when ready. **Always handle the null case.**

**Lifecycle & ownership:** the composable owns the produced `ModelInstance` and its backing glTF `Model`. When the path key changes, the previous `Model` is destroyed (after any consuming `ModelNode` has detached its renderables) and the new asset is loaded; the `Model` is also destroyed on leave-composition. `ModelLoader` does NOT dedupe by path — each call is a fresh GPU allocation, not a cache hit. Never hold a swapped-out instance; for manual lifetime control use `ModelLoader.loadModelInstanceAsync` + `ModelLoader.destroyModel`.

The `fileLocation` overload auto-detects URLs (http/https) and routes through Fuel HTTP client for download. Use it for remote model loading:
```kotlin
val model = rememberModelInstance(modelLoader, "https://example.com/model.glb")
```

### ModelLoader (imperative)
```kotlin
class ModelLoader(engine: Engine, context: Context) {
    // Synchronous — MUST be called on main thread
    fun createModelInstance(assetFileLocation: String): ModelInstance
    fun createModelInstance(buffer: Buffer): ModelInstance
    fun createModelInstance(@RawRes rawResId: Int): ModelInstance
    fun createModelInstance(file: File): ModelInstance

    // releaseSourceData (default true): frees the raw buffer after Filament parses the model.
    // Set to false only when you need to re-instantiate the same model multiple times.
    fun createModel(assetFileLocation: String, releaseSourceData: Boolean = true): Model
    fun createModel(buffer: Buffer, releaseSourceData: Boolean = true): Model
    fun createModel(@RawRes rawResId: Int, releaseSourceData: Boolean = true): Model
    fun createModel(file: File, releaseSourceData: Boolean = true): Model

    // Async — safe from any thread
    suspend fun loadModel(fileLocation: String): Model?
    fun loadModelAsync(fileLocation: String, onResult: (Model?) -> Unit): Job
    suspend fun loadModelInstance(fileLocation: String): ModelInstance?
    fun loadModelInstanceAsync(fileLocation: String, onResult: (ModelInstance?) -> Unit): Job
}
```

### MaterialLoader
```kotlin
class MaterialLoader(engine: Engine, context: Context) {
    // PBR color material — MUST be called on main thread
    fun createColorInstance(
        color: Color,
        metallic: Float = 0.0f,    // 0 = dielectric, 1 = metal
        roughness: Float = 0.4f,   // 0 = mirror, 1 = matte
        reflectance: Float = 0.5f  // Fresnel reflectance
    ): MaterialInstance

    // Unlit (flat) color material — ignores scene lighting (no PBR)
    // Use for HUD overlays, debug visualizations, billboards, stylized rendering.
    fun createUnlitColorInstance(color: Color): MaterialInstance

    // Also accepts:
    fun createColorInstance(color: androidx.compose.ui.graphics.Color, ...): MaterialInstance
    fun createColorInstance(color: Int, ...): MaterialInstance
    fun createUnlitColorInstance(color: androidx.compose.ui.graphics.Color): MaterialInstance
    fun createUnlitColorInstance(color: Int): MaterialInstance

    // Invisible depth-writing material — punches the depth buffer, emits no colour.
    // RealityKit `OcclusionMaterial` / Sceneform `makeOcclusionMaterial` parity.
    // Use for virtual aquarium walls, window/door cutouts on photogrammetry, proxy
    // walls that should hide virtual content behind them without rendering themselves.
    // For AR depth from the camera image, use ARCameraStream.isDepthOcclusionEnabled
    // instead (per-pixel depth from ARCore, not a static mesh).
    fun createOcclusionInstance(): MaterialInstance

    // Scene Semantics overlay material (#1868) — colour-codes ARCore's 12-class
    // per-pixel outdoor segmentation. `semanticTexture` is an R8 Texture holding
    // the `Frame.semanticImage()` label ordinals; `opacity` blends 0f..1f.
    // Pair with MaterialInstance.setSemanticsTexture / setSemanticsOpacity.
    fun createSemanticsOverlayInstance(
        semanticTexture: Texture, opacity: Float = 1.0f
    ): MaterialInstance

    // Texture material
    fun createTextureInstance(texture: Texture, ...): MaterialInstance

    // Custom .filamat material
    fun createMaterial(assetFileLocation: String): Material
    fun createMaterial(payload: Buffer): Material
    suspend fun loadMaterial(fileLocation: String): Material?
    fun createInstance(material: Material): MaterialInstance
}
```

### EnvironmentLoader
```kotlin
class EnvironmentLoader(engine: Engine, context: Context) {
    // HDR environment — MUST be called on main thread
    fun createHDREnvironment(
        assetFileLocation: String,
        indirectLightSpecularFilter: Boolean = true,
        // v4.3.0 (#1124): override the v4.1.0-balanced 10k IBL default (#1075)
        // without copying the buffer-loading boilerplate.
        indirectLightApply: IndirectLight.Builder.() -> Unit = {},
        createSkybox: Boolean = true
    ): Environment?

    fun createHDREnvironment(buffer: Buffer, ...): Environment?
    fun createHDREnvironment(rawResId: Int, ...): Environment?
    fun createHDREnvironment(file: File, ...): Environment?
    suspend fun loadHDREnvironment(url: String, ...): Environment?

    // KTX1 environment (precompiled cmgen output — separate IBL + skybox files).
    // Also accepts (iblBuffer, skyboxBuffer), (iblRawResId, skyboxRawResId), (iblFile, skyboxFile).
    fun createKTX1Environment(iblAssetFile: String? = null, skyboxAssetFile: String? = null): Environment
    suspend fun loadKTX1Environment(iblUrl: String? = null, skyboxUrl: String? = null): Environment

    fun createEnvironment(
        indirectLight: IndirectLight? = null,
        skybox: Skybox? = null
    ): Environment
}
```

---

## Remember Helpers Reference

All `remember*` helpers create and memoize Filament objects, destroying them on disposal.
Most are default parameter values in `SceneView`/`ARSceneView` — call them explicitly only when sharing resources or customizing.

**Ownership on key change:** the keyed async loaders (`rememberModelInstance`, `rememberEnvironment(key = …)`, `rememberHDREnvironment`, `rememberKTXEnvironment`) also destroy the **previously produced** object when their key/path changes — a path swap (e.g. a gallery or HDR slider) frees the old GPU resources automatically. Never keep a reference to a swapped-out `ModelInstance`/`Environment`; re-read the helper's return value instead. For objects you manage yourself, use the imperative loaders (`loadModelInstanceAsync`, `createHDREnvironment`) and call `destroyModel`/`destroyEnvironment` when done.

| Helper | Returns | Purpose |
|--------|---------|---------|
| `rememberEngine()` | `Engine` | Root Filament object — one per process |
| `rememberModelLoader(engine)` | `ModelLoader` | Loads glTF/GLB models |
| `rememberMaterialLoader(engine)` | `MaterialLoader` | Creates material instances |
| `rememberEnvironmentLoader(engine)` | `EnvironmentLoader` | Loads HDR/KTX environments |
| `rememberModelInstance(modelLoader, path)` | `ModelInstance?` | Async model load — null while loading |
| `rememberEnvironment(environmentLoader, isOpaque)` | `Environment` | IBL + skybox environment |
| `rememberEnvironment(environmentLoader) { ... }` | `Environment` | Custom environment from lambda |
| `rememberEnvironment(environmentLoader, key = hdrPath) { ... }` | `Environment` | Pass `key` when the factory depends on a changing value (e.g. an HDR path from a slider) — the lambda is otherwise memoised once and the skybox never swaps |
| `rememberHDREnvironment(environmentLoader, "env.hdr")` | `Environment?` | `@ExperimentalSceneViewApi` — async HDR load from assets, `null` while loading; previous environment destroyed when the path changes |
| `rememberKTXEnvironment(environmentLoader, iblAssetFile, skyboxAssetFile)` | `Environment?` | `@ExperimentalSceneViewApi` — async pre-filtered KTX1 IBL/skybox load, `null` while loading; previous environment destroyed when the paths change |
| `rememberCameraNode(engine) { ... }` | `CameraNode` | Custom camera with apply block |
| `rememberMainLightNode(engine) { ... }` | `LightNode` | Primary directional light (key) with apply block — shadows ON by default; apply block is reactive (re-runs on recomposition, so Compose-state-driven intensity/direction/color update live) |
| `rememberFillLightNode(engine) { ... }` | `LightNode` | Soft fill light opposite the main light — lifts shadows so models don't look flat; apply block is reactive (same as `rememberMainLightNode`) |
| `rememberCameraManipulator(orbitHomePosition?, targetPosition?)` | `CameraManipulator?` | Orbit/pan/zoom camera controller. `orbitHomePosition` is the camera's **absolute** eye position, never an offset from `targetPosition`; under the default `autoCenterContent = true` the subject is framed from `\|orbitHomePosition\|` — see "Camera" |
| `rememberOnGestureListener(...)` | `OnGestureListener` | Gesture callbacks for tap/drag/pinch |
| `rememberViewNodeManager()` | `ViewNode.WindowManager` | Required for ViewNode composables |
| `rememberSurfaceMirrorer()` | `SurfaceMirrorer` | In-app video recording — mirror frames to a `MediaRecorder` surface, no MediaProjection (see "Record the scene to MP4") |
| `rememberView(engine)` | `View` | Filament view (one per viewport) |
| `rememberARView(engine)` | `View` | AR-tuned view (Filmic tone mapper — round-trips the camera background, #1434) |
| `rememberRenderer(engine)` | `Renderer` | Filament renderer (one per window) |
| `rememberScene(engine)` | `Scene` | Filament scene graph |
| `rememberCollisionSystem(view)` | `CollisionSystem` | Hit-testing system |
| `rememberNode(engine) { ... }` | `Node` | Generic node with apply block |
| `rememberMediaPlayer(context, assetFileLocation)` | `MediaPlayer?` | Auto-lifecycle video player (null while loading) |

**AR-specific helpers** (from `arsceneview` module):

| Helper | Returns | Purpose |
|--------|---------|---------|
| `rememberARCameraNode(engine)` | `ARCameraNode` | AR camera (updated by ARCore each frame) |
| `rememberARCameraStream(materialLoader)` | `ARCameraStream` | Camera feed background texture |
| `rememberAREnvironment(engine)` | `Environment` | No-skybox environment for AR |

**NOTE:** There is no `rememberMaterialInstance` in the published SceneView SDK — create materials with `materialLoader.createColorInstance(...)` inside a `remember` block. (The demos use a sample-only helper of that name from `samples/common`; in your own code copy this pattern, not the helper.)
```kotlin
val mat = remember(materialLoader) {
    materialLoader.createColorInstance(Color.Red, metallic = 0f, roughness = 0.4f)
}
```

---

## Camera

```kotlin
// Orbit / pan / zoom (default)
// `orbitHomePosition` is the camera's ABSOLUTE eye position, not an offset from
// `targetPosition`. This example targets the origin, where the two readings coincide —
// which is exactly why the trap below goes unnoticed. Framed from |(0,2,4)| ≈ 4.47 m.
SceneView(cameraManipulator = rememberCameraManipulator(
    orbitHomePosition = Position(x = 0f, y = 2f, z = 4f),
    targetPosition = Position(x = 0f, y = 0f, z = 0f)
))

// Custom camera position
SceneView(cameraNode = rememberCameraNode(engine) {
    position = Position(x = 0f, y = 2f, z = 5f)
    lookAt(Position(0f, 0f, 0f))
})

// Main light shortcut (apply block is LightNode.() -> Unit)
SceneView(mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f })

// Dual-light default — fill light is added automatically; override or disable:
SceneView(
    mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f },
    fillLightNode = rememberFillLightNode(engine) { intensity = 30_000f }, // softer fill
    // fillLightNode = null,  // disable fill light entirely
)
```

### How far `orbitHomePosition` actually puts the camera

Getting this wrong is a factor-of-2 framing bug, and every example above hides it because
they all target the origin (#2873).

- `orbitHomePosition` is the eye's **absolute world position**. Filament's `OrbitManipulator`
  assigns it verbatim (`mEye = mProps.orbitHomePosition`) — it is never re-based on
  `targetPosition`.
- `targetPosition` is the orbit pivot and the initial look-at point (default: the origin). It
  does **not** set the distance.
- **Omitting `orbitHomePosition` does not give you Filament's `(0, 0, 1)` default.** `SceneView`'s
  own default manipulator is `rememberCameraManipulator(cameraNode.worldPosition)`, so the
  effective default is the camera node's position — `(0, 0.4, 2.75)`, i.e. ≈ 2.78 m, for the
  default `CameraNode`.
- Under `SceneView`'s default `autoCenterContent = true`, the DSL content is translated so its
  bounding-box centre lands on the **world origin**. So the distance the subject is framed from
  is **`|orbitHomePosition|`** — the coordinates you gave your nodes do not survive, and
  `targetPosition` does not enter into it.

```kotlin
// WRONG reading: "the camera sits 2.7 m from the row at z = -1.5".
// The nodes are auto-centred onto the origin first, so this frames from
// |(0, 0.2, 1.2)| ≈ 1.22 m — half the intended distance.
rememberCameraManipulator(
    orbitHomePosition = Position(0f, 0.2f, 1.2f),
    targetPosition = Position(0f, 0f, -1.5f)
)

// RIGHT: pick the distance you want and give a vector of that LENGTH.
rememberCameraManipulator(orbitHomePosition = Position(0f, 0.2f, 2.7f))  // ≈ 2.71 m

// Or opt out of auto-centring, and authored world positions survive: the framing
// distance becomes |orbitHomePosition − contentCentre|.
SceneView(autoCenterContent = false, cameraManipulator = rememberCameraManipulator(
    orbitHomePosition = Position(0f, 0.2f, 1.2f),
    targetPosition = Position(0f, 0f, -1.5f)
))
```

There is no built-in gesture that returns the camera to `orbitHomePosition`: `onDoubleTap` is a
plain callback `SceneView` forwards to your code, never wired to the camera.

### Auto-fit camera framing

Library-level helper (`io.github.sceneview`, #1439) that frames a model so it fills the
viewport regardless of its intrinsic glTF size — no per-model `scaleToUnits` tuning.

```kotlin
// Pure trigonometry — bounds + camera projection → orbit distance. Fits the content
// bounding SPHERE, so the result is yaw-invariant (an orbiting camera never clips).
fun fitDistanceForBounds(
    bounds: Aabb, verticalFovDegrees: Double, aspect: Double,
    padding: Float = DEFAULT_FRAMING_PADDING  // 0.15 = 15% breathing room
): Float
```

Usage:

```kotlin
// Helpers: Filament Box → Aabb, focal length → vertical FOV.
val bounds = modelInstance.model.boundingBox.toAabb()
val vfov = verticalFovDegreesForFocalLength(28.0)   // ≈ 46.4°

// Reposition the camera in one call (main thread — reads/writes Filament state):
cameraNode.frameToContent(modelNode)            // measures the node subtree's bounds
cameraNode.frameToBounds(bounds)                // explicit bounds
cameraNode.fitDistanceForContent(modelNode)     // just the distance, no reposition

// Easiest path — let SceneView drive it. `autoFitContent` moves the camera so the
// content fills the viewport; re-frames when an async model grows the union bounds
// and latches once they settle. Applies only when there is no camera manipulator
// (an active orbit manipulator owns the camera transform every frame).
SceneView(autoFitContent = true, cameraManipulator = null) {
    rememberModelInstance(modelLoader, "models/bee.glb")?.let {
        ModelNode(modelInstance = it)   // null while loading — always handle it
    }
}

// Manual guard for a custom frame loop (diagonal-stability gated — #1596):
val fitState = remember { SceneAutoFitState() }
SceneView(onFrame = { fitState.maybeFit(cameraNode, contentRoot) }) { }
```

`autoFitContent` defaults to `false` (callers that position the camera explicitly keep
control). iOS already auto-frames from `visualBounds` (#1026 / #1391).

---

## Gestures

```kotlin
SceneView(
    onGestureListener = rememberOnGestureListener(
        onDown = { event, node -> },
        onShowPress = { event, node -> },
        onSingleTapUp = { event, node -> },
        onSingleTapConfirmed = { event, node -> },
        onDoubleTap = { event, node -> node?.let { it.scale = Scale(2f) } },
        onDoubleTapEvent = { event, node -> },
        onLongPress = { event, node -> },
        onContextClick = { event, node -> },
        onScroll = { e1, e2, node, distance -> },
        onFling = { e1, e2, node, velocity -> },
        // Move / rotate / scale callbacks take (detector, event, node) — three params.
        onMove = { detector, e, node -> },
        onMoveBegin = { detector, e, node -> },
        onMoveEnd = { detector, e, node -> },
        onRotate = { detector, e, node -> },
        onRotateBegin = { detector, e, node -> },
        onRotateEnd = { detector, e, node -> },
        onScale = { detector, e, node -> },
        onScaleBegin = { detector, e, node -> },
        onScaleEnd = { detector, e, node -> }
    ),
    onTouchEvent = { event, hitResult -> false }
)
```

---

## Math Types

```kotlin
import io.github.sceneview.math.Position   // Float3, meters
import io.github.sceneview.math.Rotation   // Float3, degrees
import io.github.sceneview.math.Scale      // Float3
import io.github.sceneview.math.Direction  // Float3, unit vector
import io.github.sceneview.math.Size       // Float3
import io.github.sceneview.math.Transform  // Mat4
import io.github.sceneview.math.Color      // Float4

Position(x = 0f, y = 1f, z = -2f)
Rotation(y = 90f)
Scale(1.5f)          // uniform
Scale(x = 2f, y = 1f, z = 2f)

// Constructors
Transform(position = Position(0f), quaternion = Quaternion(), scale = Scale(1f))
Transform(position = Position(0f), rotation = Rotation(y = 90f), scale = Scale(1f))
colorOf(r = 1f, g = 0f, b = 0f, a = 1f)

// Conversions — Rotation (Euler degrees) ⇄ Quaternion
val quaternion = Rotation(y = 90f).toQuaternion(order = RotationsOrder.ZYX)
val rotation = quaternion.toRotation(order = RotationsOrder.ZYX)
```

---

## Surface Types

```kotlin
SceneView(surfaceType = SurfaceType.Surface)          // SurfaceView, best perf (default)
SceneView(surfaceType = SurfaceType.TextureSurface, isOpaque = false)  // TextureView, alpha
```

---

## Android Advanced APIs

### SceneRenderer

`SceneRenderer` encapsulates the Filament surface lifecycle and render-frame pipeline. Both `SceneView` (3D) and `ARSceneView` (AR) share the same surface management and frame-presentation code through this class.

```kotlin
class SceneRenderer(engine: Engine, view: View, renderer: Renderer) {
    val isAttached: Boolean               // true when a swap chain is ready
    var onSurfaceResized: ((width: Int, height: Int) -> Unit)?
    var onSurfaceReady: ((viewHeight: () -> Int) -> Unit)?
    var onSurfaceDestroyed: (() -> Unit)?

    fun attachToSurfaceView(surfaceView: SurfaceView, isOpaque: Boolean, context: Context, display: Display, onTouch: ((MotionEvent) -> Unit)? = null)
    fun attachToTextureView(textureView: TextureView, isOpaque: Boolean, context: Context, display: Display, onTouch: ((MotionEvent) -> Unit)? = null)
    fun renderFrame(frameTimeNanos: Long, onBeforeRender: () -> Unit)
    fun applyResize(width: Int, height: Int)
    fun destroy()
}
```

Typical composable usage:
```kotlin
val sceneRenderer = remember(engine, renderer) { SceneRenderer(engine, view, renderer) }
DisposableEffect(sceneRenderer) { onDispose { sceneRenderer.destroy() } }
```

### NodeGestureDelegate

`NodeGestureDelegate` handles all gesture detection and callback logic for a `Node`. Gesture callbacks (e.g. `node.onTouch`, `node.onSingleTapConfirmed`) are forwarded through this delegate. Access it directly when you need to batch-configure callbacks or inspect `editingTransforms`:

```kotlin
// Preferred — set callbacks directly on the node (delegates internally):
node.onSingleTapConfirmed = { e -> true }
node.onMove = { detector, e, worldPosition -> true }

// Advanced — access the delegate directly:
node.gestureDelegate.editingTransforms  // Set<KProperty1<Node, Any>> currently being edited
node.gestureDelegate.onEditingChanged = { transforms -> /* transforms changed */ }
```

Available callbacks on `NodeGestureDelegate` (and mirrored on `Node`):
`onTouch`, `onDown`, `onShowPress`, `onSingleTapUp`, `onScroll`, `onLongPress`, `onFling`,
`onSingleTapConfirmed`, `onDoubleTap`, `onDoubleTapEvent`, `onContextClick`,
`onMoveBegin`, `onMove`, `onMoveEnd`,
`onRotateBegin`, `onRotate`, `onRotateEnd`,
`onScaleBegin`, `onScale`, `onScaleEnd`,
`onEditingChanged`, `editingTransforms`.

### NodeAnimationDelegate

`NodeAnimationDelegate` handles smooth (interpolated) transform animation for a `Node`. Access via `node.animationDelegate`:

```kotlin
// Preferred — use Node property aliases:
node.isSmoothTransformEnabled = true
node.smoothTransformSpeed = 5.0f      // higher = faster convergence
node.smoothTransform = targetTransform
node.onSmoothEnd = { n -> /* reached target */ }

// Advanced — access the delegate directly:
node.animationDelegate.smoothTransform = targetTransform
```

The per-frame interpolation uses slerp. Once the transform reaches the target (within 0.001 tolerance), `onSmoothEnd` fires and the animation clears.

### NodeState

`NodeState` is an immutable snapshot of a `Node`'s observable state. Use it for ViewModel-driven UI or save/restore patterns:

```kotlin notest type-shape listing — mirrors the shipped io.github.sceneview.node.NodeState (re-declaring it here would shadow the real one)
data class NodeState(
    val position: Position = Position(),
    val quaternion: Quaternion = Quaternion(),
    val scale: Scale = Scale(1f),
    val isVisible: Boolean = true,
    val isEditable: Boolean = false,
    val isTouchable: Boolean = true
)
```

```kotlin
// Capture current state
val state: NodeState = node.toState()

// Restore state
node.applyState(state)
```

### ARPermissionHandler

`ARPermissionHandler` abstracts camera permission and ARCore availability checks away from `ComponentActivity`, enabling testability:

```kotlin
interface ARPermissionHandler {
    fun hasCameraPermission(): Boolean
    fun requestCameraPermission(onResult: (granted: Boolean) -> Unit)
    fun shouldShowPermissionRationale(): Boolean
    fun openAppSettings()
    fun checkARCoreAvailability(): ArCoreApk.Availability
    fun requestARCoreInstall(userRequestedInstall: Boolean): Boolean
}

// Production implementation backed by ComponentActivity:
class ActivityARPermissionHandler(activity: ComponentActivity) : ARPermissionHandler
```

---

## sceneview-core (KMP)

`sceneview-core` is a Kotlin Multiplatform module containing platform-independent logic shared between Android and iOS. It targets `jvm("android")`, `iosArm64`, `iosSimulatorArm64`, and `iosX64`. It depends on `dev.romainguy:kotlin-math:1.8.0` (exposed as `api`).

The `sceneview` Android module depends on `sceneview-core` via `api project(':sceneview-core')`, so all types below are available transitively.

### Math type aliases

All defined in `io.github.sceneview.math`:

| Type alias | Underlying type | Semantics |
|---|---|---|
| `Position` | `Float3` | World position in meters |
| `Position2` | `Float2` | 2D position |
| `Rotation` | `Float3` | Euler angles in degrees |
| `Scale` | `Float3` | Scale factors |
| `Direction` | `Float3` | Unit direction vector |
| `Size` | `Float3` | Dimensions |
| `Transform` | `Mat4` | 4x4 transform matrix |
| `Color` | `Float4` | RGBA color (r, g, b, a) |

```kotlin notest API-shape listing — `receiver.member(args): ReturnType` reference notation, not statements
Transform(position, quaternion, scale)
Transform(position, rotation, scale)
colorOf(r, g, b, a)

Rotation.toQuaternion(order = RotationsOrder.ZYX): Quaternion
Quaternion.toRotation(order = RotationsOrder.ZYX): Rotation
FloatArray.toPosition() / .toRotation() / .toScale() / .toDirection() / .toColor()

lerp(start: Float3, end: Float3, deltaSeconds: Float): Float3
slerp(start: Transform, end: Transform, deltaSeconds: Double, speed: Float): Transform

Float.almostEquals(other: Float): Boolean
Float3.equals(v: Float3, delta: Float): Boolean
```

### Color utilities

`io.github.sceneview.math.Color` extensions:

```kotlin notest API-shape listing — `receiver.member(args): ReturnType` reference notation, not statements
Color.toLinearSpace(): Color
Color.toSrgbSpace(): Color
Color.luminance(): Float
Color.withAlpha(alpha: Float): Color
Color.toHsv(): Float3
hsvToRgb(h: Float, s: Float, v: Float): Color
lerpColor(start: Color, end: Color, fraction: Float): Color
```

### Animation API

`io.github.sceneview.animation`:

```kotlin
// Easing functions — (Float) -> Float mappers for [0..1]
Easing.Linear
Easing.EaseIn       // cubic
Easing.EaseOut      // cubic
Easing.EaseInOut    // cubic
Easing.spring(dampingRatio = 0.5f, stiffness = 500f)

// Property animation state machine
val state = AnimationState(
    startValue = 0f, endValue = 1f,
    durationSeconds = 0.5f,
    easing = Easing.EaseOut,
    playbackMode = PlaybackMode.ONCE  // ONCE | LOOP | PING_PONG
)
val next = animate(state, deltaSeconds)
next.value      // current interpolated value
next.fraction   // eased fraction
next.isFinished // true when done (ONCE mode)

// Spring animator — damped harmonic oscillator
val spring = SpringAnimator(config = SpringConfig.BOUNCY)
// Presets: SpringConfig.BOUNCY, SMOOTH, STIFF
// Custom: SpringConfig(stiffness = 400f, dampingRatio = 0.6f, initialVelocity = 0f)
val value = spring.update(deltaSeconds)
spring.isSettled
spring.reset()
```

Time utilities:

```kotlin notest API-shape listing — function-signature reference notation, not statements
frameToTime(frame: Int, frameRate: Int): Float
timeToFrame(time: Float, frameRate: Int): Int
fractionToTime(fraction: Float, duration: Float): Float
timeToFraction(time: Float, duration: Float): Float
secondsToMillis(seconds: Float): Long
millisToSeconds(millis: Long): Float
frameCount(durationSeconds: Float, frameRate: Int): Int
```

### Geometry generators

`io.github.sceneview.geometries` — pure functions returning `GeometryData(vertices, indices)`:

```kotlin
generateCube(size: Float3 = Float3(1f), center: Float3 = Float3(0f)): GeometryData
generateSphere(radius: Float = 1f, center: Float3 = Float3(0f), stacks: Int = 24, slices: Int = 24): GeometryData
generateCylinder(radius: Float = 1f, height: Float = 2f, center: Float3 = Float3(0f), sideCount: Int = 24): GeometryData
generatePlane(size: Float2 = Float2(1f), center: Float3 = Float3(0f), normal: Float3 = Float3(y = 1f)): GeometryData
generateLine(start: Float3 = Float3(0f), end: Float3 = Float3(x = 1f)): GeometryData
generatePath(points: List<Float3>, closed: Boolean = false): GeometryData
generateShape(polygonPath: List<Float2>, polygonHoles: List<Int>, delaunayPoints: List<Float2>,
    normal: Float3, uvScale: Float2, color: Float4?): GeometryData

// Additional generators (same GeometryData return):
generateIcosphere(radius: Float = 1f, center: Float3 = Float3(0f), subdivisions: Int = 2): GeometryData
generateTorus(majorRadius: Float = 1f, minorRadius: Float = 0.3f, center: Float3 = Float3(0f),
    majorSegments: Int = 24, minorSegments: Int = 12): GeometryData
generateCapsule(radius: Float = 0.5f, height: Float = 2f, center: Float3 = Float3(0f),
    slices: Int = 24, hemisphereStacks: Int = 8, cylinderStacks: Int = 1): GeometryData
generateExtrude(crossSection: List<Float2>, path: List<Float3>, closed: Boolean = false): GeometryData
generateLathe(profile: List<Float2>, center: Float3 = Float3(0f), segments: Int = 24, closed: Boolean = true): GeometryData
generateRoundedCube(size: Float3 = Float3(1f), radius: Float = 0.1f, center: Float3 = Float3(0f), segments: Int = 4): GeometryData
generateHeightmap(width: Float = 10f, depth: Float = 10f, center: Float3 = Float3(0f),
    segmentsX: Int = 64, segmentsZ: Int = 64, heightFunction: (Float, Float) -> Float = { _, _ -> 0f }): GeometryData
generatePerlinTerrain(...): GeometryData   // procedural Perlin-noise heightmap terrain
```

### Collision system

`io.github.sceneview.collision`:

| Class | Description |
|---|---|
| `Vector3` | 3D vector with arithmetic, dot, cross, normalize, lerp |
| `Quaternion` | Rotation quaternion with multiply, inverse, slerp |
| `Matrix` | 4x4 matrix (column-major float array) |
| `Ray` | Origin + direction, `getPoint(distance)` |
| `RayHit` | Hit result with distance and world position |
| `Sphere` | Center + radius collision shape |
| `Box` | Center + size + rotation collision shape |
| `Plane` | Normal + constant collision shape |
| `CollisionShape` | Base class — `rayIntersection(ray, rayHit): Boolean` |
| `Intersections` | Static tests: sphere-sphere, box-box, ray-sphere, ray-box, ray-plane |

The Android `CollisionSystem` (in `sceneview` module) exposes `hitTest()` for screen-space and ray-based queries:
```kotlin
// Preferred API — every overload returns List<HitResult>
val fromTouch = collisionSystem.hitTest(motionEvent)              // from touch event
val fromPixels = collisionSystem.hitTest(xPx, yPx)                // screen pixels
val fromViewPos = collisionSystem.hitTest(viewPosition = Float2(0.5f, 0.5f))  // normalized [0..1]
val fromRay = collisionSystem.hitTest(                            // explicit kotlin-math Ray
    ray = Ray(origin = Position(0f), direction = Direction(z = -1f))
)

// Deprecated forerunners: `raycast(ray)` → `hitTest(ray).firstOrNull()`,
// `raycastAll(ray)` → `hitTest(ray)`.

// HitResult properties: `node` throws IllegalStateException if the result was
// reset — prefer `nodeOrNull`, which returns null instead of throwing.
val hitNode: Node? = fromTouch.firstOrNull()?.nodeOrNull
```

### Triangulation

| Class | Purpose |
|---|---|
| `Earcut` | Polygon triangulation (with holes) — returns triangle indices |
| `Delaunator` | Delaunay triangulation — computes Delaunay triangles from 2D points |

---

## Compose Multiplatform (sceneview-compose)

One composable from `commonMain`, several renderers underneath. Artifact:
`io.github.sceneview:sceneview-compose:4.26.0` — **first published in 4.26.0**. The
currently released 4.25.0 does not contain it, so do not emit that coordinate.

**SCOPE — read before using.** This module is the *viewer subset* and will not grow past
it: load a model, orbit it, light it, tap it. It is NOT a portable version of the full
SDK. The honest intersection of Filament / RealityKit / Filament.js is 5 node types (this
module covers 4 of them — SpatialAudio is deliberately out of the viewer case) out
of Android's 27.

**NOT available here — use the platform-native API instead:**

| Need | Use |
|---|---|
| **AR (anything)** | `arsceneview` on Android, `ARSceneView` on Apple. 77 of 178 `arsceneview` files import ARCore directly (58 under src/main); there is no honest common shape |
| Custom materials, shaders, post-processing | platform-native API |
| Splat, Video, View, ContactShadow, Physics, Text nodes | platform-native API |

**Renderer + status per platform** (do NOT generate code assuming iOS or Desktop render
today — they draw a visible "not available yet" notice):

| Platform | Renderer | Status |
|---|---|---|
| Android | Filament, delegates to `SceneView { }` | Implemented |
| iOS | RealityKit via `SceneViewSwift` | Needs a one-time app registration — see below |
| Desktop (JVM) | Filament via a vendored FFM binding | Placeholder — needs the native build chain |

**iOS requires one registration call.** A KMP module cannot depend on a Swift Package, so
the app supplies the renderer:

```kotlin notest iosMain-only — platform.UIKit.UIView has no Android counterpart
// iosMain, before the first SceneViewer composes
SceneViewerBridge.factory = object : SceneViewerViewFactory {
    override fun create(spec: SceneViewerSpec): UIView = /* wrap SceneViewSwift.SceneView */
    override fun update(view: UIView, spec: SceneViewerSpec) { /* mutate, never recreate */ }
}
```

Without it, `SceneViewer` draws a visible "no renderer registered" notice. `onFrame` is
never invoked on iOS — `SceneViewSwift` exposes no per-frame callback.

Targets: `androidTarget`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`. No `iosX64`
— Compose Multiplatform 1.11.1 publishes no such variant.

### The whole public API

```kotlin
@Composable
fun SceneViewer(
    model: ModelSource,
    modifier: Modifier = Modifier,
    camera: CameraState = rememberCameraState(),
    lighting: Lighting = Lighting(),
    environment: EnvironmentSource = EnvironmentSource.Default,
    onTap: ((ModelHit?) -> Unit)? = null,
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
)
```

| Type | Values |
|---|---|
| `ModelSource` | `Asset(path)` · `Bytes(bytes)` · `Url(url)` |
| `EnvironmentSource` | `Default` · `Color(r, g, b, alpha = 1f)` · `Hdr(path, showSkybox = true)` |
| `Lighting` | `direction: Float3` · `intensity: Float` (lux) · `ambientIntensity: Float` · `castShadows: Boolean` |
| `ModelHit` | `position: Float3` · `distance: Float` |
| `CameraState` | `target` · `distance` · `azimuth` · `elevation` (degrees) · `gesturesEnabled` |

Helpers: `rememberCameraState(target, distance, azimuth, elevation)` (saved across
config changes) and `rememberUnsavedCameraState(...)`.

```kotlin
// commonMain — compiles and runs on Android today
@Composable
fun ModelScreen() {
    val camera = rememberCameraState(distance = 4f, elevation = 15f)

    SceneViewer(
        model = ModelSource.Asset("models/damaged_helmet.glb"),
        modifier = Modifier.fillMaxSize(),
        camera = camera,
        lighting = Lighting(intensity = 80_000f, castShadows = true),
        environment = EnvironmentSource.Hdr("environments/studio.hdr"),
        onTap = { hit -> if (hit != null) println("hit at ${hit.position}") },
    )
}
```

Gotchas:
- `ModelSource.Bytes` AND `ModelSource.Url` must be self-contained (GLB, or glTF with
  embedded resources). Only `Asset` resolves sibling resources; `Url` downloads exactly
  one file, so a `.gltf` referencing an external `.bin` or texture loads incomplete —
  and does so silently, with no error surfaced.
- `ModelSource.Url` accepts absolute http/https only (checked in `commonMain`, so the
  rule holds on every platform), with timeouts and a 64 MB cap.
- Pixel output differs per platform: lighting maps approximately between Filament and
  RealityKit, and materials do not carry across engines. For a reproducible look, ship an
  HDR environment and use it everywhere.
- `EnvironmentSource.Hdr(showSkybox = false)` makes the viewport transparent so Compose
  content behind it shows through.

Full rationale: `docs/docs/compose-multiplatform.md`.

---

## SceneView Web (Kotlin/JS + Filament.js)

Package: `sceneview-web` v4.25.0 — npm `sceneview-web`
Renderer: **Filament.js (WebGL2/WASM)** — same Filament engine as SceneView Android, compiled to WebAssembly.
Requires: Chrome 79+, Edge 79+, Firefox 78+ (WebGL2). Safari 15+ (WebGL2).

npm install:
```
npm install sceneview-web filament
```

Script-tag usage (no bundler):
```html
<script src="https://sceneview.github.io/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.25.0/sceneview-web.js"></script>
```

After loading, the library registers itself on `window.sceneview`.

---

### ⚠️ Web API model — builder DSL, plus a minimal exported `NodeHandle`

**The Web target is still primarily a fire-and-forget builder DSL, but since slice 3
of #2024 it ALSO exports a minimal `NodeHandle` node surface to plain JS.** This is a
documented (and narrowing) divergence from SceneView Android
(`io.github.sceneview.node.Node`) and SceneView Apple (`SceneViewSwift` entities),
being closed incrementally (issue #2024, v5 milestone):

- Since slice 2 the module's Kotlin `ModelNode`/`GeometryNode` wrap renderable content,
  and since slice 2b `LightNode`/`CameraNode` wrap lights and camera-driving (the
  `light { }` DSL delegates to a retained `LightNode`). Those Kotlin node classes and the
  `addModelNode`/`addGeometryNode`/`addLightNode`/`addCameraNode` **Kotlin** factories are
  **still Kotlin-only — NOT exported to plain JS.**
- **Slice 3 / P4 exports the first plain-JS node surface: `NodeHandle`.** Plain-JS
  callers create a node via `sv.addNode()` / `sv.addCubeNode(size)` /
  `sv.addSphereNode(radius)` / `sv.addLightNode(type)` / `sv.addModelNode(url)`, keep the
  returned handle, and **address it after `create()`** — `setPosition` / `setRotation`
  (Euler degrees) / `setScale` / `setScaleUniform` / `setVisible` / `addChild` /
  `removeChild` / `getWorldPosition` / `destroy`. This is the thing the builder DSL could
  never do: mutate content imperatively after the scene is built. It is a **minimal first
  slice** — no gestures, no collision, no transform object; those are additive later.

The builder DSL below stays fully supported and is the simplest path for a static scene;
`NodeHandle` is the additive imperative surface for scenes that mutate at runtime.

| Concept | Android / iOS | Web (`sceneview-web`) |
|---|---|---|
| Add a model | `ModelNode(...)` added to a `Node` tree | builder `model("url.glb") { ... }` / `sceneView.loadModel(url)`, **or** plain-JS `sv.addModelNode(url)` → `Promise<NodeHandle>` |
| Add a primitive | `CubeNode` / `SphereNode` / … | builder `geometry { cube(); … }`, **or** plain-JS `sv.addCubeNode(size)` / `sv.addSphereNode(radius)` → `NodeHandle` |
| Add a light | `LightNode(...)` | builder `light { directional(); … }`, **or** plain-JS `sv.addLightNode("directional"\|"point"\|"spot")` → `NodeHandle` |
| Add a splat cloud | `SplatNode(splatCloud)` (#2646 P1) | plain-JS `sv.addSplatNode(url)` → `Promise<NodeHandle>` (`.ply` INRIA / `.spz` Niantic), **or** Kotlin/JS `SceneView.addSplatNode(url, onLoaded = …)` / `addSplatNode(splatCloud)` (#2646 P2) |
| Add an empty pivot / group | `Node()` | plain-JS `sv.addNode()` → `NodeHandle` (parent others under it with `addChild`) |
| Remove a node | `node.parent = null` / `node.destroy()` | plain-JS `sv.removeNode(handle)` or `handle.destroy()` (detaches + frees the Filament entity) |
| Configure the camera | `CameraNode(...)` | `camera { eye(...); target(...) }` (a `CameraConfig`) — **not** exported as a `NodeHandle` yet |
| Parent / child hierarchy | `node.parent = other` / `node.childNodes` | `handle.addChild(child)` / `handle.removeChild(child)` (exported since slice 3); builder-DSL content stays flat |
| Per-content transform | `node.position` / `node.rotation` / `node.scale` (mutable, reactive) | builder `*Config` sets it once (not re-mutable); a `NodeHandle` IS re-mutable via `setPosition` / `setRotation` / `setScale` |
| Transform inheritance | composed parent→child via Filament `TransformManager` | composed for `NodeHandle` parent/child (Filament `TransformManager`, same as Android); builder-DSL flat content is a world-space leaf |

**Correct Web code** — the builder DSL (simplest for a static scene):

```kotlin
// ✅ Web — builder DSL (Kotlin/JS)
SceneView.create(canvas, configure = {
    camera { eye(0.0, 1.5, 5.0); target(0.0, 0.0, 0.0) }
    light  { directional(); intensity(100_000.0) }
    model("models/damaged_helmet.glb")
    geometry { cube(); size(1.0); position(2.0, 0.0, 0.0); color(1.0, 0.0, 0.0, 1.0) }
}) { sceneView -> sceneView.startRendering() }
```

**Correct Web code** — the exported `NodeHandle` surface (plain JS, mutate after build):

```js
// ✅ Web — plain-JS NodeHandle (window.sceneview.*, since slice 3 / P4)
sceneview.createViewer("canvas").then(function (sv) {
  var cube = sv.addCubeNode(1.0);            // → NodeHandle, content already in scene
  cube.setPosition(2, 0, 0);
  cube.setScaleUniform(1.5);
  cube.setVisible(false);                    // actually removes it from the scene

  sv.addModelNode("models/helmet.glb").then(function (model) {
    model.setRotation(0, 45, 0);             // Euler degrees
    model.addChild(cube);                    // re-parent — cube now follows the model
  });

  // sv.removeNode(cube) or cube.destroy() detaches + frees the Filament entity
});

// ❌ Still NOT exported to plain JS: the Kotlin ModelNode/LightNode/CameraNode classes
//    and the addGeometryNode/addCameraNode Kotlin factories. From JS use the NodeHandle
//    factories above.
```

The 4 `*Config` classes (`ModelConfig`, `GeometryConfig`, `LightConfig`, `CameraConfig`)
remain builder *inputs*, not scene-graph nodes. Full Web↔Android/iOS node parity
(`CameraNode` handle, collision, gestures, smooth transforms) continues under #2024:
slice 1 (Kotlin transform nodes), slice 2a/2b (content + light/camera nodes, Kotlin-only)
and now slice 3 / P4 (`NodeHandle` export + root-node centering) have shipped
incrementally after 4.20.0.

---

### SceneView (Kotlin/JS class — 3D scene)

```kotlin
// Primary entry point — Kotlin DSL
SceneView.create(
    canvas: HTMLCanvasElement,
    assets: Array<String> = emptyArray(),  // URLs to preload (KTX)
    configure: SceneViewBuilder.() -> Unit = {},
    onError: ((Throwable) -> Unit)? = null,  // init failed — wire to your reject path (init is async; a throw can't propagate)
    onReady: (SceneView) -> Unit
)

// Constants
SceneView.DEFAULT_IBL_URL   // neutral studio IBL (KTX)
SceneView.DEFAULT_SKYBOX_URL
```

Instance methods:
```kotlin
sceneView.loadModel(url: String, onLoaded: ((FilamentAsset) -> Unit)? = null)
sceneView.loadEnvironment(iblUrl: String, skyboxUrl: String? = null)
sceneView.loadDefaultEnvironment()           // neutral IBL, no skybox
sceneView.addLight(config: LightConfig)
sceneView.addGeometry(config: GeometryConfig)
sceneView.enableCameraControls(
    distance: Double = 5.0,
    targetX: Double = 0.0, targetY: Double = 0.0, targetZ: Double = 0.0,
    autoRotate: Boolean = false
): OrbitCameraController
sceneView.fitToModels()                      // auto-fit camera to bounding box
sceneView.resize(width: Int, height: Int)
sceneView.startRendering()
sceneView.stopRendering()
sceneView.destroy()                          // release all GPU resources

// Properties
sceneView.canvas: HTMLCanvasElement
sceneView.engine: Engine                     // Filament Engine
sceneView.renderer: Renderer
sceneView.scene: Scene
sceneView.view: View
sceneView.camera: Camera
sceneView.cameraController: OrbitCameraController?
sceneView.autoResize: Boolean = true
```

---

### SceneViewBuilder (DSL — configure block inside SceneView.create)

```kotlin
SceneView.create(canvas, configure = {
    camera {
        eye(0.0, 1.5, 5.0)        // camera position
        target(0.0, 0.0, 0.0)     // look-at point
        up(0.0, 1.0, 0.0)
        fov(45.0)                  // degrees
        near(0.1); far(1000.0)
        // Photometric exposure ONLY — aperture (f-stop), shutter (s), ISO.
        // Default f/12, 1/200s, ISO 200 (mirrors Android). Filament's camera is
        // physically based: there is no model-viewer-style relative exposure.
        exposure(aperture = 12.0, shutterSpeed = 1.0 / 200.0, sensitivity = 200.0)
    }
    light {
        directional()              // or: point() / spot()
        intensity(10_000.0)        // lux, read under the photometric exposure
        color(1.0f, 1.0f, 1.0f)
        direction(0.6f, -1.0f, -0.8f)
        // for point/spot: position(x, y, z)
    }
    model("models/damaged_helmet.glb") {
        scale(1.0f)                // raw uniform local scale (like Android
                                   //  ModelNode(scale = Scale(value))); default 1f
        autoAnimate(true)          // play glTF animation 0 (default true);
                                   //  false renders the model static
        onLoaded { asset -> /* FilamentAsset */ }
    }
    geometry {
        cube()                     // or: sphere() / cylinder() / plane()
        size(1.0, 1.0, 1.0)       // cube: w/h/d; sphere/cylinder: use radius()/height()
        color(1.0, 0.0, 0.0, 1.0) // RGBA 0-1
        unlit()                    // optional — flat color, ignores all lighting
                                   //  (KHR_materials_unlit). For HUD overlays, gizmos,
                                   //  axes — anywhere PBR shading would fight the use case.
        position(0.0, 0.5, -2.0)
        rotation(0.0, 45.0, 0.0)  // Euler degrees
        scale(1.0)
    }
    environment("https://…/ibl.ktx", skyboxUrl = "https://…/sky.ktx") // custom IBL
    noEnvironment()                // skip IBL loading entirely
    cameraControls(true)           // orbit controls (default: true)
    autoRotate(true)               // auto-spin camera
    autoCenterContent(true)        // center loaded content on the camera target
                                   //  (default: true) — port of iOS autoCenterContent.
                                   //  Pass false for intentional off-center scenes.
}) { sceneView -> /* onReady */ }
```

---

### OrbitCameraController

Attached automatically when `cameraControls(true)` (the default).
Mouse: left-drag = orbit, right-drag = pan, scroll = zoom. Touch: drag = orbit, pinch = zoom.

```kotlin
controller.theta            // horizontal angle (radians)
controller.phi              // vertical angle (radians)
controller.distance         // distance from target
controller.minDistance      // default 0.5
controller.maxDistance      // default 50.0
controller.autoRotate       // Boolean
controller.autoRotateSpeed  // radians/frame (default 30°/s at 60fps)
controller.enableDamping    // inertia (default true)
controller.dampingFactor    // default 0.95
controller.rotateSensitivity // default 0.005
controller.zoomSensitivity   // default 0.1
controller.panSensitivity    // default 0.003
controller.target(x, y, z) // set look-at point
controller.update(): Boolean // call each frame (automatic inside SceneView render loop); returns true if the camera moved this frame — the on-demand render gate repaints only when update() reports moved (#2332)
controller.dispose()
```

---

### JavaScript API (window.sceneview — from script-tag usage)

```js
// Simple model viewer (creates viewer + loads model)
sceneview.modelViewer(canvasId, modelUrl)
  .then(sv => { /* SceneViewer instance */ })

// Model viewer with autoRotate toggle
sceneview.modelViewerAutoRotate(canvasId, modelUrl, autoRotate)
  .then(sv => { /* SceneViewer instance */ })

// Full viewer (camera + light customization)
sceneview.createViewer(canvasId)          // autoRotate=true, cameraControls=true
sceneview.createViewerAutoRotate(canvasId, autoRotate)
sceneview.createViewerFull(
  canvasId, autoRotate, cameraControls,
  cameraX, cameraY, cameraZ, fov, lightIntensity
).then(sv => { /* SceneViewer */ })

// The returned Promise REJECTS if Filament fails to initialize (bad canvas,
// no WebGL2, engine error) — always handle .catch, don't assume it resolves.
sceneview.createViewer(canvasId)
  .then(sv => { /* SceneViewer ready */ })
  .catch(err => { /* init failed — show a fallback / message */ })
```

SceneViewer instance methods (all return the viewer for chaining unless noted):
```js
sv.loadModel(url)           // → Promise<url>
sv.setEnvironment(iblUrl)
sv.setEnvironmentWithSkybox(iblUrl, skyboxUrl)
sv.setCameraOrbit(theta, phi, distance)  // radians
sv.setCameraTarget(x, y, z)
sv.setAutoRotate(enabled)   // Boolean
sv.setAutoRotateSpeed(radiansPerFrame)
sv.setZoomLimits(min, max)
sv.setBackgroundColor(r, g, b, a)  // 0-1 range
sv.setAutoCenterContent(enabled)   // Boolean — center loaded content (default true)
sv.fitToModels()
sv.startRendering()
sv.stopRendering()
sv.resize(width, height)
sv.dispose()

// Node scene-graph (since slice 3 / P4 of #2024) — each returns a NodeHandle
sv.addNode()                       // → NodeHandle (empty pivot)
sv.addModelNode(url)               // → Promise<NodeHandle> (resolves when loaded)
sv.addSplatNode(url)               // → Promise<NodeHandle> (3D Gaussian Splatting; .ply/.spz, #2646 P2)
sv.addCubeNode(size)               // → NodeHandle
sv.addSphereNode(radius)           // → NodeHandle
sv.addLightNode(type)              // type: "directional" | "point" | "spot" → NodeHandle
sv.removeNode(handle)              // detach + free the node's entity
sv.hitTest(x, y)                   // → NodeHandle[] nearest-first (#2024 P5c)
```

Screen-point picking (`sv.hitTest(x, y)`, #2024 P5c): coordinates are canvas
pixels ((0,0) = top-left). The point is unprojected through the live camera
into a world ray and tested against every node's real bounds (models and
geometry: their asset AABB; splats: the cloud bounds), transformed by each
node's current world transform. Returns the **same** `NodeHandle` instances
the `add*Node` factories returned (`===`-comparable), sorted nearest-first:

```js
const cube = sv.addCubeNode(0.5);
canvas.addEventListener('click', (e) => {
  // Scale CSS event coords to backing-store pixels (devicePixelRatio canvases).
  const px = canvas.width / canvas.clientWidth;
  const hits = sv.hitTest(e.offsetX * px, e.offsetY * px);
  if (hits[0] === cube) cube.setScaleUniform(1.5);
});
```

Model nodes become pickable once loaded (their AABB is only readable then);
geometry and splat nodes are pickable immediately. Empty pivots, lights and
cameras have no bounds and are never hit. Kotlin/JS additionally exposes
`SceneView.hitTest(x, y): List<HitResult>` (node + distance + world point),
a world-`Ray` overload for XR controller poses, and `Node.collisionShape`
(the Android mirror) to override a node's local picking bounds or set
`null` to make it un-pickable; `Node.isHittable = false` skips a node.

NodeHandle methods (opaque handle to a retained node; address it after create()):
```js
handle.setPosition(x, y, z)        // local position
handle.setRotation(x, y, z)        // Euler degrees (ZYX)
handle.setScale(x, y, z)           // non-uniform scale
handle.setScaleUniform(s)          // uniform scale
handle.setVisible(v)               // Boolean — model/geometry: add/remove from scene
handle.visible                     // Boolean (read-only)
handle.addChild(childHandle)       // re-parent (child keeps its local transform)
handle.removeChild(childHandle)
handle.getWorldPosition()          // → [x, y, z] composed through the parent chain
handle.destroy()                   // remove + free entity (idempotent)
```

Kotlin/JS retained nodes additionally support smooth transform animation
(#2024 P5b — the Android `Node.smoothTransform` core semantics and the same
`5f` default speed; web has no `isSmoothTransformEnabled` gate and no
`onSmoothEnd` callback. Not yet on the JS `NodeHandle` surface):

```kotlin
// Animate a node toward a target LOCAL transform — speed-scaled slerp/lerp
// stepped on the scene's frame loop (zero per-tick matrix decompositions).
node.smoothTransformSpeed = 10f    // higher = faster convergence (default 5f)
node.smoothTransform = Transform(position = Position(0f, 1f, -2f))
// On convergence the node snaps to the target and smoothTransform resets to
// null. Setting null cancels in place (the node keeps its current transform).
// Only animates while the node is attached to a SceneView — the scene's
// frame dispatch drives it, and it keeps the on-demand render gate awake.
```

---

### WebXR — ARSceneView (browser AR)

Requires WebXR Device API. Supported: Chrome Android 79+, Meta Quest Browser, Safari iOS 18+.
Must be called from a user gesture (button click).

```kotlin
// Check AR support first
ARSceneView.checkSupport { supported ->
    if (supported) {
        // Must be in a click handler
        ARSceneView.create(
            canvas = canvas,
            features = WebXRSession.Features(
                required = arrayOf(XRFeature.HIT_TEST),
                optional = arrayOf(XRFeature.DOM_OVERLAY, XRFeature.LIGHT_ESTIMATION)
            ),
            onError = { msg -> console.error(msg) },
            onReady = { arView ->
                arView.onHitTest = { pose: XRPose ->
                    // Surface detected — place content at pose
                    arView.loadModel("models/chair.glb")
                }
                arView.onSelect = { source: XRInputSource ->
                    // User tapped
                }
                arView.onSessionEnd = { /* AR session ended */ }
                arView.start()
            }
        )
    }
}

arView.stop()               // ends the XR session
arView.sceneView            // underlying SceneView for direct Filament access
```

XRFeature constants: `XRFeature.HIT_TEST`, `XRFeature.DOM_OVERLAY`, `XRFeature.LIGHT_ESTIMATION`, `XRFeature.HAND_TRACKING`, `XRFeature.DEPTH_SENSING`, `XRFeature.IMAGE_TRACKING`, `XRFeature.ANCHORS`, `XRFeature.PLANE_DETECTION`, `XRFeature.MESH_DETECTION`, `XRFeature.LAYERS`

---

### WebXR — VRSceneView (browser VR)

Requires WebXR immersive-vr. Supported: Meta Quest Browser, Chrome with headset, Firefox Reality.

```kotlin
VRSceneView.checkSupport { supported ->
    if (supported) {
        VRSceneView.create(
            canvas = canvas,
            features = WebXRSession.Features(optional = arrayOf(XRFeature.HAND_TRACKING)),
            referenceSpaceType = XRReferenceSpaceType.LOCAL_FLOOR,
            onError = { msg -> },
            onReady = { vrView ->
                vrView.sceneView.loadModel("models/room.glb")
                vrView.onFrame = { frame: XRFrame, pose: XRViewerPose? -> /* per-frame */ }
                vrView.onInputSelect = { source: XRInputSource, pose: XRPose? -> /* trigger */ }
                vrView.onInputSqueeze = { source, pose -> /* grip */ }
                vrView.onSessionEnd = { }
                vrView.start()
            }
        )
    }
}
```

---

### WebXRSession (low-level — AR + VR unified)

```kotlin
WebXRSession.checkSupport(mode = XRSessionMode.IMMERSIVE_AR) { supported -> }

WebXRSession.create(
    canvas = canvas,
    mode = XRSessionMode.IMMERSIVE_AR,  // or IMMERSIVE_VR
    features = WebXRSession.Features(
        required = arrayOf(XRFeature.HIT_TEST),
        optional = arrayOf(XRFeature.DOM_OVERLAY, XRFeature.LIGHT_ESTIMATION, XRFeature.HAND_TRACKING)
    ),
    referenceSpaceType = XRReferenceSpaceType.LOCAL_FLOOR,
    onError = { msg -> },
    onReady = { session ->
        session.onFrame = { frame, pose -> }
        session.onHitTest = { pose -> }      // AR only
        session.onInputSelect = { source, pose -> }
        session.onInputSqueeze = { source, pose -> }
        session.onInputSourcesChange = { added, removed -> }
        session.onSessionEnd = { }
        session.loadModel(url)
        session.setEntityTransform(entity, xrTransform)
        session.start()
        session.stop()
        session.isAR  // Boolean
        session.isVR  // Boolean
    }
)
```

XRSessionMode: `XRSessionMode.IMMERSIVE_AR`, `XRSessionMode.IMMERSIVE_VR`
XRReferenceSpaceType: `LOCAL_FLOOR`, `LOCAL`, `VIEWER`, `BOUNDED_FLOOR`, `UNBOUNDED`

---

### WebXR — Depth-sensing, Hand-tracking, Image-tracking, Anchors (sceneview-web ≥ 4.10.0)

Parity layer for WebXR feature strings — mirrors the Android `arsceneview` composables. Add to `WebXRSession.Features.required` or `optional`.

XRFeature constants now include `XRFeature.DEPTH_SENSING`, `XRFeature.HAND_TRACKING`, `XRFeature.IMAGE_TRACKING`, `XRFeature.ANCHORS`, `XRFeature.PLANE_DETECTION`, `XRFeature.MESH_DETECTION` (the last two are still raw — no composable yet).

#### Depth sensing (real-world occlusion)

Quest 3 + ARCore Android Chrome only. Request `XRFeature.DEPTH_SENSING` and read per-frame depth via the [`getDepthInformation`](https://www.w3.org/TR/webxr-depth-sensing-1/) extension on `XRFrame`.

```kotlin
WebXRSession.create(
    canvas = canvas,
    mode = XRSessionMode.IMMERSIVE_AR,
    features = WebXRSession.Features(
        required = arrayOf(XRFeature.HIT_TEST),
        optional = arrayOf(XRFeature.DEPTH_SENSING)
    ),
    onReady = { session ->
        session.onFrame = { frame, viewerPose ->
            val view = viewerPose?.views?.firstOrNull() ?: return@onFrame
            val raw = frame.getDepthInformation(view)        // XRCPUDepthInformation?
            val depth = XRDepthInfo.from(raw) ?: return@onFrame
            if (depth.isCpuReadable) {
                val centerMeters = depth.atNormalized(0.5, 0.5)   // Double; +Inf if invalid
                // Discard fragments behind centerMeters when rendering
            }
        }
        session.start()
    }
)
```

`XRDepthInfo`: `raw` (raw `XRDepthInformation`), `width`, `height`, `rawValueToMeters`, `isCpuReadable`, `atNormalized(x: Double, y: Double): Double`. Inputs are clamped to `[0, 1]`. `atNormalized` returns `Double.POSITIVE_INFINITY` for GPU-only buffers or invalid samples.

`XRDepthUsage`: `CPU_OPTIMIZED`, `GPU_OPTIMIZED`. `XRDepthFormat`: `LUMINANCE_ALPHA`, `FLOAT32`.

For GPU-side occlusion (shader sampling) ship the bundled `DepthOcclusionShader.VERTEX_SHADER` / `DepthOcclusionShader.FRAGMENT_SHADER` (GLSL ES 300, ~80 instr.) — set uniforms `uDepthTexture`, `uRawValueToMeters`, `uNormDepthBufferFromNormView`, `uColor`, `uFragmentDepthMeters`. Mirrors Android `ARCameraStream` depth occlusion.

#### Hand tracking (`XRHandNode`)

Quest 3 / Quest Pro / any WebXR runtime exposing `hand-tracking`. 25 spec-defined joints; short aliases live on `XRHandNode.Joint` (`INDEX_TIP`, `THUMB_TIP`, `WRIST`, `MIDDLE_INTERMEDIATE`, …). `XRHandNode.Joint.ALL` is the full ordered list.

```kotlin
val leftHand = XRHandNode(XRHandNode.Handedness.LEFT)
    .joint(XRHandNode.Joint.INDEX_TIP) { pose -> /* pose.transform.position is the fingertip */ }
    .joint(XRHandNode.Joint.THUMB_TIP) { pose -> }
    .joint(XRHandNode.Joint.WRIST)     { pose -> }

leftHand.onHandFound = { hand -> }   // first frame the hand becomes tracked
leftHand.onHandLost  = { }           // the frame the hand disappears

session.onFrame = { frame, _ -> leftHand.update(frame, session.referenceSpace) }
```

API: `joint(joint, block)` (chainable), `removeJoint(joint)`, `clear()`, `jointCount`, `isObserving(joint)`. The `update(frame, referenceSpace)` call walks `frame.session.inputSources`, finds the source with matching `handedness` and non-null `hand`, and invokes each registered joint callback with the per-frame `XRPose`. Joints with no resolvable pose (lost tracking) are silently skipped.

#### Image tracking (`XRImageTrackingNode`)

WebXR `image-tracking` ([marker-tracking explainer](https://github.com/immersive-web/marker-tracking)) — Android Chrome flag / origin trial. Mirrors Android `AugmentedImageNode`. Register the images at session creation via the `trackedImages` init dict; index into them with `XRImageTrackingNode`.

```kotlin
// 1. Build trackedImages[] (ImageBitmap + widthInMeters) via dynamic JS;
//    WebXR spec passes an external Web API type (ImageBitmap).
val sessionOptions = js("{}")
sessionOptions.requiredFeatures = arrayOf(XRFeature.IMAGE_TRACKING)
sessionOptions.trackedImages = arrayOf(js("{ image: bitmap, widthInMeters: 0.2 }"))

// 2. Wrap the per-image result:
val poster = XRImageTrackingNode(index = 0) { pose, result ->
    // pose.transform.matrix = 4x4 column-major model matrix
    // result.measuredWidthInMeters / result.trackingState ("tracked" | "emulated")
}
poster.onFound = { result -> }
poster.onLost  = { }

session.onFrame = { frame, _ -> poster.update(frame, session.referenceSpace) }
```

Tracking state constants: `XRImageTrackingState.TRACKED`, `XRImageTrackingState.EMULATED`, `XRImageTrackingState.UNTRACKED`. Helper: `frame.getImageTrackingResults(): Array<XRImageTrackingResult>` (extension on `XRFrame`).

#### Anchors (`XRAnchorNode`)

Chrome on Android (ARCore) + Quest 3. Mirrors Android `arsceneview` `AnchorNode`. Create the underlying `XRAnchor` via `frame.createAnchor(transform, referenceSpace)` or via the hit-test helper `result.createAnchor()` and wrap it:

```kotlin
val anchorPromise = session.xrSession.asDynamic().createAnchor(transform, session.referenceSpace)
anchorPromise.then { anchor: XRAnchor ->
    val node = XRAnchorNode(anchor) { pose ->
        // Optional per-frame pose callback (pose.transform.matrix, column-major)
    }
    // Scene-graph bridge (#2024 P5a): bind a ROOT-level Node so its
    // worldTransform follows the anchor pose each update() — children
    // parented under it compose for free. stopDriving() releases it.
    node.drive(sceneView.addModelNode("models/chair.glb"))
    node.onAttached = { firstPose -> }
    node.onLost     = { }
    anchors += node
}

session.onFrame = { frame, _ ->
    val it = anchors.iterator()
    while (it.hasNext()) {
        val node = it.next()
        if (!node.update(frame, session.referenceSpace)) it.remove()
    }
}

// Cleanup
session.onSessionEnd = { anchors.forEach { it.detach() }; anchors.clear() }
```

`update(frame, referenceSpace)` returns `false` when the anchor is no longer in `frame.trackedAnchors` — drop the node from your list. `detach()` calls `XRAnchor.delete()` and is idempotent.

`drive(node)` requires a parentless (root) `Node` — anchor poses are world-space, so a parented node would double-compose (the call throws; a node re-parented *after* `drive` is skipped with a one-time console warning). A destroyed node is auto-released. `drivenNode` exposes the currently bound node.

XRFrame additions: `trackedAnchors` (anchor set), extension `frame.getDepthInformation(view)`, extension `frame.getImageTrackingResults()`.

---

### Threading rules (Web)

- All Filament API calls happen on the **JS main thread** (there is no concept of background threads in browser JS).
- `SceneView.create` and `loadModel` are async (Promise-based) — await them before calling instance methods.
- `loadModel` internally calls `asset.loadResources()` which fetches external textures asynchronously; the `onLoaded` callback fires when textures are ready.
- Never call `destroy()` inside an animation frame callback — defer to next microtask.

---

### Web Geometry DSL (Kotlin/JS)

```kotlin
SceneView.create(canvas, configure = {
    geometry { cube(); size(1.0, 1.0, 1.0); color(1.0, 0.0, 0.0, 1.0); position(0.0, 0.5, -2.0) }
    geometry { sphere(); radius(0.5); color(0.0, 0.5, 1.0, 1.0) }
    geometry { cylinder(); radius(0.3); height(1.5); color(0.0, 1.0, 0.5, 1.0) }
    geometry { plane(); size(5.0, 5.0, 0.0); color(0.3, 0.3, 0.3, 1.0); position(0.0, 0.0, 0.0) }
}) { sceneView -> sceneView.startRendering() }
```

Geometry types: `cube` (w/h/d via `size(x,y,z)`), `sphere` (`radius(r)`), `cylinder` (`radius(r)` + `height(h)`), `plane` (`size(w,h,0)`)
All geometry shares the PBR material pipeline — supports `color` (base color factor), `position`, `rotation` (Euler degrees), `scale`.

---

## SceneViewSwift (iOS / macOS / visionOS)

Renderer: **RealityKit**. Requires iOS 18+ / macOS 15+ / visionOS 2+.

SPM dependency (Package.swift or Xcode):
```swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.25.0")
```

Import: `import SceneViewSwift`

Architecture: RealityKit is the rendering backend on all Apple platforms. Logic shared
with Android uses the `sceneview-core` KMP XCFramework (collision, math, geometry,
animations). There is NO Filament dependency on Apple.

---

### SceneView (SwiftUI view — 3D only)

```swift
// Declarative init — @NodeBuilder DSL
public struct SceneView: View {
    public init(@NodeBuilder content: @escaping () -> [Entity])

    // Imperative init — receives root Entity, add children manually
    public init(_ content: @escaping (Entity) -> Void)
}
```

View modifiers (chainable):
```swift
.environment(_ environment: SceneEnvironment) -> SceneView  // IBL lighting
.cameraControls(_ mode: CameraControlMode) -> SceneView     // .orbit (default), .pan, .firstPerson | iOS-only: .none, .tilt, .dolly, .gimbal
.recentersTargetOnOrbit(_ enabled: Bool) -> SceneView       // v4.4.0+ — re-pivot on content centroid when (re-)entering orbit; default false
.onEntityTapped(_ handler: @escaping (Entity) -> Void) -> SceneView   // real entity hit-test (v4.2.0+)
.autoRotate(speed: Float = 0.3) -> SceneView                // radians/s, default 0.3; 0 starts no rotation loop at all (freeze on the authored pose)
.mainLight(_ slot: LightSlot) -> SceneView                  // v4.2.0+ — see LightSlot below
.fillLight(_ slot: LightSlot) -> SceneView                  // v4.2.0+ — Android-parity 2-light setup
.renderQuality(_ preset: RenderQuality) -> SceneView        // v4.2.0+ — .cinematic / .default / .performance
.autoCenterContent(_ enabled: Bool) -> SceneView            // v4.3.0+ — default true; translates content so its centroid lands on the world origin
.framingMargin(_ margin: Float) -> SceneView                // v4.26.0+ — padding on the auto-fit distance; 1.15 default, 1.0 = sphere tangent, < 1 = tighter. NO EFFECT when autoCenterContent(false)
.cameraOrbit(azimuth: Float? = nil, elevation: Float? = nil) -> SceneView  // v4.26.0+ — seeds the INITIAL orbit pose (radians); default elevation 30°
```

**Framing the subject (`framingMargin` / `cameraOrbit`, v4.26.0+).** The auto-fit
pass dollies the camera until the content's *bounding sphere* fits the narrower
frustum axis — a sphere fit, so it never clips at any orbit angle — then scales
that distance by `framingMargin`. `1.0` makes the sphere exactly tangent; below
`1.0` only the sphere's empty corners leave frame, which is how a subject is made
to fill a tall portrait viewport. Keep it at or above ~0.95 on an auto-rotating
scene: the capture angle is arbitrary, and a long model clips at its broadside
azimuth below that.

`cameraOrbit` seeds the starting pose only — drag, `autoRotate`, and the auto-fit
(which changes distance, never these angles) own the camera afterwards. Elevation
matters more than it looks: with the 60° vertical FOV, the 30° default pitch puts
the horizon exactly on the top edge, so a scene with a `showSkybox` environment
shows none of its sky at the default pose regardless of framing. Lower the
elevation to bring sky into frame.

```swift
SceneView { root in root.addChild(hero.entity) }
    .environment(.warm)
    .framingMargin(0.95)                                 // fill the frame
    .cameraOrbit(azimuth: .pi / 5, elevation: .pi / 15)  // 36° around, camera 12° above the target
```

> **Android equivalent — `padding`, and the unit is inverted.** Android's lever is
> `CameraNode.frameToContent(padding = …)` / `frameToBounds(…)` /
> `fitDistanceForBounds(…)`, with `DEFAULT_FRAMING_PADDING = 0.15f` (documented
> above under Camera framing). It is a *fraction* where iOS's `framingMargin` is a
> *multiplier*: `margin == 1 + padding`, so iOS `1.15` is Android `0.15`, and
> tangent is iOS `1.0` / Android `0.0`. Android's demo host additionally accepts a
> `camera_distance` intent extra, but that is a sample-app knob, not the library
> API. **Web has no framing-padding lever** — `fitToModels()` takes no margin —
> but the initial orbit *is* settable, imperatively rather than declaratively:
> `sv.setCameraOrbit(theta, phi, distance)`. Android also clamps padding to
> `max(0, padding)`, so it cannot frame **tighter** than tangent, while iOS
> `framingMargin` accepts down to `0.2` — the sub-tangent range has no Android
> equivalent today. The library-side parity gap (a per-scene padding parameter on
> Android's `Scene { }`, a margin on Web's `fitToModels()`) is tracked in #2946;
> the iOS launch-argument counterpart to the sample-app intent extra is a separate
> item, #2785. These modifiers are the in-scene lever (#2896).

### Render defaults (v4.2.0+)

iOS now ships the Android-parity 2-light setup out of the box (matches the v4.1.0 BREAKING render-defaults change finally landing on iOS):

- **Main / key light**: `LightNode.directional(intensity: 10_000, castsShadow: true)` pointing straight down (`(0, -1, 0)`)
- **Fill light**: `LightNode.fill(intensity: 3_000, castsShadow: false)` from `(0.5, -0.5, 0.5)` (upper-back-left → down-front-right) — 30% of main intensity, lifts the shadow side without flattening
- **Defaults applied via `.systemDefault` slot value** — overridable per-slot

```swift
public enum LightSlot {
    case systemDefault   // built-in default (Android-parity)
    case disabled        // remove the slot — single-light setup with .fillLight(.disabled)
    case custom(LightNode)
}
```

Examples:
```swift
SceneView { /* ... */ }
  .fillLight(.disabled)                                // single-light hard-shadow look

SceneView { /* ... */ }
  .mainLight(.custom(LightNode.directional(intensity: 5_000)))   // dimmer key
  .fillLight(.custom(LightNode.fill(intensity: 6_000)))          // brighter fill

SceneView { /* ... */ }
  .renderQuality(.cinematic)         // shadows on, IBL ≥ 1.0
SceneView { /* ... */ }
  .renderQuality(.performance)       // shadows off, IBL halved (low-end devices)
```

### Per-entity gestures (v4.2.0+)

`NodeGesture` lets you scope tap / drag / scale / rotate / longPress handlers per-entity (mirrors Android's gesture detection). Enabled automatically when `SceneView` is in the view hierarchy — `targetedToAnyEntity()` ensures empty-space gestures keep driving the camera.

```swift
let cube = GeometryNode.cube(size: 0.3, color: .blue)
cube.entity
    .onTap { print("Cube tapped!") }
    .onDrag { translation in cube.position += translation }
    .onScale { magnification in cube.scale *= magnification }
    .onRotate { angle in cube.rotation = simd_quatf(angle: angle, axis: [0, 1, 0]) }
    .onLongPress { print("Cube held 0.5s") }
```

Or via the imperative API:
```swift
NodeGesture.onTap(cube.entity) { print("Tapped!") }
NodeGesture.removeAll(from: cube.entity)   // cleanup when content unloads
```

### AR Anchors (v4.2.0+)

`AnchorNode` factories cover all ARKit anchor types:

```swift
AnchorNode.world(position: SIMD3<Float>) -> AnchorNode             // world-space pose
AnchorNode.plane(alignment: .horizontal | .vertical,               // detected plane
                 minimumBounds: SIMD2<Float>) -> AnchorNode
AnchorNode.image(group: String, name: String) -> AnchorNode        // detected reference image
AnchorNode.face() -> AnchorNode                                    // front-camera face (pose only)
AnchorNode.body() -> AnchorNode                                    // rear-camera body root joint
```

Minimal usage:
```swift
@State private var model: ModelNode?

var body: some View {
    SceneView {
        GeometryNode.cube(size: 0.3, color: .red)
            .position(.init(x: -1, y: 0, z: -2))
        GeometryNode.sphere(radius: 0.2, color: .blue)
        LightNode.directional(intensity: 1000)
    }
    .environment(.studio)
    .cameraControls(.orbit)
    .task {
        model = try? await ModelNode.load("models/car.usdz")
    }
}
```

With model loading:
```swift
@State private var model: ModelNode?

SceneView { root in
    if let model {
        root.addChild(model.entity)
    }
}
.environment(.outdoor)
.cameraControls(.orbit)
.onEntityTapped { entity in print("Tapped: \(entity)") }
.task {
    model = try? await ModelNode.load("models/car.usdz")
}
```

---

### ARSceneView (SwiftUI view — AR, iOS only)

```swift
public struct ARSceneView: UIViewRepresentable {
    public init(
        planeDetection: PlaneDetectionMode = .horizontal,
        showPlaneOverlay: Bool = true,
        showCoachingOverlay: Bool = true,
        cameraExposure: Float? = nil,        // EV compensation — nil = ARKit auto-exposure
        imageTrackingDatabase: Set<ARReferenceImage>? = nil,
        faceTracking: Bool = false,
        onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)? = nil,
        onImageDetected: ((String, AnchorNode, ARView) -> Void)? = nil,
        onFrame: ((ARFrame, ARView) -> Void)? = nil
    )
}
```

View modifiers (chainable):
```swift
.onSessionStarted(_ handler: @escaping (ARView) -> Void) -> ARSceneView
.cameraExposure(_ ev: Float?) -> ARSceneView   // EV stops; iOS 15+ CIColorControls post-process
.onFrame(_ handler: @escaping (ARFrame, ARView) -> Void) -> ARSceneView
.mainLight(_ slot: LightSlot) -> ARSceneView   // v4.3.0+ — Android-parity directional key light (#1138)
.fillLight(_ slot: LightSlot) -> ARSceneView   // v4.3.0+ — Android-parity dual-light AR baseline (#1138)
```

`PlaneDetectionMode` values: `.none`, `.horizontal`, `.vertical`, `.both`

`cameraExposure` notes:
- Mirrors Android's `ARSceneView(cameraExposure: Float?)`.
- Positive values brighten; negative values darken. One stop = ±0.5 brightness unit.
- Implemented via `ARView.renderCallbacks.postProcess` (iOS 15+); no-op on earlier versions.

`mainLight` / `fillLight` notes (v4.3.0+, `#1138` — iOS half of `#1063`):
- Mirrors Android's `ARSceneView(mainLightNode = …, fillLightNode = …)` parameters.
- Default `LightSlot.systemDefault` provisions a `10 000`-lux directional main + a `3 000`-lux fill (matches the Android v4.1.0 BREAKING render-defaults change). Same `LightSlot` enum as the 3D ``SceneView``.
- ARKit has no equivalent of ARCore's `ENVIRONMENTAL_HDR` directional-light estimation; the explicit lights keep their baseline intensities each frame. `.automatic` environment texturing handles PBR cubemap reflections.
- Pass `.disabled` on `fillLight` for a single-light AR setup (rare — looks harsh).
- The light is added as a `AnchorEntity(world: .zero)` so it renders at session-start origin.

Minimal AR usage:
```swift
ARSceneView(
    planeDetection: .horizontal,
    showCoachingOverlay: true,
    onTapOnPlane: { position, arView in
        let cube = GeometryNode.cube(size: 0.1, color: .blue)
        let anchor = AnchorNode.world(position: position)
        anchor.add(cube.entity)
        arView.scene.addAnchor(anchor.entity)
    }
)
```

Image tracking:
```swift
let images = AugmentedImageNode.createImageDatabase([
    AugmentedImageNode.ReferenceImage(
        name: "poster",
        image: UIImage(named: "poster_reference")!,
        physicalWidth: 0.3  // 30 cm
    )
])

ARSceneView(
    imageTrackingDatabase: images,
    onImageDetected: { imageName, anchor, arView in
        let label = TextNode(text: imageName, fontSize: 0.05, color: .white)
        anchor.add(label.entity)
        arView.scene.addAnchor(anchor.entity)
    }
)
```

---

### Node types

#### ModelNode — 3D model (USDZ / Reality)

```swift
public struct ModelNode: @unchecked Sendable {
    public let entity: ModelEntity

    // Loading (always @MainActor, async)
    public static func load(_ path: String, enableCollision: Bool = true) async throws -> ModelNode
    public static func load(contentsOf url: URL, enableCollision: Bool = true) async throws -> ModelNode
    public static func load(from remoteURL: URL, enableCollision: Bool = true, timeout: TimeInterval = 60.0) async throws -> ModelNode

    // Transform (fluent / chainable)
    public func position(_ position: SIMD3<Float>) -> ModelNode
    public func scale(_ uniform: Float) -> ModelNode
    public func scale(_ scale: SIMD3<Float>) -> ModelNode
    public func rotation(_ rotation: simd_quatf) -> ModelNode
    public func rotation(angle: Float, axis: SIMD3<Float>) -> ModelNode
    public func scaleToUnits(_ units: Float = 1.0) -> ModelNode   // fits in cube of 'units' meters
    public func centerOrigin(_ target: SIMD3<Float> = .zero) -> ModelNode  // moves the bounds centre to `target` (absolute point); `.zero` = center on origin
    public func centerOrigin(normalized origin: SIMD3<Float>) -> ModelNode  // normalized AABB origin (-1..1, 0 = centre) — Android `centerOrigin(Position)` parity; `SIMD3(0,-1,0)` bottom-aligns

    // Animation — `speed` is now actually applied to the RealityKit animation (was a no-op before v4.0.10)
    public var animationCount: Int
    public var animationNames: [String]
    public func playAllAnimations(loop: Bool = true, speed: Float = 1.0)
    public func playAnimation(at index: Int, loop: Bool = true, speed: Float = 1.0, transitionDuration: TimeInterval = 0.2)
    public func playAnimation(named name: String, loop: Bool = true, speed: Float = 1.0, transitionDuration: TimeInterval = 0.2)
    public func stopAllAnimations()
    public func pauseAllAnimations()
    public func resumeAllAnimations()

    // Material
    public func setColor(_ color: SimpleMaterial.Color) -> ModelNode
    public func setMetallic(_ value: Float) -> ModelNode     // 0 = dielectric, 1 = metal
    public func setRoughness(_ value: Float) -> ModelNode    // 0 = smooth, 1 = rough
    public func opacity(_ value: Float) -> ModelNode         // 0 = transparent, 1 = opaque

    // Misc
    public func enableCollision()
    public func withGroundingShadow() -> ModelNode           // iOS 18+ / visionOS 2+
    public mutating func onTap(_ handler: @escaping () -> Void) -> ModelNode
}
```

Key behaviors:
- Supports `.usdz` and `.reality` files natively. glTF support planned via GLTFKit2.
- `load(_:)` calls `Entity(named:)` — file must be in the app bundle or an accessible URL.
- `load(from:)` downloads to a temp file, loads, then cleans up.
- `scaleToUnits(_:)` mirrors Android's `ModelNode(scaleToUnits = 1f)`.
- `centerOrigin(normalized:)` — **apply before positioning.** Unlike Android's `centerOrigin` (which composes additively — `position += translation`, independent of the current position), this reads the entity's **world** visual-bounds, so it does not compose with a previously-set position: call it on an unpositioned, unparented entity (load → scaleToUnits → centerOrigin, before `.position(_:)` or anchoring), and a later `.position(_:)` replaces the grounding offset.

#### LightNode — light source

```swift
public struct LightNode: Sendable {
    public static func directional(
        color: LightNode.Color = .white,
        intensity: Float = 1000,      // lux
        castsShadow: Bool = true
    ) -> LightNode

    public static func point(
        color: LightNode.Color = .white,
        intensity: Float = 1000,       // lumens
        attenuationRadius: Float = 10.0
    ) -> LightNode

    public static func spot(
        color: LightNode.Color = .white,
        intensity: Float = 1000,
        innerAngle: Float = .pi / 6,   // radians
        outerAngle: Float = .pi / 4,
        attenuationRadius: Float = 10.0
    ) -> LightNode

    // Fluent modifiers
    public func position(_ position: SIMD3<Float>) -> LightNode
    public func lookAt(_ target: SIMD3<Float>) -> LightNode
    public func castsShadow(_ enabled: Bool) -> LightNode
    public func attenuationRadius(_ radius: Float) -> LightNode
    public func shadowMaximumDistance(_ distance: Float) -> LightNode
}

// LightNode.Color
public enum Color: Sendable {
    case white
    case warm    // ~3200K tungsten
    case cool    // ~6500K daylight
    case custom(r: Float, g: Float, b: Float)
}
```

#### GeometryNode — procedural primitives

```swift
public struct GeometryNode: Sendable {
    // Primitives (simple color)
    // Simple-color factories also accept `unlit: Bool = false` (flat fill, ignores lighting)
    public static func cube(size: Float = 1.0, color: SimpleMaterial.Color = .white, cornerRadius: Float = 0) -> GeometryNode
    public static func sphere(radius: Float = 0.5, color: SimpleMaterial.Color = .white) -> GeometryNode
    public static func cylinder(radius: Float = 0.5, height: Float = 1.0, color: SimpleMaterial.Color = .white) -> GeometryNode
    public static func plane(width: Float = 1.0, depth: Float = 1.0, color: SimpleMaterial.Color = .white) -> GeometryNode
    public static func torus(majorRadius: Float = 0.4, minorRadius: Float = 0.15, color: SimpleMaterial.Color = .white) -> GeometryNode
    public static func capsule(radius: Float = 0.25, height: Float = 1.0, color: SimpleMaterial.Color = .white) -> GeometryNode
    public static func cone(height: Float = 1.0, radius: Float = 0.5, color: SimpleMaterial.Color = .white) -> GeometryNode

    // Primitives with PBR material
    public static func cube(size: Float = 1.0, material: GeometryMaterial, cornerRadius: Float = 0) -> GeometryNode
    public static func sphere(radius: Float = 0.5, material: GeometryMaterial) -> GeometryNode

    // Fluent modifiers
    public func position(_ position: SIMD3<Float>) -> GeometryNode
    public func scale(_ uniform: Float) -> GeometryNode
    public func rotation(_ rotation: simd_quatf) -> GeometryNode
    public func rotation(angle: Float, axis: SIMD3<Float>) -> GeometryNode
    public func withGroundingShadow() -> GeometryNode    // iOS 18+ / visionOS 2+
}
```

`GeometryMaterial` (enum):
```swift
public enum GeometryMaterial: @unchecked Sendable {
    case simple(color: SimpleMaterial.Color)
    case pbr(color: SimpleMaterial.Color, metallic: Float = 0.0, roughness: Float = 0.5)
    case textured(baseColor: TextureResource, normal: TextureResource? = nil, metallic: Float = 0.0, roughness: Float = 0.5, tint: SimpleMaterial.Color = .white)
    case unlit(color: SimpleMaterial.Color)
    case unlitTextured(texture: TextureResource, tint: SimpleMaterial.Color = .white)
    case custom(any RealityKit.Material)

    // Texture loading helpers
    public static func loadTexture(_ name: String) async throws -> TextureResource
    public static func loadTexture(contentsOf url: URL) async throws -> TextureResource
}
```

#### AnchorNode — AR world anchors (iOS only)

```swift
public struct AnchorNode: Sendable {
    public let entity: AnchorEntity

    public static func world(position: SIMD3<Float>) -> AnchorNode
    public static func plane(alignment: PlaneAlignment = .horizontal, minimumBounds: SIMD2<Float> = .init(0.1, 0.1)) -> AnchorNode

    public func add(_ child: Entity)
    public func remove(_ child: Entity)
    public func removeAll()

    public enum PlaneAlignment: Sendable { case horizontal, vertical }
}
```

#### AugmentedImageNode — image tracking

```swift
public struct AugmentedImageNode: Sendable {
    public let imageName: String
    public let estimatedSize: CGSize
    public let anchorEntity: AnchorEntity

    public static func fromDetection(_ imageAnchor: ARImageAnchor) -> AugmentedImageNode

    // Image database creation
    public static func createImageDatabase(_ images: [ReferenceImage]) -> Set<ARReferenceImage>
    public static func referenceImages(inGroupNamed groupName: String) -> Set<ARReferenceImage>?

    public func add(_ child: Entity)
    public func removeAll()

    public struct ReferenceImage: Sendable {
        public init(name: String, image: UIImage, physicalWidth: CGFloat)
        public init(name: String, cgImage: CGImage, physicalWidth: CGFloat)
    }

    public enum TrackingState: Sendable { case tracking, limited, notTracking }
}
```

#### TextNode — 3D text labels

```swift
public struct TextNode: Sendable {
    public let entity: ModelEntity
    public let text: String

    public init(
        text: String,
        fontSize: Float = 0.05,         // meters (world space)
        color: SimpleMaterial.Color = .white,
        font: String = "Helvetica",
        alignment: CTTextAlignment = .center,
        depth: Float = 0.005,
        isMetallic: Bool = false
    )

    public func position(_ position: SIMD3<Float>) -> TextNode
    public func scale(_ uniform: Float) -> TextNode
}
```

#### VideoNode — video playback on a 3D plane

```swift
public struct VideoNode: @unchecked Sendable {
    public let entity: Entity
    public let player: AVPlayer

    public static func load(_ path: String) -> VideoNode        // bundle resource
    public static func load(url: URL) -> VideoNode              // file or http URL

    public func position(_ position: SIMD3<Float>) -> VideoNode
    public func size(width: Float, height: Float) -> VideoNode
    public func play()
    public func pause()
    public func stop()
    public func loop(_ enabled: Bool) -> VideoNode
}
```

---

### SceneEnvironment — IBL lighting

```swift
public struct SceneEnvironment: Sendable {
    public init(name: String, hdrResource: String? = nil, intensity: Float = 1.0, showSkybox: Bool = true)

    public static func custom(name: String, hdrFile: String, intensity: Float = 1.0, showSkybox: Bool = true) -> SceneEnvironment

    // Built-in presets
    public static let studio: SceneEnvironment    // neutral studio (default)
    public static let outdoor: SceneEnvironment   // warm daylight
    public static let sunset: SceneEnvironment    // golden hour
    public static let night: SceneEnvironment     // dark, moody
    public static let warm: SceneEnvironment      // slightly orange tone
    public static let autumn: SceneEnvironment    // soft natural outdoor

    public static let allPresets: [SceneEnvironment]
}
```

`SceneEnvironment.intensity` is a **linear multiplier** — `1.0` leaves the HDR's
own radiance untouched, `0.5` halves it. RealityKit's underlying
`ImageBasedLightComponent` takes a power-of-two *exponent*; `SceneEnvironment`
converts for you, so never pre-apply a `log2` to **this** property (#2897).

> ⚠️ **Do not carry an Android IBL value across.** Android's `Environment` has no
> intensity member; its IBL level is Filament's `IndirectLight.intensity` in
> **absolute lux**, defaulting to `DEFAULT_IBL_INTENSITY = 10_000`
> (`SceneFactories.kt`). `1.0` there is one lux — effectively black. The two
> platforms are tuned to match on the key-light-to-IBL *ratio*, not on absolute
> values, and iOS's exponent-0 baseline sits around 1000 lux equivalent. Web has
> no intensity knob at all: `environment(iblUrl, skyboxUrl)` takes none.
> The sibling `ReflectionProbeNode.intensity` is a **separate** property that
> still takes a raw exponent — see #2956.

`showSkybox: true` renders the HDR as a background. On iOS / macOS it uses
`RealityViewContent.environment = .skybox(...)`. On visionOS a windowed /
volumetric scene composites over passthrough and ignores the skybox; for a
fully immersive `ImmersiveSpace` consumer, opt in with `.immersiveSpace()`
and the HDR is rendered via a `WorldComponent`-rooted inverted sphere:

```swift
ImmersiveSpace(id: "scene") {
    SceneView { root in /* ... */ }
        .environment(.nightSky)   // showSkybox == true
        .immersiveSpace()         // render the HDR skybox on visionOS
}
.immersionStyle(selection: .constant(.full), in: .full)
```

---

### NodeBuilder — declarative scene composition

`@resultBuilder` for composing scene content inside `SceneView { }`:

```swift
@resultBuilder
public struct NodeBuilder {
    // Used automatically with @NodeBuilder closure syntax
}

// All node types conform to EntityProvider:
public protocol EntityProvider {
    var sceneEntity: Entity { get }
}
// Conformers: GeometryNode, ModelNode, LightNode, MeshNode, TextNode,
// ImageNode, BillboardNode, CameraNode, LineNode, PathNode, PhysicsNode,
// DynamicSkyNode, FogNode, ReflectionProbeNode, VideoNode, ShapeNode, ViewNode
```

---

### CameraControls

```swift
public enum CameraControlMode: Sendable {
    // Cross-platform modes (custom gesture math — orbit inertia, auto-rotate, fit-to-bounds)
    case orbit         // drag rotates the scene around the orbit pivot, pinch dollies (default)
    case pan           // drag translates the target laterally, pinch dollies
    case firstPerson   // v4.4.0+ true look-around: drag yaws/pitches the camera IN PLACE (no orbit, no teleport on mode switch), pinch adjusts FOV

    // iOS-only native modes — delegate to Apple's realityViewCameraControls(_:) modifier (#1049)
    // Custom gesture math (orbit inertia, auto-rotate, fit-to-bounds) is bypassed for these.
    case none          // disables all gesture interaction
    case tilt          // tilt camera up/down about horizontal axis
    case dolly         // zoom along look direction
    case gimbal        // rotate about all three axes independently (no orbit pivot)
}

public struct CameraControls: Sendable {
    public var mode: CameraControlMode
    public var target: SIMD3<Float> = .zero
    public var orbitRadius: Float = 2.0     // v4.4.0: was 5.0
    public var azimuth: Float = 0.0
    public var elevation: Float = .pi / 6   // 30 degrees
    public var minRadius: Float = 1.0       // v4.4.0: was 0.5
    public var maxRadius: Float = 50.0
    public var sensitivity: Float = 0.005
    public var isAutoRotating: Bool = false
    public var autoRotateSpeed: Float = 0.3
    // firstPerson FOV controls (#1034)
    public var fov: Float = 60.0
    public var minFov: Float = 10.0
    public var maxFov: Float = 120.0
    public var pinchFovSpeed: Float = 0.05
}
```

---

### Entity modifiers (extension on RealityKit.Entity)

Fluent, chainable helpers available on any `Entity`:

```swift
extension Entity {
    public func positioned(at position: SIMD3<Float>) -> Self
    public func scaled(to factor: Float) -> Self
    public func scaled(to scale: SIMD3<Float>) -> Self
    public func rotated(by angle: Float, around axis: SIMD3<Float>) -> Self
    public func named(_ name: String) -> Self
    public func enabled(_ isEnabled: Bool) -> Self
}
```

---

### RerunBridge (iOS only) — stream AR data to Rerun viewer

```swift
public final class RerunBridge: ObservableObject {
    @Published public private(set) var eventCount: Int

    public init(
        host: String = "127.0.0.1",
        port: UInt16 = 9876,
        rateHz: Int = 10          // max frames/sec; 0 = unlimited
    )

    // Connection lifecycle
    public func connect()      // non-blocking; uses NWConnection on background queue
    public func disconnect()
    public func setEnabled(_ enabled: Bool)

    // High-level convenience (honours rate limiter)
    public func logFrame(_ frame: ARFrame)    // logs camera pose + planes + point cloud

    // Low-level per-event loggers
    public func logCameraPose(_ camera: ARCamera, timestampNanos: Int64)
    public func logPlanes(_ planes: [ARPlaneAnchor], timestampNanos: Int64)
    public func logPointCloud(_ cloud: ARPointCloud, timestampNanos: Int64)
    public func logAnchors(_ anchors: [ARAnchor], timestampNanos: Int64)
}
```

Usage with `ARSceneView`:
```swift
@StateObject private var bridge = RerunBridge(host: "127.0.0.1", port: 9876, rateHz: 10)

var body: some View {
    ARSceneView()
        .onFrame { frame, _ in bridge.logFrame(frame) }
        .onAppear { bridge.connect() }
        .onDisappear { bridge.disconnect() }
    Text("Events: \(bridge.eventCount)")
}
```

Threading: all I/O runs on a private `DispatchQueue` via `NWConnection`. `log*` methods
are non-blocking — hand off data from any thread (ARKit delegate queue, main thread).
Backpressure is absorbed by `rateHz`. Wire format: JSON-lines consumed by
`tools/rerun-bridge.py` Python sidecar.

---
