<!-- category: Changed -->
<!-- Maintainer note: driven by measurement, not taste. 30 days of session telemetry
     showed the cost driver was call granularity (32,941 Bash calls at a 341-byte median
     result), not subagent fan-out (183 spawns / 7 days). The infrastructure had grown
     to 26 skills + 11 workflows + 96 scripts for 61 skill invocations in 30 days. -->

- Rebuilt the contributor-facing working method around three measured laws — group calls, bound every result, keep one session to one subject — replacing ~40 lines of unenforced prose.
- Removed 14 `PostToolUse` reminder hooks that fired *after* the action they advised on, and were redundant with either a blocking gate or `CLAUDE.md`. A hook now either blocks or does not exist.
- Deleted four saved workflows with no entry point (`device-qa-orchestrate`, `doc-drift-fix`, `phase2-reconcile`, `release-checkpoint`) — each duplicated a script or a slash command that is the real path. `triptych` was in that list until a check found `/review high` invokes it; it stays, and the workflow README now states the rule it was deleted against: live means something invokes it.
- `/release` no longer stops to ask for the version or for permission to push — the version is derived from the changelog fragments (breaking → minor, else patch) and the gates are the authority.
- `CLAUDE.md` 263 → 121 lines and `.claude/workflows/README.md` 204 → 90, both re-sent on every turn of every session.
