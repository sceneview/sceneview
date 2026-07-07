# Changelog

## Unreleased

## v4.21.0 — Consumer-grade AR placement + Web node scene-graph (2026-07-07)

Highlights: `PlacementScene` gets a Scene-Viewer/IKEA-grade placement UX — a ring
reticle that brightens when a surface is ready, opt-in onboarding coaching, plane-grid
fade, and contact shadows under placed models (#2241) — and sceneview-web lands the
retained-mode `Node` transform graph reachable from plain JS via `NodeHandle` (#2024).

### Added

- **sceneview-web**: retained-mode `Node` transform graph (Kotlin/JS API, slice 1 of #2024) — `Node` base class implementing the shared `sceneview-core` `SceneNode` contract (pristine TRS state, parent/child hierarchy via Filament `TransformManager.setParent`, world-space getters/setters, `lookAt`/`lookTowards`, recursive idempotent `destroy()`), plus `SceneView.sceneGraph` / `addNode` / `removeNode`. Not yet reachable from plain JS — the `@JsExport` `NodeHandle` surface arrives in a later slice; the published JS API and the builder DSL are unchanged.
- **sceneview-web**: `ModelNode` and `GeometryNode` (+ `CubeNode`/`SphereNode`/`CylinderNode`/`PlaneNode`) — slice 2a of the #2024 node graph. `SceneView.addModelNode(url)` / `addGeometryNode(config)` / typed primitive factories run today's pipelines (render-gate #2332, supersede #1597, auto-center intact) and re-parent the asset root under a node pivot, so content is now addressable and transformable through the retained tree; the `model { }` / `geometry { }` builder DSL delegates to nodes (identical visual result, unchanged Kotlin shape). `addGeometry` now returns the created `FilamentAsset?` (additive). `Node` transform writes after `destroy()` are guarded (no engine call on a freed entity). Still Kotlin-only — the `@JsExport` surface is untouched.
- **sceneview-web**: in-browser `TransformManager.setParent` proof (#2024 P1) — a new `kotlin-bundle.spec.ts` probe asserts 2-entity world-transform composition AND detach against the real pinned Filament.js WASM, closing the slice-1 review caveat. The probe caught a real bug: embind rejects a JS `null` parent (`BindingError`), so detaching/destroying any slice-1 `Node` would have crashed at runtime — `FilamentNodeBackend` now detaches through the null instance (a component-less sentinel entity), the JS analog of Android's `setParent(i, 0)`.
- **Web:** `LightNode` and `CameraNode` land the #2024 node scene-graph slice 2b. `SceneView.addLightNode(config)` wraps a Filament light in an addressable, transformable node (runtime `intensity`/`color`/`direction`/`position` mutators push through the `LightManager` instance bindings); `SceneView.addCameraNode()` drives the camera from a node's world transform via `Camera.setModelMatrix` each frame (Android `CameraNode` parity). The `light { }` DSL block now delegates to a retained `LightNode` (visually byte-identical to the flat path, addressable via `sceneView.sceneGraph` afterwards). All `LightManager`/`Camera` embind bindings the nodes depend on are proven in-browser by the `kotlin-bundle.spec.ts` #2024-P1 slice-2b probes.
- **Web:** first exported plain-JS scene-graph surface — `NodeHandle` ([#2024](https://github.com/sceneview/sceneview/issues/2024), slice 3 / P4). `window.sceneview` viewers now expose `addNode()`, `addModelNode(url)`, `addCubeNode(size)`, `addSphereNode(radius)`, `addLightNode(type)` and `removeNode(handle)`, returning an opaque `NodeHandle` you can address after `create()`: `setPosition`/`setRotation` (Euler degrees)/`setScale`/`setScaleUniform`/`setVisible`/`addChild`/`removeChild`/`getWorldPosition`/`destroy`. The published `sceneview-web.d.ts` declares the new surface. Library auto-centering is now routed through a single real content-root `Node` translation (the iOS `contentRoot` approach) instead of per-asset root-entity offsets — visually identical; node-created content is framed via its own node transform.

### Changed

- **arsceneview**: `PlacementScene` gains a consumer-grade placement UX (#2241). The built-in reticle is now a thin **ring** (`reticleStyle = PlacementReticleStyle.RING`, the Scene Viewer / IKEA / Houzz idiom) that shows a centre dot and brightens the moment a surface is ready, instead of a solid cyan disc (`PlacementReticleStyle.DISC` keeps the old look). Three opt-in refinements match Google's AR design guidance: `fadePlaneOnFirstPlacement = true` (default) recedes the plane grid once the first model is placed so the floor stops being highlighted; `coaching = true` overlays the animated `PlaneDiscoveryGuide` onboarding while the user finds a surface; `groundShadows = true` drops an invisible shadow catcher on each detected plane so placed models cast a contact shadow instead of floating. The `PlacementSceneDemo` in `samples/android-demo` turns coaching + ground shadows on to showcase it. Existing calls are unchanged (ring is a visual upgrade; the other three are opt-in or default-safe).
- `sync-versions.sh --fix` now covers the 7 formerly-manual version locations (llms.txt prose/CDN/flutter/package labels, demo build.gradle versionName ternary, web.html JSON-LD, playground prompts, and `sceneview.js?v=` cache-busters across ALL website pages incl. embed/preview) — a release version bump is now a single command, verified by a 9.9.9 blank-bump round-trip reaching 0 MISMATCH.
- One-click releases: `release-fast.yml` (dispatch with a version → BLOCKING web device-QA gate → complete `sync-versions --fix` bump → changelog collate → auto-merge release PR) + `tag-release.yml` (tags the merged release commit and dispatches `release.yml` on the tag — working around GITHUB_TOKEN event suppression). Main stays protected; the bump rides a reviewable PR (M7c).

### Docs

- **sceneview-web**: documented the exported plain-JS node surface (#2024 P6). The `SceneViewer instance methods` block in the web agent skill (`SKILL.md`, `references/cheatsheet.md`) now lists the six `sv.*` node factories (`addNode` / `addModelNode` / `addCubeNode` / `addSphereNode` / `addLightNode` / `removeNode`) and the ten `NodeHandle` methods, closing the drift the #2615 doc reviewer flagged; the "Kotlin-only incubating node factories" note is clarified to show which factories are now reachable from plain JS. `docs/docs/quickstart-web.md` gains an "Imperative node API" section; `llms.txt` documents `sv.addNode` / `sv.removeNode`; `references/recipes.md` records the new Web parity line. Two stale code comments referencing the removed `transformScratch` field are reworded (comment-only, no logic change).
- Node-count claims aligned to reality: "42+ node types" → "44+" across README, website, MCP docs and doc site (PlacementReticleNode + ShadowReceiverPlaneNode joined the inventory in #2241) — impact-check.sh runs clean again (#2594).

## v4.20.0 — 2026-07-05

### Added

- **iOS `ARSceneView(showPlacementReticle:)`** — opt-in placement reticle: the tap-to-place
  raycast now runs every AR frame and drives a surface-snapped translucent disc at the screen
  centre (orientation slerp `0.75`, Depth Lab / Android `PlacementReticle` parity; hidden while
  the ray misses). Android's `PlacementReticle` iOS counterpart from the Sprint-1 design (#894).
- **iOS `ARSceneView(groundingShadows:)`** — entities placed synchronously in `onTapOnPlane` now
  automatically get RealityKit's `GroundingShadowComponent(castsShadow: true)`, projecting a
  contact shadow onto the detected surface — the RealityKit analogue of Android's
  `ShadowReceiverPlane` (#2580). Opt out with `groundingShadows: false` (#894).

### Changed

- **Demo app feedback rebuilt permission-free** — the MediaProjection screen + microphone recorder (foreground service, `RECORD_AUDIO` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` / `POST_NOTIFICATIONS`, worker upload) is removed and replaced by a lightweight "Report a bug" bottom sheet: an optional `PixelCopy` screenshot of the app (include/exclude toggle, honest fallback note when the 3D viewport can't be captured), the app's own logcat tail, and device/app context — shared via the system share sheet or a pre-filled GitHub issue. Zero sensitive permissions, zero foreground service, and the Play Console foreground-service declaration (#2120 / #2188) is no longer required; the FGS-declaration CI steps were removed from `play-store.yml` / `main-internal-deploy.yml`.
- **CI: every push to `main` now deploys the Android demo to the Play Store internal-testing track** ([#2596](https://github.com/sceneview/sceneview/pull/2596)). New paths-filtered `main-internal-deploy.yml` (cancel-in-progress, internal track only, versionName `X.Y.Z-main.<sha>`) gives the maintainer a minutes-fast real-device test loop; production remains tag-only via `play-store.yml`. Both Play-uploading workflows now share a single strictly-increasing epoch-minutes `versionCode` scheme (`epoch_seconds / 60`) — required because Play versionCodes must increase across all tracks, so the release workflow migrated off `github.run_number` in the same change.

### Fixed

- **AR: placed models rendered too dark / green-tinted under `ENVIRONMENTAL_HDR` light estimation (the `ARSceneView` default)** ([#2483](https://github.com/sceneview/sceneview/issues/2483)). `LightEstimator` fed ARCore's raw main-light radiance × `1/ev100` (≈ 0.067) into the light **color** — the max-component normalization from the 0.9.x/SceneformMaintained lineage was dropped in the v2 rewrite (the computed `maxIntensity` had been dead code) — while `mainLightIntensity` carried the same magnitude again, so the estimate was applied ~squared and the main directional light collapsed to ~1e-4 of its baseline (effectively black). AR models were lit only by the dim estimated cubemap/SH: dark, glossy, tinted by the camera feed (green/teal indoors). The estimate is now decomposed into hue (max-normalized `mainLightColor`) × magnitude (`mainLightIntensity` = max component), applying the radiance exactly once through the baseline-multiply contract — `(c / max) × max == c`. Pinned by `LightEstimatorTest`; on-device validation (Pixel 9) due at the next device-QA pass.
- Demo app: added a `-dontwarn com.google.android.gms.nearby.**` ProGuard rule so the release AAB's R8 minification no longer aborts on the `compileOnly` Nearby Connections types referenced by arsceneview's `NearbyCollaborativeTransport` reference implementation. This was silently blocking the Play Store deploy of the demo (the missing-class check only runs during `minifyReleaseWithR8`, not on the CI compile/unit-test gates).

### Tests

- `DemoRenderingScreenshotTest` now FAILS (instead of silently assume-skipping) when a slug listed in `BASELINED_GOLDENS` has no committed golden — a deleted/renamed baseline can no longer disable its own regression guard unnoticed (#2323 suggestion 2). New slugs keep the quiet first-run capture flow.

## v4.19.0 — 2026-07-04

### Added

- **iOS Cloud Anchor lifecycle parity.** `SceneViewSwift.CloudAnchorNode.host(ttlDays:completion:operation:)` / `.resolve(cloudAnchorId:completion:operation:)` return a cancellable `CloudAnchorFuture` — call `future.cancel()` from SwiftUI `.onDisappear` (the analogue of Android's `DisposableEffect { onDispose { future.cancel() } }`, [#1768](https://github.com/sceneview/sceneview/issues/1768)) to short-circuit billed ARCore Cloud round-trips when the view goes away. Completion fires at most once and never after `cancel()` or after the handle is deallocated. To keep the core library dependency-free, the actual `GARSession.hostCloudAnchor` call (Google's `arcore-ios-sdk` Swift Package) is supplied by the app through the `operation` closure; `CloudAnchorFuture` owns only the portable, fully unit-tested cancellation gate. Mirrors Android `CloudAnchorNode.host` / `.resolve` returning `HostCloudAnchorFuture` / `ResolveCloudAnchorFuture`. Closes [#1859](https://github.com/sceneview/sceneview/issues/1859) (tracked from the cross-platform parity umbrella [#1813](https://github.com/sceneview/sceneview/issues/1813)).
- `NearbyCollaborativeTransport` — a reference [`CollaborativeTransport`] implementation backed by Google **Nearby Connections** for offline, same-room collaborative AR (no backend). The `play-services-nearby` dependency is `compileOnly`, so it adds **zero footprint and no permissions** to AR apps that don't use collaboration; consumers that opt in declare the dependency and request the surfaced `REQUIRED_PERMISSIONS_*` themselves. Android-only for now; the `CollaborativeTransport` abstraction stays platform-neutral so iOS can map it onto RealityKit's `MultipeerConnectivityService` (#2008).
- `arsceneview`: `PlacementReticle` composable + `PlacementReticleNode` (#2241 Sprint-1, PR 4/6) — the Depth Lab `OrientedReticle` port: an AR placement cursor that slerps its orientation toward the hit surface normal each frame (default 0.75) so the disc no longer jitters as ARCore refines the normal, with optional depth-hit acceptance (`depthPoint = true`, lands on arbitrary geometry when the session depth mode is enabled — default off, #1891 plane-only contract preserved). Ships a built-in thin cyan disc visual when no custom `content` is passed; a `null` hit auto-hides the marker and resets the smoothing.
- **`PlaneDiscoveryGuide` — AR plane-discovery onboarding overlay ([#2241](https://github.com/sceneview/sceneview/issues/2241)).** New `io.github.sceneview.ar.PlaneDiscoveryGuide` composable, a Compose port of Google ARCore Elements' user-tested onboarding state machine: silent 0–3 s, animated hand-sweep hint + "Move your phone to find a surface" pill at 3 s, "Need help?" affordance with a built-in tip card at 8 s, 750 ms fade-out once the first plane tracks (latched — never re-onboards within a session), and contextual tracking-lost messages reusing the existing `sceneview_*_message` copy. Pure UI overlay — consumes `cameraReady` / `isTracking` / `anyPlaneTracked` / `trackingFailureReason` signals the host already produces; no ARCore or Filament dependency. Ships with a headless-testable `PlaneDiscoveryGuideState` (injectable clock), a stateless `PlaneDiscoveryGuideOverlay` for custom hosts/previews, and the Canvas-drawn `PlaneDiscoveryHandHint` animation (no Lottie dependency).
- **`ShadowReceiverPlane` — invisible AR shadow-catcher ground ([#2241](https://github.com/sceneview/sceneview/issues/2241) Sprint-1, PR 3/6).** New `ShadowReceiverPlaneNode` + `ARSceneScope.ShadowReceiverPlane { }` composable in `arsceneview`: an invisible surface bound to a detected ARCore `Plane` that only darkens the camera feed where a virtual object casts a shadow onto it, so placed models read as grounded on the real floor. Port of ARCore Depth Lab's `ShadowReceiverMeshShader` (`Blend Zero SrcColor`) using Filament's dedicated `shadowMultiplier` shadow-catcher feature, via a new `shadow_receiver.mat` material with a runtime-tunable `shadowIntensity` parameter (default 0.6, the Depth Lab value). The quad follows the plane's center pose and refined extents, receives shadows and never casts them, and is compiled into `shadow_receiver.filamat` with the pinned matc toolchain (Filament 1.71.5, profile C) enrolled in the `GenerateFilamat.sh` drift gate.
- **sceneview-web: wired the `model { scale(...); autoAnimate(...) }` DSL builder options that were silent no-ops ([#2432](https://github.com/sceneview/sceneview/issues/2432)).** `SceneViewBuilder.apply()` now threads both through `loadModel`, matching Android `ModelNode` semantics. `autoAnimate(false)` renders a model static (the render loop no longer unconditionally plays glTF animation 0, and a static model no longer holds the on-demand render gate live); `scale(value)` applies a **raw uniform local scale** to the model's root entity (like Android `ModelNode(scale = Scale(value))`, not `scaleToUnits` normalisation). The auto-centre / auto-dolly pass scales each model's asset-space bounding box by the same factor, so a scaled model stays centred and correctly framed. Follow-up to the #2429 doc correction.
- `ARSceneView` gains a `renderQuality: RenderQuality?` parameter, closing the 3D/AR API asymmetry the docs previously advertised as symmetric (#2524, #2519 audit). It is **nullable** and defaults to `null` — existing AR scenes are unchanged, keeping the camera-feed-tuned `createARView` defaults (no SSAO/bloom). Pass a preset to opt in, e.g. `ARSceneView(renderQuality = RenderQuality.Performance) { }` for battery-sensitive overlays or `RenderQuality.Cinematic` for a hero placement showcase. The Filmic tone mapper that round-trips the AR camera background (#1434) is preserved across every preset, since `applyRenderQuality` never writes `view.colorGrading`.

### Changed

- Documented `Node.rotation` (Euler getter) as deliberately un-cached — no per-frame render/animation path reads it (they read `quaternion`), so caching would only add invalidation cost to the hot `quaternion` write path. Closes the last open item (N2) of the Phase-2 hot-path tracker (#2328); the transform/parent JNI and gesture/light/animation allocation items landed earlier in #2366, #2417, and #2423.
- Flutter & React Native demos: the Environment demo now toggles between **two
  distinct HDRs** (Studio ↔ Night) at runtime instead of reloading a single HDR
  (Flutter) or switching HDR↔none (RN). This honestly demonstrates IBL/skybox
  switching and exercises the keyed-`rememberEnvironment` swap path that proves
  the #2361 fix (the skybox actually rebuilds on a new HDR). The second HDR
  reuses the existing in-repo `rooftop_night_2k.hdr` asset. (#2365)
- Flutter & React Native Android bridges: tidied the mutually-exclusive
  `rememberEnvironment` call sites into a single stable call site (one keyed
  `rememberEnvironment` whose factory falls back to the default environment when
  the HDR path is null), instead of a keyed call plus a separate
  `environment ?: rememberEnvironment(...)` fallback at the `SceneView` argument.
  Behavior-preserving; version-independent (still uses Compose `key {}`, not the
  unreleased `key=` param). (#2365)
- Build toolchain: bump Kotlin `2.3.21` → `2.4.0` (the 2026-06-03 stable language release) in `gradle/libs.versions.toml`. The Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) and the serialization plugin are pinned to the Kotlin version and bump in lockstep; Compose Multiplatform stays at `1.11.1` (compatible). No public-API or runtime-library changes. Doc references (`llms.txt`, `docs/docs/llms-full.txt`) updated to the new Kotlin version. Verified: `:sceneview` + `:arsceneview` release compile clean, `:sceneview-core` KMP metadata + JS compile clean, and the Android unit suites pass (`:sceneview` 494 tests, `:arsceneview` 709 tests — 0 failures). (#2391)
- `samples/android-demo`: the `ar-placement` demo now renders through the shared `TapToPlaceArSession` engine instead of its own inline `ARSceneView`, removing ~330 lines of duplicated session code (centre reticle, texture-settle gating, PAUSED-surviving anchors, per-asset rotation correction, the gesture pill, the camera-init scrim and the plane-gated status vocabulary all move into the shared engine). The demo keeps all of its developer-facing chrome — streamed/bundled chip pickers, Snap-to-plane / Show-reticle toggles, Clear All, "Next tap places:" preview and the force-tracking-failure QA menu — and its behaviour is unchanged (#2482, PR 2/4).
- The AR View tab now renders via the shared `TapToPlaceArSession` engine (#2482, PR 3/4) — it gains the centre placement reticle, texture-settle gating (no black flash on placement), helmet rotation correction (the Damaged Helmet now lands upright instead of face-down) and PAUSED-surviving anchors. The top-end X close becomes a top-start back arrow, the toast-only Share stub is dropped, and both entry points now speak one status vocabulary.
- Added a shared, demo-app-level `TapToPlaceArSession` engine (`common/placement/`) — the foundational shared session behind the AR View tab and the `ar-placement` demo (#2482 Option A, #2518). It carries the centre placement reticle (#1882), texture-settle gating and PAUSED-surviving anchors (#1435), per-asset rotation correction (#1477), tap-time model resolution as an API invariant (#2476), the camera-init scrim (#2484) and the #2234 plane-gated status vocabulary, with the tap/reticle acceptance test single-sourced as a JVM-tested `PlacementHitPolicy`. This is PR 1/4 of the unification — purely additive; the two hosts are re-pointed onto the shared engine in the follow-up PRs.
- **Collaborative AR hardening** (`NearbyCollaborativeTransport` / `CollaborativeSession`, #2569): inbound messages are now bound to the *connection-bound* transport peer id — a message whose body claims another `peer` is rejected as spoofed, and a second live connection claiming an already-connected peer id is rejected at initiation. (Per-connection integrity: absent the `shouldAcceptConnection` out-of-band check, peer ids remain self-advertised names.) `CollaborativeState` rosters are bounded (`MAX_PARTICIPANTS` = 64, `MAX_NODES` = 1024, overridable via constructor) against forged-key memory-amplification DoS. `NearbyCollaborativeTransport` gains an optional `shouldAcceptConnection` trust gate exposing the Nearby `authenticationDigest` for out-of-band pairing, guards the `ConnectionsClient.MAX_BYTES_DATA_SIZE` BYTES payload limit, fail-closes a throwing `shouldAcceptConnection` gate, observes `sendPayload` failures, and documents the same-`serviceId` auto-accept trust boundary. Wire-format vector parsing early-bails on oversized `[...]` bodies. Adds the promised `NearbyPayloadFramingTest` / `NearbyPeerRegistryTest` plus impersonation-rejection and roster-cap tests.
- **Renamed the Android source files `Scene.kt` → `SceneView.kt` and `ARScene.kt` → `ARSceneView.kt`** so each file matches the primary composable it defines (`SceneView` / `ARSceneView`); the bare `Scene` / `ARScene` are deprecated backward-compat aliases. This aligns Android with the Web (`SceneView.kt`) and iOS (`SceneView.swift` / `ARSceneView.swift`) source layout — Android was the only platform still named after the deprecated symbol — and makes the primary entry point discoverable by file name (an AI-first concern). **Source-only, no binary break:** `@file:JvmName("SceneKt")` / `@file:JvmName("ARSceneKt")` pin the published JVM facade class names, so Kotlin consumers compiled against an earlier release keep resolving the facade in their bytecode without a recompile. No public API, signature, or behavior change.

### Fixed

- `samples/android-demo`: `ARDepthColliderDemo` now drops balls so they are always visible in the camera view. The previous fix anchored the spawn to the live camera but bundled the drop height into the camera transform, so aiming at the floor (as the on-screen hint instructs) rotated the height by the phone's pitch and the balls landed off in a screen corner. The forward offset is now projected through the camera pose while the horizontal scatter and drop height are applied in **world** space (world +Y up), so balls spawn straight ahead of the camera and fall straight down into view regardless of how the device is tilted (#1874, #2466).
- **Android demo — in-app screen recording restored on Android 14+.** Re-added
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission and
  `android:foregroundServiceType="mediaProjection"` on `FeedbackRecordingService`
  (temporarily removed in #2120 to unblock a Play Console catch-22). The Play Console
  foreground service type declaration must be completed before the next Play release —
  see PR body for the console step. (#2188)
- iOS demo: the published AR-recording deep link (`sceneview://demo/ar-record-playback`) now resolves to the recorder demo instead of the "Open in app" placeholder. The iOS `DemoDeepLinkRegistry` only wired the unpublished `ar-recording` id; the canonical `ar-record-playback` (used by the website QR landing page, `llms.txt`, and the Android catalog) fell through to the placeholder. Both ids now route to `ARRecorderDemo` (#2370).
- **`Node.worldQuaternion` (and `worldRotation`) now round-trips again on parented nodes ([#2392](https://github.com/sceneview/sceneview/issues/2392)).** A 4.15.2 → 4.17.0 regression: setting a child's world-space rotation via `node.worldQuaternion = X` under a parent with a non-identity world rotation silently produced `parentWorldRotation ⊗ X` on read-back instead of `X`, scattering per-frame billboards and mis-placing rotated child meshes. The world-space TRS cache (#2280/#2264) combined with the cached-quaternion fast path in `getLocalQuaternion` (#2294/#2267) made the world→local conversion that backs the setter trust the parent's *cached* `worldQuaternion`, which could be stale relative to Filament's live world matrix. The conversion helpers (`getLocalQuaternion`/`getWorldQuaternion` plus the position/scale/transform siblings) now re-validate against the live `TransformManager` world transform — exactly the read 4.15.2 did — so a stale cache can no longer corrupt the result. The per-frame world-space *getters* stay fully cache-served (the hot read path is unchanged). Engine-backed regression coverage added in `NodeWorldQuaternionRoundTripTest`.
- **sceneview-web:** Guard the on-demand render gate (#2332) against a frozen canvas after a failed model/IBL/skybox load. A failed `fetch` now settles its in-flight load *and* requests a repaint so the viewer reflects the error state instead of freezing at the last successful frame, and the success and error paths signal the gate identically. Added `LoadModelErrorSignalTest` regression coverage. (#2409)
- `release-device-qa-gate.sh` no longer false-FAILs the release on the advisory `ar` leg. Per the CLAUDE.md "Release-gate policy for continue-on-error legs (#1651)", only `web` is BLOCKING; `android` and `ar` are ADVISORY (flaky emulator / CI assumeTrue-SKIP when the bundled recording or Play Services for AR is absent). The gate's default graded sets are now `REQUIRED=web` / `ADVISORY=android,ar` (matching `device-qa.sh`'s pre-computed `releaseGate.verdict`), it honours `--advisory=`/`--required=` CLI overrides, and a `test-release-device-qa-gate.sh` self-test guards the policy. (#2433)
- **macOS App Store demo-app upload no longer rejected for a duplicate `CFBundleVersion` (#2443).** The iOS-demo Xcode project pinned `CURRENT_PROJECT_VERSION` as a static literal (build `366`), so every store-affecting deploy re-archived the *same* build number; App Store Connect refused the duplicate on the Mac App Store stream ("`CFBundleVersion [366]` must contain a higher version than the previously uploaded [366]"). `app-store.yml` now computes `BUILD_NUMBER=$(( github.run_number + 1000 ))` in bash and passes `CURRENT_PROJECT_VERSION="$BUILD_NUMBER"` into the `xcodebuild archive` invocation for **both** the iOS and macOS legs — a command-line build setting overrides the project-file literal at archive time, and `github.run_number` is strictly increasing, so every upload now carries a fresh, monotonic `CFBundleVersion`. The `+1000` offset clears the historical high-water mark (this workflow's bare run_number was ~288, *below* the already-uploaded 366), so the build number is both monotonic and safely above every previously-uploaded build. (The arithmetic is done in bash, not a `${{ }}` expression — GitHub Actions expressions do not support the `+` operator.) No change to `project.pbxproj` (the static literal is now irrelevant at archive time); the marketing version `MARKETING_VERSION = 4.18.0` and the `sync-versions.sh` check that guards it were already correct.
- `rememberHDREnvironment` / `rememberKTXEnvironment` no longer leak the previously loaded `Environment` (its `IndirectLight` + `Skybox` GPU textures) when the asset path changes. The factories now dispose the prior environment on a key swap via a `DisposableEffect`, matching the sibling `rememberEnvironment(key = …)` — previously `produceState` only cancelled the loader coroutine and the old IBL/skybox stayed GPU-resident until the whole `SceneView` left composition (e.g. a time-of-day HDR slider leaked one set per swap). (#2458)
- `rememberModelInstance` (both overloads) no longer leaks the previously loaded `Model` (its Filament textures, vertex/index buffers and materials) when the model path changes. The factory now destroys the prior `Model` (`modelLoader.destroyModel(it.model)`) on a key swap and on leave-composition via a `DisposableEffect` — previously `produceState` only cancelled the loader coroutine and the old `Model` stayed in `ModelLoader.models`, GPU-resident until the whole `SceneView` left composition (e.g. the Sketchfab gallery swap leaked one model per swap). Disposal is ordered after the consuming `ModelNode` detaches its renderables, so the entities are off the scene before the buffers are freed. (#2459)
- **`sceneview-web`: `SceneView.destroy()` no longer leaks the camera entity handle.** Teardown destroyed the camera *component* (`engine.destroyCameraComponent`) but never freed the camera *entity*, leaking one `EntityManager` slot per `SceneView` create→destroy cycle — the exact inverse of the #1700 light-component leak. `destroy()` now also calls `engine.destroyEntity(cameraEntity)` (component first, then entity, mirroring the light teardown), so the handle is reclaimed. This accumulated on every WebXR enter→exit and SPA dispose/recreate, since `WebXRSession`/`ARSceneView`/`VRSceneView` create and destroy a `SceneView` per session (#2045). (#2461)
- Fixed `generateCapsule` (shared `sceneview-core`, all platforms) emitting a malformed mesh: the three independently-wound vertex blocks (two hemispheres, the cylinder) were stitched with one continuous-grid index loop, producing an inverted cap funnel over the top hemisphere, a degenerate zero-area band at the cylinder/hemisphere seam, and an inverted bottom cap. The blocks are now stitched independently with per-block winding, pole-row triangle caps, and a zero-length-cylinder guard (so a `height == 2 * radius` capsule is a clean sphere). Vertex layout is unchanged — only the triangle connectivity is corrected. Added a connectivity regression test (no degenerate triangles, consistent outward winding, no cross-block bridges, no positional holes) that the previous topology failed.
- Fixed `Torus` rendering inside-out on default parameters — its triangles were wound clockwise (inward), so the default single-sided material culled the visible outer surface. The donut now winds outward and renders solid. (#2469)
- Fixed the Android `Capsule` geometry rendering inside-out on default parameters — same inverted (clockwise) winding as the torus; the capsule now winds outward and renders solid. (#2470)
- Fixed `setMorphWeights(weights)` being a silent no-op: the `offset` parameter defaulted to `weights.size`, writing the weights past the end of the morph-target buffer instead of at the start. It now defaults to `0` (matching Filament), so `setMorphWeights(floatArrayOf(1f))` correctly drives the first morph target. (#2471)
- Fixed the shared `sceneview-core` `TorusGeometry` generator winding every triangle inward (clockwise), which rendered the torus inside-out under a single-sided material on the web (Filament.js) and the iOS reference path. The triangle index order is now counter-clockwise (outward-facing), mirroring the Android `Torus` fix (#2469) and matching the convention of the core Sphere/Cylinder/Cone generators. Vertices are unchanged — index/winding only. ([#2475](https://github.com/sceneview/sceneview/issues/2475))
- **android-demo:** Fix the AR View "Start AR Camera" experience always placing the default Damaged Helmet regardless of the model picked. The remembered tap-gesture lambda captured the derived `selectedModel` val from first composition, so picking Fox/Soldier/etc. updated the pill but never the placement. The tap handler now reads `arModels[selectedModelIndex]` through state at tap time (mirroring `ARPlacementDemo`), so each placement uses the currently-selected model. (#2476)
- ML Kit Object Labels demo: label billboards no longer render oversized, warped or
  mirrored/upside-down. The `BillboardNode`s were created without a
  `cameraPositionProvider`, so they kept the AR anchor's plane-aligned pose and were drawn
  edge-on or back-faced (mirrored UVs) instead of facing the viewer; they now billboard
  toward the live camera position every frame. The detector's classification confidence is
  surfaced as a "NN%" subtitle on each label, and the "Aim at a recognisable object" hint is
  dismissed once at least one object is labeled. (#2478)
- **AR Depth of Field demo**: lowered the default blur strength from `2.0×` to `1.0×` (Filament's stock cinematic strength). The old `2.0×` over-scaled the circle-of-confusion and crushed out-of-focus regions to black bands and colour smears, making the demo look broken; `1.0×` shows a legible shallow depth-of-field instead. The blur slider still ranges up to `6×` for a stronger bokeh. ([#2480](https://github.com/sceneview/sceneview/issues/2480))
- Orbital AR demo: the "Turn around — N models orbiting" banner and the directional edge-arrow now dismiss once the user has turned toward a model (the chase target enters the camera frustum) or after a short onboarding window, instead of staying up for the whole session and cluttering the view. The dismiss is sticky across device rotation (#2481, from the #2466 device review).
- **AR demos no longer open on a raw black viewport ([#2484](https://github.com/sceneview/sceneview/issues/2484)).** The shared "Starting camera…" scrim (`ARCameraInitScrim`) is now wired into the 11 AR demos that still showed jet black for the ~1–3 s ARCore camera warm-up on entry (Tap-to-Place, Depth Collider, Depth Occlusion, Streetscape, Scene Mesh, AR Fog, Orbital AR, Cloud Anchors, Augmented Faces, Augmented Images, Camera Pose), dismissing on the first `onSessionUpdated` frame. The scrim also gained a defensive 8 s self-dismiss so a stuck session can never hide a demo's own error messaging. The camera is not faster — the warm-up gap is simply covered by an honest loading affordance.
- **AR Body Tracker**: replaced silent black screen with a camera-init scrim (spinner while
  ARCore starts) and a persistent in-viewport hint pill ("Point camera at a person — full body
  visible") that fades out once a skeleton is detected. Error states (model missing, ARCore
  tracking failure) now surface as a red pill directly in the viewport, matching the
  `ARFaceDemo` UX pattern. The live skeleton detection path is device-gated and unchanged.
- Cloud Anchors demo: made the host→resolve flow discoverable (#2486). The on-screen
  Host/Resolve buttons no longer render as a faint, greyed-out ghost over the camera
  feed — both stay solid and tappable, guiding the next step on-screen (place an anchor,
  enter an ID) instead of being disabled and reading as "there are no buttons". The
  one-line instruction and the Cloud Anchor ID field are now on the main screen rather
  than buried in the Settings sheet, and the status/error banner moved to the top so the
  long `ERROR_NOT_AUTHORIZED` message is no longer clipped behind the buttons. (The
  underlying provisioning failure is tracked in #1436.)
- **android-demo:** The Explore tab's live 3D model viewer no longer flips the model fully upside-down when an orbit drag is carried past the top or bottom pole ([#2487](https://github.com/sceneview/sceneview/issues/2487), Pixel 9 device review). The hero viewer's user-drag path delegates to Filament's `ORBIT`-mode `Manipulator`, which does not clamp its polar angle; once the eye crossed directly over/under the model the fixed world-up `lookAt` collapsed and the model snapped inverted. The orbit eye's pitch is now clamped just shy of the poles (`[1°, 179°]`) — re-derived from the manipulator's transform and re-aimed at the unchanged orbit target — so a near-top-down / near-bottom-up view is still reachable but the gimbal flip can never happen. The idle auto-orbit path is untouched. Covered by `OrbitEyePitchClampTest`. (The remaining viewer-chrome items in #2487 — in-viewer control bar, detail-sheet sizing, Feedback FAB overlap, decorative-dot legibility, clipped sample card — are tracked there for follow-up; model fit/zoom/shadow are in #2348/#2233/#2235.)
- Flutter (Android): the AR plane-discovery bridge now dedupes detected planes by reference identity (`IdentityHashMap`-backed set) instead of `System.identityHashCode`, which is not collision-free — a new plane whose hash collided with an already-reported one could silently drop its `onPlaneDetected` callback (#2488).
- `sceneview-core`: `worldToLocalScale` / `localToWorldScale` no longer transform a scale through the `Mat4 * Float3` point operator, which leaked the parent transform's translation (and rotation) into the result. Scale conversions now compose the transform's basis-vector lengths, so a translated parent no longer corrupts the converted scale (#2489).
- `sceneview-core`: `LatheGeometry`'s documented `closed` parameter now has an effect. Previously `closed = false` produced byte-for-byte the same fully-closed surface as `closed = true`; it now leaves the final angular seam unstitched, producing the open lathe the parameter promises (#2490).
- `rememberOnGestureListener` no longer freezes its callbacks at first composition. The previous `remember(creator)` captured every callback lambda once, so any handler that closed over a derived `val` (rather than reading Compose `State` inside its body) silently kept stale behaviour across recompositions — the root-cause footgun behind the "AR placement always uses the first model" report. Callbacks now route through `rememberUpdatedState`, keeping the listener instance stable while always invoking the latest lambda (#2506, #2476).
- Website showcase viewer (`website-static/js/sceneview.js`): the render loop now pauses when its canvas scrolls off-screen (`IntersectionObserver`) or the browser tab is hidden (`visibilitychange`), and resumes cleanly when it returns — no more 5 concurrent Filament/WebGL loops running forever on `platforms-showcase.html` / `claude-3d.html`. `dispose()` now cancels the pending animation frame, disconnects the observer, and removes every tracked event listener (the canvas controls + the visibility listener), so a disposed viewer no longer leaks its engine. The public API is unchanged — pages get the gating for free. (#2508)
- Website showcase viewer: a failed model load (network error, 404, or corrupt GLB) now paints a subtle "3D preview unavailable" placeholder over the canvas — themed from the site's design tokens, light + dark — and logs `console.warn`, instead of leaving a permanently blank canvas. The promise still rejects, so existing `.catch()` callers (hero, lazy-loader, playground, web) keep their current behaviour. (#2509)
- Fixed the two-finger camera **pan** gesture being wildly over-sensitive — the slightest drag could throw the model off-screen. When the camera manipulator was swapped at runtime (e.g. an auto-fit viewer rebuilding it once the model loads), `SceneView` never re-pushed the surface viewport to the new manipulator, so Filament's ORBIT pan divided the touch pixel by a stale `1×1` viewport and the pan delta exploded by ~1000×. `SceneView` now caches the last surface size and re-seeds it whenever the manipulator instance changes. One-finger orbit and pinch-zoom are unaffected. (#2514)
- AR docs & KDoc: the canonical plane tap-to-place snippet called `frame.createAnchorOrNull(plane.centerPose)`, but no `Frame.createAnchorOrNull` extension exists (ARCore's `Frame.session` field is package-private, so the extension is infeasible). Swept all occurrences to the real `Trackable.createAnchorOrNull(pose)` form (`plane.createAnchorOrNull(plane.centerPose)`) across `ARSceneView` KDoc, the AR codelab, migration/showcase docs, and `samples/README.md`, so AI-reproduced code compiles (#2525, #2519).
- **iOS demo**: removed the "Sponsor" / GitHub Sponsors card from the About tab to comply with
  App Store Guideline 3.1.1 (no external payment or donation links). The Android demo's
  equivalent Sponsor card is intentionally unchanged — Google Play allows donation links.
- **Website:** fixed the garbled ("forky") GitHub icon in the site header. The nav + dev-tools GitHub mark in `index.html` carried a malformed SVG `d` path (`…24.18.0-6.63…`) whose elliptical-arc segment was truncated, so the octocat rendered distorted on every browser. Restored the canonical path used by all other pages. (#2546)
- **Website**: hero layout no longer collapses at ≥769px — `minmax(0,1fr)` grid tracks + `min-width:0` children, and the fallback visual's conflicting fixed `height:500px` (which imposed an ~889px intrinsic width via `aspect-ratio:16/9`) now derives from the aspect ratio (#2560).
- **Website**: repaired 5 SVG icon paths in `index.html` corrupted by a historical version find-replace (`4.18.0` injected into arc commands), and added a `sync-versions.sh` guard that fails when a version string appears inside any `d="…"` path data (#2562).
- **Website**: aligned stale version strings on 4.18.0 (iOS snippet 4.3.4, web JSON-LD 4.4.0, playground prompt 4.3.1, `?v=3.6.2`/`4.4.0` cache-busters) and pinned each surface in `sync-versions.sh` so they can't drift again (#2564).
- **Website**: 3D was dead site-wide — the pages' CSP `script-src` was missing `'unsafe-eval'`, which Filament's Emscripten WASM glue requires, so `Filament()` rejected and every viewer spun forever. Added `'unsafe-eval'` to all 26 HTML pages (#2561).
- **Website**: Filament engine init now has a 15s watchdog and a graceful "3D preview unavailable" placeholder — an init failure (blocked WASM, asset 404, OOM) degrades visibly instead of an infinite "Loading 3D engine…" spinner (#2563).
- **Website**: "Pricing" nav item now appears on every page (was only on the homepage), same position and markup, desktop and mobile menus (#2565).
- **Website**: added `:focus-visible` styles — keyboard users get a visible, token-themed focus ring on links, buttons and form controls in both themes (WCAG 2.4.7) (#2566).
- **Website**: hygiene — routine `SceneView:` info logs gated behind `window.SCENEVIEW_DEBUG`, dead CSS grids removed, the permanently-hidden duplicate `#hamburger` button removed from all 9 pages, scroll-reveal consolidated into `script.js` (single implementation, now honoring `prefers-reduced-motion` everywhere) (#2568).
- `arsceneview`: `ARSceneView`'s reactive `LaunchedEffect` for `flashMode` and every typed `Config.*Mode` param (`depthMode`, `planeFindingMode`, `instantPlacementMode`, `geospatialMode`, `streetscapeGeometryMode`, `cloudAnchorMode`, `augmentedFaceMode`, `imageStabilizationMode`, `semanticMode`, `updateMode`, `focusMode`) no longer reverts a `sessionConfiguration` callback override right after session creation. `LaunchedEffect(param)` always runs once on the composable's initial composition — using the parameter's untouched default — not only on genuine later changes; that first run compared the live session config against the default and, on any mismatch, silently pushed the default back into the session without re-invoking `sessionConfiguration`. Any app that configured one of these modes exclusively through the callback (the library's own documented escape-hatch pattern) — e.g. `config.depthMode = Config.DepthMode.AUTOMATIC` with the `depthMode` param left at its `DISABLED` default — had that mode permanently reset moments after creation, with no exception or log. A new `ChangeGate` tracks each param's last-applied value so only a real, later change triggers reconfiguration (#2573). For `focusMode` specifically, the redundant force-reapply in `onSessionResumed` (which re-clobbered callback overrides on every resume — including the initial one) is also removed: session config persists across pause/resume and the creation path + gated effect cover every legitimate case.

### Performance

- **`PlaneRenderer` / `PlaneRendererV2`: O(1) updated-plane membership in `RENDER_CENTER` mode ([#2504](https://github.com/sceneview/sceneview/issues/2504), audit row AR10).** The per-frame `plane !in updatedPlanes` visibility check ran against ARCore's JNI-backed list, an O(M) linear scan repeated for every active plane visualizer — O(N×M) every frame. `updatedPlanes` is now hoisted into a `HashSet` once per frame so the membership test is O(1) (O(N+M) total). Behaviour is byte-identical: ARCore returns the same `Plane` instance per trackable across frames, so identity-based set membership matches the previous list `contains` exactly. No public API change.

### Tests

- **`DemoRenderingScreenshotTest` now asserts (not silently skips) for 3 more unified demos (#2323).** Captured + committed render-goldens for `two-d-in-three-d`, `materials`, and `custom-geometry`, so their per-tab `@Test` methods now compare against a checked-in baseline instead of taking the first-run `assumeTrue`-skip path (which asserted nothing). Each golden is a clean, settled frame and was re-run to confirm determinism within its per-test tolerance (custom-geometry holds the tight 2 % default). **Capture note:** the goldens must be captured/run one method per instrumentation invocation — running all 14 methods in a single session churns the Filament/GLES context enough to blank later captures on the emulator backend, which is what left these baselines uncaptured. 5 demos remain on the first-run skip path pending baselines and are tracked under #2323: `animation-physics`, `picking-collision`, `camera-gestures`, and `secondary-camera` render an empty SceneView under `qa_mode` on the capture profile (no clean settled frame to bake in), and `lighting-lab`'s procedural dynamic-sky frame is non-deterministic well beyond its 15 % tolerance (~31 % run-to-run), so committing it would bake a flaky baseline.
- 4 render-goldens committed from the pinned CI profile (`animationphysics`, `cameragestures`, `lightinglab`, `pickingcollision` `_default`) — harvested from the new `demo-render-goldens` job (#2587); `DemoRenderingScreenshotTest` now compares instead of silently skipping for them (#2323).
- `render-tests.yml` gains a `demo-render-goldens` job: `DemoRenderingScreenshotTest` (previously run by NO workflow) now executes on the pinned emulator profile and uploads first-run captures as an artifact — the #2323 silent-skip gap becomes a harvestable baseline source. Non-blocking until the 8 missing goldens are reviewed and committed.
- Added a Maestro flow (`.maestro/android/flows/ar-view-live.yaml`) that drives the AR View tab's **live** session end-to-end — launcher → Start AR Camera → unified tap-to-place status overlay → top-start back-arrow exit → launcher restored — and wired it into `ar.yaml`. Until now no flow exercised the live AR View session (only deep-linked demos), the blind spot that let #2476 ship. Final piece of the #2482 tap-to-place unification (PR 4/4).

### Docs

- llms.txt, agent skill (cheatsheet + recipes) and llms-full.txt now document the #2241 Sprint-1 placement UX kit — `PlaneDiscoveryGuide`, `PlacementReticle`, `ShadowReceiverPlane` — with the honest platform matrix (iOS: native coaching overlay ships today, grounded shadows/continuous reticle tracked under #894; Web: coming soon).
- **AI-first docs: corrected remaining cross-platform doc↔API parity divergences so an AI no longer emits non-compiling / no-op code ([#2429](https://github.com/sceneview/sceneview/issues/2429)).** Each fix was verified against the **real** Swift / Kotlin symbol. **iOS (phantom signatures → real symbols):** `GeometryNode.plane(width:height:)` → `plane(width:depth:)` in the `llms.txt` mapping table and the `website-static` mirror; `VideoNode(url:size:)` → `VideoNode.load(...)` and `LineNode(start:end:color:)` → `LineNode(from:to:color:)` in `cheatsheet-ios.md` (and the iOS agent-skill cheatsheet, for consistency). **iOS `ViewNode`:** added an honest "⚠️ Coming soon (deferred)" note to the primary `llms.txt` surface — ViewNode currently renders a blank white plane (SwiftUI content stored but not displayed, tracked by [#1035](https://github.com/sceneview/sceneview/issues/1035)) — so an AI does not generate a non-working ViewNode. **Web:** the `model(url) { scale(); autoAnimate() }` builder options were documented as functional but `SceneViewBuilder.apply()` never reads them (silent no-ops); the `llms.txt` web-builder example (and mirror) now note that per-model `scale()` and disabling `autoAnimate()` are not yet wired on web (glTF animation 0 auto-plays by default), with wiring tracked by [#2432](https://github.com/sceneview/sceneview/issues/2432). Docs-only — no library code changed. (The `ImageNode`/`BillboardNode` cheatsheet signatures were corrected separately in the v4.18.0 release docs pass.)
- Whole-repo AI-first contract audit (#2519): fixed every verified doc↔code inconsistency across `llms.txt`, `docs/docs/llms-full.txt`, both cheatsheets, the 3 agent skills, and `samples/recipes/` — Android `cameraExposure` is documented as Filament's absolute exposure scale everywhere (the EV-stops recipe told users to pass negative values, which render a black frame, #1179); removed phantom symbols (`GeospatialNode`/`DepthNode`/`InstantPlacementNode`/`ArrowNode`, `ARSessionFailure.UnavailableArcoreNotInstalled`/`CameraPermissionNotGranted`, `createKTXEnvironment`→`createKTX1Environment`, `CollisionNode`, `ARSceneView(renderQuality=)`); corrected wrong defaults (`Sphere`/`Cylinder.DEFAULT_RADIUS` are `1.0f`, `Size` is a `Float3`); documented missing public API (`onPlaybackFailed`, `onConfigDowngraded`/`ARConfigDowngrade`, `playbackDatasetUri`, `autoFitContent`, `LightNode(color=)`, `PhysicsNode(floorProvider=)`, iOS `GeometryNode.torus/.capsule`/`unlit:`, `faceTracking:`, `.gimbal`, core geometry generators); and rewrote the recipes' iOS snippets that used invented APIs (`ModelNode(named:)`, `.autoAnimate()`, `.editable()`, `SceneView(environment:)` init, `content.add`, `ARSceneView(onTapGesture:)`) to the real SceneViewSwift surface.
- Post-merge Tier-2 review follow-up for the new `ARSceneView(renderQuality:)` (#2524): fixed a stale `llms.txt` troubleshooting line that still called `rememberARView`'s tone mapper "Linear" (it is Filmic, #1434 — the same correction #2524 applied to the capabilities row), and documented that the AR `renderQuality` preset is Android-only (the iOS `.renderQuality(_:)` modifier is 3D-`SceneView`-only) in both the `llms.txt` "Android-only" parity table and the iOS agent-skill cheatsheet, so an AI does not generate an `ARSceneView(...).renderQuality(...)` call that fails to compile on iOS.
- Weekly doc↔API drift audit: corrected the `SceneView` and `ARSceneView` parameter
  order in `llms.txt` to match the actual Kotlin source. `SceneView` now lists
  `renderQuality` / `autoCenterContent` / `autoFitContent` right after `isOpaque`
  (was misplaced after `lifecycle`), and `ARSceneView` lists `cameraExposure` after
  `cameraNode` (was misplaced after `isOpaque`). No API change — reference accuracy only.
- **Fixed a non-compiling `FogNode` initializer in the iOS cheatsheets.** Both `docs/docs/cheatsheet-ios.md` and the `sceneview-ios` agent skill cheatsheet documented `FogNode(density:color:)`, an initializer that does not exist on the Swift API (the init is private; only the static factories are public). They now use the real factory form `FogNode.linear(start:end:color:)` / `FogNode.exponential(density:color:)`, matching `llms.txt`, so an AI reading the cheatsheets emits compiling Swift.

## v4.18.0 — Cross-platform hot-path perf, Android render & CI hardening, demo-quality polish (2026-06-06)

### Added

- **Demo app: consolidated demos can now open directly on a specific tab via the launching alias or a `?tab=` deep-link / `--es tab` param (#2315).** The #2239 demo consolidations merged several demos into one segmented-button demo each (e.g. `custom-mesh` + `shape` → `custom-geometry`), but every old alias deep link landed on the demo's default first tab — so `sceneview://demo/shape` opened the Custom Mesh tab instead of Shape. A consolidated demo opened through a retired alias now pre-selects the matching tab (`shape` → Shape, `physics` → Physics, `multi-model` → Multi-Model, `movable-light` → Movable, …), and an explicit `?tab=<index|alias>` deep-link query / `--es tab <v>` intent extra overrides it (e.g. `sceneview://demo/custom-geometry?tab=1`). The no-alias / no-param path is unchanged — demos still open on their default first tab — and an out-of-range or unparseable tab value falls back to the default rather than crashing. Pure resolution logic (`DeepLinkRouter.resolveInitialTab`) is unit-tested; the alias→tab table is asserted to stay in sync with the alias map.
- **Evidence-Stamped Claim Gate — a false "it works / QA complete / live" success-claim can no longer reach the remote ([#2346](https://github.com/sceneview/sceneview/issues/2346)).** The AI repeatedly told the maintainer something was done/working/live when it was not (iOS sat on 4.0.3 for three weeks while CI was green; demo QA reported complete on KEYLESS builds where Sketchfab/ARCore were never exercised). A new deterministic gate, `.claude/scripts/claim-gate.sh`, wires onto the existing `Bash(git push*)` pre-push hook and BLOCKS the push when the canonical `STATE.md` asserts an affirmative ✅-stamped success-claim that lacks fresh, *agreeing* evidence on disk. Verifying tools now stamp that evidence: `device-qa.sh` already writes `device-qa-report.json`, and `/store-status` (`store-status.js`) now writes `.claude/data/last-store-probe.json` (`{expected, iosLive, mavenHttp, npm, verdict, ts}`, timestamp stamped via `date -u`, never `Date.now()`). The gate FAILS a QA-complete claim when the report is missing/stale, a key-gated sub-leg (`sketchfab` / `arcore-cloud`) is `skipped` (path NOT tested, #2343), or the release verdict is `blocked`; it FAILS an all-live / "verified live" claim when the probe is missing/stale or `verdict != ALL_LIVE` (the exact iOS-stuck-on-4.0.3 trap). It fires ONLY on affirmative claims — never on honest factual lines like "iOS LIVE=4.0.3 (4.17.0 in review)" or "live on Maven Central" — and fails **closed** (blocks) on an unreadable evidence file rather than waving a claim through. Escape hatch for a genuine false-positive: `ESCG_BYPASS=1 git push …`. A slow human-in-the-loop loop complements the fast gate: `/caught <class> <context>` ledgers a miss the gate did not catch (`.claude/data/claim-ledger.tsv`, gitignored) and, at the 3rd occurrence of a class, promotes it to a durable `feedback_*.md` memory rule; `/handoff` runs the gate against the drafted `## NOW` and backstops the ledger promotion. Verified exhaustively against the real `STATE.md` (zero false-positive) plus a fixture matrix (skipped key-leg, missing/stale/unparseable evidence, version mismatch, honest factual lines, evidence-backed pass).
- **`SceneViewSwift` gains shared preset-polygon helpers — `ShapePresets` plus `ShapeNode.starPoints(...)` / `ShapeNode.regularPolygonPoints(...)` ([#2354](https://github.com/sceneview/sceneview/issues/2354)).** The Shape Extrude gallery's preset outlines (triangle, star, pentagon, hexagon, L-shape, arrow) now live once in the library as `ShapePresets`, and the two point generators behind `ShapeNode.star(...)` / `ShapeNode.regularPolygon(...)` are exposed so callers can get the raw `[SIMD2<Float>]` vertices without building a node. The iOS demo's `ShapeExtrudeDemo` and the `SceneViewSwift` triangulation tests now consume this single source of truth instead of hand-copied, byte-identical coordinate lists — so retuning a preset (e.g. the star's inner/outer radius) updates the demo and its guarding test together, and the test can no longer silently drift from the shipped shape. Existing `ShapeNode.star(...)` / `regularPolygon(...)` output is byte-identical (a purely internal extraction); no rendered geometry changes.

### Changed

- **perf(sceneview, arsceneview): cache/throttle the remaining MED Filament-JNI & per-frame allocation hot paths (#2328, #2329; audit #2402 MED-1…5 + the V1/V2 plane list churn).** All changes are behavior-preserving — identical returned values, just cached/throttled/reused:
  - `RenderableComponent.renderableInstance` and `LightComponent.lightInstance` document the caching contract explicitly (a Kotlin interface property cannot hold a backing field, so the cache must live in the implementer). `ARCameraStream` — the one production `RenderableComponent` implementer that was paying the uncached interface default — now caches its renderable-instance handle lazily-once, mirroring `RenderableNode`/`LightNode`, so per-frame camera-texture swaps and priority/material reads no longer issue a `getInstance` JNI thunk each access.
  - `ModelInstance.renderableInstances` / `lightEntityInstances` resolve their handles in a single pass over the entity array (one list instead of the previous filter-then-map two), cutting an intermediate allocation on every material/shadow/visibility update of multi-entity models. Deliberately not cached across calls — `ModelInstance` is an external `FilamentInstance` typealias with no invalidation hook, and a stale handle list would be the exact silent native-handle bug this audit targets.
  - `HitResultNode` gains an opt-in `refreshIntervalMs` (default `0` = run the ARCore `Frame.hitTest` every frame, byte-for-byte as before) that rate-limits the per-pixel raycast the same way `PointCloudNode`/`DepthMeshNode` rate-limit their rebuilds; between hit tests the node keeps its last pose and the smooth-transform interpolation still runs every frame.
  - `PlaneVisualizer` (V1) and `PlaneVisualizerV2` reuse a pre-allocated 2-element list for `updateRenderable()`'s primitive selection instead of allocating a fresh `buildList { }` per plane per frame; the shared `selectPlanePrimitives` helper clears and refills it from the live visibility/shadow-receiver flags each call, so no cached state can go stale.
- **perf(SceneViewSwift): diff-guard `applyCamera()` so an unchanged orbit skips the per-frame RealityKit camera write (#2331).** `applyCamera()` ran on every `RealityView.update:` tick, every auto-rotate step (~60 Hz), every framing re-fit, and every drag/pinch tick — and each call unconditionally re-pushed the scene-root identity transform plus the perspective camera's `look(at:from:)` / position+orientation, even when nothing about the orbit had moved. Every per-mode branch is a pure function of the camera's `{mode, azimuth, elevation, orbitRadius, target, fov, firstPersonEye}`, so the apply now snapshots that state (after the mode-sync that may mutate it) and early-returns when it matches the last-applied snapshot within a float tolerance (`1e-5` rad / world units — far below one pixel of motion at any realistic scene scale, ~80× smaller than the smallest single-frame auto-rotate step, so a live camera never freezes). Behaviour-preserving: a real orbit/pan drag, an auto-rotate tick, a pinch, or a `refreshContentCentering` re-fit (which mutates `target`/`orbitRadius` then re-calls `applyCamera`) all change the key and re-apply on the same frame; all RealityKit/entity writes stay on the main actor exactly as before. No public API change. Phase-2 hot-path cleanup under the #2328–#2332 perf umbrella (the Rerun/SceneObserver half landed in #2372).
- **perf(web):** `sceneview-web` now renders **on-demand** instead of redrawing every frame. A dirty-flag gate (`RenderGate`) submits a GPU frame only when something actually changed — the camera moved, an animation is playing, an async model/environment load is in flight, the auto-center pass is still running, a resize happened, or a scene/material mutation called `requestRender()` — so an idle static scene no longer runs the full SSAO+bloom+TAA pipeline 60×/second. The `requestAnimationFrame` loop itself is never gated (only the draw call is) and the gate over-renders a short settle tail after every change, so the canvas can never freeze and async texture uploads always paint. Also trims per-frame churn: the animation loop no longer allocates a closure/iterator each tick and the `requestAnimationFrame` callback reference is hoisted. (#2332)
- Dropped per-setup micro-allocations in `sceneview-core` geometry/animation: `AnimationSequence.currentStepIndex`, `generateExtrude`, and `generateLathe` now use index loops instead of `withIndex()` (no per-step/per-point `IndexedValue` boxing), and `generateIcosphere` builds its index list directly into a pre-sized list instead of allocating a `List` per face. Behavior-preserving (geometry/animation output is byte-identical, verified by the existing test suites). Part of #2402.
- **Perf: cache `Node` parent/local-transform reads and reuse the `Pose.transform` scratch buffer — fewer Filament JNI round-trips and per-frame allocations on hot paths (#2403, #2404, #2405, #2406; audit umbrella #2402).** Four behavior-preserving caching changes in the transform/parenting hot paths, mirroring the existing `Node._worldTransform` / `_transformInstance` cache pattern: `Node.parentEntity` no longer calls `TransformManager.getParentOrNull()` on every read (#2403); `Node.parentInstance` no longer calls `getParentOrNull()` + `getInstance()` on every read (#2404) — both are cached behind a validity flag (so a legitimately-`null` "no parent" is cached, not re-fetched) and invalidated on the single reparent write path. `Node.transform` no longer calls `TransformManager.getTransform()` (a JNI round-trip plus a `FloatArray(16)` + `Mat4` allocation) on every read; the cache is populated by both local-matrix write paths with the exact matrix pushed to Filament, so the per-frame `node.transform` read an animated node makes (`NodeAnimationDelegate.onFrame`) is served without JNI even while the animation writes every tick (#2405). `Pose.transform` reuses a per-thread scratch `FloatArray(16)` instead of allocating one on every access, eliminating steady GC churn for nodes that refresh a pose 60–120 Hz (#2406). No public-API, threading, or rendering-semantics change — Filament JNI still runs on the main thread; cache invalidation is proven by an engine-backed `androidTest` (equivalence-after-mutation + read-stability across reparent/detach/local writes) and pure-JVM contract tests.

### Fixed

- **iOS Explore: Sketchfab models no longer fail with "We were unable to load the model" (#2252).** The Sketchfab download path requested the GLB format, but RealityKit's `Entity(contentsOf:)` can only load USDZ/`.reality` — never GLB/glTF — so tapping any streamed Sketchfab model threw and showed a "Failed to load model" error. This was the App Review Guideline 2.1(a) (App Completeness) rejection, reproduced on an iPad Air 11-inch (M3): bundled USDZ models always loaded, but the live Sketchfab feeds (only present when an API key is configured, i.e. release builds) did not. The service now requests the model's USDZ format and caches it with the correct `.usdz` extension; when a model offers no USDZ it surfaces an honest "not available in USDZ" message instead of a generic error. The demo target also pins `PRODUCT_MODULE_NAME` so the macOS `PRODUCT_NAME` rename (sibling fix) keeps the Swift module name stable for the test target.
- **macOS App Store app now installs as "SceneView", not "SceneViewDemo" (#2252).** The demo target's `PRODUCT_NAME` was left at `$(TARGET_NAME)`, so the built bundle, executable and `CFBundleName` were all `SceneViewDemo` — the installed name and menu-bar name did not match the "SceneView" App Store name, and the binary carried demo-naming. App Review rejected the macOS build under Guideline 2.3.8 (Accurate Metadata) and 2.2 (Beta Testing — demo language in binary naming). `PRODUCT_NAME` is now pinned to `SceneView` for the app target (the `io.github.sceneview.demo` bundle identifier is unchanged, so existing installs upgrade in place). Completes the partial #1688 fix, which had only renamed `CFBundleName`.
- **App Store "What's New" notes no longer leak other-platform references (#2252).** The iOS/macOS release notes are extracted from the cross-platform `CHANGELOG.md`, so Android/Web/Flutter bullets reached App Store Connect and tripped Guideline 2.3.10 (Accurate Metadata). The extractor now drops bullet lines that mention non-Apple ecosystems before they are pushed.
- **CI: App Store auto-submit no longer 409s on a stale open review submission ([#2301](https://github.com/sceneview/sceneview/issues/2301)).** A `workflow_dispatch` submit run that died between `POST /v1/reviewSubmissions` and the final `submitted: true` PATCH (e.g. `reviewSubmissionItems` errored) left an open, unsubmitted `reviewSubmission` attached to the app, so the next run's CREATE returned 409 since App Store Connect allows only one open submission per app — the same "stranded resource blocks CREATE" class #1831 fixed on the legacy API. `app-store.yml` now lists `GET /v1/apps/{id}/reviewSubmissions?filter[platform]=IOS&filter[state]=READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW,UNRESOLVED_ISSUES` and cancels each stale open submission via `PATCH {canceled: true}` (there is no DELETE for reviewSubmissions) before creating a fresh one, logging the HTTP status before branching.
- Demo app: the **PBR Materials** (`materials`) and **Gallery** (`model-viewer`) tabs no longer hang forever on the "Streaming material…" / "Streaming model…" loading scrim offline or on the emulator. The shared root cause was not a network dependency: both tabs fed the resolved `file://` model path to the two-argument `rememberModelInstance(modelLoader, …)`, which Kotlin binds to the asset-path overload — it tried to open the `file://` URI through `AssetManager`, failed silently, and left the model `null`. They now load the resolved file (streamed GLB or bundled fallback) through `ModelLoader.loadModelInstance("file://…")`, mirroring the already-fixed Multi-Model section, so the bundled fallback renders immediately with no network. The Gallery "Nile" chip (and the AR "Coffee Mug" entry) also pointed their offline fallback at `khronos_toy_car.glb`, whose Draco mesh buffer Filament cannot decode; they now fall back to a decodable bundled GLB so the default Gallery view renders offline ([#2302](https://github.com/sceneview/sceneview/issues/2302), [#2306](https://github.com/sceneview/sceneview/issues/2306)).
- **`ModelNode(centerOrigin = …)` is no longer silently ignored (Android).** The `ModelNode` composable applied `centerOrigin` in the underlying node's constructor (`position += origin * size`) but then immediately overwrote `node.position` with the `position` parameter — on creation *and* on every recomposition — so any non-zero `centerOrigin` (e.g. `Position(0, -1, 0)` to bottom-align a model) did nothing and the node rendered at the origin. `centerOrigin` now composes **additively** with `position`: the alignment offset survives when `position` is left at its default, and the two can be combined (bottom-align *and* place a model at a point). `centerOrigin = null` / `Position(0,0,0)` and the imperative `ModelNode` class are unaffected. Discovered while fixing the Materials → Occlusion demo (#2304).
- **Demo: the Materials → Occlusion tab now reads at a glance (#2304).** The occluder plane sat *behind* the helmet (and, when in front, was centred on it and covered the whole silhouette), so the depth-occlusion effect the tab exists to show never read. The section is reframed: the helmet sits at the world origin, scaled up and framed close by a static camera on the studio IBL, and the occluder is a vertical wall whose edge sits on the helmet's centre line — so it hides exactly one lateral half of the helmet, giving an obvious vertical occlusion cut down the middle while the other half stays fully visible. Demo-only; no library API change.
- **Demo: WebP-textured models no longer render black/untextured (#2305).** Four `android-demo` models embedded their textures as WebP (`EXT_texture_webp`) and rendered black or untextured for weeks, because Filament's Android prebuilt ships `gltfio` with WebP support compiled out (`isWebpSupported() == false`, no `image/webp` provider — verified at the binary level). They are re-encoded to a Filament-decodable format: `khronos_damaged_helmet.glb` (drops the redundant WebP variant, keeps its existing JPEG), `shiba.glb`, `threejs_soldier.glb`, `khronos_lantern.glb` (WebP → PNG; Draco geometry preserved). This also fixes `android-tv-demo`, which shares the same bundled assets. (`khronos_toy_car.glb` was already re-encoded separately in #2401.) Net APK asset growth ≈ +5.5 MB (PNG is heavier than WebP; `khronos_lantern` is the driver — KTX2/Basis is a tracked follow-up to reclaim it). General SDK-level WebP support — and the same `EXT_texture_webp` limitation on the web build (Filament.js registers no `image/webp` provider either, so the `website-static` platform models are affected too) — remains tracked on #2305.
- **perf(sceneview): cache the smoothTransform target's TRS in `NodeAnimationDelegate` — 3 → 0 `Mat4` decompositions/frame (#2324).** The smooth-transform slerp hot path still re-decomposed the **target** `Transform` every frame (`target.position` / `target.quaternion` / `target.scale` — each a polar decomposition plus column-length `sqrt`s), re-deriving the same TRS for the whole animation. The target's decomposed `(position, quaternion, scale)` is now cached once per target value and reused each frame; the cache is keyed on the target's value (structural `Mat4` equality), so it invalidates correctly on both a re-assigned target and an in-place mutation of the same target matrix. Combined with #2289 (which took the start-side decompositions from 6 → 3 per frame), the smooth-transform hot path now runs **0** matrix decompositions per frame while the target is stable. The interpolated trajectory is byte-identical — only the redundant per-frame work is removed. Follow-up of #2317/#2289 under the #2263 hot-path umbrella.
- **Five unlit transparent materials no longer wash out / read as an opaque "blob" over bright backgrounds (#2325).** `image_texture`, `transparent_unlit_colored`, `view_texture_unlit` (Android `ImageNode`/`TextNode`/`ViewNode`/`MaterialLoader`) and the AR `semantics_overlay` + `face_mesh` materials all used `blending: transparent` (premultiplied-alpha compositing) but emitted a **straight, non-premultiplied** `baseColor`, so a partial-alpha surface composited as `color + (1-alpha)*background` — the colour was added at full strength and lowering the alpha never reduced it. Each fragment now premultiplies `baseColor.rgb *= baseColor.a`, so the surface composites as the intended `lerp(background, color, alpha)`. This is the same fix class as the #2224 AR plane renderer. Opaque (`alpha = 1`) rendering is unchanged. All five `.filamat` blobs were recompiled with the pinned matc 1.71.5 (MATERIAL_VERSION 71).
- perf(sceneview): trimmed per-frame and per-gesture-event allocations on Android hot paths (#2328). `LightComponent.color` reads (overridden in `LightNode`) now reuse a per-instance scratch `FloatArray` instead of allocating a throwaway one every read; `NodeGestureDelegate.onRotate` reuses a shared world-up axis constant instead of allocating a `Float3` per rotate event; `CameraGestureDetector`'s `TouchPair` is built directly from the `MotionEvent` (1–2 `Float2` for the common 1–2-pointer case instead of 3–4); and `ModelNode.applyAnimations` drops its per-frame `MutableIterator`/in-place-removal in favour of a reused scratch list. All changes are behaviour-preserving — render output and gesture math are identical.
- **`arsceneview` Phase-2 hot-path cleanups — per-frame AR allocation/JNI wins ([#2329](https://github.com/sceneview/sceneview/issues/2329)).** Two behaviour-preserving micro-optimizations from the hot-path audit (umbrella [#2263](https://github.com/sceneview/sceneview/issues/2263)):
  - **AR5 — `ARCameraNode` projection cache.** `onCameraUpdated` rebuilt the camera projection every tracked frame, allocating a `FloatArray(16)` + a `Transform` and firing two redundant JNI calls (`Camera.getProjectionMatrix` + the Filament `projectionTransform` setter) for an identical result. The projection is now cached and recomputed only when `near`/`far` change **or** the AR display geometry changes (`Frame.hasDisplayGeometryChanged()` — the same authoritative signal `ARCameraStream` already uses), so a device rotation/resize can never freeze a stale projection. Output is identical frame-to-frame; only the allocation/JNI churn is removed.
  - **AR8 — `PointCloudNode` opt-in rate-limit.** `update` rebuilt the cloud on every tracked frame — allocating a positions `FloatArray` plus two direct `ByteBuffer`s for the Filament upload — with no rate-limit (unlike `DepthMeshNode`/[#1810](https://github.com/sceneview/sceneview/issues/1810)). A new `refreshIntervalMs` parameter (on the `PointCloudNode` constructor and `rememberPointCloud(...)`) gates the rebuild the same way `DepthMeshNode` does. It **defaults to `0` = rebuild every frame**, so existing behaviour is byte-for-byte unchanged; set a positive value (e.g. `200` = 5 Hz) to cut the per-frame allocation. The Filament upload buffers are still allocated fresh per rebuild (Filament copies asynchronously — pooling them would risk a torn upload, the [#1841] invariant).

  AR12 (sharing one `frame.hitTest` across `HitResultNode`s at the same screen point) was **not** done: the hit-test is an opaque user lambda with its filters/screen-point captured inside it, so sharing results across nodes can't be made behaviour-preserving without a public-API redesign and regression risk. [#2329](https://github.com/sceneview/sceneview/issues/2329) stays open for AR12.
- **Collision math allocation hygiene in `sceneview-core` (#2330).** The KMP collision hot paths no longer allocate a per-test swarm of `Vector3`/`Pair`/`List` objects — every intersection result (hit/miss, distance, point, normal) is byte-for-byte unchanged, only the garbage is gone. `Box.rayIntersection` and `Capsule.rayIntersection` now inline the slab/cylinder/cap math as scalar reads (was ~7 `Vector3` per ray test); `Intersections.boxBoxIntersection`'s SAT test builds its vertices/axes into function-local `FloatArray` scratch (was ~40 `Vector3`); the sphere/box test routes through a new allocation-free `pointWithinBoxDistance` (was ~10 `Vector3` in `closestPointOnBox`); `Capsule.capsuleBoxIntersection` no longer allocates a `Sphere` per test point; `Capsule.getSegmentEndpoints` gained an allocation-free `writeSegmentEndpoints(bottom, top)` sibling used by the hot paths; `MeshCollider.rayTriangleIntersection` returns a shared immutable MISS constant instead of allocating a `MeshHitResult` + two `Vector3.zero()` on every miss; and `Octree.query`/`queryRay` gained caller-supplied-sink overloads that thread one list through the recursion instead of allocating a `mutableListOf` + `addAll` per node (the `withIndex()` triangle loops in `MeshCollider`/`Octree` are now index loops, no `IndexedValue` boxing). All scratch is function-local, so the shared KMP code stays thread-safe for off-thread collision queries. Pure-math change, fully pinned by the collision unit tests (Android + iOS).
- **perf(SceneViewSwift):** cut per-emit allocation and main-thread churn in the iOS Rerun bridge and scene observer (#2331, partial). `RerunBridge` now bumps its event total on the I/O queue and publishes `eventCount` to the UI in a *coalesced* hop instead of one `DispatchQueue.main.async` per emitted line (~420/s under a busy ARKit stream) — the published total is unchanged, only the per-line main-thread wake is gone. `RerunWireFormat.pointCloud(_:)` serializes straight from the ARKit `[SIMD3<Float>]` buffer (byte-identical JSON, proven by a golden test) instead of reflattening into a temporary `[Float]` array every emit. `SceneObserver.update()` gates its `@Published entityCount`/`estimatedFPS` writes on a real value change, so a static scene graph no longer re-publishes (and re-wakes every bound SwiftUI view) every frame. The `applyCamera()` per-frame diff-guard from the same issue is deferred to a dedicated visual-QA pass and #2331 stays open.
- **The weekly `doc-audit` cron now surfaces failures as a de-duplicated tracking issue instead of failing silently ([#2340](https://github.com/sceneview/sceneview/issues/2340)).** An expired `CLAUDE_CODE_OAUTH_TOKEN` (or any failure) previously only showed in the Actions tab, so the audit could silently stop for weeks; an `if: failure()` step now opens or refreshes one tracking issue per outage.
- **Demo device-QA now builds WITH the API keys and honestly SKIPS the key-gated paths when a key is absent ([#2343](https://github.com/sceneview/sceneview/issues/2343)).** The QA harness (`device-qa.sh` / `qa-android-demos.sh`) used to build the demo `assembleDebug` with no `SKETCHFAB_API_KEY` / `ARCORE_API_KEY` injected, so the Explore/Sketchfab path and the AR Cloud demos (Cloud Anchors / Geospatial / Streetscape) were never exercised — yet the run reported a complete green QA. A new sourced helper (`.claude/scripts/lib/qa-keys.sh`) resolves both keys (env, else repo-root `local.properties`) and exports them so the existing `build.gradle` wiring bakes them into the debug APK. When a key is absent the run now records a dedicated `skipped` advisory leg (`sketchfab` / `arcore-cloud`) in `device-qa-report.json` with reason `key missing — … NOT tested`, drives the release gate to `warn` (never a silent `clear`), and prints a loud unmissable banner — impossible to misread as complete. When a key IS present, any pre-existing (possibly keyless) demo APK is deleted before the build/install so a stale artifact can never short-circuit the keyed build — an env-sourced `buildConfigField` is not a tracked Gradle input, so deleting the APK, not trusting UP-TO-DATE, is the robust trigger. The CI `android` / `ar` device-QA legs inject the secrets so the keyed paths are exercised in CI too. Presence only is ever logged; no key value is printed or committed.
- **Explore "Open in SceneView" viewer now auto-fits any model to the camera ([#2348](https://github.com/sceneview/sceneview/issues/2348)).** The Sketchfab viewer rendered the model with `scaleToUnits = 1f` (normalised to a unit cube) yet computed the orbit radius from the model's *raw* glTF bounding box — two different scales — so a car authored in large units rendered tiny in a black void while a small-unit character was over-zoomed to its legs. The viewer now mirrors the correctly-framed `ModelViewerDemo`: it renders at the model's true glTF size, recenters it on its bounding-box centre (off-origin glTF pivots were the prime cause of the "cut off at the legs" framing), and derives the orbit distance from the library helper `io.github.sceneview.fitDistanceForBounds` (bounding-sphere fit on **both** axes, fed the live render-surface aspect and the stock 28 mm lens FOV). Tall (Scifi Girl) and wide (Porsche) models now both frame to roughly 85 % of the viewport, centred — verified visually on a Pixel_7a emulator with the keyed build.
- **AR Geospatial demos no longer leak the raw `FatalException` class name into the UI ([#2349](https://github.com/sceneview/sceneview/issues/2349)).** When a Geospatial session failed to establish (no VPS coverage / no ARCore Cloud API key — e.g. on an emulator), ARCore throws a `FatalException` with a null message, and the demos surfaced `exception.message ?: exception.javaClass.simpleName` directly, so the status banner read the literal **"AR session error: FatalException"**. A new shared mapper `friendlyArSessionError(...)` translates known ARCore exception classes to honest, actionable copy and degrades the unknown / null-message case to "AR couldn't start — this needs a device with VPS coverage and an ARCore Cloud API key." Applied to `ARTerrainAnchorDemo`, `ARRooftopAnchorDemo`, and `ARStreetscapeDemo` (which shared the identical bug). Verified on a Pixel_7a emulator: a real `com.google.ar.core.exceptions.FatalException` now renders the friendly message and "FatalException" no longer appears anywhere in the UI.
- **Models demo "Surprise me" button is no longer clipped by the Settings FAB ([#2350](https://github.com/sceneview/sceneview/issues/2350)).** The extended "Surprise me" FAB and the `DemoScaffold` Settings FAB / peek chip were both pinned to the bottom-end corner with the same 16 dp padding, so the round Settings control sat on top of the extended FAB and truncated its label to "Surprise…". The "Surprise me" FAB now lives in the bottom-**start** corner (with system-bar inset padding) so the two controls occupy opposite corners. Verified visually on a Pixel_7a emulator — the full "Surprise me" label is readable with no overlap.
- **Lighting Lab's Time-of-Day slider now actually swaps the HDR sky ([#2353](https://github.com/sceneview/sceneview/issues/2353)).** Dragging Time of Day from noon to night updated the label and the dynamic sun, but the skybox + IBL stayed frozen on the initial noon `outdoor_cloudy_2k.hdr` — the marquee day↔night effect of the flagship lighting demo silently did nothing. `rememberEnvironment` memoised only on `(environmentLoader, isOpaque, environment)`; the factory lambda closed over the time-of-day-derived HDR path but Compose treats the lambda as a stable key, so `createHDREnvironment` ran once and never re-ran. `rememberEnvironment` now takes an optional `key: Any? = null` that participates in its memoisation (rebuilding and disposing the old `Environment` when the key changes), and the demo passes `key = envAsset`. Verified visually on a Pixel_7a emulator: noon shows the blue-sky HDR, dragging to night shows the dark rooftop-night HDR. The new `key` parameter is a **public-API addition** to `rememberEnvironment` (both overloads): it is source-compatible — every existing call site is unaffected — but, like any Compose `@Composable` signature change, the generated JVM method descriptor changes, so consumers must recompile against this release (binary-incompatible; permitted on a minor bump per the project's version policy).
- **iOS Shape Extrude: presets now render as real shapes instead of a collapsed edge-on ribbon (#2354).** `ShapeNode` built its polygon in the **XZ** (horizontal) plane with a +Y normal, while SceneView Android's `ShapeGeometry` builds it in the **XY** plane facing the camera (+Z). With the demo's horizontal orbit camera, a horizontal star was seen nearly edge-on — the default "Star" preset rendered as a thin gold ribbon/bowtie rather than a star (and L-Shape/Arrow were unreadable). `ShapeNode` now builds the polygon in the XY plane facing +Z (flat vertices at `(x, y, 0)` with a `(0,0,1)` normal; extrusion is symmetric along Z with front/back faces at `±depth/2` and outward-wound side quads), matching Android. The ear-clipping triangulator was unchanged — it already handled the concave star/L-shape/arrow correctly; the bug was purely the build plane. The polygon is now normalised to a single counter-clockwise winding before meshing, so a **clockwise** `ShapeNode(points:)` input (the public API documents no winding requirement) no longer renders its extruded side walls inside-out — previously a clockwise polygon got an outward normal but kept the clockwise face winding, so single-sided materials back-face-culled the real outer wall. `ShapeExtrudeDemo` was retuned with a gentle compound tilt so the shape reads head-on while the extrusion depth stays visible.
- **iOS Multi-Model "Park" demo no longer renders four identical copies of the same island in keyless mode ([#2355](https://github.com/sceneview/sceneview/issues/2355)).** The four `park` slots (tree / bench / dog / bird) in `SampleAssets.swift` all declared the same `fallbackBundledPath: "Models/tree_scene.usdz"`, so a build without a Sketchfab API key (the default local + App Store build) stacked two-to-four copies of the same 14 MB terrain island at slightly different positions instead of a multi-model diorama — and the "Loading park scene…" scrim never cleared early because every slot loaded the same heavy file, defeating the #1056 progressive-reveal. The bench / dog / bird slots now fall back to **distinct, lighter** bundled USDZs (`retro_piano.usdz` 1.8 MB as the foreground prop, `animated_butterfly.usdz` 3.1 MB as the animated occupant, `phoenix_bird.usdz` 1.1 MB as the perched bird) so keyless mode shows four distinct silhouettes, and the lightest slot (the 1.1 MB bird) lands first and dismisses the scrim early. The keyed path is untouched — it still streams the real Sketchfab oak-tree models.
- **Camera & Gestures "Free Flight" mode no longer opens on a black void ([#2357](https://github.com/sceneview/sceneview/issues/2357)).** Switching the camera mode to Free Flight dropped the user into an empty black viewport with nothing to look at, and the void even leaked back into Orbit afterwards. The real cause was the demo wrapping its whole `SceneView` subtree in `key(selectedMode, …)`: every mode switch tore down and rebuilt the scene, and the rebuilt `ModelNode` re-attached the *already-attached* shared `modelInstance`, leaving the new scene with nothing to render. The demo now keeps a single stable `SceneView` and swaps only the Filament `Manipulator` per mode (SceneView already adopts a new `cameraManipulator` live), so the helmet stays rendered and centred across Orbit → Free Flight → Map and after Reset Camera. The Free Flight start orientation is now also derived from the `home → target` vector (Filament's `eulerZYX(0, yaw, pitch) · (0,0,-1)` convention) instead of a hard-coded `(0,0)`, so the camera is correctly aimed at the model for any camera home. Verified visually on a Pixel_7a emulator (helmet centred in Free Flight, after Reset Camera, and back in Orbit).
- **The floating Feedback chip no longer overlaps a content card at rest ([#2358](https://github.com/sceneview/sceneview/issues/2358)).** The chip is anchored to a fixed bottom-left band, so a full-width card naturally resting in that band was masked even though [#2194] reserved bottom padding for the *last* item — on Explore the first "Trending models" card ("Scifi Girl v.01"), and on About the Sponsor monetization CTA ("Help keep the project free & active"). The chip now follows the standard Material 3 scroll-aware-FAB behaviour: it is hidden while the list is at its resting (top) position — where the overlap occurred — and slides in from the left the moment the user scrolls, by which point the overlapped card has left the band. Applied consistently across every tab that shows the chip (Explore, About, AR View, Samples). Verified visually on a Pixel emulator: at rest the Sponsor card and the first Trending card are fully visible and tappable; the chip reveals on scroll over empty gutter space.
- **Flutter & React Native bridges: switching the HDR environment at runtime now actually swaps the skybox ([#2361](https://github.com/sceneview/sceneview/issues/2361)).** Both Android bridges built their `Environment` via `rememberEnvironment(environmentLoader) { createHDREnvironment(path) … }`, where `path` is runtime-mutable (Flutter's `setEnvironment` method-channel, RN's environment prop). Because the factory lambda is a stable Compose `remember` key, swapping one non-null HDR for a different non-null HDR left the skybox/IBL frozen on the first one — the same stale-factory class as #2353. Both call sites now wrap the build in a `key(path) { … }` block so a new path tears down and rebuilds the `Environment` (disposing the old one). The fix uses Compose's `key {}` rather than `rememberEnvironment`'s own `key=` parameter on purpose: the bridges compile against the **published** Maven artifact (`sceneview:4.6.2` / `4.7.0`), which predates that parameter, whereas `key {}` works on every SceneView version. Pre-existing bug, not a regression.
- **`impact-check.sh` now runs correctly in a lean `--depth 1` + sparse clone — the standard batch-agent workflow (#2370).** Two failure modes are fixed. (1) The node-count consistency check FALSE-FAILed every "N+ node types" doc claim (`Claims 42, actual 24`) when only one of the two node-source dirs was checked out: the total is the SUM of `sceneview/.../node` + `arsceneview/.../ar/node`, so a sparse checkout that omitted `arsceneview/` produced a partial count and hard-failed (a blocker under `--fail` in the quality gate). The check now SKIPs — never FALSE-FAILs — unless BOTH source dirs are present so the total is complete, while still actively flagging a real count mismatch on a full checkout. (2) The sample-build check silently no-oped in a shallow clone: `git diff HEAD~1 HEAD` can't resolve `HEAD~1` without history, so it always reported a misleading "No SDK/sample source changed" PASS. It now picks a diff base that exists (`HEAD~1` in a full clone, else `origin/main` plus uncommitted working-tree edits), SKIPs honestly when `samples/android-demo` is sparse-excluded or no base is resolvable, and never silently passes. Full-clone behaviour is unchanged. A new `test-impact-check.sh` self-test (wired into `ci.yml` → `repo-hygiene`) pins the contract.
- **Bumped `vitest` 3.x → ^4.1.0 in the three Cloudflare worker projects to clear GHSA-5xrq-8626-4rwp ([#2374](https://github.com/sceneview/sceneview/issues/2374)).** `telemetry-worker`, `mcp-gateway`, and `feedback-worker` each declared `vitest` 3.x as a devDependency, which Dependabot flagged with 3 critical alerts for the Vitest UI server arbitrary-file-read advisory. All three now resolve to vitest 4.1.8 (vite 8). vitest is a test-only devDependency and the three live gateways do not ship it, so this was never a production-runtime risk — but the bump silences the critical alerts. The simple `defineConfig` Node-pool configs needed no v4 migration; every worker's test suite passes (telemetry 57, gateway 180, feedback 45). Lockfiles regenerated.
- Demo app: fixed two pre-existing issues found while landing the offline-scrim fix ([#2390](https://github.com/sceneview/sceneview/pull/2390)). (1) The same `rememberModelInstance(modelLoader, "file://…")` overload trap — a two-arg positional call binds to the asset-path overload and silently fails to load `file://` URIs — also affected the **Animation & Physics** carousel (streamed models), the **AR Placement** / **AR Instant Placement** demos (streamed placements), and the **Model Viewer** "Surprise me" stream; they now pass `fileLocation =` to bind the URL-capable overload, which scheme-detects bundled asset paths and `file://` URIs alike. (2) `khronos_toy_car.glb` was unparseable by Filament's `gltfio` — a babylon.js export left an out-of-bounds `clearcoatTexture` index plus `webp`-only textures (Filament's bundled runtime has no `image/webp` decoder) — so it rendered black even after parsing. The asset was re-exported (dangling texture dropped, textures transcoded off `webp` to PNG/JPEG, mesh re-Draco-compressed) and now decodes **and** renders textured, fixing the AR **Image**, **Depth of Field** and AR-View "Toy Car" demos (and the shared Android-TV "Toy Car") that load it as a bundled asset, and it is restored as the distinct Gallery "Nile" / AR "Coffee Mug" fallback ([#1433](https://github.com/sceneview/sceneview/issues/1433)).
- `ViewNode.WindowManager.resume()` now arms the off-screen retry listener synchronously when the owner View is detached, instead of deferring through `View.post()`. On a detached View `post()` queues into the `HandlerActionQueue` (flushed only on attach, by which point `isAttachedToWindow` is already true), so the documented #984 retry path (`OnAttachStateChangeListener`) was unreachable dead code. The end behaviour is preserved (the off-screen window attaches when the owner attaches), but the retry is now explicit and the `ViewNodeTest.windowManager_resume_withDetachedOwner_registersAttachListener` regression — which deterministically reddened the Render Tests workflow on every push to `main` — is fixed (#2393).
- **A model swapped into a single slot no longer leaves the previous model "stacked" behind the new one (#2400).** Switching the model in one slot (the Model-Viewer → Gallery chips, "Surprise me", the Animation & Physics carousel) appeared to render the new model on top of the old one. Device investigation (logcat probe on the Filament `Scene`) proved the issue's first hypothesis — that `ModelNode` disposal fails to remove the model's renderable entities — is **not** the cause: on every swap `SceneNodeManager.removeNode` correctly removes all of the old model's entities (`Scene.getRenderableCount()` drops to the new model's count and `Scene.hasEntity(...)` returns `false` for the old renderables). The real cause is rendering, not disposal: Filament defaults to `Renderer.ClearOptions.clear = false` and relies on the **skybox** to repaint the background every frame, but the model demos use an IBL-only environment (`createSkybox = false`) so the model can float on the surface background. With no skybox the swap chain is never cleared, so when the rendered footprint **shrinks** — a large model replaced by a smaller one — the previously-rendered pixels the new model does not cover are left on screen, looking like a stale model stacked behind the new one. `SceneView` now sets `Renderer.ClearOptions.clear = true` (clear to opaque black when `isOpaque`, transparent otherwise), so the color buffer is repainted every frame. When a skybox **is** present it simply overdraws the clear, so skybox scenes are unaffected. Verified on an ARCore emulator: before, the green toy-car lingered behind the fox / lantern after a Gallery chip switch; after, only the selected model renders. This per-frame-clear gap is Android/Filament-specific: Web (`sceneview-web`) already sets `clear: true` at scene setup and iOS (RealityKit) clears its framebuffer in the native render loop, so neither has the stale-pixel bug — only a quick iOS confirm pass is tracked as a parity follow-up.
- **iOS AR: `ARSceneView` no longer leaks RealityKit/ARKit resources when the SwiftUI view is removed (#2407, #2408 — audit #2402).** The `UIViewRepresentable` had no teardown path, so when the AR view left the hierarchy the `ARSession` kept running — the rear camera, motion sensors, and per-frame tracking pipeline stayed live, draining battery — and every anchor the coordinator had added stayed parented in `arView.scene`: the translucent detected-plane overlays (#2407) and the dual main/fill light anchors (#2408) were orphaned for the process lifetime. `ARSceneView` now implements `dismantleUIView(_:coordinator:)` as the primary, main-actor teardown — it pauses the session, detaches the session delegate, and removes + releases the plane overlays and the cached light anchors (reusing the #2278 cached-anchor references). The coordinator also gains a `deinit` safety net that breaks the same strong-reference graph if it is ever released without a dismantle, mirroring `SceneEntities.deinit`'s main-thread-guarded teardown (#2068). The teardown is idempotent, so the two paths never double-free. This brings iOS to parity with Android's `ARScene` `DisposableEffect`/`onDispose` teardown, which already paused the session and destroyed the plane renderer + lights. Verified on the iOS simulator with a weak-reference leak test (`ARSceneViewTeardownTests`): provisioned overlays + light anchors are removed from the scene and their `weak` references go `nil` after teardown, and the session delegate is detached.

### Tests

- **iOS gesture-delta tests now drive the production code path instead of a re-implementation ([#2313](https://github.com/sceneview/sceneview/issues/2313)).** Extracted the drag cumulative→per-frame-delta conversion + per-entity baseline out of the `private` `entityDragGesture` into an internal `EntityDragState`, so `GestureSystemTests` exercises the **same** code the gesture runs — a regression in the real `current − previous` dispatch or the `.onEnded` baseline reset is now caught (verified by mutation testing). Also added a test for the screen→world `worldTranslation` scale + Y-flip that was previously untestable.
- **Every `ALL_DEMOS` id is now guaranteed routable by the debug deep-link host, with a pure-JVM guard ([#2320](https://github.com/sceneview/sceneview/issues/2320)).** `DemoHostActivity` — the debug-only host that instrumentation tests and the `--es demo_id <id>` QA channel use to launch a single demo composable directly — hand-maintained a `when (id)` mapping ids to composables. A demo in the catalog but missing a branch crashed the harness with `error("Unknown demo id")`; [#2319](https://github.com/sceneview/sceneview/issues/2319) found three such demos by interactive QA, and an audit during this fix found **seventeen** more AR demos in the same state (`ar-hand-tracking`, `ar-scene-mesh`, `ar-orbital`, …) — all of which would have crashed the host. `DemoHostActivity` now delegates routing to the collator-generated `GeneratedDemos.Screen` (the same router the main-app `DemoRouter` uses), resolving retired ids through `DeepLinkRouter` aliases first, so it covers every catalog id by construction — the hand-written-`when()` drift class is eliminated. A new `:samples:android-demo:testDebugUnitTest` test (`DemoHostRoutableTest`) asserts the pure, non-composable `DemoHostActivity.routableId` resolver covers every `ALL_DEMOS` id and every retired-id alias — no emulator, no instrumentation.
- **iOS demo QA can now build WITH the Sketchfab API key, so the Explore/Sketchfab path is exercised instead of shipping untested ([#2356](https://github.com/sceneview/sceneview/issues/2356)).** On a fresh checkout `samples/ios-demo/SceneViewDemo/Secrets.xcconfig` is absent, so local + QA builds were keyless: `SketchfabConfig.apiKey` was `nil`, the Explore carousels + search were disabled, and the streamed-USDZ demos (Multi-Model, Orbital, Model-Viewer) silently fell back to bundled assets — the exact live path that caused the App Review 2.1(a) rejection in #2252 (RealityKit can only load USDZ, never GLB) went unverified, yet a green QA run looked complete. This mirrors Android #2343 for iOS. A committed **`Secrets.xcconfig.template`** (placeholder only) documents the key, the real **`Secrets.xcconfig` stays gitignored** (`**/Secrets.xcconfig`), and a committed **`Config.xcconfig`** `#include?`s it optionally so a keyless checkout still builds silently. `ios-device-qa.sh` now sources the shared `qa-keys.sh` resolver (env → repo-root `local.properties` `sketchfab.api.key`), passes the key to `xcodebuild` as a `SKETCHFAB_API_KEY` user-defined build setting (substituted into `Info.plist`'s `SketchfabAPIKey = $(SKETCHFAB_API_KEY)`), and adds a `--sketchfab-key` override flag; a keyless run prints a loud banner and is reported as NOT having tested the Sketchfab path (advisory). Presence only is ever logged — the token value is never printed or committed.
- **Web: the published `sceneview-web` Kotlin/JS bundle could not initialise Filament in a browser — `createViewer()` hung forever on a blank canvas (#2410).** The npm/CDN bundle (`sceneview-web@<v>/sceneview-web.js`) threw during `SceneView.create()` and, because the error was only `console.error`-ed, the returned Promise never settled. Root cause was a chain of init-path bugs hidden because the `jsTest` suite stubs the Filament externals and the demo/website run the separate hand-authored `sceneview.js`: Kotlin `as`/companion access against a Filament `external class` compiled to `instanceof <undefined>` (the class binding is captured at module-load, before `Filament.init()` attaches the embind classes) — `TypeError: Right-hand side of 'instanceof' is not an object`; `Camera.setProjectionFov` was called with 4 args where embind enforces 5 (the `Camera$Fov` direction); `LightManager.Builder` resolved to `undefined`; and `createAsset`/`createIblFromKtx1`/`createSkyFromKtx1` were handed a raw `ArrayBuffer` instead of a `Uint8Array` (embind `BindingError`). External Filament types are now resolved lazily through the runtime `Filament` global with `unsafeCast`, the `Camera$Fov`/`LightManager$Type` enums and `Uint8Array` views are passed explicitly, and `SceneView.create()` gained an `onError` hook so `createViewer()` rejects on failure instead of hanging.

- **Web: real in-browser smoke test for the compiled Kotlin/JS bundle (#2410).** `samples/web-demo/tests/kotlin-bundle.spec.ts` loads the production `sceneview-web.js` bundle next to a version-matched `filament.js`/`.wasm` and asserts `createViewer().then(...)` resolves and renders non-blank — the gap that let the init crash above ship invisibly (the Karma `jsTest` suite stubs Filament). Built + staged + run by `.claude/scripts/web-bundle-smoke.sh`, wired as a blocking step in the `web-desktop` CI job.
- Unit-tested the #2331 iOS camera diff-guard and the #2332 web render-gate against their **production** code paths (same rigor as #2313). Extracted `AppliedCameraState` out of the `private` `SceneViewRepresentation` to a top-level `internal` type so `approximatelyMatches` is directly testable, and added `AppliedCameraStateTests` (identical-state match, per-scalar/target re-apply above `eps`, sub-`eps` still-matches, mode change, nil ↔ non-nil `firstPersonEye`). Added a web `OrbitCameraController` idle test under the *shipped* default (`enableDamping = true`) asserting a settled camera reports not-moved so the render gate idles. Documented `OrbitCameraController.update()`'s `Boolean` moved-signal in `llms.txt`. (#2412)

### Docs

- **CI now guards the AI-facing docs prose against stale demo-class / demo-id references ([#2316](https://github.com/sceneview/sceneview/issues/2316)).** When a demo is deleted or merged (the #2239 consolidation merged ~20 Android demos), only the collator-generated demos block in `llms.txt` is regenerated — hand-written prose outside the markers keeps naming the deleted Kotlin class (`AnimationDemo`, `MultiModelDemo`, `PhysicsDemo`) or the retired deep-link id, teaching an AI to reference a file/id that no longer exists. A new `.claude/scripts/check-demo-class-refs.sh` greps the AI-facing surfaces (`llms.txt` outside the collator markers, `docs/docs/recipes/*`, `samples/recipes/*`, the Android + web agent skills) for `*Demo` class tokens that exist on no platform, dead `*Demo.{kt,swift,ts}` source links, and retired ids in `sceneview://demo/<id>` deep-link form. It is precise by design — it does **not** flag the iOS demo app's legitimately-separate `*Demo.swift` files that still exist, nor a retired id used as a plain prose phrase (e.g. "the gesture-editing API") — and is wired into `ci.yml` → `repo-hygiene` as an **advisory** (non-blocking) step alongside `check-doc-drift.sh`, self-tested first by `test-check-demo-class-refs.sh`. The two currently-known stale Picker-pattern references (`AnimationDemo` / `PhysicsDemo` → `AnimationPhysicsDemo` in `llms.txt` and `docs/docs/recipes/demo-settings-sheet.md`) are corrected so the guard runs clean.
- **Documented the new `HitResultNode.refreshIntervalMs` hit-test throttle in `llms.txt` ([#2328](https://github.com/sceneview/sceneview/issues/2328)).** #2328 added an opt-in `refreshIntervalMs` rate-limit to `HitResultNode` (mirroring `PointCloudNode`/`DepthMeshNode`); the AI-first reference now documents it so an assistant can generate code that uses the throttle, closing the doc-coverage gap a review-fanout flagged.
- **iOS docs: fixed `BillboardNode` / `ImageNode` signatures that did not exist in the Swift source.** The iOS cheatsheets and the cross-platform tables in `llms.txt` documented `BillboardNode(named:width:height:)`, `BillboardNode(text:fontSize:color:)`, `ImageNode(named:size:)` and an `ImageNode.billboard()` helper — none of which exist — so an AI reading the docs emitted non-compiling Swift. Corrected to the real API: `BillboardNode(child:)` / `BillboardNode.text(_:fontSize:color:)` and `ImageNode.load("img.png")`, across `docs/docs/cheatsheet-ios.md`, `agents/sceneview-ios/references/{cheatsheet,recipes}.md`, `llms.txt` and `website-static/.well-known/llms.txt`.

## v4.17.0 — Performance & correctness: the hot-path audit (2026-05-31)

A performance-and-correctness release built around the **#2263 hot-path audit** — a
5-surface sweep (Android 3D, AR, KMP core, Apple, Web) that found and fixed the
per-frame allocation / matrix-decomposition / JNI patterns that quietly burned CPU
and GC at 60–120 Hz. Highlights:

- **The #2187 transform drift is now fully fixed.** The original fix cached the TRS
  getters but the per-component setters (`node.quaternion = …`) still round-tripped
  through matrix decomposition and drifted scale; a new Filament-Engine-backed
  regression harness surfaced it and it's closed (component setters no longer
  re-decompose; the world-space TRS getters are cached too).
- **Per-frame work cut across the board:** world-space TRS cache, cached Filament
  instance handles, `Mat4.copyColumnsInto` (zero-alloc matrix upload), a
  pre-decomposed `slerp` overload, collision `Ray` by-ref, camera-manipulator
  memoization, batched ARCore updated-set lookups, web `requestAnimationFrame`
  allocation elimination, and SwiftUI auto-rotate off `@State`.
- **Correctness fixes:** AR `PoseNode` pose write, the iOS entity-drag delta bug,
  and a new Engine-backed test tier (`NodeWorldTransformDriftTest` /
  `NodeLocalTransformDriftTest` / `NodeSmoothFollowTest`) that pins the transform
  caches against regression.
- **Docs:** a hot-path / allocation-free API guidance page so generated code avoids
  the whole class of bug.

No breaking changes; new public APIs are additive (`Mat4.copyColumnsInto`, the TRS
`slerp` overload, `Pose.toTransform(out)`).

### Changed

- **Samples catalog: unified Camera & Gestures demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `camera-controls` and `gesture-editing` demos consolidated into a single `camera-gestures` entry with a segmented-button toggle between Camera Modes (orbit / free-flight / map manipulator + distance slider) and Node Gestures (per-node drag / twist / pinch with editability locks, scale sensitivity, and live transform readout) modes. Existing `sceneview://demo/camera-controls` and `sceneview://demo/gesture-editing` deep links keep working via `DEMO_ID_ALIASES`.
- **Samples catalog: unified Custom Geometry demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `custom-mesh` and `shape` demos consolidated into a single `custom-geometry` entry with a segmented-button toggle between Custom Mesh (composite primitives) and Shape Extrude (2D polygon → 3D mesh) modes. Existing `sceneview://demo/custom-mesh` and `sceneview://demo/shape` deep links keep working via `DEMO_ID_ALIASES`. Batch 1 of the 60 → 23 catalog regrouping (iOS mirror follows).
- **Samples catalog: unified Picking & Collision demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `collision` and `view-node` demos consolidated into a single `picking-collision` entry with a segmented-button toggle between Ray Hit-Test (tap-driven highlight) and View Node (Compose UI on a textured quad) modes. Existing `sceneview://demo/collision` and `sceneview://demo/view-node` deep links keep working via `DEMO_ID_ALIASES`.
- **Samples catalog: unified 2D in 3D demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `text`, `image`, `video`, and `billboard` demos consolidated into a single `two-d-in-three-d` entry with a segmented-button toggle between Text (`TextNode` labels), Image (`ImageNode` photo gallery), Video (`VideoNode` streaming MP4 with surface variants + cinematic camera), and Billboard (`BillboardNode` vs fixed `ImageNode`) modes. Existing `sceneview://demo/text|image|video|billboard` deep links keep working via `DEMO_ID_ALIASES`.
- **Samples catalog: unified Lighting Lab demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `dynamic-sky`, `environment`, `reflection-probes`, and `post-processing` demos consolidated into a single `lighting-lab` entry with a segmented-button toggle between Sky (`DynamicSkyNode` time-of-day sun), Environment (HDR IBL switching), Reflections (`ReflectionProbeNode` local IBL zone), and Post-FX (SSAO / MSAA / FXAA / dithering) modes. Existing `sceneview://demo/dynamic-sky|environment|reflection-probes|post-processing` deep links keep working via `DEMO_ID_ALIASES`.
- **Samples catalog: unified Animation & Physics demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `animation` and `physics` demos consolidated into a single `animation-physics` entry with a segmented-button toggle between Animation (skeletal/keyframe playback with a model carousel, cinematic camera shots, and play/pause/speed/loop controls) and Physics (`PhysicsNode` rigid-body simulation dropping streamed crash-test bodies or bundled spheres). Existing `sceneview://demo/animation|physics` deep links keep working via `DEMO_ID_ALIASES`.
- **Samples catalog: unified Materials demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `texture-streaming` and `occlusion-material` demos consolidated into the existing `materials` entry with a segmented-button toggle between PBR Materials (`KHR_materials_*` extension showcase), Streaming (runtime texture / material swap on a loaded model), and Occlusion (invisible depth-writing surface) modes. The `materials` id stays a live registered demo; existing `sceneview://demo/texture-streaming|occlusion-material` deep links keep working as aliases via `DEMO_ID_ALIASES`.
- **Samples catalog: unified Models demo ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** The retired `multi-model` and `scene-gallery` demos consolidated into the existing flagship `model-viewer` entry with a segmented-button toggle between Single Model (bundled hero viewer with optional Sketchfab "Surprise me" stream), Multi-Model (themed park scene from 4 streamed assets with visibility chips + spin toggle), and Gallery (chip-picked themed Sketchfab models) modes. The `model-viewer` id stays a live registered demo; existing `sceneview://demo/multi-model|scene-gallery` deep links keep working as aliases via `DEMO_ID_ALIASES`.
- **Perf: smooth-transform animation hot path ([#2265](https://github.com/sceneview/sceneview/issues/2265), part of [#2263](https://github.com/sceneview/sceneview/issues/2263)).** `NodeAnimationDelegate.onFrame` now reads `node.transform` once per frame instead of three times (eliminating two Filament JNI round-trips per animating node per frame) and feeds the interpolation the node's #2187-cached `position`/`quaternion`/`scale` via a new pre-decomposed TRS-tuple `slerp(startPosition, startQuaternion, startScale, endPosition, endQuaternion, endScale, …)` overload in `sceneview-core` — halving the per-call matrix decompositions from six to three. Behaviour and trajectories are unchanged; the original `slerp(Transform, Transform, …)` overload is retained and now delegates to the tuple form.
- **Cache Filament component instance handles instead of re-querying them on every access (#2269, #2285, #2287, part of #2263).** A `RenderableManager` / `LightManager` / `TransformManager` instance handle is stable for the lifetime of the component on an entity, so it no longer pays a `getInstance` JNI thunk on every read. Following the lazy-once pattern PR #2280 introduced for `Node.transformInstance`, `RenderableNode.renderableInstance` and `LightNode.lightInstance` now cache their handle on first use (skinning / bone-matrix / morph-weight / AABB / culling / priority reads, and reactive light setups that re-apply on every recomposition), and the AR plane visualizers (`PlaneVisualizer`, `PlaneVisualizerV2`) cache their plane entity's transform instance instead of looking it up per update. A `0` (not-yet-built) result is never frozen — it re-looks-up on the next access — so behaviour is identical; this is a pure hot-path allocation/JNI reduction with no public API change.
- **Batch per-frame ARCore updated-set lookups ([#2270](https://github.com/sceneview/sceneview/issues/2270), part of [#2263](https://github.com/sceneview/sceneview/issues/2263)).** The AR frame driver now builds the `getUpdatedTrackables` / `updatedAnchors` membership sets **once per frame** and shares them with every node, instead of each `TrackableNode` / `AnchorNode` calling back into ARCore independently (1 JNI thunk + a fresh JNI-allocated `List` + an O(n) linear `contains()` *per node* — O(N×M) work and N allocations every frame). Each node now does an O(1) `HashSet.contains()` lookup, collapsing the cost to O(N+M). `rememberDetectedPlanes` likewise maintains its tracked-plane set incrementally from `frame.getUpdatedPlanes()` (an ARCore delta) plus a mutable cache rather than recomputing `session.getAllTrackables(Plane).filter { … }.toSet()` every Compose frame. Pure performance refactor — identical callbacks fire for the same trackables/anchors/planes, and the public `update(session, frame)` signatures are unchanged.
- **`ModelNode` no longer re-scans static renderables' bounding boxes every frame ([#2273](https://github.com/sceneview/sceneview/issues/2273), part of [#2263](https://github.com/sceneview/sceneview/issues/2263)).** `sanitizeEmptyBoundingBoxes()` runs from `onFrame` every frame to disable culling/shadows on empty-AABB renderables (and re-enable them once valid), which previously meant a `getInstance` + `getAxisAlignedBoundingBox` JNI call plus a `FloatArray(3)` allocation per renderable on every frame forever — ~7 200 JNI thunks/s for a 20-renderable model at 120 Hz. A renderable is now latched and skipped on subsequent frames **only once it is observed valid AND its AABB cannot change at runtime** — the model has no skins (`modelInstance.skinCount == 0`) and the renderable has no morph targets (`getMorphTargetCount == 0`). Skinned / morph-target renderables are never latched and keep the full per-frame valid↔empty check, so the runtime `valid→empty` collapse (degenerate bone pose, zeroed morph weights) that would otherwise crash Filament is still caught. Once every renderable of a fully-loaded static model is latched, the method early-exits with zero JNI work. Pure performance — identical visible behaviour, no API change.

### Fixed

- **AR plane renderer no longer shows an opaque white blob over bright scenes (#2224).** The plane grid material used `blending: transparent` (premultiplied alpha) but emitted a straight, non-premultiplied `color`, so it composited as `color + (1-alpha)*background` — the grid colour was added at full strength and the alpha cap only controlled background bleed-through. Over a bright camera feed this read as a near-opaque white blob regardless of the cap. The fragment now premultiplies `baseColor.rgb *= baseColor.a`, so the plane composites as the intended `lerp(background, color, alpha)` — a genuine ~20 % translucent grid. Diagnosed and validated with a new non-AR `plane-grid-preview` shader-QA tool that renders the exact `plane_renderer.filamat` on a static surface (debug builds only).
- **`Node` world-space TRS cache completes the #2187 fix ([#2264](https://github.com/sceneview/sceneview/issues/2264)).** `worldPosition` / `worldQuaternion` / `worldScale` / `worldRotation` getters no longer re-decompose the Filament 4×4 matrix on every read. Cached in `_worldTransform` / `_worldPosition` / `_worldQuaternion` / `_worldScale` / `_worldRotation` fields, invalidated through `onWorldTransformChanged()` propagation. `worldRotation` extracts its Euler angles directly from the matrix (not via the quaternion) to stay bit-equivalent across gimbal-lock boundaries. `ModelNode` additionally invalidates its glTF sub-nodes' world cache on move and while animations play (their Filament transforms are written outside the Node setters). Also fixed a latent bug where re-parenting a node did not invalidate the world transform of the child and its descendants.
- **`Node.transformInstance` cached after first lookup ([#2269](https://github.com/sceneview/sceneview/issues/2269)).** The TransformManager instance handle is stable for the lifetime of an entity; cache it so transform getters/setters stop paying a JNI thunk per access (eliminates ~30 000 JNI calls/s on a 100-node animated scene at 120 Hz).
- **`NodeAnimationDelegate.onFrame` reads `node.transform` once per frame ([#2265](https://github.com/sceneview/sceneview/issues/2265)).** The old code fired the matrix round-trip three times per smooth-animated node per frame; the new code reads the current transform into a local val and reuses it. (Partial fix; the `slerp(Transform, Transform)` TRS-overload migration is tracked as a follow-up.)
- **Perf:** `PoseNode.pose` (and `ARCameraNode.pose`) now write the world translation + rotation
  directly from the ARCore `Pose` components instead of routing through
  `worldTransform(pose.transform)`, which allocated a fresh `FloatArray(16)` + `Transform` and ran
  a matrix decompose/recompose on every anchor / plane / face / image / camera pose update, every
  frame. Added an allocation-free `Pose.toTransform(out: FloatArray)` scratch variant for callers
  that still need the matrix form. ([#2266](https://github.com/sceneview/sceneview/issues/2266),
  umbrella [#2263](https://github.com/sceneview/sceneview/issues/2263))
- **World ↔ local quaternion conversions no longer run a Mat4 polar decomposition on every call ([#2267](https://github.com/sceneview/sceneview/issues/2267)).** Setting `Node.worldQuaternion` / `Node.worldRotation` (and the underlying `getLocalQuaternion` / `getWorldQuaternion`) used to decompose a 4×4 matrix into its rotation component every time. A rotation-only conversion never needs the matrix: `getWorldQuaternion` is `parentWorldQuaternion * localQuaternion`, and for an **unscaled** node `getLocalQuaternion` is `inverse(parentWorldQuaternion) * worldQuaternion`. New direct-quaternion overloads `worldToLocalQuaternion` / `localToWorldQuaternion` (and the Euler variants) skip the decomposition, reusing the world-space quaternion cache from [#2264](https://github.com/sceneview/sceneview/issues/2264). **Behavior-preserving:** `getLocalQuaternion` keeps the exact legacy matrix path for **scaled** nodes — `inverse(M).toQuaternion()` and `inverse(M.toQuaternion())` diverge once `M` carries scale (#2294 review), so the fast path is gated on an unscaled world transform. The legacy `Transform`-taking overloads are kept for source compatibility but `@Deprecated`. Part of the hot-path allocation audit ([#2263](https://github.com/sceneview/sceneview/issues/2263)).
- **Web `refreshContentCentering` no longer copies + writes back a mat4 per model per frame ([#2268](https://github.com/sceneview/sceneview/issues/2268)).** The web auto-center / framing pass ran up to 10 startup frames (re-armed on every `loadModel`/`addGeometry`) and, for each loaded model, crossed the WASM↔JS boundary to re-fetch the `TransformManager`, read 16 boxed `number`s into a fresh `[]`, then allocated a *second* 16-element array just to add the centring offset. It now caches the `TransformManager` once at construction, snapshots each model's base transform into a primitive `DoubleArray(16)` (un-boxed reads, allocated once per model), composes the offset into a single reusable scratch array mutated in place (zero allocation after warmup), and coalesces every `setTransform` into one GPU upload via `openLocalTransformTransaction()`/`commitLocalTransformTransaction()`. The final framing transform is unchanged. Web-port cousin of [#2187](https://github.com/sceneview/sceneview/issues/2187); part of the hot-path allocation audit ([#2263](https://github.com/sceneview/sceneview/issues/2263)).
- **Hot-path allocation: `Mat4`/`Mat3.copyColumnsInto` buffer-fill overloads ([#2271](https://github.com/sceneview/sceneview/issues/2271)).** `Mat4.toColumnsFloatArray()` allocated a fresh `FloatArray(16)` on every call, fired thousands of times per second on an animated scene (per smooth-transform tick, gesture, and camera-manipulator update). New allocation-free `Mat4.copyColumnsInto(out, offset = 0)` / `Mat3.copyColumnsInto(out, offset = 0)` overloads write directly into a caller-supplied scratch buffer. The hottest callers — `TransformManager.setTransform` and the `Camera.modelTransform` setter — now reuse a main-thread scratch buffer (safe: Filament JNI is main-thread-only and copies the array into native memory synchronously). The original `toColumnsFloatArray()` overloads are unchanged for cold callers. Part of the hot-path performance audit ([#2263](https://github.com/sceneview/sceneview/issues/2263)).
- **`Manipulator.transform` no longer allocates ~10 objects per frame ([#2272](https://github.com/sceneview/sceneview/issues/2272)).** The camera-manipulator transform getter, called once every frame from the render loop, used to allocate an `Array<FloatArray>`, three `FloatArray(3)`, and several `Float3`/`Mat4`/`Float4` objects on every invocation. It now reads the look-at vectors into reused scratch buffers and memoizes the resulting `Transform`, returning the cached matrix unchanged when the camera has not moved (the common no-input case). Part of the hot-path allocation audit ([#2263](https://github.com/sceneview/sceneview/issues/2263)).
- **Web:** Eliminated per-`requestAnimationFrame` allocation sources in the web render loop that caused a GC sawtooth (worst on iOS Safari). `OrbitCameraController.update()`, the `sceneview.js` orbit/freelook/map `lookAt` branches, the billboard transform update, and the WebXR per-view viewport now reuse preallocated scratch arrays mutated in place instead of allocating fresh arrays every frame. The billboard path also caches each entity's `TransformManager` instance and batches its updates in a single local-transform transaction. Behaviour is identical — only the allocations are removed. (#2274, umbrella #2263)
- **Eliminated per-triangle `Vector3` allocations in ray-vs-mesh collision ([#2276](https://github.com/sceneview/sceneview/issues/2276), umbrella [#2263](https://github.com/sceneview/sceneview/issues/2263)).** `Ray.getOrigin()` / `getDirection()` each returned a defensive `Vector3` copy, so a ray-vs-1000-triangle mesh test allocated ~3 000 throwaway vectors. The collision math (`MeshCollider`, `Box`, `Sphere`, `Plane`, `AABB`, `Capsule`) now reads the ray's backing vectors through new package-internal `Ray.originRef()` / `directionRef()` accessors — zero copies on the hot path. The public `getOrigin()` / `getDirection()` keep their defensive-copy contract, so there is no API change.
- **SwiftUI auto-rotate no longer drives a full body re-eval at 60 Hz ([#2277](https://github.com/sceneview/sceneview/issues/2277)).** On Apple platforms (`SceneViewSwift`), the auto-rotate `.task` loop mutated the `@State` `CameraControls` value every ~16 ms, invalidating the entire SwiftUI `body` and re-running `RealityView.update:` (applyCamera + both light-slot diffs + content-centering + skybox diff) every frame for the lifetime of any auto-rotating scene. The orbit/camera state now lives in a reference-type box, so mutating it never invalidates the body; the camera transform is pushed straight onto the camera entity from the mutating sites (the auto-rotate task and the drag / pinch gesture handlers). No public API change — `CameraControls` and all view modifiers are unchanged.
- **Light-slot refresh removes the previous light by cached reference instead of tree-walking ([#2278](https://github.com/sceneview/sceneview/issues/2278)).** `SceneView`'s `refreshLightSlot` (and the visionOS `refreshImmersiveSkybox`) used `entities.root.children.first { … }` to locate the entity to remove on a slot change. It now caches the provisioned entity reference on `AppliedCache` and removes it directly — mirroring `ARSceneView`'s `coordinator.main/fillLightAnchor` pattern — keeping the path O(1) regardless of scene size and guarding against a future equality regression turning a per-frame no-op into an O(n) walk.
- **SceneViewSwift: dragging an entity registered with `Entity.onDrag { }` / `NodeGesture.onDrag` no longer flies off-screen (#2283).** `entityDragGesture` dispatched SwiftUI's *cumulative* drag translation on every `onChanged` tick, but the documented `NodeGesture.onDrag` contract promises a per-frame *delta* ("translation delta in world space"). So the natural `entity.position += delta` handler double-integrated the offset and the entity accelerated away from the pointer. The gesture now tracks the previous cumulative translation per entity (keyed by `ObjectIdentifier`, in a reference box so per-frame ticks don't churn the SwiftUI body) and dispatches `current − previous`, resetting the baseline on gesture end — the handler now tracks the pointer 1:1. No public API change; the implementation now matches the documented delta contract.
- **Review-nit follow-ups from the #2263 perf PRs (#2303, part of #2263).** Three non-behavioural polish fixes surfaced during the independent batch-review: (1) `ModelNode.onWorldTransformChanged`'s KDoc rationale was wrong — the glTF sub-nodes *are* already parented through `childNodes`, so the base-class propagation reaches them; the override is now documented as redundant-but-harmless (kept for clarity/safety) rather than claiming it's required. (2) `CameraManipulator`'s memoization dirty-check now carries a comment explaining that its exact `==` float comparison is intentional (a false miss only costs one extra `lookAt` recompute; an epsilon would wrongly skip a genuine sub-epsilon move), and the file regained its trailing newline. (3) `TransformManager.setTransform` gained a debug-only main-thread assert so an off-main-thread caller fails loudly instead of silently corrupting the shared `transformScratch` buffer; the guard is gated on `BuildConfig.DEBUG` and is dead-code-eliminated (zero-cost) in release.
- **`ModelNode` now evicts a renderable from its sanitize-once latch when its geometry or bounding box is explicitly mutated ([#2311](https://github.com/sceneview/sceneview/issues/2311), part of [#2263](https://github.com/sceneview/sceneview/issues/2263)).** The `permanentlyValidEntities` latch (#2273 / #2310) skips re-scanning a static renderable's AABB once it is observed valid. The one out-of-band path that could re-introduce an empty AABB the latch never re-scanned — explicitly calling `RenderableComponent.setGeometry` / `setGeometryAt` / the `axisAlignedBoundingBox` setter with a degenerate box on a *latched* glTF child renderable — could lead to a Filament "AABB can't be empty" crash. `ModelNode.RenderableNode` now overrides those three mutators to evict the entity from the latch, so the next `sanitizeEmptyBoundingBoxes()` pass re-scans it and re-detects the empty AABB before Filament can crash on it. Internal-only — no public API change.
- **Completed the #2187 transform-drift fix — per-component setters no longer re-decompose (#2335).** `node.quaternion = …` (and `position` / `scale` / `rotation`) used to route through the public `transform =` setter, which re-decomposes the composed 4×4 matrix back into TRS on every write. Driving a single component at 60–120 Hz fed the matrix column-length (scale) and polar-decomposition (quaternion) imprecision back into the caches, so local scale crept off 1.0 (~1e-4 over 10 000 frames) — the original #2187 mesh-warp, reintroduced through the setter path. The #2187/#2217 fix had only corrected the getters. The component setters now push the composed matrix to Filament via a new private `applyCachedTransform()` WITHOUT reading it back, so they never round-trip through decomposition; local scale now stays within 1e-6 of 1.0 over 10 000 frames. The public `transform =` setter still decomposes (its input is an arbitrary external matrix that genuinely needs TRS extraction). The public API is unchanged. Covered by a new Engine-backed instrumented `NodeLocalTransformDriftTest` (the pure-math `NodeTransformDriftTest` could not catch a setter that round-trips through the real Filament matrix).
- Fixed `DemoHostActivity` (debug deep-link test harness) crashing with `Unknown demo id` when launched for `double-pendulum`, `spatial-audio`, or `placement-scene` — these three demos are registered in the catalog but were missing from the host's `when()` routing. They now route to their composables. Real users were unaffected (the normal `MainActivity` deep-link channel always handled them); the crash only hit the instrumentation/manual-QA `--es demo_id` path. Found during the #2239 interactive QA sweep.

### Tests

- Added `DeepLinkRouterTest` coverage for the six #2239 Batch 1 deep-link aliases (`custom-mesh`/`shape` → `custom-geometry`, `collision`/`view-node` → `picking-collision`, `camera-controls`/`gesture-editing` → `camera-gestures`), asserting both `validate` and `parse` resolve each retired `sceneview://demo/<id>` link to its consolidated demo.
- Removed the orphaned `shapeDemo_default_state` render-screenshot test left behind by the #2239 Batch 1 `custom-geometry` consolidation: it launched the retired `shape` deep-link slug (aliased to `custom-geometry`) against the deleted `shape_default` golden, so it only ever silently `assumeTrue`-skipped. Custom-geometry render coverage is provided by `customGeometryDemo_default_state`.
- Fixed a stale `llms.txt` reference naming the retired `gesture-editing` demo as the canonical gesture example; it now points at the `camera-gestures` demo's Node Gestures tab.
- Added `NodeWorldTransformDriftTest`, an instrumented (`androidTest`) Filament-Engine-backed regression test for the world-space TRS cache (#2264, the world half of the #2187 transform-drift fix). It runs on the emulator with a real `Engine`/`TransformManager` — the world cache reads `TransformManager.getWorldTransform()` (a JNI call) so it cannot be covered by the pure-math `NodeTransformDriftTest`. Covers: world-scale stability over 10 000 spin frames, and world-cache invalidation completeness after a local transform write, a parent move, **reparenting** (the latent bug the #2280 `parentInstance` setter side-fix closed), and adding a child to an already-moved parent — plus a combined never-stale guard. Wired into `render-tests.yml` (which already runs `:sceneview:connectedDebugAndroidTest`). Refs #2284 #2264 #2280 #2263.
- Added an Engine-backed instrumented test (`NodeSmoothFollowTest`) that pins the AR-reticle **smooth-follow glide** behavior preserved by #2296 (#2266). It drives the exact `Node.worldTransform(position =, quaternion =, smooth = true)` path that `HitResultNode`/`DepthHitResultNode` route through, on a real Filament `Engine`, and asserts the node eases toward a far target (strictly between start and target on early frames, converging after enough frames) — never snapping — plus a non-smooth negative control that snaps immediately. Closes the last verification gap from the #2263 hot-path audit; ARCore can't run on the local emulator, but the glide is a `Node`/slerp behavior, so it is deterministically testable without ARCore.

### Docs

- **Hot-path / allocation-free API guidance ([#2263](https://github.com/sceneview/sceneview/issues/2263)).** New "Hot Paths & Allocation-Free APIs" section in the [Performance guide](https://github.com/sceneview/sceneview/blob/main/docs/docs/performance.md) documents the per-frame allocation/decomposition class of bug the cross-platform hot-path audit fixed: never call a decomposing or allocating getter inside a render-rate loop. Includes a per-platform "avoid → use instead" cheat sheet (Android `Mat4.copyColumnsInto` / TRS-tuple `slerp` / set whole `transform`; AR `Pose.toTransform(scratch)`; KMP `Ray` reuse; Web scratch-array reuse; Apple no per-frame `@State`) and the "why" (float drift #2187, GC sawtooth on Safari, JNI thunks). The same guidance is mirrored into the three AI agent skills (`agents/sceneview*/SKILL.md`) and `llms.txt` so AI-generated code stops reintroducing it.
- Updated the demo catalog count across the docs + website (samples.md, llms-full.txt, try.md, website index) from the stale pre-#2239 "59 demos (30 non-AR + 29 AR)" to the current **47 demos (17 non-AR + 30 AR)** after the demo consolidation.
- Fixed a broken iOS-samples doc link (`samples-ios.md`): `ImageDemo.swift` → `ImagePlaneDemo.swift` (the actual file name).
- Extended the `camera-gestures` interaction test to also exercise the Camera Modes tab (the absorbed `camera-controls` half), not just Node Gestures.
- Doc↔API drift audit (manual run): corrected the stale "version 4.15.0" Maven-artifacts label in `llms.txt` to match the current `4.16.10` install snippets, and fixed two recipes (`samples/recipes/procedural-geometry.md`, `samples/recipes/physics.md`) plus `samples/README.md` that called `rememberMaterialInstance(materialLoader)` with no `color` argument — no such single-argument overload exists (every `rememberMaterialInstance`/`createColorInstance` overload requires a colour), so the snippets did not compile. Replaced with the SDK-level `remember(materialLoader) { materialLoader.createColorInstance(...) }` pattern. (The `rememberMaterialInstance` helper itself lives in `samples/common`, not the published SDK.)
- **Documentation now stays in sync with the public API automatically, via a two-tier guard.** A new `check-doc-drift.sh` runs per-PR (advisory, in `ci.yml` → `repo-hygiene`) and WARNs when a change touches a public-API surface (`sceneview`/`arsceneview`/`sceneview-core`/`SceneViewSwift`/`sceneview-web`) without updating the relevant docs (`llms.txt`, KDoc, `docs/docs/*`, `samples/recipes/*`) — non-blocking, since it is a heuristic. A complementary weekly `doc-audit.yml` workflow (Mondays) has an Opus agent reason over the whole repo and open a **draft** PR with concrete doc patches (or a de-duplicated tracking issue), so a wrong prose patch can never land silently. The detector is self-tested by `test-check-doc-drift.sh`.
- Clarified the `rememberMaterialInstance` note in `llms.txt`, `gpt/knowledge-api.md` and the website `.well-known/llms.txt`: the flat "there is NO `rememberMaterialInstance` function" was imprecise (and contradicted the demos, which use a sample-only helper of that name in `samples/common`). Reworded to say there is none in the *published SDK* and to copy the `materialLoader.createColorInstance(...)` pattern — so an AI reading both the docs and the demos isn't confused.

## v4.16.10 — Lint & security patch (2026-05-27)

### Fixed

- **Lint**: declare `VIBRATE` permission in `sceneview` library manifest so
  `HapticEngine`'s `Vibrator.vibrate()` calls no longer generate
  `MissingPermission` lint errors in the library and its consumers.
- **Security**: patch CVE-2026-8723 (medium) — pin `qs` transitive dependency
  to `>=6.15.2` in `mcp/packages/rerun`, `mcp/packages/interior`,
  `mcp/packages/gaming`, and `mcp-gateway` via npm `overrides`.

## v4.16.9 — Sketchfab viewer polish + code quality (2026-05-27)

### Fixed

- **Feedback flow** — the confirmation Snackbar after submitting a feedback report
  now stays visible for the full `SnackbarDuration.Long` (10 s) instead of the
  default `Short` (4 s), giving users enough time to read the "Feedback sent!"
  message before it disappears (#2230).
- **Sketchfab viewer** — the loading sheet now shows a determinate
  `LinearProgressIndicator` + `X.X / Y.Y MB` counter while a GLB is
  streaming from Sketchfab, replacing the silent indeterminate spinner
  that gave no feedback during 20+ second downloads of heavy models.
  An advisory label ("Heavy model — may take a moment") appears for
  models ≥ 500k polys (#2232).
- **Sketchfab viewer** — models no longer float on a blank background: a directional
  light + invisible `plane_renderer_shadow.filamat` plane at the model's ground level
  cast a soft contact shadow beneath every Sketchfab model (#2235).

## v4.16.8 — Google Play 16 KB page-size + plane renderer polish (2026-05-27)

### Fixed

- Fix Play Store upload rejection: enable 16 KB page-size alignment for native libraries in the demo AAB (`packaging.jniLibs.pageAlignSharedLibraries = true`, required by Google Play since January 2026 for apps targeting Android 15+).
- **Plane renderer white-blob: tighter alpha cap** ([#2224](https://github.com/sceneview/sceneview/issues/2224) — second iteration). v4.16.4 dropped the alpha hard-cap from saturation to 0.45 but on-device QA in sunny outdoor scenes still read as an opaque white blob (45 % cool-white tint on already-light camera input ≈ 80 %+ perceived white). v4.16.5 tightens to a **0.20 alpha cap** and caps `line` itself at **0.4** in `gridLine()` so the saturation is bounded at the source, not just clamped post-hoc. Grid coefficients also reduced (0.4 / 0.3 instead of 0.6 / 0.5). Industry baseline (Apple ARKit / Wayfair / ARCore Depth Lab reticle) ships plane viz at 20-30 % alpha unlit — this now matches.
- **Library 16 KB page-size alignment** ([#2226](https://github.com/sceneview/sceneview/issues/2226)): add `experimentalProperties["android.nativeLibraryAlignmentPageSize"] = "16k"` to `sceneview` and `arsceneview` library modules so Filament's prebuilt `.so` files have ELF PT_LOAD segments aligned to 16 KB at pack time. Required for consumers' APKs to pass Google Play's new enforcement (Android 15+, enforced since January 2026). Consumers must also add this property to their own app-level `build.gradle`.

## v4.16.6 — 2026-05-27

### Fixed

- **macOS App Store**: fixed all compile errors blocking macOS archive since #1049
  (Xcode 16.2+). Guarded `navigationBarTitleDisplayMode`, `CADisplayLink`,
  `secondarySystemBackground`, and AR demo scene destinations in `#if os(iOS)`
  blocks. Closes [#1794](https://github.com/sceneview/sceneview/issues/1794).

## v4.16.5 — 2026-05-27

### Fixed

- **Fix opaque white plane bug** ([#2224](https://github.com/sceneview/sceneview/issues/2224)). At oblique camera angles (typical for AR floor planes) the V1 procedural grid shader's `fwidth(uv)` saturated, collapsing `gridLine()` to ~1.0 across the whole plane and turning the detected ground into an opaque white blob. Three-lever fix: cool-white tint instead of pure white (`MATERIAL_COLOR = Color(0.85, 0.90, 1.0)`), grid alpha hard-capped at 0.45, denser cells via `BASE_UV_SCALE = 4.0` (was 8.0) so `fwidth(uv)` stays in a stable range. Detected planes now read as a subtle translucent grid overlay, as intended by #1616.
- **iOS demo**: fixed App Store archive crash — added `.gimbal` case to three exhaustive
  switch statements in `CameraControlsDemo` that were broken when `CameraControlMode.gimbal`
  was introduced in #1049 (Xcode 16.2+ treats missing enum cases as compile errors).

## v4.16.3 — 2026-05-27

### Fixed

- Fix iOS/macOS archive failure: `CameraControls.gimbal` is only available in the iOS 18.2+ / macOS 15.2+ SDK (Xcode 16.2+). Guard it at compile time — `.gimbal` mode falls back to the orbit gesture path on SDKs older than 16.2. Explicit `RealityKit.CameraControls.*` qualification added to all four native-mode cases to eliminate the type-inference ambiguity with `SceneViewSwift.CameraControls`.

## v4.16.2 — 2026-05-27

### Added

- **iOS — native camera modes** (`CameraControlMode`): four new iOS-only cases
  (`.none`, `.tilt`, `.dolly`, `.gimbal`) delegate directly to Apple's
  `realityViewCameraControls(_:)` modifier instead of SceneView's custom gesture
  math. The existing cross-platform modes (`.orbit`, `.pan`, `.firstPerson`) are
  unchanged — they keep orbit inertia, auto-rotate, and fit-to-bounds framing.
  Closes #1049 (Phase 2 — exposing the 4 Apple-only modes).

### Changed

- `bridge-ios-compile.yml` is the **first workflow opted into the self-hosted macOS runner** introduced in #2192. Its `runs-on` switched from `macos-15` to `${{ vars.SELF_HOSTED_MACOS_ONLINE == 'true' && 'sceneview-mac' || 'macos-15' }}` — when Thomas's Mac is online the type-check runs on bare metal (faster, no `macos-15` minute spend), otherwise it falls back transparently to the GitHub-hosted runner. Picked as the pilot because of its low trigger frequency (path-gated on `flutter/sceneview_flutter/ios/**` + `SceneViewSwift/**`) — minimal blast radius if the self-hosted leg misbehaves. The PR itself touches the workflow file so the very push that lands this change validates the routing end-to-end.

### Fixed

- Fix macOS archive failure: `CameraControls.gimbal` is iOS-only — guard with `#elseif os(macOS)` and fall back to orbit gesture path on macOS (#2219 follow-up).
- Fix Play Store upload rejection: enable 16 KB page-size alignment for native libraries in the demo AAB (`packaging.jniLibs.pageAlignSharedLibraries = true`, required by Google Play since January 2026 for apps targeting Android 15+).
- Bump MediaPipe Tasks Vision `0.20230731` → `0.10.26`: pre-0.10.26 builds ship 4 KB-aligned ELF `.so` files that Google Play rejects with "Artifact does not support 16KB page size" (enforced January 2026). The same root cause was fixed in v4.15.4 on the release branch only — this backports the fix to `main` so future releases are not affected.
- **Restored V1 as the default plane renderer** ([#2203](https://github.com/sceneview/sceneview/issues/2203)). v4.16.0 briefly shipped V2 (depth-driven PBR mesh + HDR reflection + type-aware shading + scan-in) as the default, but on-device QA on a Pixel 9 showed the V2 visual output not matching the design intent — a washed-out translucent grid sheet instead of the promised HDR reflection + relief. V1 is restored as the default in v4.16.1 while V2 is polished. V2 stays available behind `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V2)` as an experimental opt-in so early adopters can help shape the redesign. See `.claude/plans/v2-references-study.md` + `v2-google-ar-catalog.md` + `v2-non-google-catalog.md` for the comparative research (ARCore Depth Lab, Apple ARKit + RoomPlan, Niantic Lightship, Snap Lens Studio) that informs the next iteration.

## v4.16.0 — 2026-05-26

### Added

- **Plane Renderer V2** — detected ARCore planes now render as a depth-driven PBR mesh
  lit by ARCore's HDR estimate ([#2203](https://github.com/sceneview/sceneview/issues/2203)).
  Floors, ceilings and walls each carry a distinct material identity, a brief scan-in
  animation runs the first time a plane is detected, and the reflection ramps in over
  ~1 s to mask the HDR estimate stabilisation. The legacy flat-polygon renderer remains
  available via `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V1)` for one
  release cycle and is now `@Deprecated`. Includes a new `ar-plane-renderer-v2` demo in
  `samples/android-demo` with a live V1 ↔ V2 toggle so the difference reads instantly.
- Plane Renderer V2 — type-aware shading per `Plane.Type`: a floor, a ceiling and a wall visible at once now read as three distinct surfaces. Floor (`HORIZONTAL_UPWARD_FACING`) renders cool-white with `roughness 0.35`; ceiling (`HORIZONTAL_DOWNWARD_FACING`) renders warm-white with `roughness 0.65`; wall (`VERTICAL`) renders neutral grey with `roughness 0.80`. Same single `Material`, one `MaterialInstance` per plane — no extra Filament objects. ARCore re-classifications mid-tracking re-apply the preset on the next frame; unknown future plane types fall back to the floor preset rather than crashing. Opt in via `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V2)`. PR #4 of #2203.

### Changed

- `setup-self-hosted-runner.sh` v3 — install path moved from `~/Library/Application Support/sceneview-runner/` to `~/sceneview-runner/`. v2 picked the macOS-convention location which contains a space, breaking the runner's step-script invocation (`/bin/bash -e <path>` splits on the space → `No such file or directory`). The pilot `bridge-ios-compile` PR [#2204](https://github.com/sceneview/sceneview/pull/2204) failed in 34 seconds on the `Select Xcode` step because of this exact issue (run id 26418464635). v3 keeps the LaunchAgent bootstrap design unchanged, only relocates the runner files. The installer auto-detects an existing v2 install at the legacy path, de-registers it from GitHub, and unloads its LaunchAgent before installing fresh — old files are left in place for manual `rm -rf`.

- Until the v3 runner is reinstalled, set the repo variable `SELF_HOSTED_MACOS_ONLINE=false` (`gh variable set SELF_HOSTED_MACOS_ONLINE -R sceneview/sceneview --body "false"`) so every opted-in workflow falls back to `macos-15`. Re-running the v3 installer marks it `true` again automatically via the heartbeat.

### Fixed

- **[Android AR]** Fix `DepthMeshNode` never rendering its depth mesh — `lastRebuildTimestampMs` was
  initialised to `Long.MIN_VALUE`, causing the throttle guard (`now - lastRebuildTimestampMs <
  refreshIntervalMs`) to overflow to a large negative number on every frame and always return early.
  Changed to `0L` so the first rebuild fires immediately as designed. (#2186)
- **[Android 3D]** Fix `Node` transform floating-point drift when updating `position`, `quaternion`,
  or `scale` at high frame rates (60–120 Hz) — e.g. `node.quaternion = newQ` in an `onFrame` loop
  (#2187). The root cause was that each individual-property setter decomposed the Filament 4×4
  matrix to read the other two components, feeding float imprecision back on every tick. After
  ~10 000 frames the scale drifted visibly and the mesh warped. Fix: cache pristine TRS backing
  fields (`_position`, `_quaternion`, `_scale`) updated once on every `transform` write; individual
  getters and setters use the caches, eliminating the matrix-decomposition round-trip.
- **`catmullRom()`**: Fix centripetal/chordal parameterisation — the `alpha != 0` path now uses the Barry-Goldman pyramidal recurrence over chord-length knots instead of the uniform matrix formula, so `alpha = 0.5` (centripetal) genuinely avoids cusps and self-intersections near sharp turns. The uniform path (`alpha = 0`) is unchanged.
- **`ModelLoader.createInstance()`**: Annotate with `@MainThread` — Filament's `AssetLoader.createInstance()` is a JNI call that must run on the Filament main thread; the annotation surfaces a warning in the IDE and lint when called from a background coroutine.

## v4.15.4 — 2026-05-26

### Fixed

- **Play Store deploy:** set `inAppUpdatePriority: 3` on every release upload (both `r0adkll/upload-google-play` and the Python promote / fallback paths) so the in-app `UpdateBanner` actually fires when a new release lands. Pre-fix the workflow defaulted to priority 0 ("Google's discretion") and v4.15.2 silently never prompted v4.15.1 users — `AppUpdateManager.appUpdateInfo` returned `UPDATE_NOT_AVAILABLE` for days while Play Store itself indexed the release fine. Priority 3 = "high — surface within ~24h". Crash-fix releases can edit the workflow once to bump to 5 ("immediate"). (#2209)
- **Play Store production deploy unblocked.** v4.15.3 production track 403'd on `:commit` with `PERMISSION_DENIED — Artifact does not support 16KB page size`. Root cause traced via 5 redispatches + 2 diagnostic PRs to `libmediapipe_tasks_vision_jni.so` from MediaPipe `tasks-vision:0.10.14` — its arm64 ELF was 4 KB-aligned (`p_align = 0x1000`). Filament 1.71.4 + ARCore 1.54.0 + Compose were all already 16 KB-aligned. Bumped `mediapipe-tasks-vision` to `0.10.26` (the first release with "All the latest Android packages from Google Maven are now supporting the Android 16kb page size" per [MediaPipe v0.10.26 release notes](https://github.com/google-ai-edge/mediapipe/releases/tag/v0.10.26)). Verified locally: rebuilt `libmediapipe_tasks_vision_jni.so` now reports `p_align = 0x4000`. No API changes between 0.10.14 and 0.10.26 affect SceneView demo usage — `compileReleaseKotlin` clean. (#2214)

## v4.15.3 — 2026-05-26

### Changed

- Self-hosted macOS runner infrastructure (opt-in) — `.claude/scripts/setup-self-hosted-runner.sh` installs `actions/runner`, writes a user LaunchAgent plist directly and `launchctl bootstrap`s it (skipping `actions/runner`'s `svc.sh`, which uses the deprecated `launchctl load` and fails on macOS 11+ with `Input/output error`; see actions/runner issue 1424), plus a second launchd heartbeat that updates the repo variables `SELF_HOSTED_MACOS_ONLINE` / `SELF_HOSTED_MACOS_LAST_SEEN`. Workflows opt in by changing `runs-on: macos-15` to `runs-on: ${{ vars.SELF_HOSTED_MACOS_ONLINE == 'true' && 'sceneview-mac' || 'macos-15' }}` — the expression form supported by GitHub Actions since late-2024 — and fall back transparently to a GitHub-hosted runner when the Mac is asleep / off / the runner service is dead. The plist's `KeepAlive=true` makes the runner survive reboots, sleep/wake, and the runner's own auto-update cycle. Targets the 6 `macos-15` jobs (ios.yml, bridge-ios-compile.yml, rn-ios-compile.yml, app-store.yml × 2, render-tests.yml) plus the NIGHTLY-ONLY iOS device-QA leg (#1601) that is currently skipped on per-push runs due to macOS-hosted cost. No existing workflow is modified by this commit. Inspired by [Zach Rattner's M4 Mac cluster playbook](https://zachrattner.com/projects/m4-mac-cluster).

- Biome v2 linter wired for `mcp/src/**/*.ts` + `mcp/scripts/**/*.js` + `website-static/js/sceneview.js` via a repo-root `biome.json`. Use `cd mcp && npm run biome` (advisory) or `npm run biome:fix` (auto-fix). Excludes generated `dist/`, `mcp/src/generated/`, `__fixtures__/`, vendored `qrcode-*.js`, and Kotlin/JS-emitted `sceneview-web.js`. Not wired to CI for now — baseline reveals 216 errors / 236 warnings to clean up first. Adoption inspired by the same Mac-cluster playbook (Biome replaces ESLint/Prettier at Yembo).

- `@claude` mention bot — `.github/workflows/claude.yml` runs the official `anthropics/claude-code-action@v1` whenever a contributor drops `@claude` in an issue body/title, issue comment, PR review, or PR review comment. Auth via `CLAUDE_CODE_OAUTH_TOKEN` (Claude Max subscription, no per-call API spend). Concurrency keyed per issue/PR so duelling replies are impossible. Setup is one-time: `claude setup-token` + `gh secret set CLAUDE_CODE_OAUTH_TOKEN -b "<token>"`. Open-source contributors benefit too — they don't need an Anthropic account to ask Claude for help on a SceneView issue.

- SceneView statusline (`/.claude/scripts/statusline.sh`, wired via `.claude/settings.json`) — shows branch, `~worktree-slug` marker (so parallel sessions never confuse which checkout they're editing), `VERSION_NAME` from `gradle.properties`, free RAM in GB (useful for the emulator pool — flags when free RAM drops below the 3 GB `EMU_MIN_FREE_RAM_MB` floor), and the active Claude model. No network calls; runs fast.

- CLAUDE.md trimmed 992 → 746 lines by deleting the nested "Previous state" session-state snapshots (lines 529-779 in the old file) — they were already mirrored chronologically in `.claude/handoff.md`. CLAUDE.md now keeps only the **current** state + a stub pointing to `handoff.md` for everything older. Every future session loads ~250 fewer lines of dead session log.

### Fixed

- **iOS App Store deploy:** patch Swift 6 strict-concurrency error in `ARPlaneNodeDemo.swift:97` that broke the v4.15.2 `app-store.yml` archive step. The `private enum AssocKey { static var delegate = 0 }` global (used only as an `objc_setAssociatedObject` key) is now `nonisolated(unsafe) static var delegate: UInt8 = 0` — canonical opt-out for the "address-of-global as key" idiom. Fixes the v4.15.2 iOS deploy red without re-tagging.

## v4.15.2 — iOS demo catalog parity sprint complete + Android crash burn-down (2026-05-26)

A double-headline release. **iOS** closes umbrella [#910](https://github.com/sceneview/sceneview/issues/910):
13 new SwiftUI demos (Augmented Faces, Depth Occlusion, Image Tracking, Plane Node, Point Cloud,
Collision, Debug Overlay, HDR Environment, Gesture Editing, People Occlusion, Body Tracker, Scene
Mesh, Reflection Probes, Shape Extrude, Texture Streaming, Video Texture) plus an append-only
demo-registry pattern (#1872) so future iOS demos can land as a single `*Scene.swift` file with
no `project.pbxproj` merge conflicts. **Android** ships a sweep of 5 user-visible regression
fixes ([#2188](https://github.com/sceneview/sceneview/issues/2188),
[#2191](https://github.com/sceneview/sceneview/issues/2191),
[#2193](https://github.com/sceneview/sceneview/issues/2193),
[#2194](https://github.com/sceneview/sceneview/issues/2194),
[#2195](https://github.com/sceneview/sceneview/issues/2195)) — the in-app feedback crash on
Play Store builds, an empty Sketchfab Explore tab (CloudFront WAF), a 5-second ANR on Sketchfab
preview, and chip-overlap UI papercuts. New cross-platform `SceneMeshNode` ([#1760](https://github.com/sceneview/sceneview/issues/1760))
brings ARKit `ARMeshAnchor` parity to Android via ARCore StreetscapeGeometry. iOS gains three
native `CameraControlMode` cases ([#1049](https://github.com/sceneview/sceneview/issues/1049)
Phase 2) that delegate to Apple's `realityViewCameraControls(_:)` modifier. Install on Android via
Play Store internal track within minutes of tagging; iOS via TestFlight.

### Added

- **iOS — native camera modes** (`CameraControlMode`): three new native cases
  (`.none`, `.tilt`, `.dolly`) delegate directly to Apple's
  `realityViewCameraControls(_:)` modifier (iOS 18+, macOS 15+, visionOS 2+)
  instead of SceneView's custom gesture math. The existing cross-platform modes
  (`.orbit`, `.pan`, `.firstPerson`) are unchanged — they keep orbit inertia,
  auto-rotate, and fit-to-bounds framing. Closes #1049 (Phase 2 — exposing
  the native Apple camera modes as verified in the Xcode SDK).
- **iOS deep-link registry widened to full demo catalog.** `DemoDeepLinkRegistry.allowedIds` now contains all 42 demo IDs (matching Android's `DemoRegistry.kt`), so every `sceneview://demo/<id>` QR code is reachable on iOS — available demos open their real destination; coming-soon demos route to a `DeepLinkPlaceholder` instead of silently dropping the link. Added missing `destination(for:)` cases for `AnimationDemo`, `ARInstantPlacementDemo`, `ARLightingDemo`, `ARRecorderDemo`, `MaterialsDemo`, `OrbitalARDemo`, `SceneGalleryDemo` and `MultiModelDemo` (#1579).
- **iOS QA mode deep-link arg.** Appending `?qa_mode=1` to any `sceneview://demo/<id>` URL (or passing `-qa_mode 1` as a launch argument) writes `UserDefaults["qa_mode"]`, which freezes auto-rotation in `ModelViewerScreen` and `SketchfabModelViewerScreen` for deterministic QA screenshots — mirrors Android's `qa_mode` intent extra. Read from any view via `@AppStorage(DeepLinkRouter.qaModeDefaultsKey)` ([#1579](https://github.com/sceneview/sceneview/issues/1579)).
- **iOS QA: `lib/ios-axe.sh`** — helper script wrapping AXe (accessibility-driven iOS Simulator automation) for label-based taps, JSON UI-tree dumps, and screenshots. Mirrors `lib/android-cli.sh`'s pattern; falls back gracefully to `xcrun simctl` when AXe is not installed. Implements slice 1 of the iOS device-QA parity plan. (#1673)
- **`SceneMeshNode`** — new ARCore node wrapping `StreetscapeGeometry` meshes with unified
  `MeshClassification` semantics ([#1760](https://github.com/sceneview/sceneview/issues/1760)).
  Provides ARKit `ARMeshAnchor` parity on Android: every face in the mesh is labelled with a
  `MeshClassification` (FLOOR, WALL, CEILING, TABLE, SEAT, WINDOW, DOOR, TERRAIN, BUILDING,
  UNLABELED) and an `onClassifiedFace(faceIndex, classification)` callback lets callers build
  per-face colour maps, physics layer masks, or audio zones. On ARCore the label is coarse (one
  classification per geometry — TERRAIN or BUILDING); on ARKit it is per-face (fine-grained
  indoor labels). The callback signature is identical on both platforms so the same consumer
  code compiles unchanged.
  `ARSceneScope.SceneMeshNode(streetscapeGeometry, …)` composable wired in `ARSceneScope`; demo
  added as `ar-scene-mesh` in the Samples tab.
- **iOS demo: append-only demo registry pattern.** Adding a new iOS demo now requires creating a single `*Scene.swift` file with six header directives (`@sceneId`, `@title`, `@subtitle`, `@icon`, `@category`, `@available`); no other file needs editing. `samples/ios-demo/scripts/collate-ios-demos.sh` discovers all scene files, sorts them by `@sceneId` for a stable diff, and emits `GeneratedScenes.swift` automatically before each Xcode build via a "Collate iOS demos" Run Script phase. `GeneratedScenes.swift` is `.gitignore`d — parallel PRs adding different demos can never conflict on it ([#1872](https://github.com/sceneview/sceneview/issues/1872)).
- **iOS Augmented Faces demo** (`ar-face`): new `ARAugmentedFacesDemo` using `ARFaceTrackingConfiguration` + `AnchorEntity(.face)`; ring of coloured spheres orbiting the face pose tracked by TrueDepth camera (iPhone X+); simulator placeholder for non-device builds. Promotes `ar-face` from deep-link placeholder to a full iOS demo.
- **iOS AR Depth Occlusion demo** (`ar-depth-occlusion`): new `ARDepthOcclusionDemo` using `SceneReconstructionNode.enableOcclusion()` for LiDAR-powered real-world depth masking; toggle to enable/disable occlusion at runtime; graceful fallback banner for non-LiDAR devices; simulator placeholder. Promotes `ar-depth-occlusion` from deep-link placeholder to a full iOS demo.
- **iOS AR Image Tracking demo** (`ar-image`): new `ARImageTrackingDemo` using `AugmentedImageNode.createImageDatabase()` with a bundled QR code reference image; 3D cube overlaid on detected image; simulator placeholder shown on non-device builds. Promotes `ar-image` from deep-link placeholder to a full iOS demo.
- **iOS AR Plane Node demo** (`ar-plane-node`): detects ARKit horizontal and vertical planes, places a translucent blue marker cube at each plane centre, and displays a live plane-count pill. Mirrors Android `ARPlaneNodeDemo`. (#910)
- **iOS AR Point Cloud demo** (`ar-point-cloud`): renders ARKit live tracking feature points via `ARView.debugOptions.showFeaturePoints`, shows a live point-count pill, and offers a toggle to enable/disable the overlay. Mirrors Android `ARPointCloudDemo`. (#910)
- **iOS — Collision & Hit Test demo**: port the `collision` demo from placeholder to a full implementation — five `GeometryNode` shapes (cubes and spheres) are tap-highlighted via `SceneView.onEntityTapped`; an on-screen "Reset Colors" button clears all highlights; Maestro `interaction.yaml` promoted from `placeholder.yaml` smoke to a real `demo.yaml` flow (#910).
- **iOS demo: Debug Overlay** — RealityKit sphere stress test with live FPS stats, frame time, node/triangle counts, and a rolling FPS sparkline. Matches Android's `DebugOverlayDemo`: preset buttons (1/10/100/500/1 000 spheres), progressive spawn, and a 10-second stress ramp from 1 → 1 000 spheres. `sceneview://demo/debug-overlay` now routes to the real demo instead of the coming-soon placeholder. (#910)
- **iOS — HDR Environment demo**: port the `environment` demo from placeholder to a full SwiftUI implementation — `SceneViewDemo` now shows a `.demoSettingsSheet` with a grid of environment presets (`.studio`, `.outdoor`, `.sunset`, `.night`, `.warm`, `.autumn`, `.nightSky`) switchable at runtime; Maestro `lighting.yaml` promoted from placeholder to `demo-settings.yaml` smoke (#910).
- **iOS — Gesture Editing demo**: port the `gesture-editing` demo from placeholder to a full implementation — a `ModelNode` (ferrari_f40) is draggable, pinch-scalable, and two-finger-rotatable in Edit Mode; camera orbits freely in View Mode; settings sheet shows a mode toggle, Reset button, and live transform readout (#910).
- **iOS demo — Occlusion Material**: new `OcclusionMaterialDemo` shows RealityKit's built-in
  `OcclusionMaterial` in action — an invisible, depth-writing plane that cuts a sphere, with a
  toggle to reveal the occluder as a semi-transparent slab. Reachable via
  `sceneview://demo/occlusion-material`. Closes the last pure-3D gap in the iOS Advanced
  category relative to the Android catalog (#910).
- iOS AR People Occlusion demo (`ar-people-occlusion`): toggle ARKit `personSegmentationWithDepth` to hide virtual cubes behind real people walking in front; requires A12+ chip (#910).
- iOS AR Body Tracker demo (`ar-body-tracker`): `ARBodyTrackingConfiguration` + RealityKit `BodyTrackedEntity` marks the detected skeleton root joint in real time; requires A12+ chip (#910).
- iOS AR Scene Mesh demo (`ar-scene-mesh`): `ARWorldTrackingConfiguration.sceneReconstruction = .meshWithClassification` builds a live LiDAR mesh with a debug wireframe toggle; requires LiDAR device (#910).
- **iOS — Reflection Probes demo**: port the `reflection-probes` demo from a placeholder to a full SwiftUI implementation using `ReflectionProbeNode`. Shows a metallic sphere and cubes with varying metallic values inside a box probe zone; an environment picker switches between four IBL presets (Sunset, Night Sky, Studio, Outdoor) with a live intensity slider.
- **iOS — Shape Extrude demo**: port the `shape` demo from a placeholder to a full SwiftUI implementation using `ShapeNode`. Six preset shapes (Triangle, Star, Pentagon, Hexagon, L-Shape, Arrow) with adjustable extrusion depth slider (0–0.4 m) and a PBR/unlit material toggle.
- iOS demo: add Texture Streaming demo (`sceneview://demo/texture-streaming`) — interactive PBR material preset switcher (Gold/Silver/Copper/Ceramic/Plastic/Rubber) on a sphere using `PhysicallyBasedMaterial`; teaches runtime material swap without geometry rebuild (#910).

### Changed

- Bump Filament from 1.71.0 to 1.71.4 (patch — no `.filamat` recompile needed; includes Metal async resource loading, bounds-check fixes in `filaflat`, and iOS arm64 simulator support in Xcode 16+) (#2156).
- Refresh store listing assets: update app icon (Android + iOS) to the canonical 3D isometric cube branding, regenerate feature graphic ("3D and AR for Android, iOS & Web"), and replace all App Store / Play Store screenshots with fresh captures (#2180).
- Bump Compose BOM to `2026.05.01` (commit `6a2b4b4d1`).

### Fixed

- **Xcode project registration for new AR demos.** `ARPeopleOcclusionDemo`, `ARBodyTrackerDemo`, `ARSceneMeshDemo`, and their scene-registry files were not registered in the Xcode project's Sources build phase — fixed alongside the new demos so the iOS targets actually compile them. (#910)
- **CI (`app-store.yml` submit step):** switch from the legacy `appStoreVersionSubmissions` API to App Store Connect's `reviewSubmissions` API v3 (2023+). The old endpoint returned `403 "Allowed operation is: DELETE"` whenever a stale submission was attached to an absorbed draft (the #1687 / #1795 retargeting pattern); the read permission needed to find that stale submission was not in scope on our deploy service account. The new flow (`POST /v1/reviewSubmissions` + `POST /v1/reviewSubmissionItems` + `PATCH submitted: true`) is independent of any legacy submission state, so the 403 class is eliminated entirely. Closes the long-running #1831 saga end-to-end (#2141 closes #1831).
- **ARFaceDemo: front-camera unavailability diagnostic.** Added a 5-second timeout after which, if no AR frame has been received, the status pill turns red and reads "Front camera unavailable on this device". This surfaces the silent black-screen regression on Pixel 9 (#1612) where `frontCameraConfig` may fall back to the BACK camera, leaving the selfie feed dead without any user-visible error.
- **CI (quality-gate):** `feedback-worker` `npm test` is now run as part of the quality gate — a future regression in the worker is caught on every PR that touches `feedback-worker/`. (#2032)
- **Feedback (Android demo):** lower the screen-recording size cap from 28 MB to 25 MB to give 5 MB of headroom for the AAC audio track + multipart envelope (vs the previous ~2 MB) before the worker's 30 MB 413 threshold. (#2032)
- **Feedback (FeedbackContextTest):** fix stale KDoc mentioning the removed `route` key; add `isEmulator()` reachability test. (#2032)
- **iOS (SceneViewSwift):** `SceneEntities.deinit` no longer traps if the instance is released off the main thread. Replaced `MainActor.assumeIsolated` with an explicit `Thread.isMainThread` guard + `DispatchQueue.main.sync` fallback so an off-main release degrades gracefully instead of crashing. (#2068)
- **iOS demo (samples):** `ModelViewerDemo`, `PhysicsDemo`, and `SpatialAudioDemo` now set `.environment(.studio)` on their `SceneView` — matching the android-demo IBL fix (#2110) so metallic glTF models are consistently lit across the iOS demo catalog. (#2114)
- **CI:** CI Gate no longer hard-fails on docs-only PRs. A 90-second grace period replaces the previous 50-minute timeout — if no other check runs register (because every workflow was path-filtered out), the gate exits green immediately. (#2117)
- Fix Play Store CI deploys blocked by undeclared Foreground Service (FGS) permission (#2120).
  The production fallback now preserves the staged edit in Play Console (instead of deleting it
  on FGS failure), making the FGS declaration section visible under App content. A new
  `commit_edit_id` fast-path in `workflow_dispatch` lets you commit the preserved edit in ~2 min
  after declaring FGS — no 40-min rebuild needed.
- **ARSceneView:** `detectConfigDowngrades` now captures the post-`sessionConfiguration`-callback depth mode, so a callback-driven depth-mode request that gets silently downgraded is correctly surfaced as `ARConfigDowngrade.DepthMode`. (#2122 / #2096 gap 1)
- **MaterialsDemo:** fixed infinite "Loading…" scrim when the `materials` registry category is empty (null selected slug now exits to an `Empty` state instead of staying in `Loading` forever). (#2122)
- **Feedback (Android demo):** detect emulator in `FeedbackContext` (`isEmulator` flag). The review screen now shows a warning hint when submitting from an emulator without a typed note, since emulator mics are silent and Whisper returns an empty transcript. (#2123)
- **Feedback (worker):** the GitHub issue body now explains *why* there is no transcript when both transcript and typed text are empty: emulator submissions get a specific "no physical mic" message; other silent-audio cases get a generic explanation. A maintainer note is added to avoid confusion when an issue has no actionable content. (#2123)
- `samples/android-demo/build.gradle`: honour `-PversionName` from Play Store workflow — versionName was hardcoded, causing Play Console to show the stale name from the build.gradle source instead of the release tag.
- Fix `.well-known/assetlinks.json` and `apple-app-site-association` returning HTTP 404 on `sceneview.github.io` — `upload-artifact@v7` silently stripped dot-prefixed directories unless `include-hidden-files: true` is set, causing the deploy job's patch step to fail (#2155).
- `docs.yml`: fix `/.well-known/` files returning HTTP 404 on `sceneview.github.io` — `peaceiris/actions-gh-pages`'s internal `shelljs cp` glob does not expand dot-prefixed subdirectories, so `assetlinks.json` and `apple-app-site-association` were silently dropped on every deploy; a post-deploy patch step now adds the missing directory via a direct SSH git commit (#2155).
- **iOS registry: remove stale `ar-eis` / `ar-pose-placement` deep-link aliases** — the canonical Android IDs (`ar-image-stabilization`, `ar-pose`) were already present in `allowedIds`; the aliases were unreachable duplicates that silently dropped `sceneview://demo/ar-image-stabilization` QR-code taps. (#2173)
- **iOS demo** — renamed placeholder scenes `ArEisScene` → `ArImageStabilizationScene` and `ArPosePlacementScene` → `ArPoseScene` so their `@sceneId` directives match the canonical Android IDs (`ar-image-stabilization`, `ar-pose`) used by QR codes and deep links; closes the gap left by #2174 which fixed `allowedIds` but not the scene catalogue.
- Fix `device-qa.sh` crash on macOS (`timeout: command not found`): `lib/maestro.sh` now falls back to `gtimeout` (homebrew coreutils) or runs unbounded when neither GNU `timeout` variant is available (#2184).
- **Feedback (Android demo):** stop crashing the app when the user triggers screen recording on a Play Store build that ships without `FOREGROUND_SERVICE_MEDIA_PROJECTION` (the #2120 catch-22). `FeedbackRecordingService.isRecordingAvailable()` now detects the missing typed-FGS permission on Android 14+; the flow short-circuits to text + audio before `startForegroundService` raises `ForegroundServiceDidNotStartInTimeException`. `start()` / `stop()` are also belt-and-suspenders try/caught. Robolectric regression suite locks the SDK-gated behaviour. (#2188)
- **Sketchfab (Android demo Explore tab):** repair the silently-empty Discover/Gallery/Tutorials carousels. AWS CloudFront's WAF in front of `api.sketchfab.com` was returning HTTP 202 + an empty body + `x-amzn-waf-action: challenge` to any request carrying OkHttp's default `User-Agent: okhttp/<version>` (treated as bot traffic), so the JSON decoder threw `Expected start of the object '{', but had 'EOF' instead`, each feed swallowed the error, and the user saw a half-rendered Explore tab. Now sends an explicit app-identifying `SceneViewDemo/<version> (Android; +https://sceneview.github.io)` User-Agent and surfaces a typed `WafChallenge` error so the "Sketchfab unavailable" banner explains the state instead of three self-hiding carousels. (#2191)
- **Sketchfab (Android demo):** stop the 5+ second ANR when opening the model preview sheet. The Filament `Engine` is now pre-warmed at the sheet root on the first transition out of `Preview` (gated by `stage !is Preview`), so the ~5 s synchronous JNI cost overlaps with the Ken-Burns + spinner UI of the `Downloading` stage instead of (a) blocking the user's card-tap on a stale Explore-tab background (the original ANR) or (b) freezing on a stopped-spinner moment between `Downloading` completion and `Rendering` (an earlier partial fix). The Engine slot survives the Downloading → Rendering transition, so the model appears the instant `rememberModelInstance` finishes parsing the GLB — no second freeze. (#2193)
- **Feedback chip (Android demo):** stop masking the bottom row of content across tabs. Introduces a shared `FEEDBACK_FAB_RESERVED_SPACE` constant (in the new `feedback/FeedbackChrome.kt`) applied as bottom `contentPadding` on the Samples grid, the About column, and the AR-View launcher column, so the floating chip floats over a gutter rather than over the last items. The chip is also hidden while the live `ARSceneView` is on screen (via a `DisposableEffect` toggling `FeedbackChrome.chipVisible`), so the AR-View bottom action bar (model picker + Reset + Share) is no longer half-masked on the left. (#2194)
- **AR-View "Try an AR demo" tiles (Android demo):** stop rendering the same generic `Icons.Filled.ViewInAr` on every tile. `FeaturedArDemo` now carries a per-demo `ImageVector` (`AddLocationAlt`, `Face`, `Cloud`, `LocationCity`, `Layers`, `SelfImprovement`) so users can tell the 6 demos apart at a glance — matching the Samples-tab grid where each demo already had a unique icon. (#2195)
- **iOS — Video Texture demo**: add `VideoTextureDemo.swift` and `Videos/sample.mp4` to the Xcode project (`project.pbxproj`) so the `video` demo that was already implemented (but orphaned) now compiles and runs. Fixes `GeometryNode.plane(width:height:)` call to use the correct `width:depth:` parameter.
- **Fixed `BillboardNode` silently ignoring billboard rotation on macOS.** The `#available(iOS 18.0, visionOS 2.0, *)` guard in `BillboardNode.init(child:)` excluded macOS, so `BillboardComponent` was never applied and entities faced a fixed direction instead of the camera. Since `SceneViewSwift` requires macOS 15+ (which ships `BillboardComponent`), the guard is removed. Added a Platform Support table to `SceneViewSwift/README.md` documenting that `SceneView` (3D) is fully supported on macOS but `ARSceneView` is iOS-only ([#914](https://github.com/sceneview/sceneview/issues/914)).
- **iOS demo (SketchfabService):** `downloadBinary` now surfaces real download progress instead of always emitting `1.0` at completion. Replaced `URLSession.download(from:)` (no intermediate callbacks) with a `URLSessionDownloadDelegate` that reports per-byte progress, so the model viewer's progress bar animates smoothly on slow connections. (#982)
- **Fixed iOS AR screenshot capturing a black hole instead of 3D content.** `ARTab.shareARScreenshot` previously used `UIView.drawHierarchy`, which skips the Metal layer and produces a transparent / black hole where the 3D AR content lives. Now uses `ARView.snapshot(saveToHDR:completion:)` — RealityKit's Metal-aware capture path — which correctly captures both the camera background and 3D content. The simulator path shows a user-friendly "AR screenshots require a physical device" message instead of producing a broken image ([#983](https://github.com/sceneview/sceneview/issues/983)).
- **`sceneview-web` README CDN/API mismatch fixed.** The README marketed a non-existent `sceneview.js` CDN file and a `SceneView.modelViewer(...)` global with methods (`setQuality`, `setBloom`, `addLight`, `createText/Image/Video`, …) the build never exposed — every `<script>` snippet 404'd and the API table was fiction. It now documents the real `sceneview-web.js` artifact path and the actual `window.sceneview` API surface (`createViewer`, `modelViewer`, and the `SceneViewer` instance methods), matching `sceneview-web.d.ts`.
- **`sceneview-web` now ships its TypeScript declarations.** `package.json` gained a `"types": "sceneview-web.d.ts"` field and the hand-written `.d.ts` is now in `files[]`, so TS consumers get typings instead of `any`.
- **`sceneview-mcp` `sceneview://known-issues` resource no longer crashes on malformed GitHub API items.** The issue type guard validated only `number`/`title`, then `formatIssues` unconditionally read `issue.user.login`, `issue.labels` and `issue.updated_at` — a partial API item (e.g. during a GitHub incident) threw a `TypeError` and took down the whole resource. Items are now normalized with safe defaults for `user`, `labels` and `updated_at`.

### Tests

- **iOS deep-link registry**: sync `DemoDeepLinkRegistry.allowedIds` to the full Android catalog (65 IDs covering all 60 Android demo IDs) — 23 new AR and 3D demo IDs added so QR codes for newer demos no longer silently 404 on iOS; corresponding placeholder flows added to Maestro `.maestro/ios/` for CI smoke coverage.
- **Android Maestro**: expanded demo coverage from 43 to 58 demos — added 13 missing AR demos (`ar-depth-of-field`, `ar-fog`, `ar-depth-collider`, `ar-depth-visualization`, `ar-people-occlusion`, `ar-point-cloud`, `ar-raw-depth-point-cloud`, `ar-plane-node`, `ar-scene-mesh`, `ar-scene-semantics`, `ar-ml-object-label`, `placement-scene`, `ar-collaborative`, `ar-body-tracker`) and 2 Advanced demos (`occlusion-material`, `spatial-audio`) to the device-QA harness (#1913).

### Docs

- **iOS — Scene Reconstruction parity**: update `cheatsheet-ios.md` and `llms.txt` to mark
  `SceneReconstructionNode` (renderable mesh) and `enablePhysics(in:)` (physics collider) as
  **Available** — closes the documentation gap from #1860. The library wrapper ships since
  the earlier `SceneReconstructionNode.swift` implementation.
- **docs(ios)** — `samples-ios.md` refreshed with the full 59-demo iOS catalog
  table (3D Basics, Lighting, Content, Interaction, Advanced, AR) and updated
  minimal working examples including the new `CameraControlMode` native Apple
  modes (`.none`, `.tilt`, `.dolly`, iOS 18+). Closes the documentation gap
  left after the iOS parity sprint (umbrella [#910](https://github.com/sceneview/sceneview/issues/910)).

## v4.15.1 — Play Store R8 deploy fix + burn-down sweep: black-model IBL, Sketchfab repair, demo-hang & macOS-archive fixes (2026-05-22)

### Added

- **Surface AR camera-config / depth-mode downgrades ([#2096](https://github.com/sceneview/sceneview/issues/2096)).** `ARSceneView` now exposes an `onConfigDowngraded` callback that fires with a typed `ARConfigDowngrade` (`DepthMode` or `CameraConfig`) when a requested capability is unsupported on the device and is silently downgraded to a working fallback — so apps can adapt their UI instead of behaviour diverging silently across devices.

### Fixed

- **macOS demo target archives again ([#1794](https://github.com/sceneview/sceneview/issues/1794)).** Guarded iOS-only SwiftUI APIs in the shared `samples/ios-demo` Swift source so the `SceneViewDemo` macOS target compiles: added cross-platform `Color.systemBackground` / `secondarySystemBackground` / `tertiarySystemBackground` helpers and a `navigationBarTitleInline()` modifier in `Theme.swift`, `#if os(iOS)`-guarded the iOS 18 `.zoom(sourceID:in:)` navigation transition and the `topBarTrailing` toolbar placement.
- **Demo settings sheet remembers its last detent per demo ([#2084](https://github.com/sceneview/sceneview/issues/2084)).** The demo-app settings bottom sheet now reopens at the detent (partially-expanded vs fully-expanded) the user last left it at, individually for each demo. The detent is persisted in `SharedPreferences` keyed by demo title, so it survives navigating away from the demo and full process death. A demo never opened before still defaults to the partial detent.
- **`materials` and `scene-gallery` Android demos no longer hang on "Streaming…" ([#2088](https://github.com/sceneview/sceneview/issues/2088)).** A failed model resolution is now captured into an error state and surfaced with an error scrim and a Retry button, instead of being swallowed into a `null` path that left the loading scrim spinning forever. Both demos are flagged `DemoStatus.KnownIssue` so the Samples grid shows an honest known-issue chip.
- **Sketchfab integration repaired in the demo apps ([#2095](https://github.com/sceneview/sceneview/issues/2095)).** All 29 Stage-1 placeholder model uids in `SampleAssets.kt` / `SampleAssets.swift` were fabricated and returned HTTP 404, silently breaking every streamed sample demo. They are replaced with 29 real, API-validated Sketchfab models (each verified `200` + `isDownloadable: true` + CC-BY 4.0). The `materials` and `scene-gallery` demos no longer hang and are restored from `KnownIssue` to `Working` (reverting the #2088 stopgap). The Android Explore feed now shows the "Sketchfab unavailable" banner when the API key is rejected with HTTP 401/403 instead of silently collapsing to an empty feed, an OkHttp disk cache was added to `SketchfabService` to cut rate-limit (429) pressure on basic-plan keys, and the `verify-sketchfab-key` CI step now runs on `workflow_dispatch` release paths, not only tag pushes.
- Fixed the `android-demo` release AAB build failing at `minifyReleaseWithR8` with `Missing class javax.lang.model.**`. MediaPipe's `tasks-vision` POM dragged the full `com.google.auto.value:auto-value` annotation processor (and a shaded JavaPoet) onto the runtime/minify classpath; R8 full-mode promoted the compile-time-only `javax.lang.model.**` JDK classes to a hard error. AutoValue is now excluded from the `tasks-vision` dependency and matching `-dontwarn` keep rules were added, unblocking the Play Store release. ([#2106](https://github.com/sceneview/sceneview/issues/2106))
- **glTF models no longer render solid black in demos that didn't set an environment ([#2110](https://github.com/sceneview/sceneview/issues/2110)).** A glTF model with metallic / smooth PBR materials needs an image-based-lighting (IBL) environment to reflect. SceneView's default environment is a lightweight neutral IBL paired with a solid black skybox, so metallic surfaces had nothing bright to reflect and rendered black. The non-AR model demos (`FogDemo`, `CameraControlsDemo`, `GestureEditingDemo`, `PostProcessingDemo`, `OcclusionMaterialDemo`, `SceneGalleryDemo`, `ModelViewerDemo`) now share a `rememberModelDemoEnvironment` helper that supplies the bundled studio HDR IBL (the same one the multi-model scene uses), so the Damaged Helmet and other PBR models are correctly lit. No model materials were overridden.
- **Fresh iOS App Store screenshots + `-demo` launch argument ([#917](https://github.com/sceneview/sceneview/issues/917)).** The App Store Connect listing carried stale screenshots — Android-device captures, several of them blank white AR scenes, and phone images letterboxed onto the iPad canvas. A new set of genuine iOS-simulator captures showing real rendered 3D content now ships under `samples/ios-demo/appstore-screenshots/` (`iphone-6.9/` at 1320×2868, `ipad-13/` at 2064×2752), regenerable via `.claude/scripts/capture-appstore-screenshots.sh`. The demo app gained a `-demo <id>` launch argument that routes straight to a demo on first frame (reusing `DemoDeepLinkRegistry`), giving the capture pipeline a deterministic, dialog-free entry point alongside the existing `sceneview://demo/<id>` user-facing deep link.

### Tests

- **Deterministic `CollaborativeSessionTest` ([#2091](https://github.com/sceneview/sceneview/issues/2091)).** The `'hello propagates a participant'` test (and its siblings) intermittently failed on CI with a coroutine `TimeoutCancellationException`: cross-session message propagation ran on `Dispatchers.Default` while assertions polled a real `withTimeout`, so a contended runner could starve the thread pool past the deadline. `CollaborativeSession` now accepts an injectable I/O dispatcher (test-only, production unchanged) and the test drives propagation on a `StandardTestDispatcher` with `runTest` virtual time — no thread pool, no wall-clock race.

## v4.15.0 — Cross-platform bridge & audit hardening: Flutter/RN iOS bridges, resource-leak sweep, CI-drift fixes (2026-05-22)

### Added

- **"Replay + analyse" mode in the AR Recording demo ([#2027](https://github.com/sceneview/sceneview/issues/2027)).** The android-demo AR Recording demo gains a fourth mode that replays a dataset *and interprets it*: every replayed frame is folded through the `ARRecordInterpreter` library API and the running `ARRecordInterpretation` — tracked-frame %, trajectory length, dominant `TrackingFailureReason`, plane count/area — is overlaid live on the replay. When the dataset ends (`rememberARPlaybackStatus == FINISHED`) a final report card sums up the take with a green/amber tracking verdict and a per-failure-reason breakdown.

### Changed

- **AR demos: fitting per-demo content instead of the generic Damaged Helmet ([#2023](https://github.com/sceneview/sceneview/issues/2023)).** Nine Android AR demos no longer load `khronos_damaged_helmet.glb` purely as a generic floating stand-in. The six already-bundled models are redistributed so each demo's placed object reads as intentional content grounded in the room: Image Stabilization and Cloud Anchor use the lantern, Depth of Field and Augmented Image use the toy car, Terrain Anchor and Rerun use the fox / Shiba, Record & Playback uses the fox, and both placement cycles (`ARPlacementDemo`, `ARInstantPlacementDemo`) swap the helmet entry for the upright Soldier character. The helmet is kept only in the two occlusion demos (Depth Occlusion, People Occlusion), where a hard-surface PBR payload genuinely fits.

### Fixed

- **feedback-worker: minor cleanups deferred from the hardening review ([#2028](https://github.com/sceneview/sceneview/issues/2028)).** The `202` response now carries a `reason` field (`"quota"` vs `"github_error"`) so a caller can tell a deliberate issue-quota throttle from a GitHub-side failure. An empty or whitespace-only `Content-Length` header is now rejected with `411` instead of slipping through as a zero-length body (`Number("")` is `0`). The Whisper-detected transcript language is surfaced as a `Transcript language` row in the GitHub issue context table instead of being discarded. The unused `'purged'` value was dropped from the `feedback.status` CHECK constraint (media expiry is tracked by `media_purged`, never `status`).
- **android-demo: in-app feedback — lower-priority review follow-ups ([#2030](https://github.com/sceneview/sceneview/issues/2030)).** Rotating the device mid-recording no longer leaves the rest of the clip stretched — `FeedbackRecordingService` now re-fits the `VirtualDisplay` to the rotated screen aspect inside the fixed encoder surface (deliberate, centred letterboxing) on a configuration change. A `MediaProjection` revoked by the system or another app is now reported with a distinct "Recording stopped early" message instead of the generic "recording didn't work" copy. The tab-screen feedback FAB collapses to an accessible icon-only FAB on a narrow screen or at a large font scale, so the extended label can no longer overflow or crowd the navigation bar. The "My feedback" screen gains a "Refresh status" action that drops the 5-minute GitHub status cache so a user can re-check a ticket immediately.
- **Fixed five resource leaks in the Android core libraries.** `Node.destroy()` now recursively destroys its children as documented ([#2036](https://github.com/sceneview/sceneview/issues/2036)); `MeshNode` can free its owned `VertexBuffer`/`IndexBuffer` and `StreetscapeGeometryNode` opts in ([#2037](https://github.com/sceneview/sceneview/issues/2037)); `ARCameraStream.destroy()` releases its `IndexBuffer` ([#2039](https://github.com/sceneview/sceneview/issues/2039)); `Delaunator`'s `legalize()` stack grows on demand instead of silently dropping edges ([#2041](https://github.com/sceneview/sceneview/issues/2041)); and the `AnchorNode.anchor` setter detaches the replaced ARCore anchor ([#2043](https://github.com/sceneview/sceneview/issues/2043)).
- **iOS `NodeGesture` no longer leaks entities ([#2038](https://github.com/sceneview/sceneview/issues/2038)).** Per-entity gesture handlers are now stored in a RealityKit component attached to the target entity instead of in process-global `static` dictionaries. The handler closures live exactly as long as the entity does — the common `onDrag(cube.entity) { cube.position += … }` capture pattern no longer leaks the entity and its resources for the whole process lifetime — and two `SceneView` instances can no longer share or wipe each other's gesture state. `removeAllHandlers()` is replaced by the scene-scoped `removeAllHandlers(under:)`.
- **iOS `CameraControls` convenience init `minRadius` default corrected to `1.0` ([#2040](https://github.com/sceneview/sceneview/issues/2040)).** It previously defaulted to the pre-v4.4.0 value `0.5`, which clips the perspective camera into geometry on the true-camera orbit path — so `CameraControls(mode:sensitivity:)` silently re-introduced the bug. Both initializers now agree.
- **iOS `ViewNode<Content>` documentation is now honest ([#2042](https://github.com/sceneview/sceneview/issues/2042)).** `ViewNode` currently renders a placeholder white plane and does not display the SwiftUI `content` it is given (the UIView→texture pipeline is tracked by #1035). The type is now marked `@available(*, deprecated)` and its doc-comment no longer claims interactive SwiftUI rendering.
- **iOS removed 17 dead `#if os(...)` guards ([#2044](https://github.com/sceneview/sceneview/issues/2044)).** Inner `#if os(iOS) || os(visionOS) || os(macOS)` guards nested inside identical always-true file-level guards across 12 `SceneViewSwift` files were removed (the code inside stays). A note in `CONTRIBUTING.md` keeps new code from re-introducing them.
- **Web XR sessions no longer leak the Filament engine + WebGL context ([#2045](https://github.com/sceneview/sceneview/issues/2045)).** `WebXRSession`, `ARSceneView` and `VRSceneView` now destroy the `SceneView` they created when the session ends — both via `stop()` and via the `onend` handler (system-UI / headset-menu exit) — with an idempotency guard so the two paths cannot double-free.
- **Web XR render loop applies the per-eye projection matrix and renders both eyes ([#2046](https://github.com/sceneview/sceneview/issues/2046)).** Each frame now sets the Filament camera from `XRView.projectionMatrix` (correct FOV / passthrough registration) via a new `Camera.setCustomProjection` binding, and the VR path renders **every** `XRView` into its own viewport instead of only `views[0]` — VR is now genuinely stereo.
- **Web: `createViewerImpl` no longer leaks a `window` resize listener ([#2048](https://github.com/sceneview/sceneview/issues/2048)).** The untracked, never-removed listener was redundant with `SceneView.autoResize` (which also updates the viewport + projection) — it has been removed.
- **Web: `sceneview-web.d.ts` matches the Kotlin source ([#2057](https://github.com/sceneview/sceneview/issues/2057)).** `setAutoRotateSpeed` is documented as radians **per frame** (was wrongly "per second" — a ~60x speed error for consumers), the missing `setAutoCenterContent` method is now declared, and the stale version example is refreshed.
- **Web: committed `sceneview-web/package.json` no longer carries misleading publish fields ([#2058](https://github.com/sceneview/sceneview/issues/2058)).** The `main`/`files`/`publishConfig` entries pointed at a `build/dist/js/...` path the build never produces; they are removed (CI's `release.yml` generates the real published manifest) with a comment recording that `release.yml` is the single source of truth.
- **`mcp/dist/` is no longer committed to git ([#2047](https://github.com/sceneview/sceneview/issues/2047)).** The compiled `tsc` output was tracked in version control yet regenerated by the `prepare` script on every `npm install` / `npm publish`, so it silently drifted from `src/` — the committed `dist/generated/llms-txt.js` and `dist/generated/version.js` embedded a SceneView SDK version three minor releases stale. `mcp/dist/` is now fully `.gitignore`d (the npm tarball is always built fresh on publish), and the `.gitignore` test-artefact glob is widened from `mcp/dist/*.test.js` to the whole directory so nested compiled test files are never tracked.
- **Flutter: `SceneView`/`ARSceneView` no longer dispose a caller-owned controller ([#2050](https://github.com/sceneview/sceneview/issues/2050)).** The widget now disposes only the controller it created itself; a controller passed in by the caller is left untouched, so controller reuse and widget re-parenting no longer break.
- **Flutter iOS: 3D `onTap` callback now fires ([#2051](https://github.com/sceneview/sceneview/issues/2051)).** The iOS bridge wires SceneViewSwift's entity hit-test to the `onTap` method channel, matching Android. AR `onTap`/`onPlaneDetected` remain Android-only for now — the Dart docs now state this explicitly instead of implying parity.
- **Flutter iOS: platform views no longer leak the RealityKit scene ([#2052](https://github.com/sceneview/sceneview/issues/2052)).** Both iOS platform-view classes now detach the `FlutterMethodChannel` handler in `deinit`, breaking the retain cycle that kept the hosting controller, ARSession, and scene alive after the Flutter widget was disposed.
- **React Native: `onTap` / `onPlaneDetected` events now actually fire ([#2053](https://github.com/sceneview/sceneview/issues/2053)).** The two event props were exported but never dispatched. Android now registers them via `getExportedCustomDirectEventTypeConstants` and dispatches a `TapEvent` (tapped node name + world position) / `PlaneDetectedEvent` (one per newly-tracked ARCore plane) through the view's `EventDispatcher`. iOS wires `onTap` to SceneViewSwift's tap callback.
- **React Native iOS: `geometryNodes` / `lightNodes` parity gap disclosed ([#2054](https://github.com/sceneview/sceneview/issues/2054)).** The props are fully rendered on Android but not on the iOS RealityKit bridge — the TypeScript doc comments now state the iOS limitation explicitly instead of silently dropping the props.
- **React Native: `ARSceneView` `depthOcclusion` / `instantPlacement` wired to the AR session ([#2055](https://github.com/sceneview/sceneview/issues/2055)).** Android now forwards both flags to ARCore via `Config.DepthMode.AUTOMATIC` / `Config.InstantPlacementMode.LOCAL_Y_UP`; the iOS gap (no SceneViewSwift knob) is disclosed in the TypeScript doc comments.
- **React Native: podspec git tag fixed ([#2056](https://github.com/sceneview/sceneview/issues/2056)).** `react-native-sceneview.podspec` resolved its git source to the bare version (`4.14.0`) but the repo's release tags are `v`-prefixed; the source tag is now `v#{s.version}`.
- **Flutter plugin's iOS bridge now compiles against the real SceneViewSwift API ([#2065](https://github.com/sceneview/sceneview/issues/2065)).** `SceneViewSwiftUIWrapper` / `ARSceneViewSwiftUIWrapper` referenced APIs that do not exist on `SceneViewSwift` — `ModelNode(path)` (no `String` initialiser) and `ForEach` inside `@NodeBuilder` (unsupported) — so the iOS plugin never compiled. The wrappers now use the real imperative `SceneView { (Entity) -> Void }` content closure and the async `ModelNode.load(_:)` API, streaming models in via a persistent content root (3D) and tap-to-place anchoring (AR). A pre-existing Swift 6 actor-isolation error (`SceneState()` constructed off the main actor) is also fixed. A new `bridge-ios-compile.yml` workflow type-checks the plugin's iOS Swift against the published SceneViewSwift module on every PR so this can't regress.
- **React Native iOS bridge now compiles against the real `SceneViewSwift` API ([#2067](https://github.com/sceneview/sceneview/issues/2067)).** `RNSceneViewContent` / `RNARSceneViewContent` referenced APIs that do not exist (`ModelNode(String)`, an `ARSceneView { anchor in … }` content closure, an `override`n `requiresMainQueueSetup` on a plain `NSObject`), so the module's iOS support never built. The bridge is rewritten to use the genuine `SceneViewSwift` surface — async `ModelNode.load(_:)`, `SceneView`'s imperative content init, and `ARSceneView`'s `onSessionStarted` / `onTapOnPlane` — and a new `rn-ios-compile.yml` CI workflow type-checks `react-native/react-native-sceneview/ios/*.swift` against the real package so this can't regress. The podspec no longer declares a CocoaPods `s.dependency` on `SceneViewSwift` (it is SwiftPM-only); the README documents adding it via Xcode's Swift Package Manager. Companion fix to #2065 (Flutter).
- **flutter ios: actually break the method-channel retain cycle ([#2069](https://github.com/sceneview/sceneview/issues/2069)).** the platform-view's `setMethodCallHandler` now installs the handler with a `[weak self]` capture; a bare method reference strong-held `self`, so the previously added `deinit` could never run and the platform view, hosting controller, and RealityKit/AR scene still leaked on every create/dispose cycle.
- **React Native Android: `ARSceneView` `depthOcclusion` / `instantPlacement` now apply on a live AR session ([#2070](https://github.com/sceneview/sceneview/issues/2070)).** PR #2066 forwarded both flags through the consumed `arsceneview:4.7.0` `sessionConfiguration` callback, but that callback runs **only once at session creation** — toggling either prop from JS afterwards was a silent no-op. The RN manager now captures the live ARCore `Session` via `onSessionCreated` and re-applies the `Config` from a `LaunchedEffect` keyed on the two flags, so a runtime `depthOcclusion` / `instantPlacement` toggle genuinely reconfigures the running session.
- **CI: two recurring drift classes made structurally impossible ([#2071](https://github.com/sceneview/sceneview/issues/2071)).** The `docs/docs/llms.txt` mirror of root `llms.txt` is no longer committed — it is regenerated from root `llms.txt` at docs-build time (`docs.yml`, before `mkdocs build`) and `.gitignore`d, so it can never drift and reden the `llms.txt mirror in sync` quality-gate check on an otherwise-clean PR (same gitignore-and-generate fix as `mcp/src/generated/llms-txt.ts` #1928 and `GeneratedDemos.kt` #1976); `check-llms-drift.sh` now enforces the structural invariant that the mirror stays untracked. Separately, `sceneview-web`'s `SCENEVIEW_VERSION` constant and its `SceneViewVersionTest.kt` regression pin are now swept and auto-fixed by `sync-versions.sh`, so a release version bump no longer leaves the constant stale (shipping a wrong version) and the `:sceneview-web:jsTest` job red.
- **Flutter iOS AR bridge: `clearScene` now removes placed models and tap placements no longer leak `AnchorEntity`s ([#2078](https://github.com/sceneview/sceneview/issues/2078)).** The Flutter plugin's iOS AR placement (`ARPlacementController` in `SceneViewPlugin.swift`) previously added a fresh `AnchorEntity` to the `ARView` scene on every plane tap and never removed any — 100 taps left 100 anchors retained — and `clearScene` only dropped the load cache, leaving every tap-placed model on screen permanently. The bridge now mirrors the React Native AR bridge's design: a single reusable content `AnchorNode` is captured once via `onSessionStarted`, every tap-placed model is added as its child, and a `clearScene` (`sync(to: [])`) calls `removeAll()` on that anchor so placed models are actually torn down and the scene's anchor count stays bounded.
- **React Native iOS: a superseded model load no longer leaks a stale model into the scene ([#2079](https://github.com/sceneview/sceneview/issues/2079)).** `RNSceneViewContent.loadModels()` and `RNARSceneViewContent.placeModels()` are driven by SwiftUI `.task(id:)`, which cancels the in-flight task whenever the JS `modelNodes` prop changes. A cancelled task still resumes past its `await ModelNode.load(_:)`, so the old code could insert a now-stale model into the scene after the prop had already moved on. Both closures now re-check `Task.isCancelled` immediately after every `await` and bail out before mutating the scene, matching the cancellation discipline already used by the Flutter 3D bridge.
- **iOS/macOS demo app marketing version stuck at 4.9.0 ([#2085](https://github.com/sceneview/sceneview/issues/2085)).** The `samples/ios-demo` Xcode project's `MARKETING_VERSION` (which drives `CFBundleShortVersionString`) was frozen at `4.9.0`, so every iOS and macOS build since v4.9.0 reported marketing version `4.9.0` to the App Store regardless of the real SDK version — the release pipeline only bumped the build number (`CURRENT_PROJECT_VERSION`). Both build configurations of the `SceneViewDemo` app target are now at the source-of-truth version, and `sync-versions.sh` `--fix` rewrites `MARKETING_VERSION` in lockstep with `gradle.properties` `VERSION_NAME` so a future release bump sweeps it automatically and it can never drift again.
- **Damaged Helmet renders all-black across demos ([#2087](https://github.com/sceneview/sceneview/issues/2087)).** The bundled `samples/android-demo/.../models/khronos_damaged_helmet.glb` carried its base-color and emissive textures as **WebP**-encoded images. Filament's glTF loader (`gltfio`) decodes embedded textures with stb_image, which does not support WebP, so the base-color texture silently fell back to a black 1×1 placeholder — and with the helmet material's `metallicFactor = 1` the model collapsed to an all-black blob in every demo that loads it (Model Viewer, Lighting, Camera Controls, Environment, …). This also produced unusable Play Store screenshots. The two WebP textures have been re-encoded to JPEG in place; geometry, nodes, samplers and material parameters are untouched. The helmet now renders with correct PBR shading. (The web-demo copy was already JPEG and unaffected.)
- Relocated the tablet Play Store screenshots added by #2092 from the
  orphaned `samples/android-demo/play/listings/` tree (dead since #1710)
  into the canonical, CI-synced
  `samples/android-demo/distribution/play-store/en-GB/graphics/` directory,
  renamed to `tablet7-screenshot-*.png` / `tablet10-screenshot-*.png` so
  `play-store.yml`'s listing-sync actually uploads them. Removed the
  re-created dead `play/` tree, including the 3 Chromebook captures — the
  Play `edits.images` API has no Chromebook image type, so large-screen
  devices reuse the 10-inch tablet screenshots.

### Docs

- **Corrected the Android demo count across all docs surfaces ([#2049](https://github.com/sceneview/sceneview/issues/2049)).** `samples/README.md`, `CLAUDE.md`, `docs/docs/samples.md`, `docs/docs/try.md` and `website-static/index.html` stated three contradictory totals (14 / 37 / 42) with the AR/non-AR split backwards; they now agree on the authoritative figure derived from the per-demo fragment registry — **59 demos (30 non-AR + 29 AR)**. `docs/docs/samples.md` is also rewritten to describe the current append-only `DemoRegistry` instead of the obsolete 4-tab / 14-demo structure.
- **android-demo: Play Store tablet & Chromebook screenshots.** Added a generated set of large-screen Play Store listing assets under `samples/android-demo/play/listings/en-US/graphics/` — six 16:9 tablet screenshots (2560×1440, used for both the 7-inch and 10-inch listing slots) and three 16:9 Chromebook screenshots (2400×1350). The captures cover the headline surfaces — the Dynamic Sky, Environment Gallery, Model Viewer and Geometry Primitives demos plus the Samples and About tabs — taken on a Pixel Tablet emulator running the bundled-asset demos.

## v4.14.0 — 2026-05-21

### Added

- AR Record interpretation: new `ARRecordInterpreter` (+ `rememberARRecordInterpreter()`) folds every frame of a replayed AR Record dataset into an `ARRecordInterpretation` — camera trajectory length & extent, tracked-frame ratio with a per-`TrackingFailureReason` breakdown, and discovered plane count & area — turning a record/playback session into a quantified, CI-assertable tracking-quality report ([#1441](https://github.com/sceneview/sceneview/issues/1441)).
- **Play Store CI observability.** A new `play-vitals.sh` release-gate (wired into `release-checklist.sh` section 15) grades the real-world crash & ANR rate from the Play Developer Reporting API — advisory by default, blocking under `PLAY_VITALS_HARD=1` (#1691). A new daily `play-reviews` job in `maintenance.yml` ingests Play Store ratings + reviews via the Android Publisher API and auto-opens a de-duplicated triage issue for any review matching a crash/bug signal (#1692). Both reuse the existing deploy service account read-only — no new write scope.
- **People Occlusion** — `ARCameraStream.isPersonOcclusionEnabled` occludes virtual objects behind real people using ARCore Scene Semantics' `PERSON`-class segmentation mask (flagship parity with ARKit `ARFrame.segmentationBuffer`, AR Foundation `AROcclusionManager`). New `camera_stream_person_occlusion.filamat` camera material (a strict superset of the depth-occlusion material) and an `ar-people-occlusion` demo. Requires `Config.SemanticMode.ENABLED`; outdoor scenes only (#1761).
- Body tracking on Android via MediaPipe Pose (#1763): a new `io.github.sceneview.ar.body` package in `arsceneview` ships renderer-agnostic `BodyPose` / `BodyLandmark` value types and a 17-joint `Joint` enum named to match ARKit's `ARSkeleton.JointName` for cross-platform parity. `BodyPose.fromMediaPipeLandmarks(...)` projects the 33 raw MediaPipe Pose Landmarker landmarks onto the joint set (synthesising `ROOT`/`SPINE`/`NECK` as anatomical midpoints), and `SKELETON_BONES` exposes the bone topology for overlays. A new `ar-body-tracker` demo in `samples/android-demo` runs Google's on-device MediaPipe Pose Landmarker on the AR camera feed and draws a live 2D skeleton overlay. **Honest parity note:** ARCore has no native body-tracking API, so unlike ARKit's `ARBodyTrackingConfiguration` + `BodyTrackedEntity` this is *image-space* tracking (normalised pixel coordinates + relative depth), not a world-anchored 3D skeleton — ideal for 2D overlays, fitness/gesture detection and AR filters, but not a drop-in for a world-anchored rig. The MediaPipe runtime stays a sample-only dependency; the published `arsceneview` artifact carries only the `BodyPose` / `Joint` value types.
- **Collaborative AR — multi-user sessions.** New `io.github.sceneview.ar.collaborative`
  package brings shared-coordinate-frame multiplayer to ARCore. `CollaborativeSession`
  (and the lifecycle-bound `rememberCollaborativeSession()` helper) orchestrates a shared
  AR experience on top of the existing `CloudAnchorNode`: one device hosts the shared
  Cloud Anchor, every other resolves the same id, and participant camera poses + placed-node
  transforms are relayed between peers as JSON-lines messages. The networking layer is a
  pluggable `CollaborativeTransport` interface — SceneView deliberately does not pick a
  stack — shipped alongside an always-available, no-networking `LoopbackCollaborativeTransport`
  reference impl that makes the API unit-testable and demonstrable on a single device.
  `CollaborativeWireFormat` is pure Kotlin with zero new runtime dependencies, and the
  whole merge core (`CollaborativeState`, last-writer-wins) is covered by 52 JVM unit
  tests. The new `ar-collaborative` sample demo proves the full sync end-to-end without a
  second phone. ARCore has no `collaborationData` API (unlike ARKit) — this is the honest,
  buildable shape of multi-user AR on Android. A production Nearby Connections transport is
  filed as a follow-up (#1764).
- **In-app feedback** — the Android demo app now has a "Feedback" button on every tab: users record their screen + voice to report a bug or share an idea, the recording is transcribed server-side and filed as a pre-filled GitHub issue, and a "My feedback" screen tracks each submitted ticket's live Open/Closed status with a tap-through to the real issue. ([#1930](https://github.com/sceneview/sceneview/issues/1930))
- **In-app feedback — screen + mic recording (1C):** the Android demo app captures a screen recording with microphone audio via `MediaProjection` and a `mediaProjection` foreground service, demuxes the AAC audio track into a standalone file for server-side transcription, and shows a review screen (duration, optional note, record-again / send) before the recording is submitted. Recording is optional for the "Idea" category. ([#1933](https://github.com/sceneview/sceneview/issues/1933))
- **In-app feedback — upload & context capture (1D):** the Android demo app uploads each feedback submission as a multipart `POST` to the feedback worker, with a determinate progress bar and graceful retry on failure. The submission carries an automatic context snapshot — app version, Android version, device, locale, free RAM, and the **exact demo / navigation route** the feedback is about — and a confirmation screen shows the created GitHub issue number with a tap-through link. The worker base URL is a single configurable `BuildConfig` field (`FEEDBACK_WORKER_URL`). ([#1934](https://github.com/sceneview/sceneview/issues/1934))

### Changed

- Secondary Camera (PiP) demo: added an **Orbit** chip that flies the picture-in-picture camera around the model on its own, independently of the user's main-view orbit. This makes the per-instance `cameraNode` binding *visibly* independent — one scene, two cameras moving on their own — instead of just parking the PiP at a fixed angle ([#1256](https://github.com/sceneview/sceneview/issues/1256)).
- **Consolidated the two lighting demos into one ([#1444](https://github.com/sceneview/sceneview/issues/1444)).** The Android demo's `lighting` ("Light Types") and `movable-light` ("Movable Light") cards were near-identical — same helmet model, same topic — so they are merged into a single **Lighting** demo with an in-demo segmented-button mode switch: *Light Types* (directional / point / spot, intensity, colour) and *Movable Light* (drag to orbit the light). No feature is lost — every control from both demos is still present. The Samples tab now carries one lighting entry instead of two. The retired `sceneview://demo/movable-light` deep link keeps working: `DeepLinkRouter` aliases it to `lighting` via a new `DEMO_ID_ALIASES` table.
- Demo app: `DemoScaffold` now exposes an opt-in `onReset` parameter that renders a consistent, always-in-the-same-place **Reset** action in the demo's top app bar, giving every demo a predictable path back to its initial state and re-arming its core interaction. A brief confirmation snackbar ("Demo reset — ready to try again") tells the user the demo is ready for re-interaction. Wired into the owner-flagged AR Depth Occlusion demo. (#1966)

### Fixed

- **Post-Processing demo now makes SSAO visibly flagrant ([#1443](https://github.com/sceneview/sceneview/issues/1443)).** The damaged-helmet model is staged sitting on a plain matte ground plane instead of floating in the void, and the camera is raised to an angle that frames the floor. SSAO darkens the contact zone between the helmet and the plane, so toggling the SSAO switch now makes a soft contact shadow plainly appear and disappear — the post-processing difference reads at a glance instead of being a subtle change easy to miss.
- Cloud Anchors demo: renamed the setup runbook `STREETSCAPE_SETUP.md` → `ARCORE_CLOUD_SETUP.md` so a *Cloud Anchor* demo no longer routes `ERROR_NOT_AUTHORIZED` users to a Streetscape-named doc, and updated all 14 references across the demos, `arsceneview`, `build.gradle` and `llms.txt` ([#1614](https://github.com/sceneview/sceneview/issues/1614)). The on-screen Host/Resolve actions already shipped via `SceneActionBar` in #1986.
- In-app feedback (Android demo): hardened the screen-recording feedback feature after a review — capped the recording so a long clip can no longer 413, added an in-demo feedback entry point, fixed the demo-id context key, made the upload error messages specific, and survived process death mid-flow ([#1930](https://github.com/sceneview/sceneview/issues/1930)).
- feedback-worker: closed security + correctness blockers from review — enforce the 30 MB upload cap before buffering the body (streaming guard + `Content-Length` validation), SHA-256 IP hashing in the rate limiter, fenced-code Markdown rendering of user text/transcripts on the public issue, base64 Whisper input verified + multi-MB-safe, orphaned-R2 cleanup on D1 failure, incremental retention cron, cached GitHub installation token, and admin-token brute-force rate limiting ([#1930](https://github.com/sceneview/sceneview/issues/1930)).
- **CI Gate stopped failing every PR ([#2013](https://github.com/sceneview/sceneview/issues/2013)).** The `CI Gate` workflow shelled out to `.github/scripts/ci-gate-aggregate.sh` with no `actions/checkout` step, so the helper was never on disk and the gate died with "No such file or directory" on every PR — the real cause of the v4.13.0 admin-merge spree. A checkout step was added. The aggregator also now drops advisory checks (e.g. `Coverage (advisory)`) from its *pending-wait* set, not just from the failure verdict, so a slow or hanging advisory job can no longer push the gate past its deadline.
- **Release / docs workflows survive a failed Pages-rebuild trigger ([#2014](https://github.com/sceneview/sceneview/issues/2014)).** A non-`201` response from the GitHub Pages build API (e.g. an expired `PAGES_REBUILD_TOKEN` returning HTTP 401) is now a loud warning instead of a hard failure — the release/docs artifacts already published, so the auxiliary rebuild trigger must not fail the run.
- **Honest capability badges for the Flutter/RN bridges ([#909](https://github.com/sceneview/sceneview/issues/909)).** The Flutter and React Native demo apps and READMEs no longer over- or under-state what the bridges actually expose. The Flutter demo's About tab gains a tri-state "Bridge Coverage" list (Android + iOS / Android only / Not yet bridged), the RN demo's AR tab labels `depthOcclusion` / `instantPlacement` as "Not yet bridged" since those props are accepted but never applied to the ARCore `Config`, and every README now carries a coverage map. Stale `v3.6.1` version strings in the Flutter demo were corrected.
- `verify-sketchfab-key.sh`: dropped `curl -f` from the live API probe so the real HTTP status reaches the `case` — the `401|403` "token revoked" branch was unreachable and a revoked Sketchfab key silently passed the release guard.
- `docs.yml`: ref-scoped the workflow concurrency group (`pages-${{ github.ref }}`) so a release tag's two triggers (`push` + `release`) no longer self-cancel mid-deploy, while same-ref dedup is preserved.
- Removed the dead `.github/scripts/ar-emulator-screenshots.sh` — it had no caller anywhere in the repo.
- `telemetry-ci.yml`: added `branches: [main]` to the `push:` trigger so it no longer runs on every branch push.
- `check-workflow-scripts.sh`: now scans every workflow `if:` expression and fails on a context disallowed in `if:` (notably `secrets`) — the class of invalid-`if:` bug behind the v4.13.0 release startup-failure.
- `collate-changelog.sh`: the preamble splice now keeps every line before the first `## ` section instead of emitting only line 1, so intro prose between `# Changelog` and the first section is no longer dropped on release.
- Fixed the release pipeline: the `secrets` context is not allowed in a GitHub Actions step `if:` expression, which made `release.yml` (and `docs.yml`) invalid workflow files and blocked the v4.13.0 publish. The token-presence check is now done inside the step's `run:` script.

### Tests

- **Device-QA screen recording moved to the host-side emulator console ([#1671](https://github.com/sceneview/sceneview/issues/1671)).** New `android_cli_screenrecord_*` helpers use `adb emu screenrecord`, which is immune to the Emulator 36.x gfxstream regression that recorded `-gpu host` Filament content as near-empty — so the QA emulator drops the 35.6.11 version pin and runs the latest emulator.
- **Android demo QA — emulator boot snapshots.** `setup-ar-emulator.sh` gains `--seed-snapshot` / `--no-snapshot`: a clean post-ARCore-install boot snapshot (`qa-clean`) is seeded once and cold-booted from on every subsequent QA run with `-no-snapshot-save`, so runs start from an identical warm state and the AVD userdata partition no longer degrades after ~6 runs. Faster, deterministic local QA. Android Studio Journeys was assessed but deferred — it requires an AGP 9.0.0 bump (#1672).
- Web device-QA WebXR coverage now drives a full `immersive-ar` / `immersive-vr` session against the IWER emulated device — requests the session, runs the XR animation frame loop, nudges pose/controllers and ends it — replacing the fixture-pending soft-skip, so a WebXR-plumbing regression fails the suite instead of silently skipping (#1674, #1748).
- Device-QA: the Android leg now screen-records each run via host-side `adb emu screenrecord`, completing cross-platform parity with the iOS (`simctl io recordVideo`) and web (Playwright `page.screencast`) legs. Host-side capture is immune to the Emulator 36.x gfxstream regression that recorded `-gpu host` Filament content as near-empty. The Android and iOS QA recordings are now surfaced into `device-qa-artifacts/` alongside the web screencasts.
- Added a non-AR demo regression suite — pure-JVM state-machine tests for `AnimationDemo`'s cinematic camera scripts and a demo-registry integrity check — plus `samples/android-demo/DEMO_TESTING.md` documenting the three test layers (#880).

### Docs

- Privacy disclosures updated for the opt-in in-app feedback feature: the demo app's privacy policy (`.github/PRIVACY_POLICY.md`, `docs/docs/privacy.md`, website `privacy.html`) now discloses screen + microphone capture, device/app context, Cloudflare Workers AI (Whisper) transcription, private Cloudflare R2 storage, the 90-day retention window, and that a public GitHub issue carries only the transcript + context. Adds a Play Store **Data safety** reference doc (`samples/android-demo/distribution/play-store/DATA_SAFETY.md`) for the maintainer to transcribe into the Play Console. ([#1935](https://github.com/sceneview/sceneview/issues/1935))
- `llms.txt`: added an explicit **"Web API model — builder DSL, NOT a Node scene-graph"** section to the SceneView Web reference, with a concept-mapping table (Android/iOS `Node` ↔ Web builder DSL) and correct-vs-incorrect code examples. This stops AI assistants from generating Android-style `Node`-tree code that does not compile against `sceneview-web`, and documents that a node scene-graph for Web is a tracked v5 milestone effort ([#895](https://github.com/sceneview/sceneview/issues/895)).

## v4.13.0 — 2026-05-21

### Added

- **AR Augmented Images — on-device runtime registration.** New `RuntimeAugmentedImageDatabase`
  helper (`rememberRuntimeAugmentedImageDatabase()`) lets you register a brand-new reference
  image at runtime — e.g. from a photo the user just took — without a pre-bundled `arcoreimg`
  database. `addImage(name, bitmap, widthInMeters)` runs the ARCore feature extraction off the
  main thread and re-applies the session config on the main thread itself, returning a typed
  `AddImageResult` (`Added` / `LowQuality` / `Error`) so low-quality captures are recoverable.
  New `Frame.captureCameraBitmap()` and `Image.toArgbBitmap()` extensions grab the live AR
  camera frame as an upright `ARGB_8888` bitmap ready for the database. The Image Tracking demo
  now ships a "Capture this view" button demonstrating the full on-device flow (#1553).
- Record & Playback demo now surfaces live ARCore tracking quality while recording — a status pill, a "tracking lost" soft warning, and a per-take "tracking healthy X% of frames" stat — so a capture going bad (e.g. shot from a moving vehicle) is obvious in real time instead of only on playback (#1650).
- CI: daily `maintenance.yml` job that monitors Android App Links + iOS/macOS Universal Links verification health — cross-checks the hosted `assetlinks.json` / `apple-app-site-association` against the committed source of truth and the demo apps' intent-filters/entitlements, opening a tracking issue when the QR → demo deep-link flow is broken (#1695).
- `PlacementScene` composable (#1765) — one-line tap-to-place AR scene with Sceneform `ArFragment` parity: bundles `ARSceneView` + plane rendering + a built-in centre-screen reticle + tap-to-place anchor creation + an instant-placement fallback, so callers only declare what rides each placed anchor. New `Placement Scene` demo in `samples/android-demo`.
- `PointCloudNode` + `rememberPointCloud()` (#1773): renders ARCore's live tracking feature points (`Frame.acquirePointCloud()`) as an in-scene Filament point cloud — AR Foundation `ARPointCloudManager` parity — with a configurable color and confidence filter. Ships a new `Point Cloud` AR demo.
- `PlaneNode` composable + `rememberDetectedPlanes` lifecycle helper for `arsceneview` (#1774): react to ARCore detected-plane lifecycle (`onAdded`/`onUpdated`/`onRemoved`) declaratively from Compose — the SceneView equivalent of AR Foundation's `ARPlaneManager.planesChanged` — instead of hand-rolling a `frame.getUpdatedTrackables(Plane::class.java)` loop. New "Plane Lifecycle" demo in `samples/android-demo`.
- `MaterialLoader.createOcclusionInstance()` — invisible, depth-writing material (RealityKit `OcclusionMaterial` / Sceneform `makeOcclusionMaterial` parity). Compose helper `rememberOcclusionMaterialInstance` ships in `samples/common`. New "Occlusion Material" demo in the Android demo app (Advanced category). For AR scenes that want occlusion against the live depth camera, keep using `ARCameraStream.isDepthOcclusionEnabled`. (#1776)
- Scene Semantics label-overlay material (#1868, follow-up of #1730): a new `semantics_overlay.filamat` Filament material colour-codes ARCore's per-pixel 12-class outdoor segmentation, exposed via `MaterialLoader.createSemanticsOverlayInstance(texture, opacity)` plus `MaterialInstance.setSemanticsTexture` / `setSemanticsOpacity`. `ARSceneSemanticsDemo` now renders the live segmentation as a camera ↔ semantic blend overlay (with a colour legend) alongside the existing top-3 label HUD.
- **`ReticleNode` library-level placement reticle ([#1882](https://github.com/sceneview/sceneview/issues/1882)).** New `arsceneview` node + `ARSceneScope.ReticleNode { ... }` Composable for the "tap to place" UX every AR placement demo previously had to reinvent. `ReticleNode` is a **thin wrapper** over `HitResultNode` — it delegates the screen-coordinate hit test (including #1891's plane-only defaults and the 30 cm `minCameraDistance` floor) to `HitResultNode` and adds only the `onHitResultChanged` callback so callers can drive an "aim at a surface" hint and capture the last-known hit on tap-to-place without attaching a duplicate hit test in `onSessionUpdated`. Auto-hide on no-hit comes for free from `HitResultNode`'s trackable/visibility contract. Visual marker is left to the caller as a child node so the reticle stays material/style-agnostic. Documented in `llms.txt` (and the docs mirror) + `sceneview-mcp` bundle.
- Jetpack XR hand tracking (Slice 2, #1902): new preview `XrHandNode` mirrors an `androidx.xr.arcore.Hand` as a scene-graph node with one child node per skeleton joint, a `SceneScope.XrHandNode` composable, the JVM-testable `XrHandSkeleton` joint/bone math, and an `ar-hand-tracking` demo that renders a static reference skeleton on non-XR phones.
- `XrFaceNode` — Jetpack XR face tracking (`androidx.xr.arcore.Face`) for Android XR headsets, the preview sibling of `AugmentedFaceNode`, plus the runtime-free `XrFaceMesh` adapter and an `ar-xr-face` sample demo (#1903).

### Changed

- AR plane visualization redesigned — the dated dense dot-grid overlay is replaced by a modern procedural soft grid with anti-aliased lines and a feathered edge fade (#1616).
- AR Pose Placement demo now places a real bundled Lantern model instead of a placeholder cube/sphere and shows the live X/Y/Z coordinates as in-scene text. (#1618)
- Demo app UX-consistency pass (#1620 thread 1): dropped low-value Settings sheets — demos with no real controls (`OrbitalARDemo`, `ARMLObjectLabelDemo`) no longer show a Settings FAB, AR demos whose sheet only held the dev-only `ForceTrackingFailureMenu` (`ARStreetscapeDemo`, `ARImageDemo`, `ARSceneSemanticsDemo`) now show the FAB only in QA mode, and the verbose "How to test" help cards in `ARDepthOcclusionDemo` / `ARImageStabilizationDemo` were trimmed to a one-line hint so the sheet is just the real toggle. Status/device-support text that was buried in sheets is now surfaced on-screen.
Consolidated the duplicate Play Store listing directories into a single `samples/android-demo/distribution/play-store/en-GB/` source of truth (text + `graphics/`), and extended the `play-store.yml` listing-sync to upload the feature graphic and screenshots via the Play `edits.images` API so they reach the store automatically on release (#1710).
- Upgrade detekt 1.23.8 (silently no-op'd on Kotlin 2.3.x) → detekt 2.0.0-alpha.0; per-module baselines committed under `buildSrc/config/detekt/baseline-<module>.xml` grandfather existing violations and the `Detekt` CI step is now blocking on NEW violations (#1740).
- Release builds of the demo apps now fail loud when `SKETCHFAB_API_KEY` or `ARCORE_API_KEY` is empty (#1915): the Android `assembleRelease`/`bundleRelease` path and the iOS `Release` archive abort with a clear actionable error instead of silently shipping a store build with invisible Sketchfab carousels (the #1909 silent-fail class). Debug builds stay permissive; forks opt out with `SV_ALLOW_MISSING_SECRETS=1`.
- Pruned the unused `focusPoint`/`radius` spotlight parameters from `plane_renderer.mat` (#1922): these declared a half-built "spotlight around the focus point" effect whose fragment-shader falloff and `PlaneRenderer.kt` setter were both already commented out, so they never affected rendering. The `.mat` source, the orphaned Kotlin constants/`getFocusPoint(...)` helper, and the regenerated `plane_renderer.filamat` blob are all updated together — no behaviour change.
- CI: split JaCoCo coverage off the PR-blocking unit-test job (#1955) — the blocking `Unit tests` job now runs the plain `testDebugUnitTest` suite (fast, deterministic, 30-min timeout), while JaCoCo instrumentation + reports run in a separate non-blocking `Coverage (advisory)` job, so a slow runner can no longer push the unit-test gate over its timeout and turn `CI Gate` double-red.
- Demo app: enforced one action-placement rule — every demo's primary action (Host, Drop, Place, Record, Clear, Reset) is now an on-screen button via the shared `SceneActionBar`, while only secondary configuration (toggles, pickers, the Cloud Anchor ID field) stays in the Settings sheet (#1964).
- Added labelled "Camera distance" sliders to the `CameraControlsDemo` and `ModelViewerDemo` Android demos (#1965): zoom was pinch-only — now discoverable and Maestro-testable (no pinch in Maestro) — and the sliders complement pinch-to-zoom rather than replacing it. `ModelViewerDemo`'s slider drives the same `DemoSettings.cameraDistance` deep-link hook (#1571).
- Build: `samples/android-demo`'s `GeneratedDemos.kt` is no longer committed — it is `.gitignore`d and regenerated before Kotlin compilation by the new `generateDemoRegistry` Gradle task, killing the per-PR merge-conflict class that hit every demo-adding PR (#1976).

### Fixed

- Streetscape Geometry demo now surfaces clear "go outdoors" guidance after 15 s with no geometry, instead of spinning forever on "Looking for streetscape geometry…" indoors (#1615).
- Depth occlusion now actually occludes (#1617): `ARCameraStream` draws the depth-aware camera quad **first** (Filament priority 0) when occlusion is enabled so the real-world depth written via `gl_FragDepth` primes the z-buffer before virtual geometry is depth-tested — previously the quad was always drawn last (priority 7), writing real-world depth too late to ever hide a virtual model behind real furniture.
- AR demos + docs polish (#1777): `ARDepthOcclusionDemo` now shows a transition spinner while the depth toggle rebuilds the camera stream (+ a `connectedAndroidTest` that flips depth mode 10× and asserts stability); `LightEstimator` gains an `enableColorCorrection` toggle and exposes the raw `lastColorCorrection` triple; `sessionConfiguration` / `sessionCameraConfig` KDoc now warns about mid-session config swaps; `llms.txt` documents camera-config swapping and editable nodes (`TransformableNode` parity).
- Fix `sceneview.github.io` no longer rebuilding on push: GitHub Pages' legacy auto-build does not fire for the SSH deploy-key pushes `docs.yml`/`release.yml` make, and the existing "Trigger GitHub Pages build" workaround was permanently skipped because its `if:` condition tested an `env:` var set on the same step (not yet in scope) and referenced a non-existent secret. The step now tests `secrets.*` directly, falls back to the existing `PERSONAL_TOKEN`, and fails loudly on a bad API response; a daily `maintenance.yml` job alerts if the live site lags its source by more than a day (#1826).
- Spatial Audio demo (Android): the bouncing sphere is now clearly visible — larger radius, brighter on-brand material, closer camera framing, and a two-light key/fill setup. (#1927)
- In-app update flow in `samples/android-demo` is now demo-UI-native (#1941): `InAppUpdateManager.checkForUpdate()` no longer auto-starts the Google Play consent modal on resume — it only surfaces an integrated Material 3 `UpdateBanner` ("A new version is available"). A new `InAppUpdateManager.startUpdate()` triggers Google's single consent dialog, called solely on the user's deliberate tap of the in-app "Update" button. This fixes the double-modal (a second `onResume` in the `AVAILABLE` window is now a no-op), the "feels like leaving the app" jarring unprompted popup, and the flaky "Restart" button — `completeUpdate()` is now a no-op unless the install is `READY_TO_INSTALL` and the install-state listener stays registered until `INSTALLED`.
- Hardened the demo apps' in-app update flow (#1942 follow-up): cancelling Google's flexible-update consent modal is now delivered via an `ActivityResultLauncher` (`startUpdateFlowForResult`), so a cancel resets the banner to a retryable state instead of stranding the Update button; the in-app update info is kept until the download is confirmed started so a cancelled flow can be retried; a `destroyed` guard stops late Play Core callbacks from mutating state after `onDestroy`; the manager re-attaches to an already-running download after a rotation; and the Android TV banner's new "Update" button is now reachable by D-pad.
- Web Spatial Audio demo Play button now actually plays (#1944): the `samples/web-demo` Spatial Audio panel's `#audio-play` / `#audio-stop` buttons were dead — clicking Play produced zero Web Audio API activity. The wiring lived only in the Kotlin/JS `Main.kt::setupSpatialAudio()`, but `index.html` ships the hand-written `js/sceneview.js` runtime, not the Kotlin/JS bundle, so that code never executed. The buttons are now wired in the inline-JS runtime alongside the other tab demos: a click constructs the `AudioContext` (inside the user gesture, per the autoplay policy), `fetch`+`decodeAudioData`s the bundled `audio/bell.wav`, builds the `AudioBufferSourceNode -> PannerNode("HRTF") -> GainNode` graph, starts looping playback, and orbits the panner around the listener — matching the Android/iOS Spatial Audio demos. A new Playwright regression test (`tests/audio.spec.ts`) hooks the Web Audio API before page load and asserts the graph is constructed on a real `#audio-play` click, so CI catches a future regression.
- Post-v4.12.0 audit polish (#1957): `OpenGL.createEglContext()` now reports a descriptive `EGL context creation failed` error instead of a bare `!!` NPE; `cameraConfigFilter { }` gains scalar `targetFps(…)` / `depthSensor(…)` / `stereoCamera(…)` convenience functions so single-sensor filters no longer need `setOf(…)` (the `Set` API from #1844 is unchanged); malformed `Geometry` vertex lists with partial attribute declarations now fail with a named-attribute error instead of an opaque render-time NPE; and two edge cases gained regression tests — `DepthMeshNode.computeAabb` with an identical-Z depth frame, and the `Frame.hitTestDepth` zero-width / zero-focal-length intrinsics guard.
- The `.well-known/` deep-link manifests (`assetlinks.json` + `apple-app-site-association`) are now deployed to the live site — the website assembly step's `cp website-static/*` glob silently skipped the dot-prefixed directory, so Android App Links / iOS Universal Links auto-verification returned HTTP 404 (#1998).
- CI workflow hardening (#1702, #1708, #1984): the `CI Gate` aggregator no longer red-lights a PR when an **advisory** check (e.g. `Coverage (advisory)`) is `CANCELLED` / `SKIPPED` / `FAILURE` — the pass/fail decision now excludes any check whose name matches an `ADVISORY_CHECKS` substring, for all conclusions (a transient concurrency-cancel of advisory `Coverage` had blocked otherwise-mergeable PR #1889 for ~3h). The decision logic is factored into `.github/scripts/ci-gate-aggregate.sh` with a regression suite (`test-ci-gate-aggregation.sh`, wired into `ci.yml` repo-hygiene) covering the CANCELLED-advisory case. Also confirmed and pinned: `docs.yml` uses matching `upload-artifact`/`download-artifact@v7` pairs (no `@v8` mismatch), and `ci.yml`'s `quality-gate` job uses the shared `setup-gradle` composite action so it gets the `gradle-wrapper.jar` SHA validation supply-chain guard like every other Gradle job.

### Removed

- Removed the dead Kotlin/JS source set of `samples/web-demo` (#1946): the web demo's `src/jsMain/.../Main.kt` built a `web-model-viewer.js` bundle that `index.html` never loaded — the shipped page has always run on its hand-written inline `<script>` + self-hosted `js/sceneview.js`. The dead `Main.kt`/`WebXRParityDemos.kt`, the Kotlin/JS Gradle wiring (`build.gradle.kts`, `webpack.config.d/`, the `:samples:web-demo` `settings.gradle` include) are gone; the static deliverable moved from `src/jsMain/resources/` to `samples/web-demo/site/`. The web demo is now a plain static site with one source of truth (the inline JS) — `docs.yml` deploys it with a verbatim file copy and the Playwright suite serves it directly. Root cause of #1541 and #1944.

### Tests

- QA: `web-perf-qa.sh` now enforces a tuned Lighthouse perf budget (mobile preset — FCP/LCP/CLS + perf-score) instead of always emitting an advisory verdict, and `device-qa.sh` records the result as an advisory `web-perf` leg so a budget breach surfaces in `device-qa-report.json`'s release gate (#1898, follow-up of #1879).
- Registered the new `occlusion.mat`/`occlusion.filamat` (added by #1832) in the `tools/GenerateFilamat.sh` inventory under a new Profile E (`-a vulkan -a opengl -p mobile`), so the `.filamat` ABI drift guard now covers all 21 material blobs (#1949).

### Docs

- Added a "Hand / Face / Body tracking parity" table to the iOS cheatsheet mapping mobile ARCore, Jetpack XR (`XrHandNode`/`XrFaceNode`), ARKit phone, visionOS, and WebXR — and cross-linked it from the Jetpack XR integration design notes (#1904).
- Removed the stale `website-static/llms.txt` (pinned at v4.0.9, 12 versions behind the canonical root `llms.txt`) so the deployed `sceneview.github.io/llms.txt` always serves the current API reference (#1956); added cross-platform parity rows for v4.12.0 Spatial Audio (#1900) and Haptic Feedback (#1901) to the iOS and Android cheatsheets, stating the real iOS / Web maturity (#1958).
- Corrected the stale node-type count across `README.md`, the website, structured data, docs, and the MCP docs to the actual 41 (24 3D + 17 AR) — previously claimed 35-39 and missed the AR `PointCloudNode`, `PlaneNode`, and `ReticleNode` additions.

## v4.12.0 — 2026-05-21

### Added

- Auto-fit camera framing (#1439): a new library-level helper in `io.github.sceneview` computes the orbit distance at which a model's bounding sphere exactly fills the viewport, regardless of the model's intrinsic glTF size. `fitDistanceForBounds(bounds, verticalFovDegrees, aspect, padding)` is pure trigonometry (yaw-invariant — fits the bounding sphere, not the raw box); `CameraNode.frameToContent(node)` / `CameraNode.frameToBounds(aabb)` reposition the camera in one call; `verticalFovDegreesForFocalLength` and `Box.toAabb()` convert Filament's focal-length / `Box` types; `SceneAutoFitState` is a one-shot guard for use in a `SceneView` frame loop. The Model Viewer demo now auto-fits its orbit radius to the displayed model — a 5 cm bee and a 5 m crate are framed identically without per-demo `scaleToUnits` tuning. Android-only for now; iOS already frames from `visualBounds` (#1026 / #1391).
- `arsceneview`: Environment-aware AR fog — `ARFogNode` (in `io.github.sceneview.ar.node`) blends the live camera passthrough toward a coloured haze using the ARCore depth image, so distant real-world surfaces fade while near ones stay crisp. Mirrors `FogNode`'s `density` / `color` / `enabled` parameters so the same numbers fog both real and virtual geometry visually consistently, plus AR-only `start` / `end` distance bounds. Inspired by ARCore Depth Lab's *AR Fog* sample. Opt-in, off by default — collapses to a no-op when `enabled = false` (zero shader cost via a branchless `fogEnabled` gate). Requires `Config.DepthMode.AUTOMATIC` (or `RAW_DEPTH_ONLY`) and `ARCameraStream.isDepthOcclusionEnabled = true`. The depth-aware camera material (`camera_stream_depth.mat`) was extended with the fog term and its `.filamat` blob recompiled with the matching matc 1.71.0 toolchain — see [`CONTRIBUTING.md`](../CONTRIBUTING.md#filament-runtime---filamat-abi-invariant). Demo: new `ARFogDemo` in `samples/android-demo` (deep link `sceneview://demo/ar-fog`), with sliders that drive both the real-world fog and a virtual `FogNode` in lockstep so the parity is visible side-by-side (#1717).
- New `ARMLObjectLabelDemo` in the Android demo app — ML Kit object detection on the AR camera feed, with 3D billboard labels anchored at detected real-world objects via depth hit-tests. Uses the bundled offline ML Kit model (`com.google.mlkit:object-detection`), so the demo works without any extra asset download. Ships alongside a new `Frame.cameraImage()` extension on `arsceneview` exposing the YUV CPU image for ML / CV pipelines. (#1737, #1733)
- `arsceneview`: surfaced 11 ARCore `Config.*Mode` enums as typed DSL params on `ARSceneView` — `planeFindingMode`, `depthMode`, `instantPlacementMode`, `geospatialMode`, `streetscapeGeometryMode`, `cloudAnchorMode`, `augmentedFaceMode`, `imageStabilizationMode`, `semanticMode`, `updateMode`, `focusMode`. Each defaults to ARCore's recommended value, is applied to the `Config` BEFORE the `sessionConfiguration` callback (so the callback still wins as an escape hatch), and is reactive — flipping a param via Compose state reconfigures the running session without recreating `ARSceneView`. Demos (`ARCloudAnchorDemo`, `ARInstantPlacementDemo`, `ARPlacementDemo`) migrated off the raw `sessionConfiguration` callback (#1766).
- **sceneview-web:** WebXR feature parity composables — `XRDepthInfo` + `DepthOcclusionShader` (depth-sensing), `XRHandNode(handedness).joint(Joint.INDEX_TIP) { ... }` (hand-tracking, 25 joints), `XRImageTrackingNode(index = 0)` (image-tracking with the new `XRFeature.IMAGE_TRACKING` constant), `XRAnchorNode(xrAnchor)` (anchors). Mirrors the Android `arsceneview` composables. `XRFrame` gains the `getDepthInformation(view)` and `getImageTrackingResults()` extensions plus a `trackedAnchors` accessor (#1778, part of #1754).
- New `SpatialAudioNode` (Android + iOS + Web) — positional 3D audio attached to scene nodes with inverse/linear distance falloff. Each node owns its own player so two nodes never cross-talk. Android phase-1 per-node `MediaPlayer` backend (`Spatializer` in phase 2); iOS RealityKit spatial audio; Web Audio `PannerNode` HRTF. Phase 1 of #1900 — drive the listener with `setSpatialAudioListenerPose(position, forward, up)` from the render loop; automatic camera tracking is phase 2.
- New `rememberHapticFeedback()` (Android) + `SceneViewHaptic` (iOS) + Web `navigator.vibrate` fallback. 7 presets (light/medium/heavy/success/warning/error/selection) + `continuous()` + `pattern()` + `cancel()`. `continuous(intensity, durationMs)` takes a millisecond `Int` on every platform — Android, iOS and Web — so cross-platform callers pass the same value. Library API replaces ad-hoc per-demo wrappers. Phase 1 of #1901; NodeGesture modifiers + AR event modifiers come in phase 2 / phase 3.
- Filament materials CI guard (#1912 Part B): `tools/GenerateFilamat.sh` is rewritten to resolve the pinned matc version from `gradle/libs.versions.toml`, download + cache the matched matc tarball under `~/.cache/sceneview/matc-<version>/`, and compile every `.mat` source with its profile-specific flag list. A new `--check` mode regenerates each blob to a tmp dir and byte-diffs against the committed `.filamat`, exiting non-zero on drift; the gate is wired into `.claude/scripts/quality-gate.sh` so PRs that ship a `.mat` edit without a matching `.filamat` recompile are now blocked automatically. The smoke recompile surfaced and fixed three `website-static/` blobs that were still compiled against matc 1.70.x while the runtime moved to Filament 1.71.0.

### Changed

- **API consistency polish ([#1844](https://github.com/sceneview/sceneview/issues/1844)).** Tier-2 Wave-4 follow-ups bundled into one release surface:
  - `ARSceneView(onSessionFailed = …)` soft-deprecated in KDoc in favour of the typed `onSessionFailure` (#1759). Both still fire when set; the legacy callback stays available indefinitely for backwards compatibility.
  - `rememberARPlaybackStatus` ported to the `produceState` idiom — matches `rememberCameraGeospatialPose` / `rememberEarthState` instead of the bespoke `LaunchedEffect + mutableStateOf` pair.
  - `ARSceneScope.DepthHitResultNode` adds a custom `hitTest: (Frame) -> DepthHitResult?` lambda overload — mirrors `HitResultNode`'s 2-overload surface. Apps wanting multi-pixel / moving-reticle depth selection no longer have to subclass the node.
  - `cameraConfigFilter { … }` DSL: `depthSensor` and `stereoCamera` are now `Set<…>?` instead of singletons — symmetric with `targetFps` and with the underlying ARCore `set*(EnumSet)` API. `setOf(X)` keeps the singleton case ergonomic. Empty sets fail fast (validation moved out of #1845).
  - Cheatsheets refreshed: `docs/docs/cheatsheet.md` absorbs the Wave-4 `ARSceneView(onSessionFailure / playbackDatasetUri / flashMode)` parameters; `docs/docs/cheatsheet-ios.md` lists every new Android-only API surface so AI agents stop emitting iOS code referencing `ARSessionFailure`, `DepthHitResultNode`, `cameraConfigFilter`, `Frame.cameraImage()`, `rememberARPlaybackStatus`, or `ARRecorder.addTrack / recordTrack / State.IO_ERROR`.
  - Changelog fragments gain a `Performance` category — covers pure perf wins (#1810-style) that don't fit `Fixed` or `Changed`. `.claude/scripts/collate-changelog.sh` recognises it.
- **`mcp/src/generated/llms-txt.ts` is now build-generated, not committed ([#1928](https://github.com/sceneview/sceneview/issues/1928)).** The ~230 KB embedded `llms.txt` bundle is `.gitignore`d and regenerated by the `prebuild` / `prepare` / `test` npm lifecycle scripts, removing the guaranteed merge conflict every parallel PR that touched `llms.txt` used to hit. The published `sceneview-mcp` tarball still ships the compiled `dist/generated/llms-txt.js`.

### Fixed

- **AR demos render placed PBR models as flat-black silhouettes ([#1611](https://github.com/sceneview/sceneview/issues/1611)).** Two fixes to the `ARSceneView` IBL path. (1) The baseline `environment.indirectLight` is now applied via `LaunchedEffect(environment)` instead of `SideEffect` — demos that surface per-frame ARCore state to UI state (`latestFrame`, `isTracking`) used to recompose every frame and silently reset `scene.indirectLight` back to the baseline, dropping the per-frame rebuilt IBL produced by ARCore's `ENVIRONMENTAL_HDR` estimate. (2) The per-frame rebuild is now gated by `shouldRebuildIndirectLight(estimation, baseIndirectLight)` so partial estimations (e.g. reflections cubemap in flight, irradiance SH not yet stable) skip the rebuild instead of producing an IBL with empty irradiance OR empty reflections — KTX1-loaded baselines expose SH via the native handle (no `irradianceTexture`), so the legacy fallback returned a no-IBL builder and Filament collapsed diffuse PBR to black. Pinned by 4 new pure-JVM cases in `IndirectLightRebuildDecisionTest`. Verified on Pixel 9: placed Damaged Helmet, Fox, Lantern, Toy Car, Shiba and streamed Sketchfab models now render lit on first frame instead of as flat silhouettes.
- `DepthMeshNode.uploadGeometry` no longer caches the direct `ByteBuffer` used for `VertexBuffer.setBufferAt` / `IndexBuffer.setBuffer`. Filament's JNI captures a global ref to the buffer and copies ASYNCHRONOUSLY on the render command stream; reusing the buffer across uploads (introduced by the #1810 perf opt) could clobber bytes Filament had not yet consumed → torn vertex uploads, corrupted mesh frames. Reverted to a fresh `ByteBuffer.allocateDirect(...).order(nativeOrder())` per upload — matches the standard pattern used by every other Geometry.kt caller. A follow-up issue can revisit the perf cost with a callback-based ring buffer if profiling shows the GC churn matters in practice. Closes #1841.
- **Sketchfab missing-API-key defensive layer ([#1909](https://github.com/sceneview/sceneview/issues/1909)).** When the release build ships with a blank `SKETCHFAB_API_KEY` secret, the Android + iOS demos now surface a neutral "Sketchfab carousels disabled — API key missing" banner in the Explore tab (with a tap-to-explain dialog) instead of silently rendering empty carousels + a dead search bar. CI release pipelines (`build-apks.yml`, `play-store.yml`, `app-store.yml` iOS + macOS archives) fail-fast on tag pushes when the secret is empty/blank or rejected by `GET /v3/me`, via the new `.claude/scripts/verify-sketchfab-key.sh` (mirrors the existing ARCore-key guard from #1177). Debug builds also emit a single Logcat / `os.Logger` WARN pointing at the `local.properties` / scheme env-var workaround. Note: the actual secret rotation that re-enables Sketchfab features in shipped releases is a manual GitHub Secrets action — this PR delivers the defensive layer so a future regression is loud at CI time and visible to users.
- `.claude/scripts/quality-gate.sh`: The `LARGE_FILES` check no longer aborts the whole quality gate under `set -euo pipefail` when staged files are under the 10 MB threshold (the common case). Restructured the per-file `&&` chain into nested `if` blocks so the size comparison returning false stays local to the loop iteration instead of propagating through `pipefail` and bailing the script via `set -e`. Local pre-push runs now reach the final `Quality Gate Summary` block as intended (#1914).
- Filament materials audit Part A (#1918): removed the orphaned `view_renderable` material — its `.mat` source and the ~114 KB `view_renderable.filamat` blob shipped in every APK despite no code path ever loading it (superseded by `view_texture_lit`/`view_texture_unlit`). The static audit of all 21 material sources confirmed **no dangling parameter reads** (no Kotlin call references a parameter the `.mat` does not declare) and **no leaked `MaterialInstance`s** — every `createInstance(...)` is tracked by `MaterialLoader`, `ARCameraStream`, or `PlaneRenderer` and destroyed on teardown. Added `website-static/materials/README.md` documenting the deliberate web-vs-Android divergence, and reviewed the A-vs-B matc flag-profile split as intentional.

### Tests

- Web device-QA: `assertRendered()` in `catalog.spec.ts` and the non-blank check in `render.spec.ts` are now HARD failures (no more soft-warn). Two complementary signals must hold: WebGL context alive (`gl.isContextLost() === false`) and compositor screenshot shows non-flat luminance variance. Combined with the `--enable-unsafe-swiftshader` Chromium flag landed earlier, this closes the green-on-nothing risk on GPU-less CI runners ([#1593](https://github.com/sceneview/sceneview/issues/1593), addresses [#1674](https://github.com/sceneview/sceneview/issues/1674) items 1+2).

### Docs

- Filament materials documentation (#1919 Part C): every `.mat` source now carries a header comment block (purpose, used-by node/loader, per-parameter contract, matc flag profile), and the `CONTRIBUTING.md` "Filament runtime ↔ .filamat ABI invariant" section is updated with the `tools/GenerateFilamat.sh` workflow, the `quality-gate.sh` drift gate, and the four A/B/C/D matc flag profiles.

## v4.11.2 — 2026-05-21

### Added

- `arsceneview`: AR depth-of-field driven by ARCore environment depth — new `arDepthOfField(view, camera, options)` composable + `ARDepthOfFieldOptions(focusDepth, blurStrength, enabled)` data class wire Filament's native DoF post-pass to the same z-buffer that `ARCameraStream`'s depth-occlusion material already writes (`gl_FragDepth` in `camera_stream_depth.mat`), so tapping a near object throws the far background out of focus and vice-versa — both the virtual scene and the camera background blur from the same focus point. No new `.filamat` required. Tap-to-focus helper `Frame.depthFocusDistance(xPx, yPx): Float?` reuses the depth hit-test added in #1712. Opt-in (off by default; zero cost on disabled frames). Requires `Config.DepthMode.AUTOMATIC`/`RAW_DEPTH_ONLY` + `ARCameraStream.isDepthOcclusionEnabled = true`. New `ARDepthOfFieldDemo` in the sample app demonstrates the canonical wiring; `llms.txt` documents the API surface (#1716).
- `arsceneview`: Scene Semantics API — `Config.SemanticMode.ENABLED` is now support-gated via `ARSession.configure` (silently downgrades to `DISABLED` on devices without the on-device ML model, matching the depthMode / flashMode auto-fallbacks), and three new `Frame` extensions expose the per-pixel labels: `Frame.semanticImage(): Image?` (R8 label ordinal raster), `Frame.semanticConfidenceImage(): Image?` (R8 confidence raster), and `Frame.semanticLabelFraction(label: SemanticLabel): Float` (cheap GPU-backed pixel-share query, returns 0f when semantics are off / not yet available). Comes with a new `ARSceneSemanticsDemo` showing a live top-3 label HUD over the camera feed. Outdoor only — the ML model has no indoor training data. The custom `.filamat` label-overlay material is tracked separately as a follow-up (matc toolchain ABI work) (#1730).
- `arsceneview`: surfaced ARCore CPU camera image access via `Frame.cameraImage(): Image?` — a 1-line wrapper around `acquireCameraImage()` returning `null` on `NotYetAvailableException` and documenting the caller-owned `use { }` lifecycle. Unblocks ML Kit / OpenCV / custom CV pipelines. Pair with the new `cameraConfigFilter { facing = …; targetFps = …; depthSensor = …; stereoCamera = … }` DSL on `ARSceneView.sessionCameraConfig` to pick a session-wide `CameraConfig` (resolution, FPS, depth/stereo-sensor usage) without hand-rolling `Session.getSupportedCameraConfigs(filter)`. Falls back to the session's current config when no match exists so session creation never crashes (#1733).
- **Jetpack XR foundation — runtime availability check + integration design ([#1738](https://github.com/sceneview/sceneview/issues/1738)).** Adds `io.github.sceneview.ar.xr.XrFeatures.isAvailable(context)` to gate Android XR (headsets, glasses) code paths, declares the `androidx.xr.arcore:arcore:1.0.0-alpha14` dependency alias, and records the module / runtime decision in `arsceneview/docs/JETPACK-XR-INTEGRATION.md`. Hand tracking node + demo (Slice 2) and Jetpack XR face tracking node (Slice 3) ship in follow-up PRs. Phone-only apps are unaffected — the XR dependency is opt-in and the Perception runtime is reached via reflection.
- `arsceneview`: `sealed class ARSessionFailure` — typed taxonomy covering all 25 ARCore exception subclasses (install, permission, camera, quota, cloud-anchor, augmented-image, recording/playback, session/config) plus an `Other` escape hatch. New `ARSceneView(onSessionFailure: ((ARSessionFailure) -> Unit)? = null)` callback dispatches alongside the legacy raw-`Exception` `onSessionFailed` so apps can do exhaustive `when` matching (the compiler catches missing cases the day ARCore adds a new failure category). Original `Exception` preserved on `.cause` for every subtype. `CloudAnchorNode.onHosted` already passed the specific `CloudAnchorState` (not a binary `isError`), `AugmentedImageNode.trackingMethod` + `onTrackingMethodChanged` already surfaced `FULL_TRACKING` vs `LAST_KNOWN_POSE`, and `Config.addAugmentedImage`'s `ImageInsufficientQualityException` is now routed via the new `ARSessionFailure.ImageInsufficientQuality` subtype. Backwards compatible — existing `onSessionFailed` callers see no change (#1759).
- arsceneview: new `SceneUnderstanding` data class + `ARSceneView(sceneUnderstanding = ...)` parameter that groups four scattered AR rendering flags (`occlusion`, `lighting`, `physics`, `planeVisualization`) into one discoverable knob — mirrors RealityKit's `ARView.environment.sceneUnderstanding.options` for cross-platform parity. The parameter is opt-in (defaults to `null`); when null, the individual flags retain their pre-#1767 defaults. Named constants `SceneUnderstanding.Full`, `.Minimal`, `.None` cover the common configurations. AI assistants now find one parameter instead of four (#1767).
- `arsceneview`: rounded out the ARCore recording/playback surface (#1770).
  - `rememberARPlaybackStatus(session): State<PlaybackStatus>` — Compose State that surfaces `NONE` / `OK` / `FINISHED` / `IO_ERROR` (the **`FINISHED`** transition is the only public end-of-replay signal, useful for rewind / loop / next-dataset logic).
  - `ARRecorder.State.IO_ERROR` — distinct from generic `ERROR`. Set by `recordFrame(session)` when ARCore reports `RecordingStatus.IO_ERROR` (disk full, storage detached, permission revoked mid-recording) so apps can offer a "clear cache and retry" CTA.
  - `ARRecorder.addTrack(uuid, mimeType)` + `ARRecorder.recordTrack(handle, frame, data)` — exposes ARCore's `RecordingConfig.addTrack` + `Frame.recordTrackData` flow for ML annotation / ground-truth / custom sensor packets written inside the same MP4.
  - `ARSceneView(playbackDatasetUri: Uri? = null)` — scoped-storage equivalent of the `playbackDataset: File?` param (Android 10+). Accepts `content://` URIs straight from the SAF picker so apps don't have to copy into app-private storage. Mutually exclusive with `playbackDataset` — setting both throws `IllegalArgumentException`.
- `samples/web-demo` (QA): IWER (Immersive Web Emulation Runtime, `iwer@^2.2.1`) WebXR shim is now injected into the Playwright page via `page.addInitScript(...)` under a Meta Quest 3 emulated device profile, and a new `tests/webxr.spec.ts` clicks `#enter-ar` / `#enter-vr` and asserts no console errors, no WebGL context loss, and no unhandled rejections — closing the WebXR scaffolding gap (#1878, follow-up of #1748). The rich replay test soft-skips until a real recorded XR session fixture is added (separate follow-up, requires a real WebXR-capable device).
- **QA harness: advisory web-perf scaffold ([#1879](https://github.com/sceneview/sceneview/issues/1879)).** New `.claude/scripts/web-perf-qa.sh` runs Lighthouse (mobile preset) against `samples/web-demo` and emits `web-perf-summary.json` with FCP / LCP / CLS + the Lighthouse performance score. Wired into `device-qa.sh` as an advisory sub-leg of the web run (continue-on-error, never blocks the release gate). Thresholds are deliberately deferred — follow-up tracked.
- `samples/android-demo`: developer-only debug toggle that force-emits any `TrackingFailureReason` so the actionable-message overlay wired by #1735 can be validated indoors without staging a real failure (dark room, textureless surface, `EXCESSIVE_MOTION`, etc.). New `ForcedTrackingFailure` singleton + `ForceTrackingFailureMenu()` composable section under `samples/android-demo/.../common/` — visible only while `DemoSettings.qaMode` is on (long-press the demo's peek-chip or launch with `--ez qa_mode true`), so end users never see it. Wired into `ARImageDemo` as a proof-of-concept; a follow-up issue covers the remaining 11 AR demos that share the same `trackingFailureMessage` overlay (#1881).
- `samples/android-demo`: extended the developer-only force-tracking-failure debug toggle (#1881 / #1887) to the remaining 11 AR demos that consume `TrackingFailureReason`. `ForceTrackingFailureMenu()` is now reachable from each demo's Settings sheet (still gated by `DemoSettings.qaMode`, so end users never see it), and each demo's status-overlay path now reads `ForcedTrackingFailure.override` directly so flipping the override re-renders the banner without waiting for the next ARCore tracking-failure callback. Wired demos: `ARCloudAnchorDemo`, `ARDepthOcclusionDemo`, `ARDepthVisualizationDemo`, `ARImageStabilizationDemo`, `ARInstantPlacementDemo`, `ARPlacementDemo`, `ARRawDepthPointCloudDemo`, `ARRecordPlaybackDemo`, `ARRerunDemo`, `ARSceneSemanticsDemo`, `ARStreetscapeDemo` (#1888).

### Changed

- `samples/web-demo` (QA): Playwright bumped to `^1.59.0` and the legacy `video: 'on'` capture is replaced with a `page.screencast`-driven `screencast` test fixture that brackets every test, writes one `.webm` per test under `test-results/screencasts/<slugified-title>.webm`, and exposes a `screencast.chapter(title, description?)` API for tagging meaningful boundaries (tab switch, model load, failure). `device-qa.sh` mirrors the recordings into `$ARTIFACTS/web-screencasts/` so the web leg now ships per-test video parity with the Maestro Android / iOS legs (#1748).
- `.claude/hooks/pre-risky-github-op.sh`: added an `SV_BATCH_REBASE=1` env-var escape hatch so legitimate multi-PR rebase batches (e.g. a 9-branch ARCore audit sprint) no longer require clearing `~/.claude/logs/force-push.log` to bypass the 1-force-push-per-24h cap. The bypass logs an explicit notice to stderr and tags the entry `[SV_BATCH_REBASE]` for auditability, and the BLOCK message now points to the escape hatch instead of suggesting log tampering (#1796).
- **Append-only demo registry for `samples/android-demo` ([#1797](https://github.com/sceneview/sceneview/issues/1797)).** Adding a demo to the Android sample app no longer requires editing shared files. Each demo is registered by a single `*Fragment.kt` file under `io.github.sceneview.demo.fragments`; a collator (`samples/android-demo/scripts/collate-demos.sh`) aggregates them into `GeneratedDemos.kt`, sorted by id so two parallel PRs never collide on the same anchor. The quality gate runs the collator in `--check` mode to block stale generated files.
- ci: `.claude/scripts/worktree-auto-prune.sh` polish pass — respects `git worktree lock` by default (with `--unlock-locked` override, #1833), broadens active-session detection from `node`/`claude` to every process whose cwd is inside a worktree so gradle daemons / Python venvs / IDE indexers also block prune (#1834), writes one JSON line per evaluated worktree to `~/.claude/logs/worktree-prune-YYYYMMDD.log` for post-incident forensics, batches the merged-PR lookup into a single `gh pr list` (was N × `gh pr view`), and wraps `lsof` in `timeout 10s` so a hung scan can't hang the prune (#1839). New `.claude/scripts/test-worktree-auto-prune.sh` exercises 7 scenarios — merged, unmerged, dirty, locked, locked+`--unlock-locked`, live subprocess, `--keep` — and runs advisorily inside `quality-gate.sh` (#1835). CONTRIBUTING.md now documents the full skip ladder and flag set.
- **AR perf minor polish ([#1846](https://github.com/sceneview/sceneview/issues/1846)).** Tier-2 Wave-4 PERFORMANCE follow-ups. None individually MAJOR; collectively close out the audit's remaining minor findings.
  - `DepthMeshCollisionTest` column-order test hardened — added a 90° Y-rotation + non-axis-aligned translate case that compares the inline matmul (post-#1810) against kotlin-math's reference `Mat4 * Float4`. A column-swap in the inline math would now fail on every non-zero rotation component instead of silently passing the translate-only fixture.
  - `DepthMeshNode.acquireDirectBuffer` capped at a new `MAX_UPLOAD_BUFFER_BYTES = 1 MB` ceiling — a one-off oversized depth image no longer permanently inflates the upload-buffer cache. Buffers under the cap retain the no-shrink amortisation behaviour from #1810. New `DepthMeshNodeUploadBufferCapTest` pins the invariant.
  - `DepthHitResultNode` per-frame `Pose.makeTranslation` documented as load-bearing — investigation found ARCore's `Pose` is immutable by design and `DepthHitResult` carries no reusable Pose, so one alloc per node per frame is the floor for this surface. Inline KDoc steers future perf passes away from a false "fix".
  - `rememberARPlaybackStatus` already migrated to bare `try` / `catch (e: RuntimeException)` in #1857 — no `Throwable` wrapper allocation on IO_ERROR frames.
- `samples/android-demo`: per-demo `strings.xml` fragments. Title, subtitle, and demo-specific UI strings now live in dedicated `res/values/strings_demo_<id>.xml` files alongside each demo's `*Fragment.kt`, so two parallel PRs adding two different demos no longer collide on the central `strings.xml`. Android's resource merger fans every `res/values/*.xml` in at build time, so `R.string.demo_*` references resolve identically (no Kotlin / composable changes). The shared `strings.xml` keeps only app-level strings (navigation, AR launcher, About, accessibility…). Follow-up of #1797's append-only fragment registry (#1870).
- `samples/android-demo`: `collate-demos.sh` now also rewrites the "Sample app demos (Android)" section of `llms.txt` (and its mirror `docs/docs/llms.txt`) between dedicated marker comments, sourced from the same per-demo `*Fragment.kt` files that drive `GeneratedDemos.kt`. Adding a new demo no longer touches any `llms.txt`: drop the fragment, run the collator, regenerate the MCP bundle (`node mcp/scripts/generate-llms-txt.js`), commit. `--check` mode bit-compares all three outputs and the existing `check-llms-drift.sh` + `quality-gate.sh` wiring picks the new section up unchanged (#1871, follow-up of #1797 / PR #1869).

### Fixed

- `.claude/scripts/impact-check.sh`: trace line per check + `--fail` flag + ERR trap so the script no longer exits 1 silently in lean / sparse clones. Each check now announces itself on stderr (the last trace line points at any unexpected failure), every path-dependent check `[SKIP]`s instead of dying when its inputs are absent (`sceneview/`, `arsceneview/`, `SceneViewSwift/`), `grep | wc -l` patterns are guarded against the `pipefail` zero-match exit, and an ERR trap names the dying check + line. Default exit is now 0 (report-only); `--fail` opts in to non-zero for the quality gate. `SV_IMPACT_TRACE=1` forces `set -x`; auto-trace fires when stdout is not a TTY (CI / agent) unless `SV_IMPACT_TRACE_AUTO=0` (#1782, #1786).
- `build.gradle`: document that the `webpack <5.107.0` Yarn resolution pin (added in #1791 for `:sceneview-web:jsBrowserProductionWebpack`) also covers `:sceneview-web:jsTest` and `:sceneview-web:jsBrowserDistribution`. The root cause is shared — `kotlin-web-helpers/dist/tc-log-error-webpack.js` still does `require("webpack/lib/ModuleNotFoundError")` after webpack 5.107.0 moved that file to `lib/errors/`, and karma surfaces the resolution failure with a misleading top-of-stack `karma/bin/karma` line. The pin already keeps fresh-clone `:jsTest` runs green; this commit just makes the comment match the actual scope so future bound-lifts don't accidentally re-break test execution. Validation on a clean clone (`rm -rf build/ && ./gradlew :sceneview-web:jsTest` and `:jsBrowserDistribution`): both BUILD SUCCESSFUL with webpack 5.106.2 resolved (#1785).
- arsceneview: `DepthMeshNode.computeAabb` now clamps **every** half-extent below `DEGENERATE_AABB_HALF_EXTENT_M` to the degenerate cube, not just the all-empty-positions case (#1806). The earlier #1783 fix only handled `positions.isEmpty()`; geometry where every sample shared the same coordinate (e.g. a constant-range depth image, or the first frame after a scene reset where only one off-grid `(0,0,0)` vertex made it through) still emitted a zero half-extent and tripped Filament's `AABB can't be empty` `SIGABRT`. Added `DepthMeshNodeAabbTest` with the issue's reference case (all positions `(0.5, 1.0, -2.0)`) plus mixed-axis and single-vertex cases. Surfaced by the May 2026 Tier-2 SECURITY audit.
- arsceneview: defensive input validation on the AR depth pipeline (#1812). `DepthMeshNode.update` now `require`s non-zero camera intrinsics (width, height, focal length) so degenerate ARCore frames raise a clear error instead of poisoning `latestSnapshot` with `Inf`. `Frame.hitTestDepth` and `unprojectDepthPixel` likewise reject zero focal length — the latter throws `IllegalArgumentException` for direct callers; the former returns `null`. `DepthCollider.setBodiesRegion` rejects flat-packed arrays whose size is not a multiple of 3 (`IllegalArgumentException`) and silently falls back to disabled culling when the resulting region is non-finite. `nearestSurfaceYBelow` skips out-of-bounds index triplets so a future drift between `positions`/`indices` cannot AIOOBE on the render thread. All paths covered by JVM unit tests.
- ci: `app-store.yml`'s submit step now GETs the version record's `appStoreVersionSubmission` relationship and DELETEs any stale submission before re-POSTing — so the submission CREATE is idempotent across re-runs. When the #1687 + #1795 absorption logic retargets a stranded draft, that draft's old submission used to remain attached and 403 every subsequent CREATE (`"Allowed operation is: DELETE"`). v4.11.1 hit this on the stranded `367` draft → renamed to `4.11.1` → POST refused. Closes the last loose end of the #1795 / #1687 saga (#1831).
- `DepthMeshNode` no longer leaks the old `VertexBuffer` / `IndexBuffer` when an upload step throws mid-frame (engine teardown is the realistic trigger). `rebuildBuffersIfNeeded` returns the freshly-built buffers without mutating the `owned*` fields; `uploadGeometry` commits the swap + destroys the old buffers ONLY after `setGeometryAt` returns. On any exception the new buffers are `safeDestroy`-rolled-back and the owned* fields remain reachable for `destroy()`. Closes a latent leak introduced by the #1805 UAF fix (#1840).
- `ARDepthColliderDemo`: collapsed the per-ball `apply.onFrame` fan-out into a single Scene-level `onSessionUpdated` callback — `publishCollisionRegion` now runs ONCE per AR frame (was N times for N balls → ~300 transient `FloatArray`/sec at 5 balls × 60 fps). Replaced the `mutableListOf + activeBallNodes += this` pattern with a per-ball-count `arrayOfNulls<SphereNode>(ballCount)` slot store written by index. Recompositions that don't change `ballCount` (slider, theme, parent state) no longer leak stale node refs, so the region-cull AABB stays bounded. Region-cull payload is now packed by a pure `packCentres(...)` helper with a JVM regression test pinning the "no stale entries bleed through" invariant. Closes #1842.
- `arsceneview`: harden `ARRecorder` + `cameraConfigFilter` + playback wiring against five privacy / misuse / threading regressions surfaced by Tier-2 Wave 4 security review (#1845): `ARSceneView(playbackDatasetUri = …)` now allowlists `content://` and `file://` schemes only (rejects `https://`, `data:`, custom schemes at the SceneView boundary instead of handing them silently to ARCore — caller-side permission requirements documented on the KDoc); `cameraConfigFilter { targetFps = emptySet() }` raises `IllegalArgumentException` at builder time (was silently degrading to the session default camera config, which on Augmented Faces sessions is **front-facing** — a developer requesting back-only got the front camera with no signal) and the runtime catch is narrowed to ARCore's documented `RuntimeException` failure point (`Session.getSupportedCameraConfigs`) so builder errors propagate to dev-time tests; `ARRecorder.recordFrame` IO_ERROR transition now commits `state` + `errorMessage` inside a `Snapshot.withMutableSnapshot { }` so Compose readers can no longer observe the in-between `state == RECORDING` paired with a non-null `errorMessage`; `ARRecorder.recordTrack(handle, …)` short-circuits to `false` when `handle` was not registered via `addTrack` on the same recorder (was forwarding to ARCore — cross-recorder reuse leaked packets between unrelated recordings); `ARRecorder.addTrack` is bounded to `MAX_PENDING_TRACKS = 64` and a new `clearTracks()` API drops the in-memory registry (prevents the `addTrack(UUID.randomUUID(), …)` leak when wired into a recomposing block — unique UUIDs bypass the idempotent dedup, the cap surfaces the misuse at the call site).
- **CI: `quality-gate.sh` now blocks llms.txt mirror drift ([#1847](https://github.com/sceneview/sceneview/issues/1847)).** The drift detectors for `docs/docs/llms.txt` and the MCP bundle `mcp/src/generated/llms-txt.ts` used to live only in `sync-versions.sh`, which is not called by the PR-blocking gate. A new dedicated `check-llms-drift.sh` is wired into `quality-gate.sh` so any divergence from root `llms.txt` (e.g. the `DepthHitResultNode` drift that landed via #1822) fails the gate instead of silently sitting on `main`.
- docs, scripts: plug version-bump tooling holes for derived doc surfaces (#1848). Bumped stale Maven coordinates / SPM tags / CDN @version pins in `arsceneview/Module.md`, `sceneview/Module.md`, `docs/docs/manifest.json`, `docs/docs/structured-data.json`, and the three agent skills under `agents/sceneview*/` (SKILL + references/cheatsheet + references/migration + references/recipes) — they had drifted to 4.3.x / 4.4.x / 4.9.x. Added 14 ERROR-level checks to `.claude/scripts/sync-versions.sh` covering manifest.json `related_applications[].id`, structured-data.json `softwareVersion` + `releaseNotes` tag + Maven prose, plus every per-skill Maven coordinate / npm `sceneview-web@` / `@sceneview-sdk/react-native@` / SPM tag prose line, with matching `--fix` rewrites so future releases catch the drift.
- `samples/android-demo`: `ARRawDepthPointCloudDemo` now guides the user through motion-stereo convergence on non-LiDAR Pixels — a first-launch overlay ("Move your device for raw depth to converge") that auto-dismisses on the first non-zero frame or after 8 s, plus a passive top-right chip when the point count stays at zero for more than 2 s. The default confidence threshold is also lowered from 63/255 to 32/255 so motion-stereo's first frames produce visible points immediately. Before, a fresh launch showed "0 points" with no indication that the demo needs phone motion, which read as a broken demo (#1873).
- `samples/android-demo`: `ARDepthColliderDemo` now spawns balls in front of the **current** camera pose instead of the AR-session origin, so the balls are always visible regardless of how the user has moved before tapping Drop (#1874), and hides the underlying `DepthMeshNode` renderable by default — the cream dotted grid that the default material drew on every real surface was distracting and ambiguous. A new "Show depth mesh (dev)" Settings switch re-enables the visualization for collider debugging (#1875).
- `samples/android-demo`: `ARPlacementDemo` now surfaces a screen-centre placement reticle so the user can see where their next tap will land — a thin unlit cyan disc that follows the centre-of-screen hit-test result each frame via the AR-scope `HitResultNode`, with an "Aim at a surface…" prompt when no hit is detected (#1882). The previously-empty Settings sheet is populated with a bundled-model chip row (Damaged Helmet / Fox / Lantern / Toy Car / Shiba — or "Auto-cycle"), a "Snap to plane" toggle (default ON, gating tap acceptance to detected planes), a "Show reticle (dev)" toggle (default ON), and a prominent filled "Clear All" button — the empty placeholder above the Reset row that Pixel 9 QA flagged is gone (#1883).
- **`HitResultNode` defaults to plane-only ([#1891](https://github.com/sceneview/sceneview/issues/1891)).** The screen-coordinate `HitResultNode(xPx, yPx, ...)` overload now defaults `point = false`, `depthPoint = false`, `instantPlacementPoint = false`, plus a new defensive `minCameraDistance: Float? = 0.3f` floor that drops hits closer than 30 cm from the camera. Pixel 9 device-QA surfaced the previous wide-open defaults causing a fullscreen overlay on session start — depth / feature hits before motion-stereo convergence return positions <10 cm from the lens, and a child placement disc then blanks the camera feed. Opt each filter back in explicitly once your scene is tracking-stable.
- `samples/android-demo` + `samples/ios-demo`: restored the Sketchfab integration on the published Play Store and TestFlight binaries. The `SKETCHFAB_API_KEY` GitHub secret was empty (or whitespace) for several recent releases, so `BuildConfig.SKETCHFAB_API_KEY` / `Info.plist:SketchfabAPIKey` resolved to `""` and `SketchfabConfig.apiKey` returned `null`. That silently hid the three Explore-tab carousels (Staff Picks / Most Liked / Recently Added), turned the search bar into a no-op (queries got persisted to Recent Searches but were never executed), and forced every `SketchfabAssetResolver`-driven streamed demo (MultiModelDemo, ARPlacementDemo, ARInstantPlacementDemo, scene gallery, ...) onto its bundled-GLB fallback. The secret has been re-issued with a verified-valid token; #1910 tracks moving the request path through `mcp-gateway` so this regression class can't recur (#1909).
- **Strip lying "implemented" badges from the Flutter demo ([#909](https://github.com/sceneview/sceneview/issues/909)).** `samples/flutter-demo/lib/pages/features_page.dart` was claiming green for several methods whose iOS bridge path is a no-op (`ModelNode` pos/rot, `onTap`, `onPlaneDetected`, `Environment`). Those cards are now labelled "Android only" with the iOS gap pointed at the #909 umbrella. Added a Flutter `MethodChannel` smoke-test suite and a React Native `ARRecorder` Jest smoke-test scaffold so future drift surfaces as a red test instead of a green badge.
- **`sceneview-web` `SCENEVIEW_VERSION` constant lagged 2 releases (`4.9.0` while shipping `4.11.1`).** Bumped to `4.11.1` and promoted the `sync-versions.sh` check for this code-resident constant from WARN-only to a hard MISMATCH so it can never silently drift again. The regression-pin jsTest (#1357) was bumped in lockstep.
- **Contributor scripts: `worktree-auto-prune.sh` no longer silently deletes worktrees with unmerged work when `git fetch` fails.** Previously a failed fetch only printed a warning and continued with whatever local `origin/main` was cached, so a worktree on a branch with commits past main could be misclassified as `ahead=0` and removed. The fetch now exits with an error; pass `--allow-stale` to opt back into local refs for offline runs. In `--allow-stale` mode, candidates additionally require a merged-PR signal — `ahead=0` alone is no longer trusted. New active-session guard (on by default, `--no-check-active-sessions` to disable): a worktree is skipped if any live `node`/`claude` process has its cwd inside it. The scan re-runs immediately before the destructive loop to close the prompt-window race. The wrapper `cleanup-branches-worktrees.sh` propagates `--allow-stale` when its own fetch fails so offline runs through the wrapper still work.

### Docs

- `arsceneview`: Tighten `ARDepthOfField` KDoc with the upstream Filament verification (`colorPassOutput.depth` is the buffer `gl_FragDepth` writes to, so DoF post-pass + camera-stream depth occlusion compose without surprises) and surface three device-QA caveats that need eyeballing on real hardware: reverse-Z + early-Z culling around the `clip.z = 0.9999f` vertex hack, MSAA resolve filtering on the depth attachment, and `cocParams` calibration against the AR camera node's projection. Pure docs change; no API/behaviour delta (follow-up to #1716).
- `README.md`, `llms.txt`, and the docs landing page now explicitly position SceneView as the Compose-native successor to Google's archived Sceneform — ARCore for perception, Filament for rendering, Jetpack Compose for the API — so developers and AI assistants searching for a Sceneform replacement find SceneView (#1736).
- docs: cross-platform parity table in `cheatsheet-ios.md` mapping the four May 2026 Android-only AR surfaces (`DepthMeshNode` / `DepthCollider` / `Frame.hitTestDepth` / `CloudAnchorNode.host` Future-cancel) to their RealityKit / ARKit counterparts; root `llms.txt` cross-platform notes added in each section pointing readers to the cheatsheet. SceneViewSwift implementation work split into #1859 (CloudAnchorNode Future) + #1860 (Scene Reconstruction). #1813
- `arsceneview`: rewrote the `ARSessionFailure` KDoc + `llms.txt` examples to use a fully exhaustive `when` (all 25 subtypes + `Other`) and removed the `else -> showGenericRetryCta()` fallback that silently defeated the sealed-class compile-time-safety contract introduced by #1759. Also added a "compact" pattern showing how to dispatch many subtypes via a category-mapping helper without `else ->`. AI agents copy-pasting the snippet now keep the exhaustive-`when` guarantee (#1843).

## v4.11.1 — 2026-05-20

### Added

`rememberDepthCollider()` — depth-driven static physics collider so `PhysicsNode` bodies bounce off the real floor / table / wall in AR. Thin wrapper over `DepthMeshNode` (#1739): each rebuild's vertex/index buffers feed a per-frame surface lookup via the new `FloorProvider` interface on `PhysicsBody`. SceneView port of arcore-depth-lab's "Collider" scene (#1713).
- Android demo: added an `ar-depth-visualization` AR demo that renders the ARCore environment depth image as a false-color overlay (warm = near, cool = far), with a slider that blends the live camera feed (0) and the colorized depth map (1). The colorization runs through pure-Kotlin helpers in `samples/android-demo/.../demos/internal/DepthVisualization.kt` covered by JVM unit tests, and the demo handles "depth not supported" and "depth warming up" with explicit banners — never a black screen (#1714).
- Android demo: added an `ar-raw-depth-point-cloud` AR demo that visualizes ARCore's `Config.DepthMode.RAW_DEPTH_ONLY` output as a screen-space point cloud. The demo acquires raw depth + the companion confidence image on every frame, drops samples below a Compose-slider-driven confidence threshold, false-colors the survivors with a warm-near / cool-far ramp, and renders them over the camera feed via a `Canvas`. The filtering/sub-sampling logic is extracted as pure-Kotlin `internal` helpers in `samples/android-demo/.../demos/internal/RawDepthCloud.kt` and covered by 14 JVM unit tests. Honest unsupported / warming-up states surface explicit banners — never a black screen (#1715).
- `arsceneview`: surfaced ARCore v1.45+ Flash Mode as a new `flashMode: Config.FlashMode` parameter on `ARSceneView` (default `OFF`). Toggling between `OFF` / `TORCH` recomposes the session config reactively, and unsupported devices / front-camera sessions silently downgrade to `OFF` via `Session.isFlashModeSupported()` — matching the existing `depthMode` auto-fallback behaviour (#1732).
- `arsceneview`: surfaced `CloudAnchorNode.TTL_DAYS_RANGE = 1..365` and added the `CloudAnchorRegistry` interface plus a `SharedPreferencesCloudAnchorRegistry` default for persisting hosted Cloud Anchor IDs (name → cloudAnchorId, hostedAt, ttlDays) across app launches, with `isExpired()` / `purgeExpired()` helpers. `CloudAnchorNode.host()` now validates `ttlDays ∈ 1..365` and documents the required ARCore data-privacy disclosure (#1734).
- **`DepthMeshNode` — reify ARCore environment depth as a renderable Filament mesh.** New `rememberDepthMesh()` + `DepthMeshNode` composables in `ARSceneScope` turn the live depth image into a triangulated grid in the scene, with edge-discontinuity culling so triangles never stretch across depth jumps. Rebuild is interval-rate-limited (default 5 Hz). Exposes the camera-space vertex / index buffers via a `DepthMeshSnapshot` callback so downstream consumers (depth-driven physics collider, debug overlays) can read the geometry without poking Filament internals. SceneView equivalent of arcore-depth-lab's `ScreenSpaceDepthMesh`. ([#1739](https://github.com/sceneview/sceneview/issues/1739))
- **Async `Future` cancellation across CloudAnchor / Terrain / Rooftop ([#1768](https://github.com/sceneview/sceneview/issues/1768)).** `CloudAnchorNode.host` now returns the underlying `HostCloudAnchorFuture` so callers can cancel pending Google Cloud requests on UI disposal (avoiding billing accrual for users who navigated away). `CloudAnchorNode.resolve`, `TerrainAnchorNode.resolve`, and `RooftopAnchorNode.resolve` carry explicit return types (`ResolveCloudAnchorFuture` / `ResolveAnchorOnTerrainFuture?` / `ResolveAnchorOnRooftopFuture?`) for the same reason. Billing rationale + `DisposableEffect.onDispose { future.cancel() }` pattern documented in KDoc and `llms.txt`. JVM unit tests pin the return-type contract via reflection.
- `arsceneview`: surfaced four Geospatial accessors as Compose-friendly helpers (#1769). `rememberCameraGeospatialPose(session)` returns a `State<GeospatialPose?>` that updates each frame with the live device lat/lng/altitude (null until ARCore acquires a GPS lock + `Earth.trackingState == TRACKING`). `GeospatialPose.snapshot()` captures all 7 fields (lat/lng/altitude/heading/horizontalAccuracy/verticalAccuracy/orientationYawAccuracy) into a `GeospatialPoseSnapshot` data class so apps can retain them across frames — the existing `.transform` extension drops the four accuracy / heading fields. `rememberEarthState(session)` exposes `Earth.EarthState` (ENABLED / ERROR_INTERNAL / ERROR_NOT_AUTHORIZED / ERROR_RESOURCE_EXHAUSTED / ERROR_APK_VERSION_TOO_OLD / ERROR_GEOSPATIAL_MODE_DISABLED) as Compose State. `Session.awaitVpsAvailability(lat, lng)` is a `suspend` wrapper around `checkVpsAvailabilityAsync` — apps can gate "place Terrain anchor" buttons on actual VPS coverage instead of guessing and surfacing `ResourceExhaustedException` after a network round-trip.
- **ARCore extension one-liners ([#1771](https://github.com/sceneview/sceneview/issues/1771)).** Thin Kotlin wrappers around frequently-needed ARCore APIs: `HitResult.distance`, `Camera.displayOrientedPose`, `Plane.polygon`, `Plane.subsumedBy`, `Camera.intrinsics(useTexture)` returning a `CameraIntrinsicsSnapshot` (`focalLength` / `principalPoint` / `imageWidth` / `imageHeight`), public `Frame.depthImage()` / `Frame.rawDepthImage()` / `Frame.rawDepthConfidenceImage()` accessors that swallow `NotYetAvailableException`, and `suspend ArCoreApk.awaitAvailability(context)`. Documented in `llms.txt` under "Low-level helpers".
- `arsceneview`: added `types: Set<StreetscapeGeometry.Type>` and `minQuality: StreetscapeGeometry.Quality` filter parameters to `ARSceneScope.StreetscapeGeometryNode` (default `{BUILDING, TERRAIN}` / `Quality.NONE` — no filtering). Apps can now request only `BUILDING` meshes (drops the noisy ground terrain in dense urban scenes) and gate on `BUILDING_LOD_2` to render only the higher-LOD geometry — saves a frame-rate cliff on low-end devices. Geometries that don't pass the filter become composable no-ops and never allocate Filament buffers. The companion `cameraConfigFilter { … }` DSL covering this issue's second acceptance criterion (`DepthSensorUsage` / `StereoCameraUsage` knobs on the camera config filter) ships in #1733 (#1772).
- arsceneview: new `ARSceneScope.DepthHitResultNode(xPx, yPx, content)` composable — Compose-idiomatic mirror of `HitResultNode` for placement against the ARCore depth image. Each frame re-runs `Frame.hitTestDepth` and moves to the resulting world-space surface point; `depthHitResult` exposes the live `DepthHitResult` for surface-normal-aligned content (#1814).

### Changed

- AR demos now surface ARCore tracking-failure reasons via a single `trackingFailureMessage(reason)` helper ported from arcore-android-sdk's `TrackingStateHelper`. Each `TrackingFailureReason` (`BAD_STATE`, `INSUFFICIENT_LIGHT`, `EXCESSIVE_MOTION`, `INSUFFICIENT_FEATURES`, `CAMERA_UNAVAILABLE`) maps to a localised string resource (`tracking_failure_*` in `strings.xml`), giving the user actionable guidance instead of nothing. Inlined `when` branches across 8 AR demos (Image, DepthOcclusion, ImageStabilization, InstantPlacement, Placement, Rerun, RecordPlayback, Streetscape) collapse into a 1-line helper call. Addresses the "no guidance" half of #1615 (#1735).
- arsceneview: threading-hardening sweep on the AR depth and cloud-anchor paths (#1811). `DepthMeshNode.update / latestSnapshot / currentVertexBuffer / currentIndexBuffer / onMeshRebuilt` now carry `@MainThread` annotations with KDoc notes spelling out "render-thread only — reading from a background coroutine is unsupported". `CloudAnchorNode.hostTask` is `@Volatile` and `cancelHost()` wraps its read-cancel-clear sequence in `synchronized(this)` so callers can safely cancel an in-flight host from `viewModelScope.launch { … }` without racing the ARCore async callback (which fires on the GL/render thread). `DepthCollider.setBodiesRegion` gains a KDoc render-thread pin matching the surrounding `floorYAt` / `ingestSnapshot` contract. A new `CloudAnchorNodeThreadingTest` reflects the `@Volatile` and exercises the synchronized contract under contention.

### Fixed

- arsceneview: per-frame `IndirectLight` no longer leaks native memory across long AR sessions with intermittent light estimation. The `IndirectLight` built in `onARFrame` is now tracked via a dedicated `AtomicReference` and destroyed explicitly on every supersession or on `DisposableEffect` teardown, independent of `scene.indirectLight` mutations by third parties. The rebuild decision (estimation vs. environment baseline per channel) is extracted to a pure `pickIndirectLightSources` helper covered by JVM unit tests (#1756).
- arsceneview: clarify the depth `ByteBuffer` lifecycle invariant in `ARCameraStream` — the buffer borrowed from ARCore's depth `Image` is now documented as intentionally NOT cloned, with the upload-completed callback as the load-bearing synchronisation point that closes the ARCore image exactly once. Updates the previous misleading comment that claimed the buffer was cloned. Adds a pure-JVM sentinel test pinning the `buffer.clear()` metadata-only contract and the setter-doesn't-allocate invariant (#1757).
- **Cache the identity tangent buffer on `AugmentedFaceNode` ([#1758](https://github.com/sceneview/sceneview/issues/1758)).** The `(0, 0, 0, 1)` identity-quaternion buffer used to honour Filament's FLOAT4 TANGENTS stride contract under unlit face materials is now built once at mesh creation and reused unchanged — no per-vertex rewrite on subsequent calls, even when the cached size matches. JVM unit test pins the cache hit/miss contract.
- react-native: `@sceneview-sdk/react-native` `package.json` now declares `publishConfig.access=public`, anchoring the scoped-package public-access intent inline. `release.yml`'s `npm publish --access public` CLI flag stays as belt-and-suspenders so any future workflow_dispatch retry or manual republish from a fresh checkout cannot regress to a 404 on the registry `PUT` (#1788).
- build: v4.11.1 re-validates the `sceneview-web` Kotlin/JS production webpack chain that broke on v4.11.0 — webpack 5.107.0 moved `lib/ModuleNotFoundError.js` to `lib/errors/ModuleNotFoundError.js` while kotlin-web-helpers still resolved the legacy path, crashing every `:sceneview-web:jsBrowserProductionWebpack` invocation. Fixed on `main` by the webpack `<5.107.0` resolution pin in #1791; this release ensures the production publish + `Deploy website + docs` pipelines run end-to-end on the v4.11.1 tag (#1789).
- ci: `app-store.yml`'s submit step now reads `VERSION_NAME` from the root `gradle.properties` as its `workflow_dispatch` fallback for `ASC_VERSION_STRING`, instead of `build_version` (which returns `CFBundleVersion` — the build number, not the marketing version). v4.11.0's manual deploy created a nonsensical App Store version record named `367` and 403'd on submission because of this; the fallback is now anchored to the project's single source of truth (#1795).
- arsceneview: closed a use-after-free window in `DepthMeshNode.rebuildBuffersIfNeeded` (#1805). The old `VertexBuffer`/`IndexBuffer` were `safeDestroy`'ed **before** `RenderableManager.setGeometryAt` rebound the renderable to the new buffers — for one frame the renderable referenced freed Filament native handles. Reordered to **build new → rebind → destroy old** so the renderable never points at freed memory. Added `DepthMeshNodeBufferRebuildTest` JUnit suite that pins the ordering across two successive growths via a mock-engine recorder. Surfaced by the May 2026 Tier-2 SECURITY audit.
- arsceneview: `DepthCollider` class-level KDoc example now passes the collider through `floorProvider = collider` instead of the non-existent `depthCollider = collider` parameter (#1807). Code pasted from the KDoc previously did not compile. The `ARSceneScope.rememberDepthCollider` KDoc and the `PhysicsNode` KDoc already used the correct form, so the bug was isolated to `DepthCollider.kt`.
- sceneview: deprecated mass-overload `PhysicsNode`'s `@Deprecated(ReplaceWith(...))` now preserves the newly-added `floorProvider` parameter (#1807). The IDE quick-fix on the deprecation previously silently stripped AR floor wiring. The deprecated overload itself also gained a `floorProvider` parameter so the replacement is a 1:1 source-compatible swap.
- `mcp`: regenerated `src/generated/llms-txt.ts` so the npm bundle and the Cloudflare Worker gateway both ship the full May 2026 AR sprint surface (`DepthMeshNode` / `rememberDepthMesh` / `rememberDepthCollider` / `Frame.hitTestDepth` / `HostCloudAnchorFuture` / `ResolveCloudAnchorFuture` / Future-returning `CloudAnchorNode.host` & `resolve` / `TerrainAnchorNode.resolve` / `RooftopAnchorNode.resolve`). Added a `sync-versions.sh` CI drift guard that rebuilds the bundle in-memory and fails when it disagrees with root `llms.txt`, so a future sprint can no longer land API additions in `llms.txt` while leaving MCP clients on a stale snapshot. Documented the regen step in the new `mcp/CONTRIBUTING.md` (#1808).
- `docs`: fixed broken `TerrainAnchorNode.resolve` and `RooftopAnchorNode.resolve` examples in `llms.txt` — both used `earth = earth`, but the real signature takes `session: Session` (the function reads `session.earth` internally). Code pasted from the docs now compiles. Healed mirror drift between root `llms.txt` and `docs/docs/llms.txt` (the `CloudAnchorRegistry` + `ttlDays` block from #1734 was missing in the mirror). Added a `DisposableEffect.onDispose { future?.cancel() }` snippet to the Terrain and Rooftop sections so AI agents emit the same cancel-on-dispose pattern they already produce for `CloudAnchorNode` (#1768). Added a "Threading" note to `Frame.hitTestDepth` (~L846) and `DepthMeshNode` (~L1039) — both must run on the AR frame / GL-main thread; KDoc said so already but `llms.txt` didn't. Added "See also" cross-references between `DepthMeshNode`, `rememberDepthCollider` and `Frame.hitTestDepth` so devs landing on one discover the other two (#1809).
- arsceneview, sceneview: kill per-frame allocation hot paths in the AR render loop (#1810).
  - `ARScene.onARFrame`: single-pass `for (n in childNodes) when (n) { is PoseNode -> ...; is DepthMeshNode -> ... }` replaces two `filterIsInstance<...>().forEach { }` walks (~240 list allocations/sec at 60 fps on the render thread).
  - `DepthMeshNode.uploadGeometry`: vertex / index upload now reuses two cached direct `ByteBuffer`s grown in powers of two (was ~100 KB/s direct-buffer churn at 5 Hz, ~600 KB/s at 30 Hz).
  - `DepthMeshCollision.transformPositionsToWorld`: inline 4×4 × (x,y,z,1) matrix multiply writes straight into the output `FloatArray`, removing ~9k transient `Mat4 * Float3` allocs/sec.
  - `PhysicsBody.step`: velocity + position integrated as plain `Float` triples, committed in exactly 2 `Position` allocs per body per frame (was 3-4 → ~1200/sec at 5 balls × 60 fps).
  - `ARDepthColliderDemo`: now drives `DepthCollider.setBodiesRegion(...)` once per frame from the active sphere centres + 15 cm padding so the KDoc-documented region-cull fast path is no longer bypassed (was ~540k tri-tests/sec; region-cull collapses to the bodies' shared AABB).
- arsceneview: defensive `onDispose` ordering on `ARScene`'s per-frame `IndirectLight` rebuild — clear `scene.indirectLight = null` BEFORE `engine.safeDestroyIndirectLight(...)` so a late `onARFrame` queued on the GL thread cannot dereference a freed native handle (#1814).

### Docs

- `arsceneview`: document ARCore 1.54's **Geospatial Depth** in `llms.txt` and on `StreetscapeGeometryNode`. Enabling `Config.DepthMode.AUTOMATIC` together with `Config.GeospatialMode.ENABLED` and `Config.StreetscapeGeometryMode.ENABLED` automatically extends environment-depth accuracy from ~8 m (motion-stereo only) to **~65 m** by fusing depth with Streetscape geometry + sensors. Every existing depth consumer (`Frame.hitTestDepth`, `DepthMeshNode`, `rememberDepthCollider`, `ARCameraStream` occlusion) benefits transparently — no API change required (#1731).
- docs: new migration block in `docs/docs/migration.md` for the `CloudAnchorNode.host()` return-type change (`Unit` → `HostCloudAnchorFuture`, #1768). Covers the source-compatibility break + the `DisposableEffect.onDispose { future.cancel() }` recommendation with billing rationale (#1814).
- llms.txt: `DepthHitResultNode` section and `Frame.hitTestDepth` `@return` KDoc clarification documenting the single-vs-list asymmetry vs `Frame.hitTest` (depth at one pixel is unique) (#1814).

## v4.11.0 — 2026-05-20

### Added

- Android demo: added a `cameraDistance` zoom deep-link parameter — a `--ef camera_distance <f>` intent extra and a `sceneview://demo/<id>?cameraDistance=<f>` query parameter that override the 3D hero-orbit camera distance. This lets the Maestro device-QA flows exercise 3D camera zoom, which Maestro cannot do by pinch; `.maestro/android/flows/demo.yaml` now captures a near + far framing for `model-viewer`. Invalid or out-of-range values fall back to the demo's default framing (#1571).
- `Frame.hitTestDepth(xPx, yPx)` raycasts the ARCore depth image and returns a `DepthHitResult` (world position, camera-facing surface normal, distance) — placement onto *any* real-world surface, not just detected planes, inspired by [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab)'s "Oriented Reticle" (#1712).
- New cinematic turntable camera: `applyCinematicOrbit(cameraNode, timeSeconds)` drives a slow, eased "hero shot" orbit around the content — long lens, gentle downward tilt and a soft vertical bob. Three feel presets are provided (`CinematicCameraProfile.HeroProduct`, `SlowCinematic`, `NeutralWeb`); `CinematicCameraProfile.Default` is the contemplative `SlowCinematic` profile. Pairs with `SceneView(autoCenterContent = true, cameraManipulator = null)` for a one-call cinematic showcase.
- Remote files loaded over http(s) — glTF/GLB models, KTX environments, textures — are now cached on disk by the new `FileCache`. The first load downloads and persists the bytes; every later load reuses the cached file, so there are no repeated downloads and assets stay available offline. Caching is wired transparently into `FileLoader.loadFileBuffer`, with `Context.fileCacheDir` / `Context.clearFileCache()` to inspect or reclaim it, and `FileCache.enabled` to opt out.

### Changed

- `validate-demo-assets.sh` now cross-checks every asset physically bundled under the demo asset roots against `assets/catalog.json` and fails CI if a bundled asset is undeclared, making catalog drift a build failure instead of a manual discovery (#1666).
- CI workflow hygiene: a detekt static-analysis step is wired into the `lint` job (advisory for now — detekt 1.23.8 registers no tasks on the current Kotlin 2.3 toolchain, so the step reports without gating PRs pending a detekt upgrade and baseline), `docs.yml` artifact action versions are aligned, the `quality-gate` job restores Gradle wrapper validation, and a stale Node-version comment in `telemetry-ci.yml` is corrected (#1699, #1702, #1703, #1708).

### Fixed

- Samples cleanup: dropped the stale "Coming in v1.1" version label from the iOS demo's coming-soon placeholders (now a plain "Coming soon" badge), and documented that AR demos intentionally skip the 3D first-frame loading scrim (#1361).
- **iOS**: `FogNode.heightBased(...)` and `FogNode.heightFalloff` are now formally deprecated with a compile-warning instead of silently no-op'ing at runtime — RealityKit has no per-pixel height fog equivalent to Filament's `View.fogOptions.heightFalloff`. Use `FogNode.exponential(density:color:)` instead. The iOS Fog demo no longer advertises a "Height" mode. ([#1380](https://github.com/sceneview/sceneview/issues/1380))
- **Device QA runs no longer show `cancelled` when only the advisory android/ar emulator leg is flaky ([#1643](https://github.com/sceneview/sceneview/issues/1643)).** The emulator-leg `script:` blocks bounded `adb wait-for-device` and `device-qa.sh` with internal `timeout`s. A flaky CI emulator now produces a clean step **failure** (absorbed by `continue-on-error`) instead of letting the job run to `timeout-minutes` — a timed-out job ends `cancelled`, and a cancelled job drags the whole run conclusion red even when web/build/the other legs passed.
- Release device-QA gate is now deterministic and non-blocking — it dispatches its own uncancellable Device QA run, waits with a hard timeout, treats web+ar as required and android as advisory, and proceeds-with-warning on timeout, so a flaky harness can never block a release indefinitely (#1683).
`PhysicsNode` no longer clobbers or destroys the caller's existing `Node.onFrame` callback — it now saves the prior callback, chain-calls it each frame, and restores it on dispose (#1694).
- Web: glTF animations now play instead of freezing at t=0, `OrbitCameraController.dispose()` detaches its DOM listeners, and `SceneView.destroy()` releases leaked `LightManager` components (#1697, #1698, #1700).
Android TV demo: D-pad controls now work on launch — the root `Box` is `focusable()` and requests focus on first composition so key events reach the `onKeyEvent` handler.
- PhysicsDemo: each falling body now gets its own `ModelInstance` spawned from a shared `Model`, so every streamed crash-test mesh renders instead of only one (#1706).
- VideoDemo no longer auto-plays the video if the user tapped Pause before the player became ready — the prepared callback now honours the user's desired playback state (#1707).
- **Play Store deploy now self-heals a corrupt release AAB.** A truncated or zero-byte App Bundle from a flaky CI runner (which silently cost the v4.6.0 and v4.6.1 store releases, [#1412](https://github.com/sceneview/sceneview/issues/1412)/[#1415](https://github.com/sceneview/sceneview/issues/1415)) used to sail past gradle's exit 0 and only blow up at upload. The `Build release AAB` step now verifies the artifact is a readable zip and rebuilds once from clean before aborting, so a transient I/O flake no longer loses a release.
`SceneView` no longer triggers "Modifying state during view update" Xcode runtime warnings — `appliedMainSlot`, `appliedFillSlot`, and `appliedSkyboxResource` are now held in a private reference-type cache class rather than individual `@State` properties, so mutations inside `RealityView.update:` are invisible to SwiftUI's state-change detection.

### Tests

- Android demo: wired the 12 live-only AR demos to honour `DemoSettings.arPendingPlaybackFile`. A new shared `rememberArPlaybackDataset()` helper resolves the `--es ar_playback_file <path>` deep-link extra (set by the autonomous AR replay device-QA harness) into the `ARSceneView(playbackDataset = …)` parameter. Previously only `ar-record-playback` consumed the extra, so the harness could only grade the other AR demos `alive`; they can now graduate to `replayed` with frame-indexed assertions. When the extra is absent — i.e. every normal launch — the helper returns `null` and the demos behave exactly as before, so there is no live-AR regression for real users (#1576).

### Docs

- Corrected stale version references that the v4.10.0 release left behind — the docs landing-page "Latest Release" stat, `llms-full.txt`'s SceneView version line, the iOS deployment-target docs (now iOS 18 / macOS 15 / visionOS 2), the SwiftUI codelabs' SPM version rule, and an overstated web-demo changelog entry — and hardened `sync-versions.sh` to scan these files so future releases bump them automatically (#1693).
- Refreshed the stale `ROADMAP.md` (was pinned at v4.0.9) to v4.10.0 and resolved the `CLAUDE.md` ↔ `sync-versions.sh` contradiction over `mcp/package.json` — the Version Location Map now documents `sceneview-mcp` as an independent npm version track that must NOT be synced to the SDK `VERSION_NAME` (#1701, #1705).

## v4.10.0 — 2026-05-17

### Added

- **Web demo catalog expanded with Lighting, Animation, Text, and Environment tabs ([#1362](https://github.com/sceneview/sceneview/issues/1362)).** The `samples/web-demo` playground previously exposed only Models / Geometry / Physics / Settings — a small fraction of the SDK versus Android's ~39 demos. It now ships four new tabs in the demo app: **Lighting** adds and removes directional/point/spot lights, **Animation** loads self-hosted animated glTF models and drives keyframe playback, **Text** renders billboarded 3D text nodes, and **Environment** controls image-based lighting via spherical-harmonic presets, background color, and bloom strength. These tabs are wired against the demo's hand-vendored `samples/web-demo/.../js/sceneview.js` viewer helper — they are a web-DEMO addition and do not change the published `SceneViewJS` Kotlin/JS API surface. First slice of the cross-platform demo-parity effort; web-demo only.
- Auto-fit camera framing (#1439): a new library-level helper in `io.github.sceneview` computes the orbit distance at which a model's bounding sphere exactly fills the viewport, regardless of the model's intrinsic glTF size. `fitDistanceForBounds(bounds, verticalFovDegrees, aspect, padding)` is pure trigonometry (yaw-invariant — fits the bounding sphere, not the raw box); `CameraNode.frameToContent(node)` / `CameraNode.frameToBounds(aabb)` reposition the camera in one call; `verticalFovDegreesForFocalLength` and `Box.toAabb()` convert Filament's focal-length / `Box` types; `SceneAutoFitState` is a one-shot guard for use in a `SceneView` frame loop. The Model Viewer demo now auto-fits its orbit radius to the displayed model — a 5 cm bee and a 5 m crate are framed identically without per-demo `scaleToUnits` tuning. Android-only for now; iOS already frames from `visualBounds` (#1026 / #1391).
- **Demo: Material Streaming ([#1480](https://github.com/sceneview/sceneview/issues/1480)).** New Advanced-section demo in the Android sample app showing runtime texture/material streaming — a single loaded model whose surface material is swapped live from a chip picker (Polished Steel, Brushed Gold, Copper, Matte Plastic, Glazed Ceramic). The swap reassigns the node's Filament `MaterialInstance` via `setMaterialInstanceAt(...)` with no geometry rebuild or model reload, lit by a studio HDR so the metallic/roughness contrast reads. Distinct from the PBR Materials demo (#1423), which streams a whole new model per chip. Material sets are bundled in-app so the demo renders offline; streaming the same material/texture data from a remote catalogue (Sketchfab material packs, a `.ktx` texture-set CDN) is a documented follow-up.
- device-qa: added the maestro iOS leg — `.maestro/ios/` flows drive every iOS demo reachable via the `sceneview://demo/<id>` deep link in `samples/ios-demo` like a real user (custom-scheme launch, camera-orbit drag, tap, one screenshot per demo, crash assertion), with per-category subflows and launch-only smoke for AR demos (RealityKit AR cannot run on the simulator); `ios-device-qa.sh` is the maestro wrapper that boots a simulator, builds + installs the demo and sweeps the simulator log for crashes (#1563).
- device-qa: added `.claude/scripts/device-qa.sh` — the autonomous cross-platform device-QA orchestrator that ties the four platform harnesses (Maestro Android, Maestro iOS, Playwright web, AR replay) into one unattended pass, boots the emulator/simulator each leg needs, builds + installs the demo app, and aggregates every platform's machine-readable verdict into a single `device-qa-report.json` plus a human-readable summary; it exits non-zero if any selected platform fails, is disk-aware (reuses `disk-gated-spawn-check.sh` and cleans build output between legs), and degrades a missing emulator/simulator/browser to `skipped` (treated as a failure under `--ci`). The release checkpoint (`release-checklist.sh` + the `/release` skill) now blocks tagging on a green `device-qa-report.json`, and a path-gated `device-qa.yml` CI workflow (also reused by `nightly-ci.yml`) runs the web and Android legs (#1566).
- Device-QA emulator can now boot visible (windowed) via the opt-in `--window` flag or `EMU_VISIBLE=1` on `setup-ar-emulator.sh`; the default stays headless and CI is unchanged (#1660).

### Changed

- Android demo polish (#1443): demo-grid cards now carry a hairline `outlineVariant` border so their boundaries stay visible against the dark ParticleBackground; the Image Planes demo is staged as a three-picture wall gallery (framed procedural landscapes at varying depth and angle) instead of a single floating logo; the Billboard demo plants its billboard and fixed signs on a ground plane with an angled camera and explanatory caption so the orbit-time difference between the two node types is obvious.
- device-qa: fixed `qa-android-demos.sh`, `ios-device-qa.sh` and `ar-replay-qa.sh` resolving `REPO_ROOT` one level shy — they live in `.claude/scripts/` so the repo root is two levels up, not one. When invoked by `device-qa.sh` (whose CWD is not the repo root) the scripts `cd`'d into `.claude/` instead, so Maestro flow discovery found nothing (`[qa] no such flow: .maestro/android/3d-basics.yaml`). All three now derive `REPO_ROOT` from `${BASH_SOURCE[0]}/../..` so every path (`.maestro/...`, `./gradlew`, the demo module) resolves regardless of the caller's CWD (#1585).
- `sceneview` node API honesty (#1598, #1599): verified `MeshNode` does not leak its `RenderableManager` component — `RenderableNode.destroy()` already releases the renderable built on `entity` (#1598 confirmed stale, no code change needed). Deprecated the `PhysicsNode` / `PhysicsBody` `mass` parameter — the Euler integration applies only gravity, which is mass-independent, so `mass` was a silent no-op; it is now `@Deprecated` with a clear message and the mass-free overload is the canonical one (#1599).
- CI workflow hygiene (#1601, #1602): documented the device-qa.yml four-leg split (per-push web+android vs nightly-only ios+ar) and why `samples/ios-demo/**` is deliberately absent from its path trigger; unified telemetry-ci.yml on `node-version: 20` to match device-qa.yml and docs.yml; deleted the orphan top-level `docs/screenshots/` directory (a byte-identical, unreferenced duplicate of `docs/docs/screenshots/`, which MkDocs actually serves).
- **Device-QA harness now selects a single shared Android emulator RAM-aware and parallel-session-safe ([#1647](https://github.com/sceneview/sceneview/issues/1647)).** Before booting, `setup-ar-emulator.sh` reuses any already-running emulator, gates a fresh boot on free host RAM, scales the `-memory` flag to RAM headroom, and takes an advisory lock so concurrent Claude Code sessions cooperate on one emulator instead of each booting their own — fixing emulator resource contention and boot failures on RAM-constrained hosts. No multi-emulator pool: there is always exactly one shared emulator.
- Removed a stale verification TODO in the Android demo's `AnimationDemo` — `ModelNode.playAnimation`'s `loop` parameter is verified to be honoured correctly. (#1649)
- device-QA harness (#1654): the emulator-selection layer is now a RAM-budgeted adaptive pool — it leases a free running emulator or boots a new one on a distinct `-port` whenever live host RAM safely allows (cap `floor((free_RAM − headroom) / per-emu budget)`, clamped `[1, EMU_POOL_MAX]`), re-gates free RAM as a hard memory-safety check before every boot, reclaims stale per-emulator leases, and pins `ANDROID_SERIAL` to the leased device — superseding the strict-single emulator of #1647 while keeping the floor at 1 on RAM-tight hosts.
- Repo hygiene: stale `claude/*` branches no longer pile up on the remote. The `Automatically delete head branches` setting is now enabled, so every PR branch is dropped the instant its PR merges. The `cleanup-branches-worktrees.sh` backstop was reworked to fetch PR status with two bulk `gh pr list` calls instead of one `gh pr view` per branch — the per-branch form fired hundreds of sequential API calls and timed the daily `branch-cleanup` job out before it could delete anything, which had let the remote grow to ~190 branches.

### Fixed

- visionOS target of the `SceneViewSwift` Swift package now compiles (#1366): the deployment target is raised to visionOS 2.0 (RealityKit's `DirectionalLight`/`PointLight`/`SpotLight` entities and per-entity `shadow` API are `@available(visionOS 2.0, *)`), `SceneView` uses the cross-platform `RealityViewContent` initializer on visionOS instead of the `@available(visionOS, unavailable)` `RealityViewCameraContent`, light components drop the visionOS-unavailable `isRealWorldProxy:` initializer parameter, and a new `Build Swift Package (visionOS)` CI step in `ios.yml` builds the xrOS SDK on every iOS PR so this can't regress silently.
- iOS: the `Multi-Model Park` demo now frames all four streamed models centered and correctly sized. The previous fix translated the content root so its bounding-box centroid landed at the world origin, but `Multi-Model Park` nests its models under an `AnchorEntity` — RealityKit re-pins that anchor to its world target every frame, so the translation and the anchor fought each other and the framed centroid ran away to infinity, leaving a fully black viewport. The auto-framing pass now points the orbit camera at the content's world-space centroid instead of moving any scene node, which removes the feedback loop entirely. Framing is also computed from the union of every loaded model and re-runs until that union is stable, so partially-streamed scenes no longer latch early. Single-model demos (Model Viewer, Geometry) are unaffected (#1391, #1514, #1385).
- **Orbital AR demo now renders its four streamed planets.** The demo loaded its resolver-staged GLBs through the two-argument `rememberModelInstance(modelLoader, String)`, which Kotlin overload resolution binds to the asset-path overload — so the `file://` cache URI was handed to `AssetManager.open`, threw `FileNotFoundException`, and the four streamed planets stayed `null` while the four bundled-asset planets kept working. The streamed branch now loads the local file via `ModelLoader.loadModelInstance`, which understands `file://` URIs. Same root cause as the Multi Model demo fix ([#1422](https://github.com/sceneview/sceneview/issues/1422)).
- React Native: bumped the iOS bridge podspec `SceneViewSwift` dependency from the year-old `~> 3.4` pin to `~> 4.9`, matching the published SPM tag the bridge code already targets. (#1512)
- **iOS test build: `AugmentedImageNodeTests.swift` failed to compile under the iOS 26.2 SDK ([#1515](https://github.com/sceneview/sceneview/issues/1515)).** The `AugmentedImageNode.ReferenceImage(name:image:physicalWidth:)` initializer became `throws` in #883, but the test still called it without `try` — the macOS `swift test` target stayed green only because it never built the iOS-gated test file. The throwing call sites now use `try` (and a sibling `CameraControlsTests.swift` now imports `RealityKit` for `BoundingBox`), and the `iOS CI` workflow's `xcodebuild` steps gain `set -o pipefail` so a failing test-build is no longer masked by `xcpretty`'s exit 0.
- **CI: raise `Unit tests + coverage` and `CI Gate` timeouts ([#1554](https://github.com/sceneview/sceneview/issues/1554)).** The full JaCoCo pass runs close to the old 30-min job timeout on a slow runner; it tipped over on a release PR and cascaded a confusing double-red. The `Unit tests + coverage` job timeout is now 45 min, and the `CI Gate` aggregator's internal poll deadline (50 min) and job timeout (60 min) comfortably exceed it so a slow-but-succeeding job is seen as completing.
- iOS: detected ARKit planes now render as a subtle translucent overlay instead of an opaque bright-green debug fill that obscured the camera feed (#1557).
- **Device-QA Android leg no longer hangs silently in CI ([#1560](https://github.com/sceneview/sceneview/issues/1560)).** The leg ran 40+ minutes with zero output before the job timed out: `device-qa.sh` redirected the whole wrapper's output to a file shown only after it returned, and `qa-android-demos.sh` built the demo APK with Gradle `-q` (no output at all). The Android leg now streams live via `tee`, builds with `--console=plain`, and bounds the cold APK build and each Maestro run with `timeout` so a genuine hang fails fast with a clear diagnostic instead of eating the CI job budget. The job's `timeout-minutes` is raised to 60 to give a legitimate cold build headroom.
- **Device-QA Android leg no longer aborts at Maestro flow-parse time ([#1560](https://github.com/sceneview/sceneview/issues/1560)).** `.maestro/android/flows/demo.yaml` passed the demo deep link as a `deepLink:` sub-property of `launchApp`, but Maestro 1.39 has no such property — the flow failed to parse with `Unknown Property: deepLink` before a single demo ran, so `device-qa.sh --platform=android --fast` reported `passed=0 failed=1`. The fix delivers the demo id and `qa_mode` flag as `launchApp` `arguments:` instead, which Maestro maps to intent extras (`--es demo <id>`, `--ez qa_mode true`). `MainActivity` already reads exactly those extras through `DeepLinkRouter.validate` — the same closed-registry allow-list the `sceneview://demo/<id>` scheme uses — so demo routing and the deterministic-screenshot animation freeze are both preserved. Harness-only fix; no demo code changed.
- **Device-QA web leg — Geometry catalog test split per primitive ([#1560](https://github.com/sceneview/sceneview/issues/1560)).** The single `Geometry tab — every primitive adds, recolours and renders` test looped over all four primitives in one test body and still overran even the tripled `test.slow()` 180s budget on GPU-less CI runners. It is now four independent per-primitive tests plus a dedicated Clear-All test, so each heavy WebGL-interaction pass gets its own budget. Every primitive is still exercised; harness-only change, no demo code touched.
- **Device-QA web leg no longer times out on GPU-less CI runners ([#1560](https://github.com/sceneview/sceneview/issues/1560)).** Three Playwright catalog tests (`Models`, `Geometry`, `Settings`) failed with `Test timeout of 60000ms exceeded` on the GitHub Ubuntu runner: software-rasterised headless WebGL renders every Filament frame several times slower than a real GPU, so the looped model-load / geometry-add / render-quality-rebuild work overran the 60s budget. The demo itself was never hanging — it passed the same suite in seconds on a GPU-equipped host. Fix is in the harness, not the demo: the three heavy WebGL-interaction tests now call `test.slow()` (triples their timeout) and the Models test waits for the demo's real load-completion signal (`#loading-chip` clearing) via a new `waitForModelChipIdle` helper instead of a blind `waitForTimeout(2500)` — deterministic across fast local GPUs and slow CI runners, and faster locally because it no longer over-sleeps. The other 14 tests and the global 60s timeout are unchanged.
- web-demo: self-host the curated catalog GLB models and the IBL environment, and screenshot-sample the canvas in the Playwright suite, so the browser viewer renders and the device-QA suite passes. The catalog previously loaded every model — including the initial scene model — from jsDelivr's gh-proxy, which returns HTTP 403 for large GLB blobs under `assets/`; `initSceneView()` never resolved and the demo stayed stuck on its loading overlay. The 12-model catalog and `neutral_ibl.ktx` are now bundled under `samples/web-demo/src/jsMain/resources/` and a local `version.json` removes the last 404, eliminating all external asset failures. The `sampleCanvas` test helper now decodes a Playwright screenshot instead of `gl.readPixels`, which returned all-zero pixels on Filament's `preserveDrawingBuffer:false` context even when the canvas was visibly rendering (#1573, #1586, #1362).
- iOS demo: resolved Swift 6 concurrency warnings — the `OrbitalARDemo` and `DoublePendulumDemo` per-frame timer closures now hop onto the main actor before touching main-actor-isolated scene state, fixing real data-race risks. `DemoDeepLinkRegistry.destination(for:)` is now `@MainActor`-isolated, and `SceneViewDemoApp` adopts the modern two-parameter `onChange(of:)` signature (#1574).
- **Web demo: self-host the Filament/SceneView engine ([#1586](https://github.com/sceneview/sceneview/issues/1586)).** `samples/web-demo`'s `index.html` loaded `filament.js` and `sceneview.js` from `cdn.jsdelivr.net` — a jsDelivr hiccup 404'd both engine scripts and turned the Playwright device-QA suite red. Both files (plus `filament.wasm`) are now bundled under `src/jsMain/resources/js/` and referenced by relative path, so they ship with `jsBrowserDistribution`. Engine init is also decoupled from the default model load: a flaky model miss now surfaces as a transient chip instead of a fatal "Failed to initialize" overlay.
CI Gate no longer fails a PR when a Device QA workflow run was manually dispatched on the branch — Device QA check runs are excluded from the aggregator (#1588).
- Auto-fit camera framing is now reachable, and Android multi-model framing no longer bunches in the corner (#1595, #1596): the #1439 auto-fit API (`SceneAutoFitState`, `frameToContent`, `frameToBounds`) shipped with no caller — `SceneView` now exposes an `autoFitContent` parameter that drives it, moving the camera so the content fills the viewport regardless of the model's intrinsic glTF size. Both `SceneAutoFitState` and `SceneAutoCenterState` now use a diagonal-stability gate (Android port of web's `AutoCenterGate`, #1391 / #1540) instead of a first-frame latch, so an async model that finishes loading after a sibling already framed still triggers a re-frame. The gate also latches after a bounded number of passes so a perpetually-animated scene stops re-framing instead of fighting user interaction.
- Web: guard `SceneView.loadModel` against a use-after-free — a reloaded or destroyed model's pending `loadResources` callback no longer touches the freed `FilamentAsset` (#1597).
- `sceneview-web` `SceneView.loadModel` (#1597): the auto-center pass no longer frames the scene on a model whose `loadResources()` is still in flight (premature/wrong framing on an unreadable bounding box), and reloading the same model URL now destroys the prior `FilamentAsset` instead of orphaning it on the GPU — mirroring the `EnvironmentResourceTracker` leak-free-swap pattern from the IBL/skybox fix (#1496).
- **`assets/catalog.json` synced with bundled demo assets ([#1603](https://github.com/sceneview/sceneview/issues/1603)).** Two assets that ship in `samples/android-demo/src/main/assets/` and are actively referenced by demos were missing from the catalog that declares itself the "source of truth for all demo assets across platforms": the `threejs_soldier.glb` animated character (used by OrbitalARDemo, AnimationDemo, MultiModelDemo, the AR view, android-tv-demo, and ios-demo) and the `chinese_garden_2k.hdr` Poly Haven environment (used by EnvironmentDemo). Both now have full registry entries with `source` / `author` / `license` / `sourceUrl` provenance and `usedIn` arrays, matching the existing entry schema.
- **Device-QA AR leg no longer fails with a shell syntax error on its first CI run ([#1608](https://github.com/sceneview/sceneview/pull/1608)).** The `ar` job's ARCore sideload was an inline multi-line `if … fi` block in the `ReactiveCircus/android-emulator-runner` `script:`. That action runs each line of `script:` as a separate `sh -c`, so the standalone `if … then` line aborted with `Syntax error: end of file unexpected (expecting "fi")` and env vars never persisted across lines. The sideload logic moved into a dedicated `.claude/scripts/sideload-arcore.sh` helper (ABI-aware ARCore APK resolution from the public `google-ar` SDK release, honest non-fatal exit when ARCore is genuinely unavailable), and the workflow now invokes it as a single self-contained line — matching the working `android` job.
- **`EngineDestroyQueue` no longer resurrects a queue after engine teardown ([#1630](https://github.com/sceneview/sceneview/issues/1630)).** `EngineDestroyQueue.of(engine)` is backed by a `WeakHashMap`; a `Node.destroy()` arriving *after* `Engine.safeDestroy()` (a disposal order that does happen) used to `getOrPut` a fresh, live queue against the now-dead engine — the enqueued `Texture`/`Stream` was then never drained (no render loop left) → GPU-memory leak and latent use-after-free if Filament reused the handle. Teardown now records the engine as destroyed and removes its live map entry; a stale `of()` returns an already-drained queue whose `enqueueTexture`/`enqueueStream` destroy the resource immediately instead of queueing onto the dead engine. Dropping the live entry also fixes the `WeakHashMap`-value-strongly-references-key leak that pinned destroyed engines.
- **Web demo IBL no longer 404s on subpath deploys ([#1631](https://github.com/sceneview/sceneview/issues/1631)).** The default IBL URL in the vendored `sceneview.js` was the absolute path `/environments/neutral_ibl.ktx`, which resolved correctly from a domain root but 404'd on subpath deploys (e.g. `/sceneview/`), silently dropping image-based lighting to the synthetic SH fallback. It is now the relative path `environments/neutral_ibl.ktx`, matching the self-hosted `models/` convention and working on both layouts. Additionally, `.claude/scripts/validate-demo-assets.sh` no longer skips the entire vendored `web-demo` `resources/js/` tree — it now narrowly filters only the JSDoc placeholder literal `model.glb`, so a real broken asset literal in a future vendored js file is caught instead of silently passing.
- Web `AutoCenterGate` now latches after a bounded number of framing passes (`MAX_FRAMING_PASSES = 10`), so an animated / skeletal / physics scene whose union diagonal jitters every frame stops re-centring the camera forever — parity with Android's `FramingGate` ceiling (#1633, #1629).
- **Device-QA Android leg — CI emulator stability ([#1643](https://github.com/sceneview/sceneview/issues/1643)).** The Maestro flow ran correctly (app launch + camera-orbit swipes) but the CI emulator went offline mid-flow under the SceneView Filament 3D demo's GPU/RAM load. The `android` and `ar` device-QA jobs now boot the emulator with `-memory 4096`, and `qa-android-demos.sh` retries the Maestro flow once when — and only when — the device drops offline (a genuine demo failure, where the device stays online, is not retried).
- **Daily Maintenance workflow now actually fires on its cron schedule ([#1646](https://github.com/sceneview/sceneview/issues/1646)).** `.github/workflows/maintenance.yml` had never run from `schedule:` despite being marked active — its scheduled trigger had been registered against an account that is no longer active, so GitHub silently dropped every scheduled event for ~2 months (the workflow only ever ran when dispatched manually). Editing the `schedule:` block re-registers the cron under the current committing account. The cron is also moved off the congested top-of-hour (`0 7` → `11 7` UTC) so GitHub's scheduler no longer drops it in the hourly burst. All six maintenance jobs — dependency-version checks, stale-issue marking, the daily digest, agent-skill drift, CI health, and merged-branch pruning — now run unattended again.
- `ARRecorder` now converts the `Surface.ROTATION_*` constant passed as `recordingRotation` into degrees (`0`/`90`/`180`/`270`) before handing it to ARCore's `RecordingConfig.setRecordingRotation`, which expects degrees — not the ordinal (`0`/`1`/`2`/`3`). Previously a 90° capture was recorded as `1°`, leaving AR datasets stored sideways. New public `ARRecorder.surfaceRotationToDegrees(Int)` exposes the mapping. (#1648)
- Device-QA release gate (#1670): an all-skipped (or skipped-only) **advisory** leg is no longer aggregated as a hard `failed`. `device-qa.sh` now splits the verdict by leg weight — only a non-passing **required** leg (e.g. `web`) blocks the gate (`exit 1`, `releaseGate.verdict=blocked`), while a `failed` or honest `skipped` advisory leg (`android`/`ar`, e.g. the #1645 `ar-record-playback` skip on the CI emulator) surfaces as a `warn` and exits 0. `release-checklist.sh` section 14 then WARNs instead of FAILing for that case, so an honest environment skip no longer false-blocks a release tag.
- **Frame-deferred GPU texture destroy queue ([#874](https://github.com/sceneview/sceneview/issues/874)).** `ImageNode.destroy()` no longer leaks its Filament `Texture`, and `ViewNode.destroy()` no longer risks a native `SIGABRT` (`Invalid texture still bound to MaterialInstance`) from freeing its texture/stream too eagerly — both now enqueue their GPU resources on a per-`Engine` `EngineDestroyQueue` that destroys them a few rendered frames later, on the main thread, after Filament has reclaimed the bound `MaterialInstance`. High-churn UIs (feeds, infinite scrollers, particle emitters) that create many short-lived `ImageNode`s per `Engine` lifetime no longer accumulate GPU memory.
- DynamicSky demo now holds a "Loading helmet…" scrim until its model is ready, matching every other helmet-loading demo so no demo opens on a bare scene (#881).
- iOS demo: the Samples-tab full-screen demo cover now has an explicit Close button so a demo opened from the Samples list can always be dismissed back to the list (#1580).
- **Play Store deploy now self-heals a corrupt release AAB.** A truncated or zero-byte App Bundle from a flaky CI runner (which silently cost the v4.6.0 and v4.6.1 store releases, [#1412](https://github.com/sceneview/sceneview/issues/1412)/[#1415](https://github.com/sceneview/sceneview/issues/1415)) used to sail past gradle's exit 0 and only blow up at upload. The `Build release AAB` step now verifies the artifact is a readable zip and rebuilds once from clean before aborting, so a transient I/O flake no longer loses a release.

### Tests

- **device-QA: wire the AR replay leg into CI ([#1592](https://github.com/sceneview/sceneview/issues/1592)).** `.github/workflows/device-qa.yml` previously ran only the web (Playwright) and android (Maestro) legs; the AR replay harness (`ar-replay-qa.sh` + `ARReplayHarnessTest`, #1565) had no automated coverage, so the per-release device-QA pass effectively skipped AR. A new `ar` job boots an ARCore-capable emulator on the KVM-accelerated GitHub runner, sideloads Google Play Services for AR from the public google-ar SDK release, and runs `device-qa.sh --platform=ar --ci`, uploading the `ar-qa-summary.json` / `device-qa-report.json` artifact like the other legs.
- AR replay device-QA harness (`ARReplayHarnessTest` + `ar-replay-qa.sh`) no
  longer reports a misleading `pass` when the recorded ARCore session was never
  actually replayed. ARCore dataset playback needs camera-stream support the
  x86 software-GPU CI emulator does not provide, so `ar-record-playback`
  advancing `replayedFrames: 0` is now graded `skipped` (with the reason
  surfaced) rather than green `alive`. `ar-qa-summary.json` gains `skipped` /
  `failed` counts and a per-demo `reason`; `ar-replay-qa.sh` exits `3` and the
  device-QA AR leg records `skipped` — skips never count as passes (#1645).
- Device-QA CI: prebuild the android-demo APK in a separate cached `build-android-apk` job and install the artifact in the emulator legs (no cold build on the 2-core emulator runner); the release gate now grades `continue-on-error` legs — a red advisory leg (android/ar) surfaces as a WARN instead of being silent or hard-blocking (#1652, #1651).
- Device QA workflow (#1665): `workflow_dispatch` (release-gate) runs now get a unique, non-cancellable concurrency group keyed on `github.run_id`, so a subsequent push to `main` can no longer cancel an in-progress release-gate Device QA run. Push-triggered runs still share a `push` group and auto-cancel stale runs.
- **iOS device-QA now screen-records each run ([#1673](https://github.com/sceneview/sceneview/issues/1673)).** `ios-device-qa.sh` previously captured only one screenshot per demo; it now records the whole Maestro run via `xcrun simctl io recordVideo` (h264, to keep clear of the hevc frame-glitch artefacts), bringing the iOS leg to parity with the Android leg's screen recording. The recording is strictly best-effort — `recordVideo` needs hardware Metal, which CI VMs may lack, so a recording failure never fails the QA run — and is stopped with SIGINT so the `.mov` finalises cleanly. The file lands under `tools/qa-screenshots/ios/` (gitignored).
- **Web QA: pass `--enable-unsafe-swiftshader` to the Playwright Chromium runner ([#1674](https://github.com/sceneview/sceneview/issues/1674)).** Chrome removed the automatic SwiftShader fallback for WebGL. On a GPU-less CI runner ANGLE has no hardware path and nothing to fall back to, so WebGL context creation would fail outright — the Filament.js viewer would never get a context and the web-demo test suite could go green-on-nothing. The flag re-enables the software rasteriser so headless CI keeps a real WebGL context.
- **Web device-QA now screen-records every test ([#1674](https://github.com/sceneview/sceneview/issues/1674)).** The Playwright suite gains `video: 'on'`, bringing the web leg to parity with the Android and iOS device-QA legs so a 3D regression can be reviewed frame-by-frame. In headless Chromium the recording is software-rendered — the authoritative "did it render" assertion stays `helpers.ts:sampleCanvas` (a compositor screenshot with a luminance-variance check); the video is for human review. Recordings land under `samples/web-demo/test-results/` (gitignored).

### Docs

- **Device-QA harness documentation ([#1567](https://github.com/sceneview/sceneview/issues/1567)).** Documented the autonomous cross-platform device-QA harness: a new "Device QA" section in `CLAUDE.md` (how to run `device-qa.sh`, what each platform leg covers, where reports land, and the per-release-checkpoint mandate), a `CONTRIBUTING.md` subsection on adding/updating Maestro and Playwright flows when adding a demo, an orchestrator pointer in `.maestro/README.md`, and a Device QA section on the docs-site contributing page. Closes the final slice of umbrella #1560.
- Refreshed stale doc references: corrected the Android demo count to 43, reconciled the Maestro AR catalog count, updated the version note in `CLAUDE.md`, and repointed the iOS QA script reference.
- Align Apple platform minimums in docs with `SceneViewSwift/Package.swift` (iOS 18.0, macOS 15.0). (#1621)

- expanded the web-demo playwright suite into full per-tab / per-demo qa coverage — exercises every models, geometry, physics and settings demo with camera interaction, canvas render assertions and console-error checks, and emits a machine-readable `web-qa-summary.json` for the device-qa orchestrator (#1564)
- device-qa: added a maestro harness — `.maestro/android/` flows drive all 42 android demos like a real user (deep-link launch, camera-orbit drag, tap, one screenshot per demo, crash assertion), with per-category subflows and a `maestro.sh` auto-install helper; `qa-android-demos.sh` is now a thin maestro wrapper (#1562).
- device-qa: added an autonomous ar replay harness — `ARReplayHarnessTest` drives every augmented-reality demo through a recorded arcore session headless on the emulator (no physical device), asserts no crash, and emits a machine-readable `ar-qa-summary.json`; the `ar-replay-qa.sh` script is the orchestrator entrypoint that builds, runs and pulls the verdict (#1565).
- device-qa: fixed the android (maestro) leg of the device-QA CI workflow failing every run with the opaque `qa-android-demos.sh rc=1 (flow=3d-basics)`. The `Install Maestro` step (and the `maestro.sh` auto-install helper) fetched the installer from `get.maestro.dev`, which does not resolve — the canonical host is `get.maestro.mobile.dev`. Because the install ran as `curl … | bash`, the curl DNS failure was masked by the pipe and the step passed falsely-green, so Maestro was never on PATH and the flow could not run. The installer URL is corrected and the workflow step now runs under `set -o pipefail` with an explicit `test -x` on the binary, so a future install failure aborts loudly at the install step instead of surfacing as a misleading flow failure (#1560).

## v4.9.0 — Cross-platform demo catalogs, web auto-center parity & teardown safety (2026-05-16)

### Added

- `rememberPausableHeroYaw` gained an opt-in `idleResumeMillis` parameter: after the user stops interacting with the viewport, the hero auto-rotation gently resumes once the idle timeout elapses. Each gesture restarts the countdown, so the spin only comes back when interaction has truly stopped. Demos that omit the parameter keep the original pause-forever behaviour. Wired into the View Node demo. (#1440)
- AR Orbital demo: an on-screen directional arrow now appears at the viewport edge whenever the chase target (the orbiting toy car) is outside the camera frustum, pointing the user toward it so they know which way to turn to catch it. The arrow is driven by a per-frame `projection · view · worldPoint` projection that also handles the behind-the-camera case (#1482).
- **React Native & Flutter demo apps gain Materials / Animation / Environment demos ([#1362](https://github.com/sceneview/sceneview/issues/1362)).** Part of the cross-platform demo-parity umbrella: the RN and Flutter sample apps showcased only a small slice of the bridge surface. The `react-native-demo` app adds three tabs — **Materials** (lit PBR vs `unlit` geometry materials), **Animation** (auto-playing glTF clips via `ModelNode.animation`) and **Environment** (HDR image-based lighting plus the `autoCenterContent` toggle) — and its bottom tab bar is now horizontally scrollable so the catalog can keep growing. The `flutter-demo` app gains a dedicated **Demos** tab with four runnable per-feature scenes — Materials (`GeometryNode.unlit`), Model Animation (`loadModel` with animated Khronos assets), Environment (`setEnvironment` + `setAutoCenterContent`) and Camera Modes (`setCameraControlMode`) — complementing the existing flat "Bridge Features" reference checklist. Every demo uses only APIs the Fabric / PlatformView bridges actually expose; no dead UI for un-bridged features.

### Changed

- **AR demo:** Strengthened the Record & Playback demo's end-of-recording UX. After Stop, the saved-recording callout now explains where the file lives and that it is a standard MP4 carrying ARCore data tracks, and offers Replay, Share, Open (play as a normal video) and Export-to-Downloads. The just-recorded file is highlighted with a "Just recorded" badge in the Playback list so it is obviously discoverable. ([#1438](https://github.com/sceneview/sceneview/issues/1438))
- **Double Pendulum demo reworked with an original SceneView visual identity and fixed camera framing ([#1481](https://github.com/sceneview/sceneview/issues/1481)).** The Android demo keeps the genuine shared-KMP double-pendulum physics but is restaged as a ball-and-rod "Orbital Pendulum": glossy weighted bobs drawn at each link's actual point mass, an asymmetric long-lead / short-trailing arm ratio, a SceneView brand-token palette (primary blue → gradient violet), a warm studio backdrop, and an off-axis key/rim light rig — so it reads as SceneView's own demo rather than a port. The camera now auto-frames the full reachable swing envelope (it targets the swing-disc centre and backs off proportionally to the arm reach), fixing the poorly-aimed framing flagged in QA.

### Fixed

- AR camera background no longer renders washed-out / low-contrast. `createARView` was using `ToneMapper.Linear`, but the camera-stream shader's `inverseTonemapSRGB()` pre-applies an *inverse Filmic* tone-map curve (`Inverse_Tonemap_Filmic(pow(c, 2.2))`) that only round-trips back to the original camera pixels when the View re-applies the matching **Filmic** tone mapper. With `Linear` the inverse curve was left uncancelled, flattening the live camera feed. The AR view now uses `ToneMapper.Filmic` and keeps bloom/AO off so the background is faithful to the real camera image (#1434).
- **AR placed content no longer vanishes on transient plane loss, and placed models no longer flash black ([#1435](https://github.com/sceneview/sceneview/issues/1435)).** In the Android demo's `ARPlacementDemo` and `ARInstantPlacementDemo`, each `AnchorNode` now keeps rendering its model while the ARCore anchor is `PAUSED` (it holds its last known pose) instead of disappearing the moment the camera looks away from the plane — content only hides on a permanent `STOPPED` anchor. Newly placed models are also kept hidden for a short settle window after loading so Filament finishes uploading their textures, eliminating the black flash on placement. Behaviour is centralised in the new `demos/internal/ArPlacement` helper with JVM regression tests.
- AR Face Mesh demo: the face mesh now actually tracks. `Session.Feature.FRONT_CAMERA` only makes the front camera *eligible* — the session stayed on the default BACK camera config, so `AugmentedFaceMode.MESH3D` produced zero trackables and no mesh ever appeared. The demo now passes `sessionCameraConfig = ::frontCameraConfig` so ARCore opens the selfie camera. Added a public `frontCameraConfig(session)` helper in `arsceneview` for any Augmented Faces consumer (#1436).
- Image Tracking (Augmented Images) demo now shows an in-app "what to scan" card displaying the actual reference target image, so the user knows exactly which image to point the camera at. The card auto-collapses to a chip once an image is recognised and can be re-expanded by tapping it (#1437).
- **Animation demo model no longer renders as a black silhouette against the HDR environment ([#1468](https://github.com/sceneview/sceneview/issues/1468)).** The demo's `rooftop_night` skybox renders at full HDR luminance, but the image-based light defaulted to only 5,000 lux — half SceneView's balanced 10k default — so the soldier read as unlit against the bright sky. The default IBL intensity now matches the balanced 10k default; the slider still lets users dial down for a darker, atmospheric look.
- **Video demo:** the viewport background is now a clean neutral black instead of a light near-white wash (or a stale gradient leftover from the previous screen). The demo loaded its HDR environment with `createSkybox = false`, leaving a `null` skybox — Filament does not clear background pixels without a skybox, so the uncleared swap-chain buffer leaked through and broke the dark theme every other demo uses. The HDR IBL is now paired with an explicit opaque black skybox (#1469).
- Text Nodes demo: pulled the camera back so the top "Hello SceneView" label is no longer clipped at the top viewport edge in the default framing.
- **Gesture Editing demo — the X/Y/Z axis gizmo is now bounded to the model instead of running off all four screen edges ([#1471](https://github.com/sceneview/sceneview/issues/1471)).** The world-origin axis gizmo was 1 m long while the helmet renders at 0.3 m, so with the camera framed on the small helmet each axis tip extended well past the viewport and looked like an infinite debug line. The gizmo length is now derived from the model scale (1.5× the helmet's `scaleToUnits`), keeping each axis just longer than the model's bounding box as a clear, bounded reference.
- **ViewNode demo no longer shows a black viewport for several seconds on entry ([#1472](https://github.com/sceneview/sceneview/issues/1472)).** The scaffold's first-frame scrim dismissed on the SceneView's very first Filament frame, which arrives almost instantly because the quads carry no asset to load — but a `ViewNode` renders its embedded Compose card to an off-screen window and uploads it as a texture only a handful of frames later, leaving two black quads exposed. The demo now holds the loading scrim for a short frame warm-up so the embedded card texture is uploaded before the scrim cross-fades out.
- Android demo: AR demos (Record & Playback, Terrain Anchors) now show a "Starting camera…" spinner overlay while ARCore initializes the camera, instead of a bare black viewport that read as a frozen/broken screen. The overlay clears on the first delivered AR frame.
- AR Record & Playback demo: the "REC" elapsed-time pill is now inset below the system bars so it no longer overlaps the status bar / notch / camera cutout.
- **AR Image Stabilization demo: the EIS toggle now actually switches stabilization on and off ([#1475](https://github.com/sceneview/sceneview/issues/1475)).** The demo previously rebuilt the entire `ARSceneView` (`key(eisOn)`) on every toggle, which tore down the ARCore session and silently invalidated the placed helmet anchor — the demo's only reference object vanished the instant the user flipped EIS, so an "EIS ON" state was never visible. The toggle now reconfigures `Config.ImageStabilizationMode` live via `Session.configure` (a runtime-mutable flag), keeping the session, tracking, and anchor intact. The status pill reflects what ARCore actually applied — "EIS ON", "EIS OFF", or "EIS UNSUPPORTED" when the device or recording can't do EIS — instead of a stuck "OFF". Android demo app only.
- AR Instant Placement demo: replaced the tall per-model status column (which overflowed the top third of the viewport and overlapped placed models) with a single compact badge for the most recently placed model, and gave the "Clear All" button a solid filled background so it is legible over the camera feed.
- **VideoNode / MaterialLoader:** hardened MaterialInstance teardown against native crashes (#1539, follow-up to #1497). The `VideoNode.materialInstance` setter now drains the frame pipeline before freeing the superseded MaterialInstance — previously it was freed while the external video texture was still GPU-bound, the same `Invalid texture still bound to MaterialInstance` SIGABRT #1497 fixed for `destroy()`. `MaterialLoader.destroyMaterialInstance` now removes the instance atomically, so two threads can no longer both pass the tracking guard and double-destroy the same native MaterialInstance.
- **`sceneview-web`: multi-model scenes no longer render bunched in a corner, and the camera now auto-fits content size ([#1540](https://github.com/sceneview/sceneview/issues/1540)).** `SceneView`'s `autoCenterContent` pass latched on the *first* render frame with non-degenerate bounds, so an async model that finished loading *after* a sibling had already centred never re-centred — the multi-model regression #1391 fixed on iOS. The web `AutoCenterGate` now ports the iOS #1391 logic: it re-frames on every union-diagonal growth and latches only once the union diagonal is stable across consecutive frames, so a deferred async model always pulls the framing back to the combined extent. The pass also now calls `fitToModels()` to auto-dolly the orbit camera to the content size — previously the web viewer only auto-centred and never auto-fit, mis-framing very small or very large models. The union-AABB computation is shared between the auto-center path and `fitToModels()` (no duplicate read).
- **Web demo tab navigation no longer double-fires on every click ([#1541](https://github.com/sceneview/sceneview/issues/1541)).** Tab buttons were wired twice — once by the inline JS in `index.html` (the shipped runtime, loaded via CDN `sceneview.js`) and again by a duplicate `setupTabs()` in the Gradle-compiled Kotlin `Main.kt`, which is not referenced by the page. The dead Kotlin tab path has been removed so each `.tab-btn` click runs a single `switchTab` handler.
- Docs: reconciled `samples/README.md` with the actual `DemoRegistry` — corrected the android-demo demo count (now 42: 28 non-AR + 14 AR), fixed the tab list (Explore, AR View, Samples, About), and removed rows advertising demos that don't exist (`gltf-camera`, `ar-point-cloud`, `autopilot-demo`).
- Docs: fixed HDR asset paths across `samples/recipes/` (`environment-lighting.md`, `multi-model.md`, `editable-model.md`) to match the bundled `environments/*_2k.hdr` files, and corrected the Flutter `features_page.dart` snippet to reference the real `environments/studio_small.hdr` asset.
- `CI Gate` no longer flips red on fork PRs that are merely awaiting maintainer approval. GitHub reports such checks with conclusion `action_required`; the aggregator now treats `action_required` as pending-equivalent (it keeps waiting for the run to be approved-then-completed) instead of counting it in the failed set. It also added a name-based core-check guard so the gate cannot exit green before every always-run `ci.yml` check (`Detect changed paths`, `Repo hygiene checks`, `Quality gate (full)`) has registered for the head SHA — closing a race where a slow-to-register workflow could be missed. The guard is a no-op for genuine docs-only PRs (where `ci.yml` is path-filtered out entirely), so light PRs are never blocked (#1543).
- **Docs version staleness fixed ([#1544](https://github.com/sceneview/sceneview/issues/1544)).** `CLAUDE.md`'s "Latest release" block claimed `v4.4.0` and instructed AI sessions to treat it as the latest version — 4 minors stale (repo is `4.8.0`); it is now version-agnostic and points at `gradle.properties:VERSION_NAME` as the single source of truth. `README.md`'s SwiftPM install snippets (`from: 4.4.0` / `(SPM, from 4.4.0)`) are bumped to `4.8.0`, and a broken intra-repo anchor in `CLAUDE.md` is corrected. `sync-versions.sh` now also recognises the unquoted `from: X.Y.Z` SwiftPM prose form used in `README.md`, so this drift is caught automatically on future releases.

## v4.8.0 — Bottom-sheet settings, web & RN bridge fixes (2026-05-16)

### Added

- **Demo settings bottom sheet gains a header, "Reset" button and status-aware peek chip ([#1154](https://github.com/sceneview/sceneview/issues/1154)).** `DemoScaffold` (Android demo app) now renders a pinned sheet header; demos can opt into an `onResetSettings` callback to show a "Reset" text button that restores their defaults, and into a `peekHeader` string so the closed peek chip can surface a short live status (e.g. "3 anchors placed") instead of the generic "Settings" label. Drag-down-to-dismiss now fires a subtle haptic tick, and the previously hardcoded chip/FAB labels moved to string resources. `FogDemo` wires up the new reset button as the reference adoption. Part of the #1154 umbrella (Stage 3 polish, Android slice).

### Fixed

- **Play Store listing sync no longer marks a successful deploy red ([#1386](https://github.com/sceneview/sceneview/issues/1386)).** The `Sync Play Store listing (en-US)` job in `play-store.yml` is now `continue-on-error: true` and swallows a `403 Forbidden` (missing 'Edit store listing' permission) with a warning. The AAB build/publish jobs stay strict, so the listing-text sync is best-effort and can never block a release.
- **iOS `SceneView` now frames multi-model scenes by the union of all loaded content ([#1391](https://github.com/sceneview/sceneview/issues/1391)).** The fit-to-bounds camera pass added in #1385 framed a single content entity and latched on the first model that loaded, so multi-model demos like `Multi-Model Park` rendered their streamed models bunched in a corner of an otherwise empty viewport. The pass now computes the union axis-aligned bounding box of every content entity, centres the camera on the union centre, dollies to fit the whole union, and re-frames as each streamed/async model finishes loading — latching only once the union stabilises. Single-model demos are unaffected. Part of #1373.
- Model Viewer demo: the top-right "Streaming…" asset-source pill now clears to "Streamed" once a streamed model finishes loading, instead of staying pinned for the whole session. The streamed model instance is now loaded from a stable composable slot so the load-completion state invalidates the chip correctly (#1464).
- Scene Gallery demo no longer shows contradictory status labels: the top-right asset-source chip now stays "Streaming…" until the model is fully loaded, matching the centre loading overlay, instead of flipping to "Streamed (cached)" the moment the file path resolves (#1465).
- **Light Types demo:** re-scaled the backdrop wall from 3 × 2.4 m down to 1.6 × 1.2 m and re-centred it on the helmet. The oversized quad previously filled ~⅔ of the viewport with a hard diagonal top edge, cramming the model into the lower-left corner (#1466).
- Movable Light demo: the draggable yellow light handle is now persistently visible. The light's orbit radius was reduced from 1.5 m to 0.75 m and its elevation clamped to ±50° so the handle stays inside the fixed camera's frustum for the whole drag, instead of swinging off-screen for most of the orbit. The handle sphere is also slightly larger (radius 0.09 m) so it reads as a clear, aimable target (#1467).
- **`sync-versions.sh` no longer bumps the Flutter/RN plugins' consumed SceneView dependency ([#1494](https://github.com/sceneview/sceneview/issues/1494)).** The `io.github.sceneview:(ar)sceneview:X.Y.Z` coordinate in the Flutter plugin and React Native bridge Gradle files is a dependency on the *published* Maven Central artifact, so it must lag to the last released version — pointing it at the in-flight release broke the `Build flutter-demo APK` CI check during v4.7.0. The script now reports these consumed-dependency coordinates WARN-only (never MISMATCH) and excludes them from every `--fix` sweep, while the plugins' own package versions still bump correctly.
- **Web `SceneView` no longer leaks IBL + skybox GPU resources ([#1496](https://github.com/sceneview/sceneview/issues/1496)).** `sceneview-web`'s `SceneView.loadEnvironment` created a Filament `IndirectLight` and `Skybox` but never tracked the handles — `destroy()` left both resources allocated on the GPU, and a 2nd `loadEnvironment` / `loadDefaultEnvironment` call overwrote the scene's environment while orphaning the previous handle. The handles are now tracked by an `EnvironmentResourceTracker`, the previous IBL/skybox is destroyed before a replacement is bound, and `destroy()` detaches and destroys both.
- **CI hygiene cluster 2 ([#1500](https://github.com/sceneview/sceneview/issues/1500)).** Narrowed `app-store.yml`'s tag trigger from `v*` to the strict `v[0-9]+.[0-9]+.[0-9]+` semver glob so pre-release or stray tags can no longer fire an App Store deploy; `ci-gate.yml` no longer treats a `stale` check conclusion as a failure (a `stale` run is superseded, not broken) and now refuses to report green until at least one non-self check run has registered, closing a warm-up race; `render-tests.yml` `paths` now also matches `build.gradle*`/`settings.gradle*`/`gradle.properties` so renderer-affecting build-script changes are not skipped; and `release.yml`'s dead cross-run `dokka-api-docs` artifact upload was removed (`download-artifact` only resolves same-run artifacts).
- **React Native Android bridge now compiles against the current SceneView 4.7.0 ([#1501](https://github.com/sceneview/sceneview/issues/1501)).** `react-native/react-native-sceneview/android/build.gradle.kts` depended on the year-old `io.github.sceneview:sceneview:3.6.0` / `arsceneview:3.6.0` — pre the v3.6 `Scene`-to-`SceneView` composable rename and missing every 4.x feature — while the published `@sceneview-sdk/react-native` package is versioned 4.7.0. The Maven coordinates are bumped to the last-published `4.7.0` (a *consumed* dependency, so it tracks the released artifact per [#1494](https://github.com/sceneview/sceneview/issues/1494)). The stale `compose-bom:2024.06.00` is aligned to the repo's `2026.05.00`, and the obsolete `composeOptions { kotlinCompilerExtensionVersion }` block is replaced by the Kotlin 2.x Compose Compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`). The bridge Kotlin already targeted the 4.x API surface, so no source migration was needed — only the build configuration lagged.
- **Refreshed stale docs flagged by the post-v4.7.0 audit ([#1502](https://github.com/sceneview/sceneview/issues/1502)).** `docs/docs/desktop-filament.md` pinned Filament `v1.70.1` throughout (clone/download archives and the `filament-android:1.70.1` AAR), but the repo runtime is `1.71.0` and the committed `.filamat` blobs are v71 — all six references now read `1.71.0`. `CLAUDE.md`'s pre-push unit-test command mixed `:sceneview:test` with `:arsceneview:testDebugUnitTest`; both modules now use `testDebugUnitTest`, consistent with CI. The fully-completed `docs/v3.6.0-roadmap.md` (all 14 issues done, predating the 4.x line) is moved to `docs/archive/v3.6.0-roadmap.md`.
- **web-demo: `Main.kt`'s stale tab switcher fixed ([#1503](https://github.com/sceneview/sceneview/issues/1503)).** The Kotlin/JS `switchTab()` toggled `panel-viewer`/`panel-geometry`, but `index.html` ships `panel-models`, `panel-geometry`, `panel-physics`, `panel-settings` — a drift that left 3 of 4 panels unreachable from that entry point, with `currentTab` also defaulting to the non-existent `"viewer"`. `switchTab()` now toggles all four shipped panels and `currentTab` defaults to `"models"`. The shipped `index.html` inline-JS demo (the actual runtime path) already wires all four tabs plus the Double Pendulum physics and Settings panels correctly. The stale `samples/web-demo/README.md` and the Playwright suite were also refreshed to match the shipped 4-tab UI, and a new `render.spec.ts` test clicks every `.tab-btn` and asserts the matching `panel-*` becomes `active`.
- **android-demo: honest demo-status badges, correct AR icon, and a fixed Blender recipe path ([#1504](https://github.com/sceneview/sceneview/issues/1504)).** The `DemoStatus` enum and `StatusChip` UI were fully built, but all 41 `ALL_DEMOS` entries used the default `Working`, leaving the honest-badge feature inert. The four ARCore Geospatial / Cloud Anchor demos (`ar-cloud-anchor`, `ar-streetscape`, `ar-terrain`, `ar-rooftop`) — which all require a Cloud project API key not wired into default builds — are now marked `KnownIssue`, so they surface a "Preview" chip instead of lying as all-green. `ar-instant-placement` no longer uses the `HourglassEmpty` ('coming soon') glyph despite routing to a real working demo — it now uses the placement-themed `Bolt` icon. Finally, `samples/recipes/blender-to-sceneview.md` no longer points at the non-existent concrete path `samples/android-demo/src/main/assets/models/car.glb`; it now shows an illustrative relative path with a note clarifying that `car.glb` is the reader's own exported file and pointing at the real sample models.
- **`VideoNode.destroy()` no longer risks a native SIGABRT ([#1497](https://github.com/sceneview/sceneview/issues/1497)).** `destroy()` freed the external `Texture`/`Stream` immediately after destroying the `MaterialInstance` that referenced them — the exact ordering that triggers Filament's `Invalid texture still bound to MaterialInstance` abort, since MaterialInstance reclamation is coupled to the render loop rather than to the `destroy()` call site. Teardown now drains the frame pipeline (`Engine.drainFramePipeline()`) between destroying the MaterialInstance and freeing the external texture/stream, mirroring the safe pattern documented on the sibling `ImageNode`. Adds `VideoNodeTest` pinning the teardown ordering.

## v4.7.0 — Bridge expansion, slimmer APK & demo-app polish (2026-05-16)

### Added

- **Animated 3D particle background on the Samples home ([#1488](https://github.com/sceneview/sceneview/issues/1488)).** The android-demo Samples tab now renders a subtle, on-brand particle field behind the demo grid — a `SceneView` scene of drifting low-poly spheres with a slow auto-orbiting camera, seeded per launch. A first visual experiment that dogfoods the SDK on the app's own home screen; tuning constants live in `ParticleBackground.kt`.

### Changed

- **Daily maintenance digest in CI ([#1303](https://github.com/sceneview/sceneview/issues/1303)).** `maintenance.yml` now runs a report-only mirror of the `/maintain` skill — a new `.claude/scripts/maintenance-report.sh` produces a structured table (CI health, open issues/PRs, dependency drift, version sync, agent-skill drift, release decision) emitted to the workflow step-summary and an auto-updated tracking issue. The script is strictly read-only, retries transient API failures, and never blocks the workflow.
- **Consolidated the three PR CI workflows into one ([#1370](https://github.com/sceneview/sceneview/issues/1370)).** `ci.yml`, `pr-check.yml` and `quality-gate.yml` are merged into a single `ci.yml` with ONE `changes` path-detection job gating every downstream job (`build`, `lint`, `unit-test`, `web-desktop`, `flutter-demo`, `compile-kmp`, `repo-hygiene`, `quality-gate`). Eliminates the duplicate `dorny/paths-filter` run and two redundant checkout + JDK + Gradle-cache-restore chains per PR. The `CI Gate` aggregator is unchanged — it polls the Checks API and treats `skipped` as passing. `Closes #1370`.
- **Dropped the dead `desktop-demo` compile step from `ci.yml` ([#1396](https://github.com/sceneview/sceneview/issues/1396)).** The `web-desktop` job ran `:samples:desktop-demo:compileKotlinDesktop` with `continue-on-error: true` permanently, so it could never fail the build — it only burned runner minutes. Since `samples/desktop-demo` is a deliberate Compose Canvas wireframe placeholder (it does not use SceneView or Filament), the step was removed along with the now-redundant `samples/desktop-demo/**` path filter. The job is renamed "Build web targets" to match what it actually does.
- **Android demo APK slimmed ~36% ([#934](https://github.com/sceneview/sceneview/issues/934)).** Bundled demo assets are now compressed with no visible quality loss: the 7 HDR environments are downsampled 2K → 1K in linear-radiance space (energy-preserving 2×2 box average, 42 MB → 11 MB) and the 6 GLB models use `KHR_draco_mesh_compression` geometry plus `EXT_texture_webp` textures (21 MB → 9.5 MB), both decoded natively by Filament's bundled `gltfio`. `android-demo` `build.gradle` also drops duplicate transitive licence/metadata files via `packaging.resources.excludes`. Release APK: **98.6 MB → 62.7 MB**. No code or API change — all 37 demos load the same asset paths.
- **Playground model picker now shows thumbnails ([#953](https://github.com/sceneview/sceneview/issues/953)).** The website playground listed 30+ models in a plain `<select>` dropdown — a weak marketing surface next to Sketchfab/Babylon. The picker is now a visual thumbnail gallery: each model is a 256×256 self-hosted WebP preview (rendered offline, ~200 KB total for all 34, lazy-loaded), grouped by category, with light/dark styling from `DESIGN.md`. The native `<select>` is kept hidden as the accessible source of truth so all existing preview logic and keyboard access are unchanged. The "Open in Cursor/Windsurf/Copilot" AI links are relabelled "Open Cursor/Windsurf/Copilot" since those tools have no prompt deep-link and only open their homepage.

### Fixed

- **Demo Settings sheet no longer dismisses itself instantly ([#1420](https://github.com/sceneview/sceneview/issues/1420)).** The `DemoSettingsLayer` bottom sheet treated its initial `SheetValue.Hidden` state as a dismissal, slamming the panel shut before it could animate open — making the Settings controls dead in every demo. `Hidden` is now only honoured as a dismiss once the sheet has actually settled in a shown detent.
- **Light Types demo no longer renders an empty black scene ([#1421](https://github.com/sceneview/sceneview/issues/1421)).** The demo composes a helmet, an off-centre backdrop wall, and a light-source marker; `autoCenterContent` centred the union of all three, shifting the helmet far off the hero camera's fixed orbit pivot. The demo now passes `autoCenterContent = false` so each node keeps its authored position and the camera frames the lit helmet as intended.
- **Multi Model demo no longer hangs on "Loading 4 models…" ([#1422](https://github.com/sceneview/sceneview/issues/1422)).** The demo loaded its four resolver-staged GLBs through the two-argument `rememberModelInstance(modelLoader, String)`, which Kotlin overload resolution binds to the asset-path overload — so the `file://` cache URI was handed to `AssetManager.open`, threw `FileNotFoundException`, and every model instance stayed `null`. The demo now loads the local file via `ModelLoader.loadModelInstance`, which understands `file://` URIs, so the scene reaches a rendered state.
- **Streamed demos no longer hang forever on their loading spinner ([#1423](https://github.com/sceneview/sceneview/issues/1423)).** `SketchfabAssetResolver` staged the offline fallback (and network downloads) by opening an output stream directly on the shared cache path. When a demo resolved the same model from both `prefetchAll` and its per-slug `produceState` at once, the two writers interleaved and left a truncated GLB on disk that poisoned the cache permanently — the PBR Materials, Multi Model and Scene Gallery demos stayed stuck on "Streaming material…" / "Loading…" indefinitely. The resolver now stages into a per-call temp file and atomically renames it into place, and re-stages any cached fallback whose `glTF` magic header is missing so an already-poisoned cache self-heals.
- **Explore tab no longer crashes and loads thumbnails reliably ([#1424](https://github.com/sceneview/sceneview/issues/1424)).** `AsyncNetworkImage` decoded Sketchfab thumbnails at full resolution into `ARGB_8888` bitmaps; ~30 oversized images at once exhausted the heap and the tab crashed with `OutOfMemoryError` — which the `runCatching` fetch path never caught because an `Error` is not an `Exception`. Decoding is now downsampled via a two-pass `BitmapFactory` `inSampleSize`, the fetch path catches `Throwable` and degrades to a silent placeholder, the in-memory cache is bounded by entry count (so one oversized bitmap can no longer evict every other thumbnail and cause the "appears one time in ten" flicker), and the shared OkHttp client now has connect/read/call timeouts so a stalled CDN connection can't pin an IO thread and leave carousels spinning.
- **Edge-to-edge insets in the Android demo ([#1425](https://github.com/sceneview/sceneview/issues/1425)).** Removed the large empty gap above the "Samples" tab header — the nested `LargeTopAppBar` no longer double-counts the status-bar inset already applied by the root `Scaffold`. The in-app "Update ready / Restart" banner is now z-ordered above every screen and inset below the status bar, so it is no longer clipped behind a demo's top app bar.
- **Flat geometry no longer vanishes when rotated ([#1426](https://github.com/sceneview/sceneview/issues/1426)).** The Geometry Primitives plane and the Text Nodes labels now use double-sided materials, so they stay visible when their back face turns toward the camera instead of blinking out under single-sided culling.
- **Camera feel re-tuned across 3D model demos ([#1427](https://github.com/sceneview/sceneview/issues/1427)).** The default camera now sits further back (`DefaultCameraNode` Z `2.0` → `2.75`, Y `0.3` → `0.4`) so origin-placed models are no longer framed too tight. Orbit/pan sensitivity is reduced (`orbitSpeed` `0.005` → `0.003`) so finger drag tracks the model more calmly, and pinch-zoom is made more responsive (`DEFAULT_PINCH_ZOOM_SPEED` `1/30` → `1/18`) so zooming no longer feels sluggish.
- Camera Controls demo: the **Free Flight** camera mode no longer renders a black
  viewport on launch and is now usable on touch devices. Free-flight previously
  spawned the camera at the origin — inside the helmet model — and offered no touch
  gesture to translate (Filament drives flight movement from held keys). The demo now
  sets `flightStartPosition` to the framed home position and shows an on-screen
  movement pad (forward / back / strafe / up / down) wired to the manipulator's
  key controls. Each mode also shows a short usage hint. (#1428)
- **Gesture Editing demo — rotate/scale gestures now actually transform the helmet ([#1429](https://github.com/sceneview/sceneview/issues/1429)).** The 60 Hz live-transform poll was recomposing the whole demo (and the `SceneView`) every frame, which re-ran `SceneScope.ModelNode`'s declared-transform `SideEffect` and reverted every gesture-driven rotation before it was visible. The poll is now isolated in its own `LiveTransformOverlay` composable so gesture transforms persist. The "Moving camera" gesture-mode pill was also moved off the top-center anchor to the top-start corner so it no longer overlaps the top-end pos/rot readout card.
- **Collision & Hit Test demo — hit-test fires outside object bounds ([#1430](https://github.com/sceneview/sceneview/issues/1430)).** The demo hand-authors five shapes and a camera manipulator pinned to their row, but left `autoCenterContent` at its `true` default. Auto-centering recentred the shapes to the scene origin, off the camera's orbit pivot — so the camera orbited empty space and taps no longer lined up with the rendered shapes. Disabled `autoCenterContent` for this demo (same root cause as the LightingDemo #1421 fix) so each shape keeps its authored position and collision matches what is shown.
- **Custom Mesh auto-rotate no longer stops on a stray tap ([#1431](https://github.com/sceneview/sceneview/issues/1431)).** The "Auto-Rotate" molecule demo silently paused its spin the first time the viewport was touched, so it looked like the rotation stopped on its own. Rotation is now continuous and controlled solely by the explicit Auto-Rotate switch.
- **Lines & Paths — the Stroke Width slider now visibly rebalances the lines ([#1432](https://github.com/sceneview/sceneview/issues/1432)).** Moving the line-width control in Settings appeared to do nothing. The per-point stroke beads now have a fixed base geometry and are driven by `Scale` — a transform that `SphereNode` re-applies unconditionally every recomposition — so dragging the slider tracks every bead every frame with no vertex-buffer rebuild. The beads were also rebalanced (smaller base radius, denser run along the line) so the default reads as a clean medium stroke instead of a string of oversized spheres, and the line beads now overlap into a continuous tube at higher widths.
- **Scene Gallery demo no longer appears stuck in a loop ([#1433](https://github.com/sceneview/sceneview/issues/1433)).** The four gallery chips carried unverified placeholder Sketchfab uids, so every chip fell back to a bundled model — and two chips ("Reading Lamp" + "Wooden Chair") shared the *same* fallback GLB, making the chips look inert. Each gallery entry now points at a distinct bundled model with an honest label, so switching chips visibly changes the rendered model.
- Physics demo: the rigid-body simulation now actually runs — the `SphereNode` composable no longer re-pushes `position`/`rotation`/`scale` to the node on every recomposition, which was clobbering the per-frame position written by `PhysicsBody` (the same fix already applied to the bare `Node` composable). Spheres now drop, bounce, and settle as intended ([#1463](https://github.com/sceneview/sceneview/issues/1463)).
- Physics demo: re-framed the camera so the grey ground plane is vertically centred in the viewport instead of being shoved into the bottom third with its near edge clipped.
- **AR placement demos no longer drop the helmet face-down ([#1477](https://github.com/sceneview/sceneview/issues/1477)).** The bundled Khronos *DamagedHelmet* GLB ships a residual +90° X root rotation from its Blender export, which landed it nose-into-the-floor when placed under an ARCore plane anchor. The Cloud Anchor, Tap to Place, and Depth Occlusion demos now apply a shared `-90°` X correcting rotation at placement time so the helmet stands upright, visor forward. Other bundled cycle models are unaffected.
- **Playground `Duck` model no longer 404s ([#1487](https://github.com/sceneview/sceneview/issues/1487)).** Added the self-hosted `Duck.glb` asset so the playground's `Duck` model option and the Spring Physics example load correctly instead of issuing a 404 for the missing file.

### Added

- **Flutter bridge: `addGeometry` / `addLight` are now rendered natively on Android ([#909](https://github.com/sceneview/sceneview/issues/909)).** These two `SceneViewController` methods previously returned `result.success(null)` without drawing anything — the Flutter demo's feature badges did not reflect that. The Android `SceneViewPlugin` now appends to reactive `geometryNodes` / `lightNodes` Compose state lists, so `cube`/`box`, `sphere`, `cylinder` and `plane` primitives and `directional`/`point`/`spot` lights render in both `SceneView` and `ARSceneView`, matching the React Native bridge. Material instances are cached per `(color, unlit)` and released on dispose. The Flutter demo's GeometryNode / LightNode feature cards are re-labelled "Android only" (the iOS RealityKit port stays tracked under the #909 umbrella). First Dart unit tests for the plugin (data-class serialization + controller attach guards) were also added.
- **Regression tests for the `samples/common` shared helpers ([#972](https://github.com/sceneview/sceneview/issues/972)).** The `LifecycleAwareLaunchedEffect` (#936) and `rememberMaterialInstance` / `rememberUnlitMaterialInstance` (#937) helpers shipped with no tests. New JVM/Robolectric suites pin their contracts: `LifecycleAwareLaunchedEffectTest` drives a real `TestLifecycleOwner` to assert the body cancels on `onStop` and re-runs from the top on `onStart`, and `RememberMaterialInstanceTest` fails if a future edit puts `metallic`/`roughness`/`reflectance` back into the `remember(...)` key (the 60 Hz `MaterialInstance` churn caught by the #937 review). `:samples:common:testDebugUnitTest` is now wired into the CI unit-test step.

### Changed

- **Render tests are no longer orphan `@Ignore`'d code ([#912](https://github.com/sceneview/sceneview/issues/912)).** The five headless Filament render-test classes (`RenderSmokeTest`, `LightingRenderTest`, `GeometryRenderTest`, `VisualVerificationTest`, `DemoParametersRenderTest`) were wholly class-level `@Ignore`'d — compiled but never executed, so they could never catch a regression and silently drifted. They now use a runtime capability gate (`RenderTestCapabilities.assumeGpuReadbackAvailable()`): the tests **run** on a hardware-GPU runner that opts in via `-Pandroid.testInstrumentationRunnerArguments.gpuReadback=true`, and cleanly **skip** (JUnit assumption, not orphan, not failure) on the SwiftShader / Apple-Silicon emulator where Filament's async `readPixels` callback never fires (the harness limitation tracked in [#803](https://github.com/sceneview/sceneview/issues/803)). `Closes #912`.

## v4.6.2 — CI hotfix: land the demo app on the Play Store + API docs (2026-05-16)

### Fixed

- **Play Store release deploy no longer blocked by AAB validation ([#1416](https://github.com/sceneview/sceneview/issues/1416)).** The pre-upload guard now introspects Android App Bundles with `bundletool dump manifest` (the correct tool for `.aab` files) instead of `aapt2`, which can only read APKs and was mis-reporting bundles as corrupt. The validation step is also marked `continue-on-error` so a tooling gap can never veto a release. This unblocks the demo-app Play Store deploy that missed v4.6.0 and v4.6.1.
- **API-docs deploy no longer races the website deploy on a release tag ([#1417](https://github.com/sceneview/sceneview/issues/1417)).** `release.yml`'s Dokka deploy and `docs.yml`'s site deploy both push to the external `sceneview.github.io` repo; on a release tag they ran concurrently and the second push failed non-fast-forward. A shared cross-workflow `concurrency` group now serialises the two pushes so a release reliably publishes both the API docs and the site.

## v4.6.1 — CI hotfix: unblock the Play Store deploy for the demo app (2026-05-16)

### Fixed

- **Play Store deploy no longer blocked by AAB manifest validation ([#1413](https://github.com/sceneview/sceneview/issues/1413)).** `validate-release-artifact.sh` now resolves `aapt2` from `$ANDROID_SDK_ROOT/build-tools/<newest>/` instead of relying on `PATH` (where it never is), and the pre-upload guard now warns and skips instead of hard-failing when the validation tooling itself is unavailable — only a genuine manifest mismatch blocks a release.

## v4.6.0 — Demo polish & cross-platform parity: iOS/Android demo unification + Samples tab fixes + AR screenshot regression pipeline + CI hygiene (2026-05-16)

### Added

- **Reusable branch + worktree cleanup task.** New `.claude/scripts/cleanup-branches-worktrees.sh` deletes merged local and remote `claude/*` branches (single `git push --delete`, no bot-burst) and prunes stale `.claude/worktrees/*` directories, with current-branch / unmerged / open-PR safety guards and a `--dry-run` default. A daily `branch-cleanup` job in `maintenance.yml` prunes merged remote branches automatically.
- **AR demo screenshot regression pipeline ([#1050](https://github.com/sceneview/sceneview/issues/1050)).** New `ARPlaybackScreenshotTest` replays the bundled ARCore recording through `ARRecordPlaybackDemo` and captures the rendered AR frame at **fixed ARCore frame indices** (f=30/60/120/180) for golden comparison. Captures are gated on a per-frame counter (`DemoSettings.arPlaybackFrameCount`, bumped once per `onSessionUpdated`) rather than wall-clock sleeps, so they land on the same frame on every machine regardless of emulator load. Wired into `render-tests.yml` on a pinned emulator profile and documented in `samples/android-demo/AR_TESTING.md`.
- **Flutter & React Native demos: Double Pendulum physics demo** ([#1332](https://github.com/sceneview/sceneview/issues/1332)) — a new "Physics" tab in `samples/flutter-demo` and `samples/react-native-demo` runs the chaotic two-link pendulum with link-length / gravity sliders + reset, mirroring the Android, iOS and web demos. The bridge sample apps have no per-frame transform-mutation API, so the integrator is a 1:1 port of the shared `sceneview-core` `DoublePendulum` simulation rendered via a Flutter `CustomPainter` / React Native views. `Closes #1332`.

### Changed

- **`play-store.yml` now validates the release AAB manifest before upload ([#1301](https://github.com/sceneview/sceneview/issues/1301)).** A new gate runs after the `bundleRelease` build and fails the job fast if the artifact's `package`, `versionName`, or `versionCode` don't match what gradle was told to build — catching a stale or wrong-variant bundle in ~1 s instead of as a Play Console rejection minutes later. Backed by `.claude/scripts/validate-release-artifact.sh` + an `android_cli_describe` helper (wrapping `aapt2`, since the `android` CLI's `describe` subcommand introspects projects, not built artifacts). `Closes #1301`.
- **`cross-platform-check.sh` can cross-check the demo APK manifest ([#1302](https://github.com/sceneview/sceneview/issues/1302)).** A new opt-in `--with-apk` flag builds (or reuses) the `android-demo` debug APK and inspects its manifest via the `aapt2`-backed `android_cli_describe` helper to verify the exposed entry points match expectations — the `io.github.sceneview.demo` package id, a launchable `MainActivity`, and the `sceneview://` deep-link scheme — then cross-checks the Android `DemoRegistry` demo count against the iOS `SamplesTab` inventory so a platform missing a demo surfaces as drift. The fast source-only path stays the default.
- **CI hygiene cluster ([#1360](https://github.com/sceneview/sceneview/issues/1360)).** Consolidated the website deploy to a single path — `docs.yml` now publishes the complete built site (marketing + MkDocs + web-demo + Dokka API) to the canonical apex repo `sceneview/sceneview.github.io`, and the redundant `deploy-website.yml` (which pushed only `website-static/` to a competing URL) is removed. Fixed the iOS SPM cache keys in `ios.yml` and `app-store.yml` to hash the checked-in `samples/ios-demo/Package.resolved` instead of a nested workspace path that does not exist on a clean runner (the key was a constant empty hash, so the cache never invalidated). Raised the `CI Gate` job timeout to 35 min so the poll loop's own diagnostic surfaces before a hard runner kill. Linked the permanently `continue-on-error` `desktop-demo` compile step to tracked follow-up [#1396](https://github.com/sceneview/sceneview/issues/1396).
- **Unified demo titles & subtitles across Android and iOS ([#1376](https://github.com/sceneview/sceneview/issues/1376)).** Every demo now shows one canonical, user-facing title and subtitle on both platforms, so the Play Store and App Store apps no longer look like different products.
- **Added `jsTest` coverage for the `sceneview-web` core logic classes ([#1394](https://github.com/sceneview/sceneview/issues/1394)).** New unit tests pin the `OrbitCameraController` orbit/zoom/pan math (spherical-to-Cartesian eye conversion, phi/distance clamping, auto-rotate, damping), the `GeometryGLBBuilder` GLB container output (header, chunk alignment, accessors, `KHR_materials_unlit` extension, node transforms), and the auto-center one-shot gate. The `didCenterContent` flag was extracted into a testable `AutoCenterGate` so the #1357 regression — a 2nd `loadModel` must re-run content centering — is now directly covered. `Closes #1394`.
- **API docs: KDoc for the `sceneview` module geometry, texture and material helpers ([#965](https://github.com/sceneview/sceneview/issues/965)).** Added accurate KDoc to previously undocumented public declarations in the `sceneview` Android module: the six geometry builders (`Cube`, `Cone`, `Cylinder`, `Sphere`, `Capsule`, `Torus`), the texture helpers (`ImageTexture`, `VideoTexture`, `TextureSampler2D`/`TextureSamplerExternal`, `Texture.use`/`setBitmap`), `RenderableManager` extensions, `NodeAnimator`, and the ubershader `MaterialInstance` parameter setters. Documentation only — no behavior change. `arsceneview` is tracked separately.

### Fixed

- **Samples cleanup ([#1361](https://github.com/sceneview/sceneview/issues/1361)).** Rewrote `samples/MULTIPLATFORM.md` so the architecture diagram and recipe list match the real tree (`*-demo/` folders, the 11 actual `recipes/*.md` files). Finished the android-demo first-frame loading-scrim rollout to the remaining 13 non-AR demos so cold starts no longer flash a black viewport (AR demos intentionally skip it — they show a live camera feed, not a black Filament viewport). Converted the dead `else -> PlaceholderDemo` router fallback in `MainActivity.kt` into a debug-only drift guard that crashes loudly if a new `ALL_DEMOS` entry is added without a matching route, while still degrading gracefully in release builds.
- **iOS demo cleanup + Android parity ([#1373](https://github.com/sceneview/sceneview/issues/1373)).** Renamed the `Scenes` tab to `Samples`, aligned the Samples category taxonomy to Android, removed the `Auto Rotate` / `AR Record & Playback` duplicate entries and the dead Explore buttons, fixed the `Settings` pill overlapping the controls FAB, and corrected several inaccurate demo subtitles and captions.
- **iOS demo: Samples tab black rectangle ([#1392](https://github.com/sceneview/sceneview/issues/1392)).** Tapping a 3D demo in the Samples tab opened it in a `.medium`-detent `.sheet`, which rendered the demo's full-screen `SceneView` (RealityView) viewport as a black, half-height panel covering the demo-card list and the Settings button. Every available demo now opens in a `.fullScreenCover`; the partial `.sheet` is reserved for the lightweight `ComingSoonScreen`, which has no 3D surface.
- **`sceneview-web` `jsTest` suite can now run in CI ([#1401](https://github.com/sceneview/sceneview/issues/1401)).** The Karma / ChromeHeadless test bundle threw `Uncaught ReferenceError: Filament is not defined` at load time — the `@JsModule("filament")` external is mapped to a global that no script injected into the headless page, which failed the entire `jsTest` run before any test executed. A `karma.config.d/filament-stub.js` config now serves a no-op `Filament` global before the test bundle, so the pure-logic web tests (camera/config builders, `ContentCentering`, version pin, WebXR constants) actually run. Also fixed two latent test failures the blocker was hiding: `ContentCentering.centeringOffset` returned a signed `-0.0` for an already-centred axis (now normalised to `0.0`), and `SceneViewVersionTest`'s pinned literal was a version behind.
- **`sceneview.github.io/docs/` and `/api/` no longer serve the landing page ([#925](https://github.com/sceneview/sceneview/issues/925)).** The `docs.yml` workflow now deploys the complete assembled site — marketing landing page (root), MkDocs technical docs (`/docs/`), the Kotlin/JS web-demo (`/web-demo/`), and the Dokka API reference (`/api/sceneview/`) — to the user-facing `sceneview/sceneview.github.io` repo. Previously `docs.yml` deployed to a different Pages host while `deploy-website.yml` published only `website-static/` (which carried a `docs/` meta-refresh redirect stub) to `sceneview.github.io`, so the MkDocs and Dokka content never reached those routes and GitHub Pages' 404 fallback served the landing page byte-for-byte. The redundant `deploy-website.yml` workflow and the obsolete redirect stub have been removed; `docs.yml` is now the single authoritative site deploy.
- **`MaterialLoader` / `EnvironmentLoader` no longer leak their `CoroutineScope` across composition disposal ([#933](https://github.com/sceneview/sceneview/issues/933)).** Each loader's `destroy()` cancels its `CoroutineScope`, and `rememberMaterialLoader` / `rememberEnvironmentLoader` wire `destroy()` to `DisposableEffect.onDispose`, so an in-flight `loadMaterialAsync` / `loadHDREnvironment` job can no longer outlive the owning composition and touch a destroyed `Engine`. `EnvironmentLoader.clear()` no longer cancels the scope — it now releases environments only, so calling `clear()` on a still-live loader never leaves it with a dead scope.
- **AnimationDemo cinematic camera no longer drains the battery while backgrounded ([#974](https://github.com/sceneview/sceneview/issues/974)).** The four scripted camera loops (Hero, Reveal, Vertigo, Tracking) now park on a clean boundary when the app goes to the background and resume from the exact same pose — no teleport back to the initial yaw, unlike the `repeatOnLifecycle`-based helper that #936's review had to revert here. A new `LifecyclePausingLaunchedEffect` / `LifecyclePauseGate` pair in `samples/common` provides the reusable state-preserving primitive for any `while(true)` loop that wants lifecycle pausing without a state reset.

### Tests

- **JaCoCo coverage delta gate ([#973](https://github.com/sceneview/sceneview/issues/973)).** A committed baseline (`.claude/data/jacoco-baseline.txt`) records per-module line coverage, and `.claude/scripts/jacoco-delta-check.sh` fails when a PR drops coverage more than the configurable `threshold_pp` (0.5pp default). Wired into the `unit-test` CI job as an informational, non-blocking step for now — promoting it to a hard gate once it has been green for two consecutive weeks is the `#973` follow-up.

### Docs

- **`/issue-batch` skill rewritten as a launch-and-go continuous cycle ([#1297](https://github.com/sceneview/sceneview/issues/1297)).** The skill now encodes the validated operating mode: a replace-on-completion pipeline of 6-8 lean-clone background agents (shallow sparse clones, ~0.3-0.6 GB vs ~2.3 GB full), fire-and-forget `gh pr merge --auto`, disk-gated spawn (refuse < 15 GB), disjoint-module parallelism, autonomous dispatch, and a release checkpoint per iteration. `Closes #1297`.

## v4.5.0 — visionOS immersive-space skybox + fragment changelog system + iOS unlit/reactive-light parity + CI hardening (2026-05-15)

### Changed

- **Adopted a towncrier-style fragment changelog ([#1337](https://github.com/sceneview/sceneview/issues/1337)).** PRs now drop a small file in `changelog.d/` instead of editing `CHANGELOG.md`'s `## Unreleased` anchor, so parallel PRs no longer conflict on the changelog. `.claude/scripts/collate-changelog.sh X.Y.Z` collates the fragments into a new `## vX.Y.Z` section at release time. `Closes #1337`.

### Added — iOS

- **visionOS immersive-space skybox ([#1235](https://github.com/sceneview/sceneview/issues/1235)).** A `SceneView` pulled into a fully immersive `ImmersiveSpace` now renders its `showSkybox` HDR environment as a background. The new `.immersiveSpace()` modifier opts in; the HDR is mapped onto an inverted sphere parented under a `WorldComponent` root, since `RealityViewContent.environment` (the windowed iOS / macOS `.skybox(_:)` path from #1215) is unavailable on visionOS. Windowed / volumetric visionOS scenes are unchanged.

### Fixed — iOS

- **camera auto-framing now scales-to-fit the scene bounds ([#1026](https://github.com/sceneview/sceneview/issues/1026), [#1041](https://github.com/sceneview/sceneview/issues/1041)).** the `SceneView` default camera now dollies to a distance that fits the content bounding box in the viewport — accounting for the vertical fov and live aspect ratio — instead of sitting at a fixed pose, so models are no longer rendered too small, too low, clipped, or overflowing across the ios demos.

### Added — Documentation

- **KDoc for the `sceneview-core` collision API ([#965](https://github.com/sceneview/sceneview/issues/965)).** Documented previously-undocumented public declarations in the collision module (`Box`, `Sphere`, `Plane`, `Ray`, `RayHit`, `Vector3`, `Quaternion`, `Capsule`, `MeshCollider`, `ChangeId`, `TransformProvider`) plus the `Easing` curve set and the cross-platform `logWarning` logger.

### Added — Docs

- **New recipe: iOS visual-polish pipeline ([#1218](https://github.com/sceneview/sceneview/issues/1218)).** `docs/recipes/ios-visual-polish.md` documents how to combine the v4.4.0 HDR-skybox background render, PBR default material, and Apple AR Quick Look hand-off — decoded from [@radcli14](https://github.com/radcli14)'s `twolinks`. The iOS demo's `DynamicSkyDemo` deep-night bucket now uses the dramatic `SceneEnvironment.nightSky` HDR.

### Added — Samples

- **Web demo: Double Pendulum physics demo** ([#1221](https://github.com/sceneview/sceneview/issues/1221)) — a new "Physics" tab in `samples/web-demo` runs the chaotic two-link pendulum with link-length / gravity sliders + reset; the integrator mirrors the shared `sceneview-core` `DoublePendulum` simulation that drives the Android and iOS demos. Reachable via the `#double-pendulum` deep link.

### Fixed — iOS true look-around camera ([#1236](https://github.com/sceneview/sceneview/issues/1236))

- **iOS `.firstPerson` now rotates the perspective camera in place instead of orbiting the scene root, so switching orbit ↔ firstPerson no longer teleports the camera; new `recentersTargetOnOrbit(_:)` modifier + `CameraControls.recenterTarget()` fix pan→orbit pivot drift.** `Closes #1236`.

### Tests

- **Regression pins for three untested AR rendering fixes from the 2026-05-14 batch ([#1120](https://github.com/sceneview/sceneview/issues/1120)).** New JVM tests pin the `environmentalHdrSpecularFilter = true` default (#1086), the no-double-close hoisted cubemap upload callback (#1091), and the 7 `@Volatile` `LightEstimator` toggles (#1095).

### Added — CI

- **Nightly full-CI safety-net workflow ([#1324](https://github.com/sceneview/sceneview/issues/1324)).** `nightly-ci.yml` runs the full heavy validation surface (compile + builds + unit tests + render tests + quality gate) against `main` HEAD once a night, reusing the existing workflows via `workflow_call`, so a path-gated-out regression still surfaces within 24h. Not a PR gate.

### Fixed

- **iOS `FogNode.heightFalloff` / `heightBased` are now honestly documented as a RealityKit parity gap ([#1380](https://github.com/sceneview/sceneview/issues/1380)).** the height gradient was a silent no-op — a uniform translucent sphere cannot vary opacity by world height; the parameter is kept for Android parity but now clearly documents that height-based fog renders identically to exponential fog on iOS ([#1373](https://github.com/sceneview/sceneview/issues/1373)).

- **iOS `GeometryNode` / `ShapeNode` `unlit: true` now returns a flat `UnlitMaterial` ([#1359](https://github.com/sceneview/sceneview/issues/1359)).** The `unlit:` parameter previously produced a lit `SimpleMaterial` that still reacted to scene lighting, contradicting the KDoc contract — it now yields an `UnlitMaterial`, matching `ImageNode` and `GeometryMaterial.unlit`.

- **`SceneView` main/fill light mutations are now reactive ([#1306](https://github.com/sceneview/sceneview/issues/1306)).** `rememberMainLightNode` / `rememberFillLightNode` re-run their `apply` block on every recomposition (via `SideEffect`), so Compose-state-driven light properties (intensity, direction, color) propagate to the Filament scene without re-keying the `remember` — matching the iOS `RealityView.update:` reactive light contract.

### Changed — Samples

- **Migrated the remaining `samples/android-demo` demos to the `rememberMaterialInstance` / `rememberUnlitMaterialInstance` helpers ([#971](https://github.com/sceneview/sceneview/issues/971)).** `CollisionDemo`, `LightingDemo`, `VideoDemo`, `ARStreetscapeDemo`, `GeometryDemo`, `DebugOverlayDemo`, `PhysicsDemo` and the shared `Axes3DNode` no longer allocate `MaterialInstance` handles via raw `materialLoader.create*` without disposal — the helpers own the lifecycle. Behaviour-preserving.

### Fixed — Web

- **`sceneview-web` stale `SCENEVIEW_VERSION` + auto-center not resetting on a 2nd model load ([#1357](https://github.com/sceneview/sceneview/issues/1357)).** The `@JsExport`-reachable `SCENEVIEW_VERSION` was two majors stale (`3.6.0`); it now reports `4.4.0` and is pinned by a jsTest. `SceneView.loadModel` now resets `didCenterContent` so a model loaded after the first one was auto-centered gets re-centered, mirroring Android's `SceneAutoCenterState.reset()`.

### Fixed — CI security

- **`discord-notify.yml` no longer interpolates user-controlled `github.event.*` fields into inline shell scripts ([#1313](https://github.com/sceneview/sceneview/issues/1313)).** Issue title/author and release name/tag now pass through `env:` and are referenced as quoted shell variables, closing a GitHub Actions script-injection vector.

### Fixed — Android demo

- **Demo viewports no longer flash black for 5–12 s on cold start ([#1022](https://github.com/sceneview/sceneview/issues/1022)).** `DemoScaffold` now shows a surface-tinted loading scrim over the 3D viewport until the SceneView presents its first Filament frame, wired via the new `rememberFirstFrameState()` helper.

### Fixed — ViewNode rendering

- **`ViewNode` no longer renders as a permanent black rectangle after a background → foreground cycle ([#984](https://github.com/sceneview/sceneview/issues/984)).** `ViewNode.WindowManager` now retries the off-screen window attach via an owner-View attach listener when the owner is not yet attached at resume time, instead of silently dropping the attach.

### Fixed — Samples

- **`SecondaryCameraDemo` camera-angle controls are now TalkBack-friendly ([#1256](https://github.com/sceneview/sceneview/issues/1256)).** The section label is exposed as a heading and the selected `FilterChip` carries an explicit "Selected camera angle" state description.

### Changed — CI

- **`ci.yml`'s "Build & lint" job split into parallel `build` / `lint` / `unit-test` jobs, and `quality-gate.yml` switched to a shallow checkout ([#1311](https://github.com/sceneview/sceneview/issues/1311)).** The three Android jobs share the Gradle cache and run concurrently (~3 min wall-clock saved); the quality gate drops `fetch-depth: 0` since its scripts only diff against the working tree / HEAD.

- **CI/publish workflows' inline `pip install` deps moved into per-workflow `.github/workflows/requirements/*.txt` files so Dependabot's `pip` ecosystem tracks and bumps them ([#1286](https://github.com/sceneview/sceneview/issues/1286)).** Same packages, same pinned versions installed — Dependabot just cannot see inline `pip install x==y` lines in workflow YAML, so the pins would have gone stale silently.

- **`render-tests.yml` reverted from a 3-shard emulator matrix back to a single job ([#1119](https://github.com/sceneview/sceneview/issues/1119)).** All 5 render-test classes are class-level `@Ignore`'d on SwiftShader CI (#803), so the shard matrix booted 3 emulators to run 0 tests — strictly more CI cost for the same coverage. The matrix scaffold can be re-applied once #803 lifts the ignores.

### Fixed — arsceneview

- **`ARRecorder.start(recordingResolution=…)` now restores the session's camera config on `stop()` ([#1358](https://github.com/sceneview/sceneview/issues/1358)).** The higher-resolution CPU image stream raised for a recording no longer silently persists for the rest of the AR session — the prior config is captured before the swap and restored on `stop()` (and on a failed `start()`).

### Fixed — Docs

- **Reconciled stale version refs that survived the v4.4.0 release and hardened `sync-versions.sh` to catch them ([#1356](https://github.com/sceneview/sceneview/issues/1356)).** README badges/CDN/SPM snippets, `ai-context.md`, `android-xr-emulator.md`, `website-static/js/package.json`, the Kotlin toolchain version in `llms.txt`, and the root↔docs `llms.txt` `ARRecorder.saveToPhotoLibrary` paragraph now all read 4.4.0 / 2.3.21; `sync-versions.sh` gained checks for every one of those off-map locations.

## v4.4.0 — iOS skybox renders + true-orbit camera + iOS Stage 2 demo parity + Double Pendulum physics demo + `sceneview-swift` mirror retired (2026-05-15)

### Changed — AR `LightEstimator` allocation & robustness refactor ([#1105](https://github.com/sceneview/sceneview/issues/1105))

- **`LightEstimator.update()` no longer allocates on the AR render thread.** Per-frame `Estimation`, color-correction, cubemap face-offset, RGB-triplet, and 27-element irradiance buffers are now hoisted, reused fields; an `ENVIRONMENTAL_HDR` capability probe (`Session.isSupported`, cached per mode) early-returns instead of feeding silently-degraded HDR estimates to Filament; the legacy Sceneform `1.8` pixel-intensity gain is now a named constant. Public behaviour is unchanged. `Closes #1105`.

### Fixed — AR recording resolution ([#1065](https://github.com/sceneview/sceneview/issues/1065))

- **`ARRecorder` no longer records at ARCore's low-res 640×480 default.** ARCore writes the CPU image stream into the MP4, whose stock default is the device's *lowest*-resolution camera config. `ARSceneView`'s `sessionCameraConfig` now defaults to the new `highestResolutionCameraConfig` selector (highest-resolution BACK-facing, 30 FPS config), so every AR scene — and every recording — runs at full camera resolution without opt-in. `ARRecorder.start(...)` also gains an optional `recordingResolution: Size?` parameter to request a specific resolution explicitly. `Closes #1065`.

### Added — Agent skills

- **Published `sceneview-ios` and `sceneview-web` agent skills, and documented the Android `sceneview` skill's `android-cli` registry submission ([#1080](https://github.com/sceneview/sceneview/issues/1080), [#1081](https://github.com/sceneview/sceneview/issues/1081), [#1082](https://github.com/sceneview/sceneview/issues/1082)).** New `agents/sceneview-ios/` (SwiftUI + RealityKit) and `agents/sceneview-web/` (Filament.js + WebXR) skills with install scripts; `check-sceneview-skill.sh` now validates all three; submission packet and steps tracked in `agents/REGISTRY.md`.

### Added — Web auto-center content ([#1052](https://github.com/sceneview/sceneview/issues/1052))

- **`sceneview-web` now auto-centres loaded content on the orbit-camera target — library-level port of iOS `autoCenterContent` ([#1026](https://github.com/sceneview/sceneview/issues/1026)).** Enabled by default; opt out with `autoCenterContent(false)` in the DSL builder or `setAutoCenterContent(false)` on the JS viewer. Models placed off-origin now frame centred in the canvas without per-demo workarounds. Android sibling tracked in [#1051](https://github.com/sceneview/sceneview/issues/1051).

### Fixed — iOS demo bug cluster ([#1054](https://github.com/sceneview/sceneview/issues/1054)–[#1059](https://github.com/sceneview/sceneview/issues/1059))

- **iOS demo bug cluster — `swift test` build, `CameraControls` rename, deep-link, Plane, first-paint audit.** Local `cd SceneViewSwift && swift test` runs the full 603-test suite again ([#1054](https://github.com/sceneview/sceneview/issues/1054)): the 36 `SceneViewSwiftTests` classes are now `@MainActor`-annotated so their RealityKit `@MainActor` node factories no longer raise ~500 `#ActorIsolatedCall` compile errors under the Xcode 26 toolchain. `OrbitCameraDemo.swift` renamed to `CameraControlsDemo.swift` ([#1055](https://github.com/sceneview/sceneview/issues/1055)) so the file matches its `struct CameraControlsDemo` (`project.pbxproj` synced). The `sceneview://demo/multi-model` deep-link no longer hangs on an eternal black "Loading park scene…" scrim ([#1056](https://github.com/sceneview/sceneview/issues/1056)) — `MultiModelDemo` loads its four park models concurrently and reveals each progressively, so one slow heavy USDZ no longer blocks the rest. `GeometryDemo`'s `Plane` is no longer invisible/edge-on ([#1058](https://github.com/sceneview/sceneview/issues/1058)) — the demo now stands the XZ plane upright to face the orbit camera. First-paint audit ([#1059](https://github.com/sceneview/sceneview/issues/1059)): RealityKit does **not** exhibit Android #1022's Filament shader-compile black first frame on non-AR demos, so no library-level scrim was needed on iOS.
### Added — iOS

- **`ARRecorder.saveToPhotoLibrary(_:)` now returns the saved asset's `PHAsset.localIdentifier` (`String?`) ([#1057](https://github.com/sceneview/sceneview/issues/1057)).** Callers get a handle to the saved recording — resolve it later via `PHAsset.fetchAssets(withLocalIdentifiers:options:)` or deep-link to it — closing the cross-platform parity gap with Android's `ARRecorder.exportToDownloads()` `Uri?` return. The result is `@discardableResult`, so existing v4.3.0 call sites are unaffected.

### Added — Bridges

- **Flutter + React Native bridges now expose the v4.3.0 iOS additions — `CameraControlMode` (orbit/pan/firstPerson), `autoCenterContent`, and `ARRecorder` (record-only via ReplayKit) ([#1053](https://github.com/sceneview/sceneview/issues/1053)).** Cross-platform consumers can drive iOS camera modes, opt out of auto-centring, and record AR sessions; Android gracefully falls back where the feature is iOS-first (tracked in #1051).

### Removed — Samples

- **Removed the French localization from the sample apps — sample apps are English-only by design ([#1294](https://github.com/sceneview/sceneview/issues/1294)).** Deleted `samples/android-demo/.../res/values-fr/strings.xml`; the default English resources remain the single source of truth.

### Fixed — 3D rendering quality umbrella ([#1074](https://github.com/sceneview/sceneview/issues/1074))

- **`DefaultCameraNode` now starts at a framed 3/4 view** — the default 3D camera placement moved from `(0, 0, 1)` (1 m dead-ahead of the world origin, flat front-on, under-framing anything but a tiny object) to `(0, 0.3, 2)` looking at the origin. This frames a typical 0.3–1 m model placed at origin correctly out of the box and mirrors iOS RealityKit's `look(at: .zero, from: [0, 0.3, 2])` default, so the same scene frames identically on Android and iOS. The position is exposed as `DefaultCameraNode.DEFAULT_Y` / `DEFAULT_Z` and pinned in `SceneFactoriesTest`. This is a visual behavior change — demos that supply their own `cameraNode` are unaffected. The remaining umbrella items (IBL intensity 30k→10k, PostProcessingDemo SSAO toggle, light/material leaks, render-quality clobber, `indirectLightApply` threading, PhysicsDemo light retune) already shipped in [#1079](https://github.com/sceneview/sceneview/pull/1079), [#1088](https://github.com/sceneview/sceneview/pull/1088), [#1089](https://github.com/sceneview/sceneview/pull/1089), [#1092](https://github.com/sceneview/sceneview/pull/1092) and [#1147](https://github.com/sceneview/sceneview/pull/1147).

### Changed — CI

- **`release.yml` now deploys the generated Dokka API docs to `sceneview.github.io/api/sceneview/<version>/` + `/latest/` and wraps Dokka generation in a 3× retry to tolerate transient Maven Central 503s ([#1252](https://github.com/sceneview/sceneview/issues/1252), [#1127](https://github.com/sceneview/sceneview/issues/1127)).**
- `Deploy iOS App to App Store` is now tag-only — fixes Apple upload-limit failures on every main merge ([#1318](https://github.com/sceneview/sceneview/issues/1318)).
- Lighter `main` CI: `Deploy Demo to Play Store` is now tag-only ([#1321](https://github.com/sceneview/sceneview/issues/1321)), `Deploy website + docs` is path-gated to docs/website/markdown changes, and `Render Tests` / `Build sample APKs` path filters were tightened to skip doc-only, CI-only and `mcp/`-only merges ([#1311](https://github.com/sceneview/sceneview/issues/1311)).

### Changed — MCP

- **`sceneview-mcp` adds `search_android_docs` / `fetch_android_doc` tools wrapping Google's `android docs` CLI, and makes the `package-files` regression test deterministic by routing the `generate-llms-txt` banner to stderr ([#1083](https://github.com/sceneview/sceneview/issues/1083), [#1113](https://github.com/sceneview/sceneview/issues/1113)).**

### Added — Double Pendulum demo (shared KMP physics)

- **New `DoublePendulum` simulation in `sceneview-core` ([Addresses #1221](https://github.com/sceneview/sceneview/issues/1221))** — a pure-Kotlin, platform-independent two-link (double) pendulum in `io.github.sceneview.physics`. `DoublePendulumState` holds two `DoublePendulumLink`s (length, mass, angle, angular velocity), a fixed `pivot`, `gravity` and `damping`; `DoublePendulum.step(state, dt)` advances it with a symplectic (semi-implicit) Euler integrator, sub-stepped at `1/240 s` so the chaotic motion stays numerically stable at any frame rate. Exposes `joint` / `tip` joint positions and a `totalEnergy` accessor; covered by 12 `commonTest` cases including energy-conservation (bounded energy band with `damping = 0`) and rest-state stability. Adapted from [@radcli14](https://github.com/radcli14)'s MIT-licensed [`twolinks`](https://github.com/radcli14/twolinks).
- **New "Double Pendulum" demo on Android and iOS** — a chaotic two-link mechanism rendered as metallic PBR links swinging in real time, driven by the shared `DoublePendulum`. Android (`samples/android-demo`) wires the `sceneview-core` simulation into a `SceneView { }` frame loop with sliders for link lengths / gravity / reset; iOS (`samples/ios-demo`) ships the SwiftUI equivalent. The iOS demo hand-ports the same integrator math into a local `DoublePendulum.swift`, kept numerically identical to the Kotlin source, since iOS cannot consume the KMP module directly until the `sceneview-core` XCFramework lands ([#1033](https://github.com/sceneview/sceneview/issues/1033)). Reachable via `sceneview://demo/double-pendulum` on both platforms. Web / Flutter / React Native ports are deferred follow-ups under [#1221](https://github.com/sceneview/sceneview/issues/1221).
### Changed — iOS default material is now physically-based ([#1223](https://github.com/sceneview/sceneview/issues/1223))

- **Procedural geometry on iOS now defaults to `PhysicallyBasedMaterial` instead of `SimpleMaterial` ([#1223](https://github.com/sceneview/sceneview/issues/1223))** — `GeometryNode.cube/sphere/cylinder/cone/plane/torus/capsule(color:)`, `ShapeNode(points:color:)`, `LineNode(from:to:color:)`, and lit `ImageNode`s previously created RealityKit `SimpleMaterial`, which is effectively unlit-flat: it does not react to image-based lighting (the HDR environment `SceneView` wires by default) and cannot express metallic/roughness. The library default is now a matte-neutral PBR material (`metallic: 0`, `roughness: 0.5`) so shapes pick up soft environmental shading and reflections — the single biggest visual-quality jump for iOS demos. **This is a visual behavior change, not a breaking API change**: every existing call site keeps compiling and produces visually-similar-or-better output. Callers who explicitly want the old flat-fill look (debug visualizations, overlays) can opt back in with the new `unlit: Bool = false` parameter, e.g. `GeometryNode.cube(color: .red, unlit: true)`. Also fixes a latent bug where `GeometryMaterial.pbr(...)` was internally backed by `SimpleMaterial` (so metallic/roughness never reached the PBR pipeline) — it now correctly builds a `PhysicallyBasedMaterial`. `Closes #1223`.
### Added — `night_sky` environment preset ([#1219](https://github.com/sceneview/sceneview/issues/1219))

- **New `night_sky` HDR environment** bundled across iOS and Android demos — a dramatic Milky Way starfield over a dark landscape (Poly Haven [`dikhololo_night`](https://polyhaven.com/a/dikhololo_night) by Greg Zaal, **CC0 1.0** public domain). iOS exposes it as `SceneEnvironment.nightSky` (added to `allPresets`, so it auto-surfaces in the demo's environment picker); Android adds a "Night Sky" chip to `EnvironmentDemo`. Pairs well with metallic PBR materials for chrome-mirror reflections. Web demo does not bundle HDRs and is unaffected.

### Changed — iOS demo

- **The AR tab launcher now doubles as a discovery surface ([#1253](https://github.com/sceneview/sceneview/issues/1253))** — a 2×3 grid of headline AR demo cards under the "Start AR Camera" CTA (Plane Placement, Instant Placement, AR Lighting, AR Recording, Orbital AR, AR Debug), each opening the demo full-screen — closing the launcher-parity gap with Android's `ArLauncherScreen`. The AR tab's placed-model count is also derived from the live anchor collection so it can no longer drift.

### Fixed — iOS demo

- **`AppStoreUpdater` snooze is now version-keyed instead of a 7-day TTL ([#1231](https://github.com/sceneview/sceneview/issues/1231))** — dismissing one release's update banner no longer hides a *newer* release's banner; a new App Store version invalidates the snooze automatically, matching the Web/Flutter/RN samples. The `SceneViewDemoTests` unit-test target is now wired into `SceneViewDemo.xcodeproj` so the `AppStoreUpdater` tests run in CI ([#1227](https://github.com/sceneview/sceneview/issues/1227)).

### Fixed — iOS rendering

- **`SceneEnvironment.showSkybox = true` now actually paints the HDR as the scene background ([PR #1215](https://github.com/sceneview/sceneview/pull/1215), ported from [@radcli14](https://github.com/radcli14)'s [sceneview-swift#1](https://github.com/sceneview/sceneview-swift/pull/1))** — `SceneView` previously loaded the HDR and applied it as IBL via `ImageBasedLightComponent`, but never assigned it to `RealityViewContent.environment`, so the scene rendered against the default neutral void regardless of which environment preset was selected. The new path caches the loaded `EnvironmentResource` in a `@State` and applies it via `content.environment = .skybox(resource)` in the `RealityView.update:` closure with a diff guard against the last applied resource (no per-frame ARC churn). The `.task(id:)` keys on `(name, showSkybox)` so toggling the flag on the same env re-runs the loader, and clears the cached resource at the start of every task tick so cross-env transitions don't show stale skyboxes under a new IBL.

- **Orbit + pan camera modes now physically move the perspective camera in world-space ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))** — `applyCamera()` was faking the camera move by rotating + scaling `entities.root` while the perspective camera stayed pinned at `[0, 0.3, 2]`. With a global skybox, that made the background appear stationary while content visually orbited around the user — visually wrong from the camera's POV. Orbit and pan now position the camera via `CameraControls.cameraPosition()` + `look(at: target, ...)`, so the skybox correctly wraps. The scene root stays at identity for both modes; `camera.orbitRadius` is now the literal camera-to-target distance. `firstPerson` retains its rotate-the-root semantics (FOV pinch via [#1034](https://github.com/sceneview/sceneview/issues/1034)) — the true "stand still and look around" rewrite remains a v4.4.0 follow-up.

- **FOV no longer bleeds from `firstPerson` pinch into `orbit` / `pan`** — switching to `firstPerson`, pinching FOV down to e.g. 30°, then back to `orbit` kept the 30° pinched FOV on the perspective camera (visible as a stuck zoom-in). `applyCamera()` now writes the baseline `60°` FOV in `orbit` / `pan` regardless of `camera.fov`, and only mirrors `camera.fov` in `firstPerson`. On `firstPerson` exit, `camera.fov` itself is reset to `60` so the next entry starts fresh.

### Changed — `CameraControls` defaults (BREAKING for direct constructors)

- **`CameraControls.orbitRadius` public default changed from `5.0` to `2.0`** — `5.0` was unreachable through any public modifier (`cameraControls(_:)` only accepts a `CameraControlMode`), and the internal `@State` already overrode to `2.0` so existing demos retain their on-screen framing. Callers constructing `CameraControls()` directly will see the same `2.0` default the SceneView uses internally; the apparent angular size of a 1m model at default state is identical to the pre-v4.4.0 fake-orbit framing (28.07° at 60° FOV).
- **`CameraControls.minRadius` public default changed from `0.5` to `1.0`** — under the new true-camera path, `0.5` puts the perspective camera inside any model with extent >1m (which most demo content has). The old `0.5` was safe under the fake-orbit `scale = 5.0 / radius` scene-scale hack but clips into geometry now. Override for smaller content.

### Changed — SPM URL retirement

- **`sceneview-swift` SPM mirror retired in favour of monorepo-direct package resolution ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))** — every install snippet across docs, codelabs, GPT prompts, `.github/copilot-instructions.md`, `SceneViewSwift/README.md`, `llms.txt` (4 copies — root, docs, website-static, well-known), the website (`index.html` / `docs.html` / `playground.html`), the PWA manifest, the schema.org `sameAs` graph, and the bundled MCP `llms-txt.ts` now points at `https://github.com/sceneview/sceneview(.git)`. The `sceneview/sceneview-swift` mirror has been archived read-only; its frozen `v4.0.0` tag still resolves for SPM consumers pinned to the old URL, but no further releases will be cut there. Existing consumers should re-add the package in Xcode pointing at the monorepo URL — the root `Package.swift` (added in [PR #920](https://github.com/sceneview/sceneview/pull/920)) declares the `SceneViewSwift` product.

### Changed — CI / scripts hardening

- **CI/scripts hardening batch** ([#1226](https://github.com/sceneview/sceneview/issues/1226), [#1230](https://github.com/sceneview/sceneview/issues/1230), [#1237](https://github.com/sceneview/sceneview/issues/1237), [#1114](https://github.com/sceneview/sceneview/issues/1114)) — new `check-sceneview-swift-urls.sh` PR gate forbids reintroducing the archived `sceneview-swift` mirror URL; `sync-versions.sh` now uses a portable `_sed_inplace` helper (BSD/GNU); publish-time `pip install` calls in `play-store.yml` / `app-store.yml` are pinned to exact versions. CONTRIBUTING.md now documents that docs-only PRs skip the quality-gate + render-tests ([#1128](https://github.com/sceneview/sceneview/issues/1128)).

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

### Fixed — tooling

- **Web demo — deferred update snackbar stranded on engine-init failure** ([#1279](https://github.com/sceneview/sceneview/issues/1279)) — `flushPendingUpdateSnackbar()` (added in PR #1271 to defer the update snackbar past engine init) was only called in the `SceneView.modelViewer(...)` success path; an engine-init rejection left a deferred version stuck in `pendingUpdateVersion` forever. The `.catch()` path now flushes too — the snackbar is pure DOM and `flushPendingUpdateSnackbar()` nulls `pendingUpdateVersion`, so it can never double-show. `Closes #1279`.
- **`DemoInteractionTest` — FR-locale gap in control helpers** ([#1282](https://github.com/sceneview/sceneview/issues/1282)) — `secondaryCamera_pipAngles` now resolves the PiP-angle chip labels from `R.string.demo_secondary_camera_chip_*` instead of hard-coded English literals, so the interaction test passes on a French-locale device. Demos that still inline English control labels in the composable need a per-demo resource-extraction sweep first (tracked separately). `Closes #1282`.

- **`worktree-auto-prune.sh` no longer risks destroying a parallel session's uncommitted work** ([#1278](https://github.com/sceneview/sceneview/issues/1278)) — the script now skips any worktree with a non-empty `git status --porcelain`, uses plain `git worktree remove` (fail-safe) instead of `--force`, accepts repeatable `--keep` paths, and reclaims squash-merged worktrees via a `gh`-backed merged-PR check that degrades gracefully offline. `Closes #1278`.

### Docs

- **New recipe: Blender → SceneView asset pipeline ([#1222](https://github.com/sceneview/sceneview/issues/1222))** — `samples/recipes/blender-to-sceneview.md` and `docs/docs/recipes/blender-pipeline.md` walk contributors through authoring a custom 3D model in Blender and shipping it in a SceneView app: `.glb` is native on Android, while Apple platforms go `.glb` → Reality Converter → `.usdz` → Reality Composer Pro (Blender's own USDZ exporter produces broken materials). Adapted from [@radcli14](https://github.com/radcli14)'s [`blender-to-realitykit`](https://github.com/radcli14/blender-to-realitykit) tutorial (MIT, 17⭐), with a SceneView-specific call-out on the Android Filament JNI main-thread rule. Cross-linked from both quickstarts and the API cheatsheet. Closes [#1222](https://github.com/sceneview/sceneview/issues/1222).

### Added — Android library-level `autoCenterContent` ([#1051](https://github.com/sceneview/sceneview/issues/1051))

- **`SceneView(autoCenterContent = true)`** — port of the iOS `autoCenterContent` feature ([#1026](https://github.com/sceneview/sceneview/issues/1026) / [PR #1038](https://github.com/sceneview/sceneview/pull/1038)). DSL `content` nodes are parented to an intermediate content-root node which the library translates once — on the first frame their union bounding box is non-empty — so the content centroid lands at the orbit pivot and renders centred without per-node `ModelNode(centerOrigin = …)`. Lights / camera are `SceneView` parameters (never DSL children) so they stay put. Opt out with `autoCenterContent = false` for intentional off-centre composition.

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

### Fixed — iOS Stage 2 demo polish ([#1280](https://github.com/sceneview/sceneview/issues/1280))

- `ARPlacementDemo` / `ARInstantPlacementDemo` gain a "Clear all placed models" control that tears down every placed anchor (placed anchors previously accumulated for the demo's lifetime); `ARInstantPlacementDemo`'s Instant/Plane toggle doc-comment + copy now honestly state both modes use the same `.estimatedPlane` raycast (the toggle only shows/hides the plane + coaching overlays); `ModelViewerDemo`'s "Surprise me" failures now surface a transient error banner instead of failing silently; and a confusing double-negation in `MultiModelDemo` was simplified.

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

### Changed — in-app update (samples)

- **`InAppUpdateManagerTest` now covers the intermediate `DOWNLOADING` state + non-zero `downloadProgress` ([#1229](https://github.com/sceneview/sceneview/issues/1229)); `UpdateBanner` auto-focuses its "Restart" CTA on D-pad hosts ([#1228](https://github.com/sceneview/sceneview/issues/1228))** — the TV demo passes an optional `restartFocusRequester` so the Restart button grabs focus when an update reaches `READY_TO_INSTALL`; phone hosts leave it `null` and are unaffected.

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

