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

- Model loading from an asset, from bytes, or from a URL — glTF / GLB on Android and
  desktop, `.usdz` / `.reality` on iOS (RealityKit reads no glTF; see the table below)
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
| iOS | RealityKit, via `SceneViewSwift` | ✅ implemented — needs a one-time app registration, below |
| Desktop (JVM) | Filament, via filament-kmp (Maven). Offscreen readback → Skia. **JDK 22+** | ✅ implemented |

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

### The `UIView` is written for you

You do **not** have to wrap `SceneViewSwift.SceneView` yourself. `SceneViewSwift` ships
`SceneViewerHostView` — an `@objc UIView` that hosts the SwiftUI scene and is driven
entirely by primitives, so your factory is a field-by-field copy and nothing more:

```kotlin
// iosMain
class MyRealityKitFactory : SceneViewerViewFactory {
    override fun create(spec: SceneViewerSpec): UIView =
        SVSceneViewerHostView().also { host ->
            host.onTap = { hit, x, y, z, distance ->
                spec.onTap(hit, x, y, z, distance)
            }
            host.onCameraMoved = { distance, azimuth, elevation ->
                spec.onCameraMoved(distance, azimuth, elevation)
            }
            host.applyConfiguration(spec.toConfiguration())
        }

    override fun update(view: UIView, spec: SceneViewerSpec) {
        (view as SVSceneViewerHostView).applyConfiguration(spec.toConfiguration())
    }
}

private fun SceneViewerSpec.toConfiguration() = SVSceneViewerConfiguration().also {
    it.modelAssetPath = modelAssetPath
    it.modelURLString = modelUrl
    it.modelBytes = modelBytes?.toNSData()          // your own ByteArray -> NSData helper
    it.cameraTargetX = cameraTargetX          // …and the rest, one line each
    it.cameraDistance = cameraDistance
    it.cameraAzimuthDegrees = cameraAzimuthDegrees
    it.cameraElevationDegrees = cameraElevationDegrees
    it.cameraGesturesEnabled = cameraGesturesEnabled
    it.environmentKind = environmentKind
    // …
}
```

Set the callbacks **once, at creation** — `SceneViewerSpec`'s lambdas already forward to
the latest Compose state, so re-assigning them on every update buys nothing. Call
`applyConfiguration(_:)` on every update, and never rebuild the view: rebuilding reloads
the model and discards wherever the user had orbited to.

The same host serves the Flutter and React Native bridges: `SceneViewPlugin.swift` and
`SceneViewModule.swift` render their 3D path through it too. Each keeps a platform-view
class of its own for what is genuinely its own — the method channel, the prop bag, and
the AR path, which is anchor-driven and shares nothing with the 3D viewer.

### What iOS does differently

Not bugs to report — measured, deliberate consequences of RealityKit's surface. Each is
also stated where the corresponding API is documented, so it is not discoverable only
here.

| | Android (Filament) | iOS (RealityKit) |
|---|---|---|
| `onFrame` | called every frame | **never called.** `SceneViewSwift` publishes no per-frame callback, and a polled timer would report times that are not the renderer's |
| `ModelSource.Asset` / `Url` / `Bytes` | glTF / GLB | **`.usdz` / `.reality` only.** RealityKit does not read glTF. Convert with `tools/convert-usdz.sh` |
| `onTap` miss | fires with `null` | **no callback at all.** RealityKit's hit-test gesture only fires when it hits something |
| `ModelHit.position` | true ray-surface intersection | the tapped entity's **bounds centre** — the exact point is unavailable outside visionOS |
| `EnvironmentSource.Color` | key light only, flat colour behind | flat colour behind, but the model is still lit by RealityKit's default IBL, which is not exposed |
| `Lighting.ambientIntensity` | always applied | applies only with `EnvironmentSource.Hdr` — the other two have no authored IBL to scale |
| `CameraState.distance` | any positive value | clamped to `1…50` scene units, RealityKit's own dolly envelope. **The clamped value is reported back**, so a clamp is visible in your state rather than a silent disagreement with the screen |
| `ModelSource.Url` download | capped at 64 MB, with connect/read timeouts | same 64 MB cap, plus a scheme allowlist (`http`/`https` only) and a 60 s inactivity timeout. The cap cancels the transfer mid-flight rather than measuring it afterwards |

`CameraState.azimuth`, `elevation`, `distance` and `gesturesEnabled` are fully two-way on
iOS: gestures write into them, and writes drive the camera. Measured on the iOS 26.3
simulator — a 180-point horizontal drag moved the camera to the arithmetically expected
−51.6° and reported exactly that back, and writing 90° from the app moved the camera
there.

## Desktop (JVM)

Filament through [filament-kmp](https://github.com/Erkko68/filament-kmp) on Maven
(`filament-compose` 0.3.1, `implementation` — never on the public API). Offscreen
readback → Skia, inside filament-kmp. **JDK 22+** (FFM). Consumers must run with
`--enable-native-access=ALL-UNNAMED`.

`ModelSource.Asset` resolves against the JVM classpath. Prefer `ModelSource.Bytes`
from a Compose Multiplatform resource. Sample: [`samples/desktop-demo`](../samples/desktop-demo).

| | Android | Desktop |
|---|---|---|
| `onTap` / `ModelHit.position` | collision ray–surface | Filament color-pick unprojected through the view/projection matrices. A miss is `null`. If the pick lands before the camera has attached, the orbit target is used as a last resort |
| `EnvironmentSource.Color` `alpha` / `Hdr(showSkybox = false)` | transparent surface (`isOpaque`) | skybox colour can be translucent; the **surface stays opaque** (filament-kmp 0.3.1 has no `transparent` flag) |
| `Lighting.ambientIntensity` | always applied | applies only with `EnvironmentSource.Hdr` — Default/Color have no IBL |
| `onFrame` | Filament frame callback | filament-kmp `OnFrame` (Compose frame clock that drives the offscreen readback). Not invoked when the callback is null |

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
