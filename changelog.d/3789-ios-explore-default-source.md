<!-- category: Fixed -->

- **iOS demo: Explore opens on a catalog whose models render in 3D ([#3789](https://github.com/sceneview/sceneview/issues/3789)).**
  - Poly Haven models now open in the viewer. The demo downloads Poly Haven's USD export, which is a `.usdc` file plus its textures, and RealityKit reads it directly.
  - Without a Sketchfab key, Explore now opens on Poly Haven. With a key, it still opens on Sketchfab.
  - Poly Haven feeds and search leave out models heavier than 64 MB, so no listed model fails after a long download.
  - Icosa is hidden on iOS until SceneViewSwift can load glTF (#3655). Before, every Icosa model ended on a disabled "3D preview coming soon" button. The Android demo keeps Icosa.
  - A Trending or Recent carousel that fails to load, or loads nothing, now keeps its heading and says so, with a Try again button when it failed. Before, it disappeared.
