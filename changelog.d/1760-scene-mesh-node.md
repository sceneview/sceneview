<!-- category: Added -->

- **`SceneMeshNode`** — new ARCore node wrapping `StreetscapeGeometry` meshes with unified
  `MeshClassification` semantics ([#1760](https://github.com/sceneview/sceneview/issues/1760)).
  Provides ARKit `ARMeshAnchor` parity on Android: every face in the mesh is labelled with a
  `MeshClassification` (FLOOR, WALL, CEILING, TABLE, SEAT, WINDOW, DOOR, TERRAIN, BUILDING,
  UNLABELED) and an `onClassifiedFace(faceIndex, classification)` callback lets callers build
  per-face colour maps, physics layer masks, or audio zones. On ARCore the label is coarse (one
  classification per geometry — TERRAIN or BUILDING); on ARKit it is per-face (fine-grained
  indoor labels). The callback signature is identical on both platforms so the same consumer
  code compiles unchanged.
  `ARSceneScope.SceneMeshNode(streetscapeGeometry, …)` composable wired in `ARSceneScope`; demo
  added as `ar-scene-mesh` in the Samples tab.
