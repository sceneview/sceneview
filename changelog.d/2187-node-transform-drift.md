<!-- category: Fixed -->

- **[Android 3D]** Fix `Node` transform floating-point drift when updating `position`, `quaternion`,
  or `scale` at high frame rates (60–120 Hz) — e.g. `node.quaternion = newQ` in an `onFrame` loop
  (#2187). The root cause was that each individual-property setter decomposed the Filament 4×4
  matrix to read the other two components, feeding float imprecision back on every tick. After
  ~10 000 frames the scale drifted visibly and the mesh warped. Fix: cache pristine TRS backing
  fields (`_position`, `_quaternion`, `_scale`) updated once on every `transform` write; individual
  getters and setters use the caches, eliminating the matrix-decomposition round-trip.
