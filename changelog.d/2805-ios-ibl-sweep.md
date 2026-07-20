<!-- category: Fixed -->
- **ios-demo**: 6 more demo views now render with an image-based light
  (`.environment(.studio)`), same preset and pattern as the `ModelViewerDemo`
  (#2114), `MaterialsDemo` and Scene Gallery/Multi-Model (#2805 predecessors):
  `AnimationDemo` (bundled `cyberpunk_character.usdz` + streamed Sketchfab
  characters), `GestureEditingDemo` (Ferrari F40), `AllShapesDemo`/`GeometryDemo`
  (PBR cube + sphere — its own on-screen caption already claimed "PBR
  materials"), `BillboardDemo` (the metallic "Treasure" sphere), `CameraControlsDemo`
  (the central PBR cube), and `CustomMeshDemo` (the PBR pyramid + diamond built
  from raw vertex data). Every one of these renders a metallic/rough PBR
  surface that had nothing to reflect without an IBL. Re-measured from scratch
  against the current repo rather than reusing an older estimate — 42
  non-registry views live under `Views/Demos/*.swift` (a 43rd file,
  `GeneratedScenes.swift`, is an auto-generated registry, not a view): 12
  already carried `.environment()` before this PR, 14 are AR views
  (`ARSceneView` lights from the real camera feed, out of scope by design),
  6 gain the fix here, 3 are confirmed carve-outs (`FogDemo`, `LightTypesDemo`,
  `MovableLightDemo` — the neutral/single-light background is the demonstrated
  effect itself), and 4 have no PBR material to reflect anything with
  (`TextDemo`, `ImagePlaneDemo`/`ImageDemo`, `LinesPathsDemo`,
  `VideoTextureDemo`) so are left deliberately untouched. The remaining 3 are
  structural findings, not judgment calls: `.environment()` is only defined on
  `SceneView`, so it cannot reach a raw `RealityView`. `TextureStreamingDemo`'s
  visible PBR sphere (the demo's entire point — Gold/Silver/Copper/Ceramic/
  Plastic/Rubber presets) lives in a `RealityView` overlay entirely separate
  from its own (empty) `SceneView`; `OcclusionMaterialDemo`'s metallic
  reference sphere is also built directly on `RealityView`; `DebugOverlayDemo`
  has the same structural block but isn't a PBR showcase either way (its
  spheres are non-metallic stress-test filler). Fixing the first two for real
  needs more than this mechanical sweep, so all three are left for a
  follow-up rather than shipping a `.environment()` call that would silently
  do nothing. Part of the iOS/Android catalog-ISO effort (#2798).
  Verified: `xcodebuild` compiles clean;
  visual QA on the iOS Simulator confirms every changed view still renders
  without crashing, though — per the 2026-07-18 finding that RealityKit
  degrades IBL/skybox rendering on the Simulator — the before/after captures
  read as visually close on this host, so final visual confirmation on a
  physical device remains an open follow-up.
