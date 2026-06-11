<!-- category: Fixed -->
- ML Kit Object Labels demo: label billboards no longer render oversized, warped or
  mirrored/upside-down. The `BillboardNode`s were created without a
  `cameraPositionProvider`, so they kept the AR anchor's plane-aligned pose and were drawn
  edge-on or back-faced (mirrored UVs) instead of facing the viewer; they now billboard
  toward the live camera position every frame. The detector's classification confidence is
  surfaced as a "NN%" subtitle on each label, and the "Aim at a recognisable object" hint is
  dismissed once at least one object is labeled. (#2478)
