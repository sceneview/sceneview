<!-- category: Fixed -->
`llms.txt`'s "Android-only — no port planned or pending" table was missing `SplatNode` /
`SplatCloud` / `rememberSplatCloud` (3D Gaussian Splatting) — an AI generating
SceneViewSwift code for splat content got no signal the capability doesn't exist on iOS.
The row now matches the one already added to `cheatsheet-ios.md`. The demo-catalog parity
ledger (`parity-manifest.yml`) also pointed its `splat-preview` gap at #2768, which
explicitly parks the RealityKit port as a maintainer decision rather than owning the
implementation — it now points at #2646 (item 2 of its scope), which does own it (#2768).
