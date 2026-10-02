<!-- category: Fixed -->
- **AR Scene Geometry no longer aborts with "AABB can't be empty"**: `StreetscapeGeometryNode` now computes its bounding box from the mesh vertices, builds no renderable for an empty mesh and rebuilds it when ARCore changes the geometry; `MeshNode` without a box also disables shadows so Filament accepts it ([#4260](https://github.com/sceneview/sceneview/pull/4260))

<!-- category: Changed -->
- **Breaking (source): `StreetscapeGeometryNode.meshNode` is now `MeshNode?`**: it is `null` while ARCore reports an empty mesh, and the instance is replaced when the geometry changes, so changes made directly on it are lost on a rebuild. Use `meshNode?.` and configure the renderable through `meshMaterialInstance` / `builder`, which every rebuild re-applies ([#4260](https://github.com/sceneview/sceneview/pull/4260))
