<!-- category: Docs -->
- **docs** — node-count claims aligned to reality across 10 surfaces: "44+ node types" →
  "46+" (`ContactShadowNode` from #2817 and `SplatNode` joined the inventory after the last
  alignment in #2594). `.cursorrules` also named three node types that **do not exist** —
  `GeospatialNode`, `DepthNode`, `InstantPlacementNode`, absent from the sources and from
  both public `.api` dumps — while omitting the two real additions; its list is now
  generated from the node sources and is exhaustive (26 3D + 20 AR). `impact-check.sh` runs
  clean again (#2987).
