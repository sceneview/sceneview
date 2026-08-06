<!-- category: Fixed -->
- **`sceneview-compose` no longer reads model assets on the main thread.**
  `ModelSource.Asset` went through `ModelLoader.createModelInstance(assetFileLocation)`,
  which is `@MainThread` and reads the file on the *calling* thread — and the caller here
  is `produceState`, whose producer runs in the composition's context. The whole asset
  landed on the main thread. It now uses the suspending `loadModelInstance`, which reads
  through `Dispatchers.IO` and hops back to Main for the Filament JNI call alone. Sibling
  resolution is preserved, so a multi-file `.gltf` still loads its external `.bin` and
  textures.
- **`SceneViewerSpec` (iOS) now compares by value, and its model bytes by content.** It
  is the recomposition key the iOS `SceneViewer` publishes through
  `rememberUpdatedState`, which only notifies on an *unequal* value — but it was a plain
  class with identity equality, rebuilt on every composition. Every recomposition,
  including the one each touch-move triggers through `CameraState`, therefore handed the
  Swift renderer a new spec carrying the same model and asked it to apply it again.
  `ModelSource.Bytes` already compared its array by content precisely to avoid this; the
  guarantee was lost the moment the array was unpacked into a `ByteArray` field, whose
  own `equals` is reference equality. The callbacks stay out of the comparison — they are
  permanent forwarders that already read the app's current lambdas.
- **The neutral fallback environment is no longer built for scenes that cannot use it.**
  It was hoisted above the `when`, so every `EnvironmentSource.Color` scene paid a
  synchronous `neutral_ibl.ktx` asset read and a cubemap upload for a value that branch
  can never reach — a colour background has no image-based light. It is now built inside
  the two branches that use it.

<!-- category: Added -->
- **`SceneViewer` gains an `onError` callback**, plus the `SceneViewerError` type it
  reports. A failed load has no pixels of its own — the viewport keeps showing the
  environment, which is indistinguishable from a load still in progress — so a failure
  was previously observable only in the platform log. Handling it stays optional and the
  log line is unchanged. Both shapes of failure are reported: an exception, and a loader
  answering `null` without throwing — the second matters because the threading fix above
  changed which one a malformed model produces (`createModelInstance` threw, the
  suspending `loadModelInstance` returns `null`), so handling only exceptions would have
  made unparseable models fail silently. Added now rather than deferred because the module
  is unreleased, so it costs no compatibility; after publication it would.
- **`check-vendored-download-safety.sh`** — refuses to *build* a vendored tree whose
  build-logic downloads archives without verifying them and creates symlinks from an
  unvalidated `entry.linkName`. Both defects are real in the `filament-kmp 0.3.0`
  build-logic, and both are build-time code execution the moment something compiles it.
  The tree was removed from `main` by
  [#3015](https://github.com/sceneview/sceneview/pull/3015) while this change was in
  flight, so the gate is dormant today; it arms itself when the desktop spike
  ([#2540](https://github.com/sceneview/sceneview/issues/2540)) restores the copy and a
  `settings.gradle` include lands, and fails from that moment naming both fixes. The
  remediation is also written into `docs/docs/desktop-filament.md`
  § *Re-vendoring the binding* as item 4 of the obligations that must ship in the same PR
  as a restored tree — the requirement lands *before* the build chain, not after.
  Wired into `repo-hygiene` and `pre-push-check.sh`, and its failing path is driven on
  synthetic trees by `test-check-vendored-download-safety.sh` — a gate dormant on the real
  tree is a gate whose breakage would otherwise surface only in the PR it must stop. That
  self-test already caught one: the wiring probe matched `include("<path>")` and was blind
  to the `projectDir = file(...)` form Gradle actually uses, so wiring the tree left the
  gate green.

<!-- category: Tests -->
- **Kotlin/Native unit tests now actually run in CI.** `iosSimulatorArm64Test` was never
  invoked by any job — the KMP job compiles iOS targets to klibs on Linux, which cannot
  link or run a native test binary — so an `iosTest` source set was unexecuted code. A
  macOS job (self-hosted when awake, `macos-15` otherwise) now runs them, gated on a new
  narrow `compose` path filter rather than the broad `kmp` one. `SceneViewerSpecTest` is
  its first occupant, pinning the value-equality above; a compile-only check would have
  passed on exactly the identity equality that was the defect.

<!-- category: Docs -->
- `sceneview-compose` is now documented in the `sceneview` agent skill, with the scope
  boundary (viewer subset, no AR), the `ModelSource` rules and the per-platform status —
  a published module absent from the skills is a module future AI sessions do not know
  exists.
- The `sceneview-compose` detekt reports are now uploaded as CI artifacts alongside the
  other three library modules; the step already ran the module but discarded its reports.
