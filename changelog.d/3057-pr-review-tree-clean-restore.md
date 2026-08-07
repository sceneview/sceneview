<!-- category: Fixed -->

- CI: `pr-review.yml`'s clean-tree assertion no longer fails every PR that
  touches `.claude/**`. `claude-code-action` reverts eight config paths
  (`.claude/`, `.mcp.json`, `CLAUDE.md`, …) to the base branch before the CLI
  starts, because the CLI reads settings and hooks from cwd and a PR head is
  untrusted — so `git status` was dirty before a reviewer had read a line, and
  the error blamed the reviewers for it (#3057). The guard is not weakened and
  gains no path exclusion: `assert-review-tree-clean.sh` forgives a restored
  path only when its bytes *and* mode equal `origin/<base>` exactly, so a
  reviewer editing `.claude/` still blocks the job. Self-tested against real git
  fixtures, with a mutation test and a wiring check.
