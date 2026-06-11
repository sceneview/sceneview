<!-- category: Fixed -->
- Fixed `Torus` rendering inside-out on default parameters — its triangles were wound clockwise (inward), so the default single-sided material culled the visible outer surface. The donut now winds outward and renders solid. (#2469)
- Fixed the Android `Capsule` geometry rendering inside-out on default parameters — same inverted (clockwise) winding as the torus; the capsule now winds outward and renders solid. (#2470)
- Fixed `setMorphWeights(weights)` being a silent no-op: the `offset` parameter defaulted to `weights.size`, writing the weights past the end of the morph-target buffer instead of at the start. It now defaults to `0` (matching Filament), so `setMorphWeights(floatArrayOf(1f))` correctly drives the first morph target. (#2471)
