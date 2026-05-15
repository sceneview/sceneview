# Changelog

## Unreleased — iOS skybox renders + true-orbit camera + iOS Stage 2 demo parity + Cloud Anchor docs hotfix + `sceneview-swift` mirror retired

### Added — `night_sky` environment preset ([#1219](https://github.com/sceneview/sceneview/issues/1219))

- **New `night_sky` HDR environment** bundled across iOS and Android demos — a dramatic Milky Way starfield over a dark landscape (Poly Haven [`dikhololo_night`](https://polyhaven.com/a/dikhololo_night) by Greg Zaal, **CC0 1.0** public domain). iOS exposes it as `SceneEnvironment.nightSky` (added to `allPresets`, so it auto-surfaces in the demo's environment picker); Android adds a "Night Sky" chip to `EnvironmentDemo`. Pairs well with metallic PBR materials for chrome-mirror reflections. Web demo does not bundle HDRs and is unaffected.

### Fixed — iOS rendering

- **`SceneEnvironment.showSkybox = true` now actually paints the HDR as the scene background ([PR #1215](https://github.com/sceneview/sceneview/pull/1215), ported from [@radcli14](https://github.com/radcli14)'s [sceneview-swift#1](https://github.com/sceneview/sceneview-swift/pull/1))** — `SceneView` previously loaded the HDR and applied it as IBL via `ImageBasedLightComponent`, but never assigned it to `RealityViewContent.environment`, so the scene rendered against the default neutral void regardless of which environment preset was selected. The new path caches the loaded `EnvironmentResource` in a `@State` and applies it via `content.environment = .skybox(resource)` in the `RealityView.update:` closure with a diff guard against the last applied resource (no per-frame ARC churn). The `.task(id:)` keys on `(name, showSkybox)` so toggling the flag on the same env re-runs the loader, and clears the cached resource at the start of every task tick so cross-env transitions don't show stale skyboxes under a new IBL.

- **Orbit + pan camera modes now physically move the perspective camera in world-space ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))** — `applyCamera()` was faking the camera move by rotating + scaling `entities.root` while the perspective camera stayed pinned at `[0, 0.3, 2]`. With a global skybox, that made the background appear stationary while content visually orbited around the user — visually wrong from the camera's POV. Orbit and pan now position the camera via `CameraControls.cameraPosition()` + `look(at: target, ...)`, so the skybox correctly wraps. The scene root stays at identity for both modes; `camera.orbitRadius` is now the literal camera-to-target distance. `firstPerson` retains its rotate-the-root semantics (FOV pinch via [#1034](https://github.com/sceneview/sceneview/issues/1034)) — the true "stand still and look around" rewrite remains a v4.4.0 follow-up.

- **FOV no longer bleeds from `firstPerson` pinch into `orbit` / `pan`** — switching to `firstPerson`, pinching FOV down to e.g. 30°, then back to `orbit` kept the 30° pinched FOV on the perspective camera (visible as a stuck zoom-in). `applyCamera()` now writes the baseline `60°` FOV in `orbit` / `pan` regardless of `camera.fov`, and only mirrors `camera.fov` in `firstPerson`. On `firstPerson` exit, `camera.fov` itself is reset to `60` so the next entry starts fresh.

### Changed — `CameraControls` defaults (BREAKING for direct constructors)

- **`CameraControls.orbitRadius` public default changed from `5.0` to `2.0`** — `5.0` was unreachable through any public modifier (`cameraControls(_:)` only accepts a `CameraControlMode`), and the internal `@State` already overrode to `2.0` so existing demos retain their on-screen framing. Callers constructing `CameraControls()` directly will see the same `2.0` default the SceneView uses internally; the apparent angular size of a 1m model at default state is identical to the pre-v4.4.0 fake-orbit framing (28.07° at 60° FOV).
- **`CameraControls.minRadius` public default changed from `0.5` to `1.0`** — under the new true-camera path, `0.5` puts the perspective camera inside any model with extent >1m (which most demo content has). The old `0.5` was safe under the fake-orbit `scale = 5.0 / radius` scene-scale hack but clips into geometry now. Override for smaller content.

### Changed — SPM URL retirement

- **`sceneview-swift` SPM mirror retired in favour of monorepo-direct package resolution ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))** — every install snippet across docs, codelabs, GPT prompts, `.github/copilot-instructions.md`, `SceneViewSwift/README.md`, `llms.txt` (4 copies — root, docs, website-static, well-known), the website (`index.html` / `docs.html` / `playground.html`), the PWA manifest, the schema.org `sameAs` graph, and the bundled MCP `llms-txt.ts` now points at `https://github.com/sceneview/sceneview(.git)`. The `sceneview/sceneview-swift` mirror has been archived read-only; its frozen `v4.0.0` tag still resolves for SPM consumers pinned to the old URL, but no further releases will be cut there. Existing consumers should re-add the package in Xcode pointing at the monorepo URL — the root `Package.swift` (added in [PR #920](https://github.com/sceneview/sceneview/pull/920)) declares the `SceneViewSwift` product.

### Fixed — in-app update polish across all 6 sample platforms ([#1244](https://github.com/sceneview/sceneview/issues/1244)–[#1249](https://github.com/sceneview/sceneview/issues/1249))

Follow-ups to [PR #1216](https://github.com/sceneview/sceneview/pull/1216) (in-app auto-update feature) — six small, platform-specific hardening fixes that bring the auto-update behaviour to parity across Android, Web, React Native, Flutter, iOS and macOS:

- **Android — `InAppUpdateManager` listener stacking** ([#1244](https://github.com/sceneview/sceneview/issues/1244)): the early-return guard added in #1216 only covered `DOWNLOADING` / `READY_TO_INSTALL`. A fast double-resume landing two `checkForUpdate()` calls while the first was still in the `CHECKING` / `AVAILABLE` window would issue a parallel `appUpdateInfo` request and a duplicate `startUpdateFlow`, double-prompting the user. A private `inFlight` flag now gates re-entry, set on entry and cleared on **both** the success and failure listener so a network failure can't permanently lock out checks. Covered by two new Robolectric tests in `InAppUpdateManagerTest`.
- **Web — update snackbar hidden behind the loading overlay** ([#1245](https://github.com/sceneview/sceneview/issues/1245)): the snackbar (`z-index: 60`) sits below the loading overlay (`z-index: 100`), so a version check resolving during engine init showed the snackbar stranded behind the spinner. The snackbar is now gated on engine-init complete — if the check resolves early it is deferred and flushed once `loading-overlay.hidden` is set.
- **React Native — update banner overlapping the header** ([#1246](https://github.com/sceneview/sceneview/issues/1246)): the absolutely-positioned banner used `top: 0`, overlapping the "SceneView / React Native Demo" header. It now offsets by a `HEADER_HEIGHT` constant so it sits cleanly below the header (`react-native-safe-area-context` is not a dependency of this demo, so a measured constant is the minimal fix).
- **Flutter — deprecated `withOpacity()`** ([#1247](https://github.com/sceneview/sceneview/issues/1247)): `Color.withOpacity(0.8)` in `app.dart` replaced with the Flutter 3.27+ `withValues(alpha: 0.8)` API. `flutter analyze lib/app.dart` now reports no issues.
- **iOS / macOS — `AppStoreUpdater.openAppStore()` no-op on macOS** ([#1248](https://github.com/sceneview/sceneview/issues/1248)): the `open` call was wrapped in `#if canImport(UIKit) && os(iOS)`, making it a silent no-op on the macOS target. A `#elseif os(macOS)` branch now opens the Mac App Store via `NSWorkspace.shared.open` with the `macappstore://` scheme.
- **iOS — update throttle hard-locked on clock rollback** ([#1249](https://github.com/sceneview/sceneview/issues/1249)): if the system clock rolled backward, `now − lastCheckAt` went negative — always below the throttle — so updates never re-checked. `shouldCheck()` now clamps `lastCheckAt` to `min(last, now)` on read, repairing a future-stamped timestamp. Covered by a new test in `AppStoreUpdaterTests`.

### Fixed — Android demo regressions ([#1265](https://github.com/sceneview/sceneview/issues/1265), [#1266](https://github.com/sceneview/sceneview/issues/1266))

Two regressions from `cd4034ff` (PR #1224) that the #1241 emulator QA sweep proved still broken:

- **AR Instant Placement — "Initializing camera" pill alignment** ([#1265](https://github.com/sceneview/sceneview/issues/1265)): the scanning-indicator pill rendered bottom-left, overlapping the Clear All button, despite a `BoxScope.align(TopCenter)` set directly on its `AnimatedVisibility` wrapper. `AnimatedVisibility` introduces its own layout node for the enter/exit transition and the `align` modifier on that wrapper is not reliably honoured by the animated child. The alignment is now carried by a static `Box` that is a direct child of the outer `Box`, with `AnimatedVisibility` inside it handling only the fade — matching the structure of the working stats pill above. Pill stays pinned top-center, clear of the bottom Clear All button.
- **Debug Overlay — invisible stress-test spheres** ([#1266](https://github.com/sceneview/sceneview/issues/1266)): the `SceneView` content block had no `LightNode` and the spheres were spawned with no `materialInstance`, so the default Filament material rendered black on the black background. Added a directional key light and a shared on-brand color material (created once via `remember`, so the stress test still measures pure geometry overhead). The earlier #1212 grid-centering fix already places the count == 1 sphere at origin; this completes the visibility fix.

### Docs

- **New recipe: Blender → SceneView asset pipeline ([#1222](https://github.com/sceneview/sceneview/issues/1222))** — `samples/recipes/blender-to-sceneview.md` and `docs/docs/recipes/blender-pipeline.md` walk contributors through authoring a custom 3D model in Blender and shipping it in a SceneView app: `.glb` is native on Android, while Apple platforms go `.glb` → Reality Converter → `.usdz` → Reality Composer Pro (Blender's own USDZ exporter produces broken materials). Adapted from [@radcli14](https://github.com/radcli14)'s [`blender-to-realitykit`](https://github.com/radcli14/blender-to-realitykit) tutorial (MIT, 17⭐), with a SceneView-specific call-out on the Android Filament JNI main-thread rule. Cross-linked from both quickstarts and the API cheatsheet. Closes [#1222](https://github.com/sceneview/sceneview/issues/1222).

### Follow-ups (filed against the master polish-pipeline reference [#1218](https://github.com/sceneview/sceneview/issues/1218))

- [#1219](https://github.com/sceneview/sceneview/issues/1219) — Bundle ambientCG NightSkyHDRI008 (CC0) as `night_sky` env preset (iOS + Android + Web)
- [#1221](https://github.com/sceneview/sceneview/issues/1221) — Cross-platform 'Double Pendulum' physics demo (port of [@radcli14](https://github.com/radcli14)'s `twolinks`)
- [#1222](https://github.com/sceneview/sceneview/issues/1222) — Recipe: Blender → glb → Reality Converter → usdz → Reality Composer Pro pipeline
- [#1223](https://github.com/sceneview/sceneview/issues/1223) — Switch library-default material from `SimpleMaterial` to `PhysicallyBasedMaterial`

Special thanks to **[Eliott Radcliffe (@radcli14)](https://github.com/radcli14)** — the skybox + true-orbit camera fixes were ported with `Co-authored-by` credit from his [sceneview-swift PR #1](https://github.com/sceneview/sceneview-swift/pull/1). The asset-pipeline tutorial referenced by #1222 is from his [`blender-to-realitykit`](https://github.com/radcli14/blender-to-realitykit) repo (MIT, 17⭐).
### v4.3.6 docs hotfix — Cloud Anchor ERROR_NOT_AUTHORIZED post-SHA-1 troubleshooting ([#1177](https://github.com/sceneview/sceneview/issues/1177) follow-up)
### iOS Stage 2 demo parity catch-up ([#1194](https://github.com/sceneview/sceneview/issues/1194))

Six Android-only Sketchfab-streaming demos shipped by Stage 2 ([#1152](https://github.com/sceneview/sceneview/issues/1152)) now have proper iOS ports so the cross-platform parity guarantee (`feedback_ios_mirror_android.md`: iOS V1 == strict Android subset, no hidden gaps) holds end-to-end. The previous placeholder shape — `model-viewer` / `multi-model` deep-links routing to `SceneGalleryDemo` — is gone.

### Added — iOS samples

- **`AnimationDemo.swift`** — 5-model carousel (bundled cyberpunk character + 4 `animation`-category streamed slugs) with play / pause / speed slider / loop chips. Cinematic camera shots (Hero / Reveal / Vertigo / Tracking) + IBL intensity slider from Android remain Android-only — see the iOS demo's settings sheet for the upfront roadmap note.
- **`ModelViewerDemo.swift`** — full-screen `cyberpunk_hovercar` hero with a "Surprise me" extended button that searches the Sketchfab catalogue server-side, downloads the pick via `SketchfabService.downloadModel`, and replaces the hero in place. Button hidden when `SketchfabConfig.apiKey` is `nil` (App Store builds) so we don't ship a non-functional affordance.
- **`MultiModelDemo.swift`** — themed "Park" diorama (tree / bench / dog / bird) composed from the 4 streamed `park`-category slugs. Per-model visibility chips + spin toggle wired through `AnchorEntity` + `SceneView.autoRotate(speed:)`.
- **`ARPlacementDemo.swift`** — tap-to-place AR demo with a 5-bundle cycle and the 6 streamed `ar_placement`-category chips. Reuses `SceneViewSwift`'s `ARSceneView(onTapOnPlane:)` raycast hook.
- **`ARInstantPlacementDemo.swift`** — instant-placement variant with a toggle. ARKit doesn't expose `Config.InstantPlacementMode.LOCAL_Y_UP` directly; the iOS port approximates via `.estimatedPlane` raycasts so taps land before plane geometry has fully converged.
- **`PhysicsDemo.swift`** — rewritten from the v4.3.x cubes-only version to the Stage 2 streaming shape: bundled cubes default + 4 streamed `physics`-category crash-test meshes (vase / stool / barrel / amphora). Drop count capped at 20 active bodies because RealityKit's `PhysicsBodyComponent` slows past that.

### Changed — iOS plumbing

- **`AutoRotateDemo.swift`** struct renamed from `AnimationDemo` → `AutoRotateDemo` to free up the canonical name. The "Auto Rotate" Samples-tab entry continues to point at this struct; the new "Animation" entry routes to `AnimationDemo.swift`.
- **`SamplesTab.swift`** — added Model Viewer / Multi-Model Park entries under Geometry, and promoted "AR Plane Placement" + "AR Instant Placement" from `Coming soon` to fully wired demos.
- **`DemoDeepLinkRegistry.swift`** — `model-viewer` and `multi-model` ids no longer route to the `SceneGalleryDemo` placeholder; both land on the dedicated demos. `ar-placement` newly routed to `ARPlacementDemo`.

### Fixed — pre-existing AppStoreUpdater build break

- `AppStoreUpdater.swift:66` default parameter `currentVersion: @escaping () -> String? = AppStoreUpdater.bundleVersion` was losing the `@MainActor` global-actor isolation under Swift 6 strict concurrency, breaking the iOS demo build on main. Added `@MainActor` on both the parameter type and the stored field so the implicit `@MainActor` from the class scope propagates correctly. Surfaced while validating [#1194](https://github.com/sceneview/sceneview/issues/1194); the regression landed in [#1216](https://github.com/sceneview/sceneview/pull/1216) earlier today.

### Docs

- **`docs/docs/cheatsheet-ios.md`** — new "Demo parity status (#1194)" section above the existing "iOS parity status (#1036)" table, summarising the six ports and the honest-subset notes (cinematic camera, per-model editing, sceneview-core physics).

No library APIs change. No new releases of `:sceneview` / `:arsceneview` / `:sceneview-core` are required.

Production Cloud Anchor users still hitting `ERROR_NOT_AUTHORIZED` on v4.3.5 after the App Signing key SHA-1 was added to the Google Cloud API key restrictions. v4.3.3 ([PR #1197](https://github.com/sceneview/sceneview/pull/1197)) shipped the SHA-1 runbook + actionable in-app error pointing only at that one cause, but field experience showed there are 4 other Cloud-Console-side causes that look identical at the device.

Investigation confirmed every code-side surface is healthy:
- `ARCORE_API_KEY` GitHub secret present (39 chars, last rotated 2026-05-06)
- `samples/android-demo/build.gradle` injects `manifestPlaceholders["arcoreApiKey"]` from env / `local.properties`
- `AndroidManifest.xml` carries `<meta-data android:name="com.google.android.ar.API_KEY" android:value="${arcoreApiKey}" />`
- `ARCloudAnchorDemo.kt` enables `Config.CloudAnchorMode.ENABLED` in `sessionConfiguration`
- `play-store.yml`'s `verify-arcore-key.sh` CI guard passed green on the v4.3.5 release run (run 25891143675, 2026-05-14 23:24 UTC)
- Package name `io.github.sceneview.demo` matches the Cloud Console restriction (no `applicationIdSuffix`)

So the bug is Cloud-Console-side configuration drift, not an APK-side regression. v4.3.6 expands the docs surface so the next maintainer / contributor hitting this can self-diagnose without escalating.

### Changed

- **`samples/android-demo/STREETSCAPE_SETUP.md` adds a new "Troubleshooting — `ERROR_NOT_AUTHORIZED` persists after SHA-1 is whitelisted" subsection** under the existing "Play App Signing key" block. Five-step checklist with direct Cloud Console deep-links (replace `<PROJECT_ID>` with `pc-api-4638313286439917620-648` for the SceneView demo project):
  1. Billing enabled and active on the Cloud project (Geospatial / Cloud Anchors hit paid backends; silently rejects without billing).
  2. "ARCore API" enabled (not the legacy "ARCore Cloud Anchor API" — different products).
  3. API restrictions on the key separate from Application restrictions — must include "ARCore API" by name, or be set to "Don't restrict key".
  4. Propagation delay — observed up to 30 min in practice despite Google's "~1 min" claim.
  5. Project-ID mismatch — verify the API key whose SHA-1 you whitelisted is the same key in the GitHub secret.

- **`ARCloudAnchorDemo.kt` host/resolve error messages broadened**. The in-app banner for `ERROR_NOT_AUTHORIZED` no longer presumes the SHA-1 is the cause — it now reads "Check SHA-1 + billing + ARCore API restrictions in STREETSCAPE_SETUP.md.". This matches the v4.3.3 hotfix's actionable-error spirit but covers the full failure mode space surfaced post-#1177.

- **`.claude/scripts/verify-arcore-key.sh` reminder footer broadened** to direct maintainers reading the CI log at the new 5-step checklist rather than only the SHA-1 runbook.

### Fixed — android-demo

- **Secondary Camera demo — restore PiP overlay ([PR #1213](https://github.com/sceneview/sceneview/pull/1213))** — `SecondaryCameraDemo.kt` was renamed "Camera Presets" in commit `dfc241d5` and lost its picture-in-picture overlay; the chips ended up just snapping the main camera, defeating the "multi-camera" pitch even though the registry entry still ships the `PictureInPicture` icon + "Picture-in-picture camera view" subtitle. Two `SceneView`s now share the same engine/loaders and render the helmet simultaneously: the main view keeps the default orbital camera (user-interactive), and a small `SurfaceType.TextureSurface` PiP overlay top-start binds a dedicated `rememberCameraNode` driven by the Top / Side / Front / Corner chips via `LaunchedEffect(cameraPreset)`. Title restored to "Secondary Camera (PiP)" so `DemoInteractionTest.secondaryCamera_pipAngles` finds it again. Two correctness invariants doc'd inline: each `SceneView` gets its OWN `rememberModelInstance` (sharing one across views would double-destroy `modelInstance.root` on dispose — SIGABRT — and reparent child light/camera nodes off whichever ModelNode built last) and the PiP receives `cameraManipulator = null` (without it the SceneView frame loop writes `cameraNode.transform = manipulator.getTransform()` every frame, clobbering the `LaunchedEffect` preset writes). iOS gets the matching "Coming soon" placeholder under `.advanced` (`SamplesTab.swift`, `pip.fill` SF Symbol, v4.4) — `SceneView` Swift currently uses an internal `@State private var camera = CameraControls(mode:)` with no per-instance `cameraNode` binding, so a true RealityKit PiP needs new SceneViewSwift public API (tracked for v4.4).

No library APIs change. No new releases of `:sceneview` / `:arsceneview` / `:sceneview-core` are required — the Cloud Anchor on-device fix is entirely Cloud Console configuration; the Secondary Camera fix is scoped to `samples/android-demo/`.

## v4.3.5 — Pixel 9 production polish: AR demo UX fixes + FR i18n + CI dedup + iOS pull-to-refresh (2026-05-15)

### Added — iOS pull-to-refresh on Explore feeds ([#1211](https://github.com/sceneview/sceneview/issues/1211) item 1 — [PR #1225](https://github.com/sceneview/sceneview/pull/1225))

- **iOS pull-to-refresh on Sketchfab Explore feeds** — `samples/ios-demo/SceneViewDemo/Views/ExploreTab.swift` now wires `.refreshable { await loadSketchfabFeeds(force: true) }` on the ExploreTab `ScrollView`, mirroring the Android `PullToRefreshBox` shipped in v4.3.4 ([PR #1203](https://github.com/sceneview/sceneview/pull/1203)). New `loadSketchfabFeeds(force: Bool = false)` overload bypasses the "already loaded" guard when invoked from the swipe-down gesture, and conditionally gates the loader on `SketchfabConfig.apiKey` so builds without the key don't spinner-flash on every refresh. Items 2 (`matchedGeometryEffect` hero zoom) and 3 (ARTab close affordance) from [#1211](https://github.com/sceneview/sceneview/issues/1211) remain open as follow-ups.

### Fixed — SPM version drift caught post-v4.3.4 ([PR #1217](https://github.com/sceneview/sceneview/pull/1217))

- **2 stale SPM `from: "4.3.3"` references bumped to 4.3.4** — `pro/gpt-store/gpt-instructions.md:77` and `marketing/stackoverflow/qa-drafts.md:215`. Both files live in non-canonical directories that `sync-versions.sh` doesn't sweep, so the drift slipped past the v4.3.4 release cut ([#1153](https://github.com/sceneview/sceneview/issues/1153)). Surfaced by `.claude/scripts/impact-check.sh` after [PR #1203](https://github.com/sceneview/sceneview/pull/1203) landed.

### Changed — CI workflow deduplication (~20 min saved per PR)

- **Workflows trimmed** — Audit of `.github/workflows/` showed `assembleDebug` compiling 4× per PR (across CI, PR Check, quality-gate, Build sample APKs) and unit tests running 3×. Every duplicate removed while keeping every distinct check:
  - `pr-check.yml` — dropped `compile-android`, `lint`, `compile-web-demo`, `build-flutter-demo` (all already covered by `ci.yml`'s `build`, `web-desktop`, `flutter-demo` jobs). Kept only the unique fast guards: `check-deprecated-api`, `check-sceneview-skill`, `compile-kmp` (KMP all-targets, beyond `ci.yml`'s JS-only build), `check-workflow-scripts`, `validate-demo-assets`. Also mirrored the `paths-ignore` block from `ci.yml` so docs-only PRs no longer spin up the ~5 min Gradle KMP compile.
  - `build-apks.yml` — dropped the `pull_request` trigger. APKs were already built twice on every PR by `ci.yml` + `pr-check.yml`; this workflow's unique value (artifact upload, GitHub Release attachment) only matters on `push` / tag.
  - `quality-gate.yml` + `.claude/scripts/quality-gate.sh` — added `QUALITY_GATE_SKIP_ANDROID=1` env var, set in the CI workflow so the gate no longer re-runs `assembleDebug` + the same Android unit tests that `ci.yml`'s `build` job already executes (with JaCoCo coverage). Local invocations of `quality-gate.sh` still run the full path. MCP tests, version sync, security scans, asset CDN checks, website rules, and agent skill drift detection all still run on every PR and push.
  - `render-tests.yml` — dropped the `pull_request` trigger. Tests are non-blocking (`continue-on-error: true`) and produce screenshots rarely consulted by reviewers; the signal is still captured on every push to main, with `workflow_dispatch` available for ad-hoc feature-branch vetting.

- **Supply-chain guard centralised** — Moved `gradle/actions/wrapper-validation@v6` from the (now removed) `pr-check.yml:compile-android` step into `.github/actions/setup-gradle/action.yml` so every workflow that calls `./gradlew` (CI, PR Check, quality-gate, build-apks, render-tests, release, docs) inherits the validation. Catches any tampered `gradle/wrapper/gradle-wrapper.jar` regardless of which workflow consumes it first.

Validated by 4 independent Opus reviewers before merge. Branch protection on `main` confirmed to have zero required status checks, so no renamed job blocks merges. No downstream `workflow_run`, `needs:`, Renovate, Codecov, or contributor doc reference was broken (verified via `grep -rn workflow_run .github/workflows/` and a sweep of the last 20 merged PRs for artifact-name references).

### Fixed — Pixel 9 v4.3.0 production audit follow-ups ([umbrella #1176](https://github.com/sceneview/sceneview/issues/1176))

Five demo polish bugs caught in the Pixel 9 production audit. All are scoped to `samples/android-demo/` and `samples/android-demo/src/main/res/values-fr/strings.xml` — no library APIs change.

- **AR Instant Placement — "Initializing camera" pill overlapped Clear All at startup ([#1199](https://github.com/sceneview/sceneview/issues/1199))** — `ARInstantPlacementDemo.kt` now hides the bottom-start "Clear All" button until at least one anchor has been placed (dead affordance pre-tap), and moves the "Initializing camera — you can already tap to place" toast pill from `BottomCenter` to `TopCenter` (56 dp below the stats pill). Before, the two competed for the bottom anchor area and the user saw what looked like two half-overlapping buttons. Now: top of screen carries the transient init message, bottom is empty until an anchor exists.

- **AR Pose Placement — primitives appeared unlit on Pixel 9 ([#1200](https://github.com/sceneview/sceneview/issues/1200))** — `ARPoseDemo.kt` retunes both cube and sphere PBR materials from `roughness=0.85, reflectance=0.1` to `roughness=0.55, reflectance=0.2`. The previous values were pinned all the way to "matte safety" to avoid an IBL specular blowout on the original metallic=0.5 setup, but swung too far the other way under ARCore `ENVIRONMENTAL_HDR` — the sphere lost all visible diffuse falloff and read as a flat 2D circle next to a barely-shaded cube. The new mid-rough setting keeps the IBL safe (no blowout, metallic stays 0) while restoring the diffuse gradient that makes the sphere read as a 3D sphere.

- **Sketchfab model viewer — initial expand rendered model inside a circular crop ([#1201](https://github.com/sceneview/sceneview/issues/1201))** — `SketchfabModelViewerScreen.kt::RenderContent` now defers mounting `SceneView` until `rememberModelInstance` resolves (`instance != null`). Before, the SceneView was always composed and an opaque-surface loading placeholder was layered on top with a centered `CircularProgressIndicator`. During the bottom-sheet expand transition, the opaque surface faded relative to the still-rendering SceneView surface underneath, producing a brief "model visible inside a circular porthole" frame (the user could see the model through the fading surface overlay, with the centered spinner ring framing the visible area). Now the placeholder owns the full 440 dp box cleanly until the GLB is ready, then the SceneView mounts in one swap.

- **i18n: missing French translations for streamed-model credits sheet + asset-source chips ([#1204](https://github.com/sceneview/sceneview/issues/1204))** — `values-fr/strings.xml` adds the 7 keys flagged by the post-#1099/#1160 audit: `credits_sheet_title` / `credits_sheet_subtitle` / `credits_sheet_footer` / `credits_row_open_cd` for the streamed-model attribution sheet, and `demo_chip_bundled` / `demo_chip_streamed` / `demo_chip_streaming` for the DemoScaffold asset-source chip. The `demo_ar_streetscape_*` keys called out in the original issue body were already translated; this PR closes the broader audit gap discovered by `comm -23 used_keys.txt fr_keys.txt` (139 used keys, 7 had EN entries but no FR entries).

- **Debug Overlay — single-sphere case spawned off-screen at (-0.9, -0.9, 0) ([#1212](https://github.com/sceneview/sceneview/issues/1212))** — `DebugOverlayDemo.kt` now computes the grid footprint from the *actual* node count: `cols = min(10, count)`, `rows = min(10, ceil(count / cols))`, `layers = ceil(count / (cols × rows))`, then offsets each sphere by `-(axisLen - 1) / 2 × NODE_SPACING` so the cluster mean is *always* at origin. At count=1 → cols=rows=layers=1 → offsets all zero → sphere lands at (0, 0, 0) where the camera is looking. At count=100..1000 the new formula collapses to the same 10×10×N centered footprint as before. The previous formula `(i % 10) - 5` baked in a "10 wide" assumption that put count=1 at (-0.9, -0.9, 0) — 3.6× outside the camera frustum at the SINGLE_SPHERE_DISTANCE = 0.8 m camera distance. `autoFitDistance(...)` updated to read the new grid footprint so framing stays consistent.

## v4.3.4 — Pixel 9 production hotfix: AR Face Mesh + Instant Placement UX + UTF-8 + iOS LightingDemo (2026-05-15)

### Fixed — Sketchfab Explore cosmetic & iOS demo gaps

- **Sketchfab Explore — Polish name `My�linice` shows U+FFFD ([#1181](https://github.com/sceneview/sceneview/issues/1181) — [PR #1202](https://github.com/sceneview/sceneview/pull/1202))** — `SketchfabService.authenticatedGet` now decodes the response body as UTF-8 explicitly via `response.body.source().readString(Charsets.UTF_8)` instead of `body.string()`. OkHttp's `string()` honours the `Content-Type` charset and falls back to ISO-8859-1 when the header lacks a `charset=` parameter (which can happen at edge-cache rewrites), corrupting any non-ASCII byte. Sketchfab's API always returns UTF-8, so forcing the decode is both correct and defensive. New unit test `decodes non-ascii model names without substitution` exercises Polish / Czech / Greek / CJK fixtures.

- **AR Examples menu — green pills replaced with M3 Expressive grid ([#1185](https://github.com/sceneview/sceneview/issues/1185) — [PR #1202](https://github.com/sceneview/sceneview/pull/1202))** — `ArViewTab.kt`'s `ArDemoCard` now mirrors the `DemoCard` pattern from `DemoListScreen.kt`: gradient-tinted icon header on top + title + subtitle below, using the "Augmented Reality" category green accent (light `#66BB6A` / dark `#A5D6A7`) so the AR View launcher feels like the same app as the Samples tab. Pre-refactor the cards used floating tertiary-tinted pills that read as a "different app" against the Samples-tab grid.

- **iOS sample — `ARLightingDemo.swift` companion to #1151 fillLightNode port ([#1155](https://github.com/sceneview/sceneview/issues/1155) — [PR #1202](https://github.com/sceneview/sceneview/pull/1202))** — New AR demo at `samples/ios-demo/SceneViewDemo/Views/Demos/ARLightingDemo.swift` showcases the `.mainLight(_:)` + `.fillLight(_:)` modifiers shipped in v4.2.0 (PR #1151). Three filter chips toggle between `.systemDefault` on both slots, dim-key `.custom(LightNode.directional(intensity: 5_000))`, and key-only (`.fillLight(.disabled)`) — registered under the AR section in `SamplesTab.swift`.

### Added — Compose UX patterns in `samples/android-demo`

- **Pull-to-refresh on Explore Sketchfab feeds** ([`ExploreTabScreen.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/ui/explore/ExploreTabScreen.kt)) — `PullToRefreshBox` reloads the Trending / Staff Picks / Recently Added carousels on swipe-down. The pull-down affordance is conditionally wired so it only shows when the Sketchfab API key is present (no spinner-flash on builds without the key). The refresh path goes through a single cancel-then-restart pipeline (`refreshTick` LaunchedEffect key) so toggling the "Animated" filter mid-refresh can't race two concurrent loads writing to the same lists.
- **System back exits live AR session** ([`ArViewTab.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/ui/ArViewTab.kt)) — `BackHandler` routes the system gesture to the same exit path as the top-end Close button (detach anchors, return to the AR launcher screen). Manifest opts into `android:enableOnBackInvokedCallback="true"` so Android 13+ routes back via the new `OnBackInvokedDispatcher` (prerequisite for any future `PredictiveBackHandler` upgrade).
- **Shared-element hero morph between viewer stages** ([`SketchfabModelViewerScreen.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/ui/explore/SketchfabModelViewerScreen.kt)) — `Crossfade` replaced with `SharedTransitionLayout` + `AnimatedContent`. The 220 dp Preview thumbnail morphs in place into the 440 dp Ken-Burns Downloading hero, then into the live SceneView surface, sharing bounds across the three stages with a consistent rounded-corner clip. The live render uses `SurfaceType.TextureSurface` so the layer alpha is honoured during the morph (the default `SurfaceView` is a hardware overlay and would pop in opaque). Stage.Error is excluded from the shared bounds (no hero) and uses a clean 300 ms fade.

### Added — iOS demo parity ([umbrella #1211](https://github.com/sceneview/sceneview/issues/1211))

- **`.refreshable` on Explore Sketchfab feeds** ([`ExploreTab.swift`](samples/ios-demo/SceneViewDemo/Views/Tabs/ExploreTab.swift), [PR #1225](https://github.com/sceneview/sceneview/pull/1225)) — pull-to-refresh on the iOS `ScrollView` mirrors the Android `PullToRefreshBox` in #1203. `loadSketchfabFeeds(force: Bool)` bypasses the "already loaded" guard when called from `.refreshable` so manual pulls actually re-fetch.
- **iOS 18 zoom navigation transition Explore card → viewer** ([PR #1232](https://github.com/sceneview/sceneview/pull/1232)) — `.matchedTransitionSource(id:in:)` on the carousel card pairs with `.navigationTransition(.zoom(sourceID:in:))` on the destination so the thumbnail morphs into the viewer's preview hero on push. The viewer now exposes an explicit `Stage.Preview` (description / tag chips / "Open in SceneView" CTA / non-downloadable warning) matching Android's `Stage.Preview` `PreviewContent` — the network download only fires after the user taps the CTA, and a Retry button on the error overlay resets to the preview state. Source IDs are namespaced by feed (`"sketchfab-hero-staff-…"` / `"-liked-"` / `"-recent-"`) so a model appearing in more than one carousel doesn't collide on the matched namespace.

### Fixed — Pixel 9 v4.3.0 production audit follow-ups ([umbrella #1176](https://github.com/sceneview/sceneview/issues/1176))

Two findings ([#1179](https://github.com/sceneview/sceneview/issues/1179) Face Mesh + [#1184](https://github.com/sceneview/sceneview/issues/1184) Instant Placement) accumulated post-v4.3.3 and are the primary code content of v4.3.4. Two more ([#1183](https://github.com/sceneview/sceneview/issues/1183) EIS auto-place + [#1182](https://github.com/sceneview/sceneview/issues/1182) snap-fling) shipped on `main` before the v4.3.3 tag was cut but were not formally announced in the v4.3.3 body — they are written up here for completeness.

- **AR Face Mesh — full black surface on Pixel 9 ([#1179](https://github.com/sceneview/sceneview/issues/1179) — [PR #1198](https://github.com/sceneview/sceneview/pull/1198))** — `samples/android-demo/.../ARFaceDemo.kt` no longer passes `cameraExposure = -1.5f`. The author had intended a "-1.5 EV bias", but Filament's single-arg `CameraComponent.setExposure(Float)` is an **absolute linear exposure scaling** (1.0 ≈ ISO 100 ≈ EV 0), not a signed EV-stop bias as the prior KDoc misleadingly hinted. A negative scaling clamps the framebuffer to zero, hence the fully-black scene on Pixel 9 v4.3.0 production. The front-camera AR session already force-DISABLES light estimation (see [`ArSession.kt`](arsceneview/src/main/java/io/github/sceneview/ar/arcore/ArSession.kt#L77)) and the new `ARDefaultCameraNode` defaults (f/12, 1/200 s, ISO 200 ≈ EV 11.6 — after [PR #1088](https://github.com/sceneview/sceneview/pull/1088)) + 10k+3k lux main+fill lights give a correctly exposed selfie preview on every device tested. Also rewrote the `cameraExposure` parameter KDoc in [`ARScene.kt`](arsceneview/src/main/java/io/github/sceneview/ar/ARScene.kt) so future contributors don't repeat the misinterpretation. Pinned by `ARCompletenessDefaultsTest.ARFaceDemo no longer passes a negative cameraExposure value` so any grep-and-paste regression gets caught.

- **AR Instant Placement — anchors silently floating after `STOPPED` ([#1184](https://github.com/sceneview/sceneview/issues/1184) — [PR #1198](https://github.com/sceneview/sceneview/pull/1198))** — `samples/android-demo/.../ARInstantPlacementDemo.kt` now reconciles each placed anchor's `TrackingState` every frame. When ARCore drops a placed `InstantPlacementPoint`'s underlying `Anchor` to `STOPPED` (the user typically panned the camera away from where the point was approximated), we now detach the dead anchor, hide its `ModelNode` (which previously froze at the last good pose, visually "floating off into space"), and surface "Lost — tap to re-place" on the per-model badge. The top status pill gains a "N lost" segment when relevant. The per-model badge column iterates `placedModels` rather than `trackingMethods` so anchors that flip to `STOPPED` before their first `trackingMethod` ever fires still surface as Lost.

- **AR Image Stabilization (EIS) — demo auto-places helmet on first tracking frame ([#1183](https://github.com/sceneview/sceneview/issues/1183) — [PR #1191](https://github.com/sceneview/sceneview/pull/1191))** — `ARImageStabilizationDemo` now auto-creates a 1 m-in-front anchor on the first stable `TRACKING` frame and drops the helmet there, with a one-shot `autoPlaced` guard so Clear + manual tap still hand control back to the user. The v4.3.0 demo shipped with no model visible at start — users had to wait for the plane finder (5–10 s indoors) and tap, but the "How to test" panel never said so. Pixel 9 audit frames (`key-frames/t340s.jpg` / `t360s.jpg`) showed a 30-second EIS-toggle session where the user never saw a model. With the auto-place, the demo's core value (helmet stays glued while background stabilizes) is visible within ~1 s of TRACKING. The anchor pose is `frame.camera.pose.compose(Pose.makeTranslation(0f, 0f, -1.0f))` so the helmet appears straight ahead at eye level regardless of camera tilt, and works in featureless areas where the plane finder stalls.

- **Sketchfab carousels — snap-to-card fling + edge padding ([#1182](https://github.com/sceneview/sceneview/issues/1182) — [PR #1196](https://github.com/sceneview/sceneview/pull/1196))** — Both Explore-tab `LazyRow`s (curated samples + Sketchfab feed) gain `flingBehavior = rememberSnapFlingBehavior(state)` so scroll releases always land on a card boundary, never mid-card, plus `contentPadding = PaddingValues(horizontal = 4.dp)` for first/last-card breathing room. The Pixel 9 audit caught two Sketchfab cards (`queGRD`, `Myślinice`) rendering truncated mid-name at a viewport edge — the cards themselves were fine (`maxLines = 1, overflow = TextOverflow.Ellipsis`), but the LazyRow released the flick mid-card. iOS has its own `ScrollView`/`LazyHStack` snapping config and is intentionally not touched here.

## v4.3.3 — AR production hotfix: actionable Cloud Anchor error + CI key guard (2026-05-14)

### Fixed — AR production blockers (Pixel 9 v4.3.0 audit umbrella [#1176](https://github.com/sceneview/sceneview/issues/1176))

This hotfix follows the v4.3.0 production audit. The umbrella's P0 / P1 code bugs all landed by v4.3.2 ([PR #1136](https://github.com/sceneview/sceneview/pull/1136) AR IBL baseline + [#1086](https://github.com/sceneview/sceneview/pull/1086) HDR specular filter + [#1088](https://github.com/sceneview/sceneview/pull/1088) AR exposure + [#1075](https://github.com/sceneview/sceneview/pull/1075) 3D IBL intensity + [#1190](https://github.com/sceneview/sceneview/pull/1190) R8 keep rules for Fused Location Provider). v4.3.3 closes the remaining production-blocker gap that requires a Cloud-Console-side change to fully unblock end users.

- **Cloud Anchor `ERROR_NOT_AUTHORIZED` now surfaces actionable guidance ([#1177](https://github.com/sceneview/sceneview/issues/1177))** — When `host()` or `resolve()` comes back with `ERROR_NOT_AUTHORIZED`, the demo status banner now says `"The ARCore Cloud API key is rejecting this APK's SHA-1. See STREETSCAPE_SETUP.md → \"Play App Signing key\"."` instead of the raw enum. The root cause on a fresh Play Store deploy is that the App Signing key SHA-1 (post-Play-resign) isn't whitelisted on the Google Cloud API key — a manual Cloud Console step that the demo can't perform itself.

- **`STREETSCAPE_SETUP.md` adds a "Play App Signing key" runbook** — Step-by-step for maintainers to add the post-resign SHA-1 fingerprint to the ARCore API key restrictions, eliminating the production blocker without re-cutting a release.

- **CI guard for ARCore key wiring (`.claude/scripts/verify-arcore-key.sh`)** — `play-store.yml` now fails fast if `ARCORE_API_KEY` secret is missing, if `samples/android-demo/build.gradle` no longer injects the `arcoreApiKey` manifest placeholder, or if `AndroidManifest.xml` drops the `${arcoreApiKey}` reference. Catches the silent-regression class that ships an AAB with an unwired Cloud key.

### Verified fixed (closing tracker issues)

- **[#1097](https://github.com/sceneview/sceneview/issues/1097) `spherePlaneResponse` wrong contact point on negative side** — fixed in [`CollisionResponse.kt`](sceneview-core/src/commonMain/kotlin/io/github/sceneview/collision/CollisionResponse.kt#L89) (`contactPoint = center - planeNormal * signedDist` projects along the original unflipped normal). JVM regression test `spherePlaneResponseContactPointLandsOnPlaneOnEitherSide` pins the behaviour on both sides of the plane.

- **[#1178](https://github.com/sceneview/sceneview/issues/1178) AR Terrain & Rooftop Anchors fail in release builds (R8 strip)** — fixed in [`arsceneview/consumer-rules.pro`](arsceneview/consumer-rules.pro) via [PR #1190](https://github.com/sceneview/sceneview/pull/1190). Consumer-side R8 now keeps `com.google.android.gms.location.**`, `common.api.**`, and `tasks.**` so ARCore can reflectively link Fused Location Provider when `Config.GeospatialMode.ENABLED`.

- **[#1061](https://github.com/sceneview/sceneview/issues/1061) AR rendering quality umbrella (multiplicative drift, no default IBL, mirror reflections, EV15 vs EV11.6 exposure)** — all P0 / P1 sub-issues closed: [#1062](https://github.com/sceneview/sceneview/issues/1062) (baseline-relative light apply pattern in [`ARScene.kt`](arsceneview/src/main/java/io/github/sceneview/ar/ARScene.kt#L817), `AtomicReference` baselines), [#1063](https://github.com/sceneview/sceneview/issues/1063) (neutral IBL fallback in [`createAREnvironment`](arsceneview/src/main/java/io/github/sceneview/ar/ARFactories.kt#L65)), [#1064](https://github.com/sceneview/sceneview/issues/1064) (`environmentalHdrSpecularFilter = true` default in [`LightEstimator.kt`](arsceneview/src/main/java/io/github/sceneview/ar/light/LightEstimator.kt#L128)), [#1067](https://github.com/sceneview/sceneview/issues/1067) (AR exposure aligned to v4.1.0 3D defaults). `Config.LightEstimationMode.ENVIRONMENTAL_HDR` is the default in [`ARScene.kt`](arsceneview/src/main/java/io/github/sceneview/ar/ARScene.kt#L482) so PBR materials read ARCore's HDR cubemap + spherical harmonics + main-light estimate from frame one. Remaining sub-issues [#1065](https://github.com/sceneview/sceneview/issues/1065) (recording resolution) and [#1066](https://github.com/sceneview/sceneview/issues/1066) (camera-stream double-gamma) stay open as P1 polish for v4.4.

## v4.3.2 — #1152 Sketchfab streaming complete + iOS key + DemoScaffold v2 + APK slim (2026-05-14)

### Security — `fast-xml-parser` bumped to 5.7.0+ via npm overrides ([Dependabot alert #139](https://github.com/sceneview/sceneview/security/dependabot/139) — [PR #1162](https://github.com/sceneview/sceneview/pull/1162))

Resolves [CVE-2026-41650 / GHSA-gh4j-gqv2-49f6](https://github.com/NaturalIntelligence/fast-xml-parser/security/advisories/GHSA-gh4j-gqv2-49f6) — `fast-xml-parser` XMLBuilder fails to escape `-->` (comment) and `]]>` (CDATA) delimiters, allowing XML injection / XSS / SOAP-injection when user-controlled data flows into those contexts.

- **Package**: `fast-xml-parser` (npm, dev-only transitive in `react-native/react-native-sceneview`).
- **Resolved version before fix**: `4.5.6` → **after fix**: `5.8.0`.
- **Severity**: moderate (CVSS 6.1).
- **Dependency chain**: `react-native` (devDep) → `@react-native-community/cli-platform-ios@11.4.1` → `fast-xml-parser@^4.0.12`.

Fix shipped as an npm `overrides` block in `react-native/react-native-sceneview/package.json` — the standard npm 8+ way to force a safe transitive version without migrating `react-native` from 0.72 to a newer line. `npm install --package-lock-only` regenerated the lockfile cleanly; `npm audit` reports `found 0 vulnerabilities`. Dev-only chain (every entry in the affected closure is `"dev": true`); **no published runtime artefact from `@sceneview-sdk/react-native` ships `fast-xml-parser`**.

### Changed — Stage 3 polish + APK slim-down + Credits sheet for streamed assets ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 3)

Stage 3 closes the Sketchfab streaming umbrella (Stage 1 foundations, Stage 2 × 8 demo migrations, Stage 3 polish, Stage 4 docs). Four polish items shipped here:

**APK / IPA slim-down.** `samples/android-demo/src/main/assets/models/animated_dragon.glb` (8.0 MB) and `samples/ios-demo/SceneViewDemo/Models/animated_dragon.usdz` (8.6 MB) are removed. Both files were used as canonical picks by `OrbitalARDemo` + `ArViewTab` (Android) and `OrbitalARDemo` + `ARTab` + `ExploreTab` (iOS). Canonical references migrate to `threejs_soldier.glb` (2.1 MB, animated peer) on Android and `phoenix_bird.usdz` (1.1 MB, animated peer) on iOS. Fallback paths in `SampleAssets.kt` for streamed slugs (butterfly / hummingbird / bee / koi / songbird) flip from `animated_dragon.glb` to `threejs_soldier.glb`. Net Android release-APK savings ~5 MB (88 MB → 88 MB after measurement, was 93 MB before); ~8 MB AAB on-disk. iOS IPA savings ~8.6 MB.

**Credits sheet (CC-BY attribution).** New `samples/android-demo/.../ui/CreditsSheet.kt` + `samples/ios-demo/SceneViewDemo/Views/CreditsSheet.swift` ModalBottomSheet / SwiftUI sheet listing every streamed Sketchfab model the demo app may load, grouped by `SketchfabSlug.category`, with author + CC-BY 4.0 attribution + tap-to-open-Sketchfab-page rows. Anchored to the "Credits" card on the About tab. The sheet reads `SampleAssets.all` directly — adding a slug in the registry automatically credits it here. CC-BY 4.0 requires visible attribution; without this sheet, redistributing the streamed models violated the license.

**Per-demo offline indicator chip.** New `AssetSourceState` enum (Streamed / Streaming / Bundled) + optional `assetSource:` parameter on `DemoScaffold`. The chip is pinned to the top-end of the scene area, advertises the streamed-or-fallback origin of the currently visible asset, and auto-hides when `null`. Wired into `OrbitalARDemo` / `SceneGalleryDemo` / `ModelViewerDemo` / `ARPlacementDemo` as exemplars; remaining Stage 2 demos can opt in incrementally. Helps users (and reviewers) understand at a glance whether they're seeing the streamed CC-BY model or the bundled offline fallback.

**iOS parity audit.** `OrbitalARDemo` / `SceneGalleryDemo` / `MaterialsDemo` already stream via `SketchfabAssetResolver` (Stage 2 parity preserved). `ModelViewerDemo` / `AnimationDemo` / `MultiModelDemo` / `ARPlacementDemo` / `ARInstantPlacementDemo` / `PhysicsDemo` are Android-only in v4.3.x; per `feedback_ios_mirror_android.md` iOS V1 ships as a strict subset. Follow-up issue filed to track porting (see issue body — Stage 3 PR creation).

**Cleanup.** `SketchfabSlug.sketchfabUrl` computed property added on both platforms (link target for the Credits sheet). `assets/CREDITS.md` keeps the dragon entry for posterity — the model is still on Sketchfab and the CDN-hosted GLB at `cdn.jsdelivr.net/.../assets/models/glb/animated_dragon.glb` did not exist anyway (web-demo dragon entry was a dead link before this PR; now removed).

### Added — In-app auto-update across every sample app

Every published sample app now checks for a newer build on resume and surfaces a banner that lets the user trigger the install in a single tap. The pattern stays in `samples/` rather than the SceneView SDK itself — auto-update isn't a 3D/AR concern, and bundling Play Core / iTunes plumbing into `sceneview-core` would force every consumer to ship it.

**Android (`samples/android-demo`, `samples/android-tv-demo`).** `io.github.sceneview.sample.common.update.InAppUpdateManager` is now factored into `:samples:common` and wraps Play Core's `AppUpdateManager.startUpdateFlow(FLEXIBLE)`. The matching `UpdateBanner` composable renders during `DOWNLOADING` / `READY_TO_INSTALL` only, with a "Restart" CTA that calls `completeUpdate()`. `samples/android-demo`'s previous in-tree copy is deleted in favour of the common one; `android-tv-demo` gains the `INTERNET` permission + a TV-friendly banner overlay focused on `Alignment.TopCenter`. A secondary constructor allows tests to inject `FakeAppUpdateManager` directly. Seven Robolectric tests cover IDLE → DOWNLOADING → READY_TO_INSTALL → IDLE, `checkForStalledUpdate` (download finished while backgrounded), `destroy()` idempotency, FLEXIBLE-type sanity, and zero-totalBytes safety.

**iOS (`samples/ios-demo`).** New `AppStoreUpdater` ObservableObject hits `https://itunes.apple.com/lookup?id=6761329763` on every `ScenePhase.active` transition, compares the result with `Bundle.main.infoDictionary["CFBundleShortVersionString"]`, and renders a Liquid Glass `.regularMaterial` SwiftUI banner with **Update** (deep-links to `itms-apps://itunes.apple.com/app/id...`) + **Later** (7-day snooze) CTAs. Throttle: 12 h between network calls via `UserDefaults`; snooze key cleared after the window expires. Apple does not expose a programmatic install API on iOS, so the banner is the best we can do — documented in the manager's KDoc. XCTest fixture (`SceneViewDemoTests/AppStoreUpdaterTests.swift`) ships with a `URLProtocol` stub harness; the project-level test target wiring lands in a follow-up PR.

**Web (`samples/web-demo`).** `document.addEventListener('visibilitychange')` polls `https://sceneview.github.io/version.json` (cached for 12 h via `localStorage`) and slides a Liquid Glass snackbar from the bottom with a **Reload** CTA when the JSON reports a version newer than the build-time `BUILD_VERSION` constant. Snooze is keyed on the latest seen version so a future bump re-surfaces the prompt. New `website-static/version.json` is auto-deployed by the existing `deploy-website.yml` workflow at every `website-static/**` push — `sync-versions.sh` keeps the `.version` field in lockstep with `gradle.properties` `VERSION_NAME`.

**Flutter (`samples/flutter-demo`).** `WidgetsBindingObserver` triggers `UpdateChecker.checkForUpdate()` on `AppLifecycleState.resumed`. Android delegates to `in_app_update` (the community wrap of Play Core); iOS uses `http` + `package_info_plus` to read the iTunes lookup response and `url_launcher` to open `itms-apps://`. Material 3 banner surfaces the same Update / Later CTAs as the other platforms.

**React Native (`samples/react-native-demo`).** `<UpdateChecker />` mounts at the root; `AppState` events drive the check, Android via `sp-react-native-in-app-updates`, iOS via `fetch` + `Linking.openURL`. New cross-platform 12 h throttle + 7-day snooze in component state. RN demo version literal bumped 3.6.2 → 4.3.1 to align with `gradle.properties`.

**Infrastructure.** `.claude/scripts/sync-versions.sh` gains 5 new checks (`website-static/version.json` `.version` field, web-demo `Main.kt` `SDK_VERSION`, web-demo `index.html` `BUILD_VERSION` literal, RN-demo `package.json` `"version"`, RN-demo `App.tsx` `VERSION` literal) with matching `--fix` paths. `llms.txt` (mirrored to `docs/docs/llms.txt`) documents the pattern AI-first so a developer asking an AI to add auto-update to their SceneView app gets working code on the first try.

### Added — Stage 4 docs + AI-first surfaces for Sketchfab streaming + `DemoScaffold` v2 ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 4)

Stage 4 of the [#1152](https://github.com/sceneview/sceneview/issues/1152) umbrella. The Stage 2 patterns shipped over the last 7 PRs (Sketchfab streaming + DemoScaffold v2 modal sheet + chip picker) now have first-class documentation on every AI-first surface SceneView exposes.

**New recipe pages (mkdocs).**

- `docs/docs/recipes/sketchfab-streaming.md` — full how-to + license guidance + add-a-slug checklist + API-key wiring story.
- `docs/docs/recipes/demo-settings-sheet.md` — `DemoScaffold` v2 API + picker pattern + gesture map + discoverability lesson from issue [#951](https://github.com/sceneview/sceneview/issues/951).
- `docs/mkdocs.yml` — nav restructured. "Recipes" was a single leaf; now it's a section with Overview + the two new recipe pages.

**llms.txt updates (root + `docs/docs/llms.txt` mirror).**

Two new sections inserted before "Android Advanced APIs":

- `## Sketchfab streaming for samples (#1152)` — copy-paste resolver pattern (8 lines of Kotlin) + hard rules (CC-BY-only, no WebView, never network-required, attribute the author) + LRU cache contract + bounds sanity check.
- `## DemoScaffold v2 — full-screen scene + ModalBottomSheet controls (#1154)` — DemoScaffold API signature + picker pattern + gesture map.

`docs/docs/llms.txt` synced byte-for-byte to root via `cp`.

**New MCP resources (`sceneview-mcp` npm package).**

Two new `examples://` URIs surface compact (< 4 KB each) inline examples that an AI agent can fetch in one round-trip when it needs to scaffold a demo:

- `examples://demo-with-settings` — DemoScaffold v2 pattern.
- `examples://sketchfab-streaming` — SketchfabAssetResolver pattern.

Both are registered in `mcp/src/index.ts`'s `ListResourcesRequestSchema` + `ReadResourceRequestSchema` handlers. Body strings live in a new `mcp/src/examples.ts` module so the build pipeline can pin their byte budget via `mcp/src/examples.test.ts` (16 new vitest cases — start with H1, mention key APIs, < 4 KB, point at full recipe).

**Files touched:**

- `docs/docs/recipes/sketchfab-streaming.md` (new) — full how-to.
- `docs/docs/recipes/demo-settings-sheet.md` (new) — full how-to.
- `docs/mkdocs.yml` — Recipes section restructured.
- `llms.txt` + `docs/docs/llms.txt` — 2 new sections + version-resync to 4.3.1.
- `mcp/src/examples.ts` (new) — inline resource bodies.
- `mcp/src/examples.test.ts` (new) — 16 vitest cases pin the resource shape.
- `mcp/src/index.ts` — wires the 2 new resources into the `ListResourcesRequestSchema` + `ReadResourceRequestSchema` handlers.
- `mcp/src/generated/llms-txt.ts` — regenerated from root `llms.txt` (the build pipeline embeds it via `mcp/scripts/generate-llms-txt.js`).
- `mcp/src/__fixtures__/analyze-project/android-ok/build.gradle.kts` — fixture bumped from 4.1.2 to 4.3.1 by `mcp/scripts/generate-version.js` running during `npm run prepare`.

**Acceptance:**

- `cd mcp && npm test` GREEN (2562 tests, 102 files — 16 new from `examples.test.ts`).
- `bash .claude/scripts/sync-versions.sh` GREEN (0 errors, 1 pre-existing warning).
- `cp llms.txt docs/docs/llms.txt` — diff is now empty.

### Changed — Stage 2 demo migrations: `PhysicsDemo` drops streamed crash-test bodies ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

`samples/android-demo/.../demos/PhysicsDemo.kt` keeps the existing `PhysicsNode`-driven simulation but replaces the coloured spheres carousel with the four streamed entries from [`SampleAssets.byCategory`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt)`["physics"]` — Ceramic Vase, Wooden Stool, Wooden Barrel, Clay Amphora (all CC-BY from Sketchfab). A first "Bundled spheres" chip preserves the v4.3.1 visual default for QA / offline / store-listing screenshot determinism.

**Behavioural contract.** The simulation is unchanged — every dropped body is treated as a bounding-sphere of `collisionRadius = 0.08 m` so the bounce reads naturally regardless of mesh shape. The visual mesh is a `ModelNode` parented to the simulated `SphereNode`; the parent sphere is still drawn (the colour ramp gives a soft pad underneath the streamed mesh) so the simulation feels like "spheres with mesh skins" rather than abstract solids. This honours `feedback_demo_quality` — the demo's value is the SDK simulation hook-up, not a custom physics engine that handles convex-hull colliders.

Switching the picker resets the scene (`bodyCount = 5; generation++`) so the new shape is what falls — useful because mixed scenes confuse what the user is supposed to be observing.

Offline / no-key behaviour preserved — the resolver's per-slug fallback path returns the registered bundled GLB even when `SketchfabConfig.apiKey == null`, so the carousel always renders something visible. The streamed slot will visually match the bundled fallback in that case.

**Files touched:**

- `samples/android-demo/.../demos/PhysicsDemo.kt` — full rewrite of the composable. Adds the chip row, the slug resolver, and the streamed-mesh-as-child pattern.
- `samples/android-demo/src/main/res/values/strings.xml` + `values-fr/strings.xml` — 3 new keys: `demo_physics_picker_label`, `demo_physics_picker_spheres`, `demo_physics_picker_subtitle`.

**iOS counterpart not in this PR.** The iOS demo app does not currently have a `PhysicsDemo.swift` — RealityKit's built-in `PhysicsBodyComponent` makes the SceneView wrapper less interesting on iOS, and the iOS V1 doesn't expose a SceneView `PhysicsNode` analogue. The 4 `physics` slugs (2 new in the AR-placement PR, 2 from Stage 1) are registered in `samples/ios-demo/.../Services/SampleAssets.swift` ready for a future port.

**`SampleAssets` slugs added:** 0 — the 2 new `physics` entries (Wooden Barrel, Clay Amphora) shipped in the previous Stage 2 PR (PR [#1187](https://github.com/sceneview/sceneview/pull/1187) AR placement); this PR consumes them for the first time.

**30 s screen recording deferred** — agent worktree has no Pixel device access; tracked in the [#1152](https://github.com/sceneview/sceneview/issues/1152) acceptance checklist.

### Changed — Stage 2 demo migrations: `ARPlacementDemo` + `ARInstantPlacementDemo` gain a "Pick what to place" sheet ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

Both AR placement demos now expose the [`SampleAssets.byCategory["ar_placement"]`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt) chip row in their `DemoScaffold` v2 controls sheet (delivered in PR [#1169](https://github.com/sceneview/sceneview/pull/1169)). Selecting a streamed slug (coffee mug / houseplant / wooden crate / side table / floor lamp / picture frame — six entries CC-BY from Sketchfab) arms it as the next tap's payload; subsequent taps on a detected plane spawn a fresh AnchorNode + ModelNode using the streamed glTF resolved through [`SketchfabAssetResolver`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SketchfabAssetResolver.kt).

A first "Bundled cycle" chip preserves the v4.3.1 behaviour — each tap rotates through the existing 5-model bundled GLB cycle (helmet / fox / lantern / toy car / shiba). This keeps the demo deterministic for QA / offline / store-listing screenshots and gives the user a clear "no surprises" mode side-by-side with the streamed picker.

Behavioural contract:

- **Selected slug, download landed.** Tap places the streamed slug. Multiple taps place multiple instances of the same slug.
- **Selected slug, download still in flight.** Tap silently falls back to the bundled cycle so the tap is never lost. The picker subtitle shows "Streaming X…" so the user knows the streamed pick will activate on the next tap.
- **"Bundled cycle" selected.** v4.3.1 behaviour preserved.

Offline / no-key behaviour preserved — the resolver's per-slug fallback path still returns the registered bundled GLB even when `SketchfabConfig.apiKey == null`, so a tap on a streamed chip always renders something. The streamed slot will visually match the bundled fallback in that case, which is the same trade-off Stage 1 documented.

**Files touched:**

- `samples/android-demo/.../demos/ARPlacementDemo.kt` — adds the chip row, the slug resolver, the per-tap "selected vs cycle" decision. `PlacedModel.assetPath` renamed to `assetLocation` so both `assets/`-relative paths and `file://` URIs flow through the same `rememberModelInstance` call.
- `samples/android-demo/.../demos/ARInstantPlacementDemo.kt` — same chip row, hoisted to the outer `ARInstantPlacementDemo` composable so it survives the `key(instantEnabled)` rebuild that re-creates the inner ARCore session.
- `samples/android-demo/src/main/res/values/strings.xml` + `values-fr/strings.xml` — 5 new keys: `demo_ar_placement_picker_label`, `demo_ar_placement_picker_bundled`, `demo_ar_placement_picker_streaming`, `demo_ar_placement_picker_streamed`, `demo_ar_placement_picker_subtitle`.
- `samples/android-demo/.../sketchfab/SampleAssets.kt` + `samples/ios-demo/.../Services/SampleAssets.swift` — grow `ar_placement` from 3 to 6 entries (Side Table, Floor Lamp, Picture Frame added) so the picker has IKEA-showroom variety. iOS registry mirrored 1:1 for future Swift port.

**iOS counterpart not in this PR.** The iOS demo app (`samples/ios-demo`) does not currently have an `ARPlacementDemo.swift` — the iOS V1 didn't port the tap-to-place AR flow. The 3 new `ar_placement` slugs are registered in iOS `SampleAssets.swift` ready for a future port; the iOS demo file itself is deferred. ARKit's `RealityKit.AnchorEntity(plane:)` factory shipped in v4.2.0 ([#1025](https://github.com/sceneview/sceneview/pull/1025)) — the iOS port mostly needs a SwiftUI chip row + the existing resolver glue.

**`SampleAssets` slugs added:** 6 — 3 new `ar_placement` (Side Table, Floor Lamp, Picture Frame) + 2 new `physics` (Wooden Barrel, Clay Amphora) + 1 ([Editor's note: see PhysicsDemo PR](#)) that pairs with the next Stage 2 PR. All CC-BY 4.0.

**30 s screen recording deferred** — agent worktree has no Pixel device access; tracked in the [#1152](https://github.com/sceneview/sceneview/issues/1152) acceptance checklist.

### Changed — Stage 2 demo migrations: `MultiModelDemo` composes the streamed "Park" scene ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

`samples/android-demo/.../demos/MultiModelDemo.kt` swaps its tabletop arrangement of bundled assets (shiba + lantern + helmet + dragon) for the streamed "Park" scene composition — oak tree (backdrop) + park bench (foreground prop) + idle dog + perched songbird, all four resolved through [`SketchfabAssetResolver`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssetsResolver.kt) from the new `park` category of [`SampleAssets`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt).

The composed scene now actually showcases what "multi model" means in practice — a real outdoor vignette where each asset comes from a different author / source / tool, all unified by `studio_warm_2k.hdr` and the shared scene-yaw rotation. The dog + bird carry skeletal animations so the scene reads as alive instead of as a still life. Two models are static (tree, bench), two are animated (dog, bird) — the same 2/2 alive-vs-still ratio the original tabletop had.

Visibility chips kept the same shape (one chip per node) but renamed Tree / Bench / Dog / Bird. The "Spin scene" toggle and the per-model rotation cancellation are unchanged.

Offline behaviour preserved — each streamed slot falls back to its registered bundled GLB / USDZ (Android: `khronos_lantern.glb` for tree + bench, `shiba.glb` for the dog, `animated_dragon.glb` for the bird; iOS: `tree_scene.usdz` / `fantasy_book.usdz` / `animated_butterfly.usdz` / `phoenix_bird.usdz`). The scene composition stays four-distinct-nodes even when offline.

**`SampleAssets` slugs added:** 4 new entries in a new `park` category — Oak Tree (`1ca42d9d…`), Park Bench (`92a4c3ad…`), Idle Dog (`62fadcf9…`), Songbird (`8e7a3a8a…`). All CC-BY 4.0. The `SampleAssetsTest.every Stage 2 category is represented` test now expects `park` in the category set.

**`prefetchAll("park")`** is called from a `LaunchedEffect(Unit)` on first composition so the four streams kick off in parallel before the user has finished reading the controls panel. Each per-node `resolve` later picks up the cached file via the resolver's dedup logic.

**iOS counterpart not in this PR.** The iOS demo app (samples/ios-demo) does not currently have a `MultiModelDemo.swift` — the iOS V1 didn't port the multi-model scene. The 4 `park` slugs are registered in iOS `SampleAssets.swift` ready for a future port, but the Swift demo file itself is deferred.

**30 s screen recording deferred** — agent worktree has no Pixel / iPhone device access; tracked in the [#1152](https://github.com/sceneview/sceneview/issues/1152) acceptance checklist.

### Changed — Stage 2 demo migrations: `AnimationDemo` carousel of 5 animated models from the `animation` category ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

`samples/android-demo/.../demos/AnimationDemo.kt` is no longer locked to a single hard-coded `threejs_soldier.glb`. A new "Subject" chip row above the existing Camera row lets the user cycle through 5 animated models — the bundled soldier (slot 0, preserves the v4.3.1 default for visual stability) plus the four streamed entries of the `animation` category in [`SampleAssets`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt): Walking Robot, Dancing Knight, Idle Cat, Sleeping Fox.

Switching subjects rebinds the play/pause/speed/loop controls + the animation-name chip row to the new model — `playAnimation`/`stopAnimation` use the active model's animation count, so out-of-range indices are clamped automatically when going from a 4-animation soldier to a 1-animation streamed creature. The model lift is now derived from `scaleToUnits` (was hard-coded `position.y = 0.5`), so the feet stay grounded at `y=0` for every model regardless of scale.

Offline behaviour preserved — when `SketchfabConfig.apiKey == null`, each streamed slot falls back to the registered bundled GLB (`threejs_soldier.glb` / `shiba.glb` / `khronos_fox.glb`), so the carousel always has 5 working entries (some may look like duplicates in offline mode, which is the same trade-off Stage 1 documented).

**iOS counterpart skipped this PR.** iOS `AutoRotateDemo.swift` is the iOS V1 stand-in for the Android `AnimationDemo` and renders a non-animated metallic torus — there's no skeletal-rig playback on iOS yet (tracked in the v4.3.0 parity backlog, see [#1004](https://github.com/sceneview/sceneview/issues/1004) iOS parity umbrella). Migrating it requires the iOS skinning port first.

**`SampleAssets` slugs added:** 0. The four `animation` slugs shipped in Stage 1 already.

**30 s screen recording deferred** — agent worktree has no Pixel device access; tracked in the [#1152](https://github.com/sceneview/sceneview/issues/1152) acceptance checklist.

### Added — Stage 2 demo migrations: `MaterialsDemo` streams the curated `materials` category ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

Third Stage 2 migration. The previous `MaterialsDemo` (5-sphere metallic/roughness spectrum) didn't actually exercise any of the modern glTF material extensions — it was a hand-built PBR sweep useful for diagnosing the renderer, not for answering "what does `KHR_materials_sheen` look like in SceneView?". Stage 2 replaces it on both platforms with the curated extension-bearing models from `SampleAssets`'s `materials` category (Iridescent Beetle / Glass Decanter / Velvet Cushion — sheen, transmission, iridescence).

**Why streamed.** Each model carries a glTF extension that depends on the author's source PBR tooling — bundling a hand-authored stand-in would either ship a giant binary (transmission demands a full IBL backdrop) or fake the look (and mislead the AI-first contract). Streaming the real Khronos / community assets keeps the demo honest.

**Files touched:**

- `samples/android-demo/.../demos/MaterialsDemo.kt` (new) — chip row + studio HDR + auto-orbit + per-chip extension tag (the registry's `tags[0]` is the `KHR_materials_*` extension name).
- `samples/android-demo/.../DemoRegistry.kt` — new `materials` entry in the `Advanced` category with the `Icons.Filled.Palette` icon.
- `samples/android-demo/.../MainActivity.kt` — routes `materials` to `MaterialsDemo`.
- `samples/android-demo/src/main/res/values/strings.xml` + `values-fr/strings.xml` — 4 new keys: `demo_materials_title`, `demo_materials_subtitle`, `demo_materials_loading`, `demo_materials_credit`.
- `samples/ios-demo/SceneViewDemo/Views/Demos/MaterialsDemo.swift` — rewrote the 5-sphere PBR sweep as the streamed mirror. Same `materials` category, same chip row + extension tag + author byline, `SketchfabAssetResolver.shared.resolve(slug)` + `ModelNode.load(contentsOf:)`. The existing `SamplesTab` entry already wires up `MaterialsDemo()` — no dispatch change needed.

**`SampleAssets` slugs added:** 0. The three `materials` slugs (Iridescent Beetle, Glass Decanter, Velvet Cushion) shipped in Stage 1 and are now consumed by this PR for the first time.

**i18n hygiene.** All 4 new keys ship in EN + FR. The chip labels are catalogue-authored ids (English-only, per `OrbitalARDemo` convention). The extension tag (`KHR_materials_iridescence` etc.) is a glTF extension name and intentionally not localised — it's a spec identifier developers will Google.

**Screen recording.** Deferred to the combined Stage 2 visual-smoke pass.

**Acceptance:** Android `./gradlew :samples:android-demo:compileDebugKotlin` GREEN. `:samples:android-demo:testDebugUnitTest --tests "io.github.sceneview.demo.sketchfab.*"` GREEN (27/27 unchanged).

### Added — Stage 2 demo migrations: `ModelViewerDemo` gains a "Surprise me" Sketchfab pick ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

Second Stage 2 migration. `ModelViewerDemo` keeps the bundled `khronos_damaged_helmet.glb` as its hero default (so screenshots / Play Store store assets stay byte-identical) and adds an `ExtendedFloatingActionButton` that streams a fresh downloadable Sketchfab model on demand:

- **Default state.** Bundled helmet, same as before. The hero shot the store-page renders promise.
- **Tap "Surprise me".** Calls `SketchfabService.search(query, downloadable = true, limit = 24)` with a small rotating PBR-friendly query list (`pbr` / `modern` / `scan`), filters to `downloadable && faceCount in 1..200_000` (so a 5 M-poly scan doesn't stall the demo), picks a random hit, and downloads it through the shared `SketchfabService` cache. The streamed pick replaces the helmet for the rest of the session until the next tap.
- **No-key build.** The FAB is hidden when `SketchfabConfig.apiKey == null` (App Store / no-secret CI builds) — silently falling back to the same helmet would mislead users about the demo's capability.
- **Failure modes are silent.** A 4xx / 5xx / empty-results path keeps the helmet on screen rather than going black. The `surpriseInFlight` flag flips back to `false` so the user can retry.

**Files touched:**

- `samples/android-demo/.../demos/ModelViewerDemo.kt` — full rewrite of the composable. Adds the FAB, the surprise coroutine, the failure-keeps-helmet contract. Streamed instance scaled to 0.4 m (vs the helmet's historical 0.3 m) so a 5 cm bee and a 5 m crate both read in the orbit sweet spot.
- `samples/android-demo/src/main/res/values/strings.xml` + `values-fr/strings.xml` — 3 new keys: `demo_model_viewer_loading`, `demo_model_viewer_surprise`, `demo_model_viewer_surprise_loading`.

**iOS counterpart.** No iOS file change — there is no dedicated `ModelViewerDemo.swift`. The iOS deep-link router already maps `"model-viewer"` to `SceneGalleryDemo` (`DemoDeepLinkRegistry.swift:77`), which already streams Sketchfab content (now with the Stage 2 gallery migration). The iOS Explore tab is the canonical "browse + surprise" experience on iOS.

**`SampleAssets` slugs added:** 0. The Surprise path doesn't go through the curated registry — it's a free-form Sketchfab search restricted to `downloadable && PBR-friendly`. The license filter on the search side is not yet a 100% guarantee of CC-BY (Sketchfab returns mixed CC variants); Stage 3 will add a license-filter pass before the model lands on screen + a Credits sheet exposing the per-pick attribution.

**i18n hygiene.** All three new FAB strings ship in EN + FR. No raw English leaks on the FR locale.

**Screen recording.** Deferred to the combined Stage 2 visual-smoke pass.

**Acceptance:** Android `./gradlew :samples:android-demo:compileDebugKotlin` GREEN. `:samples:android-demo:testDebugUnitTest --tests "io.github.sceneview.demo.sketchfab.*"` GREEN (27/27 unchanged).

### Added — Stage 2 demo migrations: `SceneGalleryDemo` streams the curated `gallery` category ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

First Stage 2 migration on top of the [Stage 1 resolver foundations](#added--samples-sketchfab-streaming-foundations-1152--stage-1). `SceneGalleryDemo` is now a category-chip-driven streamed gallery on both Android and iOS — chips map 1:1 to the four `gallery` slugs in [`SampleAssets`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt) (Vintage Cassette, Polly the Parrot, Reading Lamp, Wooden Chair), the resolver hands back the streamed GLB/USDZ or the bundled fallback when no key is configured, and `SceneView` orbits the model. No external Sketchfab WebView — the demo only ever feeds the local file URL to `rememberModelInstance` (Android) / `ModelNode.load(contentsOf:)` (iOS).

**Files touched:**

- `samples/android-demo/.../demos/SceneGalleryDemo.kt` (new) — streams the four `gallery` slugs via `SketchfabAssetResolver`, warms the category on first frame with `prefetchAll("gallery")`, orbit camera, inline CC-BY author byline.
- `samples/android-demo/.../DemoRegistry.kt` — new `scene-gallery` entry in the `3D Basics` category with the `Icons.Filled.Collections` icon.
- `samples/android-demo/.../MainActivity.kt` — routes `scene-gallery` to `SceneGalleryDemo`.
- `samples/android-demo/src/main/res/values/strings.xml` + `values-fr/strings.xml` — 4 new keys: `demo_scene_gallery_title`, `demo_scene_gallery_subtitle`, `demo_scene_gallery_loading`, `demo_scene_gallery_credit` (used for `"by %s · CC-BY 4.0"`). The chip labels themselves come from the catalogue's `SketchfabSlug.displayName` (curator-authored English ids, not localizable copy).
- `samples/ios-demo/SceneViewDemo/Views/Demos/SceneGalleryDemo.swift` — rewrote the placeholder shape-pedestal scene as the cross-platform mirror: same `gallery` category, same chip row + author byline, `SketchfabAssetResolver.shared.resolve(slug)` + `ModelNode.load(contentsOf:)`, `prefetchAll(category:)` warm, error path surfaces the resolver's `localizedDescription` rather than failing silently.

**`SampleAssets` slugs added:** 0. The four `gallery` slugs (Vintage Cassette, Polly the Parrot, Reading Lamp, Wooden Chair) shipped in Stage 1 already and are now consumed by this PR for the first time.

**i18n hygiene.** The chip labels render `SketchfabSlug.displayName` directly — those strings are curator-authored Sketchfab catalogue ids (English-only, like the `OrbitalARDemo` planet labels) and don't go through `stringResource()`. All demo scaffolding (title, subtitle, loading copy, attribution caption) goes through the new `demo_scene_gallery_*` keys in both `values/` and `values-fr/`. No raw English string leaks into a non-English locale.

**Screen recording.** Deferred to the visual-smoke pass at the end of Stage 2 (one combined recording covering all three Stage 2 demos in this batch). Compile + unit tests gated this PR.

**Acceptance:** Android `./gradlew :samples:android-demo:compileDebugKotlin` GREEN. `:samples:android-demo:testDebugUnitTest --tests "io.github.sceneview.demo.sketchfab.*"` GREEN (27 sketchfab tests passing unchanged from Stage 1). iOS `xcodebuild` skipped in this batch (CHANGELOG entry kept honest — Stage 1 ran the Xcode build; the SceneGalleryDemo iOS rewrite is a small file replacement with no new Swift symbols).

### Changed — Stage 2 demo migrations: `OrbitalARDemo` streams 4 animated creatures from the `solar` category ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 2)

`samples/android-demo/.../demos/OrbitalARDemo.kt` + `samples/ios-demo/SceneViewDemo/Views/Demos/OrbitalARDemo.swift` now stream four of their eight orbiting planets via [`SketchfabAssetResolver`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SketchfabAssetResolver.kt) from the `solar` category of [`SampleAssets`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt) — butterfly, hummingbird, bee, koi fish. The remaining four planets (`khronos_damaged_helmet`, `khronos_lantern`, `khronos_toy_car`, `animated_dragon` on Android; `red_car`, `game_boy_classic`, `animated_dragon`, `nintendo_switch` on iOS) stay bundled.

Before: the 7-planet formation had to duplicate `animated_dragon` + `threejs_soldier` to fill the ring because only seven distinct GLBs ship in the APK — visible as "clones" in the [#978](https://github.com/sceneview/sceneview/issues/978) audit screenshot. After: eight distinct themed planets, every "alive" slot has a real baked animation, and Sketchfab is invisible to the user (no WebView, no "loading Sketchfab" UI — just `rememberModelInstance(modelLoader, "file://...")` once the resolver returns).

Offline behaviour preserved — when `SketchfabConfig.apiKey == null` (App Store builds, cold-cache first launch, network down), each streamed slot falls back to its registered bundled GLB / USDZ, so the orbit always renders eight models. No "Asset unavailable" placeholder ever surfaces from this demo.

**`SampleAssets` slugs added:** 0. The four `solar` slugs shipped in Stage 1 already and are consumed by this PR for the first time.

**30 s screen recording deferred** — agent worktree has no Pixel / iPhone device access; tracked in the [#1152](https://github.com/sceneview/sceneview/issues/1152) acceptance checklist.

### Added — Samples Sketchfab streaming foundations ([#1152](https://github.com/sceneview/sceneview/issues/1152) — Stage 1)

Stage 1 of the [`#1152`](https://github.com/sceneview/sceneview/issues/1152) umbrella — `SketchfabAssetResolver` foundations that the Stage 2 demo migrations (`OrbitalARDemo`, `SceneGalleryDemo`, `AnimationDemo`, `MultiModelDemo`, `ARPlacementDemo`, `PhysicsDemo`, `MaterialsDemo`) will build on. **No demo is migrated in this PR** — the bundled GLBs/USDZs stay as they are. The resolver, registry, and tests are the foundation; demo migrations land 1 PR per demo.

**New files (Android — `samples/android-demo/.../sketchfab/`):**

- [`SketchfabSlug.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SketchfabSlug.kt) — typed slug + license + scale + animation + category + author + tags. Constructor rejects any non-CC-BY 4.0 license URL, a blank author, an empty fallback path, or a non-positive scale.
- [`SampleAssets.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt) — 20-entry curated CC-BY-only registry grouped into 6 Stage 2 categories: `solar` (4), `gallery` (4), `animation` (4), `ar_placement` (3), `physics` (2), `materials` (3). `byUid` / `byCategory` lookups + `requireValid()` for CI invariants (no duplicate uids, every uid is 32-char lowercase hex).
- [`SketchfabAssetResolver.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SketchfabAssetResolver.kt) — `resolve(slug)` / `prefetchAll(category)` / LRU eviction (250 MB cap, tighter than the Explore-tab 500 MB cap) / bounds sanity check (magic-byte + size floor) / fallback-to-bundle when no key OR network fails. Wraps `SketchfabService` with exponential backoff (429/5xx only, max 3 retries) and falls back immediately on policy-decision 4xx.

**New files (iOS — `samples/ios-demo/SceneViewDemo/Services/`):**

- [`SketchfabSlug.swift`](samples/ios-demo/SceneViewDemo/Services/SketchfabSlug.swift), [`SampleAssets.swift`](samples/ios-demo/SceneViewDemo/Services/SampleAssets.swift), [`SketchfabAssetResolver.swift`](samples/ios-demo/SceneViewDemo/Services/SketchfabAssetResolver.swift) — same 20-uid registry, same resolver semantics, RealityKit-compatible (accepts both GLB `glTF` magic and USDZ ZIP `PK\x03\x04` magic in the bounds check). `actor` for the `URLSession` serialisation invariant that matches `SketchfabService`.
- [`SketchfabAssetResolver+Tests.swift`](samples/ios-demo/SceneViewDemo/Services/SketchfabAssetResolver+Tests.swift) — XCTest mirror of the Android suite (no live `xcodebuild test` target wires it up yet; the file lives next to the existing `SketchfabService+Tests.swift` scaffold for documentation parity).

**Tests (Android — 24 new unit tests, all passing):**

- [`SampleAssetsTest.kt`](samples/android-demo/src/test/java/io/github/sceneview/demo/sketchfab/SampleAssetsTest.kt) — 13 tests: registry non-empty, every entry CC-BY 4.0, every entry has a non-blank author, every entry has a fallback, scale in `[0.05 m, 5 m]`, no duplicate uids, `requireValid` succeeds, `byUid`/`byCategory` agree with `all`, all 6 Stage 2 categories represented, constructor rejects non-CC-BY / blank author / non-positive scale.
- [`SketchfabAssetResolverTest.kt`](samples/android-demo/src/test/java/io/github/sceneview/demo/sketchfab/SketchfabAssetResolverTest.kt) — 11 tests: `resolve` falls back without an API key, `Unknown` for slugs outside the registry, `boundsAreSane` rejects 0-byte/junk/missing files and accepts a real GLB header, `pruneCache` is a no-op sub-budget, `FallbackUnavailable` when the bundled asset is missing, `prefetchAll` returns 0 for unknown categories, singleton wiring.

**Hard rules honoured (Stage 1 = pure plumbing):**

- **NEVER ship a build that needs the network to render something useful.** Every `SketchfabSlug` carries a `fallbackBundledPath` that already lives in the demo APK / IPA. The resolver returns it whenever the API key is absent (App Store builds), the network fails, or the streamed asset fails the magic-byte sanity check.
- **NEVER open a Sketchfab WebView / external link.** The resolver returns a local `File` / `URL` only; consumers feed it into `rememberModelInstance(modelLoader, file)` / RealityKit `Entity.load(...)`.
- **CC-BY only.** Every entry's `licenseUrl` is `https://creativecommons.org/licenses/by/4.0/`. Other Creative Commons variants (NC, ND, SA) and the bespoke "Sketchfab Standard" license are rejected by `SketchfabSlug.init`.
- **Cache survives across demos.** Resolver uses the same `cacheDir/sketchfab/` directory as `SketchfabService`, so a model warmed by the Explore tab is reused by Stage 2 demos.

**Stage 1 status note.** The 20 placeholder uids in `SampleAssets` were curated at design time but are not yet validated against `GET /v3/models/<uid>`. Stage 2 PRs will replace each uid with one verified live (Sketchfab maintainer account check) AND add a weekly CI cron that pings each slug + opens a GitHub issue on 404 / license drift. The `licenseURL` + `fallbackBundledPath` columns are authoritative even today — they decide what the resolver hands a demo offline.

Acceptance: Android `./gradlew :samples:android-demo:compileDebugKotlin` + `:samples:android-demo:testDebugUnitTest --tests "io.github.sceneview.demo.sketchfab.*"` GREEN (27 sketchfab tests passing — 24 new + 3 pre-existing). iOS `xcodebuild -scheme SceneViewDemo … build` GREEN (3 new Swift files compile, project added them to the SceneViewDemo target).

### Fixed — iOS: `SKETCHFAB_API_KEY` never reached TestFlight + App Store binaries ([#1157](https://github.com/sceneview/sceneview/issues/1157))

Every iOS app-store ship since v3.6 silently degraded the Explore tab to bundled fallback models because the Sketchfab API key never made it into the `.ipa`. Two compounding root causes:

- **`SketchfabConfig.swift` read the key via `ProcessInfo.processInfo.environment["SKETCHFAB_API_KEY"]`** — that path only works under Xcode's "Run" scheme. CI env vars set on the runner don't survive `xcodebuild archive` into the shipped binary, so `SketchfabConfig.apiKey == nil` for every TestFlight + App Store build → `SketchfabError.missingApiKey` → `ExploreTab` `runCatching` swallow → empty / fallback results with no error banner.
- **`.github/workflows/app-store.yml` and `ios.yml` never referenced `SKETCHFAB_API_KEY`** — confirmed by `grep`. The Android pipelines (`play-store.yml:170`, `build-apks.yml:47`) inject the secret correctly and Android's `BuildConfig.SKETCHFAB_API_KEY` bakes it in at compile time, which is why Play Store builds were unaffected.

Fix (single PR, 4 files):

- [`samples/ios-demo/SceneViewDemo/Services/SketchfabConfig.swift`](samples/ios-demo/SceneViewDemo/Services/SketchfabConfig.swift) — `apiKey` now resolves from `Bundle.main.object(forInfoDictionaryKey: "SketchfabAPIKey")` first, with a guard that rejects the unsubstituted `$(SKETCHFAB_API_KEY)` xcconfig token literal. Legacy `ProcessInfo` lookup stays as a fallback so the Xcode "Run" scheme env-var workflow keeps working for contributors.
- [`samples/ios-demo/SceneViewDemo/Info.plist`](samples/ios-demo/SceneViewDemo/Info.plist) — added `SketchfabAPIKey = $(SKETCHFAB_API_KEY)` placeholder. `xcodebuild` substitutes it from the user-defined build setting at archive time.
- [`.github/workflows/app-store.yml`](.github/workflows/app-store.yml) — both iOS and macOS `xcodebuild archive` steps now pass `SKETCHFAB_API_KEY="$SKETCHFAB_API_KEY"` (sourced from the `SKETCHFAB_API_KEY` repo secret).
- [`.github/workflows/ios.yml`](.github/workflows/ios.yml) — same injection on the CI demo-build step so the `Info.plist` substitution path is exercised on every PR, not just on release tags.

Verified locally on Xcode 26.3 / iPhone 16e simulator: `xcodebuild build … SKETCHFAB_API_KEY=dummy_key_for_test` produces a `SceneViewDemo.app/Info.plist` with `SketchfabAPIKey = dummy_key_for_test` (vs. the literal `$(SKETCHFAB_API_KEY)` placeholder without the build setting). Acceptance: next TestFlight build of v4.3.2+ surfaces non-empty `SketchfabConfig.apiKey` and `ExploreTab` shows live Sketchfab categories + search.

Long-term proxy via `mcp-gateway` so end-user binaries don't ship the master key is tracked by the V1.1 TODO in `SketchfabConfig.swift` — this fix is the immediate "Explore tab works again" patch.

### Tests — Regression pins for v4.3.0 rendering-burst fixes that shipped without coverage ([#1120](https://github.com/sceneview/sceneview/issues/1120) extension)

Follow-up to the CORR-C regression-pin batch ([PR #1137](https://github.com/sceneview/sceneview/pull/1137)). Three of the v4.3.0 fixes shipped without test coverage because the failure modes required Filament JNI (CORR-C's pure-JVM batch couldn't reach them). This extension adds the missing instrumented tests so a future refactor catches the regression at `./gradlew :connectedDebugAndroidTest` time:

- **`sceneview/src/androidTest/.../RenderQualityComposeTest.kt`** — Filament-grounded companion to the JVM `RenderQualityLaunchedEffectTest`. Pins the [#1078](https://github.com/sceneview/sceneview/issues/1078) keyed-`LaunchedEffect(view, renderQuality)` contract using a real `View`: apply the preset, mutate `view.bloomOptions.strength = 0.4f`, simulate 5 unchanged recompositions, assert the user tweak survived. Pre-#1078 (unkeyed `SideEffect`), the 0.4f would have been clobbered back to the preset value on every recomposition. 3 test methods. The two pure-JVM `RenderQualityLaunchedEffectTest` + instrumented `RenderQualityComposeTest` cover the contract from both angles — JVM catches the LaunchedEffect re-keying semantics, instrumented catches the Filament-side preset-application invariants.
- **`sceneview/src/androidTest/.../node/CameraNodeLifecycleTest.kt`** — Pins the `DisposableEffect(cameraNode)` rewire shipped in [PR #1147](https://github.com/sceneview/sceneview/pull/1147) (`Scene.kt:293`, closes [#1143](https://github.com/sceneview/sceneview/issues/1143)). Three tests: 5 sequential `SceneNodeManager` lifecycles sharing one Filament `Scene` leak zero cameras, parent → child HUD-node propagation cascades on dispose, and the `cameraNode` swap path replaces cleanly without leaking the previous instance. Same-family check as the [#1122](https://github.com/sceneview/sceneview/issues/1122) light-node leak fix (PR [#1131](https://github.com/sceneview/sceneview/pull/1131)).
- **`samples/android-demo/src/androidTest/.../MaterialInstanceLeakTest.kt`** — Pins the `destroyMaterialsOnDispose: Boolean = false` flag added to `RenderableNode` + `GeometryNode` constructors in PR [#1132](https://github.com/sceneview/sceneview/pull/1132) (closes [#1123](https://github.com/sceneview/sceneview/issues/1123)). Four tests: the flag actually destroys the constructor-passed `MaterialInstance` (its `nativeObject` handle drops to `0`), default `false` preserves the instance for external owners (`rememberMaterialInstance`, `DisposableEffect`), multi-primitive lists with `null` entries are handled without NPE, and the destroy path is idempotent across double-destroy via the `runCatching`-wrapped `safeDestroyMaterialInstance`.
- **`arsceneview/src/androidTest/.../light/LightEstimatorConcurrentDestroyTest.kt`** — already shipped as part of [PR #1148](https://github.com/sceneview/sceneview/pull/1148) ([#1094](https://github.com/sceneview/sceneview/issues/1094) acceptance #3); listed here for traceability.

The pure-JVM `RenderQualityLaunchedEffectTest` and `LightEstimatorConcurrentDestroyTest` from CORR-C continue to run on every `:sceneview:test` invocation; the instrumented tests above run on `./gradlew :sceneview:connectedDebugAndroidTest` / `:samples:android-demo:connectedDebugAndroidTest`. Net +3 instrumented test files / +10 test methods.

#1123 acceptance criterion "at least 1 demo migrated to use `destroyMaterialsOnDispose = true`" stays open — surfacing the flag through the Compose `SceneScope.CubeNode` / `SphereNode` / etc. factories is a separate API extension. `MaterialInstanceLeakTest` pins the library-level contract those factories will eventually wire up.

## v4.3.1 — CI hardening + iOS AR LightSlot parity + i18n migration (2026-05-14)

CI hardening + docs accuracy + Android CLI migration + one v4.1.0-stale demo light tune,
plus the second half of #1063 ported to iOS (`LightSlot` + `.fillLight(_:)` on `ARSceneView`)
and a full `android-demo` UI migration to `stringResource(R.string.…)` so French locale
actually flips at runtime. No new Android public API; one new iOS surface.

### Changed — Demo UX: `DemoScaffold` v2 ships the controls in a `ModalBottomSheet` ([#1154](https://github.com/sceneview/sceneview/issues/1154))

The 35 Android demos no longer split their viewport 60 / 40 between scene and a side-panel of controls. The scene now fills the entire area below the top app bar, and the per-demo `controls = { ... }` block is rendered inside a Material 3 `ModalBottomSheet` launched by a "Tune" `FloatingActionButton` anchored bottom-end of the scene. A semi-transparent "Settings" peek chip sits above the FAB while the sheet is closed to advertise the gesture.

- The 35 demo call-sites stay byte-identical: `DemoScaffold(title = …, controls = { … }, scene = { … })` — only the placement of the controls panel has changed.
- The sheet supports the partial detent (`skipPartiallyExpanded = false`); drag-down, outside-tap, and back gesture all dismiss it.
- AR demos keep tracking 6DOF while the sheet is at the partial detent — opening the sheet does **not** pause the AR session.
- Long-press the peek chip toggles `DemoSettings.qaMode` (was previously a long-press on the top-app-bar title). The QA escape-hatch pill in the title bar stays unchanged.
- New `DemoScaffoldTestTags` object exposes stable testTags (`demo-settings-fab`, `demo-settings-peek`, `demo-settings-sheet`, `demo-qa-pill`) consumed by `DemoInteractionTest` and any future visual smoke tooling.
- `samples/android-demo/.../DemoInteractionTest.kt` lazy-opens the sheet inside `tap()` / `tapByDesc()` / `dragSlider()` / `typeInto()` when the target chip / slider isn't already visible — the 31 existing instrumentation tests work unchanged.
- iOS — new `.demoSettingsSheet { … }` View modifier (`samples/ios-demo/SceneViewDemo/Views/Components/DemoSheet.swift`) mirrors the Android pattern: `.presentationDetents([.fraction(0.25), .medium, .large])`, `.presentationBackgroundInteraction(.enabled)` so AR stays live at the partial detent, and `.presentationBackground(.ultraThinMaterial)` for Liquid Glass. 4 demos migrated: `FogDemo`, `DynamicSkyDemo`, `MovableLightDemo` (drag-anywhere gesture preserved), `CameraControlsDemo` (was `OrbitCameraDemo`).

Visual result: scene takes ~95 % of the viewport at the default detent on Pixel 9 vs ~60 % under v4.3.0. Documented design rationale: M3 spec for bottom sheets, HIG for `.sheet()` with `presentationDetents`. Implements the plan recorded in `plan_demo_settings_bottom_sheet` (Stage 1 + 2 of 4 — polish + AI-first docs stages are tracked separately).

### Fixed — release.yml: Dokka config-cache crash + GitHub Release decoupled from Dokka ([#1150](https://github.com/sceneview/sceneview/issues/1150))

The v4.3.0 cut surfaced two latent `release.yml` issues that skipped the `Create GitHub Release` job (recovered manually):

- **Dokka step now passes `--no-configuration-cache`** — Dokka 1.x's `dokkaSourceSets` `FactoryNamedDomainObjectContainer` cannot be deserialized from the Gradle configuration cache, so the step crashed on `release.yml` run `25870464897`. The `--retry-with-backoff` wrapper from #1127 was a no-op because the error was config-cache deserialization, not a 503. Pin Dokka out of the config cache so config-cache stays enabled globally for the rest of the build.
- **`create-release` job no longer veto-gated on `publish-api-docs`** — Maven Central + 3 npm packages + SPM tag are user-visible artifacts; Dokka HTML is secondary (users can still consume libraries on `mvnrepository` / `npm` without fresh API docs on the tag). A Dokka failure on a release tag now produces a workflow red X on the Dokka job but the GitHub Release still cuts.

### Fixed — `IBLPrefilter.specularFilter` KDoc cost mismatch with `LightEstimator` ([#1103](https://github.com/sceneview/sceneview/issues/1103))

The two KDocs disagreed by 10× on the same operation. Both are now accurate and cross-referenced:

- [`IBLPrefilter.specularFilter`](sceneview/src/main/java/io/github/sceneview/environment/IBLPrefilter.kt) — clarified that cost scales with cubemap face count + resolution. First-build of a 1024×1024×6 HDR skybox runs 100–200 ms (the historical figure); incremental update of a 16×16×6 ARCore cubemap (the AR path) runs 5–15 ms on a Pixel 9.
- [`LightEstimator.environmentalHdrSpecularFilter`](arsceneview/src/main/java/io/github/sceneview/ar/light/LightEstimator.kt) — cross-references the matrix in `IBLPrefilter.specularFilter` instead of contradicting it.

Documentation-only — no behavioral change.

### Fixed — `GeometryDemo` stacked 80 000-lux on v4.1.0 default lights ([#1146](https://github.com/sceneview/sceneview/issues/1146))

Sibling of [#1125](https://github.com/sceneview/sceneview/issues/1125) (PhysicsDemo). [`samples/android-demo/.../GeometryDemo.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/demos/GeometryDemo.kt) added a 80 000-lux directional light on top of the v4.1.0 SceneView defaults (10 000-lux main + 3 000-lux fill + IBL @ 10 000), so the metallic/roughness sweep saturated to white at every slider value. Re-tuned to 5 000 lux to match PhysicsDemo's PR [#1144](https://github.com/sceneview/sceneview/pull/1144) retune — accent fill that complements the v4.1.0 defaults without dominating them. Acceptance #1125 only scanned for `100_000`, so 80 000 slipped through; this closes the gap.

### Tooling — Android CLI migration: purge legacy raw `adb` from install/launch paths

Follow-up to the May 2026 [`feedback_android_cli_only`](https://developer.android.com/tools/agents/android-cli) rule. Multiple shell scripts still drove `adb install` + `am start` directly instead of the atomic `android run --apks=… --activity=…` path exposed by Google's `android` CLI v0.7. That kept the legacy `adb` PATH dependency as a hard requirement and surfaced as visual-QA failures on hosts where only the `android` CLI was installed.

- [`.claude/scripts/qa-android-demos.sh`](.claude/scripts/qa-android-demos.sh) — `--install` branch now calls `android_cli_install_and_launch` (atomic install+launch via `android run`) with an `adb install -r` fallback when the CLI is missing.
- [`.claude/scripts/capture-play-store-screenshots.sh`](.claude/scripts/capture-play-store-screenshots.sh) — initial APK install uses `android_cli_install_and_launch` on single-device hosts; falls back to `adb install -r` on multi-device hosts (the `android run` subcommand has no `--device` flag in v0.7). The per-iteration `am force-stop` + `am start --es demo <id>` block stays on `adb` (legit holdout — `android run` v0.7 has no intent-extras forwarding).
- [`tools/try-demo.sh`](tools/try-demo.sh) — `check_device` now accepts either `android` or `adb` on PATH (and surfaces both install hints when neither is present). Already wired to `android_cli_install_and_launch` since the helper landed.
- [`.claude/scripts/visual-check.sh`](.claude/scripts/visual-check.sh) — annotated the bottom-nav tap coordinates to flag them as legit `adb` holdouts (no input-event API in `android` CLI v0.7).
- [`sceneview/src/androidTest/.../VisualVerificationTest.kt`](sceneview/src/androidTest/java/io/github/sceneview/render/VisualVerificationTest.kt) — KDoc now states explicitly that `adb pull` is the only operation here without an `android` CLI equivalent as of v0.7.
- [`docs/docs/try.md`](docs/docs/try.md) — terminal-install snippet now shows `android run` first (atomic install+launch) and keeps `adb install -r` as the legacy alternative.

Acceptance: every legit `adb` holdout (no `android` CLI equivalent in v0.7 — `pull`, `logcat`, `input tap/swipe/keyevent`, `am force-stop`, `am start --es`, `wait-for-device`, `get-state`, `devices`, `kill-server`, `dumpsys`, `pidof`, `uiautomator dump`) is annotated in-place. Re-evaluate when `android` CLI v0.8+ ships any of those subcommands. No behavioural change for end users; CI `render-tests.yml` was already migrated by [#1153](https://github.com/sceneview/sceneview/issues/1153).

### Fixed — CI: `android-demo-screenshots` job unblocked + workflow validator hardened ([#1153](https://github.com/sceneview/sceneview/issues/1153))

The v4.3.0 cut commit `efc168bc` introduced a multi-line backslash continuation in `.github/workflows/render-tests.yml` (the `android run \\ --apks=…` block under the `Capture demo screenshots` step). The `ReactiveCircus/android-emulator-runner@v2` action exec's each line of `with.script:` via `sh -c <line>`, so the trailing `\` survives as a literal argv token and `android run` died with `Unmatched argument at index 2: '\\'`. Every push to `main` since `efc168bc` failed that screenshot job, forcing chip PRs ([#1145](https://github.com/sceneview/sceneview/pull/1145) / [#1147](https://github.com/sceneview/sceneview/pull/1147) / [#1148](https://github.com/sceneview/sceneview/pull/1148) / [#1149](https://github.com/sceneview/sceneview/pull/1149)) to merge with `--admin` and hiding any genuine screenshot regression.

- **Fix**: collapse the `android --no-metrics run …` invocation onto a single physical line, matching the documented per-line slicing rule already followed by the `attempts=0; while …; done` loop above it.
- **Validator extension**: `.claude/scripts/check-workflow-scripts.sh` (shipped by [#1145](https://github.com/sceneview/sceneview/pull/1145)) now runs a per-line slicing simulation on every `with.script:` block — `dash -n` passes a `\<EOL>` because the whole-file parser splices continuations together first, but the runtime action does not. The new pass flags any trailing-backslash continuation and fails the PR check, so this class of bug can no longer ship to `main` undetected. Sanity-tested by reintroducing the original break locally — validator exits `1` with a pointed error message.
- **Backwards compatibility**: `run:` blocks (which GitHub Actions defaults to `bash -e {0}`, executed as one script) are untouched; backslash continuations remain valid there. Only `with.script:` blocks (per-line `sh -c` semantics) are checked.

### Fixed — i18n: migrate `android-demo` UI to `stringResource(R.string.…)` ([#1099](https://github.com/sceneview/sceneview/issues/1099), closes [#955](https://github.com/sceneview/sceneview/issues/955))

PR [#1073](https://github.com/sceneview/sceneview/pull/1073) added `samples/android-demo/src/main/res/values-fr/strings.xml` (164 keys) but the Compose UI never read them — every `Text("…")` was a hardcoded English literal, so switching the device locale to French at runtime had zero visible effect.

This PR fully closes [#955](https://github.com/sceneview/sceneview/issues/955) by migrating every public-facing UI surface to `stringResource(R.string.…)`:

- **`DemoEntry` data class refactor** — `title: String, subtitle: String` → `@StringRes titleRes: Int, @StringRes subtitleRes: Int`. The `category` field stays a stable non-translated key (used as map key + accent-colour lookup) with a parallel `categoryDisplayNameRes(category)` helper that returns the localized header.
- **37-demo registry rewritten** to thread `R.string.demo_*_title` / `R.string.demo_*_subtitle` IDs through to the Samples grid and the Explore "Try a sample" carousel.
- **39 per-demo `DemoScaffold(title = "…")` callsites** migrated to `stringResource(R.string.demo_*_title)` — every demo's `TopAppBar` title now follows the active locale.
- **Top-level UI surfaces migrated**: `RootScreen.kt` (4 tab labels, About-tab 6 cards + hero tagline + footer + Star CTA), `ArViewTab.kt` (full launcher screen — status messages, CTA labels, featured-demo card titles, status pill, model picker, share toast, tracking-failure friendly names), `DemoListScreen.kt` (Samples title, "About" action, status chips, footer), `DemoScaffold.kt` (back-button content description), `MainActivity.kt` (`PlaceholderDemo` "Coming soon" + entry title fallback), `ExploreTabScreen.kt` (Explore heading, search placeholder, Animated filter chip, all carousel section titles, Categories, Recent searches, Clear, Remove $query), `SketchfabModelViewerScreen.kt` (Animated pill, Open-in-SceneView CTA, loading / streaming / rendered-by labels, error screen + Try again, download-failed fallback).
- **`strings.xml` expanded** from 164 → 270+ keys, covering every public-facing UI string in the priority surfaces. FR `values-fr/strings.xml` mirrors 1-to-1.
- **Locale-flip verified end-to-end** on Pixel_7a emulator using Android 13+ per-app locale (`adb shell cmd locale set-app-locales io.github.sceneview.demo --locales fr-FR`). 4 tabs + AR launcher + Samples list + a demo AppBar all flip between EN ⇄ FR, with no regressions. Sketchfab category chips still come from `SketchfabCategories.kt` and stay English — out of scope for #1099, separate larger refactor.
- **Existing legacy keys preserved** (e.g. `demo_lighting`, `demo_geometry`, etc.) for backwards compatibility with any external consumer holding refs to them.
- **`DeepLinkRouterTest.kt`** updated to pass `R.string.*` IDs instead of literal `"Title", "Subtitle"` strings — title / subtitle are not part of the route, so any pair satisfies the type.

Build green: `:samples:android-demo:compileDebugKotlin` + `:assembleDebug` + `:testDebugUnitTest` + `:sceneview:compileReleaseKotlin` + `:arsceneview:compileReleaseKotlin` all succeed locally.

### Added — iOS parity: `LightSlot` + `.fillLight(_:)` on `ARSceneView` ([#1138](https://github.com/sceneview/sceneview/issues/1138))

Port the second half of Android v4.3.0's `#1063` (dual-light AR baseline + `ENVIRONMENTAL_HDR` default) to `SceneViewSwift.ARSceneView`. The 3D `SceneView` already shipped these in v4.2.0 (`#1016`); AR was the missing surface.

- **`.mainLight(_:)` / `.fillLight(_:)` modifiers on `ARSceneView`** — same `LightSlot` enum as the 3D `SceneView`. Default `.systemDefault` provisions a `10 000`-lux directional main + a `3 000`-lux fill, matching Android's `ARSceneView(mainLightNode = …, fillLightNode = …)` defaults.
- **Reactive swap path** — when the caller mutates the modifier value, the previous light's `AnchorEntity` is removed from `arView.scene` and a new one is added in its place. Mirrors `Scene.kt:540`'s `prevFillLightRef` diff pattern. No full RealityView teardown.
- **`ENVIRONMENTAL_HDR` parity documented** — `config.environmentTexturing = .automatic` (already set, now annotated) is the ARKit equivalent of ARCore's `Config.LightEstimationMode.ENVIRONMENTAL_HDR`. Both drive PBR cubemap reflections for runtime-built environment probes; neither exposes a per-frame directional light estimate on `fillLight`.
- **Tests**: 9 pinning tests in `ARSceneViewTests.swift` (default slots, modifier copy-semantics, `.disabled` round-trip, `.custom(LightNode)` entity-identity retention, last-modifier-wins, chaining with `.cameraExposure` + `.onSessionStarted`).
- **Docs sync**: `docs/docs/cheatsheet-ios.md` AR section + Android↔Apple mapping table; `llms.txt` (root + `docs/docs`) ARSceneView signature + LightSlot notes.

## v4.3.0 — Android rendering pipeline overhaul + iOS CameraControls.pan/.firstPerson + ARRecorder + parity table (2026-05-14)

**Status**: shipped. 14-PR Android rendering audit (#1062 → #1142) hardens AR + 3D
defaults, fixes 6 pre-v3 BLOCKERs (multiplicative light drift, AR IBL missing,
SH coefficient swap, Box ray-parallel, spherePlaneResponse contact wrong-side,
AR cubemap GEN_MIPMAPPABLE). Also closes the last #928 silent-stub item and the
biggest v4.2.0 UX gap on iOS demos. PRs [#1038](https://github.com/sceneview/sceneview/pull/1038), [#1042](https://github.com/sceneview/sceneview/pull/1042), and the
[#1131](https://github.com/sceneview/sceneview/pull/1131)–[#1142](https://github.com/sceneview/sceneview/pull/1142) rendering + math audit batch.

### Added — iOS `ARRecorder` record-only via ReplayKit ([#1032](https://github.com/sceneview/sceneview/issues/1032))

Android has had full `ARRecorder` (capture + replay) since v4.0.8 via ARCore's `Session.startRecording(RecordingConfig)`. ARKit on iOS does not expose a deterministic playback dataset, so iOS gets the **record** half via `ReplayKit.RPScreenRecorder` and replay stays Android-only.

- **`ARRecorder` `@MainActor` `ObservableObject`** — `state: .idle / .recording / .error(message)`, `lastOutputURL`, `isRecording` (`@Published`-derived), `isAvailable`.
- **`async throws` API** — `startRecording() async throws`, `stopRecording(outputURL: URL? = nil) async throws -> URL`. Bridges ReplayKit's completion-handler API to async/await.
- **Typed error mapping** — `ARRecorderError.{permissionDenied, disabled, unavailable, alreadyRecording, notRecording, other(code:), photoLibraryDenied, photoLibrarySaveFailed}` so callers can switch on the case (no string-matching `errorDescription`).
- **`ARRecorder.remembered()` factory** — mirrors Android's `rememberARRecorder()` for code-generation symmetry.
- **`ARRecorder.saveToPhotoLibrary(_:)` static helper** ([#1043 item 2](https://github.com/sceneview/sceneview/issues/1043)) — wraps `PHPhotoLibrary.performChanges` so the recorded `.mov` can be copied into the user's Photos library. Mirrors Android's `ARRecorder.exportToDownloads()`. Requires `NSPhotoLibraryAddUsageDescription` in the host app's `Info.plist`. Demo gets a "Save to Photos" button alongside `ShareLink`.
- **What's recorded**: screen pixels only (NOT ARSession state). The `.mov` plays back in Photos / QuickTime; it cannot be fed back into `ARSession` for deterministic replay. Use [`RerunBridge`](https://github.com/sceneview/sceneview/blob/main/SceneViewSwift/Sources/SceneViewSwift/Rerun/RerunBridge.swift) for replay-driven testing.
- **iOS demo**: `samples/ios-demo/.../ARRecorderDemo.swift` mirrors Android's `ARRecordPlaybackDemo` with a record-only banner + live AR session + tap-to-place markers + "Save to Photos" + `ShareLink` for the captured `.mov`. Registered in the AR section of `SamplesTab`.
- **Tests**: 17 pinning tests in `ARRecorderTests.swift` (state machine, error code mapping, default URL placement under `.cachesDirectory/ARRecorder/`, factory smoke, photo-library missing-file guard, photo-library error Equatable + localized description).

### Added — `CameraControls.pan` + `.firstPerson` wired ([#1034](https://github.com/sceneview/sceneview/issues/1034))

Previously, calling `.cameraControls(.pan)` or `.cameraControls(.firstPerson)` produced orbit behaviour because `applyCamera()` ignored the mode and `pinchGesture` always dollied the orbit radius. Three things shipped:

- **`.pan`**: drag translates the orbit `target` along the camera-aligned right + up vectors (the scene appears to slide), pinch keeps dollying.
- **`.firstPerson`**: drag rotates the view, no orbit translation; pinch adjusts the perspective camera's `fieldOfViewInDegrees` — mirrors Android `FovZoomCameraManipulator` (range `10°..120°`, default `60°`).
- **Mode picker in iOS demo**: `CameraControlsDemo` gets a 3-way `Picker` segment so the v4.3.0 wiring can be felt at a glance.

New `CameraControls` properties: `panSpeed`, `moveSpeed`, `fov`, `minFov`, `maxFov`, `pinchFovSpeed`.

Gesture divergence from Android (documented in `CameraControlMode.pan` doc-comment): iOS uses 1-finger drag for pan; Android disambiguates via 2-finger strafe.

### Added — Library-level auto-center content ([#1026](https://github.com/sceneview/sceneview/issues/1026))

iOS demos placing content at e.g. `z = -2` rendered in the bottom-third of the viewport because the default perspective camera at `[0, 0.3, 2]` looks at world origin. Auto-center via intermediate `contentRoot` entity translates user content so its centroid lands at the orbit pivot on the first frame `visualBounds` is non-empty (bounds query in `contentRoot`-local space — invariant of orbit rotation + scale). Lights stay on `entities.root` so they're not moved by the centring translation.

- **`.autoCenterContent(_ enabled: Bool)` modifier** (default `true`). Pass `false` for narrative scenes with intentional off-centre placement.
- **iOS-only vs Android**: Android achieves the same via per-demo `ModelNode(centerOrigin = Position.ZERO)`. Cross-platform code porting Android verbatim sees iOS re-centre implicitly; opt out for strict parity.

### Added — `docs/docs/cheatsheet-ios.md` parity table ([#1036](https://github.com/sceneview/sceneview/issues/1036))

Three-bucket reference: **Deprecated on iOS** (3 rows — DoF, exposure, shadowColor), **Android-only / no port** (4 rows — playbackDataset, SurfaceType.texture, StreetscapeGeometry, TerrainAnchor/RooftopAnchor), **Approximated** (3 rows — fog variants, reflection probe volumes, subsurface). Same table in `llms.txt` for MCP consumers.

### ⚠️ BREAKING — Android 3D + AR render defaults (visual)

`SceneView` and `ARSceneView` on Android now ship with these adjusted defaults. Apps upgrading from v4.2.0 will see visible rendering changes.

- **IBL intensity (3D + AR)** : Filament hardcoded ~30 000 → **`DEFAULT_IBL_INTENSITY = 10 000`** lux ([#1075](https://github.com/sceneview/sceneview/issues/1075), [PR #1079](https://github.com/sceneview/sceneview/pull/1079) + [PR #1088](https://github.com/sceneview/sceneview/pull/1088) for the AR cross-fix). Now 1:1 with `DEFAULT_MAIN_LIGHT_COLOR_INTENSITY`, ambient and key light contribute proportionally. **Apps that hand-tuned `mainLight.intensity` against the implicit 30k IBL will see ambient drop ~3× and shadows deepen.** Restore the v4.2.0 look via `indirectLight.intensity = 30_000f` on your custom environment.
- **AR camera exposure (`ARDefaultCameraNode`)** : f/16 1/125 ISO 100 (EV 15, sunny-16) → **f/12 1/200 ISO 200** (~1 stop brighter) ([#1067](https://github.com/sceneview/sceneview/issues/1067) via [PR #1088](https://github.com/sceneview/sceneview/pull/1088)). Matches 3D `DefaultCameraNode` for cross-mode parity and aligns with the v4.1.0 light defaults (main 10k, fill 3k). **Apps that override `cameraExposure = -1.0f` (the v4.0.x workaround for the sunny-16 mismatch) will now be over-exposed.** Drop the override — the new defaults match. The 11 sample demos that still carried this workaround are cleaned up in CORR-A ([#1101](https://github.com/sceneview/sceneview/issues/1101)) — see "Fixed — AR rendering pipeline" below.
- **AR IBL specular filter default** : `environmentalHdrSpecularFilter = false` → **`true`** ([#1064](https://github.com/sceneview/sceneview/issues/1064) via [PR #1086](https://github.com/sceneview/sceneview/pull/1086)). Roughness-prefilters the ARCore HDR cubemap so reflections vary visibly with material roughness instead of being mirror-like at every value. **Cost : +5–15 ms / cubemap update** (≈ 1 Hz from ARCore HDR mode). Restore v4.2.0 cost profile via `lightEstimator.environmentalHdrSpecularFilter = false`.
- **AR `Config.LightEstimationMode` default** : ARCore's stock `AMBIENT_INTENSITY` → **`ENVIRONMENTAL_HDR`** ([#1063](https://github.com/sceneview/sceneview/issues/1063) acceptance #2, CORR-A). Set inside `ARSceneView`'s `session.configure { … }` block BEFORE the user's `sessionConfiguration` callback so callers can still opt back into another mode. Front-camera sessions still force `DISABLED` (`ARSession.configure(...)` guard, unchanged). **Cost note** : HDR captures + analyses the camera frame for an environmental cubemap (~1 Hz) + computes SH coefficients + main-light direction; combined with the `#1064` specular prefilter on the same cubemap, total cost is **+5–15 ms / cubemap update**. The 4 demos that previously didn't opt in (`ARImageDemo`, `ARRooftopAnchorDemo`, `ARStreetscapeDemo`, `ARTerrainAnchorDemo`) now ship HDR — all 4 are appropriate targets (1 indoor PBR helmet + 3 outdoor scenes). On HDR-unsupported devices ARCore silently degrades `LightEstimate.State` to `NOT_VALID` and the `#1063` neutral IBL baseline stays in place — no crash, no visual regression. **Restore v4.2.0 mode via** `sessionConfiguration = { _, c -> c.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY }`.
- **AR `ARSceneView` two-light defaults** ([#1063](https://github.com/sceneview/sceneview/issues/1063) acceptance #3, CORR-A). `ARSceneView` now exposes a new `fillLightNode: LightNode? = rememberFillLightNode(engine)` parameter, mirroring the 3D `SceneView` v4.1.0 setup (main 10k + fill 3k lux from opposite-side directional). The fill light is unaffected by ARCore light estimation — only `mainLightNode` is multiplied by the estimate. **Apps that handled their own fill light + relied on the AR scene having no library-provided fill will see a brighter shadow side**. Restore the v4.2.0 single-light look via `ARSceneView(fillLightNode = null)`. Deprecated `ARScene` alias forwards the param.
- **`SceneView(isOpaque = false)` is now actually transparent** ([#1077](https://github.com/sceneview/sceneview/issues/1077) via [PR #1092](https://github.com/sceneview/sceneview/pull/1092)). v4.2.0 ignored the flag — `uiHelper.isOpaque` and `view.blendMode` were never wired. Apps that set `isOpaque = false` and worked around the broken behaviour with custom Compose backgrounds will see double-rendering. Remove the workaround — the underlying view now bleeds through.

### BREAKING-ish — silent-stub modes now active

Apps that called `.cameraControls(.pan)` or `.cameraControls(.firstPerson)` as effective no-ops in v4.2.0 will now see the modes do something different. To restore the v4.2.0 silent behaviour, drop the modifier (defaults to `.orbit`).

Apps with intentionally off-centre content will see the centroid re-centred at the orbit pivot. To restore the v4.2.0 layout, append `.autoCenterContent(false)`.

### Fixed — AR rendering pipeline (rooted in v4.0 → v4.2 regressions)

- **🚨 Multiplicative light drift killed `mainLight` in ~15 frames** ([#1062](https://github.com/sceneview/sceneview/issues/1062) via [PR #1069](https://github.com/sceneview/sceneview/pull/1069)). Per-frame `mainLight.intensity *= estimate.pixelIntensity` compounded toward 0 (or ∞). Replaced by a baseline-cache pattern (`compareAndSet` on first valid estimate, then `baseline * estimate` each frame). Keyed on `mainLightNode` identity so the `#1017` reactive swap resets cleanly. Regression pin in `ARMainLightBaselineMultiplyTest`.
- **🚨 `createAREnvironment` shipped without `IndirectLight`** ([#1063](https://github.com/sceneview/sceneview/issues/1063) via [PR #1069](https://github.com/sceneview/sceneview/pull/1069)). New `iblBuffer: Buffer?` parameter; `rememberAREnvironment` defaults to the bundled neutral 256×128 dim-grey IBL `arsceneview/src/main/assets/neutral_environment.ibl`. Metals in AR no longer render jet-black before the first ARCore estimate.
- **🚨 `ARSceneView` AR scene baseline now mirrors the 3D `Scene` v4.1.0 two-light setup + opt in to ARCore real-environment estimate** (#1063 acceptance criteria #2 + #3, post-#1069). Two follow-ons land in CORR-A:
  - **New `fillLightNode: LightNode? = rememberFillLightNode(engine)` parameter on `ARSceneView`**. Mirrors the 3D `SceneView` v4.1.0 two-light defaults — main 10k + fill 3k lux from opposite-side directional. The fill light is unaffected by ARCore light estimation (only `mainLightNode` is multiplied by the estimate); pass `null` to keep a single-light AR scene. Deprecated `ARScene` alias forwards the new param. The `prevFillLightRef` SideEffect mirrors `prevMainLightRef` so reactive swaps are clean.
  - **Default `Config.LightEstimationMode = ENVIRONMENTAL_HDR`** (replacing ARCore's stock `AMBIENT_INTENSITY`). Without HDR, the IBL baseline shipped by `rememberAREnvironment` (#1069) never gets replaced — PBR metals stay locked on the neutral grey baseline even after the user pans across a real scene. Set BEFORE the user's `sessionConfiguration` callback so callers can still opt back into another mode. Front-camera sessions still force `DISABLED` inside `ARSession.configure(...)` regardless. Documented in the `ARSceneView` KDoc for both `sessionConfiguration` and the param section. Pinned by `ARCompletenessDefaultsTest` (4 cases).
- **🚨 SH coefficient swap on bands y20 / y21** ([#1093](https://github.com/sceneview/sceneview/issues/1093) via [PR #1100](https://github.com/sceneview/sceneview/pull/1100)). `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS[6]` and `[7]` had swapped magnitudes and signs vs Filament's upstream `CubemapSH.cpp` convention, silently producing wrong-direction matte AR shading since SceneformMaintained PR #156 (4+ years). Now matches Filament: `factor[6] = +0.078848` (y20), `factor[7] = -0.273137` (y21). 2 pinning tests added.
- **`LightEstimator` double-closed ARCore `Image` objects in cubemap callback** ([#1090](https://github.com/sceneview/sceneview/issues/1090) via [PR #1091](https://github.com/sceneview/sceneview/pull/1091)). The `image.use { }` block already closed the `Image`; the trailing `arImages.forEach { it.close() }` then threw `IllegalStateException` (swallowed). Side-fix : `@Volatile` on 6 `environmentalHdr*` toggles + `isEnabled` ([#1094](https://github.com/sceneview/sceneview/issues/1094) via [PR #1095](https://github.com/sceneview/sceneview/pull/1095)).
- **`LightEstimator` robustness — 3 follow-ups to #1091 / #1095** (CORR-B audit, acceptance #2 of umbrella [#1094](https://github.com/sceneview/sceneview/issues/1094)). Three latent issues that survived the first two `LightEstimator` cleanups:
  1. **`destroy()` race vs. late render frame** — added `@Volatile private var isDestroyed` gate at the top of `update()` so a frame arriving after `DisposableEffect.onDispose` short-circuits instead of touching freed `engine.destroyTexture` natives. `destroy()` is now idempotent and latches the flag *before* freeing textures.
  2. **Cubemap-texture leak on `environmentalHdrReflections` toggle** — toggling `true → false` previously skipped the `if (reflectionsOn) { ... }` branch entirely, leaving `cubeMapTexture` + `cubeMapTextureSpecular` + the direct staging `ByteBuffer` alive in native heap forever. New nullify-on-disable path at the top of `update()` routes through the existing destroy-on-reassign setters; symmetric handling of `environmentalHdrSpecularFilter` toggling off (frees only the specular texture, preserves the base).
  3. **Staging-buffer race vs. async Filament upload** — restored the `PixelBufferDescriptor` callback as a `@Volatile uploadInFlight` flag flip (set true before `setImage`, reset by the Filament render thread). AR thread now skips the cubemap update while in flight, preventing a `cubeMapBuffer.clear() + put(rgbBytes)` overwrite from corrupting an in-flight GPU upload (smeared cubemap / 1-frame HDR garbage flash). Long-form comment on the callback site guards against a future refactor re-no-op'ing it. Regression suite: 14 pinning tests in `LightEstimatorRobustnessTest`.
- **AR cleanup batch — 4 follow-ups to CORR-B and post-merge audit of [#1069](https://github.com/sceneview/sceneview/pull/1069) / [#1091](https://github.com/sceneview/sceneview/pull/1091)**:
  1. **`createAREnvironment` no longer advertises an inert `isOpaque`** ([#1121](https://github.com/sceneview/sceneview/issues/1121)). The hard-coded `isOpaque = true` was bypassed by `skybox = null`, so the parameter was effectively ignored. Dropped from the call; KDoc updated to call out that AR environments are inherently non-opaque (camera feed shows through). No behaviour change for end users.
  2. **`uploadInFlight` callback hoisted from per-frame allocation** ([#1102](https://github.com/sceneview/sceneview/issues/1102)). `Texture.PixelBufferDescriptor` previously received a fresh `Runnable { uploadInFlight = false }` per cubemap upload; with the new CORR-B gate firing the callback ~1 Hz, that's still one short-lived lambda per upload. Now hoisted as a `private val uploadCompletedCallback` so a single allocation per `LightEstimator` instance covers its full lifetime. Can't move to the companion object because the callback mutates per-instance state.
  3. **`LightEstimator` lifecycle ownership documented** (CORR-B FU-3). Class KDoc gets a new "Lifecycle ownership" section spelling out that `engine` and `iblPrefilter` are borrowed (caller-owned, typically `ARSceneView`-scoped) and the correct LIFO teardown order (estimator first, then engine).
  4. **Instrumented stress test for concurrent `update()` ↔ `destroy()`** ([#1094 acceptance #3](https://github.com/sceneview/sceneview/issues/1094)). `LightEstimatorConcurrentDestroyTest.kt` lands in both `src/test/` (algorithmic mirror, fast CI tier — 4 tests) and `src/androidTest/` (real Filament Engine smoke — 3 tests, JNI-grounded). `arsceneview` gains a `testInstrumentationRunner` config so `./gradlew :arsceneview:connectedDebugAndroidTest` works. Asserts: no exceptions, monotonic `isDestroyed` transition, post-destroy textures freed, engine survives ≥10 allocate→destroy cycles.

### Fixed — 3D rendering pipeline

- **🚨 `PostProcessingDemo` silently disabled SSAO on first paint** ([#1076](https://github.com/sceneview/sceneview/issues/1076) via [PR #1079](https://github.com/sceneview/sceneview/pull/1079)). Demo state initialised at `false` but the library default is `true`. Initial paint inverted the library default, hiding ambient occlusion until the user toggled it.
- **`RenderQuality` preset clobbered user view tweaks on every recomposition** ([#1078](https://github.com/sceneview/sceneview/issues/1078) via [PR #1089](https://github.com/sceneview/sceneview/pull/1089)). `view.applyRenderQuality(...)` was in an unkeyed `SideEffect`. Moved to `LaunchedEffect(view, renderQuality)` — preset reapplies only on actual quality change, user-set `view.colorGrading` / `view.bloomOptions` survive across recompositions. Switching presets still overrides preset-owned fields (intended semantic).
- **`EnvironmentLoader.createHDREnvironment` convenience overloads silently dropped `indirectLightApply`** ([#1124](https://github.com/sceneview/sceneview/issues/1124)). The 4 convenience overloads (asset / rawRes / file) plus `loadHDREnvironment(url:)` and `loadKTX1Environment(url:)` delegated to the `buffer:` overload but forgot to forward the `indirectLightApply` hook — users who wanted to override the v4.1.0-balanced 10k IBL default (#1075) had to copy the buffer-loading boilerplate. Now all overloads expose `indirectLightApply: IndirectLight.Builder.() -> Unit = {}`. `EnvironmentDemo` gains an "IBL Intensity" chip row demonstrating the override. Pinned by a Java-reflection regression test that catches any future overload that re-introduces the drop.
- **`PhysicsDemo` stacked 100 000 lux DIRECTIONAL on top of the v4.1.0 default lights** ([#1125](https://github.com/sceneview/sceneview/issues/1125)). Pre-v4.1.0 leftover from the era when the hardcoded main light was 100k. After #1075 rebalanced main to 10k + fill to 3k + IBL to 10k, this override read 10× the new main and blew the scene out under the v4.1.0 EV ≈ 11.6 camera. Retuned to **5 000 lux** as a left-side counter-fill (opposite the library's 3k right-side fill).
- **`cameraNode` leaked into shared `Scene` on `SceneView` unmount** ([#1143](https://github.com/sceneview/sceneview/issues/1143)). Same `SideEffect` + `AtomicReference` pattern that #1122 / [PR #1131](https://github.com/sceneview/sceneview/pull/1131) just fixed for the main + fill lights. Switched to `DisposableEffect(cameraNode) { addNode; onDispose { removeNode } }` so the camera (and any HUD-space child nodes parented under it) is removed from `nodeManager` on composition disposal — clean for the documented "share scene between views" use case.

### Fixed — Collision math

- **🚨 Box ray-OBB intersection broken for parallel rays** ([#1096](https://github.com/sceneview/sceneview/issues/1096) via [PR #1098](https://github.com/sceneview/sceneview/pull/1098)). `MathHelper.MAX_DELTA = 1e-10f` was below FLT_EPSILON (~1.19e-7) for normalised ray directions, so the parallel branch never triggered — `Inf / Inf` slab comparisons produced lottery hits on flat OBBs. New explicit `abs(d) < 1e-6f` parallel detection at the 3 Box slab call sites + matching twin fix in `MeshCollider.AABB.rayIntersection` ([PR #1100](https://github.com/sceneview/sceneview/pull/1100)). **Note** : `MathHelper.MAX_DELTA` stays at `1e-10f` because bumping it would silently break `Vector3.normalized()` for short vectors (documented in KDoc).
- **🚨 `spherePlaneResponse` returned wrong contact point on negative side** ([#1097](https://github.com/sceneview/sceneview/issues/1097) via [PR #1098](https://github.com/sceneview/sceneview/pull/1098)). Used the flipped (collision) `normal` for the contact-point projection — bounce side was double-shifted off the plane. Now uses `planeNormal` directly for the projection identity `contact = center - planeNormal * signedDist`, regardless of side. Ball-on-floor no longer clips through.

### Fixed — Math + collision regressions ([#1126](https://github.com/sceneview/sceneview/issues/1126) audit batch)

Four sub-items audited from the `sceneview-core` math/animation/collision packages. Each lands as its own PR with a regression pin.

- **`SpringAnimator` underdamped uses analytical velocity** ([#1126](https://github.com/sceneview/sceneview/issues/1126) item 1, [PR #1135](https://github.com/sceneview/sceneview/pull/1135)). Velocity was numerically differentiated from position — produced wrong magnitude under heavy damping and integration drift at low frame rates. Now uses the closed-form analytical derivative for the underdamped case, so spring physics is frame-rate independent and correct from the first step.
- **`Quaternion.slerp` transform uses exponential decay** ([#1126](https://github.com/sceneview/sceneview/issues/1126) item 2, [PR #1141](https://github.com/sceneview/sceneview/pull/1141)). `Transform.slerp` previously called raw `Quaternion.slerp(a, b, t)` with `t = deltaTime * speed`, which is NOT frame-rate independent (smaller `t` at higher fps → slower convergence). Replaced by exponential-decay formulation `t = 1 - exp(-speed * deltaTime)` so convergence rate is identical at 30 / 60 / 120 fps.
- **`Matrix.decomposeRotation` no longer uses `this` as scratch** ([#1126](https://github.com/sceneview/sceneview/issues/1126) item 3, [PR #1140](https://github.com/sceneview/sceneview/pull/1140)). The method mutated `this` as a scratch buffer during decomposition, corrupting the source matrix when callers held a reference. Two concurrent decompositions on the same matrix raced. Now allocates a local scratch — `decomposeRotation` is pure + thread-safe.
- **`closestPointsBetweenSegments` — Ericson §5.1.9 sign** ([#1126](https://github.com/sceneview/sceneview/issues/1126) item 4, [PR #1139](https://github.com/sceneview/sceneview/pull/1139)). A sign error in the parallel-segment branch (transcribed from Christer Ericson's "Real-Time Collision Detection" §5.1.9) returned the wrong end-point pair when one segment fully shadowed the other. Now matches the reference text + 6 pinning tests for the 4 parallel-overlap topologies.

### Fixed — Engine resource leaks

- **Main + fill light add wrapped in `DisposableEffect`** ([#1122](https://github.com/sceneview/sceneview/issues/1122) via [PR #1131](https://github.com/sceneview/sceneview/pull/1131)). `engine.scene.addEntity(light)` was called from a bare `SideEffect` so a removed `LightNode` recomposition left the light entity attached to the Filament scene forever. Now uses `DisposableEffect(mainLightNode, fillLightNode)` with explicit `removeEntity` on dispose — symmetric add/remove, no Filament-side leak across `LightSlot` swaps. Pinned by the existing `Scene` lifecycle tests + a new add/remove-balance assertion.
- **`destroyMaterialsOnDispose` flag on `RenderableNode` + `GeometryNode`** ([#1123](https://github.com/sceneview/sceneview/issues/1123) via [PR #1132](https://github.com/sceneview/sceneview/pull/1132)). `MaterialInstance` allocated inside a node's `apply` block was leaked because the node assumed the material was owned by the caller. New `destroyMaterialsOnDispose: Boolean = false` parameter (default preserves caller-owned semantics); set `true` when the node creates its own `MaterialInstance`. `rememberMaterialInstance` helpers default to `true`, so callers using the v4.0.x recommended pattern see no leak.

### Fixed — AR cubemap upload ([#1142](https://github.com/sceneview/sceneview/pull/1142))

- **🚨 `Texture.Builder` now sets `Usage.GEN_MIPMAPPABLE` for the ARCore HDR cubemap** ([PR #1142](https://github.com/sceneview/sceneview/pull/1142)). v4.3.0 RC blocker. Filament 1.71 hardened the texture-usage check and `engine.createTexture` now throws when a cubemap is built without `GEN_MIPMAPPABLE` and later submitted to mipmap generation. `LightEstimator` called `texture.generateMipmaps()` immediately after `setImage`, so AR sessions with `environmentalHdrReflections = true` crashed on the first cubemap upload (~1 second after `START_TRACKING`). Fix adds the flag at the two `Texture.Builder` call sites + a regression pin in `LightEstimatorCubemapBuilderTest`.

### Tooling — Bundled ARCore session recording for demos

- **`samples/android-demo/src/debug/assets/ar-recordings/bundled-pixel9-sample.mp4`** (16 MB, debug-only sourceSet — release APK untouched, [#934](https://github.com/sceneview/sceneview/issues/934) protected). Lets `ARRecordPlaybackDemo` show a non-empty list on first launch and unblocks emulator-testable AR demos. 4 JVM tests pin the ftyp + `avc1` + `mett` codec box layout (catches camera-only video misclassified as ARCore dataset). CI regression via the bundled recording is tracked by [#1050](https://github.com/sceneview/sceneview/issues/1050).

### Fixed — Inertia mode-gating

`CameraControls.applyInertia()` now dispatches on `mode`: `.pan` glides the `target` translation; `.orbit` and `.firstPerson` keep the rotation path. Previously the inertia velocity stored during a `.pan` drag would inject ghost rotation on release.

### Fixed — Triage sweep (PR [#1040](https://github.com/sceneview/sceneview/pull/1040))

- **Sync-versions `--fix` mode now actually rewrites SwiftPM `from:` clauses** ([#990](https://github.com/sceneview/sceneview/issues/990)). The pre-existing fix block silently no-op'd under `set -euo pipefail` because the last loop iteration's `[ ] && echo` short-circuit aborted the script before reaching the rewrite. Caught by 5-agent independent review of the same PR. Coverage extended from 30 → 45 checks (13 new SwiftPM `from:` snippets across docs/website/marketing, plus root `Package.swift` install snippet).
- **`DemoInteractionTest` AppBar titles aligned with registry labels** ([#1006](https://github.com/sceneview/sceneview/issues/1006)): Animation→Auto Rotate, Multiple Models→Multi Model, Image Node→Image Planes, Billboard Node→Billboard, Shape Node→All Shapes. The Billboard chip is now `Billboard Panel` to disambiguate from the AppBar.
- **`OrbitalARDemo` Float precision drift** ([#978](https://github.com/sceneview/sceneview/issues/978)) — modulo `2π` on orbit + spin angles (Android + iOS) so cumulative angle survives long-running sessions.
- **`ExploreTabScreen` partial-success path** ([#980](https://github.com/sceneview/sceneview/issues/980)) — `supervisorScope` + `catchingFeed` helper so a transient Sketchfab feed failure no longer wipes the other two; `CancellationException` re-thrown to keep structured concurrency intact.
- **`DeepLinkRouterTest.kt` JVM compile** — pre-existing breakage since `2556c467` (4-arg `DemoEntry` ctor lost when `icon` field was added). Caught during PR #1040 5-agent review; all 13 deep-link tests now compile and run.
- **`validate-spm` regex hardened** ([#1007](https://github.com/sceneview/sceneview/issues/1007)) with a `targets:` anchor so a commented-out `// .library(name: "SceneViewSwift", ...)` line cannot satisfy the check.
- **QA script `qa_android_demos.py`** updated to the renamed registry labels.

### Documented — Triage sweep
- **Filament runtime ↔ `.filamat` ABI invariant** ([#1023](https://github.com/sceneview/sceneview/issues/1023)) in `CONTRIBUTING.md`: the v4.1.0 → v4.1.1 hotfix lesson, the 12 blob list, the matc recompile recipe. CLAUDE.md QUALITY RULES cross-links to it so future sessions are auto-warned.

### Closed without code — Triage sweep
- [#884](https://github.com/sceneview/sceneview/issues/884) RN+Flutter version drift — `@sceneview-sdk/react-native@4.2.0` and `sceneview_flutter@4.2.0` aligned with the monorepo on npm/pub.
- [#1004](https://github.com/sceneview/sceneview/issues/1004) iOS parity v4.2.0 umbrella — SHIPPED end-to-end; deferred items split into focused #1032 / #1033 / #1034 / #1035 / #1036.

### Tests — Regression pins for the 14-PR rendering burst (CORR-C batch)

Pins for **5 of the 14** fixes shipped on 2026-05-14 (the highest-impact ones; remaining 7 batched for a follow-up). Each pin lives next to the fix it protects:

- **`BoxTest.kt`** — 5 new methods (1 perpendicular + 4 parallel-branch on x and z axes) pin `Box.rayIntersection` correct behaviour for thin-slab boxes. Acceptance criterion oublié de [#1096](https://github.com/sceneview/sceneview/issues/1096).
- **`MeshColliderTest.kt`** — 5 new methods pin the twin parallel-ray epsilon fix in `MeshCollider.AABB.rayIntersection` across x and z axes. Acceptance criterion oublié de [#1100](https://github.com/sceneview/sceneview/issues/1100).
- **`SceneFactoriesTest.kt`** (new file) — pins `DEFAULT_IBL_INTENSITY = 10_000f`, the 1:1 ratio with `DEFAULT_MAIN_LIGHT_COLOR_INTENSITY`, and the 3D `DefaultCameraNode.DEFAULT_APERTURE/SHUTTER_SPEED/ISO` triple ([#1067](https://github.com/sceneview/sceneview/issues/1067), [#1075](https://github.com/sceneview/sceneview/issues/1075)).
- **`ARDefaultCameraNodeTest.kt`** (new file) — pins `ARDefaultCameraNode` exposure via the new companion constants, cross-checks parity with 3D `DefaultCameraNode`, and asserts ≥1 stop brighter than sunny-16 ([#1067](https://github.com/sceneview/sceneview/issues/1067)). 3D `DefaultCameraNode` was refactored in the same PR to expose matching `DEFAULT_APERTURE/SHUTTER_SPEED/ISO` companion constants; AR aliases them at compile time to eliminate drift risk.
- **`RenderQualityLaunchedEffectTest.kt`** (new file) — pins the `LaunchedEffect(view, renderQuality)` re-keying contract via a 25-line JVM simulator. Pins the contract (key-equality semantics) rather than the production call site — a separate follow-up will add a Compose UI test that verifies `Scene.kt:278` actually keys on both `view` and `renderQuality` ([#1078](https://github.com/sceneview/sceneview/issues/1078)).

### CI — Batch B0 ([#1116](https://github.com/sceneview/sceneview/issues/1116), [#1117](https://github.com/sceneview/sceneview/issues/1117), [#1118](https://github.com/sceneview/sceneview/issues/1118))

- **`publish-api-docs` now gates `create-release`** (#1116) — a Dokka build failure on a tag push now produces a workflow red X instead of a silent "Other Changes" GitHub Release with no API documentation. `continue-on-error: true` and `|| echo` swallow removed.
- **`quality-gate.yml` skips docs-only PRs** (#1117) — `paths-ignore` mirrors the filter already in place on `ci.yml`. Docs PRs (typo fixes in `*.md`, `docs/**`, `website-static/**`, `marketing/**`, `branding/**`) no longer burn ~12 min of Android + MCP gate time. `mcp*/**` intentionally NOT excluded so MCP tests still run on MCP-only PRs.
- **Composite actions for JDK + MCP setup** (#1118) — new `.github/actions/setup-gradle` (JDK + Gradle cache + `chmod +x ./gradlew`, defaults to JDK 21, accepts `java-version: "17"` for Flutter jobs) and `.github/actions/setup-mcp` (Node + npm-lockfile cache + `npm ci` in `mcp/`). Adopted across 7 workflows (release, ci, pr-check, render-tests, docs, build-apks, play-store, quality-gate). Net –68 LOC, eliminates JDK-version drift, single bump point for Node/Java versions.
- **Render-tests sharding** ([#1119](https://github.com/sceneview/sceneview/issues/1119)) filed as a follow-up — `android-library-render` is `continue-on-error: true` and not a merge gate, so a 4× emulator boot cost vs current 20 min wall-clock needs validation before committing.

---

## v4.2.0 — iOS parity sprint: LightSlot, RenderQuality, NodeGesture, AR anchors (2026-05-13)

**Status**: stable. Ports the v4.1.0 BREAKING render-defaults change finally to iOS, plus closes the bulk of the [#928 silent-stub batch](https://github.com/sceneview/sceneview/issues/928) and major chunks of the [iOS parity umbrella #1004](https://github.com/sceneview/sceneview/issues/1004).

### ⚠️ BREAKING — iOS render defaults match Android v4.1.0+

`SceneView` on iOS now ships with the same out-of-the-box 2-light setup that Android landed in v4.1.0:

- **Main / key directional light intensity**: `1 000` → **`10 000`** lux (×10), pointing straight down (`(0, -1, 0)`).
- **Fill light**: new `LightNode.fill(intensity: 3 000, castsShadow: false)` from `(0.5, -0.5, 0.5)` (upper-back-left → down-front-right). 30 % of main intensity, lifts the shadow side without flattening.
- **Existing iOS apps will render brighter / more cinematic**. To restore the v4.1.x look exactly:
  ```swift
  SceneView { /* ... */ }
    .mainLight(.custom(LightNode.directional(intensity: 1_000)))
    .fillLight(.disabled)
  ```

### Added — `LightSlot` / `LightNode.fill` / `mainLight` / `fillLight` modifiers ([#1016](https://github.com/sceneview/sceneview/pull/1016))

- **`LightSlot` enum** — `.systemDefault` / `.disabled` / `.custom(LightNode)` (3-state, exhaustive switch). Cleaner than `Optional<LightNode?>` sentinel.
- **`SceneView.mainLight(_:)` + `SceneView.fillLight(_:)` modifiers**.
- **`LightNode.fill(color:intensity:castsShadow:)` factory**, signature-consistent with `LightNode.directional(...)`. No baked orientation (caller calls `.lookAt(_:)`).
- **`@MainActor public struct LightNode`** — replaces the unsound `Sendable` conformance (LightNode wraps a non-Sendable `Entity`).
- **Known limitation** ([#1017](https://github.com/sceneview/sceneview/issues/1017)): light slot is read once during scene setup. Reactive replacement via `.fillLight(.custom(newLight))` mid-frame is not yet wired — Android's `prevFillLightRef` swap pattern (`Scene.kt:287-305`) needs equivalent diffing in iOS `RealityView.update:`.

### Added — `RenderQuality` preset ([#1018](https://github.com/sceneview/sceneview/pull/1018))

- **`RenderQuality` enum** — `.cinematic` / `.default` / `.performance`, mirrors Android `RenderQuality.kt`.
- **`SceneView.renderQuality(_:)` modifier**. Walks all `DirectionalLight` children + adjusts `ImageBasedLightComponent.intensityExponent` per tier.
- **iOS / Android parity gap documented** in the enum doc-comment: RealityKit doesn't expose SSAO / MSAA / HDR-buffer / bloom toggles, so the iOS preset honours what's available (per-light shadow toggle + IBL intensity exponent).

### Fixed — `SceneView.onEntityTapped(_:)` real entity hit-test ([#1019](https://github.com/sceneview/sceneview/pull/1019), [#928](https://github.com/sceneview/sceneview/issues/928))

Previously the callback was ALWAYS called with `entities.root` (scene root) regardless of where the user actually tapped — useless for picking objects. Now wired via `SpatialTapGesture().targetedToAnyEntity()` so the callback receives the real entity at the tap location. Soft BREAKING: apps that relied on the broken behavior are unaffected (no useful logic could be built on a constant root reference).

### Fixed — `NodeGesture.dispatch*` actually fires ([#1024](https://github.com/sceneview/sceneview/pull/1024), [#928](https://github.com/sceneview/sceneview/issues/928))

The `NodeGesture` system had full registration + dispatch API surface (`onTap` / `onDrag` / `onScale` / `onRotate` / `onLongPress` + corresponding `dispatch*`) but the dispatch entry points were **never CALLED** from anywhere — handlers registered via `entity.onTap { … }` silently never fired. Wired five new `.simultaneousGesture(...).targetedToAnyEntity()` in `SceneViewRepresentation` that route to the matching `NodeGesture.dispatch*`. Empty-space gestures still drive the camera (existing `dragGesture` + `pinchGesture` for orbit/zoom).

### Added — AR `AnchorNode` factories ([#1025](https://github.com/sceneview/sceneview/pull/1025), [#894](https://github.com/sceneview/sceneview/issues/894) partial)

- **`AnchorNode.image(group:name:)`** — anchor content to a detected reference image. Mirrors Android `AugmentedImageNode`.
- **`AnchorNode.face()`** — anchor to detected face (front-camera). Mirrors Android `AugmentedFaceNode` (pose only — no morphing-mesh; for that, drop down to raw `ARFaceAnchor` + custom mesh entity).
- **`AnchorNode.body()`** — anchor to detected human body root joint (rear-camera, iOS 13+). RealityKit-exclusive, no Android equivalent.

### Fixed — AR session interruption preserves full tracking config ([#1013](https://github.com/sceneview/sceneview/pull/1013), [#928](https://github.com/sceneview/sceneview/issues/928))

`ARSceneView.Coordinator.sessionInterruptionEnded(_:)` previously rebuilt `ARWorldTrackingConfiguration` from a single stored property (`planeDetection`). Image-tracking database, mesh reconstruction flag, environment-texturing setting were silently lost on every background→foreground cycle. Now the Coordinator stores + re-applies all of them.

### Fixed — `LightNode.spot(innerAngle:)` cone-angle invariant ([#1013](https://github.com/sceneview/sceneview/pull/1013), [#928](https://github.com/sceneview/sceneview/issues/928))

Clamps `safeInner = max(0, min(innerAngle, safeOuter))` and `safeOuter = max(0, min(outerAngle, π/2))`. RealityKit silently produces undefined results when `innerAngle > outerAngle`. `#if DEBUG print(...)` diagnostic surfaces clamping events.

### Fixed — iOS demo deep-link routing for `model-viewer` + `multi-model` ([#1020](https://github.com/sceneview/sceneview/pull/1020), closes [#1015](https://github.com/sceneview/sceneview/issues/1015))

Both ids were in `DemoDeepLinkRegistry.allowedIds` but had no `destination(for:)` cases — fell to the "Coming soon" placeholder, even though `model-viewer` is the App Store listing's hero screenshot. Now route to `SceneGalleryDemo` (the closest iOS analog to Android's tabletop multi-model scene).

### Documented — `CameraNode.exposure(_:)` stays a deprecated no-op ([#1019](https://github.com/sceneview/sceneview/pull/1019), negative result)

Investigation note: `PerspectiveCameraComponent.exposureCompensation` does NOT exist on RealityKit / Xcode 26.x despite an audit suggestion otherwise. Verified via direct compile failure. The deprecation now points users at the working alternatives: `ARSceneView(cameraExposure:)` for AR, `SceneView.renderQuality(_:)` to tune IBL, per-light `LightNode.directional(intensity:)` for the key/fill ratio.

### Sample-app review

This release was visually validated by an Opus reviewer agent on the iPhone 16e simulator across 5 demos (`lighting`, `geometry`, `animation`, `model-viewer`, `multi-model`). All passed without regression. Side-finding (off-center camera framing across all iOS demos — pre-existing, not regression introduced by this release) filed as [#1026](https://github.com/sceneview/sceneview/issues/1026).

### Library API

| Surface | Change |
|---|---|
| `LightNode` | now `@MainActor` (was `Sendable`); added `.fill(color:intensity:castsShadow:)` factory + spot innerAngle clamp |
| `SceneView` | added `.mainLight(_:)` / `.fillLight(_:)` / `.renderQuality(_:)` modifiers; `.onEntityTapped(_:)` semantics fixed |
| `AnchorNode` | added `.image(group:name:)` / `.face()` / `.body()` factories |
| `RenderQuality` | new public enum |
| `LightSlot` | new public enum |
| `CameraNode.exposure(_:)` | improved deprecation message (still no-op on iOS — verified RealityKit-impossible) |
| `ARSceneView.Coordinator` | stores full tracking config across interruption |
| `NodeGesture` | dispatch API surface (existed already) now actually fires |

### Cross-platform release set

`sceneview` / `arsceneview` / `sceneview-core` (Maven Central) + `sceneview-web` (npm) + `@sceneview-sdk/react-native` (npm) + SPM tag — all bumped to `4.2.0`. `sceneview-mcp` continues on its independent 4.0.x patch track.

---

## v4.1.2 — Demo app recovery: Filament .filamat mismatch fixed + AR tab no longer crashes + Samples tab redesign (2026-05-13)

The v4.1.0 Play Store release shipped a demo app the author summarised as "très très nul":
the AR View tab crashed the whole process on tab tap, the Samples tab was a plain 2018-era
text list, and 10 of the 24 non-AR demos consistently crashed with a libfilament-jni.so
`TPanic<PostconditionPanic>` SIGABRT. This release fixes all three.

### Fixed — libfilament `TPanic<PostconditionPanic>` cascade (closes the v4.1.0 crash wave)

The bundled `.filamat` material binaries in `sceneview/src/main/assets/materials/` had been
recompiled with `matc 1.71` (commit `efd296f1`), but the Filament runtime was pinned back
to `1.70.2` (commit `4a31b579`, PR #961) without recompiling the blobs. Filament 1.70.2
silently *loaded* the 1.71 blobs and then panicked the moment a demo bound a sampler or
uniform descriptor against the new layout — taking the whole process with it.

- Reverted the 10 sampler-bearing `.filamat` to the pre-`efd296f1` snapshot
  (`git checkout efd296f1~1 -- sceneview/src/main/assets/materials/`).
- Recompiled the two newer `opaque_unlit_colored.filamat` + `transparent_unlit_colored.filamat`
  with `matc 1.70.2` from the upstream `v1.70.2` release tarball so they match the runtime.
- Verified on a Pixel_7a `-gpu host` emulator: **25 / 25 non-AR demos now pass** (was 14 / 25
  in the v4.1.0 audit). Previously crashing: `lighting`, `movable-light`, `fog`, `environment`,
  `text`, `lines-paths`, `image`, `billboard`, `view-node`, `debug-overlay` — all now render.

### Fixed — AR View tab no longer kills the app

Tapping the AR View tab on v4.1.0 unconditionally instantiated a live `ARSceneView`. On
devices without ARCore Services installed (and on emulators) the ARCore session creation
crashed Filament with the same `TPanic` signature.

- New launcher screen gates the live `ARSceneView` behind an explicit "Start AR Camera"
  CTA, with an `ArCoreApk.checkAvailability()` status pill and a 2×3 grid of the six
  headline AR demos visible immediately.
- `runCatching` around `checkAvailability` so it can't silently die on OEMs without Play
  Services. CTA is hard-disabled on `UNSUPPORTED_DEVICE_NOT_CAPABLE` / `UNKNOWN_*` so the
  user never re-enters the panic path.
- Top-right exit button on the live AR view detaches every anchor and flips back to the
  launcher — no more no-affordance dead end.
- `sessionStarted` is now `rememberSaveable` so process death doesn't dump users back to
  the launcher needlessly.

### Changed — Samples tab redesign (Material 3 Expressive grid)

Replaces the plain `ListItem` text list with a 2-column M3 Expressive grid. Each card has
a compact accent-tinted icon tile (36% of card height — title and subtitle remain the
visual anchors) plus a semantic Material icon picked per demo. Categories carry distinct
accent hues (3D Basics purple, Lighting amber, Content blue, Interaction pink, Advanced
teal, AR green) so users can scan the grid by colour at a glance. Visual reference:
Sketchfab mobile + Polycam + Reality Composer launchers.

- `DemoEntry` now carries `icon: ImageVector` and `status: DemoStatus`
  (`Working` / `KnownIssue` / `ComingSoon`). Non-Working demos surface an outlined
  "Preview" / "Soon" chip with an info icon — a calm honest signal, not a red alarm.
- Dark-mode accent palette (`#6446CD` → `#B39DDB`, etc.) keeps the tinted icon tiles
  legible on M3 dark `surfaceContainer` instead of burning at >9:1 contrast.
- `LargeTopAppBar` scroll behaviour wraps `rememberTopAppBarState()` so the collapse
  offset survives recomposition + rotation.
- Grid item keys namespaced `"demo-${id}"` to guard against id collisions.

### Changed — Explore tab polish

- Dropped the dev-flavored "Set SKETCHFAB_API_KEY (env or local.properties)" placeholder
  that leaked to end-user Play Store builds when the API key was missing. The Sketchfab
  carousels now silently fall through to the "Try a sample" carousel + categories.
- `SampleCard` rebuilt with the same accent-tinted icon-tile layout as the Samples grid
  so both tabs feel like one product.
- `FeedSection` self-hides when its Sketchfab feed is empty and not loading — no more
  three "Nothing here yet." headers stacked under each other in the offline path.
- Dropped the red "Couldn't reach Sketchfab" banner. The empty self-hide already conveys
  the offline state without dev-flavored copy.

### Other

- `feedback_stitch_mandatory.md` memory rule rewritten to drop Google Stitch as the
  mandated UI source — reference-driven (Sketchfab mobile / Polycam / Reality Composer)
  + `DESIGN.md` tokens is the new SceneView demo workflow.
- Local Sketchfab API key support in `local.properties` for developer builds (CI is
  unchanged; release builds still source the key from the GitHub Secret).

## v4.1.1 — Filament 1.71.0 / .filamat ABI realignment hotfix (2026-05-12)

**Status:** stable. Critical bug fix release. **All v4.1.0 consumers should upgrade.**

### Fixed — `SIGABRT` on `MaterialLoader.createColorInstance` (every demo using bundled materials)

A multi-agent post-ship audit caught a hard crash regression introduced in v4.1.0 — `Lighting`, `Geometry`, `Animation`, `MovableLight`, and `MultiModel` demos (and any consumer app touching `MaterialLoader.createColorInstance` or any default Filament post-process material) `SIGABRT`'d on launch with `Filament: could not parse the material package for material Opaque Colored`.

**Root cause** — Filament binary version mismatch:

- Commit [`efd296f1`](https://github.com/sceneview/sceneview/commit/efd296f1) (Apr 11) bumped Filament 1.70.2 → 1.71.0 and recompiled all 21 `.filamat` files via `matc 1.71.0` to material-binary version 71.
- Commit [`4a31b579`](https://github.com/sceneview/sceneview/commit/4a31b579) (May 11, [#961](https://github.com/sceneview/sceneview/issues/961)) reverted ONLY `gradle/libs.versions.toml`'s `filament` to `1.70.2` thinking the `.filamat` files were still v70 — they had been at v71 for a month. Filament 1.70.2 runtime cannot parse v71 packages → `SIGABRT` in `libfilament-jni.so`.
- v4.0.8, v4.0.9, and v4.1.0 all shipped this broken pair, but only v4.1.0 was caught (Lighting / Geometry / Animation / MovableLight / MultiModel were all new or refactored demos in the v4.1.0 sprint, exposing the regression).

**The fix** ([`<commit-sha>`]) reverts `4a31b579` — restores `filament = "1.71.0"` to match the v71 `.filamat` files. Future Filament downgrades MUST first run `matc <version>` against `sceneview/src/main/materials/*.mat` and commit the regenerated `.filamat`s.

### Tested — visual regression on Pixel_7a emulator

All 6 demos validated post-fix on Pixel_7a (Apple M3 host GPU, OpenGL ES 3.0):

- ✅ Lighting (was CRASH) — directional light + helmet renders correctly
- ✅ Geometry (was CRASH) — primitives render with PBR material
- ✅ Animation (was CRASH) — soldier walks in cinematic studio HDR with shadows
- ✅ MovableLight (was CRASH) — F40 model with marker sphere + intensity slider
- ✅ MultiModel (was CRASH) — 4-model tabletop tableau with studio HDR
- ✅ ModelViewer (was alive) — helmet still renders

`./gradlew :sceneview:compileReleaseKotlin :arsceneview:compileReleaseKotlin :samples:android-demo:compileDebugKotlin :sceneview:test :arsceneview:testDebugUnitTest` all green at Filament 1.71.0.

### No public API changes

Library API is identical to v4.1.0. Maven Central publishes the bumped triplet (`sceneview` / `arsceneview` / `sceneview-core` `4.1.1`) and the npm packages bump for version-tracking and to keep the cross-platform release set coherent.

---


## v4.1.0 — iOS V1 honest + Android rendering uplift + Sketchfab streaming + Claude Code plugin marketplace (2026-05-11)

### ⚠️ BREAKING — Android render defaults change visual look out-of-the-box

The `SceneView` composable now ships with `RealityKit-equivalent` defaults to close the
"iOS looks better than Android" gap reviewers consistently flagged in 2026-05-10 QA:

- **Main directional light intensity**: `100_000` → `10_000` lux (×10 drop). Existing
  apps will render **noticeably darker** unless they override `mainLightNode.intensity`
  explicitly or load a brighter IBL. Combined with shadows-now-on and a new fill light
  at 30% intensity, the overall scene exposure is much closer to RealityKit's defaults.
- **Shadows**: now on by default (`setShadowingEnabled(true)`). Existing apps that don't
  use casters will see no change; apps with floor planes will now display contact shadows.
- **Fill light**: new `fillLightNode: LightNode?` param on `SceneView`, defaulted to
  `rememberFillLightNode(engine)`. Pass `null` to disable for a single-light setup.
- **SSAO + bloom + Filmic tone mapper**: now on by default on `View`. SSAO has no visible
  cost on models without crevices; bloom strength is 0.10 (subtle, no "cheap mobile
  game" look). Override via `view.ambientOcclusionOptions.enabled = false` if needed.
- **Exposure**: `setExposure(16, 1/125, 100)` (sunny-16, EV~15) → `(12, 1/200, 200)`
  (neutral, EV~11.6). The previous defaults required cranking IBL intensity to see
  anything; the new defaults look right out of the box.

**Migration**: bump consumers to v4.1.0+ and review the visual delta. To restore v4.0.x
look exactly, set `mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f }`,
`fillLightNode = null`, and `view.ambientOcclusionOptions.enabled = false`.



### Fixed — Android demo polish (QA pass 2026-05-11)

A QA agent walked the demo screens and reported user-visible papercut issues.
Five low-effort high-impact fixes shipped (`65f6d8db`, `ea4c513e`, `15c8d254`, `15bcaf8c`):

- **ModelViewerDemo**: helmet was pinned to the lower half of the viewport with a big
  empty band at the top. `rememberHeroOrbitCameraManipulator(yHeight = 0.2f → 0f)`.
- **CameraControlsDemo**: helmet rendered at ~10% of the viewport at the default home
  camera distance. `homePosition = Position(0, 0, 4) → (0, 0, 1.5)`.
- **PhysicsDemo**: first frame showed a single ball on an empty floor — the demo's hook
  ("colourful rain on the floor") was invisible until the user pressed Drop. Initial
  `sphereCount = 1 → 5` so the first frame is the actual demo content.
- **ARStreetscapeDemo**: the permission gate showed only a "Denied" error message with
  no escape — Back was the only way out. Now offers `Retry` (re-launches the system
  prompt) and `Open Settings` (deep-links into the app's permission page) buttons.
- **DynamicSkyDemo**: rendered as "fully black at noon" because `DynamicSkyNode`
  positions a directional sun but doesn't paint a sky dome, and the default neutral
  IBL had no skybox. Mitigation in the demo (not the library): swap the IBL based on
  the time-of-day slider — `rooftop_night_2k` / `sunset_2k` / `outdoor_cloudy_2k`.
  Three buckets is coarse but covers the obvious user expectations; a proper
  procedural-atmosphere skybox is library-level work for a later sprint.

### Added — `MovableLightDemo` + `OrbitalARDemo` (samples)

Two new sample demos shipped on both iOS and Android (commits `c345404b`, `54233d56`).

- **`MovableLightDemo`** — drag-anywhere-on-the-scene → spherical-orbit math (azimuth /
  elevation, fixed radius 1.5 m) → light position updates live → specular highlights
  track the cursor on a PBR model (Damaged Helmet on Android, Ferrari F40 on iOS).
  Camera is locked so the only thing moving is the light; a yellow unlit marker sphere
  shows where the light source is. Intensity slider 1k → 100k, "Show light source"
  toggle hides/shows the marker.
- **`OrbitalARDemo`** — solar-system-style AR scene: eight distinct bundled models orbit
  around the user at radius 1.5 m, each with its own orbital speed (0.05 → 0.30 rad/s,
  21 s to 125 s for a full lap) and a slow local spin. Heights are equipartitioned
  across ±0.5 m so the formation reads as varied elevations as the user turns. Plane
  detection is disabled — the formation lives in world space, anchored at the user's
  starting position.

### Added — Sketchfab model viewer cross-fade (iOS + Android parity)

- **Wow-factor hero state on the Sketchfab download screen** ([`1e0f86ba`](https://github.com/sceneview/sceneview/commit/1e0f86ba)) — the previous bare-spinner loading state read as "loading something somewhere". Now both platforms show: (1) a Ken-Burns thumbnail (highest-res Sketchfab preview, slow 1.0→1.18 zoom, soft blur) while the GLB downloads — the screen always shows the *model itself*, never an empty container; (2) a ~500 ms cross-fade from thumbnail to live `SceneView` once the model loads — the "come to life" transition that reads as proof of native rendering; (3) premium `studio_2k.hdr` IBL by default (much more flattering on PBR than `neutral_ibl`, skybox kept off); (4) a 20 s hero auto-orbit so every angle is visible without touching the screen; (5) a cinematic radial vignette for the "Apple Store hero" framing. iOS uses SwiftUI `.onChange(of:)` + `withAnimation`; Android uses `Crossfade` from `androidx.compose.animation` keyed on the existing Stage state machine.

### Fixed — LoadingScrim on CameraControls + Animation demos (Android)

- **First-paint black screen** ([`5cae550a`](https://github.com/sceneview/sceneview/commit/5cae550a)) — QA pass on 2026-05-11 flagged "Demos noires sur first paint (Camera Controls, Lighting, Animation, Multi Model) — ~5-10s pendant lesquels l'écran est noir, user pense que l'app crash". `LightingDemo` + `MultiModelDemo` already had `LoadingScrim`; this completes the four-demo set by adding the same translucent spinner overlay to `CameraControlsDemo` and `AnimationDemo` (both load non-trivial GLBs — `khronos_damaged_helmet.glb` / `threejs_soldier.glb` — with a multi-second empty-black first-frame window). `GeometryDemo` deliberately skipped (procedural primitives, no model load).



Branch [`claude/magical-lovelace-7176b1`](https://github.com/sceneview/sceneview/tree/claude/magical-lovelace-7176b1) — staged for the next minor cut.

### Added — `RenderQuality` preset (Android)

- **`io.github.sceneview.RenderQuality`** ([`2b04c667`](https://github.com/sceneview/sceneview/commit/2b04c667)) — one-line `Cinematic` / `Default` / `Performance` switch on `SceneView`. Wraps shadows, SSAO, bloom, MSAA, HDR color buffer, and dynamic resolution into three coherent presets so AI assistants generating SceneView code (or devs who don't want to learn what `ambientOcclusionOptions` is) can pick one preset and ship. Individual `view.*` settings still win when set after the preset.
- **`rememberFillLightNode(engine)`** ([`ad81c52a`](https://github.com/sceneview/sceneview/commit/ad81c52a)) — composable factory for a secondary "fill" directional light, mirroring iOS RealityKit's default two-light setup. New `fillLightNode: LightNode?` parameter on `SceneView` defaults to this; pass `null` to keep the single-main-light look.

### Added — Sketchfab streaming scaffold

- **iOS** ([`918faacd`](https://github.com/sceneview/sceneview/commit/918faacd)) — `actor SketchfabService` under `samples/ios-demo/.../Services/`. URLSession + Codable models, on-disk LRU cache (500 MB cap), env-var-based API key (`SKETCHFAB_API_KEY`).
- **Android** ([`72cff080`](https://github.com/sceneview/sceneview/commit/72cff080)) — mirror in `samples/android-demo/.../sketchfab/`. OkHttp + kotlinx-serialization, same 500 MB LRU cache, `BuildConfig.SKETCHFAB_API_KEY` populated from env or `local.properties` (gitignored).
- **CI** ([`7858051f`](https://github.com/sceneview/sceneview/commit/7858051f)) — `build-apks.yml` forwards `secrets.SKETCHFAB_API_KEY` next to the existing `ARCORE_API_KEY` pattern. Forks / PRs from forks with an unset secret build cleanly — the gallery falls back to bundled featured models and disables Sketchfab search at runtime via `SketchfabError.MissingApiKey`.
- **Security note** — V1 scaffold bakes the key into the APK / IPA at build time. V1.1 will route through the mcp-gateway Cloudflare Worker so the master key isn't shipped; demo apps would carry only a short-lived per-user token. `TODO V1.1` markers are in place in `SketchfabConfig.{swift,kt}` and the Gradle build script.

### Changed — Android rendering defaults match iOS RealityKit

Closes the visible quality gap between Android (Filament) and iOS (RealityKit) out of the box. Side-by-side comparison on a Metal-backed Pixel_7a (Apple M3, `-gpu host`) on 5 hero models showed Android looking "blown-out / harsh" because of single-light + shadows-off + sunny-16 exposure defaults.

- **Shadows on** by default ([`ad81c52a`](https://github.com/sceneview/sceneview/commit/ad81c52a)) — `setShadowingEnabled(false → true)` in `SceneFactories.createView()`.
- **Main light intensity 100 000 → 10 000** ([`ad81c52a`](https://github.com/sceneview/sceneview/commit/ad81c52a)) — `DEFAULT_MAIN_LIGHT_COLOR_INTENSITY`. Brings it in line with RealityKit's 1 000-unit directional + IBL contribution. Crank IBL or push intensity back up explicitly when you need outdoor noon punch.
- **Fill light added** ([`ad81c52a`](https://github.com/sceneview/sceneview/commit/ad81c52a)) — secondary directional at 30% main intensity from `(0.5, -0.5, 0.5)`, no shadows. Softens contrast on the shadow side of models.
- **Exposure neutralised** ([`ad81c52a`](https://github.com/sceneview/sceneview/commit/ad81c52a)) — `setExposure(16, 1/125, 100) → (12, 1/200, 200)` (~EV 15 sunny-16 → ~EV 11.6 neutral).
- **SSAO + bloom on** ([`7858051f`](https://github.com/sceneview/sceneview/commit/7858051f)) — `view.ambientOcclusionOptions.enabled = true` and `view.bloomOptions.enabled = true; strength = 0.1f`. Visible grounding gain under metallic / cloth assets, invisible on plain diffuse models. Validated on toy_car / dragon / helmet / lantern / shiba.
- **Filmic tone mapper kept** ([`7858051f`](https://github.com/sceneview/sceneview/commit/7858051f)) — ACES was tested and produces a "cool Hollywood" grade that shifts PBR hero shots away from ground truth. SDK doesn't impose tone preferences — users opt into ACES via `view.colorGrading`. (An earlier SwiftShader-based test had flagged ACES as a "PBR helmet crush" — that turned out to be a software-renderer artifact; the loss disappears on real GPU.)

`ARScene.createARView()` was deliberately left untouched: AR sessions have their own real-world lighting estimation, and layering SSAO / bloom on top of a camera feed is a separate sprint.

### Changed — iOS V1 honest: purge the 4 silent Pareto stubs

Closes [#928](https://github.com/sceneview/sceneview/issues/928) (the 4 stubs in the Pareto-15 minimal API surface).

- **`ModelNode.playAnimation(speed:)`** ([`141eda05`](https://github.com/sceneview/sceneview/commit/141eda05)) — the three `playAnimation(...)` overloads accepted a `speed: Float` parameter but never wired it through. Fixed by capturing the returned `AnimationPlaybackController` and setting `.speed = speed`.
- **`CameraNode.depthOfField(focusDistance:aperture:)`** ([`141eda05`](https://github.com/sceneview/sceneview/commit/141eda05)) — annotated `@available(*, deprecated, message: "...")`. RealityKit's `PerspectiveCameraComponent` does not expose DOF; the method is kept for Android API parity but Xcode now surfaces a clear warning.
- **`CameraNode.exposure(_:)`** ([`141eda05`](https://github.com/sceneview/sceneview/commit/141eda05)) — same treatment. The deprecation message redirects users to `ARSceneView(cameraExposure:)` for AR or to scene lighting intensity for 3D.
- **`LightNode.shadowColor(_:)`** ([`141eda05`](https://github.com/sceneview/sceneview/commit/141eda05)) — `DirectionalLightComponent.Shadow` has no `color` property; the parameter is ignored. Deprecation message points users at `castsShadow(_:)` / `shadowMaximumDistance(_:)`.

### Added — iOS demo: "Coming soon" badges for non-ported demos

- **`DemoStatus` enum** + **`ComingSoonScreen`** ([`567d6476`](https://github.com/sceneview/sceneview/commit/567d6476)) — Android has 37 sample demos, iOS has 16. The other 21 used to be invisible on iOS. Now they appear in the `Scenes` tab list with a "Coming v1.1" badge; tapping routes to an elegant placeholder (sablier icon, version target, links to GitHub issues + the Android demo on Play Store).
- **21 placeholder items added** to `SamplesTab.allScenes()` covering Interaction (Camera Controls / Gesture Editing / Collision / ViewNode), Advanced extras (Post Processing / 2D Shape Extrude / Reflection Probes), Animated Model, Video Texture, and the 12 AR demos that aren't yet on iOS.

### Stitch design assets (UI refonte pending)

Project `15993476369356042112` on Stitch contains the 8 mockup screens for the V1 UI refresh (4 iOS Liquid Glass + 4 Android M3 Expressive). Pending: actual SwiftUI / Compose implementation in `samples/{ios,android}-demo` based on those mockups.

### Added — `sceneview/claude-marketplace` Claude Code plugin

- **New marketplace repo:** [`github.com/sceneview/claude-marketplace`](https://github.com/sceneview/claude-marketplace) (Apache-2.0). Single plugin (`sceneview` v4.0.11) bundling the `sceneview-mcp` server, 11 namespaced contributor commands (`/sceneview:contribute`, `/release`, `/review`, `/test`, `/document`, `/quality-gate`, `/publish-check`, `/sync-check`, `/version-bump`, `/evaluate`, `/maintain`), and 5 cross-platform reminder hooks that fire on edits to nudge Android ↔ iOS ↔ Web ↔ Flutter ↔ RN API parity.
- **Install (Claude Code):**
  ```
  /plugin marketplace add sceneview/claude-marketplace
  /plugin install sceneview@sceneview
  ```
- **Marketplace clone ~256 KB** (vs 1.4 GB if it had lived in the SDK monorepo — split-to-dedicated-repo decision after a multi-agent review flagged the monorepo clone as a ship-blocker).
- **Plugin manifest references its npm-published MCP via `npx`** — no code vendoring, `sceneview-mcp` stays independently versioned on npm.
- **Discovery surfaces wired** ([`01114229`](https://github.com/sceneview/sceneview/commit/01114229)): plugin-install instructions added to `README.md`, `llms.txt`, `mcp/README.md`, `docs/docs/ai-development.md`, `docs/docs/index.md`. GitHub topics on the marketplace repo cover `claude-code`, `claude-plugin`, `mcp`, `3d`, `ar`, `android`, `ios`, `web`, `jetpack-compose`, `swiftui`.

### Added — `.claude/scripts/sync-plugin-versions.sh`

Verifies the `sceneview` plugin's manifest version matches `npm view sceneview-mcp version`. Lives in the marketplace repo (also). Decoupled from `sync-versions.sh` because the plugin tracks the wrapped npm MCP, not `gradle.properties` `VERSION_NAME`.

### Security — sceneview/sceneview HEAD scrub

Removed off-topic personal-portfolio code from the public SDK repo that had nothing to do with SceneView: `hub-gateway/`, `hub-mcp/`, `mcp-gaming/`, `mcp-interior/`, plus the strategy/registry-submission docs that listed unrelated MCPs. Also dropped tracked CDI-sensitive session artefacts (`.claude/handoff*.md`, `.claude/plans/`, `.claude/marketplace-submissions/`, `RERUN-CHECK.md`, hardcoded user paths in samples). The standard employer/portfolio identifier greps return 0 hits in HEAD. Past commits still contain the historical strings — a `git filter-repo` session is the planned followup.

## v4.0.9 — Web unlit parity + Android demo APK -38% + Play Store race fix (2026-05-07)

**Status:** stable. No new library API surface vs v4.0.8 — instead this release bundles cross-platform unlit parity (web + Flutter + RN bridges), big Android sample-app size cuts, and a fix for the Play Store deploy workflow's recurring internal-track race.

### Added — `KHR_materials_unlit` parity on `sceneview-web`

- **`GeometryConfig.unlit()` builder** + `GeometryConfig.unlit: Boolean` field on the web `geometry { … }` DSL. When set, the GLB material gets the standard glTF 2.0 `KHR_materials_unlit` extension — Filament.js supports it natively and skips PBR / IBL evaluation entirely. Closes the cross-platform unlit gap (Android already had `createUnlitColorInstance` in v4.0.8, Apple had `CustomMaterial.unlit`, RN/Flutter bridges shipped `unlit: bool` in v4.0.9 too).
- **Web demo showcase** — per-shape "Unlit" checkbox in [`samples/web-demo`](samples/web-demo/) so users can A/B compare lit-PBR vs unlit on every primitive.

### Added — Cross-platform unlit on bridges

- **React Native** ([`react-native/`](react-native/react-native-sceneview)) — `<GeometryNode unlit={true} />` exposed through the JS Fabric bridge with type-safe `ReadableType.Boolean` parsing on the Android side (anti-crash for JS callers without strict TS). Material cache key bumped from `(color)` to `(color, unlit)` so toggling returns a fresh instance.
- **Flutter** ([`flutter/sceneview_flutter`](flutter/sceneview_flutter)) — `GeometryNode(..., unlit: true)` constructor + `toMap()` field. API-ready for when the Android platform-view bridge gains geometry rendering (currently no-ops `addGeometry`).

### Performance — Android demo APK 161 MB → 100 MB (-38%)

- **9 orphan assets dropped** ([`7a466736`](https://github.com/sceneview/sceneview/commit/7a466736)) — 5 models (`robo_bun.glb`, `coffee_cart.glb`, `koi_fish.glb`, `trumpet.glb`, `casio_keyboard.glb`) + 4 environments (`artist_workshop_2k.hdr`, `comfy_cafe_2k.hdr`, `pav_studio_2k.hdr`, `autumn_field_2k.hdr`) verified unused by every sample app. Phone APK 161 → 131 MB.
- **TV-only assets split** ([`9877918e`](https://github.com/sceneview/sceneview/commit/9877918e), closes [#879](https://github.com/sceneview/sceneview/issues/879)) — moved 6 TV-exclusive models (`nike_air_jordan.glb` 30 MB, `khronos_iridescent_dish.glb`, `khronos_sheen_chair.glb`, `khronos_glam_velvet_sofa.glb`, `toon_cat.glb`, `khronos_duck.glb`) from the shared `android-demo/assets/` symlink target to a TV-demo-private folder. TV demo picks up shared assets via `sourceSets.main.assets.srcDirs += '../android-demo/src/main/assets'`. Phone APK 131 → **100 MB**.
- **Disabled asset-pack module dropped** ([`c2fe9010`](https://github.com/sceneview/sceneview/commit/c2fe9010)) — 186 MB on-disk repo cleanup. The `samples/android-demo-assets/` `com.android.asset-pack` module was disabled (`assetPacks = […]` commented in the demo's build.gradle) but still tracked in git. None of its 25 GLBs were referenced by code.

### Fixed

- **Play Store deploy workflow race** ([`f2829214`](https://github.com/sceneview/sceneview/commit/f2829214)) — added `max-parallel: 1` to the publish job's matrix so the `internal` and `production` tracks upload sequentially. Before this, both jobs would grab the same Google Play Edit ID, one would finish first, and the other would fail with "This Edit has been deleted". Recurred on every tag push since v4.0.5; v4.0.9 deploy uses the new sequential path.
- **iOS demo `MARKETING_VERSION` blind spot** ([`04e75ad5`](https://github.com/sceneview/sceneview/commit/04e75ad5)) — `samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj` was missed for 8+ releases. `sync-versions.sh` now covers it (29 checks, was 28).

### Tested

- **`NoTangentsGlbContractTest`** ([`04e75ad5`](https://github.com/sceneview/sceneview/commit/04e75ad5)) — substring `"TANGENT"` assertion replaced with regex anchored to the `attributes` block, so a future contributor adding `"comment": "no TANGENT"` to the manifest cannot false-positive. Added 6th test pinning BIN chunk byte length math.
- **`TvModelListTest`** ([`9877918e`](https://github.com/sceneview/sceneview/commit/9877918e)) — updated to search both asset folders (TV-only + shared via sourceSets) so missing-asset regressions still fail fast.

### Library API

No public Kotlin / Swift / Filament API changes vs v4.0.8. Maven Central artifacts are bumped for version-tracking and to keep the cross-platform release set coherent (`sceneview`, `arsceneview`, `sceneview-core`, `sceneview-web@4.0.9`, `sceneview-mcp@4.0.11`, SwiftPM `v4.0.9`, Flutter / npm bridges).

### Sample-app review

This release was vetted by 5 parallel Opus reviewers ([commit `04e75ad5`](https://github.com/sceneview/sceneview/commit/04e75ad5)) — 13 findings triaged in 4 buckets (BLOCKING / MAJOR / MINOR / NIT), all BLOCKING + MAJOR + MINOR fixed. Notable: ARFaceDemo overlay had been migrated to opaque blue in v4.0.8, hiding the user's face under a solid mask; switched back to translucent `SceneViewColors.PrimaryOverlay` (alpha 0.4) so the fitted face mesh actually overlays the visible face — which is the entire point of the demo.

## v4.0.8 — Unlit material + 3 demo refresh + AR feature coverage (2026-05-07)

**Status:** stable. Bundles the `createUnlitColorInstance` material API, the AR feature coverage sprint (6 demos + ARRecorder + EIS), three demo refactors driven by on-device QA, and a regression test for the silent-closed #836 GLB-without-TANGENTS bug.

### Added — Unlit colour material

- **`MaterialLoader.createUnlitColorInstance(color)`** — flat-colour material that bypasses lighting entirely. Three overloads: Filament `Color`, Compose `Color`, and `Int`. Use for HUD overlays, gizmos, axes, lines, sprites, AR face/body meshes — anywhere PBR shading would fight the use case. Closes [#871](https://github.com/sceneview/sceneview/issues/871).
- iOS parity: `CustomMaterial.unlit(color:)` (was `.debug(color:)`, now deprecated as alias).
- Sample app migrations: `Axes3DNode`, `CollisionDemo`, `LinesPathsDemo`, and `ARFaceDemo` — the front-camera face-mesh overlay no longer needs an explicit fill light to compensate for the front-camera disabling `ENVIRONMENTAL_HDR`. Removes a long-standing visibility-regression risk.

### Changed — 3D demo refresh

- **`AnimationDemo`** — IBL intensity slider (0–10 000 lux) replaces the hard-coded 5 000 lux baseline so users can dial atmospheric ↔ neutral. HERO orbit lifted from `yHeight = 0.15 m` (low-angle monument) to `0.55 m` (eyes-level) so head + feet stay in frame on portrait viewports.
- **`GeometryDemo`** — chip row is now horizontally scrollable, all primitives spin continuously on Y, and Metallic / Roughness sliders cover the full PBR range from chalky matte (M=0, R=1) to polished mirror (M=1, R=0).
- **`MultiModelDemo`** — refonte from a generic spread-slider carousel to a tabletop living-room display lit by `studio_warm_2k.hdr`. Front row at z=-1.3, back row at z=-1.7. Spread slider removed (the new layout is hand-tuned for the dusk-lit display).
- **`LightingDemo`** — 3×2.4 m backdrop wall + small coloured marker sphere at the light source so directional / point / spot read distinctly. Light pinned at (0, 1.4, 1.0) with tightened spot cone and 4 m falloff.

### Fixed

- **`Scene.kt` cameraManipulator swap reactivity** — `cameraManipulator` is now wrapped in `rememberUpdatedState` so the frame loop reads through a state ref. Callers that swap manipulators at runtime (e.g. `AnimationDemo`'s scripted → Free hand-off, custom mode pickers) now see `getTransform()` route to the new manipulator on the next frame instead of staying stuck on the launch-time value.

### Tested

- **`NoTangentsGlbContractTest`** (5 JVM tests) — pins the canonical "minimal lit primitive without TANGENTS" GLB binary fixture so future `gltfio` bumps cannot silently break the auto-tangent synthesis path that fixes [#836](https://github.com/sceneview/sceneview/issues/836). Closes [#863](https://github.com/sceneview/sceneview/issues/863).

### Added — AR feature coverage (`arsceneview` + `samples/android-demo`)

Five ARCore capabilities that were already wired in the library but had no demo are now showcased, plus one brand-new library feature.

- **`ARRecorder` + `ARSceneView(playbackDataset = ...)`** — first-class ARCore Recording / Playback in SceneView. `rememberARRecorder()` captures the full session (camera frames, IMU, planes, depth, anchors) into an MP4; `playbackDataset: File?` on `ARSceneView` replays that file 1:1 without a phone. Pair with the existing Rerun bridge for record-replay-inspect debugging. Library: `arsceneview/src/main/java/io/github/sceneview/ar/recording/ARRecorder.kt`. Demo: `samples/android-demo/.../ARRecordPlaybackDemo.kt` with LIVE / RECORD / PLAYBACK modes. Recording uses `setAutoStopOnPause(true)` so backgrounding the app produces a clean MP4; optional `recordingRotation` keeps replay upright across orientations.
- **`ARDepthOcclusionDemo`** — toggles `Config.DepthMode.AUTOMATIC` so real-world objects correctly hide virtual ones. Falls back to a clear "device not supported" banner when `isDepthModeSupported` returns false. Library plumbing in `ARCameraStream` was already wired.
- **`ARInstantPlacementDemo`** — `Frame.hitTestInstantPlacement(x, y, 1.0f)` places models the moment the user taps, before plane detection converges. Tracking-method badges flip from "Approximating" to "Tracked" once the trackable promotes to `FULL_TRACKING`.
- **`ARTerrainAnchorDemo`** — geospatial anchor that snaps a model to Google's terrain altitude at any lat/lng. Drop-here button gated on `Earth.EarthState.ENABLED` to avoid silently swallowed `IllegalStateException`s.
- **`ARRooftopAnchorDemo`** — geospatial anchor that snaps to building rooftops. Same Earth-state gate as Terrain.
- **`ARImageStabilizationDemo`** — toggles `Config.ImageStabilizationMode.EIS`. Smooths the camera background image without affecting virtual content. Gates on `Session.isImageStabilizationModeSupported`. Back-camera only.

`llms.txt` gains a new "AR Recording & Playback" section with full record + replay recipes plus a sibling "AR Image Stabilization (EIS)" section; `playbackDataset` appears in the `ARSceneView` reference signature.

### Tested

- `ARRecorderTest`: 21 JVM unit tests pin the Recorder state machine, error paths, and `RecordingConfig` builder calls. Surprising current behaviours pinned: `stop()` does not internally guard the IDLE state, and `attach(newSession)` mid-RECORDING is a pure pointer swap (the original session never receives `stopRecording()` — see warning in the AR Recording & Playback docs).

### Documented

- `docs/docs/ar-recording.md` — new mkdocs page for library consumers (record + replay recipes, caveats, Rerun pairing).
- `samples/android-demo/RECORDING_PLAYBACK.md` — sample-app feature guide for demo users.
- `README.md` — new "Record & Replay AR sessions" sub-section under Developer tools.

### Changed

- `ARSceneView`: new optional `playbackDataset: File? = null` param. Snapshotted at first composition; switch playback files via `key(playbackDataset) { ARSceneView(...) }`. `PlaybackFailedException` is routed to `onSessionFailed`.

## v4.0.7 — ARCore Cloud API key documentation everywhere + npm sceneview-mcp@4.0.9 (2026-05-06)

**Status:** stable. Documentation + MCP-server release.

### Documented

The ARCore Cloud API key requirement (for `Config.CloudAnchorMode.ENABLED`,
`Config.GeospatialMode.ENABLED`, `Config.StreetscapeGeometryMode.ENABLED`) is
now surfaced everywhere a SceneView consumer might look:

- `arsceneview/Module.md` — dedicated "ARCore Cloud API key" section in the
  Dokka-published lib reference (with manifest snippet + build.gradle injection
  + link to the setup guide).
- `llms.txt` (root) + `mcp/llms.txt` + `docs/docs/llms.txt` — warning block
  under the ARSceneScope intro so AI assistants generating Cloud-using code
  emit the manifest/build.gradle wiring automatically.
- `docs/docs/integrations.md` — full setup section in the doc-site Cloud
  Anchor + Room example.
- `mcp/src/guides.ts` (returned by the `get_setup_guide` MCP tool): added the
  API_KEY meta-data + ACCESS_FINE_LOCATION permission + Cloud setup block.
- `mcp/src/explain-api.ts` (returned by `explain_api`): added the missing
  key/permission gotcha to the "common mistakes" list.
- `mcp/src/debug-issue.ts` (returned by `debug_issue`): added Cloud
  manifest snippet to the AR troubleshooting flow.
- `mcp/src/samples.ts`: prepended the setup comment block to the Cloud
  Anchor sample so generated code includes the prereq inline.
- `samples/android-demo/STREETSCAPE_SETUP.md` shipped earlier in v4.0.6 stays
  the canonical step-by-step guide.

### Improved — sample app demos

- `ARStreetscapeDemo` and `ARCloudAnchorDemo` now read
  `com.google.android.ar.API_KEY` from the manifest at runtime (via
  `PackageManager.GET_META_DATA`) and surface a precise "ARCore Cloud API
  key not configured — see STREETSCAPE_SETUP.md" banner instead of letting
  the user wait on "Looking for streetscape geometry…" forever or seeing
  a cryptic `ERROR_NOT_AUTHORIZED` after a tap. No-op for production
  builds (Play Store / App Store ship the key); helpful for forks.

### Internal

- `npm sceneview-mcp` 4.0.8 → **4.0.9** — picks up the regenerated
  `mcp/src/generated/llms-txt.ts` so `npx sceneview-mcp` users see the new
  ARCore Cloud key section in `sceneview://api`.
- 8 Dependabot ip-address moderate alerts cleared via `npm audit fix`
  across 8 lockfiles (commit `a155966b`).
- iOS bundle 362 → 363, `MARKETING_VERSION` 4.0.6 → 4.0.7.

### What's still in flight from v4.0.6 (unchanged)

- Apple TestFlight processing v4.0.6 build 362 (auto-submit pending Apple review).
- Play Store production track for v4.0.6 (Google review pending).

## v4.0.6 — Streetscape Geometry / Geospatial enabled in production (2026-05-06)

**Status:** stable. Activates the AR Streetscape Geometry, Geospatial, and Cloud Anchors demos for Play Store and App Store builds. The library artefacts on Maven Central are unchanged from v4.0.5 — this release only re-builds the sample apps with the now-wired ARCore Cloud API key.

### Fixed

The v4.0.5 sample apps shipped with `com.google.android.ar.API_KEY` empty in the manifest, which left the Streetscape / Geospatial / Cloud Anchors demos disabled at runtime. The wiring landed on main *after* v4.0.5 was tagged (commit `b280b6d9` — `samples/android-demo/build.gradle` reads `ARCORE_API_KEY` from env or `local.properties`, injects it via `manifestPlaceholders`).

v4.0.6 re-cuts the sample-app AAB / iOS archive with the env var supplied by CI (`secrets.ARCORE_API_KEY`, restricted to package `io.github.sceneview.demo` + the debug, upload, and Play App Signing SHA-1s). End users of the published demos can now exercise Streetscape Geometry and Geospatial on the production builds.

### Internal

- iOS `MARKETING_VERSION` 4.0.5 → 4.0.6, `CURRENT_PROJECT_VERSION` 361 → 362 (TestFlight cumulative bundle counter).
- Documentation: `samples/android-demo/STREETSCAPE_SETUP.md` shipped in v4.0.5 stays valid — provisioning a new key follows the same flow.

## v4.0.5 — hotfix: android-demo compile + iOS bundle bump (2026-05-06)

**Status:** stable. Hotfix on top of v4.0.4 — that release's tag triggered Maven Central publication successfully, but the store-bound builds (Play Store APK, App Store iOS archive) failed in CI:

### Fixed

- `samples/android-demo/MainActivity.kt`: `Unresolved reference 'initialDemo'` — leftover reference to the old launch-time deep-link param after the v4.0.4 conflict resolution. Replaced with a `remember { activity?.pendingDemoIdFlow?.value }` capture so the NavHost picks the right start destination on first composition without re-introducing the param.
- `samples/android-demo/demos/PhysicsDemo.kt`: `Assignment type mismatch: actual type is 'Node', but 'SphereNode?' was expected.` — the conflict resolution wrapped the falling spheres in a `Node()` to attach a position via the wrapper, breaking `apply = { nodeRef = this }` because `this` was the wrapper Node, not the inner SphereNode. Collapsed back to `SphereNode(position = …, apply = { nodeRef = this })` since SphereNode supports both.
- `samples/ios-demo/SceneViewDemo.xcodeproj`: `CURRENT_PROJECT_VERSION` 359 → 361 — App Store Connect rejected the v4.0.4 archive (`bundle version must be higher than the previously uploaded version: '360'`).

The v4.0.4 library artefacts on Maven Central are unchanged and still valid. v4.0.5 is intentionally minimal — only the android-demo sample app and the iOS sample app are affected.

## v4.0.4 — Pixel 9 review fixes + library hardening (2026-05-06)

**Status:** stable. Brings PR #851 (87 sample-app fixes + 20 library fixes from the Pixel 9 live-review session that diverged on 2026-04-22 and never made it into v4.0.3) plus the multi-agent-review hardening of its public API surface.

### Fixed — Android demo app (87 commits)

The store-published v4.0.3 APK shipped without the live-review fixes. v4.0.4 brings them all:
- AR demos: Face Mesh now visible (proper TANGENTS quaternion encoding via PR #852), Pose has matte materials + Blender-style axes gizmo, Streetscape falls back to plain AR when geospatial unavailable + links Google Fused Location Provider, Placement multi-model spawn + editable + Clear All, Rerun v2 UX (intro screen, live stream stats card, help dialog).
- 3D demos: Animation default Reveal+Walk + cinematic shots + dragon centred, Geometry plane no longer twisted into a wall, Physics 5×N grid spread + Drop-10 + horizontal floor + diagnostic static sphere, Lighting reactive props, DynamicSky time slider drives illumination, BillboardNode mirror, ViewNode reactive props (closes #856), Custom mesh auto-pause, MultiModel redesign, Lines/Paths 3D helix, Gesture-editing axes gizmo + sliders + live transform readout, Video Big Buck Bunny streaming + cinematic camera + creative surfaces, PostProcessing camera-orbit + SideEffect writes, Debug-overlay interactive node spawner + auto-fit + perf graph + stress test.
- Branding: launcher icons regenerated, palette sweep across collision/AR demos + Text + Billboard, gradient video, Surface base palette adoption.
- QA: deep-link `--es demo <id>` ingress for instrumented tests (coexists with the public scan-to-open URL routing).

### Fixed — sceneview / arsceneview library (20 commits)

- `LightNode` (`SceneScope`) now drives intensity / colour / direction reactively on recomposition (was applying only at first creation).
- `ViewNode`: reactive `position` / `rotation` / `scale` / `isVisible` props on the composable; lifecycle race on post-destroy fixed.
- `Node` / `ModelNode` default `Scale(1f)` regression — was `(1, 0, 0)` singular transform that cascaded NaN through every downstream matrix op (Physics, animations, children).
- `MaterialInstance` reassignment now propagates to all geometry nodes (Sphere/Cube/Plane).
- `onFrame` callback no longer captured stale (was ignoring recomposition).
- AR camera: editable-node gestures isolated from camera gestures.
- AR `AugmentedFaceNode`: tracking state callback always fires (PR #789 follow-up via PR #852).
- New: `FovZoomCameraManipulator` — pinch-to-FOV zoom for orthographic-style framing.
- New: `DefaultCameraManipulator(pinchZoomSpeed, pinchZoomDamping)` — non-linear damping curve, default tuned for dense screens (was abrupt on Pixel 9).

### New — testability surface

- Pure-Kotlin `pinchZoomDelta` and `nextFov` helpers extracted from the gesture detectors so the math curves can be regression-tested on the JVM (no Filament Engine needed). 14 new tests in `:sceneview:test` cover sub-pixel linearity, sign preservation, speed scaling, damping softening, FOV clamps, and default constants.

### API surface — non-breaking by design

- `LightNode(color = …)` parameter placed AFTER `position` (not in slot 3) to preserve positional source-compat for existing 4.0.x callers passing `direction` positionally. Documented in `SceneScope.kt:354`.
- `Engine.kt` `safeDestroy*` helpers retain `runCatching` wrapping (the rebase-rescue PR initially stripped it; restored to avoid ABI break for v4.0.x consumers — see commit message `fd1d820e`).
- `ImageNode.destroy()` deliberate `Texture` retention now documented in a public KDoc with the recommended `bitmap = newBitmap` recycling pattern. Tracked: #874.

### Internal

- 14 new JVM tests (`CameraGestureMathTest`).
- Roborazzi screenshot tests stay `@Ignore`'d (DemoListScreen renderer change tracked separately).
- gradle test deps bumped: robolectric 4.14.1 → 4.16.1, roborazzi 1.43.0 → 1.60.0; new androidxTestExtJunit + androidxTestUiAutomator for instrumented coverage.

### Follow-up issues filed during the rebase rescue

- #873: cache `SurfaceOrientation` in `AugmentedFaceNode.computeTangents` (~30 Hz JNI alloc on hot path).
- #874: frame-deferred destroy queue for `ImageNode` / `ViewNode` GPU textures.

## v4.0.3 — Save & Share Rerun + scan-to-open deep-links (2026-05-06)

**Status:** stable. Maven Central, Swift Package Manager, npm, and Play Store artifacts are published from this tag.

### New — Rerun.io self-serve hosted viewer

- `sceneview.github.io/rerun/` page added — drop a `.rrd` recording on it (or paste a URL) and SceneView opens the embedded Rerun Web Viewer with the right defaults. Removes the need to install the Rerun desktop app for quick AR-debug shares (afe1cc94).
- `RerunBridge.recordToFile(...)` + `share(...)` (`Tier-S` events) ship on Android and iOS with full parity. iOS uses the native share sheet; Android uses `MediaStore`. Wire-format goldens updated (4b8993dd, fa1f8bc1).
- One-command review guide `.claude/scripts/check-rerun.sh` for the Save & Share MVP (58c74d3f).

### New — scan-to-open deep-links

- `https://sceneview.github.io/open/?demo=<id>` resolves to the published Play Store / App Store apps with the right demo pre-selected. README, website, docs all expose QR codes that route from web → installed app → specific demo (e49d4062, c95ed0d6).
- Android App Links: `.well-known/assetlinks.json` now ships both Play App Signing and upload-key SHA-256 fingerprints — the production-signed APK is now correctly verified by Android (133df8ff).
- iOS Universal Links: `SceneViewDemo.entitlements` now declares `applinks:sceneview.github.io` (Associated Domains capability). Pairs with the existing `apple-app-site-association` published on the website (932ac8dc).

### Improved — Play Store CI (canary pattern)

- Push to `main` → AAB uploaded to the Play Store **internal** track only (snapshot for dogfooding) (12f3a5ab).
- Tag `v[0-9]+.[0-9]+.[0-9]+` (this release) → AAB uploaded to internal + production **in parallel** (canary pattern). The `v4.0.3` tag triggers both jobs concurrently (1e247180).
- A real release no longer requires a manual Play Console step — once green CI on the tag, the production review is auto-submitted.

### Fixed — android-demo About version

- `AboutTab` was hard-coding `"v4.0.0-rc.1"`; now reads `BuildConfig.VERSION_NAME` so the published build always shows the truthful version (f516387f).

### Internal

- 11 commits in this release, all on `main`. Tag `v4.0.3` is the GA cut.

---

## v4.0.2 — Crash hardening & reactive ViewNode props (2026-05-06)

**Status:** stable. Maven Central and Swift Package Manager artifacts are published from this tag.

### Fixed — Filament destroy-order crashes

- `RenderableNode.destroy()` now destroys the renderable component before the entity, fixing the `MaterialInstance "view" still in use by Renderable` SIGABRT seen on screen navigation (#849, closes #837, #847).
- `PlaneRenderer.destroy()` routes through `MaterialLoader.destroyMaterial()` to prevent double-free on AR scene teardown (#850).
- `ViewNode.destroy()` and `rememberViewNodeManager` hardened against the post-destroy race that left a leaked `WindowManager` view if `resume()` and `destroy()` interleaved within a single frame (#820, #853).

### Fixed — BillboardNode mirrored texture

- `BillboardNode` (and `TextNode` via inheritance) no longer renders the back face of the plane quad. Switched from `lookAt(camPos)` to `lookTowards(worldPosition - camPos)` so local +Z (front face, correct UVs) faces the viewer. Hardened guard rejects NaN inputs in addition to the zero vector (#838, #854). A 9-test JVM regression suite in `BillboardNodeMathTest` pins the math convention (#858).

### Fixed — ViewNode reactive props

- `ViewNode` composable restores the full reactive prop set (`position`, `rotation`, `scale`, `isVisible`) and switches from `SideEffect` to `DisposableEffect` keyed on scalar components — Compose state changes now propagate without redundant per-recomposition writes (#856, #857). Closes the regression of the original `7d82701c` implementation reintroduced by #842.

### Security

- `hono` bumped to 4.12.17 across `mcp-gateway`, `telemetry-worker` and the bundled MCP packages — resolves the `hono/jsx` SSR XSS via JSX attribute names (9 alerts) (#862).
- `postcss` bumped to 8.5.14 in the same set — resolves XSS via unescaped `</style>` in CSS Stringify Output (4 alerts).
- 0 open Dependabot alerts at the time of this entry.

### Improved — Tooling

- `roborazzi` 1.43.0 → 1.60.0 (#830).
- `dev.romainguy:kotlin-math` reference in `llms.txt` synced to 1.8.0 across all 4 copies (root, website, well-known, bundled MCP) — AI consumers no longer suggest the outdated 1.6.0 dependency (#788 follow-up, #859).
- Marketplace submission packet (OpenAI App Store + MCPize manifest) committed under `.claude/marketplace-submissions/` for cross-session reuse (#855).

### Internal

- Render tests on SwiftShader CI remain `@Ignore`'d — `Filament.capturePixels()` still crashes the emulator. Coverage by iOS simulator, Web Playwright, and Android demo screenshot jobs. Pure-JVM math regressions can land in `:sceneview:test` (see #858 for the pattern).

---

## v4.0.1 — Swift Geometry Primitives, Filament 1.71.0, Hub MCP v0.3.0

**Status:** stable. Maven Central and Swift Package Manager artifacts are published from this tag.

### New — Swift Geometry Primitives

- `torus()` and `capsule()` added to `SceneViewSwift` geometry API, matching the Android/KMP surface
- `ConeNode`, `TorusNode`, `CapsuleNode` documented in docs/nodes.md

### Fixed — Filament 1.71.0 Materials

- Recompiled 6 `.filamat` materials for Filament 1.71.0 (closes #818)
- All material binaries updated in `arsceneview/src/main/assets/`

### Improved — Hub MCP v0.3.0 (78 tools)

- 78 tools across 11 bridge-API MCPs (up from 52)
- `gaming-3d-mcp` and `interior-design-3d-mcp` `files[]` glob fix — tarball no longer ships incomplete
- FREE_TOOLS count corrected (14 → 23)

### Improved — Android Samples

- Layout and `scaleToUnits` tuned across all 24 Android demo scenes for better camera framing
- PhysicsDemo layout refined for Pixel 9 QA

---

## v4.0.0 — Declarative Compose DSL, Rerun.io AR Debug, MCP Gateway & Cross-Platform Bridges

**Status:** stable. Maven Central and Swift Package Manager artifacts are published from this tag.

**Backward compatible** with 3.6.x. Existing code compiles and runs unchanged against 4.0.0.

### New — Declarative Compose DSL (breaking rename, additive)

Renamed the top-level composables from `Scene`/`ARScene` to `SceneView { }` / `ARSceneView { }` across all public surfaces (KDocs, MCP packages, sample apps, docs, llms.txt, README, website). The old names are still accepted via deprecated aliases — no callers break.

- Nodes are now declared as composables inside the trailing content lambda; imperative node management is no longer the primary API.
- `LightNode`'s `apply` is a named parameter (`apply = { intensity(…) }`), not a trailing lambda — matches the Compose convention for layout-affecting side effects.
- `rememberModelInstance(modelLoader, "models/file.glb")` returns `null` while loading; all samples handle the null case explicitly.

### New — AR Debug via Rerun.io

Stream an ARCore (Android) or ARKit (iOS) session to the [Rerun](https://rerun.io) viewer for scrub-and-replay debugging. Same JSON-lines wire format on both platforms, single Python sidecar handles both.

- **Android:** new `io.github.sceneview.ar.rerun.RerunBridge` + `rememberRerunBridge` composable helper. Non-blocking `Dispatchers.IO` scope, `Channel.CONFLATED` drop-on-backpressure, rate-limited 10 Hz by default, runtime `setEnabled()` kill switch. Zero new Gradle dependencies.
- **iOS:** new `SceneViewSwift.RerunBridge` (`@ObservableObject` with `@Published eventCount`), `Network.framework` `NWConnection` on a dedicated utility queue. New `ARSceneView.onFrame { frame, arView in … }` modifier — usable independently of the bridge for any per-frame custom logic.
- **Wire format:** 5 event types (`camera_pose`, `plane`, `point_cloud`, `anchor`, `hit_result`), byte-identical output from Kotlin and Swift, enforced by 24 golden-string tests (12 per platform).
- **Python sidecar:** `tools/rerun-bridge.py` — reads the TCP stream and re-logs each event as the matching Rerun archetype (`Transform3D`, `LineStrips3D`, `Points3D`). Spawns the Rerun viewer automatically via `rr.init(spawn=True)`.
- **Playground:** new "AR Debug (Rerun)" example in the `ar-spatial` category with per-platform code tabs.
- **Sample apps:** new `RerunDebugDemo` tile in `samples/android-demo` (Samples tab) and `samples/ios-demo` (Scenes → AR category).

### New — `rerun-3d-mcp@1.0.0` on npm

New dedicated MCP server (`npx rerun-3d-mcp`) generating Rerun integration boilerplate from natural-language prompts. 5 tools, 73 vitest tests, Apache-2.0. Tarball 13.6 kB.

### New — MCP Gateway (Cloudflare Workers + Stripe)

Production-grade monetization layer for `sceneview-mcp`:

- Cloudflare Worker (`gateway/`) with Hono router, D1 database, KV namespace.
- Stripe-first anonymous checkout: no login wall — user clicks CTA, pays, receives API key by email via Stripe webhook + KV single-use handoff.
- 4 plans: Free / Pro (€19) / Team (€49) / Enterprise — with tier gating and per-plan rate limiting.
- `POST /mcp` proxy with `X-Api-Key` auth, lite mode detection, and upstream routing.
- Dashboard-less by design: billing managed entirely through the Stripe Customer Portal.
- 168 tests passing across gateway + hub packages.
- Live in production at `https://sceneview-mcp.mcp-tools-lab.workers.dev`.

### New — Anonymous telemetry worker

`sceneview-mcp` now sends lightweight anonymous usage telemetry (tool name, tier, timestamp — no personal data) to a Cloudflare Worker via batched HTTP. Sponsor CTA fires every 10 tool calls.

### New — `sceneview-mcp` on `@latest` npm tag (4.0.0)

`sceneview-mcp@4.0.0` is promoted to the `@latest` dist-tag. Previous `@latest` was `3.6.5`; `@next` pointed to `4.0.0-rc.5`. The `publishConfig: { tag: "next" }` guard in `package.json` has been removed now that the gateway go-live pipeline has verified a real paying customer.

### New — Cross-platform bridges

- **Flutter:** `flutter/sceneview_flutter` — PlatformView bridge to SceneView on Android + SceneViewSwift on iOS; Kotlin 2.0 + Compose Compiler plugin compatibility fixed.
- **React Native:** `react-native/react-native-sceneview` — Fabric/Turbo bridge with native `android/` and `ios/` modules scaffolded.
- **Web:** `sceneview-web` Kotlin/JS package (`npm view sceneview-web`) — Filament.js (WASM) + WebXR, webpack 5 polyfills unblocked.

### New — Empire Analytics dashboard

`website-static/` now includes a GA4-backed analytics dashboard (`/analytics`) for tracking playground interactions, MCP install events, and Stripe checkout funnels.

### Fixes

- **NodeAnimator (#388):** `NodeAnimator` now writes animated values back to the target `Node`'s transform fields on every frame, fixing silent no-op animations that computed but discarded results.
- **Render tests (#803):** Fixed intermittent SwiftShader JVM crashes in CI by sharing a single `Engine` instance per test class. The class-level `@Ignore` workarounds have been removed.
- **AR camera exposure (#792):** Added `cameraExposure` parameter to `ARSceneView` composable.
- **customer_creation bug:** `stripe-client.ts` now guards `form.customer_creation = "always"` with `if (mode === "payment")`, preventing a Stripe 400 error on subscription checkouts.

### Tests

- 16 new JVM tests in `arsceneview` (Rerun wire format + socket integration).
- 12 new Swift tests in `SceneViewSwiftTests` (cross-platform wire-format parity).
- 73 new vitest tests in `mcp/packages/rerun`.
- 90+ new unit tests across `sceneview` and `arsceneview` (#814).
- 168 gateway/hub tests.

### Dependencies

- AGP bumped `8.11.1 → 8.13.2`, `maven-publish 0.35.0 → 0.36.0`.
- `activesupport` bumped `>= 7.2.3.1` (CVE-2026-33176/33170/33169).

### Demo apps

- `samples/android-demo`: Sprint 1 refactor — 4-tab nav replaced with categorized list, 20 demos (including RerunDebugDemo).
- `samples/android-tv-demo` + `samples/web-demo`: broken asset refs fixed; all 8 previously-404 GLB/USDZ/HDR paths resolved.
- `samples/ios-demo`: AR Debug demo added in Scenes → AR category.

### Version sweep

`gradle.properties` `VERSION_NAME`, all `gradle.properties` submodule files, npm packages, Flutter `pubspec.yaml` + podspec, `llms.txt`, docs, website, samples — synced to `4.0.0` via `.claude/scripts/sync-versions.sh --fix`.

---

## v4.0.0-rc.1 — SceneView ↔ Rerun.io integration (Release Candidate)

**Status:** release candidate. Maven Central and Swift Package Manager artifacts are **not** published from this tag — pin to `4.0.0-rc.1` manually to test, or wait for the `v4.0.0` stable tag.

**Strictly additive** to 3.6.2. Existing 3.6.x code compiles and runs unchanged.

### New — AR Debug via Rerun.io

Stream an ARCore (Android) or ARKit (iOS) session to the [Rerun](https://rerun.io) viewer for scrub-and-replay debugging. Same JSON-lines wire format on both platforms, single Python sidecar handles both.

- **Android:** new `io.github.sceneview.ar.rerun.RerunBridge` + `rememberRerunBridge` composable helper. Non-blocking `Dispatchers.IO` scope, `Channel.CONFLATED` drop-on-backpressure, rate-limited 10 Hz by default, runtime `setEnabled()` kill switch. Zero new Gradle dependencies.
- **iOS:** new `SceneViewSwift.RerunBridge` (`@ObservableObject` with `@Published eventCount`), `Network.framework` `NWConnection` on a dedicated utility queue. New `ARSceneView.onFrame { frame, arView in … }` modifier wired to the existing `ARSessionDelegate.session(_:didUpdate:)` — usable independently of the bridge for any per-frame custom logic.
- **Wire format:** 5 event types (`camera_pose`, `plane`, `point_cloud`, `anchor`, `hit_result`), byte-identical output from Kotlin and Swift (enforced by 24 golden-string tests, 12 per platform).
- **Python sidecar:** `samples/android-demo/tools/rerun-bridge.py` — reads the TCP stream and re-logs each event as the matching Rerun archetype (`Transform3D`, `LineStrips3D`, `Points3D`). Spawns the Rerun viewer automatically via `rr.init(spawn=True)`.
- **Playground:** new "AR Debug (Rerun)" example in the `ar-spatial` category — embeds the official Rerun Web Viewer from `app.rerun.io` next to the SceneView canvas with per-platform code tabs for Android / iOS / Web / Flutter / React Native / Desktop / Claude.
- **Sample apps:** new `RerunDebugDemo` tile in both `samples/android-demo` (Samples tab) and `samples/ios-demo` (Scenes → AR category).

### New — `rerun-3d-mcp@1.0.0` on npm

New dedicated MCP server — `npx rerun-3d-mcp` — that generates the Rerun integration boilerplate from natural-language prompts in any MCP client (Claude, Cursor, etc.). 5 tools:

- `setup_rerun_project` — Gradle / SPM / Web / Python scaffolding with boilerplate
- `generate_ar_logger` — Kotlin or Swift AR streaming helper, parameterized by data types and rate
- `generate_python_sidecar` — TCP → `rerun-sdk` Python bridge
- `embed_web_viewer` — HTML + module-script snippets for `@rerun-io/web-viewer`
- `explain_concept` — focused docs for `rrd`, `timelines`, `entities`, `archetypes`, `transforms`

Published Apache-2.0. 73 vitest tests. Tarball size 13.6 kB (9 files).

### New — `sceneview-mcp@4.0.0-rc.1` on `@next` npm tag

`sceneview-mcp` gains the Rerun integration docs via the regenerated `sceneview://api` resource (82.5 kB, +5.4 kB vs 3.6.4). Stays on the `@next` dist-tag — `@latest` is intentionally pinned to `3.6.4` until the gateway go-live pipeline has a first real paying customer (see `NOTICE-2026-04-11-mcp-gateway-live.md`). Install the RC with `npx sceneview-mcp@next`.

Adds `publishConfig: { tag: "next" }` to `mcp/package.json` so future sessions can't accidentally promote the RC to `@latest` by running a bare `npm publish`.

### New — AR camera exposure control (#792)

- Added `cameraExposure` parameter to `ARSceneView` composable, allowing developers to programmatically control the camera exposure applied to the AR scene.

### Fixes

- **Render tests** (#803): Fixed intermittent SwiftShader JVM crashes in CI by sharing a single `Engine` instance per test class instead of creating and tearing down one per test method. Affected classes (`GeometryRenderTest`, `VisualVerificationTest`, `LightingRenderTest`, `RenderSmokeTest`) are now stable; the class-level `@Ignore` guards added as a temporary workaround have been removed.
- **MCP tiers test**: Removed stale Polar URL from `tiers.test.ts` that was causing a test failure after the Polar → Stripe migration.

### Tests

- 16 new JVM tests in `arsceneview` (12 golden-JSON for `RerunWireFormat`, 4 socket integration for `RerunBridge` with a mock `ServerSocket`)
- 12 new Swift tests in `SceneViewSwiftTests` — identical golden strings, enforcing cross-platform wire-format parity at build time
- 73 new vitest tests in `mcp/packages/rerun` — 100% tool coverage
- 90+ new unit tests across `sceneview` and `arsceneview` modules (#814)
- Full suite validation:
  - `./gradlew :arsceneview:compileDebugKotlin :arsceneview:testDebugUnitTest` ✓
  - `./gradlew :samples:android-demo:assembleDebug` ✓
  - `swift build --package-path SceneViewSwift` ✓
  - `swift test --package-path SceneViewSwift --filter Rerun*` ✓
  - `xcodebuild -project samples/ios-demo/SceneViewDemo.xcodeproj -scheme SceneViewDemo -destination 'generic/platform=iOS Simulator'` ✓

### Version bump — 3.6.2 → 4.0.0-rc.1

Propagated to 28 files via `.claude/scripts/sync-versions.sh --fix` + manual touches on docs/website/samples. The 4.0.0 major bump reflects two new capabilities (Rerun integration + the `4.0.0-beta.1` gateway lite proxy shipped earlier this day by a parallel session), not breaking API changes — 3.6.x code compiles unchanged against 4.0.0-rc.1.

### Release workflow

Git tag `v4.0.0-rc.1` + GitHub pre-release created. `release.yml` only matches strict semver `v[0-9]+.[0-9]+.[0-9]+`, so this RC tag does **not** trigger Maven Central / SPM publish. Promote to stable by bumping to `v4.0.0` and tagging again.

---

## v3.6.2 — Cross-Platform Parity + Render Testing

### Architecture
- Extract `SceneRenderer` — shared render loop between SceneView and ARSceneView
- Decompose `Node` god class into `NodeGestureDelegate`, `NodeAnimationDelegate`, `NodeState`
- Extract `ARPermissionHandler` interface (testable without Activity)
- Fix `ModelLoader.releaseSourceData()` memory leak
- Clean legacy Java collision code

### Quality
- Add 175 JVM unit tests for sceneview module
- Add 15 JVM unit tests for arsceneview module
- Add 63 KMP tests for sceneview-core
- Add 18 Swift tests for SceneViewSwift (ShapeNode)
- Fix 8 MCP test regressions
- Add pre-push quality gate script
- Stability audit: all platforms PASS

### Demo Apps
- Rebrand to "3D & AR Explorer" (iOS + Android)
- iOS: Add model gallery, favorites, share, categorized browsing
- Android: Material 3 Expressive rewrite, 4 tabs, 40 models
- Fix Play Store build (duplicate assets in asset pack)
- Fix App Store build (private init access level)
- Fix AR camera tone mapper (rememberView → rememberARView)

### Website
- Redesign 8 sections on homepage
- Rewrite Showcase page from scratch
- Playground: 7 platform tabs, camera manipulator, Open in Claude
- Playground: geometry primitives preview, AR placeholders
- Fix Docs 404 (redirect page)
- Auto-deploy GitHub Pages workflow

### Cross-Platform
- iOS: Add ShapeNode (23/24 Android parity)
- iOS: Fix GeometryMaterial.custom(), ViewNode platform guard
- Web: Fix SCENEVIEW_VERSION (1.3.0 → 3.6.0)
- TV: Fix missing assets (would crash at runtime)
- MCP: Align version 3.5.5 → 3.6.0
- Flutter + React Native: Prepare for publication
- CI: Web builds now blocking, Gradle verification added

---

## 3.6.0 — Comprehensive quality audit, SwiftUI fixes, website migration (2026-03-31)

### SceneViewSwift
- Fixed SceneSnapshot visionOS compilation (ARView unavailable)
- Fixed VideoNode memory leak (NotificationCenter observer never removed)
- Fixed CameraNode macOS support (removed unnecessary platform guards)
- Removed unreachable dead code in GeometryNode

### Website
- Migrated ALL pages from model-viewer/Three.js to sceneview.js
- Removed Three.js (53K LOC) and model-viewer.min.js
- Rewrote sceneview-demo.html to use SceneView.modelViewer() API
- Fixed 3 demo pages crashing from non-existent API calls
- Fixed model paths in claude-3d.html
- Deleted 5 dead demo pages + fixed sitemap.xml
- Added 404.html page for GitHub Pages
- Fixed og:image/twitter:image meta tags (SVG → PNG) across all 8 pages
- Fixed sceneview.js version mismatch (runtime 1.5.0 → 3.6.0)
- Fixed IBL path (relative → absolute) for embed/preview subdirectory pages
- Improved synthetic IBL fallback lighting for Claude Artifacts

### Branding
- Generated 22 PNG exports from SVG sources (logo, app icon, favicon, social, npm, store)
- Created favicon.ico (multi-resolution)
- Updated Open Collective: logo, cover, tiers (Backer $10, Sponsor $50, Gold $200), 10 tags

### AI Integration
- Added Claude Artifacts section to llms.txt (HTML template, CDN URLs, 26 models)
- Updated MCP tool count: 22 → 26 tools, 2360 tests across 98 suites

### Dependencies
- Bumped Filament 1.70.0 → 1.70.1

### CI/CD
- Fixed maintenance.yml (Filament version grep, graceful fallback)
- Fixed docs.yml (download-artifact version, deploy retry)
- All 10 workflows verified green

### Version alignment
- Updated 100+ files from 3.5.0/3.5.1 to 3.6.0
- All satellite MCPs (automotive, gaming, healthcare, interior) aligned

---

## 3.5.1 — macOS support, environment picker, MCP 3.5.3 (2026-03-29)

### Apple platforms
- Native macOS support in SceneViewSwift (all source files + demo app)
- macOS App Store submission (build 357, pending review)
- iOS App Store submission (build 355, pending review)
- Environment picker UI with 6 HDR presets (Studio, Outdoor, Sunset, Night, Warm, Autumn)
- Proper macOS app icon sizes (16px to 1024px)
- Swift 6 strict concurrency fix (`@MainActor` on HapticManager)

### MCP Server v3.5.3
- Updated all dependency references from 3.4.7 to 3.5.0
- Published to npm as sceneview-mcp@3.5.3
- 1204 tests passing

### CI/CD
- Extended app-store.yml with macOS deploy job (parallel iOS + macOS)
- Fixed TestFlight deploy failure (Swift 6 concurrency)

### Documentation
- Added ViewNode, SceneSnapshot, SceneEnvironment.allPresets to llms.txt
- Rebuilt docs site — zero stale version references
- Fixed CDN versions in README (1.2.0 → 3.5.1) and website (1.4.0 → 3.5.1)

### Assets
- URL-based model loading (Android + iOS)
- 6 iOS HDR environments
- Progressive texture loading (Filament async)
- 25 models migrated to GitHub Releases CDN (Play Store compliance)

## 3.5.0 — Full coherence audit, version alignment (2026-03-29)

### Version coherence
- Unified all version references across 60+ files to 3.5.0
- Fixed module gradle.properties (sceneview, arsceneview, sceneview-core)
- Updated MCP source + dist files, docs, website, samples, Flutter, React Native
- Fixed Flutter/React Native Android build files (were still on 2.3.0)

### Documentation
- Updated llms.txt, all docs, codelabs, cheatsheets, quickstarts
- Updated CLAUDE.md code samples and platform table
- Cross-platform version consistency across all READMEs

## 3.4.7 — MCP 18 tools, orbit fix, geometry demo (2026-03-26)

### MCP Server v3.4.13
- 4 new tools: `get_platform_setup`, `migrate_code`, `debug_issue`, `generate_scene`
- 834 tests across all tools

### Bug fixes
- Orbit controls: corrected inverted horizontal/vertical camera drag
- 3 core math/collision bugs fixed
- Removed stale CI job

### Website
- Geometry demo: mini-city with 4 presets (City, Park, Abstract, Minimal)
- Meta tags, sitemap, favicon, canonical URLs polished

---

## 3.4.6 — Procedural 3D geometry in Claude Artifacts (2026-03-26)

### Highlights
- `create_3d_artifact` MCP tool with geometry type: procedural shapes with PBR materials
- SceneView.js v1.1.0 published to npm: one-liner web 3D with auto Filament WASM loading
- Filament.js PBR rendering on website (replaced model-viewer)
- 9 MCP servers all at v2.0.0

---

## 3.4.5 — SceneView Web with Filament.js WASM (2026-03-26)

### Features
- Real 3D rendering in browser via Google Filament compiled to WebAssembly
- 25 KB bundle (+ Filament.js from CDN)
- Live demo at sceneview.github.io

### Other
- Website mobile polish, 50+ broken links fixed
- GitHub Sponsors: 3 new tiers; Polar.sh approved with Stripe
- MCP v3.4.9: `create_3d_artifact` tool (590 tests)

---

## 3.4.4 — Play Store readiness, MCP legal (2026-03-25)

### Features
- Android demo: Play Store readiness (crash prevention, dark mode, store listing)
- MCP Server: Terms of Service, Privacy Policy, disclaimers added
- GitHub Sponsors tier structure

---

## 3.4.3 — Embeddable 3D widget (2026-03-25)

### Features
- Embeddable 3D viewer via single `<iframe>` snippet
- MCP `render_3d_preview` accepts code snippets and direct model URLs
- Web demo: branded UI, model selector, loading indicator

---

## 3.4.2 — Critical AR fix, MeshNode improvement (2026-03-25)

### Breaking fix
- AR materials regenerated for Filament 1.70.0 — previous materials crashed all AR apps

### Features
- `MeshNode` now accepts optional `boundingBox` parameter

### Security
- 6 Dependabot vulnerabilities fixed, 15 audit issues resolved
- 28 stale repository references updated

---

## 3.4.1 — Website, smart links, 3D preview (2026-03-25)

### Features
- Website rebuilt: Kobweb replaced with static HTML/CSS/JS + model-viewer 3D
- Smart links: `/go` (platform redirect), `/preview` (3D preview), `/preview/embed` (iframe viewer)
- MCP `render_3d_preview` tool for AI-generated 3D previews

### Infrastructure
- 21 secrets configured (Apple + Android + Maven + npm)
- README rewritten (622 to 200 lines)

---

## 3.4.0 — Multi-platform expansion (2026-03-25)

### New platforms
- **Web** — `sceneview-web` module: Filament.js (WASM) rendering + WebXR AR/VR
- **Desktop** — `samples/desktop-demo`: Compose Desktop, software 3D renderer
- **Android TV** — `samples/android-tv-demo`: D-pad controls, model cycling
- **Flutter** — `samples/flutter-demo`: PlatformView bridge (Android + iOS)
- **React Native** — `samples/react-native-demo`: Fabric bridge (Android + iOS)

### Android showcase
- Unified `samples/android-demo` — Material 3 Expressive, 4 tabs, 14 demos
- Blue branding with isometric cube icon

### Infrastructure
- **MCP Registry** — SceneView MCP published at `io.github.sceneview/mcp`
- **21 GitHub Secrets** — Android + iOS + Maven + npm fully configured
- **Apple Developer** — Distribution certificate, provisioning profile, API key
- **CI/CD** — Play Store + App Store workflows ready

### Samples cleanup
- 15 obsolete samples deleted, merged into unified platform demos
- `{platform}-demo` naming convention across all 7 platforms
- Code recipes preserved in `samples/recipes/`

### Fixes
- material-icons-extended pinned to 1.7.8 (1.10.5 not published on Google Maven)
- wasmJs target disabled (kotlin-math lacks WASM variant)
- AR emulator script updated for new sample structure

---

## 3.3.0 — Unified versioning, cross-platform, website

### Version unification
- **All modules aligned to 3.3.0** — sceneview, arsceneview, sceneview-core, MCP server, SceneViewSwift, docs, and all references across the repo are now at a single unified version

### SceneViewSwift (Apple)
- **iOS 17+ / macOS 14+ / visionOS 1+** via RealityKit — alpha
- Node types: ModelNode, AnchorNode, GeometryNode, LightNode, CameraNode, ImageNode, VideoNode, PhysicsNode, AugmentedImageNode
- PBR material system with textures
- Swift Package Manager distribution

### SceneViewSwift — new nodes and enhancements
- **DynamicSkyNode** — procedural time-of-day sky with sun position, atmospheric scattering
- **FogNode** — volumetric fog with density, color, and distance falloff
- **ReflectionProbeNode** — local cubemap reflections for realistic environment lighting
- **ModelNode enhancements** — named animation playback, runtime material swapping, collision shapes
- **LightNode enhancements** — shadow configuration, attenuation radius and falloff
- **CameraNode enhancements** — field of view, depth of field, exposure control

### MCP server — iOS support
- **8 Swift sample snippets** for iOS code generation
- **`get_ios_setup`** tool for Swift/iOS project bootstrapping
- **Swift code validation** in `validate_code` tool
- iOS-specific guides and documentation

### Tests
- **65+ new tests** covering edge cases and platform-specific behavior
- Test coverage for all 15+ SceneViewSwift node types
- Platform tests for iOS-specific RealityKit integration

### Website
- Platform logo ticker on homepage — infinite-scroll marquee showing all supported platforms and technologies (Android, iOS, macOS, visionOS, Compose, SwiftUI, Filament, RealityKit, ARCore, ARKit, Kotlin, Swift)
- CSS-only animation with fade edges, hover-to-pause, dark mode support

### Documentation
- Updated ROADMAP.md to reflect current state (SceneViewSwift exists, phased plan revised)
- Updated PLATFORM_STRATEGY.md — native renderer per platform architecture (Filament + RealityKit)
- All codelabs, cheatsheet, migration guide updated to 3.3.0
- **iOS quickstart guide** — step-by-step setup for SceneViewSwift
- **iOS cheatsheet** — quick reference for SwiftUI 3D/AR patterns
- **2 SwiftUI codelabs** — hands-on tutorials for iOS 3D scenes and AR

---

## 3.1.2 — Sample polish, CI fixes, maintenance tooling

### Fixes
- `autopilot-demo`: remove deprecated `engine` parameter from `PlaneNode`, `CubeNode`, `CylinderNode` constructors (API aligned with composable node design)
- CI: fix AR emulator stability — wait for launcher, dismiss ANR dialogs, kill Pixel Launcher before screenshots

### Sample improvements
- `model-viewer`: scale up Damaged Helmet 0.25 → 1.0; add Fox model (CC0, KhronosGroup glTF-Sample-Assets) with model picker chip row
- `camera-manipulator`: scale up model 0.25 → 1.0; add gesture hint bar (Drag·Orbit / Pinch·Zoom / Pan·Move)

### Developer tooling
- `/maintain` Claude Code skill + daily maintenance GitHub Action for automated SDK upkeep
- AR emulator CI job using x86\_64 Linux + ARCore emulator APK for screenshot verification
- `ROADMAP.md` added covering 3.2–4.0 milestones

## 3.1.1 — Build compatibility patch

- Downgrade AGP from 8.13.2 → 8.11.1 for Android Studio compatibility
- Update AGP classpath in root `build.gradle` to match
- Refresh `gltf-camera` sample: animated BrainStem character + futuristic rooftop night environment

## 3.1.0 — VideoNode, reactive animation API

### New features
- `VideoNode` — render a video stream (MediaPlayer / ExoPlayer) as a textured 3D surface
- Reactive animation API — drive node animations from Compose state
- `ViewNode` rename — `ViewNode2` unified into `ViewNode`

### Fixes
- `ToneMapper.Linear` in `ARScene` prevents overlit camera background
- `ImageNode` SIGABRT: destroy `MaterialInstance` before texture on dispose
- `cameraNode` registered with `SceneNodeManager` so HUD-parented nodes render correctly
- Entities removed from scene before destroy to prevent SIGABRT
- `UiHelper` API corrected for Filament 1.56.0

### AI tooling
- MCP server: `validate_code`, `list_samples`, `get_migration_guide` tools + live Issues resource
- 89 unit tests for MCP validator, samples, migration guide, and issues modules

## 3.0.0 — Compose-native rewrite

### Breaking changes

The entire public API has been redesigned around Jetpack Compose. There is no source-compatible
upgrade path from 2.x; see the [Migration guide](https://github.com/sceneview/sceneview/blob/main/MIGRATION.md) for a step-by-step walkthrough.

#### `Scene` and `ARScene` — new DSL-first signature

Nodes are no longer passed as a list. They are declared as composable functions inside a
trailing content block:

```kotlin
// 2.x
Scene(
    childNodes = rememberNodes {
        add(ModelNode(modelInstance = loader.createModelInstance("helmet.glb")))
    }
)

// 3.0
Scene {
    rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
        ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
    }
}
```

#### `SceneScope` — new composable DSL

All node types (`ModelNode`, `LightNode`, `CubeNode`, `SphereNode`, `CylinderNode`, `PlaneNode`,
`ImageNode`, `ViewNode`, `MeshNode`, `Node`) are now `@Composable` functions inside `SceneScope`.
Child nodes are declared in a `NodeScope` trailing lambda, matching how Compose UI nesting works.

#### `ARSceneScope` — new AR composable DSL

All AR node types (`AnchorNode`, `PoseNode`, `HitResultNode`, `AugmentedImageNode`,
`AugmentedFaceNode`, `CloudAnchorNode`, `TrackableNode`, `StreetscapeGeometryNode`) are now
`@Composable` functions inside `ARSceneScope`.

#### `rememberModelInstance` — async, null-while-loading

```kotlin
// Returns null while loading; recomposes with the instance when ready
val instance = rememberModelInstance(modelLoader, "models/helmet.glb")
```

#### `SurfaceType` — new enum

Replaces the previous boolean flag. Controls whether the 3D surface renders behind Compose layers
(`SurfaceType.Surface`, SurfaceView) or inline (`SurfaceType.TextureSurface`, TextureView).

#### `PlaneVisualizer` — converted to Kotlin

`PlaneVisualizer.java` has been removed. `PlaneVisualizer.kt` replaces it.

#### Removed classes

The following legacy Java/Sceneform classes have been removed from the public API:

- All classes under `com.google.ar.sceneform.*` — replaced by Kotlin equivalents under the same
  package path (`.kt` files).
- All classes under `io.github.sceneview.collision.*` — replaced by Kotlin equivalents.
- All classes under `io.github.sceneview.animation.*` — replaced by Kotlin equivalents.

#### Samples restructured

All samples are now pure `ComponentActivity` + `setContent { }`. Fragment-based layouts have been
removed. The `model-viewer-compose`, `camera-manipulator-compose`, and `ar-model-viewer-compose`
modules have been merged into `model-viewer`, `camera-manipulator`, and `ar-model-viewer`
respectively.

### Bug fixes

- **`ModelNode.isEditable`** — `SideEffect` was resetting `isEditable` to the parameter default
  (`false`) on every recomposition, silently disabling gestures when `isEditable = true` was set
  only inside `apply { }`. Pass `isEditable = true` as a named parameter to maintain it correctly.
- **ARCore install dialog** — Removed `canBeInstalled()` pre-check that threw
  `UnavailableDeviceNotCompatibleException` before `requestInstall()` was called, preventing the
  ARCore install prompt from ever appearing on fresh devices.
- **Camera background black** — `ARCameraStream` used `RenderableManager.Builder(4)` with only
  1 geometry primitive defined (invalid in Filament). Fixed to `Builder(1)`.
- **Camera stream recreated on every recomposition** — `rememberARCameraStream` used a default
  lambda parameter as a `remember` key; lambdas produce a new instance on every call, making the
  key unstable. Fixed by keying on `materialLoader` only.
- **Render loop stale camera stream** — The render-loop coroutine captured `cameraStream` at
  launch; recomposition could recreate the stream while the loop kept updating the old (destroyed)
  one. Fixed with an `AtomicReference` updated via `SideEffect`.

### New features

- **`SceneScope` / `ARSceneScope`** — fully declarative, reactive 3D/AR content DSL
- **`NodeScope`** — nested child nodes using Compose's natural trailing lambda pattern
- **`SceneNodeManager`** — internal bridge that syncs Compose snapshot state with the Filament
  scene graph, enabling reactive updates without manual `addChildNode`/`removeChildNode` calls
- **`SurfaceType`** — explicit surface-type selection (`Surface` vs `TextureSurface`)
- **`ViewNode`** — Compose UI content rendered as a 3D plane surface in the scene
- **`Engine.drainFramePipeline()`** — consolidated fence-drain extension for surface resize/destroy
- **`rememberViewNodeManager()`** — lifecycle-safe window manager for `ViewNode` composables
- **Autopilot Demo** — new sample demonstrating autonomous animation and scene composition
- **Camera Manipulator** — new dedicated sample for orbit/pan/zoom camera control
- **`Node.scaleGestureSensitivity`** — new `Float` property (default `0.5`) that damps
  pinch-to-scale gestures. Applied as `1f + (rawFactor − 1f) × sensitivity` in `onScale`,
  making scaling feel progressive without reducing the reachable scale range. Set it per-node in
  the `apply` block alongside `editableScaleRange`.
- **AR Model Viewer sample** — redesigned with animated scanning reticle (corner brackets +
  pulsing ring), model picker (Helmet / Rabbit), auto-dismissing gesture hints,
  `enableEdgeToEdge()`, and a clean Material 3 UI.

---

## 2.3.0

- AGP 8.9.1
- Filament 1.56.0 / ARCore 1.48.0
- Documentation improvements
- Camera Manipulator sample renamed
