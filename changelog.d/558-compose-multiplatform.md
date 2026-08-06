<!-- category: Added -->
- **Compose Multiplatform support** — new `sceneview-compose` module exposing a single
  `SceneViewer` composable from `commonMain`, answering
  [#558](https://github.com/sceneview/sceneview/issues/558) and
  [#486](https://github.com/sceneview/sceneview/issues/486). Scope is the *viewer
  subset* (model, orbit camera, key light, environment, tap hit-testing); AR, custom
  materials and post-processing stay platform-native by design. Android is implemented
  and delegates to the existing Filament `SceneView { }`; iOS (RealityKit) and Desktop
  render an explicit placeholder until their renderers are wired. Purely additive — no
  existing published surface changes. See
  [docs/docs/compose-multiplatform.md](https://github.com/sceneview/sceneview/blob/main/docs/docs/compose-multiplatform.md).
- **iOS bridge for `sceneview-compose`** — `SceneViewerBridge` lets an iOS app supply the
  RealityKit renderer, since a KMP module cannot depend on a Swift Package. Gestures are
  written back into `CameraState`, so reads stay truthful about what the user did. The
  reusable `@objc UIView` wrapper around `SceneViewSwift` is not written yet; without a
  registered factory `SceneViewer` draws a visible notice rather than an empty viewport.
- **`ModelSource.Url` now rejects non-http/https URLs in `commonMain`**, so the documented
  invariant holds on every platform instead of only inside the Android downloader.
- **The vendored `third_party/filament-kmp/` copy was removed again before shipping.**
  It was 31 700 lines that no `settings.gradle` referenced, so nothing compiled it, and
  its Apache-2.0 §4(b) guard cloned a single-maintainer GitHub repo on every CI run —
  making an unrelated upstream outage able to redden every PR in the monorepo. The
  desktop track still plans to vendor; the *execution* moves to the P1 spike, where the
  copy can be taken at a current upstream tag instead of ageing on `main`. Restoring it
  is one command, documented in
  [docs/docs/desktop-filament.md](https://github.com/sceneview/sceneview/blob/main/docs/docs/desktop-filament.md).
