<!-- category: Docs -->
- `Engine.renderableGeneration()` now documents all three call sites that bump it. It previously
  named `destroyRenderable` and `safeDestroyEntity` as "the only two calls that can reindex the
  array", omitting `ModelLoader.destroyModel` — which reindexes it natively through
  `AssetLoader.destroyAsset` without ever touching `RenderableManager` from Kotlin. Undercounting
  the reindex sites is exactly what made the stale-handle bug hard to see in the first place.
- `Engine.safeDestroyEntity` no longer says "all three" right after listing four components. It
  now states why the camera component has no generation counter: nothing in this codebase caches a
  camera instance handle.

<!-- category: Tests -->
- `quality-gate.sh` no longer dies without printing a verdict. Under `set -euo pipefail` a `grep`
  that matches nothing exits 1, `pipefail` promotes that to the command substitution and `set -e`
  kills the script on the spot — before the summary block. Four such pipelines were live; the one
  in the cross-platform section fired on the safest possible input (a diff touching
  `sceneview/src/` that adds no public API, i.e. a comment-only edit), so the gate reported
  "exited 1 without reaching its verdict" — indistinguishable from a real blocker. CI never saw it
  because there `git diff HEAD` is empty and the enclosing guard is false. Pinned by
  `test-quality-gate-pipefail.sh`: the shell semantics are measured in a child shell, and a static
  rule over the real file is proven falsifiable against a fixture carrying the pre-fix line.
