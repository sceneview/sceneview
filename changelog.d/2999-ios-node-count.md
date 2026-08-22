<!-- category: Fixed -->
<!-- breaking: false -->
The SceneViewSwift node count is no longer a hand-typed number that disagreed with itself:
the MCP `platform-setup` tool said 16, the README, `MULTIPLATFORM.md` and the docs site
said 19, and the tree holds 20 public `*Node` structs. `generate-version.js` now counts
them from `SceneViewSwift/Sources` at build time (`IOS_NODE_TYPES` /
`IOS_NODE_TYPE_COUNT`), the MCP guide and `list_platforms` use that value, the iOS setup
table lists all 20 (it was missing `ShapeNode`, `ViewNode`, `SpatialAudioNode` and
`AnchorNode`), and a test fails if the table and the Swift tree ever disagree. The prose
surfaces that cannot read a generated value drop the number instead of restating it.
