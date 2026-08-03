<!-- category: Docs -->
- **docs** — node-count claims aligned to reality across every checked-in surface (16 now
  verified by the gate): `44+`/`42+`/`41+`/`30+`/`29+` node types → `46+` (`ContactShadowNode`
  from #2817 and `SplatNode` joined the inventory after the last alignment in #2594), the doc
  site, the website, the MCP docs and the checked-in `marketing/` copy included.
  `.cursorrules` **and `.windsurfrules`** also named node types that **do not exist** —
  `GeospatialNode`, `DepthNode`, `InstantPlacementNode`, absent from the sources and from both
  public `.api` dumps — while omitting the real additions; both lists are now generated from
  the node sources and are exhaustive (26 3D + 20 AR). The website's unqualified
  `26+ Node types` stat is relabelled `3D node types`: 26 is the genuine 3D-only subset, and
  it sat on the same page as the 46+ card (#2987).
- **build** — `impact-check.sh`'s node-count gate no longer passes by being blind. Its regex
  matched only a bare `N+ node type`, so `41+ built-in node types` and `42+ composable node
  types` evaded it for two alignments running, and split-markup stat cards (number and label
  in separate elements) were structurally invisible — the gate reported clean while the repo
  contradicted itself in seven places. It now accepts a generic qualifier, reads split stat
  cards pair-aware, still ignores platform-qualified subsets (`26+ 3D`, `15+ SceneViewSwift`),
  and watches `.windsurfrules`, `docs/docs/index.md` and the three `marketing/` files, none of
  which were in its list. Every branch mutation-tested against the files' real content (#2987).
