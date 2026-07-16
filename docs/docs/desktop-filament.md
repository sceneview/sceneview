# Filament Desktop JNI — Research

Research document for hardware-accelerated 3D rendering on Desktop via Filament JNI,
replacing the current software renderer in `samples/desktop-demo/`.

**Last updated:** 2026-03-25

---

## Current state: software renderer

The desktop demo (`samples/desktop-demo/`) uses a pure-software approach:

- **Compose Desktop** (JetBrains) provides the window and UI framework
- **Compose Canvas** draws wireframe geometry (cube, octahedron, diamond)
- **sceneview-core** KMP math is available but rendering is manual projection + line drawing
- No texture mapping, no PBR materials, no glTF loading, no shadows
- LWJGL 3.3.6 is already declared as a dependency (OpenGL, GLFW, STB) but unused

This is a placeholder. The About screen states "Filament JNI planned" for hardware rendering.

---

## Filament Java/JNI desktop support — what exists

### Official support status

Filament (v1.72.1, July 2026) officially supports desktop platforms (macOS, Linux, Windows)
via its native C++ API. It also provides **Java/JNI bindings** for desktop use:

- **`filament-java.jar`** — Java classes (Engine, Scene, View, Camera, Renderer, Material, etc.)
- **`libfilament-jni`** — native JNI shared library (`.dylib` / `.so` / `.dll`)

The Java API lives in `com.google.android.filament` (despite the package name, the classes
are pure Java, not Android-specific). Key classes: `Engine`, `Renderer`, `Scene`, `View`,
`Camera`, `SwapChain`, `Material`, `RenderableManager`, `LightManager`, `TransformManager`,
`IndirectLight`, `Skybox`, `Texture`, `IndexBuffer`, `VertexBuffer`.

### AWT/Swing integration

Filament provides `FilamentCanvas` (AWT) and `FilamentPanel` (Swing) for embedding
Filament rendering in standard Java desktop applications. Usage:

1. Call `Filament.init()` to load JNI
2. Create `Engine` and `Renderer`
3. Instead of calling `beginFrame`/`endFrame` on the `Renderer`, call them on
   `FilamentCanvas` or `FilamentPanel`

### Headless / offscreen rendering

Filament supports headless rendering via `NativeSurface`:

```java
NativeSurface surface = new NativeSurface(width, height);
SwapChain swapChain = engine.createSwapChainFromNativeSurface(surface, 0);
```

This works with the OpenGL backend. Vulkan headless has had issues (glX symbol lookup errors).

### What is NOT available

- **No Maven Central artifacts for desktop** — only `com.google.android.filament:filament-android`
  is published to Maven Central (as AAR for Android)
- **No pre-built desktop JNI in GitHub releases** — the `filament-v1.72.1-mac.tgz`,
  `filament-v1.72.1-linux.tgz`, `filament-v1.72.1-windows.tgz` archives contain native
  C++ libraries and tools, but NOT `filament-java.jar` or `libfilament-jni`
- **The CI release workflow does not build Java for desktop** — Java is only enabled in
  the Android build job
- **Filament issue #7558** (Feb 2024): request for KMP desktop support was closed as
  "not planned" — the team stated the Android library is Java and not compatible with KMP

---

## Integration approaches

### Approach 1: Build Filament from source with Java/JNI enabled (Recommended)

The Filament build system (`build.sh`) supports Java JNI compilation when `JAVA_HOME` is set.
By default it attempts to compile Java bindings. The `-j` flag can skip Java compilation.

**Steps:**

1. Clone `google/filament` (v1.72.1)
2. Build for each desktop platform with Java enabled:
   ```bash
   export JAVA_HOME=/path/to/jdk17
   ./build.sh -p desktop release
   ```
3. Collect output artifacts:
   - `out/release/filament/lib/filament-java.jar`
   - `out/release/filament/lib/<arch>/libfilament-jni.{dylib,so,dll}`
   - Additional native deps: `libbackend`, `libbluegl`, `libbluevk`, `libfilabridge`,
     `libfilaflat`, `libutils`, `libgeometry`, `libsmol-v`, `libibl`
4. Publish to a local or project Maven repository as:
   ```
   io.github.sceneview:filament-desktop-jni:<version>
   ```
   with platform classifiers (e.g., `natives-macos-arm64`, `natives-linux`, `natives-windows`)

**Pros:** Full control, exact version match, all features available
**Cons:** Complex CI (must build on 3+ platforms), large native binaries, maintenance burden

### Approach 2: Extract Java classes from Android AAR + build native JNI separately

The `filament-android` AAR on Maven Central contains the Java classes in `classes.jar`.
These classes are the same ones used on desktop (pure Java, no Android APIs).

**Steps:**

1. Download `com.google.android.filament:filament-android:1.72.1` AAR
2. Extract `classes.jar` (rename to `filament-java.jar`)
3. Build only the native JNI shared library from Filament source for each desktop platform
4. Load with `System.loadLibrary("filament-jni")` or `Filament.init()`

**Pros:** Java classes always available from Maven Central without building
**Cons:** Version sync risk between AAR classes and native library, some Android-specific
classes may be present (`AndroidPlatform.java`), untested combination

### Approach 3: LWJGL window + Filament headless rendering + Compose overlay

Use LWJGL to create an OpenGL window context, render Filament to an offscreen texture,
then composite with Compose Desktop UI.

**Architecture:**

```
┌─────────────────────────────────┐
│         Compose Desktop         │
│   (Material 3 UI, overlays)     │
├─────────────────────────────────┤
│     LWJGL / GLFW window         │
│    (OpenGL context owner)       │
├─────────────────────────────────┤
│      Filament Engine            │
│  (renders to SwapChain/FBO)     │
│  PBR, IBL, shadows, glTF       │
├─────────────────────────────────┤
│  Native: libfilament-jni        │
│  Backend: OpenGL or Vulkan      │
└─────────────────────────────────┘
```

JetBrains has an experimental LWJGL integration for Compose Desktop that demonstrates
this pattern (compose-multiplatform/experimental/lwjgl-integration). It creates a GLFW
window, binds a Skia OpenGL context, and runs a ComposeScene on top.

**Pros:** Compose UI and Filament in the same window, LWJGL already in the build
**Cons:** Complex GL context sharing, experimental Compose integration, frame sync issues

### Approach 4: Separate Filament window + Compose Desktop UI window

Run Filament in its own GLFW/SDL2 window (using the native C++ API or Java bindings)
and Compose Desktop as a separate UI window. Communicate via shared state.

**Pros:** Simplest to implement, no GL context conflicts
**Cons:** Two windows (bad UX), no overlay UI on the 3D viewport

---

## Recommended architecture for SceneView Desktop

**Approach 1 + elements of Approach 3** — build Filament JNI from source, integrate via
LWJGL OpenGL context in a Compose Desktop window.

### Rendering pipeline

```
1. Compose Desktop creates window via LWJGL/GLFW
2. Filament.init() loads libfilament-jni
3. Engine.create(Engine.Backend.OPENGL) creates Filament engine
4. SwapChain created from GLFW native window handle
5. Per frame:
   a. Compose layout pass (UI overlays, controls)
   b. Filament beginFrame / render / endFrame
   c. Compose renders UI on top via Skia
   d. glfwSwapBuffers()
```

### Module structure

```
sceneview-desktop/
├── build.gradle.kts          # Compose Desktop + LWJGL + filament-desktop-jni
├── src/desktopMain/kotlin/
│   ├── FilamentEngine.kt     # Engine lifecycle, SwapChain management
│   ├── DesktopScene.kt       # SceneView{} composable for desktop (mirrors Android API)
│   ├── DesktopModelLoader.kt # glTF/GLB loading via Filament gltfio
│   ├── DesktopRenderer.kt    # Frame loop, Compose+Filament composition
│   └── Main.kt               # Application entry point
└── libs/
    ├── filament-java.jar
    ├── natives-macos-arm64/libfilament-jni.dylib
    ├── natives-macos/libfilament-jni.dylib
    ├── natives-linux/libfilament-jni.so
    └── natives-windows/filament-jni.dll
```

---

## Pre-built binaries availability

| Artifact | Maven Central | GitHub Releases | Build from source |
|---|---|---|---|
| filament-android (AAR) | Yes (v1.72.1) | Yes | Yes |
| filament-java.jar (desktop) | No | No | Yes |
| libfilament-jni (macOS arm64) | No | No | Yes |
| libfilament-jni (macOS x86_64) | No | No | Yes |
| libfilament-jni (Linux x86_64) | No | No | Yes |
| filament-jni.dll (Windows x64) | No | No | Yes |
| Native C++ libs (desktop) | No | Yes (.tgz) | Yes |
| gltfio native (desktop) | No | Yes (.tgz) | Yes |

**Key finding:** Desktop JNI binaries must be built from source. They are not distributed
in any pre-built form.

---

## Challenges

### 1. Native library building and distribution

- Must build Filament from source on macOS (arm64 + x86_64), Linux (x86_64), Windows (x64)
- CI needs 3 platform runners (GitHub Actions: `macos-14`, `ubuntu-24.04`, `windows-2022`)
- Total native binary size: ~50-100 MB per platform
- Must be packaged as Maven artifacts with platform classifiers

### 2. OpenGL context sharing between Compose and Filament

- Compose Desktop uses Skia with an OpenGL backend
- Filament also wants an OpenGL context
- Sharing a single GL context between two renderers is fragile
- Alternative: Filament renders to FBO, Compose blits the texture (adds latency)

### 3. Platform-specific windowing

- macOS: requires `CAMetalLayer` or `NSOpenGLView` for Metal/GL backends
- Linux: X11 or Wayland surface
- Windows: HWND for the swap chain
- LWJGL/GLFW abstracts most of this, but Filament's `SwapChain` needs the native handle

### 4. No official KMP/Compose Desktop support from Filament team

- Issue #7558 closed as "not planned"
- The `com.google.android.filament` package name suggests Android-first thinking
- Any desktop integration is community-maintained

### 5. gltfio and asset loading

- `filament-utils` and `gltfio` (glTF loader) also need JNI bindings for desktop
- These are separate native libraries with their own JNI layers
- Without gltfio, cannot load `.glb` / `.gltf` models

### 6. Material compilation

- Filament materials (`.filamat`) must be compiled with `matc` for the target backend
- Desktop uses OpenGL or Vulkan (not OpenGL ES like Android)
- Material files may need to be recompiled or use the `DESKTOP` target

---

## Estimated complexity

| Task | Effort | Risk |
|---|---|---|
| Build Filament JNI from source (all 3 platforms) | 2-3 days | Medium (build system complexity) |
| CI pipeline for cross-platform native builds | 2-3 days | Medium (platform runners, caching) |
| LWJGL + Filament SwapChain integration | 3-5 days | High (GL context sharing) |
| Compose Desktop overlay rendering | 2-3 days | High (Skia + Filament frame sync) |
| glTF model loading (gltfio JNI) | 2-3 days | Medium |
| SceneView{} composable API (mirror Android) | 3-5 days | Low |
| Material compilation for desktop backends | 1-2 days | Low |
| Testing and platform-specific fixes | 3-5 days | High (3 platforms) |
| **Total** | **18-29 days** | **High** |

---

## Alternative: lighter-weight approaches

If full Filament JNI is too expensive, consider:

1. **Filament.js via embedded browser** — Use a WebView/CEF component in Compose Desktop
   to run the existing `sceneview-web` module. Simpler but adds browser overhead.

2. **JMonkeyEngine or JOGL** — Alternative JVM 3D engines with existing desktop support.
   Different rendering quality than Filament but much easier to integrate.

3. **Vulkan via LWJGL** — Use LWJGL's Vulkan bindings directly with a custom renderer.
   Maximum control but requires writing a renderer from scratch.

4. **Keep software renderer** — Adequate for previewing/debugging. Add rasterization
   (filled triangles, depth buffer) without the Filament dependency.

---

## References

- [Filament GitHub repository](https://github.com/google/filament)
- [Filament README — Java/JNI usage](https://github.com/google/filament/blob/main/filament/README.md)
- [Filament BUILDING.md](https://github.com/google/filament/blob/main/BUILDING.md)
- [Filament issue #7558 — KMP desktop support](https://github.com/google/filament/issues/7558)
- [Filament issue #142 — macOS JNI loading](https://github.com/google/filament/issues/142)
- [Compose Multiplatform LWJGL integration](https://github.com/JetBrains/compose-multiplatform/tree/master/experimental/lwjgl-integration)
- [Compose Multiplatform OpenGL issue](https://github.com/JetBrains/compose-multiplatform/issues/3810)
- [Filament releases (v1.72.1)](https://github.com/google/filament/releases)
- [Filament Android on Maven Central](https://central.sonatype.com/artifact/com.google.android.filament/filament-android)
