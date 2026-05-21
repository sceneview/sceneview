# SceneViewSwift recipes — pointers to verified demos

**Do not improvise.** Every pattern below has a matching SwiftUI demo file in
`samples/ios-demo/SceneViewDemo/Views/Demos/`. Read that file before writing
code — the demo is the authoritative recipe.

## 1. Model viewer (3D, USDZ/GLB)
[`ModelViewerDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ModelViewerDemo.swift)
— `ModelNode.load(...)` in `.task { }`, stored in `@State`, added to the
`SceneView` root.

## 2. All built-in shapes
[`AllShapesDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/AllShapesDemo.swift)
— `GeometryNode.cube / .sphere / .cylinder / .plane / .cone / .torus / .capsule`.

## 3. Camera controls (orbit / pan)
[`CameraControlsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CameraControlsDemo.swift)
— `.cameraControls(.orbit)` modifier.

## 4. Auto-rotate turntable
[`AnimationDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/AnimationDemo.swift)
— `.autoRotate(speed:)` modifier.

## 5. Light types
[`LightTypesDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/LightTypesDemo.swift)
— `LightNode.directional / .point / .spot`.

## 6. Movable light
[`MovableLightDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MovableLightDemo.swift)
— drag a `LightNode` around the scene.

## 7. Materials (PBR)
[`MaterialsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MaterialsDemo.swift)
— `material: .pbr(color:metallic:roughness:)` / `.textured(...)` on geometry.

## 8. Animation
[`AnimationDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/AnimationDemo.swift)
— `model.playAllAnimations()` / `playAnimation(at:)`.

## 9. Multi-model scene
[`MultiModelDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MultiModelDemo.swift)
— several `ModelNode`s in one `SceneView`.

## 10. 3D text
[`TextDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/TextDemo.swift)
— `TextNode(text:fontSize:color:depth:)`.

## 11. Billboard
[`BillboardDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/BillboardDemo.swift)
— `BillboardNode` always faces the camera.

## 12. Lines and paths
[`LinesPathsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/LinesPathsDemo.swift)
— `LineNode` / `PathNode` from `SIMD3<Float>` points.

## 13. Custom mesh
[`CustomMeshDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CustomMeshDemo.swift)
— composing built-in geometry into a custom shape.

## 14. Physics
[`PhysicsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/PhysicsDemo.swift)
and [`DoublePendulumDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/DoublePendulumDemo.swift)
— `PhysicsNode.dynamic / .static / .kinematic`.

## 15. Dynamic sky & fog
[`DynamicSkyDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/DynamicSkyDemo.swift),
[`FogDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/FogDemo.swift).

## 16. Image plane
[`ImagePlaneDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ImagePlaneDemo.swift)
— `ImageNode(named:size:)`.

## AR recipes (iOS only)

## 17. AR tap-to-place
[`ARPlacementDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARPlacementDemo.swift)
— `ARSceneView(planeDetection:onTapOnPlane:)` + `AnchorNode.world(position:)`.

## 18. AR instant placement
[`ARInstantPlacementDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARInstantPlacementDemo.swift)
— estimated-plane raycasts for immediate placement.

## 19. AR lighting
[`ARLightingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARLightingDemo.swift)
— `.mainLight(_:)` / `.fillLight(_:)` on `ARSceneView`.

## 20. AR recorder (record-only)
[`ARRecorderDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARRecorderDemo.swift)
— `ARRecorder` via ReplayKit. `startRecording()` / `stopRecording()`,
`ARRecorder.saveToPhotoLibrary(_:)`. No deterministic playback on iOS.

## 21. AR debugging (Rerun)
[`RerunDebugDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/RerunDebugDemo.swift)
— stream AR frame data to a Rerun viewer via `ARSceneView.onFrame`.

## Cross-platform parity

Android (`sceneview` skill) and Web (`sceneview-web` skill) expose the same
node concepts but with platform-idiomatic shapes. **Don't copy-paste between
platforms.** The Android↔Apple mapping table is in
[`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md).
