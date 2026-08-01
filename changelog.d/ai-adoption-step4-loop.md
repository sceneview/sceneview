<!-- category: Added -->
- Agent review now runs **in CI**, not only inside a live session. The four
  reviewer mandates (`sv-code-reviewer`, `sv-security-reviewer`,
  `sv-impact-reviewer`, `sv-doc-freshness`) fan out on every non-draft PR via
  `pr-review.yml`, every ERROR is adversarially verified before it counts, and
  the verdict is posted as one comment updated in place. Until now those
  reviewers only ran through the `review-fanout` saved workflow, which coupled
  every merge to someone having a Claude Code session open — measured
  2026-08-01, the five most recently merged PRs carried zero review recorded on
  GitHub. The reviewers FIND; `grade-pr-review.sh` DECIDES, deterministically,
  mirroring `review-fanout.js` so both paths reach the same verdict. It fails
  closed: a missing verdict file, unparsable JSON, or a dropped reviewer is
  `REVIEW_INCOMPLETE` (blocking), because a crashed fan-out produces no
  findings and would otherwise be indistinguishable from a clean review. A
  confirmed `sv-impact-reviewer` error remains the maintainer gate. Fork PRs
  cannot be reviewed (GitHub withholds secrets, and `pull_request_target` is
  deliberately unused) and say so loudly instead of reporting a silent green.
- Agent token use is now **measured** — `agent-cost-report.sh` aggregates the
  local session transcripts by day / model / session / branch. The repo had no
  instrumentation at all (no OTel, no analytics, no counter), so the step-3
  bottleneck of "are tokens used efficiently" was managed by feel. It reports
  tokens and never dollars — this is a flat Max plan, so a dollar figure would
  be an invented number wearing a measurement's clothes — and groups `--by
  model`, which is the actionable view because the quota is per-model.
  Everything is keyed on `requestId`: a transcript writes several records per
  API call carrying the same `usage`, and summing records overstates output
  tokens by ~95% (measured: 980 usage records for 658 real requests).
- Claude now starts some work without being asked. `issue-intake.yml` gains a
  `triage` job that runs *after* the deterministic labeller (never replacing
  it) and comments duplicate/reproducibility/location/cross-platform findings
  on newly opened issues; `maintenance.yml` gains `digest-to-tasks`, turning
  the daily digest from a report into individually actionable, de-duplicated
  issues. The issue body is treated as untrusted data in both directions — it
  is never interpolated into a `run:` step *or* into the prompt, the agent
  fetches it with `gh` and is told explicitly that what it reads is data, not
  instructions. `digest-to-tasks` is capped at 3 new issues per run, and the
  cap is verified by a deterministic step that reddens the run when exceeded
  rather than trusting the prompt; a healthy repo files zero.

<!-- category: Tests -->
- `test-grade-pr-review.sh` and `test-agent-cost-report.sh` pin the two new
  guards in `ci.yml` → `repo-hygiene`, both with a **mutation test**. Removing
  the reviewer-count check makes a 3-of-4 review grade `MERGE`; re-keying the
  cost dedup from `requestId` to `uuid` inflates the fixture total from 350 to
  450. Both mutations turn the suite red, so neither guard can regress into a
  silently-green no-op (the #2947 failure mode). Writing them also corrected
  two wrong assumptions: the missing-file branch in the grader is redundant
  with its own `try/except`, and the cost report's dedup comes from the choice
  of key, not from the `continue` that feeds the duplicate counter.
