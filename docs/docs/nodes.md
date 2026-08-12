# SceneView nodes — complete reference

A scannable, AI-first reference for every node type exposed by `SceneView` and `ARSceneView` on Android. Each section answers four questions:

1. **What is it?** — one line
2. **Signature** — the composable parameters (source of truth: `SceneScope.kt` / `ARSceneScope.kt`)
3. **Copy-paste example** — a complete, runnable snippet
4. **Gotchas** — lifecycle, recomposition, threading traps

All examples assume you are inside a `SceneView { … }` or `ARSceneView { … }` block (for AR nodes). Import the `io.github.sceneview.*` / `io.github.sceneview.ar.*` packages as needed.

Artifact versions: `io.github.sceneview:sceneview:4.29.0` and `io.github.sceneview:arsceneview:4.29.0`.

---

## Table of contents

**Scene composables**

- [SceneView](#sceneview) — the entry-point composable
- [ARSceneView](#arsceneview) — the AR entry point

**Common lifecycle / helpers**

- [Threading rule — Filament JNI is main-thread only](#threading-rule)
- [Loading models: `rememberModelInstance`](#remembermodelinstance)
- [Creating materials: `materialLoader.*`](#materialloader)
- [Recomposition model: component-keyed transforms (`DisposableEffect`) + diffed props (`SideEffect`)](#recomposition-model)

**Standard nodes**

- [ModelNode](#modelnode) — glTF/GLB models with animations
- [LightNode](#lightnode) — directional, point, spot, sun
- [CameraNode](#cameranode) — secondary cameras (PiP, CCTV, etc.)
- [ViewNode](#viewnode) — Compose UI on a 3D plane

**Procedural geometry**

- [CubeNode](#cubenode) — box
- [SphereNode](#spherenode) — sphere
- [CylinderNode](#cylindernode) — cylinder
- [PlaneNode](#planenode) — quad / plane
- [LineNode](#linenode) — line segment
- [PathNode](#pathnode) — polyline
- [MeshNode](#meshnode) — custom triangle mesh

**Content nodes**

- [TextNode](#textnode) — SDF text
- [ImageNode](#imagenode) — textured quad
- [VideoNode](#videonode) — video texture
- [BillboardNode](#billboardnode) — always-face-camera
- [ReflectionProbeNode](#reflectionprobenode) — local reflections

**AR-only nodes**

- [AnchorNode](#anchornode) — ARCore anchor
- [PoseNode](#posenode) — raw `Pose`
- [HitResultNode](#hitresultnode) — follow hit test
- [AugmentedImageNode](#augmentedimagenode) — track a detected image
- [AugmentedFaceNode](#augmentedfacenode) — face tracking
- [CloudAnchorNode](#cloudanchornode) — host/resolve cloud anchors
- [StreetscapeGeometryNode](#streetscapegeometrynode) — Geospatial building meshes

**Composition & state**

- [Nesting and coordinate spaces](#nesting-and-coordinate-spaces)
- [Changing node parameters reactively](#reactive-params)
- [Destroying nodes — it is automatic](#auto-destroy)
- [Editing imperatively with `apply { … }`](#imperative-apply)
- [Common mistakes](#common-mistakes)

---

## SceneView

`SceneView` is the Composable that hosts a Filament scene. All other node composables are declared inside its trailing content block. (`Scene { }` is the pre-v3.6 name and still works as a `@Deprecated` alias.)

```kotlin
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
val materialLoader = rememberMaterialLoader(engine)
val environmentLoader = rememberEnvironmentLoader(engine)

SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    materialLoader = materialLoader,
    environmentLoader = environmentLoader,
    viewNodeWindowManager = rememberViewNodeManager(), // only needed if you use ViewNode
    cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 0f, 4f)
    },
    mainLightNode = rememberMainLightNode(engine),
    environment = rememberEnvironment(environmentLoader) {
        environmentLoader.createHDREnvironment("environments/studio_small.hdr")
            ?: createEnvironment(environmentLoader)
    }
) {
    // ─ nodes go here ─
    rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
        ModelNode(modelInstance = instance, scaleToUnits = 1f)
    }
}
```

**Gotchas**

- All heavy resources (`Engine`, `ModelLoader`, `MaterialLoader`) are **expensive** and must be `remember`'d across recompositions. Always use the `rememberXxx` helpers.
- `cameraNode` needs a start position — if you forget it, the camera is at the origin looking straight down and you see nothing.
- `mainLightNode` defaults to a sensible directional light; if you pass `null`, you'll only see anything with unlit materials.
- `viewNodeWindowManager` is **required** if and only if you use [`ViewNode`](#viewnode). Without it, `ViewNode` renders a black rectangle.

---

## ARSceneView

Same idea as `SceneView`, but backed by ARCore. Adds camera feed, trackables, session lifecycle, and the AR-only node composables. (`ARScene { }` is the pre-v3.6 name and still works as a `@Deprecated` alias.)

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    viewNodeWindowManager = rememberViewNodeManager(),
    sessionConfiguration = { session, config ->
        config.depthMode =
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                Config.DepthMode.AUTOMATIC
            else
                Config.DepthMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    },
    onSessionUpdated = { session, frame ->
        // fires every frame with the latest Frame
    }
) {
    HitResultNode(xPx = centerX, yPx = centerY) {
        CubeNode(size = Float3(0.1f))
    }
}
```

---

## Threading rule

**Filament JNI calls must run on the Android main thread.** This includes:

- `modelLoader.createModel*`
- `materialLoader.create*`
- anything that touches `Engine`, `Scene`, `View`, `Renderer`, or a `*Node`

Violating this rule causes silent crashes (SIGSEGV in `libfilament.so`) or opaque "invalid engine" errors.

### Safe patterns

- **In composables** → `rememberModelInstance(...)` / `rememberMaterialLoader(...)`. These are main-thread by construction.
- **In imperative code** → `modelLoader.loadModelInstanceAsync(...) { instance -> /* callback on main */ }`.
- **In a coroutine** → wrap with `withContext(Dispatchers.Main) { ... }` — don't call Filament from `Dispatchers.IO`.

---

## rememberModelInstance

```kotlin
val instance = rememberModelInstance(modelLoader, "models/helmet.glb")
instance?.let { ModelNode(modelInstance = it, scaleToUnits = 1f) }
```

- Returns `null` while loading — **always handle the null case**.
- Triggers recomposition when ready.
- Automatically disposed when the composable leaves the tree.
- File path is relative to `src/main/assets/`.
- For remote URLs, use `rememberModelInstance(modelLoader, url = "https://…")`.

---

## materialLoader

```kotlin
val redMaterial = remember(materialLoader) {
    materialLoader.createColorInstance(Color(1f, 0f, 0f, 1f))
}
CubeNode(materialInstance = redMaterial)
```

Common factory methods on `MaterialLoader`:

| Method | Purpose |
|---|---|
| `createColorInstance(color, metallic, roughness, reflectance)` | Solid PBR color |
| `createColorInstance(color, unlit = true)` | Unlit flat color (no lighting needed) |
| `createTextureInstance(texture, ...)` | Textured PBR material |
| `createImageInstance(texture, sampler)` | Unlit image on a quad |
| `createVideoInstance(texture, chromaKeyColor?)` | Video texture, optional chroma key |

**Gotcha:** material instances are owned by the `MaterialLoader` and destroyed when the composable tree disposes. Don't cache them across `Scene` recompositions — use `remember(materialLoader)`.

---

## Recomposition model

All node composables share the same skeleton — a `remember`'d node impl, effects that
push declarative params to that runtime node, and `NodeLifecycle` to attach/dispose.

**Every node composable** pushes each transform component (`position` / `rotation` /
`scale`) through a **component-keyed `DisposableEffect`**, so a component is re-applied
to the runtime node **only when its declared value actually changes**:

```kotlin
val node = remember(engine, /* stable id */) {
    // Constructor — runs ONCE per unique id. Heavy work goes here.
    NodeImpl(engine = engine, /* initial params */).apply { /* apply block */ }
}
// Keyed on the scalar components: the body re-runs ONLY when a declared component changes.
DisposableEffect(node, position.x, position.y, position.z) {
    node.position = position; onDispose {}
}
DisposableEffect(node, rotation.x, rotation.y, rotation.z) {
    node.rotation = rotation; onDispose {}
}
// … scale, isVisible, isEditable keyed the same way
NodeLifecycle(node, content) // attaches to scene + disposes on leave
```

Because the effect body runs only when a declared component changes, a **bare** recomposition
(one where `rotation` never changed) never re-applies the declared value — so a rotation a
gesture (`NodeGestureDelegate`) or a frame-loop driver (physics, animation, camera-follow) wrote
directly on the runtime node **survives** the recomposition. Reverting this back to an unkeyed
`SideEffect` reintroduces [#2639](https://github.com/sceneview/sceneview/issues/2639) (a
gesture-rotated model snapping back to its declared rotation) — the geometry/media nodes
(`CubeNode`, `CylinderNode`, `ConeNode`, `TorusNode`, `CapsuleNode`, `PlaneNode`, `ImageNode`,
`BillboardNode`, `TextNode`, `VideoNode`, `LineNode`, `PathNode`, `ShapeNode`, plus `LightNode`'s
position) carried exactly that unkeyed idiom until
[#2653](https://github.com/sceneview/sceneview/issues/2653) migrated them. The keys are the
individual scalar components, not the `Float3` wrapper.

**Non-transform parameters** still flow through an unkeyed `SideEffect`, which is correct for
them — geometry and material changes are **diffed** against the previous value before doing any
work, and text/bitmap props are cheap idempotent setters:

```kotlin
SideEffect {
    // Runs every recomposition, but only acts on change: geometry/material diffing.
    if (listOf(size, center) != prevGeometry.value) {
        node.updateGeometry(center = center, size = size)
        prevGeometry.value = listOf(size, center)
    }
    if (materialInstance != null && materialInstance != prevMaterial.value) {
        node.setMaterialInstanceAt(0, materialInstance)
        prevMaterial.value = materialInstance
    }
}
```

Key consequences:

- **A declared `position` / `rotation` / `scale` is applied only when it actually changes** — on
  every node composable, a runtime transform a gesture or frame driver wrote is not clobbered by
  an unrelated recomposition (#2639, #2653).
- **Changing geometry parameters (`size`, `radius`, `stacks`, …) triggers `updateGeometry()`** under the hood — a O(vertex count) rebuild. Cheap for small meshes, not free.
- **Changing the `modelInstance` reference rebuilds the node** — use the same instance when toggling props.
- **Never call `node.destroy()` manually** — let the composable's `NodeLifecycle` do it.

---

## ModelNode

glTF/GLB models with optional animation playback.

### Signature

```kotlin
ModelNode(
    modelInstance: ModelInstance,
    autoAnimate: Boolean = true,
    animationName: String? = null,
    animationLoop: Boolean = true,
    animationSpeed: Float = 1f,
    scaleToUnits: Float? = null,
    centerOrigin: Position? = null,
    position: Position = Position(0f),
    rotation: Rotation = Rotation(0f),
    scale: Scale = Scale(1f),
    isVisible: Boolean = true,
    isEditable: Boolean = false,
    apply: ModelNodeImpl.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### Example

```kotlin
val instance = rememberModelInstance(modelLoader, "models/helmet.glb")
var walking by remember { mutableStateOf(false) }

instance?.let {
    ModelNode(
        modelInstance = it,
        // Fit the model into a 1m cube regardless of its original size
        scaleToUnits = 1f,
        // Pivot at the bottom (feet) instead of geometric center. centerOrigin composes
        // additively with position below — the model is bottom-aligned AND placed at z = -2.
        centerOrigin = Position(x = 0f, y = -1f, z = 0f),
        // Reactive animation switch
        autoAnimate = false,
        animationName = if (walking) "Walk" else "Idle",
        animationLoop = true,
        animationSpeed = 1f,
        position = Position(0f, 0f, -2f)
    )
}
```

### Gotchas

- **`scaleToUnits` and `scale` are mutually exclusive.** When `scaleToUnits != null`, the `scale` parameter is **ignored** — the model's scale is computed from its bounding box.
- **Switching animations:** provide `animationName` as reactive state, set `autoAnimate = false`. The previous animation is stopped automatically when `animationName` changes.
- **`centerOrigin` convention:** the bounding-box point selected by the normalized origin (`-1..1` per axis, `0` = AABB center) lands on the node origin, whatever the asset's authored pivot. `null` keeps the model's authored pivot, `Position(0,0,0)` centers the bounding box on the node origin, `Position(0,-1,0)` bottom-aligns (the model sits on the origin). Composes **additively** with `position` — the alignment offset (`-(center + origin * halfExtent) * scale`) is added to `position`, so a non-zero `centerOrigin` takes effect even when `position` keeps its default, and the two can be combined (bottom-align *and* place at a point). Applied once on creation (not reactive, like `scaleToUnits`).
- **`autoAnimate = true` ignores `animationName`** — the code explicitly ORs the two to avoid double-playing.

---

## LightNode

Directional, point, spot, or sun lights. At least one light is required for PBR materials to be visible.

### Signature

```kotlin
LightNode(
    type: LightManager.Type,
    intensity: Float? = null,
    direction: Direction? = null,
    position: Position = Position(0f),
    apply: LightManager.Builder.() -> Unit = {},
    nodeApply: LightNodeImpl.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### Examples

**Simple directional fill light:**

```kotlin
LightNode(
    type = LightManager.Type.DIRECTIONAL,
    intensity = 100_000f,          // lux for directional/sun
    direction = Direction(0f, -1f, -1f)
)
```

**Spot light with shadow falloff:**

```kotlin
LightNode(
    type = LightManager.Type.SPOT,
    position = Position(0f, 3f, 0f),
    direction = Direction(0f, -1f, 0f),
    apply = {
        intensity(50_000f)         // candela for point/spot
        color(1f, 0.95f, 0.9f)
        falloff(10f)
        spotLightCone(innerAngle = 0.1f, outerAngle = 0.5f)
        castShadows(true)
    }
)
```

### Gotchas

- **`apply` is a named parameter, NOT a trailing lambda.** `LightNode { … }` will not compile — the trailing lambda is `content` (child nodes), not `apply`.
- **Intensity units differ by type:** `DIRECTIONAL` / `SUN` are lux, `POINT` / `SPOT` are candela. A 100 000 candela spot will blow out your scene; a 100 000 lux sun is correct daylight.
- **`direction` only matters for directional-style lights.** Ignored by `POINT`.
- **No visible light = black PBR models.** Either add a `LightNode`, an indirect `Environment`, or switch your material to `createColorInstance(..., unlit = true)`.

---

## CameraNode

Secondary cameras inside the scene (e.g. a picture-in-picture CCTV view, a mirror, a portal). The main camera is configured at `SceneView` level via `rememberCameraNode`.

```kotlin
CameraNode(
    position = Position(0f, 2f, 0f),
    rotation = Rotation(x = -90f),
    apply = {
        projection = Projection.Perspective(fovDegrees = 60f)
    }
)
```

---

## ViewNode

Renders a Jetpack Compose UI onto a flat plane in 3D space. Great for in-world labels, info cards, and HUDs.

!!! warning "The rendered UI is not interactive"

    A `ViewNode` draws its Compose content into a texture, and the hosting window is
    `FLAG_NOT_TOUCHABLE` — a `Button` placed inside renders, but its `onClick` never
    fires (no ripple, no press state, no inner scrolling). To react to a tap, hit-test
    the node instead: `onSingleTapUp = { _, node -> if (node is ViewNode) … }`.
    Real touch forwarding is tracked by [#2845](https://github.com/sceneview/sceneview/issues/2845).

### Signature

```kotlin
ViewNode(
    windowManager: ViewNode.WindowManager,
    unlit: Boolean = false,
    invertFrontFaceWinding: Boolean = false,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    apply: ViewNodeImpl.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
    viewContent: @Composable () -> Unit
)
```

### Example

```kotlin
val windowManager = rememberViewNodeManager()

SceneView(
    modifier = Modifier.fillMaxSize(),
    viewNodeWindowManager = windowManager, // ⚠️ required
    // …
) {
    ViewNode(
        windowManager = windowManager,
        unlit = true,
        position = Position(0f, 1.5f, -2f)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Text(
                text = "Hello from 3D!",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}
```

### Gotchas

- **You MUST pass `viewNodeWindowManager = rememberViewNodeManager()` to `SceneView` / `ARSceneView`.** Without it, the off-screen window is never attached → `Layout.onLayout` never fires → surface stays at 0×0 → Filament renders a black rectangle. Fixed in v4.0.0 by wiring the manager into the lifecycle observer, but the parameter is still required.
- **Use `unlit = true` for readable text.** Under PBR lighting, Compose UI gets shaded by scene lights and may look dim or color-shifted.
- **ViewNode is relatively expensive.** Each instance allocates a `SurfaceTexture`, a `FrameLayout`, and a ComposeView. Reuse or pool them if you need many.
- **Don't put touch-heavy widgets inside.** The off-screen window uses `FLAG_NOT_TOUCHABLE` — interactive content works, but standard gesture plumbing does not route through the 3D scene the way you'd expect.

---

## CubeNode

A box with independent width × height × depth.

### Signature

```kotlin
CubeNode(
    size: Size = Cube.DEFAULT_SIZE,
    center: Position = Cube.DEFAULT_CENTER,
    materialInstance: MaterialInstance? = null,
    position: Position = Position(0f),
    rotation: Rotation = Rotation(0f),
    scale: Scale = Scale(1f),
    apply: CubeNodeImpl.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null
)
```

### Example — resize from state

```kotlin
var height by remember { mutableFloatStateOf(1f) }
val redMaterial = remember(materialLoader) {
    materialLoader.createColorInstance(Color(1f, 0f, 0f, 1f))
}

CubeNode(
    size = Size(x = 1f, y = height, z = 1f),
    materialInstance = redMaterial,
    position = Position(0f, 0f, -3f)
)
```

Changing `height` triggers `updateGeometry()` — the mesh is rebuilt in-place, no allocation churn in the scene graph.

### Gotchas

- The **geometry rebuild** (on `size` / `center` changes) runs on the main thread. Smooth at 60 FPS for small cubes; don't drive it from a physics loop for hundreds of nodes.
- `materialInstance = null` uses a default opaque grey — good for quick prototyping, not for production.
- Procedural geometry nodes **do not share material instances by default** — creating N cubes with `createColorInstance(red)` makes N material instances. `remember` the instance yourself and pass it to all nodes.

---

## SphereNode

```kotlin
SphereNode(
    radius: Float = Sphere.DEFAULT_RADIUS,
    center: Position = Sphere.DEFAULT_CENTER,
    stacks: Int = Sphere.DEFAULT_STACKS,   // horizontal subdivisions
    slices: Int = Sphere.DEFAULT_SLICES,   // vertical subdivisions
    materialInstance: MaterialInstance? = null,
    // …transform params…
)
```

Low-poly: `stacks = 8, slices = 8`. Smooth: `stacks = 32, slices = 32`. Changes to `stacks` / `slices` rebuild the mesh.

---

## CylinderNode

```kotlin
CylinderNode(
    radius: Float,
    height: Float,
    center: Position = Cylinder.DEFAULT_CENTER,
    sideCount: Int = 24,                   // polygon resolution
    materialInstance: MaterialInstance? = null,
    // …transform params…
)
```

---

## ConeNode

A cone pointing upward along the Y-axis with a circular base cap.

```kotlin
ConeNode(
    radius: Float = 1.0f,                 // base radius
    height: Float = 2.0f,
    center: Position = Position(0f),
    sideCount: Int = 24,                   // polygon resolution
    materialInstance: MaterialInstance? = null,
    // …transform params…
)
```

Useful for arrows, indicators, or procedural trees.

---

## TorusNode

A donut (torus) shape defined by a major radius (ring centre) and minor radius (tube thickness).

```kotlin
TorusNode(
    majorRadius: Float = 1.0f,             // centre-to-tube distance
    minorRadius: Float = 0.3f,             // tube thickness
    center: Position = Position(0f),
    majorSegments: Int = 32,               // ring resolution
    minorSegments: Int = 16,               // tube cross-section resolution
    materialInstance: MaterialInstance? = null,
    // …transform params…
)
```

---

## CapsuleNode

A cylinder with hemispherical caps — the canonical physics collision shape.
Total height = `height + 2 * radius`.

```kotlin
CapsuleNode(
    radius: Float = 0.5f,                 // hemisphere + tube radius
    height: Float = 2.0f,                 // cylinder section only
    center: Position = Position(0f),
    capStacks: Int = 8,                    // hemisphere subdivision
    sideSlices: Int = 24,                  // circumference resolution
    materialInstance: MaterialInstance? = null,
    // …transform params…
)
```

---

## PlaneNode

A flat quad. Useful as a ground plane, a billboard surface, or a drop shadow.

```kotlin
PlaneNode(
    size: Size = Size(x = 1f, y = 1f),
    center: Position = Position(0f),
    normal: Direction = Direction(y = 1f), // face direction
    materialInstance: MaterialInstance? = null,
    // …transform params…
)
```

`normal = Direction(0f, 1f, 0f)` → horizontal (ground). `Direction(0f, 0f, 1f)` → vertical (wall).

---

## LineNode

A single line segment between two 3D points.

```kotlin
LineNode(
    start = Position(0f, 0f, 0f),
    end   = Position(1f, 0f, 0f),
    materialInstance = remember(materialLoader) {
        materialLoader.createColorInstance(Color.Red, unlit = true)
    }
)
```

Use an unlit material — lines have no real surface normals, so PBR shading renders them black.

---

## PathNode

A polyline defined by a list of points. Closed by default if `points.first() == points.last()`.

```kotlin
PathNode(
    points = listOf(
        Position(0f, 0f, 0f),
        Position(1f, 0f, 0f),
        Position(1f, 1f, 0f),
        Position(0f, 1f, 0f),
    ),
    materialInstance = /* unlit line material */
)
```

---

## MeshNode

Arbitrary triangle mesh from raw vertex / index buffers. Use this when none of the procedural helpers fit — e.g. loading a `MeshDescriptor` you built yourself, or generating marching-cubes output.

```kotlin
MeshNode(
    vertices = vertexFloatArray,           // [x,y,z, x,y,z, …]
    normals = normalFloatArray,            // [nx,ny,nz, …] (optional — auto-computed if null)
    uvs = uvFloatArray,                    // [u,v, u,v, …] (optional)
    indices = indexIntArray,               // triangle indices
    materialInstance = myMaterial
)
```

**When to use MeshNode vs CubeNode / SphereNode:**

- Static primitives → use the dedicated procedural nodes. They update efficiently when params change.
- Procedural shapes you generate at runtime → `MeshNode` + your own buffers.
- Imported GLTF content → **never** `MeshNode`. Use `ModelNode` with `rememberModelInstance`, which handles skinning, animations, PBR materials, and texture loading for you.

---

## TextNode

SDF-based 3D text. Crisp at any distance, scales cleanly.

```kotlin
TextNode(
    text = "Hello, SceneView!",
    size = 0.3f,                           // character height in meters
    position = Position(0f, 1.5f, -2f),
    materialInstance = remember(materialLoader) {
        materialLoader.createColorInstance(Color.White, unlit = true)
    }
)
```

For reactive Compose-style text, prefer [ViewNode](#viewnode) with a `Text()` inside — it supports typography, rich formatting, layout.

---

## ImageNode

A textured quad. Three overloads exist — bitmap, URL, or already-loaded `Texture`.

```kotlin
ImageNode(
    bitmap = myBitmap,
    size = Size(x = 1f, y = 0.5f),
    position = Position(0f, 1f, -2f)
)
```

---

## VideoNode

Plays a `MediaPlayer` video onto a 3D quad. Supports optional chroma keying (green-screen removal).

```kotlin
val mediaPlayer = remember {
    MediaPlayer.create(context, R.raw.my_video).apply { isLooping = true }
}
DisposableEffect(mediaPlayer) {
    mediaPlayer.start()
    onDispose { mediaPlayer.release() }
}

VideoNode(
    player = mediaPlayer,
    size = Size(x = 1.6f, y = 0.9f),
    chromaKeyColor = android.graphics.Color.GREEN, // optional green-screen key
    position = Position(0f, 1f, -2f)
)
```

---

## BillboardNode

A wrapper that makes its children always face the camera. Commonly used for in-world labels.

```kotlin
BillboardNode(position = Position(0f, 2f, 0f)) {
    TextNode(text = "Tap me", size = 0.15f)
}
```

---

## ReflectionProbeNode

A local reflection capture — adds spherical harmonics / cubemap reflections for the area around the node.

```kotlin
ReflectionProbeNode(
    position = Position(0f, 1f, 0f),
    extent = Size(x = 5f, y = 5f, z = 5f)
)
```

---

## AnchorNode

Follows a fixed ARCore `Anchor`. Once placed, the node stays pinned to that real-world spot across frames, plane updates, and loop closures.

### Example — tap a plane to drop a model

```kotlin
var anchors by remember { mutableStateOf(listOf<Anchor>()) }
val drone = rememberModelInstance(modelLoader, "models/drone.glb")

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { motionEvent, node ->
            val hit = sessionRef?.frame?.hitTest(motionEvent)?.firstOrNull {
                val tr = it.trackable
                tr is Plane && tr.isPoseInPolygon(it.hitPose)
            }
            hit?.createAnchor()?.let { newAnchor ->
                anchors = anchors + newAnchor
            }
        }
    ),
    sessionConfiguration = { _, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
    }
) {
    anchors.forEach { anchor ->
        AnchorNode(anchor = anchor) {
            drone?.let { ModelNode(modelInstance = it, scaleToUnits = 0.3f) }
        }
    }
}
```

### Recomposition & AR state — the correct pattern

AR composables are driven by `Frame` updates that fire **60 times per second**. Dumping that into `mutableStateOf` naively would recompose the whole scene 60 times/s.

**Rules:**

1. **Store anchors / trackables in a `mutableStateListOf<Anchor>` or a `MutableState<Set<Anchor>>`,** not individual fields. Append only when a new anchor is created (tap event), not every frame.
2. **Let `AnchorNode.updateAnchorPose = true` (the default)** handle per-frame pose updates — the transform flows through Filament's `TransformManager` without triggering Compose recomposition.
3. **Avoid reading `frame.trackables` inside a composable body.** Read them inside `onSessionUpdated` or `onFrame` callbacks and only update state when something genuinely changes.

```kotlin
// ✅ Good — only appends, rare recompositions
var anchors by remember { mutableStateOf(listOf<Anchor>()) }

// ❌ Bad — triggers recomposition on every frame
var frame by remember { mutableStateOf<Frame?>(null) }
onSessionUpdated = { _, f -> frame = f }
```

---

## PoseNode

Like `AnchorNode` but follows a raw `Pose` instead of an ARCore-persisted anchor. Cheaper (no anchor allocation), but the pose is **not drift-corrected** across loop closures.

```kotlin
PoseNode(
    pose = hitResult.hitPose,
    onPoseChanged = { newPose -> /* … */ }
) {
    CubeNode(size = Float3(0.05f))
}
```

Use for ephemeral indicators (hit test cursor, preview) — upgrade to `AnchorNode` only when the user commits.

---

## HitResultNode

Raycasts each frame at the given view coordinates and moves to the intersection. Perfect for "placement cursors" in the center of the screen.

```kotlin
HitResultNode(
    xPx = viewWidth / 2f,
    yPx = viewHeight / 2f,
    planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
    point = false,
    depthPoint = true
) {
    CubeNode(size = Float3(0.05f))
}
```

### Custom hit test

```kotlin
HitResultNode(
    hitTest = { frame ->
        frame.hitTest(centerX, centerY).firstOrNull {
            (it.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
    }
)
```

---

## AugmentedImageNode

Tracks a detected `AugmentedImage` from ARCore's image database.

```kotlin
var detected by remember { mutableStateOf<List<AugmentedImage>>(emptyList()) }

ARSceneView(
    sessionConfiguration = { session, config ->
        config.augmentedImageDatabase = AugmentedImageDatabase(session).apply {
            addImage("cover", coverBitmap)
        }
    },
    onSessionUpdated = { _, frame ->
        val imgs = frame.getUpdatedTrackables(AugmentedImage::class.java)
            .filter { it.trackingState == TrackingState.TRACKING }
            .toList()
        if (imgs != detected) detected = imgs
    }
) {
    detected.forEach { image ->
        AugmentedImageNode(
            augmentedImage = image,
            applyImageScale = true          // scale to match the real image's physical size
        ) {
            drone?.let { ModelNode(modelInstance = it, scaleToUnits = 0.3f) }
        }
    }
}
```

**Gotcha:** update `detected` only when the list **actually changed** (the `if (imgs != detected)` guard). Frames may re-emit the same image on every update — assigning the same value still notifies `State` observers.

---

## AugmentedFaceNode

Front-camera face tracking (iOS-like FaceID style overlays). Provides an `ARFaceMesh` with vertices, normals, and texture coords.

```kotlin
AugmentedFaceNode(
    augmentedFace = face,
    faceMeshTexture = mustacheTexture  // optional overlay texture
)
```

---

## CloudAnchorNode

Hosts an anchor to the ARCore Cloud (returns a cloud anchor ID) or resolves a previously hosted one. Requires an ARCore Cloud Anchor API key.

```kotlin
CloudAnchorNode(
    anchor = localAnchor,
    ttlDays = 7,
    onHosted = { cloudId -> saveCloudId(cloudId) }
)
```

Or on the resolving side:

```kotlin
CloudAnchorNode(
    cloudAnchorId = savedCloudId,
    onResolved = { anchor -> /* place content */ }
)
```

---

## StreetscapeGeometryNode

Geospatial building and terrain meshes from ARCore's Streetscape Geometry API. Each detected building or terrain patch becomes a node with its real-world shape.

```kotlin
// Inside ARSceneView with Geospatial mode enabled
streetscapeGeometries.forEach { geom ->
    StreetscapeGeometryNode(
        streetscapeGeometry = geom,
        materialInstance = buildingMaterial
    )
}
```

Requires `config.streetscapeGeometryMode = StreetscapeGeometryMode.ENABLED` and VPS coverage.

---

## Nesting and coordinate spaces

**Every node transform is local.** When a node has a parent, its `position` / `rotation` / `scale` are expressed in the parent's local space, and the final world transform is computed by multiplying up the chain:

```
worldTransform(child) = worldTransform(parent) * localTransform(child)
```

### Example — a light bolted to a model

```kotlin
ModelNode(
    modelInstance = ship,
    scaleToUnits = 1f,
    position = Position(0f, 0f, -2f)
) {
    // This light is a CHILD of the ship. Its position (0, 2, 0) is 2m ABOVE
    // the ship in the ship's LOCAL space — if the ship moves or rotates, the
    // light moves with it.
    LightNode(
        type = LightManager.Type.POINT,
        position = Position(0f, 2f, 0f),
        apply = { intensity(30_000f); color(1f, 0.8f, 0.5f) }
    )
    // This billboard label follows the ship too.
    BillboardNode(position = Position(0f, 2.5f, 0f)) {
        TextNode(text = "SS SceneView", size = 0.2f)
    }
}
```

### Coordinate conventions

- Y is up. X is right. **Z is toward the viewer** (right-handed, like OpenGL and Filament).
- Rotations are Euler angles in **degrees**.
- Distance units are **meters** — this matters for AR, where models must match real-world scale.

### Useful conversions

```kotlin
// Local ↔ world on any node
val worldPos = node.worldPosition
val worldRot = node.worldRotation
val worldTr  = node.worldTransform

// Setting a child to a specific world position, ignoring the parent transform:
child.worldPosition = Position(5f, 0f, 0f)
```

---

## Reactive params

Every visual parameter that is **stored as state on the node impl** (position, rotation, scale, visibility, color, intensity, …) can be driven directly by Compose state, and none of these reallocate the node. Transform components (`position` / `rotation` / `scale`) on every node composable are pushed through **component-keyed `DisposableEffect`s** — applied only when the declared value changes (see [Recomposition model](#recomposition-model)) — while non-transform parameters flow through `SideEffect` (diffed for geometry/material, idempotent for text/bitmap props).

```kotlin
var glowing by remember { mutableStateOf(false) }

LightNode(
    type = LightManager.Type.POINT,
    intensity = if (glowing) 80_000f else 20_000f,
    position = Position(0f, 2f, 0f)
)

Button(onClick = { glowing = !glowing }) { Text("Toggle glow") }
```

Parameters that **rebuild geometry** (`size` on `CubeNode`, `radius` on `SphereNode`, `vertices` on `MeshNode`) also work from state — but cost a mesh rebuild each change. Keep them monotonic or use `derivedStateOf` to debounce.

---

## Auto-destroy

Nodes are destroyed automatically when they leave the composition tree. You never need to call `node.destroy()` yourself.

```kotlin
if (showCube) {
    CubeNode(size = Size(1f))  // when showCube → false, the cube is destroyed
}
```

Destruction releases:

- Filament entities and renderables
- Geometry buffers
- Default material instances (if `materialInstance = null`)
- ViewNode: `SurfaceTexture`, `Surface`, `FrameLayout`

Material instances you created yourself via `materialLoader.create*` are destroyed with the `MaterialLoader` when the scene disposes.

---

## Imperative apply

Every node composable has an `apply` parameter — a lambda with the impl class as receiver, invoked **once** at construction. Use it for settings that don't have a top-level composable parameter.

```kotlin
ModelNode(
    modelInstance = ship,
    apply = {
        // Direct access to ModelNodeImpl internals
        setReceiveShadows(true)
        setCastShadows(true)
        getAnimator()?.applyAnimation("Idle", 0f)
    }
)
```

For `LightNode`, `apply` is `LightManager.Builder.() -> Unit` — you configure the Filament light at build time:

```kotlin
LightNode(
    type = LightManager.Type.DIRECTIONAL,
    apply = {
        intensity(110_000f)
        color(1f, 0.95f, 0.9f)
        castShadows(true)
        direction(0f, -1f, -0.5f)
    }
)
```

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| `LightNode { intensity(…) }` using trailing lambda | Won't compile | Use `apply = { intensity(…) }` |
| Forgetting `viewNodeWindowManager` on `SceneView` | ViewNode renders a black rectangle | Pass `viewNodeWindowManager = rememberViewNodeManager()` |
| No light + PBR material | Everything renders black | Add a `LightNode`, or an `Environment`, or switch to `createColorInstance(..., unlit = true)` |
| Creating material instances inline | Leaks, slow recomposition | `remember(materialLoader) { materialLoader.create*(…) }` |
| Calling `modelLoader.createModel*` from `Dispatchers.IO` | Native crash | Use `rememberModelInstance(...)` or `withContext(Dispatchers.Main)` |
| Storing `Frame` in `mutableStateOf` for AR | 60 FPS recomposition | Store only derived facts (`anchors`, `detectedImages`) and diff before assign |
| `ModelNode(scaleToUnits = 1f, scale = Scale(2f))` | `scale` is silently ignored | Pick one — they are mutually exclusive |
| Using `MeshNode` for loaded glTF content | No animations, no PBR textures | Use `ModelNode` with `rememberModelInstance` |
| Not handling `rememberModelInstance` returning `null` | Conditional branches crash at launch | Use `?.let { }` or `if (instance != null)` guards |
| glTF with WebP-encoded textures (`EXT_texture_webp`) | Model loads/animates but renders untextured; Logcat shows `Missing texture provider for image/webp` | Android converts embedded WebP textures automatically since 4.28.0 — the message survives only for WebP kept in separate `.webp` files, or on the web build; re-encode those to PNG/JPEG/KTX2 ([#2305](https://github.com/sceneview/sceneview/issues/2305), [details](troubleshooting.md#model-loads-but-renders-untextured-webp-textures)) |
| Calling `node.destroy()` manually | Use-after-free, crashes on recomposition | Let the composable own the lifecycle |

---

## See also

- [`llms.txt`](../../llms.txt) — the machine-readable API surface consumed by `sceneview-mcp`
- [`DESIGN.md`](../../DESIGN.md) — the design system for any UI around a `Scene`
- [Samples](../../samples/) — runnable apps for every platform
- [sceneview-mcp](https://www.npmjs.com/package/sceneview-mcp) — MCP server that exposes this reference to Claude and other AI assistants
