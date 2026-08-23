# SceneView on Compose Multiplatform — scope & decision

Decision record for `sceneview-compose`, a Compose Multiplatform façade over the
existing per-platform renderers.

**Last updated:** 2026-08-19 · **Answers:** [#558](https://github.com/sceneview/sceneview/issues/558),
[#486](https://github.com/sceneview/sceneview/issues/486)

---

## The ask

Two external users asked for Compose Multiplatform support and were closed without
it ever existing:

- [#486](https://github.com/sceneview/sceneview/issues/486) (2024-05) — *"Support for
  sceneview for Compose Multiplatform iOS"*
- [#558](https://github.com/sceneview/sceneview/issues/558) (2024-09) — *"Compose
  Multiplatform support"*

Compose Multiplatform for iOS has been stable since CMP 1.8.0 (May 2025), so a
developer writing a CMP app today has no way to reach SceneView from `commonMain`.

## Decision

**Ship a thin `commonMain` façade that delegates to the existing native renderers.**
It is a *viewer subset*, published as a **separate, additive artifact**. Nothing in
`sceneview/`, `arsceneview/`, `SceneViewSwift/` or `sceneview-web/` changes.

| Target | `actual` implementation | Renderer | Status |
|---|---|---|---|
| Android | delegates to the existing `SceneView { }` composable | Filament | implemented |
| iOS | `UIKitView` hosting an app-supplied `UIView` (`SceneViewerBridge` → `SceneViewerHostView`) | RealityKit | implemented — the app registers the factory |
| Desktop (JVM) | offscreen render → pipelined `readPixels` → Skia image | Filament, via Maven `filament-kmp` 0.3.1 (FFM, JDK 22+) | **implemented** — `SceneViewer` desktop actual |

The point is that **one API does not imply one renderer.** RealityKit stays the Apple
renderer — it is what ARKit and visionOS align with, and it is what the published App
Store app uses.

## Scope: the viewer subset, and nothing more

The honest intersection of Filament, RealityKit and Filament.js is narrow. Measured on
the current tree:

- Android exposes **27 node types** (31 files in
  `sceneview/src/main/java/io/github/sceneview/node/`, four of which are the `Node` base
  and its delegates/state rather than node types), Swift 20, web ~10. The intersection of
  all three is **5**: Camera, Geometry, Light, Model, SpatialAudio.
- **77 of the 178 Kotlin files** in `arsceneview/` import `com.google.ar.core` directly
  (58 under `src/main`, 19 under `src/test`); 100 reference the package at all. On Apple
  the equivalent surface is ARKit/RealityKit.

The façade covers **4** of those 5. SpatialAudio is left out deliberately: it is not part
of the viewer case, and each platform's audio session has its own lifecycle to own.

So the façade covers the *model viewer* case — load a model, orbit it, light it, tap
it — which is roughly 80% of real usage, and says so plainly.

### Explicit non-goals

These are **not** coming to `commonMain`, in this release or a later one:

- **AR.** Abstracting `ARCore Config` and `ARKit ARConfiguration` behind one type
  produces a lowest-common-denominator API that lies about both. `ARScene` stays
  per-platform.
- **Materials, shaders, post-processing.** `.filamat` blobs and RealityKit ShaderGraph
  have no common ground.
- **The full node catalogue.** Splat, Video, View, ContactShadow, Physics and Text stay
  platform-native.
- **Migrating `sceneview/` to Compose Multiplatform.** The Android library keeps
  targeting Compose Android. Existing consumers are untouched.

### A failed load is reported, not just logged (decided 2026-08-06)

`SceneViewer` shipped with no error surface: a load that failed left the viewport showing
the environment, which is *pixel-identical* to a load still in progress, and the only
trace was a logcat line. That was flagged at review and consciously accepted — a viewer
subset can reasonably decide that failures are the app's problem to detect.

It was reconsidered and reversed, on one measurable fact: **the module is unreleased**
(no `sceneview-compose` artifact on Maven Central, its changelog fragment still pending in
`changelog.d/`). Adding a parameter costs nothing today and costs compatibility forever
after the first publication, so deferring the decision was strictly worse than taking it.

The shape is deliberately small — `onError: ((SceneViewerError) -> Unit)? = null`, opt-in,
with the log line unchanged when it is absent. It is **not** a general state machine: no
loading/loaded/failed enum, no retry policy, no error taxonomy. Those would be a viewer
API growing into an app framework, which the non-goals above rule out. What it guarantees
is only this: an app can tell "failed" from "still loading", which it previously could not.

Both shapes of failure reach it — an exception, and a loader answering `null` without
throwing. The second is not a nicety: moving asset reads off the main thread in the same
change swapped `createModelInstance` (which raised `IllegalArgumentException` when Filament
refused to parse a buffer) for the suspending `loadModelInstance` (which returns `null` for
the same input). Reporting only exceptions would have made every malformed model fail
silently — a fix for one defect quietly creating another.

`SceneViewerError` has a public constructor (#3051). The type reaches apps through
`onError`, so an app must be able to build one to unit-test whatever it renders on
failure — a retry button should not need a real renderer and a real failed load. The
constructor was `internal` at merge to keep the door open for extra fields; that door
stays open through defaulted parameters, which is the usual additive path and costs
callers nothing.

## Hard guardrails

These are load-bearing. Breaking one turns a bounded, reversible module into a
liability.

1. **No renderer type in `commonMain`.** Not Filament, not RealityKit, not LWJGL, not
   any third-party binding. Enforced by binary-compatibility-validator plus a CI check
   on the committed `.api` dumps.
2. **No `public inline` functions touching a renderer.** Inline bodies get baked into
   consumer bytecode and walk straight around the façade.
3. **Purely additive.** The published Android, Apple and web surfaces do not change by
   one byte, and neither do the store apps.
4. **Scope stated in the module README, at commit 1.** "Viewer subset" is a promise to
   keep, not a phase to grow out of quietly.

## The iOS `UIView` wrapper

`SceneViewerHostView` lives in `SceneViewSwift/Sources/SceneViewSwift/Bridge/`. It is a
`@objc` `UIView` that hosts `SceneView` in a `UIHostingController` and is configured
entirely through primitives on `SceneViewerConfiguration` — no Swift enums, no `SIMD3`,
no optionals-of-scalars, because a bridge boundary cannot carry them.

**One wrapper, three bridges.** `sceneview-compose` needs a `UIView` because KMP cannot
see SwiftUI through cinterop; the Flutter plugin and the React Native module need one
because their host frameworks hand them a `UIView`. Solving "host a SwiftUI scene in
UIKit correctly" once — retain cycles, Swift 6 actor isolation, the persistent content
root that async-loaded models attach to, per-field change gating — is the point, and all
three consume it: `sceneview-compose`, `SceneViewPlugin.swift` and
`SceneViewModule.swift` all render their 3D path through this host.

Each bridge still has a platform-view class of its own, because each does more than
viewing. What stayed behind is what is genuinely theirs: the Flutter method channel, the
React Native prop bag, and the **AR** path — `ARSceneView` is anchor-driven and shares
nothing with the 3D viewer, so `ARSceneViewPlatformView` is untouched. What left is the
part all three had written three times: hosting a SwiftUI scene in UIKit, and loading
models into it.

### What the wrapper needed from `SceneViewSwift`, and why it is additive

The published Swift surface could seed an orbit pose but never report one back, so a
hoisted `CameraState` could only ever echo its own writes. Four additions close that,
none of them changing existing behaviour:

- `SceneView.cameraPose(_:)` — continuous camera write-through, applied only when the
  *value* changes so it coexists with a live drag instead of fighting it.
- `SceneView.onCameraChanged(_:)` — the read-back, fired from the single point that
  already knows the camera really moved (the far side of the #2331 diff-guard), so it
  covers drag, pinch, auto-rotate and re-framing rather than just the gesture handlers.
- `SceneView.cameraGesturesEnabled(_:)` — freezes the gestures without handing the camera
  to Apple's `realityViewCameraControls(_:)`, which `CameraControlMode.none` does and
  which would silently switch off `cameraPose(_:)` too.
- `SceneView.onEntityTapHit(_:)` — the tap plus a world-space position. A distinct
  *base* name, arrived at the hard way: an overload distinguished only by a `hit:` label
  does **not** protect existing call sites, because an unlabelled trailing closure ignores
  the label. Measured, every published `.onEntityTapped { entity in }` snippet — in
  `llms.txt`, both iOS cheatsheets and the SwiftUI codelab — stopped compiling under the
  labelled overload.

### One pre-existing bug it surfaced

`.onEntityTapped` never fired on iOS, and neither did any `NodeGesture` handler. Nodes
generated collision shapes — the documented purpose of `enableCollision` is "for hit
testing" — but SwiftUI's `targetedToAnyEntity()` gestures additionally require an
`InputTargetComponent`, which nothing in the package ever set. The failure is completely
silent: no error, no warning, a scene that looks correct until someone taps it. The
repo's own `CollisionHitTestDemo` had never been tappable, and its header comment
asserted the opposite.

The first attempt at this fix touched `ModelNode.load` only, while the changelog
announced the class — which the review caught. `InputTargetComponent` now goes on the
whole content subtree in `buildContent` (covering `GeometryNode`, `MeshNode`, `TextNode`,
`ImageNode`, `ShapeNode`, `ViewNode` and `PhysicsNode`), on the loaded model under
`enableCollision`, and on the entity a `NodeGesture` handler registers against. Measured
on the iOS 26.3 simulator: a tap on a loaded `.usdz` and on an inline `GeometryNode.cube`
produced no callback before and fired on the first try after. This also repairs the
Flutter bridge's `onTap` ([#2051](https://github.com/sceneview/sceneview/issues/2051)).

The other face of it is a behaviour change to code that did not ask: handlers registered
through `NodeGesture` that were silently dead will now fire. Camera orbit and pinch are
unaffected — the entity gestures are attached with `.simultaneousGesture`, and a drag
over a model was verified to still orbit by the expected amount.

### Divergences the wrapper reports rather than hides

Stated in `sceneview-compose/README.md` and in the KDoc of each affected parameter:
`onFrame` is never called (RealityKit publishes no per-frame callback); iOS reads
`.usdz` / `.reality` and no glTF; a tap that misses produces no callback rather than a
null hit; the hit position is the tapped entity's bounds centre, not the surface point;
`EnvironmentSource.Color` still gets RealityKit's default IBL; `ambientIntensity` applies
only to an HDR environment; and `CameraState.distance` is clamped to RealityKit's dolly
envelope — with the clamped value reported back, so it is visible rather than a silent
disagreement between the state and the screen.

## Desktop bindings: vendored from filament-kmp, with attribution

Google [removed Filament's Java/desktop support in 2021](https://github.com/google/filament/pull/4263)
and closed the [KMP request as *not planned*](https://github.com/google/filament/issues/7558),
so there is no official Filament JVM desktop binding. The alternatives were: depend on
[Erkko68/filament-kmp](https://github.com/Erkko68/filament-kmp), write our own binding,
or write a non-Filament renderer.

**Decision: vendor the desktop path of filament-kmp under `third_party/`, under the
terms of its Apache-2.0 licence.** It is genuinely good work — Google's official C++
prebuilts, a hand-written combined C wrapper, and FFM bindings generated with
`jextract` — and copying it beats both re-deriving it and taking a runtime dependency.

Why vendor rather than depend:

- Its `filament-compose` module is *already* a high-level declarative CMP API. Building
  our façade on top of the published artifact would make SceneView a rename of its own
  dependency, permanently one release behind. We take the bindings, not the DSL.
- It would otherwise put a single-maintainer, pre-1.0 dependency (~10 source-breaking
  changes in `0.3.0` alone) on the critical path.
- Vendoring lets the desktop track its own Filament version deliberately. Upstream ships
  1.74.0 (`MATERIAL_VERSION` 74) while SceneView Android pins 1.72.1 (73) and web pins
  1.52.3 (52); the repo holds **28 committed `.filamat` blobs** across those tracks
  (`git ls-files '*.filamat'`), and v4.1.0 already crashed 10 demos over exactly one such
  mismatch.

### The copy is taken when the spike starts, not before

The tree was vendored, landed on `main` in `c01ae5d87`, and was removed again days later
without ever being built. No `settings.gradle` referenced it, so 31 700 lines sat in
every checkout compiling nothing — while its own §4(b) guard `git clone`d a
single-maintainer GitHub repository on every CI run and failed the job if that clone
failed, putting every pull request in the monorepo behind one individual's repo staying
reachable.

The decision above is unchanged; only its timing moved. Restoring the copy is one
command and the full procedure — including the attribution and CI obligations that must
land in the same PR — is in
[desktop-filament.md § Re-vendoring the binding](desktop-filament.md#re-vendoring-the-binding).

### Attribution is mandatory, not optional

Apache-2.0 permits this copy and sets the conditions for it. All of the following are
required whenever the tree is present, and a missing one makes the copy a licence
violation:

- The upstream `LICENSE` ships inside `third_party/filament-kmp/`.
- A `NOTICE` names **Èric Bitriá Ribes** as the original author, links
  <https://github.com/Erkko68/filament-kmp>, and records the copied version, both in
  that directory and in the repository's root `NOTICE`.
- Files we change carry a prominent notice that they were modified.
- Upstream sources carry no per-file copyright headers, so attribution lives at
  directory level — that is the correct form here, not an omission.
- A CI job actually runs `diff-upstream.sh`. Attribution that nothing verifies is a
  claim, not a compliance position.

`assets/CREDITS.md` is generated; the third-party entry must flow through its generator
rather than being hand-edited.

### Inherited costs, stated up front

- **JDK 22+** on the desktop module. Project Panama's FFM API is what makes the binding
  possible; copying the code does not remove the floor.
- **A native build chain**: Filament prebuilt download, CMake for the C wrapper, and a
  pinned `jextract` early-access build.
- **Three-OS CI.** The repo has 66 `ubuntu-latest` jobs and 5 macOS jobs and **no
  Windows runner**; one has to be added for the native leg.
- **Recurring upkeep** at each Filament release we choose to follow — the work upstream
  would otherwise have absorbed.

### Desktop gates

The desktop actual ships only when it clears all of these:

1. ≥60 FPS at 1080p with one glTF model; CPU cost measured, not asserted.
2. Zero per-frame allocation in steady state, double-buffered readback.
3. Resize storm: 30 s of continuous drag, no crash, memory back to baseline.
4. 100 open/close cycles, RSS stable.
5. Seam proof: the `.api` dump contains no renderer type, and a `Noop` implementation
   satisfies the interface.
6. `bash .claude/scripts/check-vendored-download-safety.sh` is green **with the binding
   restored and actually wired**. The `0.3.0` build-logic downloads Filament and `jextract`
   archives with no integrity check, and `extractAll` creates symlinks from an unvalidated
   `entry.linkName` — a tarball carrying `a -> /tmp/evil` followed by `a/x` writes outside
   the destination, because `normalize()` does not resolve symlinks and the entry-path
   check therefore passes. Both are build-time code execution, and both are harmless only
   while nothing compiles the tree. The gate exits 0 on the tree as it stands (absent since
   #3015) and fails from the instant a `settings.gradle` include lands, so this cannot be
   deferred to a follow-up PR: the hardening lands *with* the build chain, not after it.
   Full remediation in
   [desktop-filament.md § Re-vendoring the binding](desktop-filament.md#re-vendoring-the-binding),
   item 4.

## Increments

Each one is independently shippable. No big bang.

1. `sceneview-compose` module, `commonMain` API, **Android actual** — the delegation is
   near-free (on Android, Compose Multiplatform *is* androidx.compose).
2. **iOS actual** — shipped, but not the way this section originally planned. The plan
   was to add an `@objc` façade *inside* `SceneViewSwift` and depend on it. That is not
   buildable: a KMP module cannot depend on a Swift Package at all, so the dependency
   direction had to be inverted. `SceneViewer` now declares what it needs — a factory
   producing a `UIView` (`SceneViewerBridge`) — and the **app** supplies it once at
   launch, where `SceneViewSwift` is already linked. The `@objc` façade did land inside
   `SceneViewSwift` after all, as `SceneViewerHostView`; what changed is that nothing
   depends on it at build time — the app links it and hands it over. See
   [the iOS wrapper](#the-ios-uiview-wrapper) below.
3. **Desktop actual** — shipped via Maven `filament-kmp` 0.3.1 (`implementation`, never
   `api`), not a vendored tree. filament-kmp already owns the offscreen readback → Skia
   path; `SceneViewer.desktop.kt` is the façade. JDK 22+ and
   `--enable-native-access=ALL-UNNAMED` at run. The vendoring gates below remain the
   path *if* we later copy the binding into `third_party/`; they are not a blocker for
   the Maven actual.
4. CMP sample, `llms.txt` section, honest platform matrix.

Web is deliberately last and unscheduled: `sceneview-core`'s `wasmJs` target is still
blocked on `dev.romainguy:kotlin-math` publishing a wasmJs variant.

## References

- [#558](https://github.com/sceneview/sceneview/issues/558) ·
  [#486](https://github.com/sceneview/sceneview/issues/486) — the original asks
- [desktop-filament.md](desktop-filament.md) — desktop renderer analysis and the
  filament-kmp evaluation
- [google/filament#4263](https://github.com/google/filament/pull/4263) — Java/desktop
  support removed (2021)
- [CMP 1.8.0 — iOS stable](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)
