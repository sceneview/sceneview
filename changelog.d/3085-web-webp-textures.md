<!-- category: Fixed -->
- **Web: WebP-textured glTF/GLB models now render textured ([#3085](https://github.com/sceneview/sceneview/issues/3085)).** Filament.js registers no `image/webp` texture provider, so an asset using `EXT_texture_webp` — or plain `image/webp` images — loaded silently untextured. `sceneview-web` now re-encodes the embedded WebP images to PNG in the browser before the model reaches Filament, exactly like the Android `WebPTextureTranscoder` does. A model that uses no WebP is passed through untouched. WebP images referenced by an external file URI still cannot be converted and are now reported with an actionable console error instead of rendering blank.

<!-- category: Docs -->
- **`troubleshooting` no longer says WebP textures are unfixable on the web.** The remaining uncovered case is a WebP referenced by an external URI, not the web platform as a whole.
