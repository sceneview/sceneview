<!-- category: Fixed -->
- **The pre-push gate now finds a node installed by nvm ([#3202](https://github.com/sceneview/sceneview/issues/3202)).**
  It announced `node not found` and skipped its two JS legs on a host where node
  works — measured four times across 2026-08-15 → 16. The gate runs from a
  non-interactive shell, which never loads `nvm.sh`, so `which node` returned
  nothing while node was installed. Resolution order is `$NODE_CMD` → PATH →
  Homebrew → nvm's default alias → its newest install, version-sorted so `v9`
  cannot outrank `v22`, and overridable through `$NODE_RESOLVE_PREFIXES`.
