<!--
DRAFT for an issue on google/filament. Not posted: a third-party repo goes
through Thomas's click. Remove this file before #4930 (or its 1.79 successor)
merges.
-->

# [Android] IBLPrefilterContext output is black on any Engine other than the first one created in the process (1.77.3, OK on 1.72.1)

## Describe the bug

Since we moved from 1.72.1 to 1.77.3, converting an equirectangular HDR into an
IBL with `IBLPrefilterContext` (`EquirectangularToCubemap.run()` then
`SpecularFilter.run()`) gives an all-black cubemap whenever the conversion runs
on an `Engine` that is not the first one the process created. The skybox built
from that cubemap is black and the indirect light contributes nothing. The same
code on the first engine of the process renders correctly, including a second
conversion on that first engine.

Our app creates one `Engine` per screen (created on enter, destroyed on leave),
so on 1.77.3 every screen reached after the first loses its HDR lighting.

## To Reproduce

Minimal sequence (Kotlin, `filament-android` + `filament-utils-android` 1.77.3):

```kotlin
fun loadHdrIbl(engine: Engine, hdr: ByteBuffer): Pair<IndirectLight, Skybox> {
    val equirect = HDRLoader.createTexture(engine, hdr)!!
    val context = IBLPrefilterContext(engine)
    val toCube = IBLPrefilterContext.EquirectangularToCubemap(context)
    val specular = IBLPrefilterContext.SpecularFilter(context)
    val skyboxTex = toCube.run(equirect)
    val reflections = specular.run(skyboxTex)
    val ibl = IndirectLight.Builder().reflections(reflections).intensity(30_000f).build(engine)
    val skybox = Skybox.Builder().environment(skyboxTex).build(engine)
    return ibl to skybox
}

// 1. Engine A: create, render a scene lit by loadHdrIbl(engineA, hdr) -> correct.
// 2. Leave the screen: destroy A's entities, renderer, view, swap chain, engine.
// 3. Engine B (Engine.create()): same scene, same HDR, loadHdrIbl(engineB, hdr)
//    -> black sky, objects lit by direct light only.
```

Pre-filtered KTX environments (`KTX1Loader`) render correctly on engine B, so
the engine itself and the rendering path are fine; only the prefilter output is
black.

## Expected behavior

The IBL and skybox on engine B look the same as on engine A, as they do on
1.72.1.

## Root cause (our reading)

Bisected on Maven Central builds: 1.72.1 through 1.76.1 are fine, **1.77.0 is
the first bad version** (1.77.1, 1.77.2 and 1.77.3 are bad too). The
regression is not in the engine or the GL backend: it is in how the Android
AARs are linked.

- 9a01374c1 ("Fix linker flags", #10350) adds
  `CMAKE_SHARED_LINKER_FLAGS_RELWITHDEBINFO ... --version-script` in
  `android/filament-android/CMakeLists.txt` (line 90 at v1.77.0). Release AARs
  are RelWithDebInfo, so from 1.77.0 `libfilament-jni.so` stops exporting
  everything outside `libfilament-jni.map`, including all of `utils::*`
  (`llvm-nm -D --defined-only | grep -c _ZN5utils`: 213 in 1.76.1, 0 in 1.77.0).
- `libgltfio-jni.so` then links its own copy of `libutils` and exports it
  (`_ZN5utils13EntityManager3getEv` goes from `U` in 1.76.1 to `T` in 1.77.0).
  `libfilament-utils-jni.so` lists `libgltfio-jni.so` before
  `libfilament-jni.so` in its `NEEDED` entries and imports
  `utils::EntityManager::get()`, so it binds to the gltfio copy.
- The process now has two entity managers. `FEngine` uses the one inside
  `libfilament-jni.so` (`mEntityManager(EntityManager::get())`,
  `filament/src/details/Engine.cpp:290`), which is also what
  `EntityManager.get()` returns in Java. `IBLPrefilterContext`
  (`libs/iblprefilter/src/IBLPrefilterContext.cpp:103`) creates its camera and
  full-screen-triangle entities in the gltfio copy.
- `FScene::prepare` keeps only entities that are alive in the engine's manager
  (`filament/src/details/Scene.cpp:131`, `em.isAlive(e)`). The gltfio copy
  hands out generation 0 on slots 1, 2, 3… As long as the engine's manager has
  never destroyed an entity on those slots, the check passes by accident: that
  is the first engine. Once the first engine is torn down, its entities' slots
  carry generation 1 in the engine's manager, the prefilter's triangle is
  dropped as dead, nothing is drawn, and every cubemap the prefilter outputs is
  black.

Two checks back this up on 1.77.3:
- keeping the first engine alive while a second one prefilters still gives
  black on the second engine (its own entities share slots with the first
  engine's live ones), while a third engine created afterwards is correct;
- creating 64 entities with `EntityManager.get().create(...)` before the first
  engine, and never destroying them, makes every engine correct: those slots
  stay at generation 0 in the engine's manager, matching what the gltfio copy
  hands out.

Any other code in `filament-utils` or `gltfio` that calls
`EntityManager::get()` instead of taking the engine's manager has the same
split. The sources of 1.78.1, 1.79.0, 1.80.0 and `main` (2026-11-03) still
apply the version script and `IBLPrefilterContext` still calls
`EntityManager::get()`, so we expect them to be affected too.

Possible fixes: export `utils::EntityManager` (or all of `utils::*`) from
`libfilament-jni.map`; or have `IBLPrefilterContext` create its entities with
`engine.getEntityManager()`.

## Workaround

Reserve the first slots of the engine's entity manager before anything else
creates an entity, and never free them (SceneView reserves 4096). The slots the
prefilter's manager hands out then have generation 0 on both sides.

It holds for the first 2048 contexts created in the process. Each
`IBLPrefilterContext` takes two fresh slots from the gltfio copy and never gets
one back: `~IBLPrefilterContext` destroys the triangle entity only, not the
camera entity (`libs/iblprefilter/src/IBLPrefilterContext.cpp:171`), and a
destroyed slot only returns to a manager's free list when its epochs advance
(`libs/utils/src/EntityManagerImpl.h`, `recycleGarbageEpochs`), which only
`FEngine` triggers, on its own manager (`filament/src/details/Engine.cpp:818`).
So context k gets slots 2k-1 and 2k, alive or destroyed.

## What we ruled out earlier

All of these still give black on engine B:

- engine A never running a conversion (the first engine is just created and
  destroyed);
- no shared GL context;
- waiting on a `Fence` before destroying the equirect source texture, or never
  destroying it;
- toggling each feature flag added since 1.72 through system properties
  (priority override, parallel shader compile, UBO batching, skip frame,
  frame_info).

It also reproduces with a software renderer, so it does not look
GPU-driver-specific.

`libs/iblprefilter` is byte-identical between v1.72.1 and v1.77.3: the change
that matters is in how the AARs are linked, not in the prefilter.

## Screenshots

<!-- Attach before posting: engine A vs engine B, 1.72.1 vs 1.77.3, from
/private/tmp/qa-4930-r2/ (park scene direct vs via picker) and
/private/tmp/qa-4930-r3/exp12-swiftshader.png -->

## Logs

No Filament or GL warning or error in logcat on engine B.

## Smartphone (please complete the following information)

- Device: Android Emulator, AVD Pixel 7a, API 36, arm64
- GPU: `-gpu host` (Apple M3, GLES translator) and `-gpu swiftshader_indirect`
  (SwiftShader); both black
- Backend: OpenGL ES
- Filament: 1.77.0 to 1.77.3 from Maven Central (black); 1.72.1, 1.73.0,
  1.74.0, 1.74.1, 1.75.0, 1.75.1, 1.76.0 and 1.76.1 (correct). 1.78+ are not
  on Maven Central yet, so not run.

## Additional context

Found while upgrading SceneView (https://github.com/sceneview/sceneview/pull/4930).
SceneView ships the workaround above with 1.77.3.
