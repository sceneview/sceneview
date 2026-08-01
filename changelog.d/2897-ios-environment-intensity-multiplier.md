<!-- category: Fixed -->
- **iOS/macOS/visionOS: `SceneEnvironment.intensity` is applied as the linear
  multiplier it is documented to be.** It was passed straight to RealityKit's
  `ImageBasedLightComponent(intensityExponent:)`, which scales the IBL by `2^x`,
  so every bundled preset rendered at the wrong exposure: `.studio` 1.0 at ×2.0
  and `.night` 0.4 at ×1.32 — *brightening* where it should dim to ×0.4, a ~3.3×
  divergence from Android's linear `Environment` intensity under the same preset
  name. The defect pre-dated #2896 but was latent, because
  `EnvironmentResource(named:)` could not load the Radiance `.hdr` presets and no
  `ImageBasedLightComponent` was ever set; #2896 made the IBL load, and with it
  the wrong unit. The value is now converted with `log2` at apply time, so `1.0`
  is a true no-op, the presets keep their linear authoring, and identical code
  gives identical lighting on Android and Apple. The KDoc and `llms.txt` state
  the unit explicitly (#2897).
- **The committed App Store screenshots predate this fix.** `appstore-screenshots/`
  was captured while the exponent was live, so those frames are ~2× brighter than
  the app now renders. Re-capture and re-judge the mosaic before dispatching
  `app-store-screenshots.yml` (#2897).
