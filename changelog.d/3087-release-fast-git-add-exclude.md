<!-- category: Fixed -->
- `release-fast.yml` no longer dies right after collating the changelog. Staging the release
  commit with exclude pathspecs (`git add -A ':!device-qa-report.json' …`) makes git treat a
  gitignored match as explicitly named and exit 1 — only `device-qa-report.json` is actually
  gitignored, which is one too many — and under `bash -e` that killed the run before
  the release branch was ever pushed. The artifacts are now unstaged with `git reset` instead.
