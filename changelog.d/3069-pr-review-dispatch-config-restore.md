<!-- category: Fixed -->
- CI: `pr-review.yml` now restores `.claude/`, `.mcp.json`, `CLAUDE.md` and the
  other five sensitive config paths from the base branch when it runs on
  `workflow_dispatch`. `claude-code-action` performs that restore only under a
  pull-request context, so the dispatch path — the documented way to review a
  fork PR — previously ran the CLI against the checked-out head's own settings
  and hooks. Covered by a new self-test (`test-dispatch-config-restore.sh`)
  wired into the repo-hygiene job.
