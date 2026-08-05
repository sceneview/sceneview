# Vendored: Filament KMP (desktop/JVM path)

This is a **copy**, not a dependency. See [`NOTICE`](NOTICE) for the attribution
that the Apache-2.0 licence requires, and [`LICENSE`](LICENSE) for the licence
itself.

| | |
|---|---|
| Upstream | <https://github.com/Erkko68/filament-kmp> |
| Original author | Èric Bitriá Ribes |
| Licence | Apache License 2.0 |
| Copied version | `0.3.0` (tag), commit `91a4a39cd8be92ac9c86726834f5ef7386bfd93e` |
| Vendored on | 2026-08-03 |
| Filament engine version | 1.74.0 (`MATERIAL_VERSION` 74) |

## Why this is vendored rather than depended on

The full reasoning is in [`docs/docs/compose-multiplatform.md`](../../docs/docs/compose-multiplatform.md).
The short version: Google [removed Filament's Java/desktop support in
2021](https://github.com/google/filament/pull/4263), so there is no official JVM
desktop binding. Filament KMP is the only credible one. Vendoring lets the
desktop target pin its own Filament version — the repo already carries 28 committed
`.filamat` blobs across three incompatible Filament tracks — and keeps a
single-maintainer, pre-1.0 project off the critical path at runtime.

This is not a judgement on the upstream project, which is good work. It is a
supply decision, and the licence explicitly permits it.

## What was copied

| Path | Lines | What it is |
|---|---:|---|
| `c/` | 8 239 | Hand-written C wrapper over Filament's C++ API |
| `java/src/` | 352 | FFM (Project Panama) support code |
| `kotlin/filament/` | 12 114 | Core engine bindings — `commonMain` + `jvmMain` only |
| `kotlin/gltfio/` | 1 382 | glTF / GLB loading — `commonMain` + `jvmMain` only |
| `kotlin/filament-utils/` | 6 967 | Camera manipulators, KTX/HDR loaders, math |
| `build-logic/` | 1 305 | Native build pipeline: prebuilt download, CMake, `jextract` |

## What was deliberately left out

- **`kotlin/filament-compose/`** — upstream's own Compose Multiplatform DSL. We
  take the *bindings*, not the DSL; SceneView's public API is
  `sceneview-compose`, so copying this would just be a rename of their product.
- **Non-desktop source sets** (`androidMain`, `nativeMain`, `webMain`, `jsMain`)
  — Android renders through Filament directly and Apple renders through
  RealityKit. Only `commonMain` + `jvmMain` are relevant here.
- **`web/`, `samples/`, `docs/`, CI workflows** — not applicable to a vendored
  binding.

## How the build chain works

Nothing about Filament itself is built from source. The pipeline in
`build-logic/`:

1. downloads Google's **official** Filament C++ prebuilts from the
   `google/filament` GitHub releases (pinned to 1.74.0);
2. compiles the combined C wrapper in `c/` with **CMake**;
3. generates the JVM FFM bindings with a pinned **`jextract`** early-access build
   fetched from `download.java.net`;
4. stages the per-platform natives.

Consequences, all inherited and none removable by copying the code:

- **JDK 22+** is required to build and to run the desktop module — the FFM API
  (`java.lang.foreign`) was finalised in JDK 22.
- **CMake** and a C++ toolchain are needed on the build host.
- Producing natives for all desktop targets needs **macOS, Linux and Windows**
  build legs.

## Local modifications

This copy is currently **unmodified** — byte-identical to the upstream commit. Any
file changed later must carry a notice at the top saying so, as Apache-2.0 §4(b)
requires.

That is checked, not trusted: `diff-upstream.sh` **runs on every PR** (the `Repo
hygiene checks` job), and it enumerates in both directions — the tree is pinned by
`MANIFEST.sha256`, and the manifest is checked against a fresh upstream clone.

```bash
bash third_party/filament-kmp/diff-upstream.sh
```

The two-way walk is the point. A one-way walk of the vendored tree with an extension
filter — the obvious implementation — cannot see a **deleted** file, an **added** one
whose extension is not in the filter (a `.sh` that runs at build time, a `.so` blob),
or a **symlink** (not a regular file, so `find -type f` skips it). Each of those is
covered by a mutation test; all seven exit 1.

After a deliberate change, re-pin and review the manifest diff — a file appearing or
vanishing there is exactly what wants reviewing:

```bash
bash third_party/filament-kmp/diff-upstream.sh --regenerate
```

## Updating to a newer upstream version

1. Check out the target upstream tag and re-copy the paths in the table above.
2. Re-apply the local modifications (the notices mark them).
3. Bump `filaVersion` and re-run the native build on all three OSes.
4. Update the version, commit and date in `NOTICE` and in this file.
5. Recompile any `.filamat` blob on the desktop track — a `MATERIAL_VERSION`
   bump silently breaks material loading at runtime, which is exactly how
   v4.1.0 crashed 10 demos.
