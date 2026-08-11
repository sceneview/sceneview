# Performance Guide

Ship smooth 60fps 3D and AR experiences on Android. This guide covers profiling,
asset optimization, scene tuning, Compose best practices, and device-tier strategies
for the SceneView SDK.

---

## Hot Paths & Allocation-Free APIs

This is the **single most common performance bug** in SceneView apps, and the one
that AI assistants reintroduce most often. A cross-platform [hot-path audit
(umbrella #2263)](https://github.com/sceneview/sceneview/issues/2263) found the
exact same anti-pattern on all five platforms: **getters that decompose a matrix
or allocate are far too easy to call once per frame.** They cost almost nothing
when called occasionally, so the cost is invisible in code review — but in a
render-rate loop they melt your frame budget.

!!! danger "The rule"
    **Never call a decomposing or allocating getter inside `onFrame`,
    `onSessionUpdated`, an `update` closure, a `requestAnimationFrame` tick, or any
    other per-frame loop.** Read once, cache outside the loop, or use the
    allocation-free overload. The frame loop runs **30–60 times per second** — one
    stray `FloatArray(16)` there is thousands of garbage objects per second.

### Why it matters

Three distinct failure modes, all from the same root cause:

- **Float drift.** Decomposing a matrix into components and recomposing it every
  frame is lossy. The trigger for this whole audit ([#2187](https://github.com/sceneview/sceneview/issues/2187))
  was `node.quaternion = q` round-tripping through the world matrix → measurable
  rotational drift over time. Writing one TRS component at a time forces a
  decompose-edit-recompose cycle; set the whole `transform` once instead.
- **GC sawtooth.** Per-frame allocation produces the classic memory-profiler
  sawtooth: allocate → GC pause → jank. It is worst on **iOS Safari / mobile web**,
  where the JS heap is small and a collection mid-`rAF` drops a frame outright.
- **JNI / bridge thunks.** On Android each Filament world-matrix read is a JNI
  round-trip; doing it three times per frame per node (position + rotation +
  scale) is three thunks where one cached read would do.

### Cheat sheet — avoid in the hot path → use instead

=== "Android (Kotlin / Compose)"

    | Avoid per frame | Use instead | Why |
    |---|---|---|
    | Setting `node.position` / `node.quaternion` / `node.rotation` / `node.scale` individually in a loop | Build a `Transform` (`Mat4`) and assign `node.transform = …` **once** | Each component setter recomposes the matrix; one-at-a-time writes also round-trip and drift (#2187) |
    | `Mat4.toColumnsFloatArray()` for a per-frame uniform / `TransformManager.setTransform` upload | `Mat4.copyColumnsInto(scratch)` into a reused `FloatArray(16)` (also `Mat3.copyColumnsInto`) | `toColumnsFloatArray()` allocates a fresh array every call (#2271) |
    | `slerp(startTransform, endTransform, …)` when you already hold the TRS components | the TRS-tuple overload `slerp(startPosition, startQuaternion, startScale, …)` | The `Transform` overload runs **6 matrix decompositions** per call (#2265) |
    | Re-loading a model / re-creating a node per recomposition | `rememberModelInstance(modelLoader, "models/x.glb")`, `rememberNode { … }` | They cache and marshal Filament JNI onto the main thread for you |

    `node.worldPosition` / `worldQuaternion` / `worldScale` / `worldRotation` are
    **now cached** (#2264) — reading them per frame is fine. They invalidate
    automatically when the transform or parenting changes.

=== "AR (ARCore)"

    | Avoid per frame | Use instead | Why |
    |---|---|---|
    | `pose.transform` inside `onSessionUpdated` | `pose.toTransform(scratch)` with a reused `FloatArray(16)`, or just feed the pose to a node/anchor setter | The `Pose.transform` getter allocates a `FloatArray(16)` every read (#2266) |
    | Allocating collections — `frame.getUpdatedPlanes().map { … }`, `listOf(...)`, `filter` — every frame | Hoist the references out of the callback; iterate without building new lists | `onSessionUpdated` runs every frame; per-frame collections are pure GC pressure |

    Anchor / `PoseNode` setters already do the matrix work internally — let them,
    rather than reading `pose.transform` yourself.

=== "KMP collision (sceneview-core)"

    | Avoid per frame | Use instead | Why |
    |---|---|---|
    | A fresh `Ray(origin, direction)` per pick/cast | Reuse one `Ray` and `setOrigin(...)` / `setDirection(...)` | Each `Ray` and each `getOrigin()` / `getDirection()` returns a **new `Vector3` copy** |
    | Allocating per triangle in a mesh-intersection loop | Reuse scratch `Vector3` / arrays across the loop | Triangle loops run thousands of iterations per cast |

=== "Web (Filament.js)"

    | Avoid per frame | Use instead | Why |
    |---|---|---|
    | Fresh `[x, y, z]` / `float3(...)` / mat4 array literals every `requestAnimationFrame` tick | Preallocate scratch arrays once and **mutate in place** before the call | Filament.js reads the array synchronously, so reuse is safe; fresh literals = GC sawtooth on Safari |

    SceneView's own `OrbitCameraController` already does this — it keeps
    `eyeScratch` / `centerScratch` / `upScratch` and rewrites them per frame instead
    of allocating (#2268). Follow the same pattern in your render loop.

=== "Apple (SwiftUI / RealityKit)"

    | Avoid per frame | Use instead | Why |
    |---|---|---|
    | Driving `@State` from a per-frame `onFrame` / RealityKit `update` closure | Mutate a reference box (a `class` you hold), or subscribe to scene events, and only flip `@State` when UI-visible state actually changes | A `@State` write every frame churns the SwiftUI view `body` — the per-frame loop should touch entities, not view state |

### One-line takeaway

> Per-frame code reads cached values and writes whole transforms. If a getter's
> name implies a decomposition (`world*`, `*.transform`) or returns a fresh array
> or object, hoist it out of the loop or use its allocation-free overload.

---

## Measuring Performance

Before optimizing, measure. Guessing where time is spent leads to wasted effort.

### Frame budget

At 60fps your app has **16.6ms per frame** for everything: CPU logic, GPU rendering,
Compose layout, and ARCore tracking. Anything over that budget causes dropped frames
and visible jank.

!!! info "The 16.6ms rule"
    60fps = 1000ms / 60 = **16.6ms per frame**. That includes CPU work, GPU rendering,
    and any Compose recomposition. Aim for headroom — target **12ms** so spikes don't
    push you over.

### Android Studio Profiler

Use the built-in profiler in Android Studio to identify bottlenecks:

- **CPU Profiler** — look for long `onFrame` or `onSessionUpdated` calls, excessive
  allocations, or blocking I/O on the main thread.
- **GPU Profiler** — check for overdraw (red = 4x overdraw), long fragment shader
  times, or GPU-bound frames.
- **Memory Profiler** — watch for repeated allocations each frame (GC pauses cause
  jank). Look for leaked `ModelInstance` or `Material` objects.

### Filament debug stats

Enable Filament's built-in frame statistics to see draw calls, triangle counts, and
GPU timing without leaving your app:

```kotlin
SceneView(
    engine = engine,
    modelLoader = modelLoader,
    // ...
) {
    // Access the Filament view for debug options
}
```

!!! tip "Quick debug overlay"
    Use `adb shell dumpsys gfxinfo <package>` for a quick frame-time histogram
    without any code changes.

---

## Model Optimization

Models are usually the biggest performance lever. A poorly optimized model can
single-handedly destroy your frame rate.

### Polygon count

| Target | Triangle budget |
|---|---|
| Interactive objects | < 100K triangles |
| Hero/showcase models | < 200K triangles (high-end only) |
| Background/environment | < 50K triangles |

!!! warning "Triangles add up fast"
    A single model might be 50K triangles, but if you have 10 in the scene that is
    500K — well beyond mobile budgets. Always count **total scene triangles**.

**Reduction tools:**

- **Blender** — Decimate modifier (collapse or un-subdivide)
- **meshoptimizer** — `meshopt_simplify` for automated LOD generation
- **gltfpack** — CLI tool that simplifies, compresses, and optimizes glTF/GLB files

Use **LOD (Level of Detail)** when available: show high-poly when the camera is close,
swap to low-poly at distance. This can cut triangle count by 50-80% for complex scenes.

### Textures

Textures consume the most GPU memory and bandwidth on mobile.

| Rule | Recommendation |
|---|---|
| Format | **KTX2** with Basis Universal compression |
| Max size | **2048x2048** for mobile (1024x1024 for low-end) |
| Mipmaps | Always enable for objects viewed at varying distances |
| Channels | Use single-channel textures for roughness/metallic, not full RGBA |

!!! tip "KTX2 saves memory and load time"
    KTX2 with Basis Universal (ETC1S or UASTC) compresses textures 4-8x compared to
    raw PNG/JPEG, and they stay compressed in GPU memory. Convert with
    `toktx --t2 --bcmp input.png output.ktx2`.

### File size

Smaller files mean faster loading and less memory pressure:

| Target | Size |
|---|---|
| Interactive models | < 10MB |
| Hero/showcase models | < 50MB |
| Quick-load previews | < 2MB |

**Optimization checklist:**

- [x] Use **GLB** (binary glTF) instead of glTF + separate .bin/.png files
- [x] Enable **Draco geometry compression** for mesh data
- [x] Strip unused animations, blend shapes, and extra UV sets
- [x] Run `gltfpack` as a final optimization pass

```bash
# Example: optimize a model with gltfpack
gltfpack -i model.glb -o model_optimized.glb -tc -cc -si 0.5
# -tc = texture compression, -cc = codec compression, -si = simplification ratio
```

---

## Scene Optimization

### Limit draw calls

Each visible node typically generates one or more draw calls. On mobile, aim for
**fewer than 100 draw calls** per frame.

- **Fewer separate nodes** = fewer draw calls. Merge static geometry in your 3D
  tool before export.
- **Use instancing** for repeated objects (trees, rocks, particles). Filament
  supports GPU instancing for identical meshes.
- **Frustum culling** is automatic in Filament — objects outside the camera view
  are not rendered. But they still cost CPU time if they exist as nodes.

!!! example "Merge before export"
    If you have 50 static building meshes in Blender, join them into one object
    before exporting to GLB. This turns 50 draw calls into 1.

### Lights

Lights are one of the most expensive parts of a scene. Each additional light
increases per-fragment shading cost.

| Light type | Cost | Recommendation |
|---|---|---|
| Directional (sun) | Low | Use 1 as your main light |
| Point / Spot | Medium | Limit to 2-3 total |
| Shadow-casting | High | Limit to 1-2 lights with shadows |

```kotlin
val mainLight = rememberMainLightNode(engine) {
    intensity = 100_000f
    // Shadows on the main light only
}
```

!!! tip "Use IBL instead of many point lights"
    Image-Based Lighting (IBL) from an HDR environment map provides realistic ambient
    lighting at nearly zero per-frame cost. One directional light + IBL covers most
    use cases better than 5+ point lights.

```kotlin
val environment = rememberEnvironment(environmentLoader) {
    createHDREnvironment("environments/studio.hdr")
        ?: createEnvironment(environmentLoader)
}
```

### Post-processing

Post-processing effects look great but eat into your frame budget:

| Effect | Typical cost | Notes |
|---|---|---|
| Bloom | ~1-2ms | Acceptable on mid-tier and above |
| Depth of Field | ~1-2ms | Use sparingly, mainly for screenshots |
| SSAO | ~2-3ms | Most expensive — skip on low-end devices |
| Anti-aliasing (FXAA) | ~0.5ms | Cheap, usually worth enabling |

!!! warning "SSAO on budget devices"
    Screen-Space Ambient Occlusion is the most expensive post-process effect. On
    low-end devices it can take 3ms+ alone — nearly 20% of your frame budget.
    Disable it on devices below your mid-tier threshold.

Enable effects selectively based on device tier (see [Device Tiers](#device-tiers)
below).

---

## Compose Integration

SceneView is a Jetpack Compose library, so Compose performance rules apply directly.

### Avoid unnecessary recompositions

Recomposition during rendering can cause frame drops. Follow these rules:

```kotlin
// BAD — creates new Position every recomposition, triggering node updates
SceneView(/* ... */) {
    ModelNode(
        modelInstance = model,
        position = Position(0f, 1f, 0f)  // new object every recomposition!
    )
}

// GOOD — stable reference, no unnecessary updates
val position = remember { Position(0f, 1f, 0f) }

SceneView(/* ... */) {
    ModelNode(
        modelInstance = model,
        position = position
    )
}
```

!!! danger "No allocations in the composition body"
    Never create new `Position`, `Rotation`, `Scale`, or `Quaternion` objects
    directly inside `SceneView { }` without `remember`. Each recomposition creates
    a new instance, causing the node to update every frame.

**Key rules:**

- Use `remember` for stable `Position`, `Rotation`, and `Scale` references
- Use `key` on model instances to avoid unnecessary reload when list order changes
- Use `derivedStateOf` when computing values from other state

### Share the Engine

The Filament `Engine` is expensive to create. Never create more than one.

```kotlin
// At the app/activity level
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
val materialLoader = rememberMaterialLoader(engine)
val environmentLoader = rememberEnvironmentLoader(engine)

// Share across all scenes via CompositionLocal or parameter passing
SceneView(engine = engine, modelLoader = modelLoader, /* ... */) { }
```

!!! failure "One Engine per app — not per screen"
    Creating multiple `Engine` instances wastes GPU memory and can cause crashes
    on devices with limited resources. Create one at the top level and pass it down.

### Lazy loading

Load models on-demand rather than all at startup:

```kotlin
// rememberModelInstance loads asynchronously and returns null while loading
val model = rememberModelInstance(modelLoader, "models/character.glb")

SceneView(/* ... */) {
    if (model != null) {
        ModelNode(modelInstance = model)
    } else {
        // Show a placeholder while loading
        CubeNode(materialInstance = placeholderMaterial)
    }
}
```

- `rememberModelInstance` handles async loading and main-thread marshalling correctly
- Show placeholder geometry (a simple cube or spinner) while the model loads
- For imperative code outside Compose, use `modelLoader.loadModelInstanceAsync`

!!! warning "Threading: Filament calls must be on the main thread"
    Never call `modelLoader.createModel*` or `materialLoader.*` from a background
    coroutine. `rememberModelInstance` handles this automatically. For imperative
    code, use `loadModelInstanceAsync`.

### Stop rendering an idle scene

A `SceneView` renders every vsync for as long as it is composed, whether or not anything
changed. Compose's frame clock does not idle on its own, so a screen showing a static model —
a product viewer nobody is touching, a board game between turns — keeps the GPU drawing an
identical frame 60 or 120 times a second. On devices whose Choreographer keeps ticking a
visually static UI it is a measurable source of battery drain and thermal throttling (#3108).

Pass `isRendering = false` to park the frame loop:

```kotlin
SceneView(isRendering = sceneIsDirty) { /* … */ }
```

The loop *suspends* while paused, so an idle scene schedules no work at all — it does not
trade GPU frames for a polling timer — and it resumes on the recomposition that passes `true`,
with no restart and no dropped frame.

!!! warning "Nothing is presented while `isRendering = false`"
    A node added or moved, a camera or manipulator change, a material edit, a model that
    finishes loading and a viewport resize all leave the **last drawn frame** on screen until
    rendering resumes. `onFrame` does not fire either.

    So derive the flag from "is anything dirty", holding it `true` for at least one frame after
    the last mutation — **not** from "is an animation running". An animation flag is already
    `false` at the instant a one-shot change is published, which strands that change undrawn:

    ```kotlin
    // WRONG — a new position, a highlight, a finished load is never drawn.
    SceneView(isRendering = isAnimating) { /* … */ }

    // RIGHT — every mutation extends a dirty window that outlives it.
    val dirty = isAnimating || isInteracting || System.nanoTime() < dirtyUntilNanos
    SceneView(isRendering = dirty) { /* … */ }
    ```

    The first frame is the easiest one to lose: a scene whose content is set once, before any
    animation runs, never renders at all if the flag starts `false`.

---

## AR-Specific Optimization

AR adds the camera feed and ARCore tracking to your frame budget, leaving less room
for rendering.

### Camera frame processing

`onSessionUpdated` runs **every single frame**. Any work you do here directly
impacts frame rate.

```kotlin
ARSceneView(
    // ...
    onSessionUpdated = { session, frame ->
        // FAST: read a cached value
        val planes = frame.getUpdatedPlanes()

        // SLOW: don't do this!
        // val bitmap = frame.acquireCameraImage().toBitmap()  // allocation + conversion
    }
)
```

!!! danger "No allocations in onSessionUpdated"
    This callback runs 30-60 times per second. Allocating objects here causes GC
    pressure and frame drops. Cache references outside the callback and reuse them.

**Rules for `onSessionUpdated`:**

- Do not allocate objects (no `listOf`, `map`, `filter` on every frame)
- Cache plane and anchor references outside the callback
- If you need heavy processing (image analysis, ML), dispatch to a background
  thread and read results on the next frame

### Plane rendering

The AR plane renderer draws detected surfaces with a semi-transparent overlay.
This causes **GPU overdraw** — especially problematic when multiple planes overlap.

```kotlin
ARSceneView(
    planeRenderer = planeRenderer,
    // ...
)

// Disable after the user has placed their object
LaunchedEffect(objectPlaced) {
    if (objectPlaced) {
        planeRenderer.isVisible = false  // reduces overdraw
    }
}
```

!!! tip "Disable planes after placement"
    Once the user has placed an object, disable `planeRenderer`. This removes
    overdraw from plane visualization and saves 1-2ms per frame on most devices.

---

## Device Tiers

Not all Android devices are equal. Adapt your scene complexity based on hardware
capability.

| Tier | Example devices | Triangle budget | Post-processing | Shadows |
|---|---|---|---|---|
| **High** | Pixel 8 Pro, Samsung S24, OnePlus 12 | 200K triangles | Full (Bloom, SSAO, DoF) | 2 shadow-casting lights |
| **Mid** | Pixel 6a, Samsung A54, Pixel 7 | 100K triangles | Basic (Bloom only) | 1 shadow-casting light |
| **Low** | Older budget phones, 2GB RAM devices | 50K triangles | None | No shadows |

### Detecting device tier

```kotlin
fun getDeviceTier(context: Context): DeviceTier {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
        as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)

    val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

    return when {
        totalRamGb >= 8.0 -> DeviceTier.HIGH
        totalRamGb >= 4.0 -> DeviceTier.MID
        else -> DeviceTier.LOW
    }
}

enum class DeviceTier { HIGH, MID, LOW }
```

!!! note "RAM is a proxy, not a guarantee"
    Total RAM is a rough proxy for device capability. For more accurate tiering,
    also consider GPU model (`GLES20.glGetString(GLES20.GL_RENDERER)`), Android
    version, and the [Android Performance Tuner](https://developer.android.com/games/sdk/performance-tuner)
    library.

### Applying tiers

```kotlin
val tier = remember { getDeviceTier(context) }

SceneView(
    engine = engine,
    modelLoader = modelLoader,
    // ...
) {
    // Adjust quality based on tier
    val modelPath = when (tier) {
        DeviceTier.HIGH -> "models/character_high.glb"
        DeviceTier.MID -> "models/character_mid.glb"
        DeviceTier.LOW -> "models/character_low.glb"
    }

    val model = rememberModelInstance(modelLoader, modelPath)
    if (model != null) {
        ModelNode(modelInstance = model)
    }
}
```

---

## Performance Checklist

Use this checklist before shipping:

- [ ] **Profile first** — measured with Android Studio Profiler, not guessing
- [ ] **Models** — all models under 100K triangles (per model), total scene under 200K
- [ ] **Textures** — KTX2 compressed, max 2048x2048, mipmaps enabled
- [ ] **File size** — GLB format, Draco compressed, under 10MB for interactive models
- [ ] **Draw calls** — under 100 per frame, static geometry merged
- [ ] **Lights** — 1 directional + IBL, max 2-3 additional point/spot lights
- [ ] **Shadows** — limited to 1-2 shadow-casting lights
- [ ] **Post-processing** — adapted to device tier, SSAO disabled on low-end
- [ ] **Compose** — no allocations in composition body, `remember` for stable refs
- [ ] **Idle scenes** — `isRendering = false` when nothing changes, driven by a dirty
      signal that outlives the last mutation by a frame (not by an animation flag)
- [ ] **Engine** — single shared Engine, ModelLoader, and MaterialLoader
- [ ] **AR callbacks** — no allocations in `onSessionUpdated`
- [ ] **Plane renderer** — disabled after object placement
- [ ] **Device tiers** — different asset quality levels for high/mid/low devices
