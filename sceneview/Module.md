# Module sceneview

3D rendering for Jetpack Compose, powered by Google Filament.

## Quick start

```kotlin
dependencies {
    implementation("io.github.sceneview:sceneview:4.16.3")
}
```

```kotlin
@Composable
fun ModelViewerScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val instance = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
    ) {
        instance?.let { ModelNode(modelInstance = it, scaleToUnits = 1.0f) }
        LightNode(type = LightManager.Type.SUN)
    }
}
```

## API overview

### SceneView composable

| Composable | Description |
|---|---|
| `SceneView { }` | Root 3D scene. Accepts a `SceneScope` content block. |

### remember helpers

| Function | Returns | Description |
|---|---|---|
| `rememberEngine()` | `Engine` | Filament engine with EGL context. Destroyed on leave. |
| `rememberModelLoader(engine)` | `ModelLoader` | glTF/GLB loader. Calls `updateLoad()` every frame. |
| `rememberMaterialLoader(engine)` | `MaterialLoader` | Compiled `.filamat` template cache. |
| `rememberEnvironmentLoader(engine)` | `EnvironmentLoader` | HDR/KTX1 → IBL + Skybox loader. |
| `rememberScene(engine)` | `Scene` | Filament Scene, shareable across Views. |
| `rememberView(engine)` | `View` | Filament View (one per window). |
| `rememberRenderer(engine)` | `Renderer` | Filament Renderer (one per window). |
| `rememberCameraNode(engine)` | `CameraNode` | Main rendering camera. |
| `rememberMainLightNode(engine)` | `LightNode` | Directional sun light (required for shadows). |
| `rememberEnvironment(...)` | `HDREnvironment` | IBL + optional skybox bundle. |
| `rememberCollisionSystem(view)` | `CollisionSystem` | Hit-test via View projection ray. |
| `rememberCameraManipulator(...)` | `CameraManipulator?` | Orbit/pan/zoom. Pass `null` to disable. |
| `rememberViewNodeManager(...)` | `ViewNodeManager` | Off-screen Window for ViewNode composables. |
| `rememberModelInstance(...)` | `ModelInstance?` | Async GLB load; `null` while loading. |
| `rememberOnGestureListener(...)` | `OnGestureListener` | All gesture callbacks in one object. |

### Node composables (inside `SceneView { }`)

| Node | Description |
|---|---|
| `ModelNode` | Renders a glTF/GLB model instance with animation support. |
| `LightNode` | Directional, point, spot, or sun light source. |
| `CameraNode` | Perspective or orthographic camera (secondary viewpoint). |
| `CubeNode` | Procedural box geometry with configurable size and center. |
| `SphereNode` | Procedural sphere geometry with configurable radius/stacks/slices. |
| `CylinderNode` | Procedural cylinder geometry with configurable radius/height/sides. |
| `PlaneNode` | Procedural flat quad with configurable size, normal, and UV scale. |
| `ImageNode` | Textured plane displaying a bitmap, asset image, or drawable resource. |
| `BillboardNode` | Image quad that always faces the camera (billboard behaviour). |
| `TextNode` | 3D text label rendered via Android Canvas, always faces the camera. |
| `VideoNode` | Renders Android MediaPlayer video onto a flat plane in 3D space. |
| `ViewNode` | Android View or Jetpack Compose UI rendered into a 3D quad. |
| `LineNode` | Single line segment between two 3D points. |
| `PathNode` | Polyline through an ordered list of 3D points. |
| `ShapeNode` | Triangulated 2D shape (polygon/Delaunay) extruded into 3D geometry. |
| `MeshNode` | Custom `RenderableManager` mesh from VertexBuffer + IndexBuffer. |
| `Node` | Invisible transform node; use for grouping / hierarchy. |

### Effect composables (inside `SceneView { }`)

| Composable | Description |
|---|---|
| `DynamicSkyNode` | Drives the scene's sun light based on time-of-day (colour, intensity, direction). |
| `FogNode` | Applies atmospheric fog to the scene via Filament `View.fogOptions`. |
| `ReflectionProbeNode` | Overrides the scene's IBL with a baked environment for a defined zone. |
| `PhysicsNode` | Attaches simple rigid-body physics (gravity, bounce) to a node. |

## Package structure

| Package | Contents |
|---|---|
| `io.github.sceneview` | `Scene`, `SceneView`, `SceneScope`, `NodeScope`, `SceneNodeManager`, `Engine`, `SurfaceType` |
| `io.github.sceneview.node` | `Node`, `CameraNode`, `LightNode`, `ModelNode`, `ViewNode`, geometry nodes, effect composables |
| `io.github.sceneview.loaders` | `ModelLoader`, `MaterialLoader`, `EnvironmentLoader` |
| `io.github.sceneview.collision` | `CollisionSystem`, ray/hit primitives |
| `io.github.sceneview.gesture` | `GestureDetector`, `OnGestureListener`, `CameraManipulator` |
| `io.github.sceneview.math` | Filament math extension functions |
| `io.github.sceneview.texture` | `ImageTexture`, `VideoTexture` |
| `io.github.sceneview.environment` | `Environment`, HDR environment loading helpers |
| `io.github.sceneview.animation` | `NodeAnimator`, animation utilities |
| `io.github.sceneview.geometries` | `Geometry`, `Cube`, `Sphere`, `Cylinder`, `Plane`, `Line`, `Path`, `Shape` |
| `io.github.sceneview.model` | `Model`, `ModelInstance`, glTF asset extensions |
| `io.github.sceneview.material` | Material utilities and default material helpers |
| `io.github.sceneview.managers` | Transform, renderable, and light manager extensions |
| `io.github.sceneview.components` | `RenderableComponent`, `LightComponent`, `CameraComponent` interfaces |
| `io.github.sceneview.utils` | Coroutine, file, and context helpers |
