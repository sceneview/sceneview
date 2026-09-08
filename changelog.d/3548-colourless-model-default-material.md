<!-- category: Fixed -->
- **A 3MF, STL, PLY or OBJ that declares no colour now renders as a lit, shaded, neutral
  grey instead of a blown-out white solid wearing a bloom halo
  ([#3548](https://github.com/sceneview/sceneview/issues/3548)).** The fallback albedo the
  four loaders share was written `0.62, 0.64, 0.68` — sRGB numbers, put straight into
  glTF's `baseColorFactor`, which is defined in **linear** space. So the "light grey"
  those numbers describe was really an sRGB 0.81 near-white, and SceneView's camera runs
  about two stops over sunny-16 by design (f/12, 1/200 s, ISO 200, to match RealityKit).
  Under the model viewer's 30,000-lux IBL that albedo clipped: every shading cue vanished,
  the surface read as unlit, and it crossed the bloom threshold so the print wore a yellow
  halo. A CadQuery chair measured 435 of 480 sampled surface pixels fully clipped, with a
  visible glow outside its own silhouette. The fallback is now **18% linear grey** — the
  photographic mid-grey, neutral on all three channels — defined once and shared by the
  3MF, STL, PLY and OBJ paths, so a colourless file reads the same whatever format it
  arrived in. Same chair after: zero clipped pixels, no glow outside the silhouette, and
  facet-by-facet shading you can read. Files that *do* carry colour are untouched — an
  explicit 3MF `displaycolor` still goes through the sRGB→linear transfer it always did.
