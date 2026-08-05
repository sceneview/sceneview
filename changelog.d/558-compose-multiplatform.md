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
- **Apache-2.0 §4(b) compliance for `third_party/filament-kmp/` is now enforced in CI.**
  `diff-upstream.sh` existed but no job ran it. It now runs on every PR and pins the
  vendored tree with `MANIFEST.sha256` in both directions, so a deleted file, an added
  build script, an added binary blob or a symlink cannot land unreviewed — none of which
  the previous extension-filtered, one-way walk could see.
