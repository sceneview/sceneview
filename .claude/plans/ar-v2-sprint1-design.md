# AR V2 Sprint-1 (Android) + iOS RealityKit parity — implementation-ready design

> **Issue:** #2521 (sub-issue of #2517) · **Implements:** #2241 (Sprint-1 Android) + #2210
> (iOS RealityKit parity).
> **Binds to:** `.claude/plans/tap-to-place-unification.md` §7 "Sprint-1 (#2241) compatibility
> contract" (merged via #2522). This design lands its three components into that doc's named
> slots and contradicts none of its decisions.
>
> **Source snapshot:** every `file:line` below resolves against branch `main` @ `5e8c0e30b`
> ("fix(android-demo): cover the ~1-3s AR camera warm-up with the shared init scrim in 11 AR
> demos (#2484) (#2502)", 2026-06-15) in the sceneview repo.
>
> **Scope:** design only — no code in this PR. Written so an Opus-class agent can implement each
> PR without re-deriving any decision.
>
> **Input caveats (kept honest):**
> - The `.claude/plans/v2-*.md` Sprint-1 research files are gitignored and were **not present**
>   in this snapshot. The Sprint-1 direction below is taken from issue **#2241**'s body and the
>   already-merged unification doc §7 — no prior v2-*.md decision is invented or cited.
> - PR **#2502** (the AR camera-init scrim, #2484) has **landed** — it is the HEAD commit. The
>   unification doc was written while #2502 was still open; that caveat is now resolved. The
>   scrim already exists in `ARPlacementDemo.kt` (`ARCameraInitScrim`, demos/ARPlacementDemo.kt:625).
> - Industry-comparison claims (what "the industry does" with plane decoration) are **not
>   statically verifiable from this repo** — they are quoted from #2241's body, not re-derived.

---

## Table of contents

0. [TL;DR — what gets built](#0-tldr)
1. [Component specs](#1-component-specs)
   - 1.1 [PlaneDiscoveryGuide](#11-planediscoveryguide)
   - 1.2 [ShadowReceiverPlane](#12-shadowreceiverplane)
   - 1.3 [PlacementReticle](#13-placementreticle)
2. [Integration with TapToPlaceArSession](#2-integration)
3. [iOS RealityKit parity (#2210)](#3-ios-parity)
4. [File-by-file change plan](#4-file-plan)
5. [Test & QA plan](#5-test-qa)
6. [Open questions](#6-open-questions)
7. [Appendix — what already exists (do not rebuild)](#7-appendix)

---

<a name="0-tldr"></a>
## 0. TL;DR — what gets built

Three **library-level** APIs in `arsceneview/` (#2241), then a 4th PR wires them into the
demo-app session shell and a 5th into iOS:

| # | Deliverable | Module | Material/shader | New code (est.) |
|---|---|---|---|---|
| PR 1 | Strings + Compose-Canvas hand asset (no Lottie) | `samples/android-demo` | — | ~+180 |
| PR 2 | `@Composable PlaneDiscoveryGuide(...)` — onboarding state machine | `arsceneview` (UI overlay) | none | ~+260 |
| PR 3 | `ShadowReceiverPlane` Node + reuse `plane_renderer_shadow.mat` | `arsceneview` | **REUSE** existing `.mat` (no new `.filamat`) | ~+170 |
| PR 4 | `@Composable PlacementReticle(...)` — depth-sampled, slerp-smoothed | `arsceneview` | optional new `reticle.mat` (see §1.3 — **default: reuse unlit**) | ~+230 |
| PR 5 | Wire all three into `TapToPlaceArSession`; modernise `ARPlacementDemo` | `samples/android-demo` | — | ~+120 / −90 |
| PR 6 | iOS Layer A parity + Layer B (#2210) | `SceneViewSwift` | — | ~+300 |
| PR 7 | llms.txt + KDoc + changelog | docs | — | ~+90 |

**Critical pre-existing facts that shrink the work (see §7):**

- The shadow-receiver material **already exists and ships**: `arsceneview/src/main/materials/plane_renderer_shadow.mat`
  (`shadowMultiplier : true`, transparent, profile C) — consumed today by both
  `PlaneRenderer` (arsceneview/.../scene/PlaneRenderer.kt:85-86) and `PlaneRendererV2`
  (arsceneview/.../scene/PlaneRendererV2.kt:141-142). `ShadowReceiverPlane` **reuses** it —
  **no new `.filamat` blob, no `matc` run, the ABI invariant is untouched.**
- A reticle primitive **already exists**: `ReticleNode` (arsceneview/.../node/ReticleNode.kt)
  + the `ARSceneScope.ReticleNode` composable (arsceneview/.../ARSceneScope.kt:406-452), a thin
  `HitResultNode` wrapper with an `onHitResultChanged` callback and #1891 plane-only defaults.
  `PlacementReticle` is the **depth-sampling + slerp-smoothing + default visual** layer on top —
  not a from-scratch hit tester.
- On iOS, the coaching overlay (`ARCoachingOverlayView`, ARSceneView.swift:268-273), the
  translucent plane visualizer (`PlaneVisualizer`, ARSceneView.swift:766-840) and tap-to-place
  raycast (`handleTap`, ARSceneView.swift:638-653) **already ship**. iOS Layer A is mostly a
  *mapping + polish* exercise, not a build.

---

<a name="1-component-specs"></a>
## 1. Component specs

All three are **library** APIs (`io.github.sceneview.ar`), per #2241. They are consumed by the
demo-app `TapToPlaceArSession` shell (§2) but belong in `arsceneview/` so any consumer app gets
them.

---

<a name="11-planediscoveryguide"></a>
### 1.1 PlaneDiscoveryGuide — the onboarding state machine

**Purpose.** A self-contained Compose overlay that walks a first-time AR user from "black
viewport" to "plane found" with the timing the #2241 spec pins (a port of the ARCore Elements
`PlaneDiscoveryGuide` UX state machine): silent 0–3 s, hand-hint + snackbar at 3 s, help button at
8 s, 0.75 s fade-out on first plane, contextual tracking-lost copy.

**Public API (compilable Kotlin shape, real types from the repo):**

```kotlin
package io.github.sceneview.ar

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.google.ar.core.TrackingFailureReason

/**
 * Onboarding overlay that guides a user to find a plane, then gets out of the way (#2241).
 *
 * Pure UI: it owns no ARCore session and runs no hit test. It reads three already-produced
 * signals and drives the timed onboarding UX (hand hint, snackbar, help button, fade-out).
 * Render it as a sibling INSIDE the `ARSceneView`'s Box, above the viewport.
 *
 * Timing (validated copy/durations from the #2241 spec):
 *  - 0–3 s after first frame, camera TRACKING, no plane → silent (let the user just look).
 *  - 3 s   → animated hand sweep + "Move your phone to find a surface" snackbar.
 *  - 8 s   → a "Need help?" affordance (expands the snackbar into a tip card).
 *  - first plane TRACKING → 0.75 s fade-out, then the guide stops composing children.
 *  - camera not TRACKING / a forced failure → contextual lost message (5 reasons), timers reset.
 */
@Composable
fun BoxScope.PlaneDiscoveryGuide(
    /** True once ARCore has delivered its first camera frame (drives "wait vs guide"). */
    cameraReady: Boolean,
    /** `frame.camera.trackingState == TRACKING`. */
    isTracking: Boolean,
    /** ≥1 ARCore Plane in TrackingState.TRACKING — the fade-out trigger. */
    anyPlaneTracked: Boolean,
    /** Latest ARCore failure reason, or null. Null while TRACKING. */
    trackingFailureReason: TrackingFailureReason? = null,
    modifier: Modifier = Modifier,
    /** Overridable timings — defaults are the #2241-validated values. */
    state: PlaneDiscoveryGuideState = rememberPlaneDiscoveryGuideState(),
    /** Optional "Need help?" tap handler (e.g. open docs / expand a tip card). */
    onHelp: (() -> Unit)? = null,
)

/** Hoisted timing config + derived phase. Test the phase derivation headlessly. */
@Immutable
data class PlaneDiscoveryGuideDurations(
    val handHintAfterMs: Long = 3_000L,
    val helpAfterMs: Long = 8_000L,
    val fadeOutMs: Long = 750L,
)

enum class PlaneDiscoveryPhase { WAITING, SILENT, HAND_HINT, HELP_OFFERED, LOST, FADING_OUT, DONE }

@androidx.compose.runtime.Stable
class PlaneDiscoveryGuideState internal constructor(
    val durations: PlaneDiscoveryGuideDurations,
) { /* elapsedSinceTrackingMs, phase derivation, latch on DONE */ }

@Composable
fun rememberPlaneDiscoveryGuideState(
    durations: PlaneDiscoveryGuideDurations = PlaneDiscoveryGuideDurations(),
): PlaneDiscoveryGuideState
```

**ARCore data deps.** None directly — it consumes the three booleans + the failure reason the
host already computes. The host wires them from `onSessionUpdated` /
`onTrackingFailureChanged`, exactly the signals `deriveUxState` already uses
(tap-to-place-unification.md §2.1). `anyPlaneTracked` is the per-frame
`session.getAllTrackables(Plane::class.java).any { it.trackingState == TRACKING }` scan that the
unification doc already moves into the session (tap-to-place-unification.md:357, §3.2).

**Rendering — pure Compose, ZERO Filament.**
- The hand-hint is a **Compose `Canvas`** animation (an arc-sweep phone glyph), **not** Lottie —
  #2241 PR 1 explicitly forbids a Lottie dependency. Driven by `rememberInfiniteTransition`.
- The snackbar / help card are Material 3 `Surface` capsules, matching the unified pill style
  (`ui/ArViewTab.kt:477-533` translucent capsule, referenced by tap-to-place-unification.md §2.4).
- The fade-out is a single `AnimatedVisibility` with a 750 ms `fadeOut` keyed on `anyPlaneTracked`.
- **No `.mat`, no `.filamat`, no mesh.** This is a 2D overlay; it never enters the Filament scene
  graph. → **ABI invariant N/A.**

**Contextual lost messages.** Reuse the existing `tracking_failure_*` string set
(strings.xml:288-292) — the 5 reasons (`BAD_STATE`, `INSUFFICIENT_LIGHT`, `EXCESSIVE_MOTION`,
`INSUFFICIENT_FEATURES`, `CAMERA_UNAVAILABLE`) already have user-tested copy via
`trackingFailureMessage(...)` (common/TrackingFailureMessages.kt, used at
demos/ARPlacementDemo.kt:717). **Do not invent a parallel string set** — the unification doc
already flags the `ar_tracking_*` vs `tracking_failure_*` duplication for a localisation diff
(tap-to-place-unification.md:534-536); PlaneDiscoveryGuide standardises on `tracking_failure_*`.

**State/lifecycle.** Phase is a pure function of `(cameraReady, isTracking, anyPlaneTracked,
elapsedSinceTrackingMs, failureReason)`. The elapsed clock starts on the SILENT→ first stable
TRACKING transition and resets on any LOST. `DONE` is latched once reached (the guide never
re-onboards within one session unless the host recreates it via `key(...)`). This mirrors the
unification doc's "orthogonal layers, latched DONE" structure so it slots cleanly into §2.

---

<a name="12-shadowreceiverplane"></a>
### 1.2 ShadowReceiverPlane — invisible mesh that catches virtual shadows

**Purpose.** Ground a placed model by catching its shadow on the real surface, **without**
drawing a visible plane decoration (the #2241 industry direction: "make virtual content respect
real geometry"). The mesh renders invisible (`Blend Zero SrcColor` equivalent) and only darkens
the camera feed where a shadow falls.

**Public API:**

```kotlin
package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Plane
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.ar.node.PlaneNode

/**
 * An invisible shadow-receiver surface bound to a detected ARCore [Plane] (#2241).
 *
 * Wraps the existing shadow-receiver material (`plane_renderer_shadow.mat`,
 * `shadowMultiplier : true`) onto a plane-tracked mesh: the surface is itself invisible but
 * receives shadows cast by scene renderables, so a placed model reads as grounded on the real
 * floor. Distinct from [PlaneNode]'s plane *visualisation* — this is the shadow catcher only.
 *
 * Declare inside an `ARSceneScope` content block (or via the `ShadowReceiverPlane` composable):
 *     ShadowReceiverPlane(plane = trackedPlane)
 */
open class ShadowReceiverPlaneNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    plane: Plane,
) : PlaneNode(engine = engine, plane = plane) { /* applies the shadow material instance */ }
```

```kotlin
// In ARSceneScope (alongside the existing PlaneNode composable at ARSceneScope.kt:833):
@Composable
fun ShadowReceiverPlane(
    plane: Plane,
    visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    apply: ShadowReceiverPlaneNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
)
```

**ARCore data deps.** A tracked `Plane` (same input as the existing `PlaneNode` composable,
ARSceneScope.kt:834-835). The host enumerates planes from `frame.getUpdatedTrackables(Plane)` /
`session.getAllTrackables(Plane)` — the standard `PlaneNode` pattern (ARSceneScope.kt:826,
"Obtain … from Frame.getUpdatedTrackables").

**Rendering — material is REUSED, no new blob.**

| Asset | Status | Profile | ABI action |
|---|---|---|---|
| `arsceneview/src/main/materials/plane_renderer_shadow.mat` | **EXISTS** (PlaneRenderer.kt:85, PlaneRendererV2.kt:141) | C — `--optimize-size -p mobile -a opengl -a vulkan` (CONTRIBUTING.md:309) | **NONE — reused as-is** |
| `arsceneview/src/main/assets/materials/plane_renderer_shadow.filamat` | **EXISTS** (committed blob) | C | **NONE — not recompiled** |

The material is already a generic shadow catcher: `shadingModel : unlit`, `blending :
transparent`, `shadowMultiplier : true`, fixed 60 %-alpha black, with the +0.005 m y-shift to
avoid z-fighting (plane_renderer_shadow.mat:8-31). `ShadowReceiverPlaneNode` calls
`materialLoader.createMaterial("materials/plane_renderer_shadow.filamat")` (the exact call shape
already used at PlaneRendererV2.kt:141-142) and `setMaterialInstance(...)` on the plane mesh,
then enables shadow receiving. **Because no `.mat` source changes, the `GenerateFilamat.sh`
drift gate (CONTRIBUTING.md:301) stays green and the Filament runtime↔`.filamat` ABI invariant
(CONTRIBUTING.md:273-321) is not engaged.**

> ⚠️ If, during implementation, a *dedicated* shadow material with different alpha/softness is
> wanted (e.g. a softer 40 % shadow distinct from the plane-renderer's 60 %), that becomes a NEW
> `.mat` + `.filamat` under **profile C**, MUST be added to the `MATS` inventory in
> `tools/GenerateFilamat.sh`, compiled with the pinned `matc` (`bash tools/GenerateFilamat.sh`),
> and **committed in the same PR** as the runtime it targets (CONTRIBUTING.md:313, :317-319).
> **Design default: do NOT add one — reuse the existing material; ship the new alpha only if a
> device pass shows the 60 % shadow reads too heavy.**

**State/lifecycle.** Same as `PlaneNode`: visible only while the plane is in
`visibleTrackingStates` (ARSceneScope.kt:836); a subsumed plane stops rendering automatically
(ARSceneScope.kt:822-824). The shadow material instance is created once per node and destroyed
on node disposal (mirror the `destroyMaterial` teardown at PlaneRendererV2.kt:275).

**Lighting dependency.** Shadows require a shadow-casting light. `ARSceneView` already sets up
ENVIRONMENTAL_HDR light estimation by default (ARSceneView.kt:936) which drives a directional
main light; the placed `ModelNode` casts by default. The shadow only appears if the estimated
main light casts — verify on device (§5.3). No new light wiring in this component.

---

<a name="13-placementreticle"></a>
### 1.3 PlacementReticle — depth-sampled, slerp-smoothed placement cursor

**Purpose.** A library composable that shows where the next tap lands, snapped to the surface
normal, with two upgrades over today's demo-local reticle: depth-sample the centre hit (not just
plane hits) and **slerp the orientation by 0.75** so the disc doesn't jitter as ARCore refines
the normal (a port of the Depth Lab `OrientedReticle` smoothing).

**Public API (builds ON the existing `ReticleNode`, ARSceneScope.kt:406):**

```kotlin
// In ARSceneScope:
@Composable
fun PlacementReticle(
    xPx: Float,
    yPx: Float,
    /** Acceptance policy — defaults to plane-only (#1891), matching ReticleNode. */
    snapToPlane: Boolean = true,
    depthPoint: Boolean = false,
    /** Slerp factor for normal smoothing (Depth Lab OrientedReticle ≈ 0.75). */
    orientationSmoothing: Float = 0.75f,
    /** Fired on every hit change (drives AIMING/READY host state). */
    onHitResultChanged: ((HitResult?) -> Unit)? = null,
    /** Default visual when `content == null`: a thin unlit-cyan disc. */
    content: (@Composable NodeScope.() -> Unit)? = null,
)
```

**ARCore data deps.** Per-frame `Frame.hitTest(xPx, yPx)`, already done inside `ReticleNode`/
`HitResultNode` (the base resolves hits from the render-loop frame, ReticleNode.kt:61-64). When
`depthPoint = true`, the underlying `HitResultNode` `depthPoint` flag is set; depth hits require
`Config.DepthMode` ≠ DISABLED (ARSceneView.kt depth doc) — **gate the default OFF** (today's demo
reticle is plane-only, ARPlacementDemo.kt:551-556) so a no-depth device is unaffected.

**Rendering.**

| Asset | Status | Decision |
|---|---|---|
| Reticle disc geometry | Built in code via `CylinderNode(radius=0.07f, height=0.005f, sideCount=48)` (ARPlacementDemo.kt:572-577) | **REUSE** — no asset |
| Reticle material | `materialLoader.createUnlitColorInstance(Color(0x99_44E7FF))` (ARPlacementDemo.kt:535-538; signature MaterialLoader.kt:309) | **REUSE existing unlit material** — no new `.mat` |
| Optional `reticle.mat` (pulsing ring shader) | does not exist | **DO NOT ADD by default.** A textured/animated ring is a polish item; if added it is profile **C**, new `.filamat`, inventory + recompile + same-PR commit per CONTRIBUTING.md:313-319. Design default: ship the unlit disc — it's a move of the proven demo visual. |

**Orientation smoothing — the only genuinely new logic.** `ReticleNode` delegates pose to
`HitResultNode`, which sets the node pose from the hit pose each frame. `PlacementReticle` adds a
slerp on the rotation component: keep the last applied quaternion, slerp toward the hit's
quaternion by `orientationSmoothing` each frame. The repo already has slerp math in
`sceneview-core` (math/animation) — reuse it rather than hand-rolling. The smoothing is **pose
post-processing inside the node `apply` block / a `hitTest`-result transform**, not a new ARCore
call.

**State/lifecycle.** Identical to `ReticleNode`: a `null` hit clears the trackable → drops out of
`visibleTrackingStates` → the child disc stops rendering automatically (ReticleNode.kt:28-31), so
the "hide on no surface" behaviour is free. `onHitResultChanged` is the change-only callback
(ReticleNode.kt:128-136) that feeds `state.reticleHit` (tap-to-place-unification.md:173) → the
AIMING/READY derivation.

> **Relationship to the unification doc's reticle slot.** tap-to-place-unification.md:714 says PR
> 4 "Replaces the internal `HitResultNode(hitTest=…)` + `CylinderNode` block in
> `TapToPlaceArSession.kt`. `PlacementHitPolicy` stays the acceptance policy". `PlacementReticle`
> therefore exposes the same `snapToPlane` knob and routes its acceptance through
> `PlacementHitPolicy.accept(...)` (tap-to-place-unification.md:308-326) via its `predicate`/
> `hitTest`, so the on-screen disc and the tap handler share one policy — zero divergence.

---

<a name="2-integration"></a>
## 2. Integration with `TapToPlaceArSession`

The unification doc (`tap-to-place-unification.md`) is the demo-app integration shell. §7 of that
doc names exactly where each Sprint-1 component lands. This design fills those slots and changes
**nothing** in either host (`ui/ArViewTab.kt`, `demos/ARPlacementDemo.kt`).

| #2241 component | §7 slot (verbatim) | What the entry gains | Divergence |
|---|---|---|---|
| **PlaneDiscoveryGuide** | "Replaces the SCANNING/TRACKING_LOST affordances **inside `TapToPlaceStatusOverlays`**." (tap-to-place-unification.md:713) | Both AR entries (AR View tab + ar-placement demo) get the timed hand-hint + help + fade-out onboarding instead of a static "Scanning…" pill. | None — `deriveUxState`'s camera/plane/failure signals (unification §2.1) are exactly its inputs. The §2.4 pill text is the **fallback** vocabulary, layered under the guide. |
| **PlacementReticle** | "Replaces the internal `HitResultNode(hitTest=…)` + `CylinderNode` block in `TapToPlaceArSession.kt`." (tap-to-place-unification.md:714) | Smoother, depth-capable reticle for both entries; `PlacementHitPolicy` stays the acceptance gate; `state.reticleHit` keeps feeding AIMING/READY. | None — same policy object, same change-only state write. |
| **ShadowReceiverPlane** | "Added inside the session's scene content (or via `extraSceneContent` during experimentation)." (tap-to-place-unification.md:715) | Placed models ground with a real shadow on both entries. | None — uses the session's `ARSceneScope` content slot (`extraSceneContent`, unification §1.3 / TapToPlaceArSession param at tap-to-place-unification.md:282). |
| **"refonte ARPlacementDemo" (PR 5)** | "Becomes a refonte of the **session internals** — and therefore upgrades the consumer AR View entry in the same commit." (tap-to-place-unification.md:716) | The #2241 investment lands once, in `TapToPlaceArSession.kt`; both hosts inherit it. | None — this is the central payoff of Option A. |

**Concrete wiring inside `TapToPlaceArSession.kt`** (the file the unification doc creates at
`samples/android-demo/.../common/placement/TapToPlaceArSession.kt`, tap-to-place-unification.md
§3.2):

1. The reticle block (the code the unification doc moves verbatim from ARPlacementDemo.kt:529-578)
   is **replaced** by `PlacementReticle(xPx = centreX, yPx = centreY, snapToPlane = snapToPlane,
   onHitResultChanged = { state.reticleHit = it })`. The default disc child is kept.
2. `TapToPlaceStatusOverlays` (tap-to-place-unification.md:294-298) gains
   `PlaneDiscoveryGuide(cameraReady = state.cameraReady, isTracking = state.isTracking,
   anyPlaneTracked = state.anyPlaneTracked, trackingFailureReason = state.trackingFailureReason)`
   as the SCANNING/TRACKING_LOST renderer, with the §2.4 pill kept as the fallback text path.
3. `ShadowReceiverPlane(plane = it)` is declared in the session's scene content for each tracked
   plane (or behind a dev toggle during experimentation via `extraSceneContent`).

**Ordering note.** Because the unification PR (#2518/#2522) is **already merged**, Sprint-1 PRs
2–4 build the library APIs first (no host dependency), and **PR 5** is the only one that edits
`TapToPlaceArSession.kt`. PRs 2–4 are independently mergeable and testable in isolation
(headless + the `PlaneGridPreviewDemo` non-AR harness, §5).

---

<a name="3-ios-parity"></a>
## 3. iOS RealityKit parity (#2210)

#2210 asks for the iOS counterpart. The honest framing: RealityKit's auto-plane overlay
(`showPlaneOverlay`) is "a single boolean that doesn't take custom shaders" (#2210 body), so iOS
needs its own architecture. **But several pieces already exist** (§7) — Layer A is mostly mapping.

### 3.A Layer A — strict feature parity (Android component → RealityKit equivalent)

| Android (Sprint-1) | RealityKit equivalent | Already in `SceneViewSwift`? | Free vs build |
|---|---|---|---|
| **PlaneDiscoveryGuide** onboarding | `ARCoachingOverlayView` (goal `.horizontalPlane`/`.verticalPlane`/`.anyPlane`, `activatesAutomatically`) | **YES** — wired at ARSceneView.swift:268-273, goal mapped at :500-506 | **FREE** — Apple's first-party coaching IS the onboarding state machine. Parity = expose a `showCoachingOverlay` toggle (already a param, ARSceneView.swift:102) + confirm the goal mapping. No hand-hint to port: the coaching overlay ships its own animated guidance. |
| **ShadowReceiverPlane** (invisible shadow catcher) | RealityKit `GroundingShadowComponent` on the placed model OR an `OcclusionMaterial`/shadow-receiver plane entity | **NO** — no grounding-shadow code today (`grep GroundingShadow` → none; only `DirectionalLightComponent.Shadow` in RenderQuality.swift) | **BUILD (S)** — add `GroundingShadowComponent(castsShadow: true)` to placed entities so RealityKit projects a contact shadow onto detected planes; the existing `PlaneVisualizer` already anchors per-plane entities (ARSceneView.swift:766) to host a receiver if needed. This is the RealityKit-idiomatic analogue — no custom shader, matching #2210's "authored Materials" note. |
| **PlacementReticle** (centre cursor, normal-snapped) | Continuous `ARView.raycast(from:allowing:.estimatedPlane, alignment:.any)` from the render loop, feeding a small reticle `ModelEntity` | **PARTIAL** — the raycast exists for taps (`handleTap`, ARSceneView.swift:643-647) but is **not** run continuously to drive a visible reticle | **BUILD (S/M)** — run the same raycast each frame in `session(_:didUpdate:)` (the `onFrame` hook already fires per frame, ARSceneView.swift:657-660), place a reticle entity at the result's `worldTransform`, hide it when the raycast is empty. The #1882 KDoc on the Android `ReticleNode` already states this exact iOS mapping (ReticleNode.kt:52-57) — implement it. |
| **Plane visualisation** (translucent overlay) | `PlaneVisualizer` translucent fill | **YES** — ships (ARSceneView.swift:766-840, 12 % white fill) | **FREE** |
| **Tap-to-place** | `onTapOnPlane` + raycast | **YES** — ships (ARSceneView.swift:638-653) | **FREE** |

**Layer A net new iOS work:** GroundingShadowComponent on placed models (S) + a continuous-raycast
reticle entity (S/M). Everything else is already shipped or a one-line toggle.

### 3.B Layer B — 1–2 "wow" enhancements beyond strict parity

Pick **one** for this sprint (keep iOS V1 a strict subset of Android per the project rule that iOS
mirrors Android honestly):

1. **(L1) Mesh-anchored shadow receiver** — on LiDAR devices, use `ARMeshAnchor`
   (`sceneReconstruction = .mesh`, already enabled at ARSceneView.swift:732-734) so shadows fall
   on the **real reconstructed geometry**, not just the flat plane — a tangible RealityKit-only
   win that Android's plane-only path can't match. Effort **M**.
2. **(L2) Reticle surface-classification tint** — colour the reticle by ARKit
   `ARMeshAnchor`/plane `classification` (floor vs table vs wall) so the cursor confirms what kind
   of surface it found. Effort **S**. Lower priority than L1.

**Design default:** ship **L1** (mesh-anchored shadows) as the Layer B item; it directly answers
#2210's "depth mesh" ask using infrastructure that already exists. Defer L2.

### 3.C Parity matrix (capability × Android × iOS × effort)

| Capability | Android (Sprint-1) | iOS (RealityKit) | Effort (iOS) |
|---|---|---|---|
| Plane-found onboarding | PlaneDiscoveryGuide (new, PR 2) | `ARCoachingOverlayView` (ships) | **FREE** |
| Contextual tracking-lost copy | `tracking_failure_*` strings (ship) | coaching overlay's built-in guidance | FREE (Apple copy) |
| Translucent plane overlay | PlaneNode / PlaneRendererV2 (ship) | `PlaneVisualizer` (ships) | FREE |
| Placement reticle (normal-snapped) | PlacementReticle (new, PR 4) | continuous raycast + reticle entity (new) | **S/M** |
| Reticle orientation smoothing | slerp 0.75 (new) | slerp on reticle entity transform | S |
| Invisible shadow receiver | ShadowReceiverPlane (reuse `.mat`) | `GroundingShadowComponent` on model (new) | **S** |
| Tap-to-place | `TapToPlaceArSession` engine (ship via #2518) | `onTapOnPlane` raycast (ships) | FREE |
| Shadow on reconstructed mesh (Layer B) | — (not in Android Sprint-1) | `ARMeshAnchor` receiver (L1) | **M** |

---

<a name="4-file-plan"></a>
## 4. File-by-file change plan

### PR 1 — strings + hand-hint asset (`samples/android-demo`)
- **NEW** `samples/android-demo/.../common/placement/PlaneDiscoveryGuideAssets.kt` (~+120) —
  the Compose-`Canvas` hand-sweep animation (NO Lottie, NO drawable — #2241 PR 1).
- **MODIFIED** `res/values/strings.xml` (~±10) — add `ar_guide_move_to_find`, `ar_guide_help`,
  `ar_guide_tip_*`; **reuse** existing `tracking_failure_*` (strings.xml:288-292) for lost copy.
  Mirror into every `values-*` locale (`grep -rl tracking_failure_bad_state res`).
- Diff: ~+130.

### PR 2 — `PlaneDiscoveryGuide` (`arsceneview`)
- **NEW** `arsceneview/src/main/java/io/github/sceneview/ar/PlaneDiscoveryGuide.kt` (~+230) —
  the composable + `PlaneDiscoveryGuideState` + phase derivation + `rememberPlaneDiscoveryGuideState`.
  Pure Compose; no Filament import.
- **NEW** `arsceneview/src/test/.../ar/PlaneDiscoveryPhaseTest.kt` (~+90) — phase truth table.
- Diff: ~+320. **No material/shader. ABI invariant N/A.**

### PR 3 — `ShadowReceiverPlane` (`arsceneview`)
- **NEW** `arsceneview/src/main/java/io/github/sceneview/ar/node/ShadowReceiverPlaneNode.kt` (~+90).
- **MODIFIED** `arsceneview/src/main/java/io/github/sceneview/ar/ARSceneScope.kt` (~+50) — add the
  `ShadowReceiverPlane` composable next to `PlaneNode` (ARSceneScope.kt:833).
- **REUSE** `plane_renderer_shadow.mat` / `.filamat` — **no `.mat` edit, no `matc` run, drift gate
  stays green** (CONTRIBUTING.md:301). **Shader/material build steps: NONE.**
- **NEW** `arsceneview/src/test/.../ShadowReceiverPlaneNodeTest.kt` (~+40) — primitives only
  (material-instance assignment, dispose path), no JNI.
- Diff: ~+180.

### PR 4 — `PlacementReticle` (`arsceneview`)
- **NEW** `arsceneview/src/main/java/io/github/sceneview/ar/node/PlacementReticleNode.kt` (~+110) —
  subclass `ReticleNodeImpl`, add slerp smoothing.
- **MODIFIED** `arsceneview/src/main/java/io/github/sceneview/ar/ARSceneScope.kt` (~+60) — add the
  `PlacementReticle` composable next to `ReticleNode` (ARSceneScope.kt:406).
- **REUSE** unlit material via `createUnlitColorInstance` (MaterialLoader.kt:309) — **no new `.mat`.**
- **NEW** test for the slerp/clamp math (~+60, JVM, reuses `sceneview-core` slerp).
- Diff: ~+230. **Material build steps: NONE** (unless the optional polish ring is added — then
  profile C + recompile + same-PR commit; default: skip).

### PR 5 — wire into the session + modernise demo (`samples/android-demo`)
- **MODIFIED** `samples/android-demo/.../common/placement/TapToPlaceArSession.kt` — swap the
  inline reticle block for `PlacementReticle`; add `PlaneDiscoveryGuide` to
  `TapToPlaceStatusOverlays`; declare `ShadowReceiverPlane` in scene content. (~+120 / −90)
- **MODIFIED** `demos/ARPlacementDemo.kt` — only if a dev toggle for the shadow receiver is added
  to the controls sheet (~+20). The 3 components otherwise arrive transparently via the session.
- Diff: ~+140 / −90.

### PR 6 — iOS parity (`SceneViewSwift`)
- **MODIFIED** `SceneViewSwift/Sources/SceneViewSwift/ARSceneView.swift` — add
  `GroundingShadowComponent` to placed entities (Layer A shadow); add a continuous-raycast reticle
  entity driven from `session(_:didUpdate:)` (ARSceneView.swift:657); Layer B: mesh-anchored
  shadow receiver on `ARMeshAnchor`. (~+260)
- **NEW** `SceneViewSwift/Tests/.../ARReticleParityTests.swift` (~+40) — headless coordinator
  state (raycast-empty → reticle hidden), no simulator AR.
- Diff: ~+300.

### PR 7 — docs
- **MODIFIED** `llms.txt` — document `PlaneDiscoveryGuide`, `ShadowReceiverPlane`,
  `PlacementReticle` in the AR section; iOS parity note.
- **MODIFIED** KDoc on the new public APIs (done inline in PRs 2–4; PR 7 is the cross-surface sweep
  + `agents/sceneview*/` skill refresh via `check-sceneview-skill.sh`).
- **NEW** `changelog.d/2241-ar-v2-sprint1.md` (`<!-- category: Added -->`) + a `2210-` fragment.
- Diff: ~+90.

### Shader/material build-step summary (the ABI invariant, explicit)

| PR | `.mat` touched? | `matc` run needed? | ABI invariant engaged? |
|---|---|---|---|
| 1, 2, 4, 5, 6, 7 | No | No | **No** |
| 3 | **No** (reuses `plane_renderer_shadow.mat`) | **No** | **No** |
| 3 *only if* a dedicated softer shadow `.mat` is added (NOT the default) | Yes (new, profile C) | **Yes** — `bash tools/GenerateFilamat.sh`, add to `MATS` inventory, commit `.mat`+`.filamat` in the SAME PR as the runtime (CONTRIBUTING.md:313-319) | **Yes** |

**Default path engages the ABI invariant in ZERO PRs.** This is the single biggest risk-reducer
versus the v4.1.0 split-blob incident (CONTRIBUTING.md:277).

---

<a name="5-test-qa"></a>
## 5. Test & QA plan

### 5.1 Headless JVM (`:arsceneview:testDebugUnitTest`, `:samples:android-demo:testDebugUnitTest`)
- **`PlaneDiscoveryPhaseTest`** — full truth table of the phase derivation: WAITING when
  `!cameraReady`; SILENT 0–3 s; HAND_HINT at 3 s; HELP_OFFERED at 8 s; LOST on `!isTracking`/forced
  failure (timers reset); FADING_OUT on first `anyPlaneTracked`; DONE latched.
- **`PlacementReticle` slerp test** — quaternion slerp by 0.75 converges monotonically; identity
  input is a no-op; a 90° normal flip is damped, not snapped. Pure math, reuses `sceneview-core`.
- **`ShadowReceiverPlaneNodeTest`** — material-instance is assigned from the shadow `.filamat`; the
  dispose path destroys the instance once (no double-free, mirroring PlaneRendererV2.kt:275). No JNI.
- **Existing suites stay green untouched:** `PlaneVisualizerV2Test`, `PlaneRendererV2Test`,
  `ARPermissionFlowTest`, `DemoRegistryIntegrityTest`, `DeepLinkRouterTest`.
- **NOT headless-testable (don't pretend):** real reticle pose, shadow rendering, actual ARCore
  hit results — instrumentation/emulator/device territory.

### 5.2 Emulator — virtualscene + non-AR shader isolation (Mac-friendly)
- **Shadow isolation on plain arm64 emulator (no ARCore):** render the reused
  `plane_renderer_shadow.filamat` on a static hand-built plane mesh in a **non-AR `SceneView`**
  with `renderQuality = RenderQuality.Performance` — the `PlaneGridPreviewDemo` pattern
  (CONTRIBUTING.md:327, `--es demo_id plane-grid-preview`). Confirms the shadow catcher darkens
  only under a cast shadow. This is the documented Mac path since ARCore can't run on Apple-Silicon
  emulators.
- **Maestro `ar.yaml`** — the existing `ar-placement` entry (deep-link, tap, drag, no-crash) stays
  green; the reticle swap and guide must not crash the flow.
- **Differential check** (`feedback_visual_qa_emulator_recipe`): `git stash` the PR-4 reticle swap
  and confirm identical reticle behaviour pre/post on the demo — the reticle is an *upgrade of* the
  proven block, not a rewrite that regresses it.

### 5.3 AR-replay (`ar-replay-qa.sh` / `ARDemoPlaybackSmokeTest`)
- `ar-placement` must keep grading **`replayed`** (#1576) — the session passes `playbackDataset`
  through (tap-to-place-unification.md:605); the Sprint-1 swap must not break the passthrough.
  A downgrade to `alive` = the reticle/guide extraction broke the QA replay hook.

### 5.4 Pixel 9 (physical — request an AR Record / Thomas pass; emulator-first rule)
Only what needs real tracking + real lighting (and the #2241 "never claim wow without a frame" gate):
1. **Onboarding walk** — fresh launch: black → silent → hand hint at 3 s → help at 8 s →
   plane found → 0.75 s fade-out. Frame-extract each beat.
2. **Reticle** — normal-snap on a slanted surface; slerp damps jitter vs today's hard-snap;
   first-paint sanity (no parked-at-origin disc, the #1891 residue).
3. **Shadow grounding** — place a model; confirm a real contact shadow on the floor under
   ENVIRONMENTAL_HDR (the lighting dependency in §1.2). If no shadow appears, it's a light-estimate
   issue, not a ShadowReceiverPlane bug — diagnose the estimated main light's `castShadows`.
4. **Both entries** — verify PR 5 lands the three components identically under the AR View tab and
   the ar-placement demo (the Option-A payoff).
- **iOS:** simulator launch-smoke (AR is launch-only on sim); GroundingShadow + reticle need a real
  ARKit device pass — request one, don't fake it.

---

<a name="6-open-questions"></a>
## 6. Open questions (max 3 — genuine product calls)

1. **ShadowReceiverPlane: reuse the 60 % plane-renderer shadow, or author a softer dedicated
   shadow?** The existing `plane_renderer_shadow.mat` is a fixed 60 %-alpha black
   (plane_renderer_shadow.mat:29). Reusing it = zero new `.filamat`, zero ABI risk (the design
   default). But a contact shadow under a small object may read heavier than a soft 30–40 % shadow.
   *Default: reuse; only author a new profile-C material if a device frame shows 60 % too dark.*
   Confirm before PR 3, since a new material changes the PR's ABI footprint.
2. **PlaneDiscoveryGuide vs the unified status pill — replace or layer?** §7 says the guide
   "replaces the SCANNING/TRACKING_LOST affordances inside `TapToPlaceStatusOverlays`". Does the
   timed hand-hint guide fully *replace* the "Scanning…" pill (one voice), or do both show (pill
   for state, guide for onboarding)? *Default: guide replaces during onboarding, pill returns after
   the guide latches DONE — but confirm you don't want the pill suppressed entirely once a plane is
   found.*
3. **iOS Layer B scope — mesh-anchored shadows (L1) this sprint, or defer to keep iOS a strict
   subset?** L1 (shadows on reconstructed `ARMeshAnchor` geometry) is the strongest answer to
   #2210's "depth mesh" ask and uses already-enabled mesh reconstruction. But it's a RealityKit-only
   capability Android's plane-only Sprint-1 lacks — shipping it makes iOS *exceed* Android here,
   which cuts against `feedback_ios_mirror_android`. *Default: ship L1 (it's the #2210 headline
   ask), document it honestly as an iOS-only Layer-B extra.* Confirm this is acceptable.

---

<a name="7-appendix"></a>
## 7. Appendix — what already exists (do NOT rebuild)

Grounded inventory so the implementer doesn't duplicate shipped code:

| Thing | Where | Reuse in |
|---|---|---|
| Shadow-receiver material (`shadowMultiplier`, transparent, 60 % black, +0.005 m y-shift, profile C) | `arsceneview/src/main/materials/plane_renderer_shadow.mat` + committed `.filamat`; consumed at PlaneRenderer.kt:85-86, PlaneRendererV2.kt:141-142 | ShadowReceiverPlane (§1.2) — **reused, no recompile** |
| Reticle node primitive (HitResultNode subclass + `onHitResultChanged`, #1891 plane-only defaults, 0.3 m floor) | `arsceneview/.../node/ReticleNode.kt`; composable at ARSceneScope.kt:406-452 | PlacementReticle (§1.3) — **subclassed** |
| Custom-`hitTest` HitResultNode (change-only state write pattern) | ARSceneScope.kt:325-335; live demo use ARPlacementDemo.kt:543-565 | PlacementReticle policy plumbing |
| PlaneNode composable (plane-bound mesh, visibleTrackingStates, subsume-aware) | ARSceneScope.kt:833-856 | ShadowReceiverPlane base |
| `createUnlitColorInstance(Color)` | MaterialLoader.kt:309 | reticle disc material |
| `tracking_failure_*` user-tested copy (5 reasons) + `trackingFailureMessage(...)` | strings.xml:288-292; common/TrackingFailureMessages.kt; used ARPlacementDemo.kt:717 | PlaneDiscoveryGuide lost copy |
| `anyPlaneTracked` per-frame scan | unification §2.1 / tap-to-place-unification.md:357 (moved into the session) | PlaneDiscoveryGuide input |
| Texture-settle + PAUSED-surviving anchors | demos/internal/ArPlacement.kt:55-56 (`ANCHORED_VISIBLE_STATES`), :46 (`TEXTURE_SETTLE_MS`) | session (unchanged) |
| Camera-init scrim (#2484/#2502 — **landed**) | `ARCameraInitScrim`, demos/ARPlacementDemo.kt:625 | session (unchanged) |
| iOS coaching overlay | ARSceneView.swift:268-273, goal map :500-506 | iOS PlaneDiscoveryGuide parity — **free** |
| iOS translucent plane visualizer (12 % white fill, no collision) | ARSceneView.swift:766-840 | iOS plane overlay — **free** |
| iOS tap-to-place raycast (`.estimatedPlane`) | ARSceneView.swift:638-653 | iOS reticle (continuous variant) |
| iOS per-frame hook | `onFrame` → `session(_:didUpdate:)` ARSceneView.swift:657-660 | iOS continuous-raycast reticle driver |
| iOS mesh reconstruction (`.mesh`, LiDAR) already enabled | ARSceneView.swift:732-734 | iOS Layer B mesh-anchored shadows |

**Build/ABI references:** material build via `tools/GenerateFilamat.sh` (CONTRIBUTING.md:290),
drift gate in `quality-gate.sh` (CONTRIBUTING.md:301), profile C =
`--optimize-size -p mobile -a opengl -a vulkan` for `arsceneview/` (CONTRIBUTING.md:309), the
runtime↔blob ABI invariant (CONTRIBUTING.md:273-321). **The default Sprint-1 path adds no new
`.filamat`, so none of this is engaged.**
