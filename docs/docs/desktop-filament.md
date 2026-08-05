# Filament on Compose Desktop — status & decision

Decision record for hardware-accelerated 3D rendering on Desktop, replacing the
wireframe placeholder in `samples/desktop-demo/`.

**Last updated:** 2026-08-03 · **Decision:** superseded — see
[compose-multiplatform.md](compose-multiplatform.md). The offscreen architecture below
still stands; the *binding supply* decision does not.

> **2026-08-03 update.** Desktop rendering is now delivered as the desktop `actual` of
> the `sceneview-compose` façade. The binding supply changed: instead of *depending on*
> `io.github.erkko68.filament-ffm`, its desktop path is **vendored** into
> `third_party/filament-kmp/` under its Apache-2.0 licence, with the attribution that
> licence requires. The offscreen architecture and the filament-kmp analysis below are
> unchanged and still the basis for the work. See
> [compose-multiplatform.md](compose-multiplatform.md). Original decision thread:
> [issue #2540](https://github.com/sceneview/sceneview/issues/2540).

> Supersedes the 2026-03-25 research version of this page, which incorrectly
> presented Filament's desktop Java build (`filament-java.jar`, `FilamentCanvas`,
> `FilamentPanel`) as something upstream still provides. It does not — see below.

---

## Current state: software renderer

The desktop demo (`samples/desktop-demo/`) uses a pure-software approach:

- **Compose Desktop** (JetBrains) provides the window and UI framework
- **Compose Canvas** draws wireframe geometry (cube, octahedron, diamond)
- **sceneview-core** KMP math is available but rendering is manual projection + line drawing
- No texture mapping, no PBR materials, no glTF loading, no shadows

This is a placeholder and says so in its README/About screen.

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
   Èric Bitriá Ribes.
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

## References

- [Issue #2540 — decision + full design doc](https://github.com/sceneview/sceneview/issues/2540)
- [google/filament#4263 — removal of Java/desktop builds (2021)](https://github.com/google/filament/pull/4263)
- [google/filament#7558 — KMP desktop support: not planned](https://github.com/google/filament/issues/7558)
- [Erkko68/filament-kmp](https://github.com/Erkko68/filament-kmp) ·
  [Compose integration strategies](https://github.com/Erkko68/filament-kmp/blob/main/docs/compose/integration-strategies.md)
- [compose-multiplatform#3810 — external GPU texture interop (open)](https://github.com/JetBrains/compose-multiplatform/issues/3810)
- [JetBrains lwjgl-integration (experimental)](https://github.com/JetBrains/compose-multiplatform/tree/master/experimental/lwjgl-integration)
