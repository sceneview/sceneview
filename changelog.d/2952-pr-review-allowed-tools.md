<!-- category: Fixed -->
- `pr-review.yml` now grants the orchestrator the `Task` and git tools it needs. They
  are not in `claude-code-action`'s default set, so the four reviewers could never be
  spawned and the diff could never be computed: every review since the workflow landed
  ended as `REVIEW_INCOMPLETE` with no `review-verdict.json`. The git allowlist is
  per-subcommand — reviewers share one working tree, and a branch switch corrupts it
  for the others (#2431).
