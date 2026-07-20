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
  against the current repo rather than reusing an older estimate: 12 non-AR
  demo views already carried `.environment()` before this PR, 6 more gain it
  here, and a further set (`FogDemo`, `LightTypesDemo`, `MovableLightDemo`,
  `TextDemo`, `ImagePlaneDemo`/`ImageDemo`, `LinesPathsDemo`) is left
  deliberately untouched — either the neutral background is the demonstrated
  effect itself, or the view has no PBR material to reflect anything with.
  AR demo views are unaffected — `ARSceneView` already lights its content from
  the real-world camera feed, so a synthetic IBL would not apply. Part of the
  iOS/Android catalog-ISO effort (#2798). Two structural gaps were found but
  deliberately not patched here because the one-line `.environment()` pattern
  does not reach them (raw `RealityView` content, not the `SceneView` wrapper
  that owns the modifier): `TextureStreamingDemo`'s visible PBR sphere lives in
  a `RealityView` overlay entirely separate from its (empty) `SceneView`, and
  `OcclusionMaterialDemo`'s metallic reference sphere is also built directly on
  `RealityView`. Fixing either for real needs more than this mechanical sweep,
  so both are left for a follow-up rather than shipping a `.environment()` call
  that would silently do nothing. Verified: `xcodebuild` compiles clean;
  visual QA on the iOS Simulator confirms every changed view still renders
  without crashing, though — per the 2026-07-18 finding that RealityKit
  degrades IBL/skybox rendering on the Simulator — the before/after captures
  read as visually close on this host, so final visual confirmation on a
  physical device remains an open follow-up.
