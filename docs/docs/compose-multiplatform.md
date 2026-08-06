# SceneView on Compose Multiplatform — scope & decision

Decision record for `sceneview-compose`, a Compose Multiplatform façade over the
existing per-platform renderers.

**Last updated:** 2026-08-03 · **Answers:** [#558](https://github.com/sceneview/sceneview/issues/558),
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
| iOS | `UIKitView` hosting an app-supplied `UIView` (see `SceneViewerBridge`) | RealityKit | implemented — the app registers the factory |
| Desktop (JVM) | offscreen render → pipelined `readPixels` → Skia image | Filament, via a vendored FFM binding | **planned** — draws a placeholder today |

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

### Attribution is mandatory, not optional

Apache-2.0 permits this copy and sets the conditions for it. All of the following are
required, and a missing one makes the copy a licence violation:

- The upstream `LICENSE` ships inside `third_party/filament-kmp/`.
- A `NOTICE` names **Èric Bitriá Ribes** as the original author, links
  <https://github.com/Erkko68/filament-kmp>, and records the copied version (`0.3.0`).
- Files we change carry a prominent notice that they were modified.
- Upstream sources carry no per-file copyright headers, so attribution lives at
  directory level — that is the correct form here, not an omission.

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
   actually wired**. The vendored build-logic downloads Filament and `jextract` archives
   with no integrity check, and `extractAll` creates symlinks from an unvalidated
   `entry.linkName` — a tarball carrying `a -> /tmp/evil` followed by `a/x` writes outside
   the destination, because `normalize()` does not resolve symlinks and the existing
   entry-path check therefore passes. Both are unreachable while nothing builds the tree,
   and both become build-time code execution the moment something does. The gate is silent
   until a `settings.gradle` include lands and fails from that instant, so this cannot be
   deferred to a follow-up PR: the hardening lands *with* the build chain, not after it.

## Increments

Each one is independently shippable. No big bang.

1. `sceneview-compose` module, `commonMain` API, **Android actual** — the delegation is
   near-free (on Android, Compose Multiplatform *is* androidx.compose).
2. **iOS actual** — shipped, but not the way this section originally planned. The plan
   was to add an `@objc` façade *inside* `SceneViewSwift` and depend on it. That is not
   buildable: a KMP module cannot depend on a Swift Package at all, so the dependency
   direction had to be inverted. `SceneViewer` now declares what it needs — a factory
   producing a `UIView` (`SceneViewerBridge`) — and the **app** supplies it once at
   launch, where `SceneViewSwift` is already linked. Still open: the reusable Swift
   `@objc` `UIView` wrapper itself, which also serves the Flutter and React Native
   bridges.
3. **Desktop actual**, on the vendored Filament binding, behind the gates above. This is
   the long pole: ~26 000 lines to vendor plus a three-OS native build chain.
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
