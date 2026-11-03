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

## What we ruled out

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

`libs/iblprefilter` is byte-identical between v1.72.1 and v1.77.3, so the change
is likely in the backend or engine-level state the prefilter relies on. We read
the 1.72→1.77 diffs of the GL driver, `PlatformEGLAndroid`, `Texture.cpp`,
`FeatureFlagManager` and the JNI statics without finding anything that depends
on which engine was created first.

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
- Filament: 1.77.3 from Maven Central (black), 1.72.1 (correct). 1.78/1.79 are
  not on Maven Central yet, so not tested.

## Additional context

Found while upgrading SceneView (https://github.com/sceneview/sceneview/pull/4930).
We are staying on 1.72.1 until this is understood.
