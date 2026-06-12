# Unified `TapToPlaceArSession` — implementation-ready design

> **Issue:** #2518 (sub-issue of #2517) · **Decision basis:** the Option-A recommendation on
> #2482 · **Device evidence:** #2466 (Pixel 9 walkthrough, 2026-06-07 + high-fps re-pass
> 2026-06-10) · **Source snapshot:** all `file:line` citations below resolve against `main`
> @ `be6e6e4b9` ("docs(privacy): add Face Data section", 2026-06-12).
>
> **Scope:** design only — no code in this PR. Written so an Opus-class agent can implement
> it without re-deriving any decision.
>
> **Input caveats (kept honest):**
> - PR **#2502** (AR camera-init scrim, #2484) was **still OPEN** at this snapshot — the
>   scrim has *not* landed in `ARPlacementDemo` on `main` yet. This design absorbs the scrim
>   into the shared session either way; §3 describes both merge orders.
> - The `.claude/plans/v2-*.md` Sprint-1 research files are gitignored and not in the repo;
>   the Sprint-1 direction used here is taken from issue **#2241**'s body (which summarises
>   them). §7 spells out the compatibility contract with #2241 / the sibling design #2521.

---

## Table of contents

0. [TL;DR — what gets built](#0-tldr)
1. [The composable API](#1-the-composable-api)
2. [The state machine](#2-the-state-machine)
3. [File-by-file change plan](#3-file-by-file-change-plan)
4. [Feature reconciliation table](#4-feature-reconciliation-table)
5. [Test & QA plan](#5-test--qa-plan)
6. [Open questions for the maintainer](#6-open-questions)
7. [Sprint-1 (#2241) compatibility contract](#7-sprint-1-compatibility)
8. [Appendix — rejected alternatives](#8-appendix-rejected-alternatives)

---

<a name="0-tldr"></a>
## 0. TL;DR — what gets built

One shared, **demo-app-level** (not library-level) composable:

```
samples/android-demo/src/main/java/io/github/sceneview/demo/common/placement/
├── TapToPlaceArSession.kt   ← the session composable + default overlays
└── TapToPlaceState.kt       ← hoisted state holder, PlacementSpec, UX-state derivation
```

consumed by **two entry points with differentiated roles** (Option A on #2482):

| Entry | Role | Keeps |
|---|---|---|
| `ui/ArViewTab.kt` "Start AR Camera" | quick **consumer** entry | ARCore availability gate, permission recovery, immersive fullscreen (#2238), launcher, sheet-grid picker (#2498) |
| `demos/ARPlacementDemo.kt` (`ar-placement`) | **feature demo** with dev toggles | `DemoScaffold` chrome, streamed/bundled chip pickers, snap-to-plane & show-reticle toggles, force-failure menu (#1881) |

The session core that both render is **ARPlacementDemo's engine**, verbatim in behaviour:
centre reticle (#1882), texture-settle gating (#1435), per-asset rotation correction
(#1477), PAUSED-surviving anchors (#1435), gesture-mode feedback, tap-time model
resolution (#2476-proof by construction), QA playback passthrough (#1576) — plus the
camera-init scrim (#2484/#2502) and the AR-View plane-gated status vocabulary (#2234).

Chrome standardisation: **back arrow everywhere** (the X close at `ui/ArViewTab.kt:456-474`
is replaced — Thomas's own review note on #2482), **one status pill vocabulary** (the #2234
state machine + "N placed · tap to add"), and the `Bundled fallback` jargon chip reworded.

---

<a name="1-the-composable-api"></a>
## 1. The composable API

### 1.1 Package & visibility

`io.github.sceneview.demo.common.placement` — sibling of the existing shared demo
components (`common/ForcedTrackingFailure.kt`, `common/TrackingFailureMessages.kt`,
`common/SceneActionBar.kt`). Everything is `internal` to the demo app **except** nothing:
this is *not* library API. The library-level placement APIs (`PlaneDiscoveryGuide`,
`PlacementReticle`) belong to Sprint 1 (#2241) and land in `arsceneview/`; this composable
is the demo-app integration shell that will consume them (§7).

### 1.2 Types (compilable shape, real types from the repo)

```kotlin
package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.demo.rememberArPlaybackDataset
import java.io.File

/**
 * What one accepted tap places. Produced by the host's [onPlaceModel] lambda
 * at tap time (never captured at composition — the #2476 stale-closure class
 * is impossible by construction).
 */
@Immutable
data class PlacementSpec(
    /**
     * `file://…` URI for a streamed asset OR `assets/`-relative path for a bundled
     * GLB. Loaded via `rememberModelInstance(modelLoader, fileLocation = …)` — the
     * named-param overload that scheme-detects both forms (#1422/#2302 overload trap,
     * see the KDoc currently at demos/ARPlacementDemo.kt:113-117).
     */
    val assetLocation: String,
    /** User-facing name — drives "Tap a surface to place {name}". */
    val displayName: String,
    /** Passed to `ModelNode(scaleToUnits = …)`. Both surfaces use 0.3f today. */
    val scaleToUnits: Float = 0.3f,
    /**
     * Optional per-asset rotation override. `null` ⇒ the session applies
     * [io.github.sceneview.demo.demos.internal.DemoMath.placementRotationFor]
     * (#1477 — the helmet's −90° X correction; identity for everything else).
     */
    val rotationOverride: Rotation? = null,
)

/** A committed placement (internal render-list entry; moves out of ARPlacementDemo). */
internal data class PlacedModel(
    val id: Int,
    val anchor: Anchor,
    val spec: PlacementSpec,
)

/** Which edit gesture is live on a placed model (typed; was a raw String at demos/ARPlacementDemo.kt:215). */
enum class PlacementGesture { MOVING, ROTATING, SCALING }

/**
 * The camera/plane/reticle axis of the UX state machine (§2). `placedCount` and
 * `activeGesture` are orthogonal observables layered on top — see [TapToPlaceState].
 */
enum class TapToPlaceUxState {
    /** No camera frame delivered yet — viewport is jet black (#2484). */
    INITIALIZING,
    /** Camera not TRACKING, or a forced failure override is set (#1881). */
    TRACKING_LOST,
    /** Camera TRACKING but no Plane has reached TRACKING yet (#2234 gate). */
    SCANNING,
    /** ≥1 plane tracked, but the centre-screen hit test is empty (#1882 prompt). */
    AIMING,
    /** Centre reticle is locked on a real surface — a tap will place. */
    READY,
}

/**
 * Hoisted state holder — created by the host via [rememberTapToPlaceState], written
 * by the session, read by the host (status pills, reset buttons, "Next tap places").
 */
@Stable
class TapToPlaceState internal constructor() {
    /** True once the first `onSessionUpdated` frame arrived (drives the init scrim). */
    var cameraReady: Boolean by mutableStateOf(false)
        internal set
    var isTracking: Boolean by mutableStateOf(false)
        internal set
    var trackingFailureReason: TrackingFailureReason? by mutableStateOf(null)
        internal set
    /** #2234 — at least one ARCore Plane is in TrackingState.TRACKING. */
    var anyPlaneTracked: Boolean by mutableStateOf(false)
        internal set
    /** Latest accepted centre-screen hit, `null` when nothing is under the reticle. */
    var reticleHit: HitResult? by mutableStateOf(null)
        internal set
    var activeGesture: PlacementGesture? by mutableStateOf(null)
        internal set

    internal val placedModels = mutableStateListOf<PlacedModel>()
    internal var nextId: Int = 0
    val placedCount: Int get() = placedModels.size

    /** Derived — pure function of the fields above; see §2.3 for the exact table. */
    val uxState: TapToPlaceUxState
        get() = deriveUxState(
            cameraReady = cameraReady,
            isTracking = isTracking,
            forcedFailure = ForcedTrackingFailure.override != null,
            anyPlaneTracked = anyPlaneTracked,
            hasReticleTarget = reticleHit != null,
        )

    /**
     * Detach every ARCore anchor and clear the placed list. The shared semantics of
     * the demo's "Clear All" (demos/ARPlacementDemo.kt:370-381) and the AR-View exit
     * path (ui/ArViewTab.kt:351-355). `Anchor.detach()` is idempotent and
     * `AnchorNode.destroy()` also detaches (arsceneview/...AnchorNode.kt:146-147),
     * so calling this before disposal is belt-and-braces, not load-bearing.
     */
    fun clearAll() {
        placedModels.forEach { runCatching { it.anchor.detach() } }
        placedModels.clear()
    }
}

@Composable
fun rememberTapToPlaceState(): TapToPlaceState = remember { TapToPlaceState() }
```

### 1.3 The session composable

```kotlin
/**
 * The single canonical tap-to-place AR session (#2482 Option A). Renders a
 * full-bleed ARSceneView with: plane visualisation, centre placement reticle
 * (#1882), tap-to-place with the shared hit policy, per-placement AnchorNode +
 * ModelNode with texture-settle gating (#1435), PAUSED-surviving anchors (#1435),
 * per-asset rotation correction (#1477), editable placed models with a live
 * gesture pill, QA playback passthrough (#1576) and the default status overlays
 * (camera-init scrim #2484 + the #2234 plane-gated status pill vocabulary).
 *
 * Hosts: ui/ArViewTab.kt (consumer entry) and demos/ARPlacementDemo.kt (feature demo).
 */
@Composable
fun TapToPlaceArSession(
    /**
     * Display name of what the NEXT tap will place, or null when nothing is armed.
     * Read by the default status pill ("Tap a surface to place {label}") and by
     * hosts for their own chrome ("Next tap places: …").
     */
    nextModelLabel: String?,
    /**
     * Resolves the model for an accepted tap. CONTRACT: invoked exactly once per
     * accepted placement, on the main thread, INSIDE the tap handler — never
     * captured at composition (this is the #2476 fix as an API invariant; see the
     * in-body read at ui/ArViewTab.kt:422 and demos/ARPlacementDemo.kt:464-479).
     * The host may advance internal cycle state here. Return null to reject the
     * tap (e.g. asset still resolving and no fallback armed) — no anchor is created.
     */
    onPlaceModel: () -> PlacementSpec?,
    modifier: Modifier = Modifier,
    state: TapToPlaceState = rememberTapToPlaceState(),
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    /**
     * ON (default) ⇒ only detected-plane hits inside the polygon place (the v4.3.1
     * behaviour, demos/ARPlacementDemo.kt:444-445). OFF ⇒ any tracked hit, with
     * plane hits still polygon-gated (demos/ARPlacementDemo.kt:446-453, #1883).
     * The AR View tab does not expose a toggle and uses the default.
     */
    snapToPlane: Boolean = true,
    /** Hide the reticle disc without losing the hit-test pipeline (#1882/#1883 dev toggle). */
    showReticle: Boolean = true,
    /**
     * ARCore MP4 replay for the device-QA harness (#1576). Defaults to the pending
     * deep-link dataset (null on every real-user launch — DemoHelpers.kt:236).
     */
    playbackDataset: File? = rememberArPlaybackDataset(),
    /**
     * Extra session config. Default null — the ARSceneView defaults already are
     * HORIZONTAL_AND_VERTICAL plane finding (arsceneview/...ARSceneView.kt:416) and
     * ENVIRONMENTAL_HDR light estimation (arsceneview/...ARSceneView.kt:936), i.e.
     * exactly what ui/ArViewTab.kt:377-380 sets redundantly today. One config path
     * for both surfaces is the precondition for closing #2483 in one place.
     */
    sessionConfiguration: ((Session, Config) -> Unit)? = null,
    /** Fired after a placement is committed (haptics, analytics, snackbars). */
    onModelPlaced: ((PlacementSpec) -> Unit)? = null,
    /**
     * Status overlays drawn inside the session's Box, above the viewport. The
     * default renders the unified vocabulary (§2.4). Hosts that need to ADD chrome
     * (back button, bottom bars) layer siblings in their own Box — they replace
     * this slot only to change the status language itself.
     */
    overlays: @Composable BoxScope.(TapToPlaceState) -> Unit = { s ->
        TapToPlaceStatusOverlays(state = s, nextModelLabel = nextModelLabel)
    },
    /**
     * Extra AR-scope scene content rendered inside the same ARSceneView (escape
     * hatch for future demo flourishes — e.g. Sprint-1 ShadowReceiverPlane, §7).
     */
    extraSceneContent: (@Composable ARSceneScope.() -> Unit)? = null,
)
```

```kotlin
/**
 * The unified status vocabulary (§2.4) as a reusable overlay block:
 * camera-init scrim, top status pill (#2234 wording + TouchApp/CheckCircle icon),
 * "Aim at a surface…" bottom hint (#1882), and the active-gesture pill.
 * Reads ForcedTrackingFailure.override so the #1881 QA shim drives both surfaces.
 */
@Composable
fun BoxScope.TapToPlaceStatusOverlays(
    state: TapToPlaceState,
    nextModelLabel: String?,
)
```

### 1.4 The shared hit policy (extracted for testability and anti-drift)

Today the tap filter (demos/ARPlacementDemo.kt:439-454) and the reticle filter
(demos/ARPlacementDemo.kt:538-551) are **duplicated by hand** and only stay in sync by
review discipline. They become one pure function — primitives only, fully JVM-testable:

```kotlin
// TapToPlaceState.kt (same package)
internal object PlacementHitPolicy {
    const val MAX_HIT_DISTANCE_M = 5.0f   // demos/ARPlacementDemo.kt:443

    /**
     * Single source of truth for "does this hit accept a placement / reticle lock".
     * Mirrors demos/ARPlacementDemo.kt:439-454 exactly:
     *  - trackable must be TRACKING
     *  - distance ≤ [MAX_HIT_DISTANCE_M]
     *  - snapToPlane ON  ⇒ plane && poseInPolygon
     *  - snapToPlane OFF ⇒ plane hits still polygon-gated; non-plane hits accepted
     */
    fun accept(
        isPlane: Boolean,
        isPoseInPolygon: Boolean,
        isTrackableTracking: Boolean,
        distanceMeters: Float,
        snapToPlane: Boolean,
    ): Boolean
}
```

Both the `onSingleTapConfirmed` handler and the `HitResultNode(hitTest = …)` lambda call
`PlacementHitPolicy.accept(…)` with values unpacked from the ARCore `HitResult`.

### 1.5 State-hoisting summary — who owns what

| State | Owner | Why |
|---|---|---|
| Model selection (picker index / slug / cycle index / bundled lock) | **Host** | The two pickers are deliberately different products (sheet grid vs dev chips). The session only sees `nextModelLabel` + `onPlaceModel()`. |
| `snapToPlane`, `showReticle` | **Host** (demo: toggles at demos/ARPlacementDemo.kt:170-171; AR View: fixed defaults) | Dev toggles are demo-specific surface. |
| Placed models, ids, anchors | **Session** (via `TapToPlaceState`) | The anchor lifecycle is the engine's invariant; hosts get `placedCount` + `clearAll()`. |
| Camera/plane/reticle/gesture signals | **Session** (writes) → host (reads) | Mirrors `ScrollableState`-style holder hoisting; hosts build chrome from it. |
| Session recreation (`key(arSceneId)`) | **Host** (AR View Reset, ui/ArViewTab.kt:336,580-597) | Full ARCore-state discard is an entry-point product choice; `rememberTapToPlaceState()` sits inside the `key` block so it resets together. |
| Immersive mode, permission, availability, launcher | **Host** (AR View only, ui/ArViewTab.kt:171-310) | The demo host (`DemoHostActivity`/`MainActivity` routing + `DemoScaffold`) has its own entry plumbing. |
| Playback dataset | **Session default** (`rememberArPlaybackDataset()`) | #1576 QA reach extends to any host for free; null for real users. |

---

<a name="2-the-state-machine"></a>
## 2. The state machine

### 2.1 Signals (all already produced today)

| Signal | Source (real ARCore wiring) |
|---|---|
| `cameraReady` | first `onSessionUpdated(session, frame)` callback (PR #2502 pattern; `ARSceneView` param at arsceneview/...ARSceneView.kt) |
| `isTracking` | `frame.camera.trackingState == TrackingState.TRACKING` (demos/ARPlacementDemo.kt:418, ui/ArViewTab.kt:383) |
| `trackingFailureReason` | `onTrackingFailureChanged` (demos/ARPlacementDemo.kt:420-422, ui/ArViewTab.kt:390-392) |
| `forcedFailure` | `ForcedTrackingFailure.override != null` (#1881, common/ForcedTrackingFailure.kt:58-68) |
| `anyPlaneTracked` | `session.getAllTrackables(Plane::class.java).any { it.trackingState == TRACKING }` per frame (#2234, ui/ArViewTab.kt:387-389) |
| `hasReticleTarget` | the `HitResultNode(hitTest = …)` lambda's accepted candidate, written change-only to avoid 60 Hz snapshot churn (demos/ARPlacementDemo.kt:538-558) |
| `placedCount` / `activeGesture` | session placement list / gesture callbacks (demos/ARPlacementDemo.kt:495-506) |

### 2.2 Diagram

```
            first frame                plane TRACKING            centre hit accepted
INITIALIZING ────────────▶ SCANNING ────────────────▶ AIMING ────────────────────▶ READY
     ▲                      ▲    │                      ▲  │                         │
     │ (only via session    │    │ all planes lost      │  │ centre hit lost         │ tap →
     │  recreation)         │    ▼                      │  ▼                         │ PLACED++
     │                      └── (no transition back     └──┘                         │
     │                           to INITIALIZING)                                    ▼
     │                                                              [placedCount > 0 layers
     └──────────────────────────────────────────────────────────────  onto AIMING/READY]
TRACKING_LOST  ◀── camera != TRACKING or forcedFailure ── (from SCANNING/AIMING/READY)
TRACKING_LOST  ──▶ camera TRACKING again ──▶ SCANNING / AIMING / READY (re-derived)
```

`placedCount` and `activeGesture` are **orthogonal layers**, not states: placed models
stay visible through `TRACKING_LOST` at their frozen poses (PAUSED-surviving anchors,
demos/internal/ArPlacement.kt:55-56) — collapsing them into the enum would force the
pill to lie about one axis or the other.

### 2.3 Derivation (pure function — the headless-testable core)

```kotlin
internal fun deriveUxState(
    cameraReady: Boolean,
    isTracking: Boolean,
    forcedFailure: Boolean,
    anyPlaneTracked: Boolean,
    hasReticleTarget: Boolean,
): TapToPlaceUxState = when {
    !cameraReady                 -> TapToPlaceUxState.INITIALIZING
    !isTracking || forcedFailure -> TapToPlaceUxState.TRACKING_LOST
    !anyPlaneTracked             -> TapToPlaceUxState.SCANNING
    !hasReticleTarget            -> TapToPlaceUxState.AIMING
    else                         -> TapToPlaceUxState.READY
}
```

Priority order is significant and pinned by unit test (§5.1): failure beats plane state
beats reticle state; `INITIALIZING` wins over everything (the scrim covers all pills).

### 2.4 Affordance per state (the unified vocabulary)

Single top pill (AR-View visual style: translucent capsule + icon,
ui/ArViewTab.kt:477-533) + at most one bottom hint. The demo's separate bottom
scanning banner (demos/ARPlacementDemo.kt:695-714) is **merged into the pill** — one
voice, not two.

| State | Scrim | Top pill (icon · text) | Bottom hint | Reticle disc |
|---|---|---|---|---|
| INITIALIZING | **`ARCameraInitScrim`** (#2484, DemoHelpers.kt:165) | hidden (scrim covers) | — | hidden |
| TRACKING_LOST | — | ⚠ `trackingFailureMessage(reason)` (#1881 strings, common/TrackingFailureMessages.kt:69) `?:` "Scanning for surfaces…" | — | hidden |
| SCANNING | — | TouchApp · "Scanning for surfaces…" (`ar_status_scanning`, strings.xml:104 — the #2234 gate: never claim "tap a surface" before a plane exists) | — | hidden |
| AIMING | — | TouchApp · placedCount==0 ? "Tap a surface to place {label}" (`ar_status_tap_to_place`, :105) : "N placed · tap to add" (:106-107) | "Aim at a surface…" (#1882, shown only when `showReticle`) | hidden |
| READY | — | same as AIMING | — | **visible** at hit pose |
| + activeGesture (any state) | — | unchanged, plus a second "Moving/Rotating/Scaling" pill below it (demos/ARPlacementDemo.kt:641-661 style) | — | unchanged |

Entry/exit side effects that are **host** affordances, not pill states: immersive
enter/exit (#2238, ui/ArViewTab.kt:205-222) and the back arrow (§3).

---

<a name="3-file-by-file-change-plan"></a>
## 3. File-by-file change plan

Implementation lands as **one PR** (estimated M, ~+800/−540 lines incl. tests/KDoc), with
the triptych review gate. If PR #2502 merges first (expected), its `cameraReady` +
`ARCameraInitScrim` wiring inside `ARPlacementDemo.kt` is simply *moved* into the session
during extraction; if this PR lands first, #2502's `ARPlacementDemo.kt` hunk becomes
obsolete and should be dropped from #2502 at rebase (the other 10 demos are unaffected).

### 3.1 NEW — `common/placement/TapToPlaceState.kt` (~+150)

`PlacementSpec`, `PlacedModel`, `PlacementGesture`, `TapToPlaceUxState`,
`TapToPlaceState`, `rememberTapToPlaceState`, `deriveUxState`, `PlacementHitPolicy`.
No Compose-UI dependencies beyond the runtime — keeps the derivation JVM-testable.

### 3.2 NEW — `common/placement/TapToPlaceArSession.kt` (~+440 incl. KDoc)

Code that **moves verbatim in behaviour** from `demos/ARPlacementDemo.kt`:

| What | From (current lines) |
|---|---|
| Viewport capture for the reticle (`onSizeChanged`) | :178, :401-404 |
| `ARSceneView(...)` call shape: playback (:411), planeRenderer (:412), `onSessionUpdated` (:416-419), `onTrackingFailureChanged` (:420-422) | :406-422 |
| Tap handler skeleton (node-tap passthrough, TRACKING gate, hit test + policy, anchor creation) | :424-489 |
| Gesture-mode callbacks → `state.activeGesture` | :495-507 |
| Reticle: once-allocated unlit cyan material (:529-533), `HitResultNode(hitTest = …)` + change-only `reticleHit` write + `CylinderNode` disc | :509-573 |
| Placement render loop: `key(id)` → `AnchorNode(visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES)` → `rememberModelInstance(fileLocation = …)` → `rememberTexturesSettled` gate → `ModelNode(scaleToUnits, centerOrigin, rotation = placementRotationFor, isVisible, isEditable = true)` | :575-615 |

Code that moves in **adapted** form:

- The asset-resolution `when` (:464-479) is replaced by `onPlaceModel()` (host-provided).
- The count pill (:620-635), gesture pill (:641-661), "Aim at a surface…" (:668-688) and
  scanning/failure banner (:690-714) are consolidated into `TapToPlaceStatusOverlays`
  per §2.4 (pill style from ui/ArViewTab.kt:477-533, wording per the table).
- `ARCameraInitScrim(initializing = !state.cameraReady)` added per the #2502 pattern.
- The `anyPlaneTracked` per-frame scan is added to `onSessionUpdated`
  (from ui/ArViewTab.kt:387-389, #2234).

### 3.3 MODIFIED — `demos/ARPlacementDemo.kt` (718 → ~390 lines; −400/+70)

**Keeps (demo-specific):** `DemoScaffold` + title + `assetSource` chip (:220-230); the
whole `controls = { … }` sheet — streamed-slug chips (:245-276), bundled-lock chips
(:284-313), snap-to-plane + show-reticle toggles (:315-363), Clear All button
(:365-381, now calling `state.clearAll()`), "Next tap places:" label (:383-393),
`ForceTrackingFailureMenu()` (:398, #1881); the Sketchfab prefetch + `produceState`
resolve (:188-203); `MODEL_CYCLE` (:130-136).

**Replaces:** the entire scene `Box` (:401-715) with:

```kotlin
val state = rememberTapToPlaceState()
TapToPlaceArSession(
    nextModelLabel = nextLabel,                       // same derivation as :384-388
    onPlaceModel = {
        // 3-tier resolution moves here UNCHANGED (was :464-479):
        // 1. resolved streamed slug → file:// URI   2. bundled lock   3. auto-cycle++
        …
    },
    state = state,
    engine = engine, modelLoader = modelLoader, materialLoader = materialLoader,
    snapToPlane = snapToPlane,
    showReticle = showReticle,
)
```

**Loses (absorbed by shared defaults):** its private `PlacedModel` (:110-119), the
duplicated hit filters, all four overlay blocks, the bottom scanning banner (its text
now appears in the unified pill — same strings).

### 3.4 MODIFIED — `ui/ArViewTab.kt` (1216 → ~1060 lines; −230/+75)

**Keeps:** launcher + availability gate (:223-310, :741-1009), permission flow
(:171-187, :285-310), `sessionStarted` save/restore (:195), immersive wiring (#2238,
:205-222), feedback-chip suppression (:280-283), `BackHandler` (:364-366), bottom action
bar + `ExtendedFloatingActionButton` picker (:536-597), `ModalBottomSheet` +
`ModelPickerGrid` (:627-730), `AR_MODELS`/`ArModel` (:1193-1216), session-recreate Reset
(`arSceneId`, :336, :580-585).

**Replaces:** the inline `ARSceneView` block (:368-451) and the hand-rolled status pill
(:477-533) with:

```kotlin
key(arSceneId) {
    val state = rememberTapToPlaceState()
    TapToPlaceArSession(
        nextModelLabel = arModels[selectedModelIndex].name,
        onPlaceModel = {
            val m = arModels[selectedModelIndex]      // read at tap time — #2476 invariant
            PlacementSpec(m.assetPath, m.name, m.scale)
        },
        state = state,
        engine = engine, modelLoader = modelLoader, materialLoader = materialLoader,
    )
}
```

`exitArSession` (:351-355) becomes `state.clearAll(); sessionStarted = false`.

**This auto-inherits** into the consumer path: reticle (#1882), texture-settle (#1435),
rotation correction (#1477 — note ui/ArViewTab.kt:435-449 today has **none** of these:
default `visibleTrackingStates`, no settle gate, no rotation), PAUSED anchors, gesture
pill, camera-init scrim, playback support — and removes the second lighting/config code
path (#2483's "natural landing spot", since :377-380 was redundant with the library
defaults — verified at arsceneview/...ARSceneView.kt:416 and :936).

**Loses:** the **X close** button (:456-474) → replaced by a **top-START back arrow**
(`Icons.AutoMirrored.Filled.ArrowBack`, same `FilledIconButton` styling, same
`exitArSession` action, `cd_back_button` content description) — the app-wide back
affordance per Thomas's #2482 note; the screenshot toast stub (:599-622) → **dropped**
pending Q1 (§6); `friendly()` (:1180-1189) → superseded by the shared overlay's
`trackingFailureMessage` (the `ar_tracking_*` strings stay until the localisation sweep
confirms `tracking_failure_*` parity — implementer must diff the two string sets and
keep the better wording under the `tracking_failure_*` keys).

### 3.5 MODIFIED — `res/values/strings.xml` (±8 lines)

- Reword `demo_chip_bundled` "Bundled fallback" (:243) → plain language, e.g.
  **"Offline model"** (#2482 plan item 4; chip is global — all `AssetSourceChip` demos
  inherit the wording, which is an improvement everywhere, not a regression).
- Localise the gesture pill: add `ar_gesture_moving` / `ar_gesture_rotating` /
  `ar_gesture_scaling` (today hard-coded English at demos/ARPlacementDemo.kt:496-506).
- Remove `ar_screenshot_toast` / `ar_share_screenshot` (:100-101) **iff Q1 = drop**.
- Mirror every change into the existing `values-*` locale files (check which locales
  exist before assuming — `grep -rl ar_status_scanning samples/android-demo/src/main/res`).

### 3.6 NEW — tests (~+220; see §5.1)

`src/test/java/io/github/sceneview/demo/common/placement/TapToPlaceUxStateTest.kt` and
`PlacementHitPolicyTest.kt`.

### 3.7 NEW — `.maestro/android/flows/ar-view-live.yaml` (~+40) + `ar.yaml` entry

Today **no Maestro flow drives the AR View tab's live session** (only deep-linked demos
via `flows/demo.yaml` — the `ar-placement` entry at .maestro/android/ar.yaml:17-21 stays
unchanged). Add a flow: launch app → AR View tab → "Start AR Camera" → assert the status
pill text appears → back-arrow exit → assert launcher restored. This closes the QA gap
that let #2476 ship.

### 3.8 NEW — `changelog.d/2518-tap-to-place-unification.md`

`<!-- category: Changed -->` — "One canonical tap-to-place engine under both the AR View
tab and the ar-placement demo (#2482): the AR View entry gains the reticle,
texture-settle, rotation correction and PAUSED-surviving anchors; back arrow replaces
the X close; one status vocabulary."

### Out of scope (explicitly)

- `demos/ARInstantPlacementDemo.kt` — third placement sibling; already shares
  `ArPlacement` (:389, :398). Converting it to `TapToPlaceArSession` with a custom hit
  policy is a follow-up issue, not this PR (instant-placement semantics need a policy
  extension first).
- Any `arsceneview/` library change — the library already has everything needed
  (`ReticleNode` at arsceneview/...ARSceneScope.kt:407 exists but the session keeps the
  demo's `HitResultNode(hitTest = …)` form because the unified policy needs the custom
  lambda + change-only state write; revisit when #2241 PR 4 lands).
- The #2483 tint fix itself — this PR creates the *single* place to fix it; the fix is
  its own investigation (§5.3).

---

<a name="4-feature-reconciliation-table"></a>
## 4. Feature reconciliation table

Every feature on either surface, with its introducing issue — nothing regresses silently.

| Feature | Origin | Today | Decision |
|---|---|---|---|
| Centre placement reticle (disc previews next tap) | #1882 | demo only (demos/ARPlacementDemo.kt:534-573) | **UNIFIED** — in session; AR View gains it (default ON; see Q2) |
| "Aim at a surface…" hint | #1882 | demo only (:668-688) | **UNIFIED** (AIMING state, shown when `showReticle`) |
| Texture-settle gating (no black flash) | #1435 | demo only (:595) | **UNIFIED** — internal, always on |
| PAUSED-surviving anchors | #1435 | demo only (:584-586) | **UNIFIED** — internal, always on |
| Per-asset rotation correction (helmet −90° X) | #1477 | demo only (:608) | **UNIFIED** — `rotationOverride ?: placementRotationFor(...)`; matters for AR View where the helmet is `AR_MODELS[0]` (ui/ArViewTab.kt:1210) and currently lands face-down |
| Tap-time model resolution (stale-closure-proof) | #2476/#2498 | both, by convention (ui/ArViewTab.kt:410-422) | **UNIFIED as API contract** — `onPlaceModel()` is *called* inside the tap handler by the session |
| Gesture-mode feedback pill (Moving/Rotating/Scaling) | Pixel-9 review v2 | demo only (:495-506, :641-661) | **UNIFIED** — typed `PlacementGesture`, localised strings |
| Snap-to-plane toggle | #1883 | demo only (:315-343) | **KEPT demo** (session param; AR View uses default ON) |
| Show-reticle dev toggle | #1882/#1883 | demo only (:345-363) | **KEPT demo** (session param) |
| Streamed-slug chips + Sketchfab resolve/prefetch | #1152 | demo only (:153-203, :245-276) | **KEPT demo** (lives in `onPlaceModel` + controls sheet) |
| Bundled-lock chips + auto-cycle | #1883 | demo only (:159-165, :284-313) | **KEPT demo** (same) |
| "Next tap places:" preview | #1883 | demo only (:383-393) | **KEPT demo** (host derives from its own picker state; pill already names the model on both surfaces) |
| Clear All | #1883 | demo (:365-381) / AR-View exit+Reset (:351-355, :580-585) | **UNIFIED semantics** — `state.clearAll()`; demo button + AR View Reset/exit all call it |
| Force-tracking-failure QA menu | #1881 | demo controls (:394-398) | Menu **KEPT demo** (needs a controls sheet + qaMode); the *override read* is **UNIFIED** in the shared overlay so a forced reason renders on both surfaces |
| QA playback replay (deep-link `ar_playback_file`) | #1576 | demo only (:143-146, :411) | **UNIFIED** — session default `rememberArPlaybackDataset()`; AR View inherits (harness still needs the tab flow of §3.7 to exploit it) |
| AssetSource chip (Streamed/Streaming/Bundled) | #1152 St.3 | demo only (:217-230) | **KEPT demo** (`DemoScaffold assetSource`); wording fix §3.5 |
| ARCore availability gate + launcher | v4.1.0 crash lesson | AR View only (ui/ArViewTab.kt:223-273) | **KEPT AR View** (demos route via their own host) |
| Permission request + denial recovery | — | AR View only (:171-187, :285-310) | **KEPT AR View** |
| Immersive fullscreen (hide nav + system bars) | #2238 | AR View only (:197-222; RootScreen.kt:95-129) | **KEPT AR View** (entry-point concern; demo keeps `DemoScaffold`'s top bar) |
| Plane-gated status vocabulary ("Scanning…" until a real plane) | #2234 | AR View only (:325-331, :387-389, :522-528) | **UNIFIED** — the SCANNING state + `anyPlaneTracked` scan move into the session |
| Status pill visual (capsule + icon) | #1185-era | AR View (:477-533) | **UNIFIED** as the default overlay style |
| Sheet-grid model picker | #2498-fixed | AR View (:627-730) | **KEPT AR View** (consumer picker) |
| Session-recreate Reset (`key(arSceneId)`) | — | AR View (:336, :580-585) | **KEPT AR View** (host-level; state holder recreated inside `key`) |
| `sessionStarted` process-death restore | — | AR View (:189-195) | **KEPT AR View** |
| Feedback-chip suppression in live session | #2194 | AR View (:280-283) | **KEPT AR View** |
| X close button (top-end) | — | AR View (:453-474) | **DROPPED** → back arrow top-start (#2482 review note; `cd_back_button` a11y string reused) |
| Screenshot/Share toast stub | — | AR View (:599-622) | **DROPPED** (pending Q1) — a Share affordance that only shows a toast is an anti-affordance flagged in #2466 ("share=toast") |
| Demo bottom scanning/failure banner | #1615/#1881 | demo (:690-714) | **DROPPED as separate surface** — same strings now render in the unified top pill (TRACKING_LOST/SCANNING states); no message is lost |
| Explicit `sessionConfiguration` (HORIZONTAL_AND_VERTICAL + ENVIRONMENTAL_HDR) | — | AR View (:377-380) | **DROPPED** — redundant with library defaults (arsceneview/...ARSceneView.kt:416, :936; #1766); one config path for #2483 |
| "X models placed" black count pill | demo (:618-635) | demo | **DROPPED wording** — superseded by "N placed · tap to add" (strings.xml:106-107) in the unified pill |

---

<a name="5-test--qa-plan"></a>
## 5. Test & QA plan

### 5.1 Headless JVM (runs in `ci.yml` unit-test job — `:samples:android-demo:testDebugUnitTest`)

1. **`TapToPlaceUxStateTest`** — full truth table of `deriveUxState` (2⁵ = 32 rows is
   cheap; at minimum the priority pins: `!cameraReady` beats forced failure;
   forced failure beats `anyPlaneTracked`; AIMING vs READY on `hasReticleTarget`).
2. **`PlacementHitPolicyTest`** — table test of `PlacementHitPolicy.accept`: plane in/out
   of polygon × snapToPlane on/off × tracking/not × distance 4.9/5.1 m. Pins the #1883
   free-placement semantics and the polygon gate that both the tap and reticle share.
3. **Rotation default pin** — `PlacementSpec(rotationOverride = null)` resolves the
   helmet asset to `Rotation(x = -90f)` and everything else to identity (extends the
   existing `DemoMath.placementRotationFor` coverage in
   `src/test/.../demos/internal/`, #1477 regression pin for the *new* call path).
4. **Existing suites must stay green untouched:** `DemoRegistryIntegrityTest`,
   `DeepLinkRouterTest` (the `ar-placement` id and fragment routing don't change —
   fragments/ArPlacementFragment.kt:14 keeps its `DemoEntry`).

Not headless-testable (don't pretend): `rememberTexturesSettled` timing, reticle pose,
actual anchor lifecycles — these are instrumentation/emulator territory.

### 5.2 Emulator (virtualscene planes — `setup-ar-emulator.sh`, then `device-qa.sh --platform=android|ar`)

1. **Maestro `ar.yaml`** — the existing `ar-placement` entry (deep-link, tap, drag,
   no-crash) must stay green unchanged.
2. **New `ar-view-live.yaml` flow (§3.7)** — launcher → Start AR Camera → pill visible →
   back-arrow exit → launcher restored. First-ever automated coverage of the consumer
   entry (the #2476 class had zero automation).
3. **AR replay harness (`ar-replay-qa.sh` / `ARDemoPlaybackSmokeTest`)** — `ar-placement`
   must keep grading **`replayed`** (#1576): the session passes `playbackDataset` through
   to `ARSceneView`, so recorded frames still advance. Any downgrade to `alive` = the
   extraction broke the passthrough.
4. **Emulator visual pass (the `feedback_visual_qa_emulator_recipe` ritual)** — on the
   virtualscene floor: reticle disc appears on the rug plane; tap places; model is
   textured on first visible frame (no black flash); pill walks
   Scanning → Tap-a-surface → 1 placed; Clear All empties; demo settings toggles still
   gate the reticle/policy. Differential check: `git stash` the change and confirm
   identical reticle behaviour pre/post on the demo (the engine must be a move, not a
   rewrite).

### 5.3 Pixel 9 (physical — request an AR Record / Thomas pass, per the emulator-first rule)

Only what genuinely needs real-world tracking & real lighting:

1. **#2483 verification** — place the helmet + 2 more models via the AR View entry;
   compare against the 3D viewer ground-truth render. The unification collapses the two
   lighting paths into one; this pass tells us whether #2483 survives (then it's an
   engine/lighting bug to fix once in the session) or was a divergence artifact.
2. **Reticle first-paint** (#1891 residue) — confirm the small-teal-disc-on-first-paint
   behaviour didn't worsen now that consumers see it.
3. **Real-plane UX walk** — the #2466 script: enter from both surfaces, place, edit
   (drag/twist/pinch → pill labels), look away/back (PAUSED anchors hold), exit via back
   arrow / system back, re-enter (process-death restore on AR View).
4. **Immersive enter/exit** — nav bar + status bar restore on every exit path (#2238).

---

<a name="6-open-questions"></a>
## 6. Open questions for the maintainer (max 3)

1. **Screenshot/Share button (AR View bottom bar):** the design drops the toast stub
   (ui/ArViewTab.kt:599-622) as an anti-affordance. OK to drop now and track real
   capture (Filament readback / `PixelCopy`) as its own issue — or must a working Share
   ship in this same change? *(Design default: drop + file follow-up.)*
2. **Reticle for consumers before Sprint-1 polish:** the canonical engine shows the
   cyan reticle disc in the AR View tab from day one. Given your #2466 note ("le réticule
   apparaît bizarrement") and that the polished `PlacementReticle` only lands with #2241
   PR 4 — ship the current reticle to consumers now (design default: yes, it's still
   strictly more guidance than the nothing they have today), or keep `showReticle=false`
   on the AR View entry until #2241 PR 4 replaces the visual?
3. **`ar-placement` on the AR View launcher:** after unification the launcher's featured
   grid still links to the `ar-placement` demo (ui/ArViewTab.kt:1098-1104) right under
   the "Start AR Camera" CTA — two taps into the *same engine* from one screen, differing
   only in chrome. Keep both (launcher = quick start, demo = dev toggles), or re-point
   the featured card at a different AR demo to avoid reading as a duplicate?

---

<a name="7-sprint-1-compatibility"></a>
## 7. Sprint-1 (#2241) compatibility contract

The sibling design (#2521) builds the definitive Sprint-1 plan (PlaneDiscoveryGuide +
ShadowReceiverPlane + PlacementReticle, library-level, per #2241). This design is
deliberately **shaped so Sprint-1 lands *inside* the session without touching either
host**:

| #2241 deliverable | Slot in this design |
|---|---|
| PR 2 — `PlaneDiscoveryGuide(arSession, …)` (hand animation 3 s, help 8 s, fade-out, contextual lost messages) | Replaces the SCANNING/TRACKING_LOST affordances **inside `TapToPlaceStatusOverlays`**. The `deriveUxState` signals (camera/plane/failure) are exactly its inputs. The pill text contract of §2.4 is the *fallback* vocabulary, not a competing system. |
| PR 4 — library `PlacementReticle(arSession, onPlacementTapped, …)` (depth-sampled, slerp-smoothed) | Replaces the internal `HitResultNode(hitTest=…)` + `CylinderNode` block in `TapToPlaceArSession.kt`. `PlacementHitPolicy` stays the acceptance policy; `state.reticleHit` keeps feeding AIMING/READY. No host change. |
| PR 3 — `ShadowReceiverPlane` | Added inside the session's scene content (or via `extraSceneContent` during experimentation). |
| PR 5 — "refonte ARPlacementDemo" | Becomes a refonte of the **session internals** — and therefore upgrades the consumer AR View entry in the same commit. This is the central payoff of Option A: #2241's investment lands once, in one place. |

Nothing in this design contradicts the Sprint-1 direction; it *narrows the blast radius*
of Sprint-1 to one file.

---

<a name="8-appendix-rejected-alternatives"></a>
## 8. Appendix — rejected alternatives

- **Option B (chrome-only alignment, keep two engines)** — rejected on #2482: the
  engines keep drifting; #2476/#2483 bred precisely in the weaker re-implementation
  (ui/ArViewTab.kt:435-449 still lacks settle/rotation/PAUSED handling today).
- **Library-level `TapToPlaceArSession` in `arsceneview/`** — premature. The
  status-pill vocabulary, Sketchfab resolution, and `DemoScaffold` interplay are
  demo-app product decisions. Promote *after* #2241 PRs 2-4 provide the library
  primitives and the demo-side shell has stabilised through a release.
- **`PlacementModelProvider` interface instead of two params** — more ceremony for two
  call sites; the `nextModelLabel` + `onPlaceModel()` pair keeps hosts lambda-simple
  and makes the #2476 contract a one-line KDoc invariant.
- **Folding `placedCount`/`activeGesture` into the UX enum** — forces the pill to lie
  on one axis during TRACKING_LOST-with-placed-models (frozen-pose anchors are visible
  while the camera is lost); orthogonal layers match the real signal structure.
- **Using library `ReticleNode` (ARSceneScope.kt:407) today** — its callback form
  doesn't carry the unified custom policy + change-only state write; the
  `HitResultNode(hitTest=…)` form does. Revisit at #2241 PR 4.
