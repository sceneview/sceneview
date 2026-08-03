# `:java` — JVM/Desktop native runtime (Project Panama / FFM)

This is the single module that binds Filament on the **JVM/Desktop** target. It uses
**Project Panama** (the Foreign Function & Memory API, finalised in JDK 22) to call the
combined C wrapper directly — no JNI. Android does **not** use this module; it depends on
the official `com.google.android.filament` Maven library instead.

Published as **`io.github.erkko68.filament-ffm:filament-ffm`** and pulled in transitively
by every `:kotlin:*` JVM target (each declares `api(project(":java"))` in its `jvmMain`),
so consumers never add it by hand.

## What it does (`build.gradle.kts` + [`build-logic/FilamentJvmNative.kt`](../build-logic/src/main/kotlin/FilamentJvmNative.kt))

1. **CMake** builds the combined `libfilament-c.{dylib,so,dll}` from
   [`c/CMakeLists.txt`](../c/CMakeLists.txt) (`-DFILAMENT_BUILD_SHARED=ON`). All four C
   wrappers — filament + filamat + filament-utils + gltfio — and their Filament static
   archives are linked into **one** shared image. One image means one set of Filament's
   process-global singletons (notably `EntityManager`); splitting them across multiple
   shared libraries duplicates those singletons and silently corrupts cross-library entities.
2. **`jextract`** runs once over the whole C header surface to generate a single
   `io.github.erkko68.filament.ffm.FilamentC` class of low-level `MethodHandle` bindings.
   The jextract task is wired as a source dir, so every consumer (`compileJava`,
   `compileKotlin`, `sourcesJar`) depends on it.
3. The dylib is packaged into the JAR under `natives/<platform>-<arch>/` together with a
   `.sha256` stamp. At runtime
   [`FilamentLoader`](src/main/java/io/github/erkko68/filament/ffm/FilamentLoader.java)
   extracts it **once** into a content-hash-keyed cache dir (`~/.filament-kmp/filament-c-<hash>/`)
   and `System.load`s it from there, so jextract's `loaderLookup` resolves the symbols.
   Subsequent JVM starts reuse the cached copy; concurrent processes are serialized by a
   lock file; unused cache dirs are purged after 30 days. No system install of Filament
   is needed. Runtime knobs (system properties):
   - `filament.library.path` — load the lib from this directory instead of extracting.
   - `filament.data.path` — cache root (default `~/.filament-kmp`).
   - `filament.data.cleanup.days` — stale-cache purge age; `<= 0` disables (default 30).
4. [`Ffm.kt`](src/main/kotlin/io/github/erkko68/filament/Ffm.kt) hosts the shared FFM
   helpers (arenas, struct/array marshalling, upcall stubs) that the `:kotlin:*` `jvmMain`
   actuals build their idiomatic Kotlin API on top of `FilamentC`.

`jextract` is fetched automatically by the build (the `downloadJextract` task, cached under `.gradle/jextract/`) — no manual setup. The pinned build coordinates live in [`build-logic/FilamentDownloads.kt`](../build-logic/src/main/kotlin/FilamentDownloads.kt).

Build knobs:
- `-Pfilament.debug=true` — build the C wrapper with `CMAKE_BUILD_TYPE=Debug` (prebuilts
  stay Release; on Windows the `/MTd`↔`/MT` CRT mismatch makes the Debug link fail, so
  use macOS/Linux for this).
- `FILAMENT_PREBUILTS_DIR=<dir>` (env) — link against a locally built Filament instead of
  the downloaded prebuilts and skip the download task. Layout mirrors `prebuilts/`:
  `<dir>/<target>/lib` (e.g. `<dir>/macosArm64/lib`). Also honoured by the Kotlin/Native
  cinterop builds.
- On macOS/Linux the shared lib is **sealed**: only the `Fila*` C API is exported
  (see [`c/filament-c.map`](../c/filament-c.map)), keeping Filament's C++ internals out of
  the process-wide symbol namespace.

## Requirements

- **JDK 22+** at runtime (the FFM API floor). Compilation targets `--release 22`; the
  Gradle daemon itself runs on a newer JDK.

## Publishing — artifact set (skiko-awt-runtime style)

The bindings and the natives are published separately (artifact ids pinned via
`maven.artifactId` in each module's `gradle.properties`):

| Artifact | Contents |
|---|---|
| `filament-ffm` | jextract bindings + loader + FFM helpers — **no natives**. By default its runtime metadata depends on **all** platform modules below; its Gradle-metadata variants (`OperatingSystemFamily` × `MachineArchitecture`) narrow that to exactly one |
| `filament-ffm-runtime-{macos-arm64, linux-x64, linux-arm64, windows-x64}` | one platform's `libfilament-c` (+ `.sha256`) |

The `:kotlin:*` JVM targets depend on `filament-ffm` alone, so plain consumers keep
working with zero configuration — they pull every platform's natives, as before the
split. Gradle consumers who only want their platform's ~13 MB add two attributes and the
per-platform variant is selected automatically:

```kotlin
configurations.matching { it.isCanBeResolved }.configureEach {
    attributes {
        attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(OperatingSystemFamily.MACOS))
        attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(MachineArchitecture.ARM64))
    }
}
```

Maven (non-Gradle) consumers get the per-platform modules through `filament-ffm`'s POM.

### Release packaging — don't ship four platforms

The zero-config default matters for *packaging* too: `jpackage` / Compose Desktop
distributions bundle the whole runtime classpath, so without the attributes above a
packaged desktop app carries **all four** platforms' natives instead of one. When
building per-platform installers, either set the two attributes for the target platform
(as in the snippet), or depend on the platform runtime module directly — each
`filament-ffm-runtime-<platform>-<arch>` is standalone: it pulls the bindings via an
`api` dependency that excludes the sibling platforms, so nothing else's natives come
along:

```kotlin
dependencies {
    implementation("io.github.erkko68.filament-ffm:filament-ffm-runtime-macos-arm64:<version>")
}
```

(This is the skiko model: Compose's plugin injects `skiko-awt-runtime-<os>-<arch>` for
the host; we default to all-platforms for zero-config and let packagers narrow.)

The platform set mirrors upstream Filament's prebuilt releases (no windows-arm64: Google
doesn't publish one; Windows-on-ARM works via the x64 JVM emulation path). CI's
[`publish.yml`](../.github/workflows/publish.yml) builds `libfilament-c` on each platform
runner and publishes with `-PcArtifactsDir=<dir>` (one `<platform>-<arch>/` subdir per
platform); `:java` stages the natives per platform and the `:java:runtime*` modules jar
them. Publishing fails fast if a runtime jar would ship without its natives.
