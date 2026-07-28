<!-- category: Fixed -->
- **iOS environments never lit anything.** Every bundled `SceneEnvironment`
  preset is a Radiance `.hdr`, and `EnvironmentResource(named:)` cannot load
  one — it threw `resourceLoadFailure` on `studio.hdr` / `outdoor_cloudy.hdr` /
  every other preset, and `SceneEnvironment.load()` swallowed that into "scene
  continues with default lighting". So every iOS scene carrying
  `.environment(…)` ran with **no custom IBL and no skybox**: the
  `ImageBasedLightComponent` was never set, so the scene fell back to
  RealityView's own default environment lighting (dim, not unlit — see
  #2842/#2868), and `showSkybox` had no visible effect at all. **Visual change
  on upgrade:** an app already on 4.25.0 that tuned its look around the broken
  state will render differently once the IBL and the skybox appear. `load()` now
  falls back to
  decoding the file through ImageIO (which reads `public.radiance` natively)
  and building the resource from the equirectangular `CGImage`. The
  `named:` path is still tried first, so `.exr`, asset-catalog and Reality
  Composer Pro resources are unaffected (#2896).

<!-- category: Added -->
- **`SceneView.framingMargin(_:)`** (iOS/macOS/visionOS) — scales the distance
  the auto-fit pass picks. `1.15` (default) keeps existing framing; `1.0` puts
  the content's bounding sphere exactly tangent to the frustum; below `1.0` the
  subject fills more of a tall portrait viewport. Stay at or above ~`0.95` on an
  `autoRotate` scene, where the visible azimuth is arbitrary (#2896).
- **`SceneView.cameraOrbit(azimuth:elevation:)`** (iOS/macOS/visionOS) — seeds
  the initial orbit pose. Elevation matters more than it looks: at the 60°
  vertical FOV, the 30° default pitch puts the horizon exactly on the top edge
  of the frame, so a scene with a `showSkybox` environment showed none of its
  sky at any framing (#2896).

<!-- category: Changed -->
- **The iOS App Store screenshot set is refreshed** to the v2 trio
  (`model-viewer` · `dynamic-sky` · `multi-model`) that Android shipped in
  #2854, restoring cross-store parity. The four pre-v2 images are removed. The
  scenes were retuned for capture: the model viewer uses the `.warm` photo
  studio as a backdrop instead of `.studio`'s living room, the dynamic-sky
  skyline sits on a footprint-sized ground plane at a 12° camera pitch so the
  sky fills the frame, and the multi-model park formation is compact enough for
  each model to read (#2896, #2854).
- **`qa_mode` now actually freezes auto-rotation.** `DeepLinkRouter` has
  advertised `-qa_mode 1` / `?qa_mode=1` as the deterministic-screenshot switch
  since it was added, but no demo read it — so every store capture shot
  whatever azimuth the sweep had reached, giving a different pose *and* a
  different slice of the HDRI backdrop each run. `ModelViewerDemo` and
  `MultiModelDemo` now honour it; two independent capture runs are byte-identical
  (measured: 0 differing pixels) (#2896).
- **`capture-appstore-screenshots.sh` refuses to keep a frame with a system
  banner in it.** `simctl` has no notification-suppression API, and simply
  waiting does not work — a freshly-erased device posted "Ready for Apple
  Intelligence" about a minute in, i.e. *during* a capture, which is how it
  leaked into an iPad frame. The script now re-shoots each demo after a pause
  and compares a hash of the frame's top band; a band that changed means
  something transient was drawn over it, so the pair is discarded and retried,
  and exhausting the retries fails the run (#2896, #917).
- **Known consequence — #2897 becomes live.** `SceneEnvironment.intensity` is
  applied as a `2^x` exponent (`intensityExponent:`), while the presets are
  authored as linear multipliers (`.night` 0.4, `.nightSky` 0.5, `.sunset` 0.8,
  `.outdoor` 1.2) and Android's `Environment` intensity is linear. That defect
  pre-exists this change, but it was latent while the IBL never loaded at all;
  now that it does, `.night` *brightens* ×1.32 instead of dimming ×0.4 — a ~3.3×
  divergence from Android under the same preset name. Tracked in #2897; land it
  in the same release, or the two platforms ship different lighting for
  identical code.
