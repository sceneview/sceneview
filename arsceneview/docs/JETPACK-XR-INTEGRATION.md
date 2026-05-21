# Jetpack XR integration — design notes

Tracking issue: [#1738](https://github.com/sceneview/sceneview/issues/1738) ·
Parent milestone: [#1729](https://github.com/sceneview/sceneview/issues/1729) ·
Cross-platform table: [`CLAUDE.md`](../../CLAUDE.md) "Supported platforms" row "Android XR".

## What this document records

The issue acceptance for #1738 asks for three things before any
implementation PR lands:

1. **Integration approach documented** — how SceneView exposes Android XR
   perception data.
2. **Module/runtime decision recorded** — new module vs. extension of
   `arsceneview/`.
3. **Broken into implementation PRs** — concrete slices to ship the work
   without one giant changeset.

This file captures (1) and (2). (3) is filed as follow-up issues, linked
at the bottom.

## Scope correction

The original issue title mentions "Body & hand tracking", but the ARCore
for Jetpack XR SDK (`androidx.xr.arcore`) exposes **hand tracking and
face tracking** as its perception primitives — not body tracking. Body
tracking is an ARKit (iOS) feature. We adjust the scope to what the
runtime actually delivers:

| Feature              | Mobile ARCore (`com.google.ar:core`) | Jetpack XR ARCore (`androidx.xr.arcore`) |
|----------------------|--------------------------------------|------------------------------------------|
| Plane detection      | Stable                               | Stable                                   |
| Anchors              | Stable                               | Stable                                   |
| Depth                | Stable                               | Stable                                   |
| Augmented faces      | Front-camera, stable                 | Headset, alpha (`Face` perception)       |
| Augmented images     | Stable                               | Not in alpha14                           |
| **Hand tracking**    | Not available                        | **Headset, alpha (`Hand` perception)**   |
| Body tracking        | Not available                        | Not available                            |
| Geospatial / Earth   | Stable                               | Roadmap                                  |

Body tracking on Android would mean ML Kit Pose Detection (not ARCore)
or 3rd-party (MediaPipe). That is out of scope for #1738; if there is
demand it gets its own issue.

## Runtime landscape

ARCore for Jetpack XR is a **separate runtime** from mobile ARCore:

- **Mobile ARCore** (`com.google.ar.core.Session`) — runs on phones via
  the Google Play Services for AR APK. Backs everything in
  `arsceneview/` today.
- **Jetpack XR ARCore** (`androidx.xr.runtime.Session` +
  `androidx.xr.arcore.*`) — runs on Android XR devices (headsets,
  glasses). Backed by the Jetpack XR Runtime APK. Different `Session`
  class, different `Config`, different `Pose` type, different threading.

The two cannot share a `Session` instance. They CAN share most of the
SceneView consumer-facing concepts: nodes are positioned by a `Pose`,
tracked surfaces appear/disappear per frame, the composable DSL maps a
trackable to a child scene.

## Decision — module/runtime layout

**Decision: extend `arsceneview/` with a new `io.github.sceneview.ar.xr`
subpackage. NO new Gradle module.**

### Considered alternatives

| Option                                  | Pros                                                    | Cons                                                                                          |
|-----------------------------------------|---------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| **A. New module `xrsceneview/`**        | Phone-only apps don't pull XR deps                      | Duplicates the AR scene plumbing; one more artifact to publish/version; raises maintenance   |
| **B. `xr/` subpackage in `arsceneview/`** | Reuses scene plumbing; one artifact to ship             | Phone-only apps see the XR types in IDE autocomplete                                          |
| **C. `xr/` in a new `arsceneview-xr/`** | Mid-way: separate publication, shared sources           | Gradle complexity (variant publication); contributors don't love hyphenated coordinates       |

### Why B wins

1. **Body and hand are TRACKING DATA, not a renderer**. The Filament
   rendering path stays identical; only the perception source changes.
   No new scene graph, no new node types beyond the tracking nodes
   themselves.
2. **`androidx.xr.arcore` will be `compileOnly` in
   `arsceneview/build.gradle`**. Phone-only apps get zero runtime weight
   from XR — no extra DEX entries, no extra Manifest merges. Consumers
   that actually target Android XR add the `implementation
   'androidx.xr.arcore:arcore:...'` line themselves (one Gradle line is
   acceptable for an opt-in feature).
3. **Single module, single publication, single version**. Same `4.x`
   stream as the rest of `arsceneview/`. Fits the existing release
   pipeline (`release.yml` + `play-store.yml` already cover this
   artifact).
4. **Reuses the existing `ARSceneView` / `ARSceneScope` familiarity**.
   Devs who know `AugmentedFaceNode` find `XrFaceNode` next to it. No
   need to learn a second scope.
5. **Aligns with `arsceneview/build.gradle`'s top-level TODO** which
   already plans to merge `arsceneview/` into `sceneview/` and treat
   ARCore as `compileOnly`. The XR subpackage rides the same pattern
   one level deeper.

### What "extend" means in practice

- New subpackage: `arsceneview/src/main/java/io/github/sceneview/ar/xr/`.
- New `compileOnly` dep in `arsceneview/build.gradle`:
  `compileOnly("androidx.xr.arcore:arcore:1.0.0-alpha14")` (+ transitive
  `androidx.xr.runtime`).
- Public API gated on a runtime availability check
  (`XrFeatures.isAvailable(context)`) so a non-XR phone never touches
  the XR classes at runtime — no `ClassNotFoundException` on phones.
- Tracking nodes mirror existing patterns:
  - `XrHandNode(hand: androidx.xr.arcore.Hand, …)` mirrors
    `AugmentedFaceNode(augmentedFace, …)`.
  - `XrFaceNode(face: androidx.xr.arcore.Face, …)` ditto.
  - Both ship as `@Composable` extensions on a future `XrSceneScope`,
    or — to keep things simple at first — at the top level next to
    `AugmentedFaceNode`, callable from a regular `ARSceneScope` when
    the XR session is active.

### Threading

Jetpack XR `Session.create(activity)` is a `suspend` coroutine and the
perception state flows are `kotlinx.coroutines.flow.StateFlow`s. That
plays well with Compose. The Filament JNI rule from `CLAUDE.md` still
applies — node geometry/material creation happens on the main thread,
the XR `StateFlow` is collected on the main dispatcher.

### Preview-status guardrail

`androidx.xr.arcore` is **`1.0.0-alpha14`** at the time of writing
(2026-05-21). It is preview, subject to breaking changes. Every public
SceneView API that wraps it MUST:

1. Carry a `@RequiresOptIn(message = "Jetpack XR API is preview")`
   annotation forcing consumers to opt in explicitly.
2. State "preview, may change" in its KDoc.
3. Be flagged in `llms.txt` so generated code makes the preview status
   visible to the developer.

We bump the wrapped alpha as the upstream stabilises, keeping the
opt-in marker until the upstream goes to a stable `1.0.0`.

## Sliced delivery plan

Each slice is a separate PR.

| Slice | Status | Scope                                                                                     | Issue                                          |
|-------|--------|-------------------------------------------------------------------------------------------|------------------------------------------------|
| **1** | ✅     | This design doc · `androidx.xr.arcore` dep declaration · `XrFeatures` availability check · stubs in `llms.txt` · CHANGELOG fragment | [#1908](https://github.com/sceneview/sceneview/pull/1908) — closed #1738 |
| **2** | ✅     | `XrHandNode` + `XrHandSkeleton` joint math + `SceneScope.XrHandNode` composable + `samples/android-demo/ARHandTrackingDemo.kt` + JVM tests · gated on `XrFeatures.isAvailable` | [#1902](https://github.com/sceneview/sceneview/issues/1902) |
| **3** | ✅     | `XrFaceNode` + `XrFaceMesh` mesh/region math + `SceneScope.XrFaceNode` composable + `samples/android-demo/ARXrFaceDemo.kt` + JVM tests · gated on `XrFeatures.isAvailable` | [#1903](https://github.com/sceneview/sceneview/issues/1903) |

Cross-platform parity follow-ups:

- iOS already covers hand tracking on visionOS via `HandTrackingProvider`
  and face mesh via `ARFaceTrackingConfiguration`. The cross-platform
  parity table — mobile ARCore vs. Jetpack XR vs. ARKit phone vs.
  visionOS vs. WebXR — lives in the iOS cheatsheet:
  [`docs/docs/cheatsheet-ios.md`](../../docs/docs/cheatsheet-ios.md)
  "Hand / Face / Body tracking parity (#1904)". Filed as
  [#1904](https://github.com/sceneview/sceneview/issues/1904).
- Web covers WebXR `hand-tracking` under issue #1778 — no work duplicated
  here.

## Visual QA strategy

Android XR has no public emulator at the time of writing. Slices 2 and 3
will mark visual QA as **"blocked — needs Android XR emulator or
device"** in their PR descriptions, per CLAUDE.md "Emulator-first QA".
The pure-Kotlin joint math is JVM-testable today and goes through
Robolectric / plain JUnit.

## Public-API additions in Slice 1

Only the **availability check** ships as public API in this slice. No
node types, no DSL extensions. This keeps Slice 1 minimal and lets
Slices 2 and 3 design the node API in their own PRs without retroactive
constraints.

```kotlin
package io.github.sceneview.ar.xr

object XrFeatures {
    /**
     * Returns `true` when the consumer has declared
     * `androidx.xr.arcore:arcore` on the runtime classpath — i.e. they
     * have explicitly opted into the XR path. Returns `false` otherwise
     * (the default for a phone-only consumer).
     *
     * Safe to call on any Android device — the check uses reflection so
     * the `androidx.xr.runtime` classes don't need to be present at
     * runtime.
     *
     * Device-level capability (XR headset vs mobile phone) is layered on
     * top by Slices 2 / 3 via the upstream `Session.create(activity)`
     * outcome.
     */
    fun isAvailable(context: android.content.Context): Boolean
}
```

That is the entire public API surface of this PR.

## References

- [ARCore for Jetpack XR overview](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore)
- [Jetpack XR SDK overview](https://developer.android.com/develop/xr/jetpack-xr-sdk)
- [Jetpack SceneCore](https://developer.android.com/develop/xr/jetpack-xr-sdk/scenecore)
- [ARCore for Jetpack XR release notes](https://developer.android.com/jetpack/androidx/releases/xr-arcore)
- [`CLAUDE.md`](../../CLAUDE.md) — "Supported platforms" cross-platform table
