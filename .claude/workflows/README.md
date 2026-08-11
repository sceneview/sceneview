# How we work

> Rewritten 2026-08-11, replacing a 204-line version that described ten workflows of
> which seven were marked TODO while their files sat on disk unrun. This file describes
> **what actually runs**. If a capability is not exercised, it does not belong here.

## The loop

`issue → claim → implement → verify → merge → release → verify live`

| Step | Runs via |
|---|---|
| Intake | every actionable item becomes a GitHub issue, immediately — never "I'll remember it" |
| Claim | `bash .claude/scripts/claim.sh <issue#>` + the `in-progress` label (the real cross-host lock) |
| Implement | one issue → one agent → one PR. Disjoint modules, so agents never share files |
| Verify | `bash .claude/scripts/pre-push-check.sh`, then `review-fanout.js` on anything non-trivial |
| Merge | `gh pr merge --squash --auto`. Fire and forget — never watch CI |
| Release | `/release`. Play rolls out automatically; App Store submission is automatic but Apple's review is not |
| Verify live | `/store-status` — an upload is not a release |

**No step in this loop asks permission.** The gates are the authority; a human is called
only when a gate breaks. → `CLAUDE.md` "What done means".

## Three tooling layers

Cheap and deterministic goes low; judgment goes high. A layer never re-implements the
layer below — it calls it.

| Layer | For | Rule |
|---|---|---|
| **Hooks** (`settings.json`) | Deterministic gates that must **block** | A hook either blocks or it does not exist. A reminder that fires *after* the action has no lever — it is context you pay for on every later turn |
| **Scripts** (`.claude/scripts/*.sh`) | Aggregated checks, one source of truth | ~100 of them; find one with the `automation-map` skill before writing a new one |
| **Workflows** (`*.js`) + **skills** | Multi-agent fan-out, human entrypoints | Thin — they call scripts, they do not re-encode them |

## The six live workflows

Live means: something invokes it. A workflow with no entry point is deleted, not kept
"for later" — four were, on 2026-08-11.

| Workflow | Trigger | Fans out |
|---|---|---|
| `review-fanout.js` | any non-trivial PR | 4 `sv-*` reviewers ∥ → adversarial verify of every ERROR → graded merge recommendation |
| `triptych.js` | `/review high`, pre-merge on a risky PR | reviews ∥ `impact-check` ∥ serial visual device-QA → CLEAR / BLOCKED. The executable form of the per-PR triptych rule |
| `fix-issue-batch.js` | continuous issue cycle | claim → fix → self-review → PR; medium+ routes through review-fanout |
| `parity-audit.js` | after a public API change | one auditor per target platform → verify each gap against source |
| `audit-sweep.js` | a bug reveals a *class* | parallel Explore across surfaces → umbrella + deduped sub-issues |
| `store-status.js` | post-publish | iTunes lookup + ASC reject states + Maven HTTP 200 |

Authoring rules: pure-literal `meta` · `pipeline()` by default, `parallel()` only when a
stage needs *all* prior results · `schema` for structured output · pin `{model, effort}`
on every `agent()` (never inherit — an inherited Fable default dies on the monthly quota
mid-fan-out) · resumable (no `Date.now()`/`Math.random()`). Validate with
`bash .claude/scripts/check-saved-workflows.sh`.

Model routing: mechanical probe → `haiku`/`low` · sweep, synthesis → `sonnet`/`medium` ·
code patch, independent review → `opus`/`high` · final safety gate → `opus`/`xhigh`.

## Parallelism

The orchestrator is serial and stateful: it owns `STATE.md`, dispatches, merges, hands
off — and **never edits code**. Agents run in disjoint modules, capped at 2 build-heavy
at once (disk and RAM are the binder). Never parallelised: device QA (one lease),
`/release` (one agent ever), interactive browser/console work, `git push`.

**Fan-out must earn its overhead** — a subagent costs ~60–90k tokens before it does any
work. Measured 2026-08-11: 183 spawns over 7 days was *not* the cost driver; 32,941 Bash
calls at a 341-byte median result was. Delegate a broad search to one agent that returns
a conclusion; never spawn for two or three checks.

## Quality

Blocking, every PR: independent review (generator ≠ evaluator — a `blocker:true` stops
the merge), updated-everywhere (`impact-check.sh`), and visual QA on anything a user
sees. "BUILD SUCCEEDED" is not a test.

Advisory, never blocking: `check-doc-drift.sh`, Play Vitals, render tests, coverage —
blocking a heuristic guarantees trust-eroding false positives.

Honest blind spots, never faked green: ARCore does not run on arm64 emulators, so true
AR is 3D-emulated or replay-only locally. A QA leg built without its API key is reported
`SKIPPED (path NOT tested)` — never as a pass.
