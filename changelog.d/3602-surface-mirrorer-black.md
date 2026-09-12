<!-- category: Fixed -->
- **`SurfaceMirrorer` recordings are no longer uniformly black ([#3602](https://github.com/sceneview/sceneview/issues/3602)).** The live viewport no longer
  goes black while recording. Mirroring now renders the scene a second time into each mirrored
  surface's own swap chain, after the scene's own frame has been presented, instead of calling
  Filament's `Renderer.copyFrame` in the middle of it — that copy left the window's colour buffer
  undefined on drivers that discard it once it leaves the EGL draw slot, so both the MP4 and the
  on-screen frame came out black. Wiring a `surfaceMirrorer` no longer forces the window swap
  chain to `CONFIG_READABLE`.
