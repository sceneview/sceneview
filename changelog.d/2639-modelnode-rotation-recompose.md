<!-- category: Fixed -->
- `ModelNode` no longer re-applies its declared `rotation` (and `position`/`scale`) on every recomposition — a gesture-rotated model is no longer silently reset to its declared transform when an unrelated state change triggers a recomposition (#2639).
