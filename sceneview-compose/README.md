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

The reason is measurable. Android exposes 31 node types, the Swift API 20, the web one
~10; the honest intersection of all three is **four** — Camera, Geometry, Light, Model.
An API that promised more would be a lowest-common-denominator that lies about every
platform it covers.

### What is here

- glTF / GLB loading from an asset, from bytes, or from a URL
- Orbit camera with hoistable, saveable state
- A directional key light and an environment (default, flat colour, or HDR)
- Tap hit-testing against the model

### What is deliberately not here, ever

| | Use instead |
|---|---|
| **AR** | `io.github.sceneview.arsceneview` on Android, `ARSceneView` on Apple |
| Custom materials, shaders, post-processing | the platform-native API |
| Splat, Video, View, ContactShadow, Physics, Text nodes | the platform-native API |

AR is the important one: 77 of the 178 Kotlin files in `arsceneview` import ARCore
directly, with its types in public signatures, and Apple's equivalent is ARKit. There is
no honest common shape.

## One API, several renderers

| Platform | Renderer | Status |
|---|---|---|
| Android | Filament, via `io.github.sceneview.SceneView` | ✅ implemented |
| iOS | RealityKit, via `SceneViewSwift` | ⏳ placeholder — needs an `@objc` `UIView` façade on the Swift side |
| Desktop (JVM) | Filament, via the binding vendored in `third_party/filament-kmp/` | ⏳ placeholder — needs the native build chain |

Unimplemented platforms render a visible placeholder naming the platform and the reason,
not an empty viewport — a blank box is indistinguishable from a model that failed to
load.

**Pixel output differs between platforms.** Lighting values map approximately between
Filament and RealityKit, not exactly, and materials do not carry across engines. If you
need reproducible output, ship an HDR environment and use it everywhere.

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
