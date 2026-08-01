<!-- category: Fixed -->
- **iOS/macOS/visionOS: `SceneEnvironment.intensity` is applied as the linear
  multiplier it is documented to be.** It was passed straight to RealityKit's
  `ImageBasedLightComponent(intensityExponent:)`, which scales the IBL by `2^x`,
  so every bundled preset rendered at the wrong exposure: `.studio` 1.0 at ×2.0,
  and `.night` 0.4 at ×1.32 — *brightening* where its authored value asks it to
  dim to ×0.4. The defect pre-dated #2896 but was latent, because
  `EnvironmentResource(named:)` could not load the Radiance `.hdr` presets and no
  `ImageBasedLightComponent` was ever set; #2896 made the IBL load, and with it
  the wrong unit. The value is now converted with `log2` at apply time, so `1.0`
  is a true no-op and the presets keep their linear authoring. The result is
  clamped finite for every `Float`, including `NaN` and `±infinity`, which
  RealityKit rejects. The KDoc and `llms.txt` state the unit explicitly (#2897).
- **Note for anyone reading this as a parity fix — it is not one.** Android's
  `Environment` has no intensity member; its IBL level is Filament's
  `IndirectLight.intensity` in **absolute lux** (`DEFAULT_IBL_INTENSITY = 10_000`),
  so the two knobs are not interchangeable and never were. This change moves iOS
  onto the exponent-0 baseline that `SceneFactories.kt`'s cross-platform note
  already assumes it uses (≈1000 lux equivalent); the platforms stay matched on
  the key-to-IBL ratio, not on absolute values (#2897).
- **The committed App Store screenshots predate this fix.** `appstore-screenshots/`
  was captured while the exponent was live: `01-model-viewer.png` on `.warm`
  (intensity 1.0 → ×2.00, now ×1.00) and `02-dynamic-sky.png` on `.outdoor`
  (1.2 → ×2.30, now ×1.20). Only the IBL contribution changes — the direct lights
  and the skybox are untouched — so the frames are not uniformly twice as bright,
  but they no longer match what the app renders. Re-capture and re-judge the
  mosaic before dispatching `app-store-screenshots.yml` (#2897).
