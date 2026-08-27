<!-- category: Fixed -->
- **A release whose PR auto-merges is now always tagged ([#3361](https://github.com/sceneview/sceneview/pull/3361)).** GitHub
  emits no workflow events for pushes made with the default `GITHUB_TOKEN`, so when
  `release-fast.yml`'s release PR auto-merged, the merge produced no `push` event and the
  `push`-triggered `Tag release` workflow never ran: v4.33.0 sat on `main` with
  `VERSION_NAME` bumped, no tag, no publications and nothing red anywhere until a human
  noticed. `release-fast.yml` now owns the tag in the same run — a `tag` job waits for the
  PR it opened to merge, tags the merge commit and dispatches `release.yml` — so the
  release no longer depends on an event chain, and a PR that never merges ends in a red
  run instead of a silent half-release. `tag-release.yml` remains as the human-merge
  safety net and a manual recovery handle; both callers share the idempotent
  `.claude/scripts/tag-release.sh`.
