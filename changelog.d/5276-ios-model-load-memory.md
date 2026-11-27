<!-- category: Fixed -->
- **SceneViewSwift uses less memory when loading GLB models and stops cancelled glTF loads.** The importer shares GLB chunk and embedded image bytes with the original data and checks for cancellation during decoding and RealityKit construction.
