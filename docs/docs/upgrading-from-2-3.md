---
title: Upgrading from 2.3.x
description: "Move an Android app from SceneView 2.3.0 or 2.3.1 to 2.3.4, then to 4.x: API changes with before/after code, the Filament and ARCore updates, and the Engine.nDestroyEngine ANR."
---

# Upgrading from SceneView 2.3.x

If your Android app still ships SceneView 2.3.x, this page takes it forward in two steps:

1. **2.3.0 / 2.3.1 → 2.3.4.** Same API family: a dependency bump, newer build floors and a few
   small edits. It brings the newer Filament and ARCore, fixes a crash on a failed ARCore update,
   and stops the main thread from waiting on Filament's teardown, the cause of the
   `Engine.nDestroyEngine` ANR, in views that create their own engine. Coming from 2.3.2 or 2.3.3,
   only the version number changes: Filament 1.68.2 and ARCore 1.52.0 are already the ones 2.3.4
   ships, and sections 1.2 to 1.6 do not apply.
2. **2.3.4 → 4.x.** The Compose-only API. More work, covered here in summary and in detail in the
   [Migration Guide](migration.md#sceneview-2x-to-3x).

Go straight from 2.3.4 to the latest 4.x. Do not stop at 3.x: 3.0.0 ships Filament **1.56.0**,
older than the 1.68.2 of 2.3.2 to 2.3.4.

## What each release pins

Read from each release's published POM on Maven Central.

| Release | Filament | ARCore | Kotlin stdlib it depends on | Notes |
|---|---|---|---|---|
| 2.3.0 | 1.56.0 | 1.48.0 | 2.0.21 | |
| 2.3.1 | 1.66.0 | 1.51.0 | 2.2.21 | Materials regenerated, Fuel 3 |
| 2.3.2 | 1.68.2 | 1.52.0 | 2.3.0 | `keepScreenOn` moves to the view |
| 2.3.3 | 1.68.2 | 1.52.0 | 2.2.21 | Kotlin back to 2.2.x for older toolchains |
| **2.3.4** | 1.68.2 | 1.52.0 | 2.2.21 | ANR and ARCore-update fixes |
| 3.0.0 | 1.56.0 | 1.53.0 | 2.1.21 | Compose rewrite |
| 4.0.0 | 1.71.0 | 1.53.0 | 2.3.20 | |
| 4.67.0 | 1.77.3 | 1.57.0 | 2.4.21 | Latest at the time of writing |

`sceneview` exposes Filament and `arsceneview` exposes ARCore as `api` dependencies: your app gets
these versions transitively. If your build also declares `com.google.ar:core` or
`com.google.android.filament:*`, remove that line or set it to the version in this table, so that
what your build file says matches what ships.

---

## Step 1 — 2.3.0 / 2.3.1 to 2.3.4

### 1.1 Dependency

```kotlin
// Before
implementation("io.github.sceneview:sceneview:2.3.0")
implementation("io.github.sceneview:arsceneview:2.3.0")

// After
implementation("io.github.sceneview:sceneview:2.3.4")
implementation("io.github.sceneview:arsceneview:2.3.4")
```

The View API (`SceneView`, `ARSceneView` as Android views) and the Compose API (`Scene {}`,
`ARScene {}`, `childNodes = rememberNodes { }`) stay the same across 2.3.x, apart from the edits
below. Each one only applies if your app uses the feature it names.

### 1.2 Build requirements

2.3.0 builds with Java/JVM target 17 and Kotlin 2.0.21. From 2.3.1, SceneView is compiled for
JVM 21 and depends on Kotlin 2.2, so the app that uses 2.3.4 needs:

| Setting | 2.3.0 | 2.3.4 |
|---|---|---|
| JVM target (Java and Kotlin) and JDK | 17 | **21** |
| Kotlin | 2.0 | **2.1 or newer** (2.2 recommended) |
| `compileSdk` with `arsceneview` | 35 | **36** (its `androidx.core` 1.17.0 requires it) |
| Android Gradle Plugin with `arsceneview` | 8.x | **8.9.1 or newer** |

The JVM target matters because SceneView has public `inline` functions, and Kotlin refuses to
inline JVM 21 bytecode into a lower target.

```kotlin
android {
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
```

### 1.3 Filament 1.56.0 → 1.68.2

SceneView 2.3.1 moved to Filament 1.66.0 and regenerated every bundled `.filamat` with the
matching `matc` ([#640](https://github.com/sceneview/sceneview/pull/640)); 2.3.2 moved to 1.68.2
([#651](https://github.com/sceneview/sceneview/pull/651)).

**Your own materials.** A `.filamat` is compiled for one Filament material version. If your app
ships materials of its own, recompile them with `matc` from the
[Filament 1.68.2 release](https://github.com/google/filament/releases/tag/v1.68.2): the bundled
materials go from material version 56 in 2.3.0 to 68 in 2.3.4, and Filament does not load a
material built for another version.

**`KTX1Loader` returns bundles.** If you call Filament's `KTX1Loader` yourself, take the object out
of the returned bundle, as SceneView's `EnvironmentLoader` does since 2.3.1:

```kotlin
// Before (Filament 1.56)
val indirectLight = KTX1Loader.createIndirectLight(engine, iblBuffer)
val skybox = KTX1Loader.createSkybox(engine, skyboxBuffer)

// After (Filament 1.66 and later)
val indirectLight = KTX1Loader.createIndirectLight(engine, iblBuffer).indirectLight
val skybox = KTX1Loader.createSkybox(engine, skyboxBuffer).skybox
```

**Textures you build yourself.** SceneView 2.3.1 added `Texture.Usage.GEN_MIPMAPPABLE` to the
textures it creates (`ImageTexture`, `LightEstimator`, `ARCameraStream`). If your code builds a Filament `Texture`
and generates its mipmaps, declare that usage too:

```kotlin
Texture.Builder()
    .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
    .levels(0xff)
    .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
    .format(Texture.InternalFormat.R11F_G11F_B10F)
    .build(engine)
```

### 1.4 ARCore 1.48.0 → 1.52.0

No SceneView API change comes with it. If your build declares `com.google.ar:core` itself, use
1.52.0 or above.

### 1.5 `FileLoader.fuelManager` → `FileLoader.httpLoader` (2.3.1)

2.3.1 moved the HTTP client from Fuel 2.3.1 to Fuel 3.0.0-alpha04
([#641](https://github.com/sceneview/sceneview/pull/641)). The property that lets you configure
it changed name and type. Fuel is an `implementation` dependency of SceneView: to configure it,
declare the same Fuel version in your app.

```kotlin
// Before (2.3.0) — Fuel 2
FileLoader.fuelManager = FuelManager().apply {
    baseHeaders = mapOf("Authorization" to "Bearer $token")
}

// After (2.3.1 to 2.3.4) — Fuel 3, backed by OkHttp
FileLoader.httpLoader = FuelBuilder()
    .config(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                )
            }
            .build()
    )
    .build()
```

Behaviour change: when an `http`/`https` load fails, `FileLoader.loadFileBuffer` now logs the
error and returns `null` instead of throwing. Code that caught the exception to show an error
should check for `null` instead.

If you are going on to 4.x, skip this section: 4.x is back on Fuel 2 and `fuelManager`
(section 2.6).

### 1.6 Keep-screen-on (2.3.2)

In 2.3.0 and 2.3.1, `SceneView` set `FLAG_KEEP_SCREEN_ON` on the whole Activity window when it
resumed. From 2.3.2, it sets `keepScreenOn = true` on the view itself when it is created
([#645](https://github.com/sceneview/sceneview/pull/645)): the screen stays on only while the
view is shown, and you can turn it off.

```kotlin
// View API
val sceneView = SceneView(context).apply {
    keepScreenOn = false
}
```

```kotlin
// Compose API (2.3.x)
Scene(
    onViewCreated = { keepScreenOn = false }
)
```

### 1.7 A failed ARCore update no longer crashes the app (2.3.4)

`ARSceneView.onFrame` called `Session.update()` without a guard, so an exception from ARCore (for
example a `FatalException` from its native side, or the camera being taken away) ended the
process. Since 2.3.4 that frame's AR update is skipped and logged once, the scene keeps
rendering, and the next frame tries again
([#4403](https://github.com/sceneview/sceneview/pull/4403)). It is not reported through
`onSessionFailed`, which keeps meaning that the session could not be created.

If you wrapped `onFrame` in a `try` in a subclass to work around this, you can remove it.
Exceptions thrown by your own `onSessionUpdated` still reach your app.

### 1.8 The `Engine.nDestroyEngine` ANR

**What it looks like.** An "Application Not Responding" report, often when a Dialog, Fragment or
Activity that holds a `SceneView` is closed, with the main thread here:

```text
pthread_join
  at com.google.android.filament.Engine.nDestroyEngine
  at com.google.android.filament.Engine.destroy
  at io.github.sceneview.EngineKt.safeDestroy
  at io.github.sceneview.SceneView.destroy
  at io.github.sceneview.SceneView.onDetachedFromWindow
```

Variants of the same wait: `Engine.nDestroyRenderer` instead of `nDestroyEngine`,
`Engine.flushAndWait` from `SurfaceCallback.onDetachedFromSurface`, and `Fence.wait` from
`SurfaceCallback.onResized`.

**Why it happens.** Filament renders on its own driver thread, which runs a queue of commands.
`Engine.destroy()` asks that thread to stop and joins it once it has run everything queued.
`Engine.destroyRenderer()` and `Engine.flushAndWait()` wait for the same queue. Up to 2.3.3,
`SceneView` called all three on the main thread with no time limit. When the queue is long —
typically a view closed right after it appeared, while its shaders are still being compiled —
the main thread waits past the ANR threshold.

**What 2.3.4 fixes.** For a `SceneView` or `ARSceneView` that creates its own engine (the default
with the View API):

- `destroy()` no longer blocks: it frees what it can at once, then destroys the renderer, the
  loaders, the engine and the EGL context, in the same order as before, as soon as Filament has
  run its queued work. With nothing queued, the usual case, all of it still happens before
  `destroy()` returns.
- `onDetachedFromSurface` waits at most 1 s, `onResized` at most 500 ms, and each logs a warning
  when it stops waiting.

Upgrading to 2.3.4 is enough for this path; no code change is needed.

**What 2.3.4 does not fix.** Compose `Scene {}` and `ARScene {}`, and any view that you give a
shared engine, wait exactly as in 2.3.3: `rememberEngine()` and the other `remember*` helpers still
destroy their objects synchronously when the composition leaves. If your ANR reports come from
Compose screens, the fix is in **4.40.0 and later**
([#3799](https://github.com/sceneview/sceneview/issues/3799)): `rememberEngine` destroys the
engine once the queued work is done, without blocking the UI thread, surface detach waits at
most 1 s and resize at most 250 ms. That is a reason to take Step 2.

---

## Step 2 — 2.3.4 to 4.x

### 2.1 Build requirements

4.x raises the floor of the app that depends on it. For 4.67.0:

| Setting | Value |
|---|---|
| `compileSdk` | 37 (the AARs declare `minCompileSdk=37`) |
| `minSdk` | 24, unchanged |
| Android Gradle Plugin | 9.4.0 or newer |
| Kotlin | 2.4 |
| JVM target (Java and Kotlin) and JDK | 21 |

The [Quickstart](quickstart.md#requirements) explains each line and gives the
`build.gradle.kts` block.

```kotlin
// Before
implementation("io.github.sceneview:sceneview:2.3.4")
implementation("io.github.sceneview:arsceneview:2.3.4")

// After
implementation("io.github.sceneview:sceneview:4.67.0")
implementation("io.github.sceneview:arsceneview:4.67.0")
```

### 2.2 Filament 1.68.2 → 1.77.3, ARCore 1.52.0 → 1.57.0

As in Step 1: recompile your own `.filamat` with `matc` from
[Filament 1.77.3](https://github.com/google/filament/releases/tag/v1.77.3), and drop or raise any
Filament or ARCore pin in your build.

### 2.3 The Android views are gone: SceneView is Compose only

Since 3.0.0 there is no `FrameLayout` subclass to inflate from XML or build with a `Context`; in
4.x the composables are `SceneView` and `ARSceneView`. An app built on views hosts the scene in a
`ComposeView`:

```kotlin
// Before (2.3.x) — a View
val sceneView = SceneView(context)
container.addView(sceneView)
sceneView.addChildNode(
    ModelNode(
        modelInstance = sceneView.modelLoader.createModelInstance("models/helmet.glb"),
        scaleToUnits = 1.0f
    )
)
```

```kotlin
// After (4.x) — a composable, hosted in a ComposeView
container.addView(
    ComposeView(context).apply {
        setContent {
            val engine = rememberEngine()
            val modelLoader = rememberModelLoader(engine)
            val helmet = rememberModelInstance(modelLoader, "models/helmet.glb")
            SceneView(engine = engine, modelLoader = modelLoader) {
                helmet?.let { ModelNode(modelInstance = it, scaleToUnits = 1.0f) }
            }
        }
    }
)
```

`rememberModelInstance` loads in the background and returns `null` until the model is ready, so
the node appears once it is loaded.

### 2.4 Compose: `Scene` → `SceneView`, `childNodes` → content block

The 2.3.x composables were `Scene` and `ARScene`, fed with a node list. In 4.x they are
`SceneView` and `ARSceneView`, and nodes are composables declared in a trailing block. `Scene`
and `ARScene` still exist as deprecated aliases, but without `childNodes`.

```kotlin
// Before (2.3.x)
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
Scene(
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
    }
)
```

```kotlin
// After (4.x)
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
val helmet = rememberModelInstance(modelLoader, "models/helmet.glb")
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader
) {
    helmet?.let { ModelNode(modelInstance = it, scaleToUnits = 1.0f) }
}
```

Child nodes follow the same rule: `parent.addChildNode(child)` becomes a node declared inside its
parent's trailing block. AR anchors work the same way: the `AnchorNode` is declared in the
`ARSceneView` block and driven by state. The [Migration Guide](migration.md#sceneview-2x-to-3x)
walks through anchors, `ViewNode` and the other node types with before/after code.

### 2.5 Rendering on demand (4.38.0)

A 2.3.x scene drew a frame on every display refresh for as long as it was shown. Since 4.38.0 the
default is `FrameRatePolicy.OnDemand()`: the library draws while something changes (touch, camera,
animation, loading, a node moved) and stops otherwise. Most apps need no change.

If you drive an animation yourself from `onFrame`, by changing a Filament object directly, ask for
a frame on every refresh:

```kotlin
// Before (2.3.x) — every frame was drawn anyway
Scene(onFrame = { frameTimeNanos -> spin(frameTimeNanos) })

// After (4.x)
SceneView(
    frameRatePolicy = FrameRatePolicy.Continuous(),
    onFrame = { frameTimeNanos -> spin(frameTimeNanos) }
)
```

See [SceneView 4.37.x to 4.38.0](migration.md#sceneview-437x-to-4380-android-isrendering-replaced-by-frameratepolicy)
for what the library tracks and when `Continuous()` is the right choice.

### 2.6 `FileLoader.httpLoader` → `FileLoader.fuelManager`

4.x uses Fuel 2.3.1, like 2.3.0. If you moved to `FileLoader.httpLoader` for 2.3.1 to 2.3.4
(section 1.5), go back to `FileLoader.fuelManager`. A failed `http`/`https` load in
`FileLoader.loadFileBuffer` throws again, as in 2.3.0: put back the `try`/`catch` if you replaced
it with a `null` check. (`rememberModelInstance` still returns `null` on failure.)

```kotlin
// Before (2.3.1 to 2.3.4)
FileLoader.httpLoader = FuelBuilder().config(okHttpClient).build()

// After (4.x)
FileLoader.fuelManager = FuelManager().apply {
    baseHeaders = mapOf("Authorization" to "Bearer $token")
}
```

### 2.7 Keep-screen-on

In 4.x, `ARSceneView` keeps the screen on while it is composed and restores the previous setting
when it leaves. A 3D-only `SceneView` no longer does: if your viewer needs it, set it on the host
view, for example from a `DisposableEffect` that sets `LocalView.current.keepScreenOn` and
restores it on dispose.

### 2.8 The `nDestroyEngine` ANR in 4.x

Since 4.40.0 the Compose path no longer waits on the main thread for the engine to be destroyed,
including engines you create with `rememberEngine()`, and the surface waits are bounded (see
section 1.8). Nothing to change in your code.

---

## Checklist

| From | Change | Section |
|---|---|---|
| 2.3.0 / 2.3.1 | Bump to 2.3.4 | 1.1 |
| 2.3.0 | JVM target 21, Kotlin 2.1+; with `arsceneview`, `compileSdk` 36 and AGP 8.9.1+ | 1.2 |
| 2.3.0 | Recompile your own `.filamat` for Filament 1.68.2 | 1.3 |
| 2.3.0 | `KTX1Loader.create*(…)` → `.indirectLight` / `.skybox` | 1.3 |
| 2.3.0 | `FileLoader.fuelManager` → `FileLoader.httpLoader`; failed URL loads return `null` | 1.5 |
| 2.3.0 / 2.3.1 | Turn keep-screen-on off per view if needed | 1.6 |
| 2.3.4 | Raise `compileSdk`, AGP, Kotlin and JVM target | 2.1 |
| 2.3.4 | Recompile your own `.filamat` for Filament 1.77.3 | 2.2 |
| 2.3.4 | Views → composables, hosted in `ComposeView` if needed | 2.3 |
| 2.3.4 | `Scene` / `childNodes` → `SceneView { }` | 2.4 |
| 2.3.4 | Animation driven from `onFrame` → `FrameRatePolicy.Continuous()` | 2.5 |
| 2.3.4 | `FileLoader.httpLoader` → `FileLoader.fuelManager`; failed URL loads throw again | 2.6 |
