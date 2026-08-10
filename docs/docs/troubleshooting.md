# Troubleshooting

Common issues and fixes when working with SceneView.

---

## Build errors

### "Duplicate class" or dependency conflicts

SceneView pulls in Filament, ARCore, and Kotlin coroutines transitively. If another library in
your project brings in a different version, Gradle will report duplicate classes.

**Fix:**

- Use the Compose BOM to align Compose-related versions across your project.
- Exclude the conflicting transitive dependency from whichever library introduces it:

```kotlin
implementation("com.example:some-library:1.0") {
    exclude(group = "com.google.ar", module = "core")
}
```

- Run `./gradlew app:dependencies` to inspect the resolved dependency tree and identify the
  conflict source.

### NDK / CMake errors

SceneView bundles pre-built native libraries (Filament .so files). You do **not** need to install
the NDK or configure CMake yourself.

If you see NDK-related errors:

- Make sure you are not forcing an `ndkVersion` that conflicts with the bundled binaries.
- Run a clean build after switching ABIs or updating SceneView:

```bash
./gradlew clean assembleDebug
```

---

## Runtime errors

### Model not loading / null ModelInstance

`rememberModelInstance` returns `null` while the model is still loading **and** if the load fails.

**Checklist:**

1. **Verify the asset path.** Paths are relative to `src/main/assets/`. If your file is at
   `src/main/assets/models/helmet.glb`, pass `"models/helmet.glb"`.
2. **Confirm the file exists.** Open the APK in Android Studio's APK Analyzer and check that the
   file is present under `assets/`.
3. **Handle the null case.** The model is not available on the first frame:

```kotlin
rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
    ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
}
```

4. **Check Logcat.** Filter by `Filament` or `SceneView` — load failures are logged there.

### Model loads but renders untextured (WebP textures)

Filament's Android prebuilt ships `gltfio` with WebP support compiled out, so on its own it
cannot decode glTF textures encoded as `EXT_texture_webp` (`image/webp`) and logs
`Missing texture provider for image/webp` while the model renders untextured
([#2305](https://github.com/sceneview/sceneview/issues/2305)).

**On Android, SceneView handles this for you.** Since 4.28.0, `ModelLoader` re-encodes WebP
textures to PNG with Android's own decoder before handing the asset to Filament, so a WebP-textured
glTF loads with its textures. This costs one decode + PNG encode per texture at load time; a model
without WebP textures is untouched.

Two cases are still **not** covered, and each logs an actionable `SceneView` error rather than
failing silently:

- textures stored as **separate `.webp` files** next to a `.gltf` (only images embedded in a GLB
  buffer view or in a `data:` URI are converted);
- the **web build** — Filament.js registers no `image/webp` provider either.

**Fix for those:** re-encode the model's textures to PNG, JPEG, or KTX2, e.g.
`npx @gltf-transform/cli optimize model.glb out.glb --texture-compress png`, or re-export from
Blender with PNG/JPEG textures. PNG, JPEG, and KTX2 decode natively everywhere.

### Black screen / no rendering

A completely black or empty viewport usually means the scene is set up but nothing is visible to
the camera.

**Checklist:**

- **Environment:** If no HDR environment is set, the scene has no ambient light and everything
  appears black. Load an environment:

```kotlin
environment = rememberEnvironment(environmentLoader) {
    environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
        ?: createEnvironment(environmentLoader)
}
```

- **Engine:** Make sure `rememberEngine()` is called and passed to `Scene`. Without a valid engine,
  nothing renders.
- **SurfaceType:** If you are embedding the scene in a `TextureView` or composing it with other
  Compose layers, confirm you are using the correct `SurfaceType`. The default (`SurfaceType.SURFACE`)
  works for most cases; switch to `SurfaceType.TEXTURE_SURFACE` only when overlay compositing is
  required.

### Crash on background thread

Filament's JNI layer is **not thread-safe** and must be called on the **main thread**. A crash
with a native stacktrace (often in `libfilament-jni.so`) almost always means a Filament call ran
on the wrong thread.

**Rules:**

- **Use `rememberModelInstance`** in composables — it handles threading correctly.
- **Never call `modelLoader.createModelInstance()`** (or any `modelLoader` / `materialLoader`
  method) from `Dispatchers.IO` or any background coroutine.
- For imperative (non-Compose) code, use `modelLoader.loadModelInstanceAsync` which dispatches to
  the correct thread internally.

```kotlin
// WRONG — will crash
viewModelScope.launch(Dispatchers.IO) {
    val model = modelLoader.createModelInstance("models/helmet.glb") // native crash
}

// RIGHT — use the async helper
modelLoader.loadModelInstanceAsync("models/helmet.glb") { instance ->
    // already on main thread
}
```

### AR session fails to start

If the AR session never initialises or immediately throws an exception:

1. **Camera permission.** `ARScene` requires `android.permission.CAMERA`. Request it before
   displaying the AR composable.
2. **ARCore installed.** The device needs *Google Play Services for AR*. On devices that do not
   ship with it pre-installed, the user must install it from the Play Store.
3. **Physical device required.** ARCore has very limited emulator support. Always test AR features
   on a physical device.
4. **Manifest metadata.** Your `AndroidManifest.xml` must declare ARCore support:

```xml
<application>
    <meta-data android:name="com.google.ar.core" android:value="required" />
</application>
```

Set the value to `"optional"` if your app can function without AR.

---

## Performance

### Low FPS / jank

- **Reduce polygon count.** Use decimated or LOD versions of your models. Tools like Blender or
  `gltfpack` can optimise meshes.
- **Compress textures.** Convert textures to KTX2 with Basis Universal compression. This reduces
  GPU memory bandwidth and upload time.
- **Limit lights.** Each additional dynamic light adds rendering cost. One directional main light
  plus an HDR environment is sufficient for most scenes.
- **Avoid unnecessary recomposition.** If Compose state changes trigger recomposition of the
  `Scene` block, `rememberModelInstance` may re-execute. Keep scene-unrelated state outside the
  `Scene` content lambda.

### High memory usage

- **Share the engine.** If your app has multiple screens with 3D content, pass the same `Engine`
  instance to each `Scene` rather than creating a new one per screen.
- **Use `scaleToUnits`.** Models exported at real-world scale (e.g. a building at 50 m) allocate
  large bounding volumes. Set `scaleToUnits` on `ModelNode` to normalise them.
- **Dispose unused instances.** When a model is no longer displayed, make sure its `ModelInstance`
  is not held in a long-lived reference. Let the composable lifecycle handle disposal, or call
  `destroy()` manually in imperative code.

---

## Common mistakes

### LightNode: `apply` is a named parameter

`LightNode`'s `apply` block is a **named parameter**, not a trailing lambda. The compiler may
accept the trailing-lambda form without error, but the block will not execute.

```kotlin
// WRONG — compiles but the apply block is ignored
LightNode(type = LightManager.Type.SUN) {
    intensity(100_000f)
}

// RIGHT — use the named parameter
LightNode(type = LightManager.Type.SUN, apply = {
    intensity(100_000f)
})
```

### Missing null check on model load

`rememberModelInstance` returns `ModelInstance?`. Passing a nullable instance where a non-null is
expected causes a compile error — or worse, a force-unwrap crash at runtime.

```kotlin
// WRONG — force-unwrap can crash
val model = rememberModelInstance(modelLoader, "models/helmet.glb")!!

// RIGHT — safe handling
rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
    ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
}
```

### Washed-out or too-dark AR camera preview

On some devices, ARCore's auto-exposure setting does not match what Camera2 actually delivers,
producing a camera feed that looks overexposed (white / blown-out) or underexposed compared to the
device's native camera app.

**Fix — use `cameraExposure` to override the AR camera's EV:**

```kotlin
// Washed-out preview → lower the exposure
ARSceneView(
    cameraExposure = 1.0f   // positive = brighter; try -1.0f if preview is too dark
) { }
```

`null` (the default) keeps ARCore's built-in exposure tuning. Adjust in 0.5 EV steps until the
preview matches the device's native camera app. A value of `0f` is the standard middle-grey
reference exposure.

---

### Forgetting the HDR environment

A scene with no environment and no explicit light is completely dark. Always set at least one of:

- An HDR environment via `rememberEnvironment`
- A main light via `rememberMainLightNode`

---

## Still stuck?

- Search existing [GitHub Issues](https://github.com/sceneview/sceneview/issues) —
  many questions have already been answered.
- Open a new issue with your SceneView version, device model, Android version, and a minimal
  reproducible snippet.
