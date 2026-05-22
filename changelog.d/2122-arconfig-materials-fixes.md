<!-- category: Fixed -->
- **ARSceneView:** `detectConfigDowngrades` now captures the post-`sessionConfiguration`-callback depth mode, so a callback-driven depth-mode request that gets silently downgraded is correctly surfaced as `ARConfigDowngrade.DepthMode`. (#2122 / #2096 gap 1)
- **MaterialsDemo:** fixed infinite "Loading…" scrim when the `materials` registry category is empty (null selected slug now exits to an `Empty` state instead of staying in `Loading` forever). (#2122)
