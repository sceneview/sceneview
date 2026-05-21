<!-- category: Fixed -->
- `verify-sketchfab-key.sh`: dropped `curl -f` from the live API probe so the real HTTP status reaches the `case` — the `401|403` "token revoked" branch was unreachable and a revoked Sketchfab key silently passed the release guard.
- `docs.yml`: ref-scoped the workflow concurrency group (`pages-${{ github.ref }}`) so a release tag's two triggers (`push` + `release`) no longer self-cancel mid-deploy, while same-ref dedup is preserved.
- Removed the dead `.github/scripts/ar-emulator-screenshots.sh` — it had no caller anywhere in the repo.
- `telemetry-ci.yml`: added `branches: [main]` to the `push:` trigger so it no longer runs on every branch push.
- `check-workflow-scripts.sh`: now scans every workflow `if:` expression and fails on a context disallowed in `if:` (notably `secrets`) — the class of invalid-`if:` bug behind the v4.13.0 release startup-failure.
- `collate-changelog.sh`: the preamble splice now keeps every line before the first `## ` section instead of emitting only line 1, so intro prose between `# Changelog` and the first section is no longer dropped on release.
