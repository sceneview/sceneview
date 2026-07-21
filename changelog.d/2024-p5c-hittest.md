<!-- category: Added -->
- Web: `sv.hitTest(x, y)` — screen-point picking on the retained node tree
  (#2024 P5c). The point is unprojected through the live camera (projection +
  model matrix reads proven by a new in-browser embind probe) into a world
  ray and tested against **real per-node bounds**: model/geometry nodes get
  their asset AABB (analytic for primitives — pickable immediately), splat
  nodes their cloud bounds, each transformed by the node's current world
  transform at hit time. Returns the same `NodeHandle` instances the
  `add*Node` factories handed out (`===`-comparable), nearest-first.
  Kotlin/JS gains `SceneView.hitTest(x, y)` / `hitTest(ray)` (→
  `List<HitResult>`) and the Android-mirror `Node.collisionShape` override.
  The unprojection samples its second point mid-volume because Filament
  renders with an infinite-far projection (NDC z = +1 is a point at
  infinity).
