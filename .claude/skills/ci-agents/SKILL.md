---
name: ci-agents
description: Everything agent-driven in CI: the @claude mention bot and its OAuth auth, the agent PR-review fan-out with its DETERMINISTIC fail-closed grader (generator != evaluator, advisory not blocking), agent cost instrumentation (tokens never dollars, dedup on requestId), and the event-driven jobs with their MEASURED daily budgets. Use when changing pr-review.yml / claude.yml / issue-intake.yml / maintenance.yml, when a review verdict looks wrong or incomplete, or when measuring what the agents cost.
---

## @claude mention bot (GitHub Action)

[`.github/workflows/claude.yml`](/.github/workflows/claude.yml) runs the official
[`anthropics/claude-code-action@v1`](https://github.com/anthropics/claude-code-action)
whenever a contributor drops **`@claude`** in any of:

- a new issue body or title
- an issue comment
- a PR review or PR review comment

Claude reads the repo (full git history), the issue/PR context, and replies in
place — proposing a fix, opening a PR, or answering questions. Open-source
contributors benefit too; they don't need an Anthropic account.

**Auth — OAuth via Claude Max** (no per-call API spend). Generate the token
once on a logged-in machine and push it to the repo secret:

```bash
claude setup-token                                                    # outputs an OAuth token
gh secret set CLAUDE_CODE_OAUTH_TOKEN -R sceneview/sceneview -b "<token>"
```

The token is long-lived; rotate via the same flow if revoked. Cost is on
Thomas's Max quota; gate every fire with an explicit `@claude` mention so
Dependabot etc. never trigger it. Concurrency is keyed per issue/PR — a second
mention cancels a still-running earlier reply.

## Agent review in CI (`pr-review.yml`)

The four reviewer mandates in [`.claude/agents/`](/.claude/agents/) used to run
**only inside a live Claude Code session**, via the `review-fanout` saved
workflow. That coupled every merge to a human having a session open: measured
2026-08-01, the five most recently merged PRs (#2947 → #2930) carried **zero
review recorded on GitHub**, because the review happened where GitHub cannot
see it. [`pr-review.yml`](/.github/workflows/pr-review.yml) moves the same
fan-out onto the PR, so an agent-authored PR is reviewable — and mergeable —
without a session.

**Generator ≠ evaluator, enforced structurally.** The agents FIND: they write
`review-verdict.json` (per-reviewer verdicts, adversarially-verified errors,
warnings, propagation). `grade-pr-review.sh` DECIDES: it computes the merge
verdict in bash/python, mirroring the grading block of `review-fanout.js` so
the in-session and in-CI paths agree. The model never grades its own findings.

**It fails closed.** A missing verdict file, unparsable JSON, or a dropped
reviewer is `REVIEW_INCOMPLETE` — blocking — because a crashed fan-out produces
no findings, which is otherwise indistinguishable from a clean review. A
confirmed error from `sv-impact-reviewer` sets `breaking_api` and is the
maintainer gate, never an auto-fix. `test-grade-pr-review.sh` pins all of this
in `repo-hygiene`, **including a mutation test**: strip the reviewer-count
check and a 3-of-4 review grades `MERGE`, which turns the suite red.

**Fork PRs cannot be reviewed** — GitHub withholds secrets from fork
`pull_request` runs, and `pull_request_target` (which would hand secrets to
fork-authored code) is deliberately not used. Such a run says so loudly in the
job summary plus a `::warning::`, and never reports a silent green review. Get
coverage with `gh workflow run pr-review.yml -f pr=<n>`.

⚠️ **This is the repo's most expensive quota consumer**, and the volume is
measured, not guessed: this repo peaks at **30 PRs in a single day** (21 on
several others), and each review spawns 4 reviewers plus one adversarial
verifier per error — 120+ agent runs on an active day if every PR is reviewed.
Three things bound it: `concurrency` (a push burst on one PR costs one review),
**bot-authored PRs are excluded** (7 of the last 100 PRs were Dependabot — a
dependency bump does not need four Opus reviewers arguing about KDoc), and
`MAX_REVIEWS_PER_DAY` (25). Over budget, the review is skipped **loudly** with
the dispatch command to force one — an unreviewed PR must never look reviewed.

**`Agent review` is ADVISORY — it does not hold the merge button.** It is
listed in `ci-gate.yml`'s `ADVISORY_CHECKS`, so its conclusion never decides
the `CI Gate` aggregator that branch protection actually requires. That is the
same rule the repo applies to `check-doc-drift`: an LLM review is a heuristic,
and blocking a heuristic guarantees false positives that erode trust in the
whole gate. A wrong `DO_NOT_MERGE` should cost one read of the review comment,
not a frozen repository.

Nothing about that makes it quiet: the check still goes RED, the verdict is
still posted on the PR, and the grader still fails CLOSED. It also avoids a
structural deadlock — `claude-code-action` refuses to run on any PR that edits
`pr-review.yml`, so a blocking check would make every future change to the
review workflow unmergeable by design. Promote it to blocking only after
enough runs to *measure* the false-positive rate, the same bar the advisory
device-QA legs have to clear.

## Agent cost instrumentation

Step-3 autonomy's stated bottleneck is *"ensuring tokens are used efficiently
as usage increases"* — and until 2026-08-01 the repo had **no** measurement:
no OTel export, no analytics, no counter. Quota was managed by feel.

```bash
bash .claude/scripts/agent-cost-report.sh --days 7 --by model
```

reads the ground truth Claude Code already writes to
`~/.claude/projects/<slug>/*.jsonl` and reports where the tokens went, grouped
by `day` / `model` / `session` / `branch`. `--json` for machine consumption,
`--all` for every project, `--days 0` for everything on disk.

**It reports tokens, never dollars.** This account is on a flat Max plan, so a
dollar figure would be an invented number wearing a measurement's clothes. The
quantity that binds is quota, and output tokens dominate it. Grouping `--by
model` is the actionable view, because the quota is **per model**.

⚠️ **Deduplication is the whole correctness story.** A transcript writes
several records per API call, each carrying the *same* `usage` block: measured
on one real session, 980 usage records for 658 distinct `requestId`s — summing
records overstates output by **~95%**. Everything is keyed on `requestId`.
`test-agent-cost-report.sh` pins it in `repo-hygiene` with a mutation test on
the dedup key (re-key to `uuid` and the fixture total inflates 350 → 450).

**OTel is the opt-in complement, not a replacement.** If a collector is ever
available, `CLAUDE_CODE_ENABLE_TELEMETRY=1` plus the standard `OTEL_*` exporter
variables ship the same data live. It is deliberately NOT configured here:
without a collector endpoint it would export nowhere and read as instrumented
when it is not.

## Event-driven agents

Two jobs let Claude start work no one asked for by hand:

| Job | Fires on | Guard |
|---|---|---|
| `issue-intake.yml` → `triage` | an outside reporter opens an issue | runs AFTER the deterministic labeller, never replaces it; bots and write-access authors excluded; 10 runs/day budget |
| `maintenance.yml` → `digest-to-tasks` | daily cron | hard cap of 3 new issues/run, **measured** after the fact; mandatory dedup; anomaly-only |
| `claude.yml` → `claude` | `@claude` mention | 20 real runs/day budget (skipped triggers excluded from the count) |

⛔ **A public repo's issues and comments spend the maintainer's quota.** Fork
`pull_request` runs get no secrets, so `pr-review.yml` structurally cannot
spend anything on an outside contributor's PR — but `issues: opened` and
`issue_comment` fire in the BASE repo, where secrets *are* available. Anyone
can therefore spend Claude Max quota by opening an issue or typing `@claude`,
and `concurrency` does not help: it is keyed per issue/PR, so distinct threads
never queue behind each other. Both jobs carry a daily budget.

Two calibration facts, both measured 2026-08-01 — recheck them before changing
a threshold, because both are counter-intuitive:

- **Most `claude.yml` runs cost nothing.** The workflow is triggered by every
  issue/comment event and the job's `if:` gate skips the ones without a
  mention. 15 runs that day, *all* skipped; zero real executions across the
  last 200 runs. Counting raw runs would have capped the bot on 2026-07-20
  (46 triggers, 0 real spend). The budget counts `conclusion != "skipped"`.
- **Most issues don't need triage.** Of the last 200: 186 opened by the
  maintainer, 8 by `github-actions`, 6 by outside reporters. Triaging an issue
  its own author wrote seconds ago spends ~93% of the budget on noise, so
  `OWNER`/`MEMBER`/`COLLABORATOR` are excluded. This is the *opposite* of
  gating on "is this person a collaborator" — that would kill triage exactly
  where it earns its keep.

⛔ **The issue body is untrusted input, in both directions.** `issue-intake.yml`'s
original header documents why it is never interpolated into a `run:` step
(shell injection). The triage job extends the same rule to the *prompt*: a
crafted issue body reaching the prompt is the LLM equivalent of that bug, and
it would arrive holding `issues: write`. The agent fetches the issue itself
with `gh` and is told explicitly that what it reads is DATA. The only
interpolated value is the issue number, which GitHub guarantees is an integer.

⛔ **Anti-spam is `digest-to-tasks`'s design constraint**, not a nicety — an
agent with `issues: write` on a daily cron is a burst waiting to happen. The
cap is stated in the prompt *and* verified by a deterministic step that reddens
the run when exceeded: the prompt asks, the check measures. The job creates the
`auto-filed` label itself, because the prompt forbids the agent from creating
labels — without that step the issues would carry no label, the before/after
count would read 0 → 0 forever, and the cap would be a guard measuring nothing.
A healthy repo files **zero** issues; an empty run is the expected outcome.

