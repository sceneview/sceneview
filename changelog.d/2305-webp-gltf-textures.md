<!-- category: Fixed -->
- glTF/GLB models whose textures are WebP-encoded (`EXT_texture_webp`) now load **with** their
  textures on Android. Filament's Android prebuilt ships no `image/webp` decoder and offers no seam
  to register one, so `ModelLoader` re-encodes embedded WebP textures to PNG — using Android's own
  decoder — before handing the asset to Filament, instead of letting it render untextured with only
  `Missing texture provider for image/webp` in Logcat ([#2305](https://github.com/sceneview/sceneview/issues/2305)).
  A model without WebP textures is passed through untouched. WebP kept in separate `.webp` files
  beside a `.gltf` still cannot be converted, and now logs an actionable `SceneView` error rather
  than failing silently.

<!-- The transcode runs off the main thread on the suspend/async loading paths and inline on the
     @MainThread createModel ones (same thread contract as before). The web build (Filament.js) is
     unchanged and still needs PNG/JPEG/KTX2 — tracked in
     https://github.com/sceneview/sceneview/issues/3085. -->
