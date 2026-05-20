<!-- category: Added -->
- arsceneview: new `ARSceneScope.DepthHitResultNode(xPx, yPx, content)` composable — Compose-idiomatic mirror of `HitResultNode` for placement against the ARCore depth image. Each frame re-runs `Frame.hitTestDepth` and moves to the resulting world-space surface point; `depthHitResult` exposes the live `DepthHitResult` for surface-normal-aligned content (#1814).
- docs: new migration block in `docs/docs/migration.md` for the `CloudAnchorNode.host()` return-type change (`Unit` → `HostCloudAnchorFuture`, #1768). Covers the source-compatibility break + the `DisposableEffect.onDispose { future.cancel() }` recommendation with billing rationale (#1814).
- llms.txt: `DepthHitResultNode` section and `Frame.hitTestDepth` `@return` KDoc clarification documenting the single-vs-list asymmetry vs `Frame.hitTest` (depth at one pixel is unique) (#1814).

<!-- category: Fixed -->
- arsceneview: defensive `onDispose` ordering on `ARScene`'s per-frame `IndirectLight` rebuild — clear `scene.indirectLight = null` BEFORE `engine.safeDestroyIndirectLight(...)` so a late `onARFrame` queued on the GL thread cannot dereference a freed native handle (#1814).
