---
title: Migration Guide
description: "Migration guides for SceneView: 3.6.x to 4.0.0 Rerun integration, 3.5.x to 3.6.0 API simplification, and 2.x to 3.x full rewrite."
---

# Migration Guide

---

## SceneView 4.37.x to 4.38.0 (Android) — `isRendering` replaced by `frameRatePolicy`

### `SceneView(isRendering:)` is removed; render-on-demand is the default ([#3108](https://github.com/sceneview/sceneview/issues/3108))

A `SceneView` no longer renders every vsync for as long as it is composed. The new
`frameRatePolicy` parameter defaults to `FrameRatePolicy.OnDemand()`: the library tracks what makes
the picture change, holds the display's full cadence while anything is happening, then draws a
short tail of settle frames and parks the loop.

`isRendering: Boolean` is **removed outright — there is no deprecated overload.** Keeping one
would have meant two parameters that can disagree about the same thing, and the boolean's whole
contract ("you work out when the scene is dirty") is precisely what the new default replaces.

```kotlin
// Before — you computed "is anything dirty" yourself and fed it in
var isDirty by remember { mutableStateOf(true) }
LaunchedEffect(dirtyToken) {
    isDirty = true
    delay(200)
    isDirty = false
}
SceneView(isRendering = isAnimating || isInteracting || isDirty) { /* … */ }

// After — that is the library's job now
SceneView { /* … */ }
```

**Key differences:**

- `isRendering = true` (or omitting the parameter) becomes
  `frameRatePolicy = FrameRatePolicy.Continuous()` if you genuinely want a frame every vsync. If you
  never passed the parameter at all, the honest migration is usually to change **nothing** and take
  the new default.
- The `isDirty` state, the `dirtyToken`, the `LaunchedEffect { delay(200) }` window — **delete
  them, do not translate them.** Every source they were standing in for (touch, camera coast,
  animation, smooth transform, video, `ViewNode`, splat sort, async load, mirrorer, auto-fit, node
  added/moved/removed, visibility, geometry, material swap, surface resize, lifecycle resume) is
  now tracked by the library.
- **Never invalidate from a recomposition, and never write Compose state from `onFrame`.** The
  library asks for a frame from the thing that changed, never from the fact that a recomposition
  happened — and your screen should do the same. A counter that writes snapshot state on every
  presented frame recomposes its host on every presented frame; if any of that feeds back into the
  scene, the screen is measuring its own loop rather than the scene's. `DebugStats` holds plain
  fields and `DebugOverlay` reads them on a 250 ms tick, which is the pattern to copy.
- **A `ViewNode` keeps the scene awake while its view is drawing, and only then.** The hosted
  Android `View` animates on its own schedule — a ripple, a spinner, a cursor, an inner fling, a
  recomposition of hosted Compose content — and
  the library cannot see inside it, so the node reports itself active from the one thing that is
  observable: every buffer the view hierarchy queues onto its `SurfaceTexture`. An animating view
  therefore holds the full cadence for as long as it animates, and a view that has finished drawing
  parks with the rest of the scene. A `VideoNode` answers the same way, plus a direct read of
  `player.isPlaying`.
- Pre-compiled consumers must recompile. A caller passing nine or more **positional** arguments
  gets a type error at slot 9 rather than a silent behaviour change — the slot went from `Boolean`
  to `FrameRatePolicy`.
- `maxFps` is new, and it is an argument of both modes rather than a policy of its own:
  `OnDemand(maxFps = 30)` renders on demand and, when it does render, never faster than 30 fps;
  `Continuous(maxFps = 30)` is a steady 30 fps. Either way the display is voted `maxFps` rather
  than the panel maximum. `null` (the default) means the display's own cadence.
- **Direct Filament edits are the one thing the default cannot see.** Anything written below the
  scene graph leaves nothing to invalidate on. The full list, and it is a list rather than an
  example, because the cost of missing one is a frozen image:
  - a `MaterialInstance` parameter (`setParameter`) on an instance you already hold;
  - a light property written through `LightManager` rather than through the node;
  - a `Skybox` or `IndirectLight` assigned straight onto the Filament `Scene`;
  - morph-target weights and bone transforms written through `RenderableManager`;
  - an external `Stream` — a camera or video surface — pushing new content;
  - `View` options changed at runtime (bloom, ambient occlusion, dynamic resolution, blend mode)
    on a `View` you own.

  For all of them: call `node.requestRender()` on a node you hold, or take the new
  `renderInvalidator` parameter with `rememberRenderInvalidator()` and call
  `invalidator.requestRender()` after the edit. Call it on the main thread — post to it from a
  background upload rather than calling across. Before a `PixelCopy` or a screenshot, request a
  frame and then **wait for your next `onFrame`**: the request is fire-and-forget, and there is no
  "await one frame" API.
- **`ARSceneView` does not take this parameter, and its loop never parks.** A live camera feed is
  never idle, so there is no idle frame to skip. What it does do is skip the *GPU submit* on a
  vsync where ARCore hands back a duplicate `Frame.timestamp` and nothing in the scene changed —
  `session.update()` still runs every vsync, so tracking, anchors and plane detection are
  unaffected.

**Action:** delete the `isRendering` argument and the dirty-tracking behind it. Pass
`frameRatePolicy = FrameRatePolicy.Continuous()` only if your scene is driven by something the
library cannot observe — an external simulation writing into Filament each frame, a custom
`Renderer` hook, a texture updated off-thread.

### Where the line is: SDK mutators invalidate, raw Filament objects do not

This is the one rule to carry out of the migration, and it is the adoption risk the whole default
turns on — a missed invalidation does not crash, log or fail a test. It shows the previous frame,
forever, and reads as "the slider is broken".

**Anything you change through a SceneView type asks for its own frame.** Setting
`lightNode.intensity`, `lightNode.color`, `lightNode.lightDirection`, `cameraNode.setExposure(…)`,
a projection, `focusDistance`, a node's transform, `materialInstance` / `setMaterialInstanceAt`,
`setGeometry`, `setLayerVisible`, morph weights or bone matrices *through the node*, shadow flags,
priority, culling, blend order — all of these invalidate on their own. You do not call
`requestRender()` after them, and you do not need `Continuous()` to make them show.

**Anything you change on a raw Filament object does not.** The SDK hands these out and never sees
them again, so nothing is left to observe the write:

```kotlin
// Raw Filament object → the SDK cannot see this. Ask for the frame yourself.
indirectLight.intensity = 30_000f
indirectLight.setRotation(rotation)
materialInstance.setParameter("baseColorFactor", color)
view.ambientOcclusionOptions = options
scene.skybox = skybox
lightManager.setIntensity(instance, lux)   // through the manager, not the node

renderInvalidator.requestRender()          // ← the missing line
```

Take the invalidator with `rememberRenderInvalidator()` and pass it as
`SceneView(renderInvalidator = …)`, or call `requestRender()` on any node you hold. The rule of
thumb that costs nothing to apply: **if the type you are writing to came from `com.google.android
.filament`, ask for a frame.**

Measured, so it is not hypothetical: a demo screen dragging *Environment rotation* from 302° to
100° and *Exposure* from 1.00 to 2.72 on a parked scene produced **0** frames and a viewport still
lit the old way. Both writes went into an `IndirectLight`.

### Check your app for heuristics that read the frame rate

This is the failure mode that survives the mechanical migration, because it compiles, it is in
*your* code, and it looks like it is measuring something else.

**Any heuristic built on a run of closely spaced frames breaks under `OnDemand`.** The pattern is
always some form of "I will believe the scene is up once I have seen N frames within M
milliseconds of each other" — a loading cover, a splash dismissal, a warm-up probe, an "is the GPU
keeping up" check. It worked because every `SceneView` drew every vsync forever, so frames were a
clock you could count on. They are not a clock any more. A finished scene presents a short settle
tail and parks: our own demo app measured **3 frames in 10 s** on a fully drawn model, and its
loading cover — which wanted 8 frames within 250 ms of each other — never lifted. At 12 s the
scaffold replaced the spinner with a "Still loading…" card, over a scene that had been complete
for ten seconds. Nudging the camera restarted the loop, satisfied the streak and dismissed the
card, which is the tell: the signal was reading the frame rate and reporting it as progress.

**A readiness signal must never depend on cadence — a parked scene is a ready scene.** Rewrite the
rule to count frames without looking at the intervals between them, and if what you actually need
is "has the GPU finished", ask the GPU: `Engine.flushAndWait()` blocks until the backend has
executed the queued work, which is the fact the timing was being used to guess. Two presented
frames are enough to start that question, because Filament refuses a new frame while the driver is
behind — a second accepted submission is itself the evidence that the first was drained.

Do **not** fix this by holding the loop awake to feed the heuristic. That re-creates exactly the
drain this release exists to remove, and hides the bug instead of closing it.

### `onFrame` cannot be what keeps your loop awake

The same shape, one level down, and it is the one that costs you a screen rather than a cover.

`onFrame` fires **once per presented frame, right after that frame reached the surface**. Under
`Continuous` that was indistinguishable from "every vsync, forever", so it became the natural home
for anything per-frame: advancing an animation clock, stepping a physics simulation, driving a
turntable, syncing an audio listener pose. Under `OnDemand` it closes on itself. Nothing else
invalidates the scene, so the loop settles and parks; parked means no presented frame; no presented
frame means no `onFrame`; and the clock that was going to ask for the next frame is the one that
just stopped running. The screen does not stutter — it freezes on open, with a fully drawn, entirely
correct first frame, which is why it reads as "the animation is broken" and not as "the loop
stopped".

Two things to check in every per-frame callback you own:

- **Does it drive motion?** Then say so: `frameRatePolicy = FrameRatePolicy.Continuous()` while it
  runs, and back to `OnDemand()` when the user pauses it. This is what the mode is for, and it also
  lets the cadence vote tell the display. A `RenderInvalidator.requestRender()` called from inside
  `onFrame` works too — each rendered frame buys the next — but it says "one more frame" sixty times
  a second to mean "keep going", and nothing tells the panel.
- **Does it apply a change the user just made?** Then apply it **outside** `onFrame` and request the
  frame after: `onFrame` runs after presentation, so a pose written there lands in the *next* frame.
  One `requestRender()` would draw the old pose, then park one frame behind, permanently.

The state that decides — `isPlaying`, `isReplaying`, "is this band on screen" — also needs a rising
edge. Going from paused to playing while the loop is parked changes a flag no one is reading;
request one frame from a `LaunchedEffect` on that flag, and the callback takes it from there.

And it must follow the motion, not a flag only a gesture clears. A `Continuous()` held by
`isReplaying`, where `isReplaying` goes false only when the user presses Reset, is the same lie as a
loader that never resolves: the spheres stop bouncing, the flag stays true, and the screen renders a
frozen picture at 57 fps until someone touches it. Tie the declaration to the thing that is actually
moving.

### `Node.onFrame` holds the loop open; `SceneView(onFrame = …)` does not

They share a name and nothing else, and the asymmetry is deliberate:

| | when it runs | effect on the loop |
|---|---|---|
| `SceneView(onFrame = …)` | after its frame was presented | **none** — it is an observer |
| `node.onFrame = { … }` | before the frame is drawn | **pins** — a non-null slot asks for a frame every tick |

`Node.onFrame` is a *driver*: `PhysicsNode` steps its simulation there, and a driver that only runs
when a frame happens could never produce the first one. So setting it is read as "keep rendering",
and `node.onFrame = null` is how you stop asking. A transform written there is on screen in the same
frame — it runs ahead of the GPU submit, not after it.

Two consequences worth knowing before you go looking for a leak:

- **A node you hand an `onFrame` to will not let its scene park.** That is the safe default, and it
  is under your control. If the callback only *observes*, hold the value in Compose state instead.
- **The library's own per-frame work does not go through that slot** and does not pin anything.
  `BillboardNode` (and `TextNode`, which is one) re-orients only when the camera has actually moved;
  a settled `PhysicsNode` reports itself idle; `rememberModelAnimationState` observes without
  costing you a frame. Before 4.37 every one of them quietly held its scene at full cadence — a
  screen with two `TextNode`s on it ran at 57 fps on a still picture. None of this needs a change in
  your code.

---

## SceneView 4.14.x to 4.15.1 (iOS) — native Apple camera modes added to `CameraControlMode`

### Three new `CameraControlMode` cases — `.none`, `.tilt`, `.dolly` ([#1049](https://github.com/sceneview/sceneview/issues/1049))

`CameraControlMode` now exposes three **native Apple** cases that delegate to
`realityViewCameraControls(_:)` introduced in iOS 18 / macOS 15:

| New case | Behaviour | SDK requirement |
|---|---|---|
| `.none` | Disables all gesture interaction | iOS 18+, macOS 15+ |
| `.tilt` | Tilt camera up/down about horizontal axis | iOS 18+, macOS 15+ |
| `.dolly` | Move camera forward/back along look direction | iOS 18+, macOS 15+ |

**This is additive.** Existing code using `.orbit`, `.pan`, or `.firstPerson`
compiles and runs unchanged.

**visionOS note.** `realityViewCameraControls(_:)` is `@available(visionOS, unavailable)`.
On visionOS, `SceneView` automatically falls back to its custom gesture handlers.
No `#if os()` guards are required in app code.

```swift
// Before v4.15.1 — only 3 modes
.cameraControls(.orbit)    // .orbit | .pan | .firstPerson

// v4.15.1+ — 6 modes; native Apple modes available on iOS 18+ / macOS 15+
.cameraControls(.orbit)    // existing modes unchanged
.cameraControls(.none)     // lock all camera gestures (iOS 18+ / macOS 15+)
.cameraControls(.tilt)     // native tilt — Apple's realityViewCameraControls (iOS 18+ / macOS 15+)
.cameraControls(.dolly)    // native dolly — Apple's realityViewCameraControls (iOS 18+ / macOS 15+)
```

**Action:** no migration required. If you previously worked around the absence of
a "no gestures" mode by disabling the modifier entirely, you can now use
`.cameraControls(.none)`.

---

## SceneView 4.10.x to 4.11.0 (Android) — `CloudAnchorNode.host()` returns `HostCloudAnchorFuture`

### `CloudAnchorNode.host()` now returns the underlying ARCore `HostCloudAnchorFuture` ([#1768](https://github.com/sceneview/sceneview/pull/1768))

Pre-v4.11.0, `CloudAnchorNode.host(session, ttlDays, onCompleted)` returned `Unit`
and stored the in-flight future privately — apps had no way to cancel a pending
host request when the UI scope was leaving. The network round-trip ran to
completion regardless, accruing a Google Cloud billing event whether or not the
caller was still listening.

v4.11.0 surfaces the underlying ARCore [`HostCloudAnchorFuture`](https://developers.google.com/ar/reference/java/com/google/ar/core/HostCloudAnchorFuture)
as the return value so callers can cancel via `future.cancel()` — typically from
`DisposableEffect.onDispose { future?.cancel() }`. The same change applies to
`CloudAnchorNode.resolve(...)`, `TerrainAnchorNode.resolve(...)`, and
`RooftopAnchorNode.resolve(...)`.

**Billing rationale.** Each pending host/resolve future is **one Google Cloud
ARCore API request**. Without explicit cancellation, the request runs to
completion (a few seconds, sometimes longer for cross-continent resolves) and
the billing event lands on your Google Cloud project even after the user has
navigated away from the screen that started it. For apps that surface a "Place
anchor" CTA inside a navigable hierarchy (Compose / Fragment / Activity stack)
this is a measurable cost on cancelled user flows — see
[Google's ARCore Cloud Anchors documentation](https://developers.google.com/ar/develop/cloud-anchors).
Cancellation does **not** invoke the `onCompleted` callback (matches ARCore
semantics), so observers stay clean.

**Action — source compatibility.** Most call sites are unaffected — the new
return type is purely additive. The only break is for callers that wrote
`val unused: Unit = node.host(...)` or any test that asserts the return type is
`Unit`. Adjust the local binding and (recommended) cancel the future on dispose:

```kotlin
// pre-v4.11.0
DisposableEffect(anchorNode) {
    anchorNode.host(session, ttlDays = 7) { id, state -> /* ... */ }
    onDispose { /* nothing to cancel — the future was internal */ }
}

// v4.11.0 — capture the future, cancel on dispose to free the billing event
DisposableEffect(anchorNode) {
    val future = anchorNode.host(session, ttlDays = 7) { id, state -> /* ... */ }
    onDispose { future.cancel() }
}
```

The same pattern applies to `CloudAnchorNode.resolve(engine, session, id) { ... }`,
`TerrainAnchorNode.resolve(...)`, and `RooftopAnchorNode.resolve(...)`. The
node's own `destroy()` lifecycle still calls `cancelHost()` internally, so an
app that doesn't cancel explicitly still cleans up on scene disposal — but it
pays the round-trip until destroy fires.

---

## SceneView 4.3.x to 4.4.0 (iOS) — true camera motion + skybox renders + mirror retired

### `Environment.showSkybox = true` now actually paints the HDR as background ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))

Pre-v4.4.0 the flag set up IBL lighting from the HDR but never rendered the HDR
as the scene background — the void stayed neutral. v4.4.0 wires the HDR resource
through `RealityViewContent.environment = .skybox(...)`.

**Action**:

- If your app set `showSkybox: false` to *explicitly* hide a background that was
  already invisible, you can leave the code as-is — `false` continues to render
  the neutral default.
- If your app set `showSkybox: true` and was depending on the void-background
  bug (e.g. compositing the scene over a custom SwiftUI background), explicitly
  flip to `showSkybox: false`. The IBL still lights your scene.

```swift
// pre-v4.4.0 (the bug)
.environment(.custom(name: "city", hdrFile: "city.hdr", showSkybox: true))
//                                                       ^^^^ silently ignored

// v4.4.0 (intended behaviour)
.environment(.custom(name: "city", hdrFile: "city.hdr", showSkybox: true))
//                                                       ^^^^ now renders HDR as background
.environment(.custom(name: "city", hdrFile: "city.hdr", showSkybox: false))
//                                                       ^^^^ neutral background, IBL still applies
```

### Orbit + pan modes now physically move the perspective camera ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))

Pre-v4.4.0 `applyCamera()` rotated + scaled the scene root while the perspective
camera stayed pinned at `[0, 0.3, 2]`. v4.4.0 positions the camera in world-space
via `CameraControls.cameraPosition()` so the skybox correctly wraps around the
camera as it orbits.

**Action**:

- Most apps need no code change — the apparent on-screen framing of existing
  scenes is preserved at the new defaults (the SceneView's internal `CameraControls`
  default `orbitRadius` was bumped from `5.0` to `2.0` to match the old camera
  distance, so a 1m model fills the same fraction of viewport).
- If your code reads `entities.root.scale` / `.orientation` via reflection,
  debug-overlay, or a custom modifier, those now stay at identity in orbit / pan
  modes. Migrate to `CameraControls.cameraTransform()` to recover the camera
  matrix.

### `CameraControls` defaults changed (BREAKING for direct constructors)

If your app constructs `CameraControls()` directly (rather than using the
`.cameraControls(_:)` modifier):

- `orbitRadius` default: `5.0` → `2.0` (camera-to-target distance, was unreachable
  through any public modifier before so this aligns with the SceneView's
  internal default)
- `minRadius` default: `0.5` → `1.0` (pinch-in floor — `0.5` clipped into 1m-extent
  models under the new true-camera path; `1.0` matches typical bundled demo content)

**Action**:

```swift
// pre-v4.4.0 implicit defaults
var controls = CameraControls()
// controls.orbitRadius == 5.0, controls.minRadius == 0.5

// v4.4.0 — bump explicitly if you want the pre-v4.4.0 framing for non-bundled content
var controls = CameraControls()
controls.orbitRadius = 5.0
controls.minRadius = 0.5
```

### FOV no longer bleeds from `firstPerson` pinch into `orbit` / `pan`

Pre-v4.4.0, pinching FOV down to e.g. 30° in `.firstPerson` then switching to
`.orbit` kept the 30° pinched FOV on the perspective camera (visible as a stuck
zoom-in). v4.4.0 writes the baseline `60°` FOV in orbit/pan regardless, and
mirrors `camera.fov` only in `.firstPerson`. On `.firstPerson` exit, `camera.fov`
itself is reset to `60` so the next entry starts fresh.

**Action**: no code change required — the previous behaviour was a bug.

### Swift Package Manager URL changed

The `sceneview/sceneview-swift` mirror has been archived read-only. SPM consumers
should re-add the package in Xcode pointing at the monorepo directly:

```diff
- .package(url: "https://github.com/sceneview/sceneview-swift.git", from: "4.0.0")
+ .package(url: "https://github.com/sceneview/sceneview.git", from: "4.4.0")
```

The frozen `v4.0.0` tag on the mirror still resolves for any consumer that hasn't
migrated, but no further releases will be cut there. The root `Package.swift` in
the monorepo (added in [#920](https://github.com/sceneview/sceneview/pull/920))
declares the `SceneViewSwift` product, so the import statement
(`import SceneViewSwift`) is unchanged.

### Attribution

The skybox-renders + true-orbit camera fixes were ported with `Co-authored-by`
credit from [@radcli14](https://github.com/radcli14)'s
[sceneview-swift PR #1](https://github.com/sceneview/sceneview-swift/pull/1).

---

## SceneView 4.2.x to 4.3.0 (iOS) — silent-stub modes now active

### `.cameraControls(.pan)` and `.cameraControls(.firstPerson)` no longer silently orbit ([#1034](https://github.com/sceneview/sceneview/issues/1034))

In v4.2.0 these two modes existed in the enum but `applyCamera()` ignored
them — they produced orbit behaviour at runtime. v4.3.0 wires them to
real per-mode handlers.

**Action**:

- Apps that called `.cameraControls(.pan)` *expecting* orbit behaviour
  should switch to `.cameraControls(.orbit)` (or drop the modifier
  entirely — orbit is the default).
- Apps that called `.cameraControls(.firstPerson)` get FOV-zoom pinch
  instead of dolly. To keep the v4.2.0 orbit-with-dolly behaviour,
  switch to `.orbit`.

```swift
// v4.2.0 (silent stub)
SceneView { /* ... */ }
  .cameraControls(.pan)   // actually orbited

// v4.3.0 (wired)
SceneView { /* ... */ }
  .cameraControls(.pan)   // now translates target; drop modifier for orbit
```

### Library-level auto-center is on by default ([#1026](https://github.com/sceneview/sceneview/issues/1026))

iOS v4.3.0 introduces an intermediate `contentRoot` Entity and translates
it on the first frame the scene's `visualBounds` is non-empty so the
centroid lands on the world origin. Most demos benefit; scenes that rely
on intentional off-centre placement (carousels, dioramas, story-mode)
will see content re-centre.

**Action**: append `.autoCenterContent(false)` for off-centre-by-design
scenes.

```swift
// v4.2.0 — manual per-demo centring
SceneView { root in
    let model = ModelNode.load("hero.usdz")
    model.entity.position = .init(x: 0, y: -0.5, z: -2)  // intentional offset
    root.addChild(model.entity)
}

// v4.3.0 — opt out of library centring to keep the offset
SceneView { root in
    let model = ModelNode.load("hero.usdz")
    model.entity.position = .init(x: 0, y: -0.5, z: -2)
    root.addChild(model.entity)
}
.autoCenterContent(false)   // ← restore strict v4.2.0 placement
```

---

## SceneView 3.6.x to 4.0.0 (Release Candidate)

**Status:** `v4.0.0` is live as a release candidate. Maven Central and Swift Package Manager artifacts are **not** built from the RC tag — pin to the tag manually to test, or wait for the `v4.0.0` stable tag.

4.0.0 is a **strictly additive** release for the SDK side. Existing 3.6.x code compiles and runs unchanged. The version bump reflects two new capabilities, not breaking changes.

### 1. New: AR Debug — Rerun.io integration

SceneView can now stream an ARCore / ARKit session to the [Rerun](https://rerun.io) viewer for scrub-and-replay debugging. See the full guide in the [llms.txt reference](https://github.com/sceneview/sceneview/blob/main/llms.txt) or the [AR Debug (Rerun)](https://sceneview.github.io/playground.html) example in the playground.

Quick wire-up on Android:

```kotlin
import io.github.sceneview.ar.rerun.rememberRerunBridge

@Composable
fun MyARScreen() {
    val bridge = rememberRerunBridge(enabled = BuildConfig.DEBUG)
    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        onSessionUpdated = { session, frame ->
            bridge.logFrame(session, frame)
        }
    )
}
```

And on iOS:

```swift
import SceneViewSwift

struct MyARView: View {
    @StateObject private var bridge = RerunBridge(host: "192.168.1.42")

    var body: some View {
        ARSceneView()
            .onFrame { frame, _ in bridge.logFrame(frame) }
            .onAppear { bridge.connect() }
            .onDisappear { bridge.disconnect() }
    }
}
```

No breaking changes — this is a new `io.github.sceneview.ar.rerun` package on Android and a new `SceneViewSwift.RerunBridge` type on iOS. Nothing existing was touched.

### 2. New: `ARSceneView.onFrame` hook on iOS

`SceneViewSwift.ARSceneView` gained a new `onFrame(_ handler:)` modifier mirroring Android's `onSessionUpdated` callback. Fires from the existing `ARSessionDelegate.session(_:didUpdate:)` without adding a second delegate. Used by the Rerun bridge, but useful for any per-frame custom logic (pose streaming, custom tracking analytics, etc.).

```swift
ARSceneView()
    .onFrame { frame, arView in
        // runs on the ARKit delegate queue — do NOT block
    }
```

### 3. Version bump — just the number

Update your Gradle / SPM / pubspec / package.json references:

```kotlin
// Before
implementation("io.github.sceneview:sceneview:4.39.0")
implementation("io.github.sceneview:arsceneview:4.39.0")

// After (4.0.0)
implementation("io.github.sceneview:sceneview:4.39.0")
implementation("io.github.sceneview:arsceneview:4.39.0")
```

`4.0.0` and every later 4.x release are on Maven Central — use the current version shown above.

### 4. `sceneview-mcp` moved to the 4.x line

During the 4.0.0 release candidate, the 4.x line of the [`sceneview-mcp`](https://www.npmjs.com/package/sceneview-mcp) npm package shipped under the `@next` tag while `@latest` stayed on `3.6.4`. That is over: `@latest` is now on the 4.x line (with the Rerun integration docs), so the plain package name is all you need:

```json
"sceneview": {
  "command": "npx",
  "args": ["-y", "sceneview-mcp"]
}
```

There is also a **new** [`rerun-3d-mcp`](https://www.npmjs.com/package/rerun-3d-mcp) package (`@latest = 1.0.0`) that generates the Rerun integration boilerplate on demand:

```bash
npx rerun-3d-mcp
```

---

## SceneView 3.5.x to 3.6.0

SceneView 3.6.0 simplifies the API surface so that AI assistants and developers can write correct
code on the first try. All changes use `@Deprecated(replaceWith = ...)` — the Kotlin compiler will
auto-suggest fixes via IDE quick actions.

### 1. `Scene` composable renamed to `SceneView`, `ARScene` to `ARSceneView`

The `Scene { }` composable is renamed to `SceneView { }` and `ARScene { }` is renamed to
`ARSceneView { }`. This aligns Android naming with Apple (SwiftUI already uses `SceneView` and
`ARSceneView`) and avoids confusion with Filament's internal `Scene` class.

```kotlin
// Before
Scene(modifier = Modifier.fillMaxSize()) { /* nodes */ }
ARScene(modifier = Modifier.fillMaxSize()) { /* AR nodes */ }

// After (3.6.0)
SceneView(modifier = Modifier.fillMaxSize()) { /* nodes */ }
ARSceneView(modifier = Modifier.fillMaxSize()) { /* AR nodes */ }
```

`SceneScope` and `ARSceneScope` are **unchanged** — only the top-level composable names changed.

### 2. `CameraNode` composable renamed to `SecondaryCamera`

The `CameraNode { }` composable inside `SceneView { }` creates a non-active camera — it does NOT
become the scene's rendering camera. The name was misleading, so it's renamed to `SecondaryCamera`.

```kotlin
// Before
SceneView(cameraNode = rememberCameraNode(engine)) {
    CameraNode { /* secondary camera — name was confusing */ }
}

// After (3.6.0)
SceneView(cameraNode = rememberCameraNode(engine)) {
    SecondaryCamera { /* clearly named as non-primary */ }
}
```

The scene's active camera is still set via `SceneView(cameraNode = rememberCameraNode(engine))` —
this has NOT changed.

### 3. All geometry nodes now have uniform transform params

Every geometry composable (`CubeNode`, `SphereNode`, `CylinderNode`, `PlaneNode`, `LineNode`,
`PathNode`) now accepts the same `position`, `rotation`, `scale` trio. Previously, some were missing.

```kotlin
// Before — SphereNode had no rotation or scale
SphereNode(radius = 0.2f, materialInstance = mat)

// After (3.6.0) — all geometry nodes have the full trio
SphereNode(
    radius = 0.2f,
    materialInstance = mat,
    position = Position(x = 1f),
    rotation = Rotation(y = 45f),
    scale = Scale(2f)
)
```

### 4. `LightNode` — explicit params instead of dual lambdas

`LightNode` now exposes `intensity`, `direction`, and `position` as direct parameters instead of
requiring two separate `apply` / `nodeApply` lambdas.

```kotlin
// Before
LightNode(
    type = LightManager.Type.DIRECTIONAL,
    apply = { intensity(100_000f); direction(0f, -1f, 0f) },
    nodeApply = { position = Position(0f, 5f, 0f) }
)

// After (3.6.0)
LightNode(
    type = LightManager.Type.DIRECTIONAL,
    intensity = 100_000f,
    direction = Direction(0f, -1f, 0f),
    position = Position(0f, 5f, 0f)
)
```

### 5. `VideoNode` — convenience overload with asset path

New overload that handles `MediaPlayer` lifecycle automatically:

```kotlin
// Before — manual MediaPlayer setup
val player = rememberMediaPlayer(context, assetFileLocation = "videos/promo.mp4")
SceneView {
    player?.let { VideoNode(player = it, position = Position(z = -2f)) }
}

// After (3.6.0) — one-liner
SceneView {
    VideoNode(videoPath = "videos/promo.mp4", position = Position(z = -2f))
}
```

### 6. New composables: `ShapeNode` and `PhysicsNode`

Both are now available directly in the `SceneView { }` DSL:

```kotlin
SceneView {
    ShapeNode(
        polygonPath = listOf(Position2(0f, 0f), Position2(1f, 0f), Position2(0.5f, 1f)),
        color = Color(0xFF2196F3.toInt())
    )
    PhysicsNode(gravity = -9.81f, floorY = 0f) {
        SphereNode(radius = 0.1f, materialInstance = mat)
    }
}
```

### 7. Swift: Declarative `SceneView` with `@NodeBuilder`

SwiftUI `SceneView` now supports a declarative builder matching Android's `SceneView { }`:

```swift
// Before — imperative
SceneView { root in
    root.addChild(cube.entity)
    root.addChild(sphere.entity)
}

// After (3.6.0) — declarative
SceneView {
    GeometryNode.cube(size: 0.3, color: .red)
        .position(.init(x: -1, y: 0, z: -2))
    GeometryNode.sphere(radius: 0.2, color: .blue)
        .position(.init(x: 1, y: 0, z: -2))
}
```

### 8. Swift: `NodeGesture` automatic cleanup

`NodeGesture` now tracks entities with weak references and automatically purges stale handlers.
Entity fluent extensions are available for cleaner syntax:

```swift
// Before — static calls only
NodeGesture.onTap(entity) { print("Tapped!") }

// After (3.6.0) — fluent chaining
entity.onTap { print("Tapped!") }
       .onDrag { translation in entity.position += translation }
```

---

## SceneView 2.x to 3.x

SceneView 3.0 is a ground-up rewrite around Jetpack Compose. The core concepts are the same
(Filament engine, ARCore session, node graph), but the API is fully Compose-native. This guide
walks through every breaking change with before/after examples.

SceneView 3.0 is a ground-up rewrite around Jetpack Compose. The core concepts are the same
(Filament engine, ARCore session, node graph), but the API is fully Compose-native. This guide
walks through every breaking change with before/after examples.

---

## 1. Dependency version

```kotlin
// Before
implementation("io.github.sceneview:sceneview:2.3.0")
implementation("io.github.sceneview:arsceneview:2.3.0")

// After
implementation("io.github.sceneview:sceneview:4.39.0")
implementation("io.github.sceneview:arsceneview:4.39.0")
```

---

## 2. `SceneView` — nodes move into the content block

The `childNodes` parameter is gone. Declare nodes directly inside the `SceneView { }` trailing lambda.

```kotlin
// Before — nodes passed as a list
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    childNodes = rememberNodes {
        add(
            ModelNode(
                modelInstance = modelLoader.createModelInstance("models/helmet.glb"),
                scaleToUnits = 1.0f
            )
        )
        add(CylinderNode(engine = engine, radius = 0.1f, height = 1.0f))
    },
    cameraManipulator = rememberCameraManipulator()
)

// After — nodes declared as composables in the DSL
val modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb")

SceneView(
    modifier = Modifier.fillMaxSize(),
    cameraManipulator = rememberCameraManipulator()
) {
    modelInstance?.let { instance ->
        ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
    }
    CylinderNode(radius = 0.1f, height = 1.0f)
}
```

Key differences:
- `engine` and `modelLoader` parameters have sensible defaults — you only need to provide them
  explicitly if you're sharing resources across multiple scenes.
- `rememberModelInstance` is async and returns `null` while loading. Use `?.let { }` to show the
  node only when ready. It triggers recomposition automatically.
- No more `add()` calls. The Compose runtime manages the node lifecycle.

---

## 3. Node hierarchy — `NodeScope` replaces `addChildNode`

```kotlin
// Before — imperative parent/child wiring
val parentNode = Node(engine).apply {
    addChildNode(
        ModelNode(modelInstance = helmet).apply {
            position = Position(y = 0.1f)
        }
    )
}

// After — declarative nesting via NodeScope
Node(position = Position(y = 0.0f)) {   // trailing lambda opens a NodeScope
    ModelNode(modelInstance = helmet, position = Position(y = 0.1f))
}
```

Every node composable in `SceneScope` accepts an optional `content` trailing lambda. Nodes
declared inside that lambda are automatically parented to the enclosing node.

---

## 4. `ARSceneView` — AR nodes move into the content block

```kotlin
// Before
var anchor: Anchor? = null

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    childNodes = rememberNodes { /* populated imperatively in onSessionUpdated */ },
    onSessionUpdated = { session, frame ->
        if (anchor == null) {
            anchor = frame.hitTest(centerX, centerY)
                .firstOrNull { it.trackable is Plane }
                ?.createAnchor()
                ?.also { a ->
                    childNodes.add(AnchorNode(engine, a).apply {
                        addChildNode(ModelNode(modelInstance = helmet))
                    })
                }
        }
    }
)

// After — state drives composition
var anchor by remember { mutableStateOf<Anchor?>(null) }

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    onSessionUpdated = { _, frame ->
        if (anchor == null) {
            anchor = frame.getUpdatedPlanes()
                .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                ?.let { it.createAnchorOrNull(it.centerPose) }
        }
    }
) {
    anchor?.let { a ->
        AnchorNode(anchor = a) {
            ModelNode(modelInstance = helmet, scaleToUnits = 0.5f)
        }
    }
}
```

The anchor state variable drives everything. When `anchor` becomes non-null, `AnchorNode` enters
the composition. When it is cleared, the node is removed and destroyed automatically.

---

## 5. Model loading — `rememberModelInstance` replaces synchronous creation

```kotlin
// Before — blocking, called inside rememberNodes or init
val instance = modelLoader.createModelInstance("models/helmet.glb")

// After — async, null while loading
val instance = rememberModelInstance(modelLoader, "models/helmet.glb")
// instance is null until the file is loaded, then recomposition fires
```

`rememberModelInstance` reads the file on `Dispatchers.IO` and creates the Filament asset on the
main thread, so it is both non-blocking and thread-safe.

---

## 6. `SurfaceType` — replaces boolean flags

```kotlin
// Before (if the flag existed in your version)
SceneView(isOpaque = false)

// After — explicit enum
SceneView(surfaceType = SurfaceType.TextureSurface)  // TextureView, supports alpha blending
SceneView(surfaceType = SurfaceType.Surface)          // SurfaceView, best performance (default)
```

---

## 7. `ViewNode` — Compose UI as a 3D surface

`ViewNode` is now a first-class composable in `SceneScope`. It requires a `WindowManager`
obtained with `rememberViewNodeManager()`.

```kotlin
// After
val windowManager = rememberViewNodeManager()

SceneView {
    ViewNode(windowManager = windowManager) {
        Card { Text("Hello from 3D!") }
    }
}
```

---

## 8. Activity / Fragment structure

All samples (and the recommended app structure) have moved from Fragment + XML layout to a single
`ComponentActivity` with `setContent { }`. There is no Fragment API in 3.0.

```kotlin
// Before — Fragment with layout inflation
class MainFragment : Fragment() {
    override fun onCreateView(...) = layoutInflater.inflate(R.layout.fragment_main, ...)
    override fun onViewCreated(view: View, ...) {
        val sceneView = view.findViewById<ARSceneView>(R.id.sceneView)
        sceneView.onSessionUpdated = { ... }
    }
}

// After — Activity with Compose
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ARSceneView(modifier = Modifier.fillMaxSize()) {
                // AR content here
            }
        }
    }
}
```

---

## 9. Sceneform / legacy Java classes

All classes that were still present under `com.google.ar.sceneform.*` and
`io.github.sceneview.collision.*` as Java files have been converted to Kotlin. The class names
and package paths are unchanged — only the file extension changes from `.java` to `.kt`.

If you were importing these classes directly (e.g. `com.google.ar.sceneform.rendering.Color`),
the imports continue to work. No action required.

---

## Summary checklist

| Change | Action |
|---|---|
| Bump dependency to `4.0.0` | Update `build.gradle` |
| Remove `childNodes = rememberNodes { }` | Move node declarations into `SceneView { }` |
| Replace `add(ModelNode(...))` | Use `ModelNode(...)` composable directly |
| Replace `addChildNode(...)` | Use nested `NodeScope` content lambda |
| Replace `modelLoader.createModelInstance(...)` | Use `rememberModelInstance(modelLoader, path)` |
| Replace `isOpaque = false` | Use `surfaceType = SurfaceType.TextureSurface` |
| Replace Fragment + XML layout | Use `ComponentActivity` + `setContent { }` |
| Replace imperative `anchor` node wiring | Drive with `mutableStateOf<Anchor?>` |

---

# v3.1.x → v3.2.x

## New node types (non-breaking)

v3.2.0 adds 8 new node composables in `SceneScope`. No migration required — these are additive.

| Node | Purpose |
|---|---|
| `PhysicsNode` | Rigid body simulation (gravity, floor collision, sleep) |
| `DynamicSkyNode` | Time-of-day sun positioning and coloring |
| `FogNode` | Atmospheric fog (density, height, color) |
| `ReflectionProbeNode` | Local/global IBL override zones |
| `LineNode` | Single line segment between two points |
| `PathNode` | Polyline through ordered points |
| `BillboardNode` | Camera-facing image quad |
| `TextNode` | Camera-facing text label |

## Dependency management change

Sample apps now use the Gradle version catalog (`libs.*`) instead of hardcoded versions.
If you copied a sample `build.gradle` as a starting point, update your dependencies:

```kotlin
// Before (hardcoded)
implementation "androidx.compose.ui:ui:1.10.5"
implementation "androidx.compose.material3:material3:1.3.2"

// After (version catalog)
implementation libs.androidx.compose.ui
implementation libs.androidx.compose.material3
```

Or if you're not using a version catalog, bump to the latest versions listed in
`gradle/libs.versions.toml`.

## Edge-to-edge

All sample activities now call `enableEdgeToEdge()` before `setContent {}`. If you're
building on a sample, add it to your `onCreate`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { /* ... */ }
}
```

## 16 KB page-size alignment (API 35+, required for Play Store)

Google Play has enforced 16 KB ELF page-size alignment for all APKs targeting Android 15+
(API 35) since January 2026. SceneView adds the AGP flag to its own library modules; your
**app-level** `build.gradle` / `build.gradle.kts` must also opt in:

```groovy
// app/build.gradle (Groovy DSL)
android {
    experimentalProperties["android.nativeLibraryAlignmentPageSize"] = "16k"
    // …
}
```

```kotlin
// app/build.gradle.kts (Kotlin DSL)
android {
    experimentalProperties["android.nativeLibraryAlignmentPageSize"] = "16k"
    // …
}
```

This rewrites ELF `PT_LOAD` alignment headers for **all** native `.so` files packaged
into the APK/AAB at build time, including the Filament and ARCore prebuilt libraries.
Without this flag, Play Store will reject the upload with:
`Artifact does not support 16KB page size`.
