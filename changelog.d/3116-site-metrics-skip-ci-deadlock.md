<!-- category: Fixed -->
- The weekly community-metrics PR no longer carries `[skip ci]` in its commit subject. `CI Gate` is the single required context on `main` and a skipped run never reports it, so `gh pr merge --auto` waited on a check that could never arrive — #3075 sat open in that state with a body promising it would "merge itself once CI Gate reports green", and needed an admin merge.
- `check-workflow-scripts.sh` now fails any workflow that commits with a CI-skip marker and then asks for `gh pr merge --auto`. The pair is the bug; either half alone is legitimate. Matched case-insensitively and including the `skip-checks: true` trailer, since GitHub honours those identically.

<!--
Maintainer note — this PR reproduced its own bug. The first commit's subject
was `fix(ci): [skip ci] deadlocked …`, quoting the marker it was removing, so
GitHub fired ZERO workflows on the branch: `statusCheckRollup` was null, CI Gate
never reported, and the PR describing the deadlock sat in the deadlock. It read
as "BLOCKED with no failing checks", which is why the comment prose was replaced
by a gate — prose in a workflow file did not stop the very next author (me) from
writing the marker into a commit message one line away from it.

The new pass was verified both ways rather than by a new CI self-test step:
`WORKFLOWS_DIR` pointed at `git archive origin/main`'s workflows exits 1 and
names site-metrics.yml; the fixed tree exits 0. No new ci.yml step was added on
purpose — the script is already invoked by both ci.yml and pre-push-check.sh, and
touching either would collide with #3115 on the self-test counts.
-->
