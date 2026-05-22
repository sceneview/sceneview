---
title: Sketchfab streaming for SceneView demos
description: How to stream Sketchfab CC-BY models into a SceneView demo with SketchfabAssetResolver, the LRU cache, and the per-slug bundled fallback contract.
---

# Sketchfab streaming for SceneView demos

**Intent:** "I want my SceneView app to load a variety of 3D models without bundling 30 MB of GLBs into the APK."

The SceneView sample apps (Android + iOS) ship a small library that streams CC-BY licensed models from Sketchfab on demand, caches them on disk, and falls back to a bundled GLB / USDZ when the network or the API key is unavailable. This recipe describes the contract so you can lift the same pattern into your own app.

## What's on the shelf

- **`SketchfabAssetResolver`** (Android Kotlin / iOS Swift) — single entry point that takes a `SketchfabSlug` and returns a local `File` (`URL` on iOS) ready to feed into `rememberModelInstance(modelLoader, "file://...")`. Source:
    - `samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SketchfabAssetResolver.kt`
    - `samples/ios-demo/SceneViewDemo/Services/SketchfabAssetResolver.swift`
- **`SampleAssets`** — curated registry of `SketchfabSlug` entries grouped by category (`solar`, `gallery`, `animation`, `park`, `ar_placement`, `physics`, `materials`). Each entry has a `uid` (Sketchfab primary key), an `author`, a `licenseUrl`, a `fallbackBundledPath`, a `scaleToUnits` hint, and a CC-BY-only license invariant enforced by the constructor.

## Hard rules — license + UX

- **CC-BY 4.0 only.** Every `SketchfabSlug` is validated at construction time; non-CC-BY models (NC, ND, SA, Sketchfab Standard) are rejected so the registry can't silently regress.
- **No Sketchfab WebView / external link.** All loading is in-process: `resolve(uid) → rememberModelInstance(modelLoader, file)`. Sketchfab is an invisible asset CDN, not a UX surface.
- **Never ship a build that needs the network to render something useful.** Each entry's `fallbackBundledPath` points to a bundled GLB / USDZ in the APK / IPA. When the resolver fails (no key, 4xx / 5xx, truncated download), it stages and returns the bundled fallback instead.
- **Attribute the author.** Every streamed model surfaced in your UI must show the author byline — that's the contract you sign with the CC-BY 4.0 license. The sample app's `Credits` sheet (`samples/android-demo/.../About`) is the canonical pattern.

## Use in a demo composable (Android)

```kotlin
@Composable
fun MyDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val resolver = remember(context) { SketchfabAssetResolver.getInstance(context) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    // Warm the cache so the first frame doesn't pop in.
    LaunchedEffect(Unit) {
        runCatching { resolver.prefetchAll("animation") }
    }

    // Pick a slug from the curated registry.
    val slug = remember { SampleAssets.byCategory["animation"].orEmpty().first() }

    // Resolve to a local file (null while downloading / staging the fallback).
    val file: File? by produceState<File?>(initialValue = null, key1 = slug.uid) {
        value = runCatching { resolver.resolve(slug) }.getOrNull()
    }

    val modelInstance = file?.let {
        rememberModelInstance(modelLoader, "file://${it.absolutePath}")
    }

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
        modelInstance?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = slug.scaleToUnits,
                autoAnimate = slug.hasBakedAnimation,
            )
        }
    }
}
```

## LRU cache contract

The shared on-disk cache lives at `Context.cacheDir/sketchfab/<uid>.glb` (Android) and `URL.cachesDirectory/sketchfab/<uid>.usdz` (iOS). The resolver caps it at **250 MB** by sample-side budget (the underlying `SketchfabService` enforces a looser 500 MB cap). `pruneCache()` runs after every resolve so the eviction loop runs incrementally — oldest files (by `lastModified`) go first.

`prefetchAll(category)` fans every slug in the category out in parallel through the resolver. Concurrent calls for the same `uid` deduplicate at the `SketchfabService` layer — N callers, one network download.

## Bounds sanity check

Before returning a streamed file, the resolver checks the `glTF` magic header (4 bytes) and the file size (≥ 12 B). Truncated downloads (Sketchfab CDN sometimes returns a 200 with HTML in the body), USDZ archives served when only USDZ was available, and 0-byte stubs all get rejected — the resolver falls back to the bundled asset for that run instead of handing a broken file to Filament.

Full visual-bounds verification happens lazily at load time in Filament-land; mismatches (huge bounding boxes, off-center origins) are surfaced by the demo's visual smoke test and get a registry-curation issue filed.

## Adding a new slug

The registry is small and reviewed by hand. To add an entry:

1. Verify the Sketchfab page says **CC-BY 4.0** — click the badge to confirm Attribution (a generic "Creative Commons" badge is not enough).
2. Verify the model is marked downloadable in `glb` format (`usdz` for iOS).
3. Compute a realistic `scaleToUnits` (the resolver's bounds sanity check rejects outside `[0.05 m, 5 m]`).
4. Pick a bundled fallback that visually resembles the streamed model so an offline render isn't broken.
5. Add the entry to **both** `SampleAssets.kt` and `SampleAssets.swift`, grouped by category.
6. Run `./gradlew :samples:android-demo:testDebugUnitTest --tests "io.github.sceneview.demo.sketchfab.SampleAssetsTest"` — the test enforces CC-BY validation, 32-char hex uids, no duplicate uids, and every demo category present.

## API key wiring

The Sketchfab API key is a **build-time secret**, never a user-facing field. Android reads it from `local.properties` (`sketchfab.api.key=...`) or the `SKETCHFAB_API_KEY` GitHub Secret in CI — see `samples/android-demo/build.gradle` lines 45–61. iOS reads it from a generated `SketchfabConfig.swift` so TestFlight + App Store binaries see the live key — see `samples/ios-demo/Scripts/inject-sketchfab-key.sh` (issue [#1157](https://github.com/sceneview/sceneview/issues/1157) shipped this in v4.3.1).

When `SketchfabConfig.apiKey == null` (no-secret CI builds, store builds where the secret hasn't been injected), the resolver bypasses the network and goes straight to the bundled fallback. The demo never crashes on a missing key.

## See also

- [`DemoScaffold` v2 — bottom-sheet picker](./demo-settings-sheet.md) — the modal sheet pattern that pairs with this recipe.
- [`SampleAssets.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SampleAssets.kt) — full curated registry.
- [`SketchfabAssetResolver.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/sketchfab/SketchfabAssetResolver.kt) — implementation.
- Umbrella issue: [#1152](https://github.com/sceneview/sceneview/issues/1152).
