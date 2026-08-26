# Filament on Compose Desktop — status & decision

Decision record for hardware-accelerated 3D rendering on Desktop. `samples/desktop-demo`
now consumes `SceneViewer`.

**Last updated:** 2026-08-19 · **Decision:** superseded — see
[compose-multiplatform.md](compose-multiplatform.md). The offscreen architecture below
still stands; the *binding supply* decision does not.

> **2026-08-19 update.** The desktop `SceneViewer` actual is implemented. It depends on
> Maven `io.github.erkko68.filament:filament-compose:0.4.0` as `implementation` (never
> `api`) — filament-kmp already does the offscreen readback → Skia path. Not vendored.
> Requires JDK 22+ and `--enable-native-access=ALL-UNNAMED` at run. See
> [compose-multiplatform.md](compose-multiplatform.md). Issue
> [#2540](https://github.com/sceneview/sceneview/issues/2540).

> **2026-08-03 update.** Desktop rendering is now delivered as the desktop `actual` of
> the `sceneview-compose` façade. The binding supply changed: instead of *depending on*
> `io.github.erkko68.filament-ffm`, its desktop path is **vendored** under its
> Apache-2.0 licence, with the attribution that licence requires. The offscreen
> architecture and the filament-kmp analysis below are unchanged and still the basis for
> the work. See [compose-multiplatform.md](compose-multiplatform.md). Original decision
> thread: [issue #2540](https://github.com/sceneview/sceneview/issues/2540).

> **2026-08-05 amendment — vendor stays the decision, the *timing* moved.** The copy did
> land on `main` (commit `c01ae5d87`) and was removed again in the same week, unused. See
> [Re-vendoring the binding](#re-vendoring-the-binding) below for why, and for the one
> command that brings it back.

> Supersedes the 2026-03-25 research version of this page, which incorrectly
> presented Filament's desktop Java build (`filament-java.jar`, `FilamentCanvas`,
> `FilamentPanel`) as something upstream still provides. It does not — see below.

---

## Current state: `SceneViewer` on desktop

`samples/desktop-demo` consumes `sceneview-compose` `SceneViewer`. Filament is
filament-kmp on Maven (offscreen readback → Skia). JDK 22+ and
`--enable-native-access=ALL-UNNAMED`.

---

## Ground truth: what Filament provides on desktop (verified 2026-07)

**Upstream removed Java/desktop support in July 2021**
([google/filament#4263](https://github.com/google/filament/pull/4263) — *"Remove
support for Java/desktop builds. These builds are never tested nor used on our
end."*). Concretely, today:

- `FilamentCanvas` / `FilamentPanel` (the old AWT/Swing hosts) are **gone from the tree**;
  `build.sh` has no desktop Java path; the JNI library is only built inside the
  Android Gradle build.
- The GitHub release archives (`filament-vX.Y.Z-{mac,linux,arm-linux,windows}.tgz`)
  contain **C++ static libs + host tools only** — no `filament-java.jar`, no desktop
  `libfilament-jni`.
- Maven Central under `com.google.android.filament` publishes **Android AARs and the
  `matc`/`cmgen` host-tool executables only** — no desktop runtime library of any kind.
- [google/filament#7558](https://github.com/google/filament/issues/7558) (KMP desktop
  request) is closed **not planned**.

**Bottom line:** anyone doing Filament-on-JVM-desktop maintains their own native
distribution — so the real supply question is *where the desktop bindings come from*.

---

## Community bindings: filament-kmp (the S1 supply)

[Erkko68/filament-kmp](https://github.com/Erkko68/filament-kmp) — unofficial,
Apache-2.0, actively maintained KMP wrapper around Filament, published on Maven
Central (re-verified against `repo1.maven.org`, latest `0.3.0`, 2026-08-03):

- **Desktop/JVM via Project Panama (FFM, JDK 22+)** over a single combined C wrapper,
  natives bundled per platform: `io.github.erkko68.filament-ffm:filament-ffm` +
  `filament-ffm-runtime-{macos-arm64, linux-x64, linux-arm64, windows-x64}`.
  **No macos-x64 (Intel Mac) natives** — re-confirmed 2026-08-03 (404 on Maven Central).
- Wraps **Filament 1.74.0** (`MATERIAL_VERSION` 74); modules mirror upstream
  (`filament`, `gltfio`, `filamat`, `filament-utils`). A legacy `filament-jni` group
  also exists; FFM is the current track.
- It does **not** build Filament from source: it downloads Google's official C++
  prebuilts, compiles a shared C wrapper, and generates the FFM bindings with `jextract`
  at build time. The web runtime is the exception — that one comes from a personal fork
  of Filament pending an upstream PR.
- Backends on desktop: Metal on macOS, Vulkan default on Windows/Linux, OpenGL fallback.
- Its Compose Desktop integration is exactly the offscreen architecture adopted in
  #2540, documented with real numbers in
  [integration-strategies.md](https://github.com/Erkko68/filament-kmp/blob/main/docs/compose/integration-strategies.md).

Known risks: pre-1.0 API churn, essentially single-maintainer (bus factor), version
pin ahead of SceneView Android's Filament ref (see the `.filamat` note below).

---

## Decision (#2540, 2026-07)

**Offscreen route, contributor-driven, S1 bindings for the spike:**

1. **Architecture (a) — offscreen render → pipelined `readPixels` → Skia image in
   Compose.** The 3D frame lives *inside* the Skia scene, so clipping, z-order,
   overlays above **and** below, and stacked viewports all compose correctly. Cost:
   a GPU→CPU copy (≈4 MB/frame at FHD, ≈33 MB/frame at 4K) and 1–2 frames of latency —
   fine for a viewer/editor. AWT heavyweight interop was **rejected** (no reliable
   Compose blending, and the requesting framework doesn't go through AWT); zero-copy
   GPU interop is a later upgrade behind the same API, blocked on
   [compose-multiplatform#3810](https://github.com/JetBrains/compose-multiplatform/issues/3810).
2. ~~**Binding supply S1 — consume `io.github.erkko68.filament-ffm` as a dependency.**~~
   **Amended 2026-08-03: vendor instead of depend.** The shape of the binding is
   unchanged — filament-kmp's desktop path is still the right one, and S2 "build our
   own from scratch" is still not worth paying. What changed is that the code is
   **copied into `third_party/filament-kmp/`** under its Apache-2.0 licence rather than
   resolved from Maven Central, so the desktop track can pin its own Filament version
   and is not exposed to upstream's pre-1.0 API churn. The costs that come with owning
   the copy — JDK 22+, the CMake/`jextract` chain, three-OS CI, recurring upkeep — are
   listed in [compose-multiplatform.md](compose-multiplatform.md). Attribution is
   mandatory, not discretionary: upstream `LICENSE` plus a `NOTICE` crediting
   Èric Bitriá Ribes. **Amended again 2026-08-05: the copy is taken when the spike
   starts, not before** — see [Re-vendoring the binding](#re-vendoring-the-binding).
3. **Phased, with measured gates and abandon criteria** — P1 spike (engine boot +
   offscreen + one GLB in `samples/desktop-demo` behind a flag; FPS/CPU/resize/leak
   gates) → P2 `sceneview-desktop/` module mirroring the Android composable surface
   for an honest subset → P3 demand-driven parity growth. Full gates and criteria in
   [#2540](https://github.com/sceneview/sceneview/issues/2540).
4. **Core-team cost is capped at review + CI wiring.** Desktop is not a demand driver;
   this track is green-lit because a contributor carries the implementation.

### Integration notes for whoever writes the PR

- **`.filamat` ABI:** SceneView's material blobs target mobile/web profiles compiled
  with the repo-pinned `matc`. Custom desktop materials need a desktop profile
  compiled with the `matc` **matching the desktop runtime** (1.72.0 under S1), which
  puts the desktop module on its own Filament-version track — document it in the
  Version Location Map, don't diverge silently. P1/P2 dodge this via gltfio's
  built-in ubershaders.
- **JDK:** FFM means a **JDK 22+ runtime floor** for the desktop module and demo
  (jpackage bundles a modern JDK for the demo; library consumers get it documented
  in the platform matrix).
- **Platform honesty:** no Intel-Mac natives under S1 — say so in the docs rather
  than failing at load time.
- **Frame pacing:** drive rendering from `withFrameNanos`, render on the engine
  thread (the repo's single-threaded Filament rule applies on desktop too), reuse
  the two pixel buffers — no per-frame allocation.

---

## Re-vendoring the binding

The vendored copy landed on `main` in `c01ae5d87` and was removed in the same week. It
was never a mistake of *shape* — vendoring is still the decision above — it was a
mistake of *timing*. Three measurements decided it:

- **Nothing compiled it.** No `settings.gradle` referenced `third_party/`, so the 31 700
  lines were dead weight in every checkout, every `grep`, and every cross-cutting lint.
  It had already cost one repo-wide guard: `check-deprecated-api.sh` needed a
  `third_party/*` whitelist, because Filament's own `Scene` class tripped SceneView's
  deprecated-composable detector and the files could not be edited without falsifying
  the §4(b) NOTICE.
- **Its guard made an unrelated repo a single point of failure.** `diff-upstream.sh` ran
  unconditionally in `Repo hygiene checks` and `git clone`d
  `github.com/Erkko68/filament-kmp` on every CI invocation, exiting non-zero if the clone
  failed. A rename, a privatisation or an outage on one individual's repository would
  have reddened every pull request in the monorepo — to protect code nothing built.
- **A frozen 0.3.0 ages exactly like the churn it was meant to escape.** The spike is
  contributor-carried with no date; whoever starts it will want a current tag anyway.

**Restoring it is one command** — the full tree, plus `LICENSE`, `NOTICE`,
`MANIFEST.sha256` and `diff-upstream.sh`, verbatim and independent of upstream's
survival:

```bash
git checkout c01ae5d87 -- third_party/filament-kmp
```

To take a *newer* upstream tag instead: clone at that tag, replace the tree, update the
tag and commit lines in `third_party/filament-kmp/NOTICE`, then
`bash third_party/filament-kmp/diff-upstream.sh --regenerate` and review the manifest
diff — a file appearing or vanishing there is the point of the exercise.

Either way, three things must land in the **same** PR as the restored tree, or the
restore is not real:

1. the §4(b) step back in `.github/workflows/ci.yml` under `repo-hygiene` (the version in
   `c01ae5d87` is copy-pasteable) — a guard no job invokes is prose;
2. the Filament KMP attribution block back in the root `NOTICE` (also in `c01ae5d87`);
3. the `settings.gradle` include that makes something actually build it. Vendoring code
   that nothing compiles is what this section exists to prevent repeating.
4. **the two hardening fixes below, in that same PR — not a follow-up.** Item 3 is what
   makes them reachable, so shipping them later means shipping a window.

### The build-logic must be hardened before anything builds it

`build-logic/src/main/kotlin/FilamentDownloads.kt` in the copy taken from `0.3.0` has two
defects. Both are build-time code execution, and both are harmless only for as long as
nothing compiles the tree — which item 3 ends:

- **Downloads are not verified.** `downloadToCache()` streams a URL into the cache and
  returns it. The *version* is pinned; the *bytes* are not. A GitHub release asset can be
  deleted and re-uploaded under the same tag, and the `download.java.net` jextract builds
  are explicitly transient. Fix: hash the bytes once the stream completes, compare against
  a checked-in digest per artifact, and **delete the cached file on mismatch** — a
  poisoned cache entry that survives is worse than a failed download.
- **`extractAll()` is symlink-tar-slippable.** It asserts each entry's own path stays
  under the destination, which stops a `../../etc/passwd` entry, but it does not validate
  `entry.linkName` before `Files.createSymbolicLink`. A tarball carrying `a -> /tmp/evil`
  followed by a regular entry `a/x` passes that assertion — `normalize()` does not resolve
  symlinks — and writes outside the destination, into a tree that is then marked
  executable and run. Fix: resolve `linkName` against the entry's parent, assert the
  result stays under `destPath`, and reject absolute targets outright.

This is not left to a reviewer's memory. `bash .claude/scripts/check-vendored-download-safety.sh`
runs in `repo-hygiene` and in `pre-push-check.sh`: it is silent while the tree is absent
or unbuilt, and **fails from the moment a `settings.gradle` include lands** with either
fix missing. Its own failing path is exercised on synthetic trees by
`test-check-vendored-download-safety.sh`, so the gate cannot rot while it is dormant.

---

## References

- [Issue #2540 — decision + full design doc](https://github.com/sceneview/sceneview/issues/2540)
- [google/filament#4263 — removal of Java/desktop builds (2021)](https://github.com/google/filament/pull/4263)
- [google/filament#7558 — KMP desktop support: not planned](https://github.com/google/filament/issues/7558)
- [Erkko68/filament-kmp](https://github.com/Erkko68/filament-kmp) ·
  [Compose integration strategies](https://github.com/Erkko68/filament-kmp/blob/main/docs/compose/integration-strategies.md)
- [compose-multiplatform#3810 — external GPU texture interop (open)](https://github.com/JetBrains/compose-multiplatform/issues/3810)
- [JetBrains lwjgl-integration (experimental)](https://github.com/JetBrains/compose-multiplatform/tree/master/experimental/lwjgl-integration)
