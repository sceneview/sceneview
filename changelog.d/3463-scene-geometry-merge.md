<!-- category: Changed -->
- **Scene Mesh and Streetscape Geometry are one card — "Scene Geometry", with Mesh and
  Streetscape modes ([#3463](https://github.com/sceneview/sceneview/issues/3463)).** They
  were 246 identical lines out of 445 and — more to the point — the *same* primary API:
  both enable `Config.StreetscapeGeometryMode.ENABLED` and read
  `frame.getUpdatedTrackables(StreetscapeGeometry::class.java)`. The whole difference is
  which node consumes the trackable: `SceneMeshNode`, which colour-codes each geometry by
  its `MeshClassification` (the enum that gives ARKit `ARMeshAnchor` parity), or the raw
  `StreetscapeGeometryNode` it subclasses. That is a toggle, and shipping it as two cards
  taught the reader that ARCore has two scene-geometry APIs when it has one with an
  optional classification layer. Both capabilities are intact — the classified colour map
  and its legend on Mesh, the single-material raw mesh on Streetscape — and each mode keeps
  its own `ARSceneView` and `rememberEngine`, so switching tears the inactive ARCore
  session and Filament engine down completely. 50 cards → 49.
  `sceneview://demo/ar-streetscape` still opens the card **on the Streetscape mode**, so no
  QR code, doc link or Maestro leg lost its target or its coverage; the card carries the
  `KnownIssue` badge the Streetscape half already had, because both modes still need an
  outdoor location with Street View coverage and an ARCore Cloud API key.
<!-- RELEASE NOTE (maintainer-only):
     The merged screen keeps the id `ar-scene-mesh` rather than minting a new one: an id is
     a public deep-link surface and iOS ships its own (LiDAR) screen under the same one, so
     retiring it would have cost a parity row for a rename. Two counts were wrong before
     this PR and are recounted, not inherited: `parity-manifest.yml`'s section banners said
     32 working / 20 android-only against 31 / 19 actual, and its `ar-cloud-anchor` row
     still read `KnownIssue` after #3451 moved that fragment to `InReview`. Both are fixed
     here. Note for whoever picks up the next merge in #3463: the header of that manifest
     names `.claude/scripts/check-demo-id-parity.sh` as the CI guard that recounts the
     banners — that script does not exist in the repository, which is why the banners could
     drift behind a green CI in the first place. -->
