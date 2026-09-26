# Migration Guide

The full SceneView migration guide lives in **[`docs/docs/migration.md`](docs/docs/migration.md)** —
that's the canonical source, published by the website at
<https://sceneview.github.io/docs/migration/>.

It covers, in chronological order:

- **3.6.x → 4.0.0** — Rerun integration, top-level rename `Scene → SceneView` /
  `ArScene → ARSceneView`, `MaterialLoader.createUnlitColorInstance(...)`,
  `ARRecorder` async API + `recordFrame(session)` stateless overload,
  Geospatial-anchor composables (`TerrainAnchorNode`, `RooftopAnchorNode`),
  the `@MainThread`-annotated loader sync overloads.
- **3.5.x → 3.6.0** — API simplification (`rememberModelInstance` lifecycle,
  loader-injection composables, post-processing module split).
- **2.x → 3.x** — full Compose rewrite (Sceneform deprecation, `ArSceneView` →
  `ARSceneView`, `TransformableNode` → `isEditable = true`,
  `PlacementNode` → `AnchorNode + HitResultNode`, `ViewRenderable` → `ViewNode`).

Open a PR against `docs/docs/migration.md` rather than this file — this stub
exists only so the GitHub root view surfaces a top-level pointer (audit #900,
canonicalisation: kept `docs/docs/migration.md`, removed `docs/docs/migration-v4.md`
which had been superseded by the v4 section of `migration.md`).
