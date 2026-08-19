<!-- category: Added -->
- **Desktop `SceneViewer` via filament-kmp ([#2540](https://github.com/sceneview/sceneview/issues/2540)).** The `sceneview-compose` desktop actual loads a glTF and presents Filament frames through filament-kmp's offscreen Skia path. Public API is unchanged — no Filament type in the façade. Requires JDK 22+ (FFM). `samples/desktop-demo` consumes `SceneViewer`.
