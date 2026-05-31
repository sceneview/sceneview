# Recipe: Procedural Geometry

**Intent:** "Create 3D shapes without any model files"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun ProceduralScene() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val material = remember(materialLoader) {
        materialLoader.createColorInstance(Color.Gray, metallic = 0f, roughness = 0.4f)
    }

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine) {
        CubeNode(size = Size(0.5f), materialInstance = material)
        SphereNode(radius = 0.3f, materialInstance = material,
            position = Position(x = 1f))
        CylinderNode(radius = 0.2f, height = 0.8f, materialInstance = material,
            position = Position(x = -1f))
    }
}
```

## iOS (Swift + SwiftUI)

```swift
struct ProceduralScene: View {
    var body: some View {
        SceneView { content in
            let cube = GeometryNode.cube(size: 0.5)
            content.add(cube.entity)

            let sphere = GeometryNode.sphere(radius: 0.3)
                .position(.init(x: 1, y: 0, z: 0))
            content.add(sphere.entity)

            let cylinder = GeometryNode.cylinder(radius: 0.2, height: 0.8)
                .position(.init(x: -1, y: 0, z: 0))
            content.add(cylinder.entity)
        }
        .cameraControls(.orbit)
    }
}
```

## Key concepts

| Concept | Android | iOS |
|---|---|---|
| Cube | `CubeNode(size = Size(0.5f))` | `GeometryNode.cube(size: 0.5)` |
| Sphere | `SphereNode(radius = 0.3f)` | `GeometryNode.sphere(radius: 0.3)` |
| Cylinder | `CylinderNode(radius, height)` | `GeometryNode.cylinder(radius, height)` |
| Plane | `PlaneNode(size = Size(1f))` | `GeometryNode.plane(width, depth)` |
| Material | `materialLoader.createColorInstance(color)` | `SimpleMaterial(color:)` |
| Mesh generation | Filament VertexBuffer | RealityKit MeshResource |
