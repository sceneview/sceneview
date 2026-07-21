<!-- category: Added -->
- iOS demo: ported Android's `placement-scene` demo — the "one-line
  tap-to-place AR" showcase for `PlacementScene`'s batteries-included bundle
  (coaching overlay, a placement reticle, an instant-placement-style raycast,
  and a contact shadow under each model). `PlacementSceneScene.swift` wires
  `ARSceneView`'s equivalent flags (`showCoachingOverlay`,
  `showPlacementReticle`, `groundingShadows`) and drops a single bundled
  `khronos_damaged_helmet` model per tap, with a "models placed" counter and
  a "Clear All" control — distinct from the existing low-level `ar-placement`
  demo, not an alias of it. Honest gap noted in-app and in code: unlike
  Android, the plane-detection grid does not fade out after the first
  placement, since `ARSceneView`'s plane overlay isn't reactive after scene
  setup (#2839).
