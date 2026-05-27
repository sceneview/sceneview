## 4.16.10

- Version alignment with SceneView v4.16.10. Lint: VIBRATE permission declared in sceneview library manifest (HapticEngine). Security: CVE-2026-8723 qs pin. SVG coordinate corruption in website fixed. No breaking Flutter API change.

## 4.16.9

- Version alignment with SceneView v4.16.9. Security: patch CVE-2026-8723 (qs transitive dep pinned >=6.15.2 in MCP packages). Stale SDK version references fixed in gaming/interior/rerun MCP packages (4.0.9 → 4.16.9). No breaking Flutter API change.

## 4.16.8

- Version alignment with SceneView v4.16.8. Google Play 16 KB page-size alignment for Filament native libs, plane renderer alpha cap tightened, library-level 16 KB alignment fix for AAB (#2223, #2224, #2226). No breaking Flutter API change.

## 4.15.1

- Version alignment with SceneView v4.15.1. iOS demo parity sprint complete (umbrella #910 — iOS app now at full 59-demo parity with Android). Native `CameraControlMode` cases added (`.none`, `.tilt`, `.dolly` via `realityViewCameraControls(_:)`, iOS 18+). `SceneReconstructionNode` / `DepthMeshNode` available on device. Play Store deploy fix for Foreground Service permissions (#2120). No breaking Flutter API change.

## 4.15.0

- Version alignment with SceneView v4.15.0. iOS AR demo parity additions, DebugOverlay demo, TextureStreaming demo, Maestro iOS test coverage expansion, `ios-axe.sh` QA helper. No breaking Flutter API change.

## 4.14.0

- Version alignment with SceneView v4.14.0. Android Maestro test coverage expanded to 58 demos (#2144), ARFace demo front-camera diagnostic surface (#1612), docs well-known deep-link verification (#1695/#2155). No breaking Flutter API change.

## 4.13.0

- Version alignment with SceneView v4.13.0. Android + iOS demo parity sprint continued (collision/gesture-editing/image-tracking/augmented-faces/depth-occlusion demos added on iOS). No breaking Flutter API change.

## 4.12.0

- Version alignment with SceneView v4.12.0. iOS demo sprint: HDR Environment demo, Shape Extrude, Reflection Probes, Video Texture fix, AR People Occlusion, Body Tracker, Scene Mesh demos added. No breaking Flutter API change.

## 4.11.2

- Version alignment with SceneView v4.11.2. App Store submit step now uses `reviewSubmissions` API (#1831). iOS append-only demo registry pattern (#1872). No breaking Flutter API change.

## 4.11.1

- Version alignment with SceneView v4.11.1. Deploy fixes: `VERSION_NAME` on `workflow_dispatch` fallback (#1795), `publishConfig.access=public` for React Native (#1788), webpack <5.107.0 pin for sceneview-web (#1789). No breaking Flutter API change.

## 4.11.0

- Version alignment with SceneView v4.11.0. Store-deploy correctness sweep (5 PRs: #1678, #1680, #1687, #1688). Play Store listing sync, App Store draft absorption, unified "SceneView" app name. No breaking Flutter API change.

## 4.10.0

- Version alignment with SceneView v4.10.0. iOS visibility improvements, Filament 1.71.4 bump, CI hardening. No breaking Flutter API change.

## 4.9.0

- Version alignment with SceneView v4.9.0. Web-demo catalog expansion, React Native and Flutter demo catalogs, web auto-center parity port, MaterialInstance teardown safety, CI hygiene, and docs refresh. The native `io.github.sceneview` dependency coordinates continue to track the last published SceneView release. No breaking Flutter API change.

## 4.8.0

- Version alignment with SceneView v4.8.0. Bottom-sheet settings refactor, web-demo tab fixes, sceneview-web GPU-leak fix, VideoNode teardown fix, React Native bridge modernized to 4.x, CI hardening, and docs refresh. The native `io.github.sceneview` dependency coordinates continue to track the last published SceneView release. No breaking Flutter API change.

## 4.7.0

- Version alignment with SceneView v4.7.0. Flutter/React Native bridge surface expansion ([#909](https://github.com/sceneview/sceneview/issues/909)), APK size optimization, playground thumbnails, samples test infrastructure, render-test capability gate, and CI consolidation. No breaking Flutter API change.

## 4.6.2

- Version alignment with SceneView v4.6.2. CI hotfix release — the demo-app Play Store deploy is unblocked (AAB validation now uses `bundletool` instead of `aapt2`, [#1416](https://github.com/sceneview/sceneview/issues/1416)) and the API-docs deploy no longer races the website deploy on a release tag ([#1417](https://github.com/sceneview/sceneview/issues/1417)). No public Flutter API change.

## 4.6.1

- Version alignment with SceneView v4.6.1. CI hotfix release — `play-store.yml` AAB manifest validation no longer blocks the Play Store deploy ([#1413](https://github.com/sceneview/sceneview/issues/1413)). No public Flutter API change.

## 4.6.0

- Version alignment with SceneView v4.6.0. New Double Pendulum physics demo in `samples/flutter-demo` ([#1332](https://github.com/sceneview/sceneview/issues/1332)); iOS/Android demo title & taxonomy unification ([#1376](https://github.com/sceneview/sceneview/issues/1376)); AR demo screenshot regression pipeline ([#1050](https://github.com/sceneview/sceneview/issues/1050)); `MaterialLoader`/`EnvironmentLoader` coroutine-scope leak fix ([#933](https://github.com/sceneview/sceneview/issues/933)); CI hygiene cluster ([#1360](https://github.com/sceneview/sceneview/issues/1360)). No public Flutter API change.

## 4.5.0

- Version alignment with SceneView v4.5.0. visionOS immersive-space skybox ([#1235](https://github.com/sceneview/sceneview/issues/1235)); towncrier-style `changelog.d/` fragment changelog ([#1337](https://github.com/sceneview/sceneview/issues/1337)); iOS `GeometryNode`/`ShapeNode` `unlit:` now returns a flat `UnlitMaterial` ([#1359](https://github.com/sceneview/sceneview/issues/1359)); reactive main/fill light mutations on Android ([#1306](https://github.com/sceneview/sceneview/issues/1306)); `ARRecorder` restores the camera config on `stop()` ([#1358](https://github.com/sceneview/sceneview/issues/1358)); CI hardening (parallel jobs, nightly safety-net, script-injection fix). No public Flutter API change.

## 4.4.0

- Version alignment with SceneView v4.4.0. iOS skybox rendering + true-orbit camera ([#1215](https://github.com/sceneview/sceneview/pull/1215)); iOS Stage 2 demo parity catch-up ([#1194](https://github.com/sceneview/sceneview/issues/1194)); cross-platform Double Pendulum physics demo ([#1221](https://github.com/sceneview/sceneview/issues/1221)); Flutter + React Native bridges expose `CameraControlMode`, `autoCenterContent`, `ARRecorder` ([#1053](https://github.com/sceneview/sceneview/issues/1053)); in-app update polish across all 6 sample platforms ([#1244](https://github.com/sceneview/sceneview/issues/1244)–[#1249](https://github.com/sceneview/sceneview/issues/1249)); the Swift Package mirror was retired in favour of monorepo-direct SPM resolution. No public Flutter API change.

## 4.3.5

- Version alignment with SceneView v4.3.5. Pixel 9 production polish: AR Instant Placement pill overlap fix ([#1199](https://github.com/sceneview/sceneview/issues/1199)); AR Pose unlit material retune ([#1200](https://github.com/sceneview/sceneview/issues/1200)); Sketchfab model viewer expand-transition porthole fix ([#1201](https://github.com/sceneview/sceneview/issues/1201)); FR i18n for streamed-model credits sheet + asset-source chips ([#1204](https://github.com/sceneview/sceneview/issues/1204)); Debug Overlay single-sphere off-screen fix ([#1212](https://github.com/sceneview/sceneview/issues/1212)); iOS Explore `.refreshable` pull-to-refresh ([#1211](https://github.com/sceneview/sceneview/issues/1211) item 1); CI workflow deduplication (~20 min saved per PR). No public Flutter API change.

## 4.3.4

- Version alignment with SceneView v4.3.4. Pixel 9 production hotfix: AR Face Mesh full-black fix ([#1179](https://github.com/sceneview/sceneview/issues/1179)) — dropped negative `cameraExposure` on `ARFaceDemo`; AR Instant Placement Lost-anchor UX ([#1184](https://github.com/sceneview/sceneview/issues/1184)); AR Image Stabilization auto-place ([#1183](https://github.com/sceneview/sceneview/issues/1183)); Sketchfab carousel snap-fling polish ([#1182](https://github.com/sceneview/sceneview/issues/1182)); Sketchfab UTF-8 decoding ([#1181](https://github.com/sceneview/sceneview/issues/1181)); AR launcher M3 grid parity ([#1185](https://github.com/sceneview/sceneview/issues/1185)); iOS `ARLightingDemo.swift` ([#1155](https://github.com/sceneview/sceneview/issues/1155)). No public Flutter API change.

## 4.3.3

- Version alignment with SceneView v4.3.3. Hotfix: AR production blockers from Pixel 9 v4.3.0 audit — actionable Cloud Anchor `ERROR_NOT_AUTHORIZED` toast pointing at the Play App Signing key runbook ([#1177](https://github.com/sceneview/sceneview/issues/1177)), CI guard `verify-arcore-key.sh` for missing/dropped `ARCORE_API_KEY` wiring, plus verified-fixed closures for `spherePlaneResponse` ([#1097](https://github.com/sceneview/sceneview/issues/1097)), R8 strip of Fused Location Provider ([#1178](https://github.com/sceneview/sceneview/issues/1178)), and the AR rendering umbrella P0s/P1s ([#1061](https://github.com/sceneview/sceneview/issues/1061)). No public Flutter API change.

## 4.3.2

- Version alignment with SceneView v4.3.2. Patch release: completes [#1152](https://github.com/sceneview/sceneview/issues/1152) Sketchfab streaming umbrella — Stage 1 `SketchfabAssetResolver` foundations, Stage 2 demo migrations (`OrbitalARDemo`, `SceneGalleryDemo`, `AnimationDemo`, `ModelViewerDemo`, `MaterialsDemo`, `MultiModelDemo`, `ARPlacementDemo`, `ARInstantPlacementDemo`, `PhysicsDemo`), Stage 3 polish (APK slim-down, Credits sheet, offline-source chip), Stage 4 docs + MCP resources. Also: `DemoScaffold` v2 modal-bottom-sheet (#1154), iOS `SKETCHFAB_API_KEY` injected into TestFlight + App Store binaries (#1157), instrumented regression pins for v4.3.0 fixes (#1120 extension), and Dependabot security fix (`fast-xml-parser` bumped to 5.7.0+ via npm overrides — dev-only transitive, GHSA-gh4j-gqv2-49f6). No public Android API change.

## 4.3.1

- Version alignment with SceneView v4.3.1. Patch release: CI hardening (Dokka config-cache + GitHub Release decoupled from Dokka, render-tests `android-demo-screenshots` job unblocked), Android CLI migration sweep across install/launch QA scripts, GeometryDemo lux retune (80k → 5k for v4.1.0 light defaults), i18n `stringResource(R.string.…)` migration for the Android demo, IBLPrefilter KDoc cost matrix, and iOS parity for `LightSlot` + `.fillLight(_:)` on `ARSceneView`. No public Android API change.

## 4.3.0

- Version alignment with SceneView v4.3.0. Android rendering pipeline hardened: AR main light additive vs ARCore estimate (no more pitch-black scenes when ARCore dims), new AR fill light baseline + IBL, fixed Spring underdamped + Matrix.decomposeRotation thread-safety + slerp frame-rate independence + segment closest-points sign, LightEstimator robustness (destroy gate, toggle leak, buffer race), AR cubemap `GEN_MIPMAPPABLE` fix. iOS unchanged. See root `CHANGELOG.md` for full breakdown.

## 4.2.0

- Version alignment with SceneView v4.2.0. ⚠️ **iOS render defaults now match Android** (main 1k → 10k lux, fill 300 → 3k lux, new key+fill setup) — existing iOS apps embedding `sceneview_flutter` will render brighter / more cinematic. See root `CHANGELOG.md` for the full migration recipe + new `LightSlot` / `RenderQuality` / `NodeGesture` / `AnchorNode` AR APIs.

## 4.1.2

- Version alignment with SceneView v4.1.2. ⚠️ Native Android renderer defaults changed: main light intensity 100k → 10k lux, shadows on, fill light, SSAO + bloom, neutral exposure. Existing apps embedding `sceneview_flutter` will render with the new RealityKit-equivalent look. See root `CHANGELOG.md`.

## 4.0.9

- Version alignment with SceneView v4.0.7 (Rerun Save & Share, scan-to-open deep-links, Play Store canary CI).

## 4.0.2

- Version alignment with SceneView v4.0.2 (Filament destroy-order crash fixes, BillboardNode mirror, ViewNode reactive props, security bumps).

## 4.0.1

- Version alignment with SceneView v4.0.1

## 3.6.2

- Version alignment with SceneView v3.6.2

## 3.6.1

- Initial public release
- SceneView widget for Android and iOS
- ModelNode with GLB model loading
- Gesture controls (orbit, zoom, pan)
- Environment and lighting configuration

## 3.6.0

- Update SceneView dependency to 3.6.0 (Filament 1.70.1)
- Add `repository`, `issue_tracker`, and `topics` to pubspec.yaml for pub.dev discoverability
- Improve README with badges, detailed setup instructions, full controller API table, and architecture diagram
- Add LICENSE file (Apache-2.0)

## 3.5.0

- Update SceneView dependency to 3.5.0
- Version alignment with SceneView SDK

## 3.4.7

- Update SceneView dependency to 2.3.0 (latest on Maven Central)
- Fix missing `addGeometry` and `addLight` method handlers on Android (no longer crash with `notImplemented`)
- Fix `rememberEnvironment` null safety in Android bridge
- Fix iOS bridge to track per-model scale (not just paths)
- Add proper `dispose()` to SceneViewController
- Add `StateError` when calling controller methods before view is ready
- Add `isAttached` property to SceneViewController
- Update Kotlin to 2.0.21, Compose BOM to 2024.06.00, compileSdk to 35
- Update iOS podspec version to 3.4.7
- Improve Dart documentation on all public APIs

## 0.1.0

- Initial scaffold release
- SceneView widget (3D) with PlatformView on Android and iOS
- ARSceneView widget (AR) with PlatformView on Android and iOS
- SceneViewController with method channel bridge
- ModelNode, GeometryNode, LightNode data classes
- Android: SceneViewPlugin with ComposeView + Scene composable
- iOS: SceneViewPlugin with UIHostingController + SceneViewSwift
- Example app with 3D model viewer
