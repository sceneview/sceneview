# sceneview-compose

SceneView from `commonMain`, in a Compose Multiplatform project.

```kotlin
SceneViewer(
    model = ModelSource.Asset("models/damaged_helmet.glb"),
    modifier = Modifier.fillMaxSize(),
)
```

## Scope: the viewer subset

This module is **one composable**, `SceneViewer`, covering the model-viewer case: load a
model, orbit it, light it, tap it. That is the whole contract, and it is not a phase on
the way to something larger.

The reason is measurable. Android exposes 27 node types, the Swift API 20, the web one
~10; the honest intersection of all three is **five** — Camera, Geometry, Light, Model,
SpatialAudio — of which this module covers **four**. SpatialAudio is left out on purpose:
it is not the viewer case, and each platform's audio session has its own lifecycle.
An API that promised more would be a lowest-common-denominator that lies about every
platform it covers.

### What is here

- glTF / GLB loading from an asset, from bytes, or from a URL
- Orbit camera with hoistable, saveable state
- A directional key light and an environment (default, flat colour, or HDR)
- Tap hit-testing against the model
- An opt-in `onError` callback — **use it.** A failed load has no pixels of its own: the
  viewport keeps showing the environment, which is indistinguishable from a load still in
  progress. Without `onError` the only trace is the platform log, under the `SceneViewer`
  tag. It reports an exception (missing asset, HTTP error, size cap) *and* a loader that
  answers "no model" without throwing (a malformed glTF/GLB), where `cause` is `null`.

### What is deliberately not here, ever

| | Use instead |
|---|---|
| **AR** | `io.github.sceneview.arsceneview` on Android, `ARSceneView` on Apple |
| Custom materials, shaders, post-processing | the platform-native API |
| Splat, Video, View, ContactShadow, Physics, Text nodes | the platform-native API |

AR is the important one: 77 of the 178 Kotlin files in `arsceneview` import ARCore
directly (58 under `src/main`, 19 under `src/test`), and Apple's equivalent is ARKit. There is
no honest common shape.

## One API, several renderers

| Platform | Renderer | Status |
|---|---|---|
| Android | Filament, via `io.github.sceneview.SceneView` | ✅ implemented |
| iOS | RealityKit, via `SceneViewSwift` | ⚙️ Kotlin side done — needs a one-time app registration, below |
| Desktop (JVM) | Filament, via an FFM binding to be vendored from filament-kmp | ⏳ placeholder — binding not vendored yet, then needs the native build chain |

Unimplemented platforms render a visible placeholder naming the platform and the reason,
not an empty viewport — a blank box is indistinguishable from a model that failed to
load.

**Pixel output differs between platforms.** Lighting values map approximately between
Filament and RealityKit, not exactly, and materials do not carry across engines. If you
need reproducible output, ship an HDR environment and use it everywhere.

## iOS integration (one-time, per app)

A Kotlin Multiplatform module cannot depend on a Swift Package, and `SceneViewSwift`'s
API is pure SwiftUI, which does not cross cinterop. So this module declares *what it
needs* and your app — where `SceneViewSwift` is already linked — supplies it once:

```kotlin
// iosMain, before the first SceneViewer composes
SceneViewerBridge.factory = MyRealityKitFactory()
```

Your factory implements two methods:

```kotlin
public interface SceneViewerViewFactory {
    fun create(spec: SceneViewerSpec): UIView
    fun update(view: UIView, spec: SceneViewerSpec)   // mutate, never recreate
}
```

`SceneViewerSpec` is deliberately flat and primitive so it crosses into Swift cleanly:
model source (asset path / URL / bytes), camera (target, distance, azimuth, elevation in
**degrees** — Swift converts to radians), lighting, environment, plus three callbacks:

- `onTap(hit, x, y, z, distance)` — wire to `SceneViewSwift`'s `.onEntityTapped`.
- `onCameraMoved(distance, azimuth, elevation)` — **call this after every gesture.**
  It is what keeps `CameraState` truthful; skip it and reads return only what the app
  last wrote, silently ignoring the user.
- `onError(message)` — **call this when a load fails.** It surfaces as the app's
  `onError`. RealityKit fails the same way Filament does, with no pixels of its own, so
  an implementation that never calls it leaves the app unable to tell a failure from a
  slow load.

`SceneViewerSpec` compares by **value**, and its `modelBytes` by **content**. That is
what lets `update` be skipped when a recomposition changed nothing — so implement
`update` as a plain apply, and do not add your own "did anything change?" caching on the
Swift side expecting to be called on every frame. You will not be.

Until a factory is registered, `SceneViewer` renders a visible notice saying so.

> **Not yet written:** the reusable `@objc UIView` that wraps `SceneViewSwift.SceneView`
> in a `UIHostingController`. The pattern it should follow already exists and is
> production-tested in `flutter/sceneview_flutter/ios/Classes/SceneViewPlugin.swift`
> (see `SceneViewPlatformView` / `SceneViewSwiftUIWrapper`, including its retain-cycle
> and Swift-6 actor handling). Two known limits to carry over: `SceneViewSwift` exposes
> no per-frame callback, so `onFrame` is not invoked on iOS; and its public API seeds the
> orbit pose via `.cameraOrbit(azimuth:elevation:)` but does not expose continuous camera
> control, so full `CameraState` write-through needs an additive extension to
> `SceneViewSwift` first.

## Targets

`androidTarget`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`.

`iosX64` (the Intel simulator) is absent because Compose Multiplatform 1.11.1 publishes
no `iosX64` variant. `sceneview-core` still targets it — it has no Compose dependency —
so the two modules are not inconsistent.

## Guardrail

No renderer type may appear in this module's public API. It is enforced, not trusted:
`explicitApi()` plus the committed `.api` dump from binary-compatibility-validator make
any leak a reviewable diff.

Full reasoning: [`docs/docs/compose-multiplatform.md`](../docs/docs/compose-multiplatform.md).
