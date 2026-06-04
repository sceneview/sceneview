# SceneView API Quick Reference

## Core Composables

### SceneView -- 3D Viewport

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
    renderer: Renderer = rememberRenderer(engine),
    scene: Scene = rememberScene(engine),
    environment: Environment = rememberEnvironment(environmentLoader, isOpaque = isOpaque),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    cameraNode: CameraNode = rememberCameraNode(engine),
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    cameraManipulator: CameraManipulator? = rememberCameraManipulator(cameraNode.worldPosition),
    viewNodeWindowManager: ViewNode.WindowManager? = null,
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    activity: ComponentActivity? = LocalContext.current as? ComponentActivity,
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
    content: (@Composable SceneScope.() -> Unit)? = null
)
```

**Minimal usage:**

```kotlin
@Composable
fun My3DScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator(),
        mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f }
    ) {
        rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
            ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
        }
    }
}
```

### ARSceneView -- AR Viewport

```kotlin
@Composable
fun ARSceneView(
    modifier: Modifier = Modifier,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    sessionFeatures: Set<Session.Feature> = setOf(),
    sessionCameraConfig: ((Session) -> CameraConfig)? = null,
    sessionConfiguration: ((session: Session, Config) -> Unit)? = null,
    planeRenderer: Boolean = true,
    cameraStream: ARCameraStream? = rememberARCameraStream(materialLoader),
    view: View = rememberARView(engine),
    environment: Environment = rememberAREnvironment(engine),
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    cameraNode: ARCameraNode = rememberARCameraNode(engine),
    onSessionCreated: ((session: Session) -> Unit)? = null,
    onSessionResumed: ((session: Session) -> Unit)? = null,
    onSessionPaused: ((session: Session) -> Unit)? = null,
    onSessionFailed: ((exception: Exception) -> Unit)? = null,
    onSessionUpdated: ((session: Session, frame: Frame) -> Unit)? = null,
    onTrackingFailureChanged: ((trackingFailureReason: TrackingFailureReason?) -> Unit)? = null,
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    content: (@Composable ARSceneScope.() -> Unit)? = null
)
```

**Minimal usage:**

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
        sessionConfiguration = { session, config ->
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        },
        onSessionFailed = { exception -> /* show fallback */ }
    ) {
        // AR content here
    }
}
```

---

## Node Types

### ModelNode -- 3D Model

```kotlin
@Composable fun ModelNode(
    modelInstance: ModelInstance,
    autoAnimate: Boolean = true,
    animationName: String? = null,
    animationLoop: Boolean = true,
    animationSpeed: Float = 1f,
    scaleToUnits: Float? = null,       // uniformly scale to fit this size (meters)
    centerOrigin: Position? = null,    // Position(0,0,0) = center, Position(0,-1,0) = bottom-aligned; composes additively with `position`
    position: Position = Position(x = 0f),  // added on top of the centerOrigin offset
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    isVisible: Boolean = true,
    isEditable: Boolean = false,       // enables pinch-to-scale, drag-to-move
    apply: ModelNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

Key properties in `apply` block: `isShadowCaster`, `isShadowReceiver`, `playAnimation(name)`, `stopAnimation(name)`, `editableScaleRange`, `scaleGestureSensitivity`.

### LightNode -- Light Source

**CRITICAL: `apply` is a named parameter, NOT a trailing lambda.**

```kotlin
@Composable fun LightNode(
    type: LightManager.Type,           // DIRECTIONAL, POINT, SPOT, FOCUSED_SPOT, SUN
    intensity: Float? = null,          // lux (directional/sun) or candela (point/spot)
    direction: Direction? = null,
    position: Position = Position(x = 0f),
    apply: LightManager.Builder.() -> Unit = {},
    nodeApply: LightNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

```kotlin
// Correct usage:
LightNode(
    type = LightManager.Type.SPOT,
    intensity = 50_000f,
    position = Position(2f, 3f, 0f),
    apply = { falloff(5.0f); spotLightCone(0.1f, 0.5f) }
)
```

### CubeNode, SphereNode, CylinderNode, PlaneNode -- Geometry

```kotlin
CubeNode(size: Size = Size(1f), center: Position = ..., materialInstance: MaterialInstance? = null, ...)
SphereNode(radius: Float = 0.5f, center: Position = ..., materialInstance: MaterialInstance? = null, ...)
CylinderNode(radius: Float = 0.5f, height: Float = 2.0f, materialInstance: MaterialInstance? = null, ...)
PlaneNode(size: Size = Size(1f), normal: Direction = ..., uvScale: UvScale = ..., materialInstance: MaterialInstance? = null, ...)
```

All geometry nodes share common parameters: `position`, `rotation`, `scale`, `apply`, `content`.

### ImageNode -- Image on Plane (3 overloads)

```kotlin
ImageNode(bitmap: Bitmap, size: Size? = null, position: Position = ...)
ImageNode(imageFileLocation: String, size: Size? = null, position: Position = ...)
ImageNode(@DrawableRes imageResId: Int, size: Size? = null, position: Position = ...)
```

### TextNode -- 3D Text Label (faces camera)

```kotlin
TextNode(
    text: String,
    fontSize: Float = 48f,
    textColor: Int = Color.WHITE,
    backgroundColor: Int = 0xCC000000.toInt(),
    widthMeters: Float = 0.6f,
    heightMeters: Float = 0.2f,
    position: Position = Position(x = 0f)
)
```

Reactive: text, fontSize, textColor, backgroundColor, position, scale update on recomposition.

### BillboardNode -- Always-Facing-Camera Sprite

```kotlin
BillboardNode(
    bitmap: Bitmap,
    widthMeters: Float? = null,
    heightMeters: Float? = null,
    position: Position = Position(x = 0f)
)
```

### VideoNode -- Video on 3D Plane

```kotlin
// Simple (from asset path):
VideoNode(videoPath: String, autoPlay: Boolean = true, isLooping: Boolean = true, chromaKeyColor: Int? = null, ...)

// Advanced (bring your own MediaPlayer):
VideoNode(player: MediaPlayer, chromaKeyColor: Int? = null, size: Size? = null, ...)
```

### ViewNode -- Compose UI in 3D

Requires `viewNodeWindowManager` on the parent SceneView.

```kotlin
val windowManager = rememberViewNodeManager()
SceneView(viewNodeWindowManager = windowManager) {
    ViewNode(windowManager = windowManager) {
        Card { Text("Hello 3D World!") }
    }
}
```

### LineNode, PathNode -- Lines and Curves

```kotlin
LineNode(start: Position, end: Position, materialInstance: MaterialInstance? = null)
PathNode(points: List<Position>, closed: Boolean = false, materialInstance: MaterialInstance? = null)
```

### ShapeNode -- 2D Polygon in 3D

```kotlin
ShapeNode(polygonPath: List<Position2>, polygonHoles: List<Int> = listOf(), normal: Direction = ..., color: Color? = null, ...)
```

### PhysicsNode -- Rigid Body Physics

```kotlin
PhysicsNode(node: Node, mass: Float = 1f, restitution: Float = 0.6f, linearVelocity: Position = ..., floorY: Float = 0f, radius: Float = 0f)
```

Does NOT add the node to the scene. The node must already exist.

### DynamicSkyNode -- Time-of-Day Sun

```kotlin
DynamicSkyNode(timeOfDay: Float = 12f, turbidity: Float = 2f, sunIntensity: Float = 110_000f)
```

### ReflectionProbeNode -- Local IBL Override

```kotlin
ReflectionProbeNode(filamentScene: FilamentScene, environment: Environment, position: Position = ..., radius: Float = 0f, ...)
```

### MeshNode -- Custom Geometry

```kotlin
MeshNode(primitiveType: PrimitiveType, vertexBuffer: VertexBuffer, indexBuffer: IndexBuffer, materialInstance: MaterialInstance? = null)
```

### Node -- Empty Pivot/Group

```kotlin
Node(position: Position, rotation: Rotation, scale: Scale, isVisible: Boolean = true, isEditable: Boolean = false,
     content: (@Composable NodeScope.() -> Unit)? = null)
```

---

## AR-Specific Nodes (ARSceneScope)

### AnchorNode -- Pin to Real World

```kotlin
AnchorNode(anchor: Anchor, visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
           onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
           content: (@Composable NodeScope.() -> Unit)? = null)
```

### HitResultNode -- Surface Cursor

```kotlin
// Screen-coordinate hit test (most common):
HitResultNode(xPx: Float, yPx: Float, planeTypes: Set<Plane.Type> = ...,
              content: (@Composable NodeScope.() -> Unit)? = null)

// Custom hit test:
HitResultNode(hitTest: HitResultNode.(Frame) -> HitResult?,
              content: (@Composable NodeScope.() -> Unit)? = null)
```

### AugmentedImageNode -- Image Tracking

```kotlin
AugmentedImageNode(augmentedImage: AugmentedImage, applyImageScale: Boolean = false,
                   content: (@Composable NodeScope.() -> Unit)? = null)
```

### AugmentedFaceNode -- Face Mesh

```kotlin
AugmentedFaceNode(augmentedFace: AugmentedFace, meshMaterialInstance: MaterialInstance? = null,
                  content: (@Composable NodeScope.() -> Unit)? = null)
```

### CloudAnchorNode -- Cross-Device Anchors

```kotlin
CloudAnchorNode(anchor: Anchor, cloudAnchorId: String? = null,
                onHosted: ((cloudAnchorId: String?, state: Anchor.CloudAnchorState) -> Unit)? = null,
                content: (@Composable NodeScope.() -> Unit)? = null)
```

### PoseNode, TrackableNode

```kotlin
PoseNode(pose: Pose = Pose.IDENTITY, content: (@Composable NodeScope.() -> Unit)? = null)
TrackableNode(trackable: Trackable, content: (@Composable NodeScope.() -> Unit)? = null)
```

**Important:** AR composables can only be declared at the ARSceneView root level, not inside other nodes' content blocks.

---

## Resource Loading

### rememberModelInstance (async, composable)

```kotlin
// From local asset
val model: ModelInstance? = rememberModelInstance(modelLoader, "models/helmet.glb")

// From URL (auto-detects http/https)
val model: ModelInstance? = rememberModelInstance(modelLoader, "https://example.com/model.glb")
```

Returns null while loading. **Always handle the null case.**

### ModelLoader (imperative)

```kotlin
// Synchronous -- MUST call on main thread
modelLoader.createModelInstance("models/file.glb"): ModelInstance
modelLoader.createModel("models/file.glb", releaseSourceData = true): Model

// Async -- safe from any thread
suspend fun loadModelInstance(fileLocation: String): ModelInstance?
fun loadModelInstanceAsync(fileLocation: String, onResult: (ModelInstance?) -> Unit): Job
```

### MaterialLoader

```kotlin
materialLoader.createColorInstance(
    color: Color,
    metallic: Float = 0.0f,    // 0 = dielectric, 1 = metal
    roughness: Float = 0.4f,   // 0 = mirror, 1 = matte
    reflectance: Float = 0.5f
): MaterialInstance
```

There is no `rememberMaterialInstance` in the published SDK (only a `samples/common` helper). Create materials inside `remember`:

```kotlin
val mat = remember(materialLoader) {
    materialLoader.createColorInstance(Color.Red, metallic = 0f, roughness = 0.6f)
}
```

### EnvironmentLoader

```kotlin
environmentLoader.createHDREnvironment("environments/sky_2k.hdr"): Environment?
environmentLoader.createKTXEnvironment("environments/studio.ktx"): Environment
environmentLoader.createEnvironment(indirectLight, skybox): Environment
```

---

## Remember Helpers Reference

| Helper | Returns | Purpose |
|--------|---------|---------|
| `rememberEngine()` | `Engine` | Root Filament object |
| `rememberModelLoader(engine)` | `ModelLoader` | Loads glTF/GLB models |
| `rememberMaterialLoader(engine)` | `MaterialLoader` | Creates material instances |
| `rememberEnvironmentLoader(engine)` | `EnvironmentLoader` | Loads HDR/KTX environments |
| `rememberModelInstance(modelLoader, path)` | `ModelInstance?` | Async model load (null while loading) |
| `rememberEnvironment(environmentLoader)` | `Environment` | IBL + skybox environment |
| `rememberCameraNode(engine) { ... }` | `CameraNode` | Custom camera |
| `rememberMainLightNode(engine) { ... }` | `LightNode` | Primary directional light |
| `rememberCameraManipulator(...)` | `CameraManipulator?` | Orbit/pan/zoom controller |
| `rememberOnGestureListener(...)` | `OnGestureListener` | Gesture callbacks |
| `rememberViewNodeManager()` | `ViewNode.WindowManager` | Required for ViewNode |
| `rememberView(engine)` | `View` | Filament view |
| `rememberARView(engine)` | `View` | AR-tuned view (linear tone mapper) |
| `rememberARCameraNode(engine)` | `ARCameraNode` | AR camera (updated by ARCore) |
| `rememberARCameraStream(materialLoader)` | `ARCameraStream` | Camera feed texture |
| `rememberAREnvironment(engine)` | `Environment` | No-skybox environment for AR |
| `rememberMediaPlayer(context, path)` | `MediaPlayer?` | Auto-lifecycle video player |

---

## Camera Control

```kotlin
// Orbit / pan / zoom (default)
SceneView(cameraManipulator = rememberCameraManipulator(
    orbitHomePosition = Position(x = 0f, y = 2f, z = 4f),
    targetPosition = Position(x = 0f, y = 0f, z = 0f)
))

// Custom camera position
SceneView(cameraNode = rememberCameraNode(engine) {
    position = Position(x = 0f, y = 2f, z = 5f)
    lookAt(Position(0f, 0f, 0f))
})

// Main light shortcut
SceneView(mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f })
```

---

## Gesture Handling

```kotlin
SceneView(
    onGestureListener = rememberOnGestureListener(
        onDown = { event, node -> },
        onSingleTapUp = { event, node -> },
        onSingleTapConfirmed = { event, node -> },
        onDoubleTap = { event, node -> },
        onLongPress = { event, node -> },
        onMove = { detector, node -> },
        onMoveBegin = { detector, node -> },
        onMoveEnd = { detector, node -> },
        onRotate = { detector, node -> },
        onRotateBegin = { detector, node -> },
        onRotateEnd = { detector, node -> },
        onScale = { detector, node -> },
        onScaleBegin = { detector, node -> },
        onScaleEnd = { detector, node -> },
        onFling = { e1, e2, node, velocity -> },
        onScroll = { e1, e2, node, distance -> }
    ),
    onTouchEvent = { event, hitResult -> false }
)
```

**Editable nodes:** Set `isEditable = true` to enable built-in pinch-to-scale and drag-to-move. Fine-tune with `isPositionEditable`, `isRotationEditable`, `isScaleEditable`, `editableScaleRange`.

---

## Node Properties (Common to All Nodes)

```kotlin
// Transform
node.position = Position(x = 1f, y = 0f, z = -2f)  // meters
node.rotation = Rotation(x = 0f, y = 45f, z = 0f)   // degrees
node.scale = Scale(x = 1f, y = 1f, z = 1f)
node.worldPosition / node.worldRotation / node.worldScale  // world-space

// Visibility
node.isVisible = true

// Interaction
node.isTouchable = true
node.isEditable = true
node.isHittable = true

// Smooth transform
node.isSmoothTransformEnabled = false
node.smoothTransformSpeed = 5.0f

// Orientation
node.lookAt(targetWorldPosition, upDirection)
node.lookTowards(lookDirection, upDirection)
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
```

---

## Threading Rules (Critical)

- Filament JNI calls must run on the **main thread**
- `rememberModelInstance` is safe -- reads bytes on IO, creates on Main
- `modelLoader.createModel*` (synchronous) -- **main thread only**
- `materialLoader.createColorInstance(...)` -- **main thread only**; safe inside `remember { }` in SceneScope
- Use `modelLoader.loadModelInstanceAsync(...)` for imperative async code
- Inside `SceneView { }` composable scope, you are on the main thread

---

## Surface Types

```kotlin
SceneView(surfaceType = SurfaceType.Surface)                          // SurfaceView, best perf (default)
SceneView(surfaceType = SurfaceType.TextureSurface, isOpaque = false) // TextureView, supports alpha
```
