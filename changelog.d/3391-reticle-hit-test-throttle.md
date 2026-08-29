<!-- category: Fixed -->
<!-- breaking -->
- **`ReticleNode` and `PlacementReticle` can now be throttled like every other AR hit-test node ([#3391](https://github.com/sceneview/sceneview/issues/3391)).** `HitResultNode` has
  rate-limited its per-frame ARCore `Frame.hitTest()` through `refreshIntervalMs` since
  #2328, but neither reticle subclass accepted or forwarded that value: it appeared in no
  constructor, no KDoc and no composable parameter list, so both silently inherited the
  `0` = every-frame default and the raycast ran once per rendered frame whatever the caller
  wanted. Both classes and all three composables now take `refreshIntervalMs` and forward it
  to the base node. `0` remains the default and byte-for-byte the previous behaviour; `100`
  gives a 10 Hz reticle. The throttle covers the hit test only — the inherited
  smooth-transform easing, and `PlacementReticle`'s orientation smoothing, still run every
  frame, so a rate-limited reticle glides between hits instead of stepping.

<!-- category: Added -->
- **`refreshIntervalMs` on the `HitResultNode` composable.** The class has honoured the
  throttle since #2328, but `ARSceneScope.HitResultNode` never exposed it, so Compose callers
  had to reach through `apply { }` to find a knob the class already had. All three
  composables (`HitResultNode`, `ReticleNode`, `PlacementReticle`) now take the parameter and
  re-apply it in a `SideEffect` instead of keying `remember` on it — changing the rate
  re-rates the live node rather than destroying and re-creating it.

<!-- category: Changed -->
- **Binary compatibility:** the new `refreshIntervalMs` parameter added to the
  already-released public `ReticleNode` / `PlacementReticleNode` constructors and to the
  `HitResultNode` / `ReticleNode` / `PlacementReticle` composables is source-compatible but
  **binary-incompatible** (it changes the shipped JVM signatures and the Kotlin default-args
  synthetics). It therefore rides a MINOR release, never a patch, and binary consumers of
  `arsceneview` must recompile against the new artifact — the same rule the `predicate`
  parameter followed when it was added to these same classes.
