<!-- category: Docs -->

- Documented honestly that the **Web** renderer cannot recycle Filament entity ids: the pinned
  `filament.js` 1.52.3 usably binds only `EntityManager.get()`/`create()` — its runtime `destroy()`
  is a no-op on the id pool (verified by an in-browser probe: 2000 create/destroy/create yields zero
  id reuse) and `isAlive()` is unbound. So the id-recycling that `Node.destroy()` gains on Android
  (#2859) has no working Web equivalent until the `filament.js` pin is bumped. Corrected a comment in
  the Web `SceneView.destroy()` that wrongly claimed the camera entity's id was reclaimed, and added a
  guard note on the `EntityManager` binding so no future change naively calls the no-op `destroy()`.
- Fixed the `MeshNode` / `GeometryNode` KDoc example, which referenced an undefined `renderable`
  variable and showed low-level manual entity creation; it now shows real node usage and notes that
  letting the node own its entity is preferred (#2859).
