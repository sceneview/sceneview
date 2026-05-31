---
description: Launch-and-go continuous issue-processing cycle — a replace-on-completion pipeline of 6-8 lean-clone background agents, fire-and-forget merge, disk-gated spawn, release checkpoint per iteration.
---

# /issue-batch — SceneView continuous issue cycle

You are the **orchestrator** of SceneView's continuous issue-processing cycle. Your
job is to **audit, dispatch, and monitor — never to code yourself**. A maintainer
starts a session, says "run the cycle", and you keep a steady pipeline of background
agents flowing until the backlog is drained or they ask you to stop.

> **Executable form.** The mechanical per-issue loop is now the **`fix-issue-batch`
> saved workflow** (`Workflow({ name: "fix-issue-batch" })` → `.claude/workflows/fix-issue-batch.js`):
> claim → lean-clone → fix → self-review → fire-and-forget merge → release-claim, with
> `claim.sh` killing the #2300 race. This skill is the orchestrator's *playbook* (the
> *why*, plus the audit + release-checkpoint wrapping); the workflow is the *how* for the
> fan-out. Run the workflow for the loop; use `audit-sweep` / `release-checkpoint` workflows
> for those phases.

The validated operating model (formalized 2026-05-15) is:

```
AUDIT → AUTO-FILE ISSUES → BATCH → DISPATCH PIPELINE → SELF-REVIEW + CI → MERGE → TIER-2 (risky only) → re-AUDIT
```

**Master playbook:** the full rationale lives in agent memory
`feedback_continuous_issue_cycle.md`. Supporting detail: `feedback_issue_workflow.md`
(Rapporteur/Correcteur split), `feedback_pr_review_workflow.md` (Tier 1 / Tier 2),
`feedback_resource_hygiene.md` (disk gating). Read those for the *why*; this file is
the *how* — keep it as the executable checklist.

⛔ **Harness limitation (issue #1243)** — background agents launched via `spawn_task`
or `Agent(isolation="worktree")` **cannot spawn nested `Agent()` reviewers**. The
harness silently rejects nested calls. So real multi-agent review only happens at the
**orchestrator level** (Tier 2). Each background agent does a single self-review pass
(Tier 1).

---

## Core operating rules (the launch-and-go contract)

1. **Pipeline, not waves.** Keep **6-8 background agents running at all times**.
   The moment one signals done, immediately dispatch the next batch into the freed
   slot — no idle gap waiting for a whole wave to finish.
2. **Disjoint modules only.** Concurrent agents must touch non-overlapping module
   trees: `sceneview/` ≠ `arsceneview/` ≠ `sceneview-core/` ≠ `samples/ios-demo/` ≠
   `samples/android-demo/` ≠ `mcp/` ≠ `sceneview-web/` ≠ `.github/workflows/` ≠
   `docs/`. If two ready batches collide, serialize them.
3. **Lean clone per agent.** Every agent works in its OWN shallow sparse clone and
   deletes it on exit. A full clone is ~2.3 GB; a lean one is ~0.3-0.6 GB — this is
   the disk-bloat fix. See the agent brief template below.
4. **Disk-gated spawn.** Before spawning, check free disk. **Refuse to spawn a new
   agent if free space < 15 GB** — wait for an in-flight agent to return and clean
   up first. (`df -g / | tail -1`.)
5. **Fire-and-forget merge.** Each agent pushes, opens its PR, runs
   `gh pr merge --squash --auto`, then exits. It does **not** sit watching CI. The
   orchestrator monitors for stuck PRs (see Phase 4).
6. **Release checkpoint each iteration.** At the end of every cycle iteration, run a
   release checkpoint (`/release` or `release-checklist.sh`). **Semver is capped at
   minor — major `4` is frozen** (cf. `feedback_version_policy.md`). v5 is a
   deliberate milestone, never an auto-bump.
7. **Autonomous dispatch; consult only on non-trivial decisions.** Routine batching
   and dispatch needs no approval. Pause and ask the maintainer only for: scope
   ambiguity, breaking-change strategy, a revert, or a cross-cutting design call.
8. **Verify before dispatch.** Confirm each issue is still OPEN and not already
   fixed on `main`. Stale issues are common — don't dispatch a no-op batch.
9. **Auto-file everything.** Every finding — maintainer remark, in-passing
   discovery, Tier-2 result — becomes a GitHub issue immediately. Track in GitHub,
   never just remember.

---

## Phase 0 — Setup (once per session)

- `df -g / | tail -1` — record free disk. If < 15 GB, clean stale `/tmp/sv-*`
  clones and worktrees before starting.
- Confirm `feedback_continuous_issue_cycle.md` + `feedback_issue_workflow.md` exist
  in memory.
- `.claude/handoff.md` reflects current state; git status of the orchestrator
  checkout is clean.

---

## Phase 1 — Audit (Rapporteur)

Launch in parallel via `Agent` `subagent_type=Explore` (read-only, fast):

1. **Issue triage** — `gh issue list -L 100 --state open` → categorize into
   `BUG_CRITICAL / BUG / PARITY / REFACTO / CI / DOCS / TEST`. Identify natural
   batches by module. Flag stale issues already fixed on `main`.
2. **Parity gaps** — Android (Filament) vs iOS (RealityKit) vs Web (Filament.js)
   public API surface. List untracked gaps. Don't redocument umbrellas.
3. **CI optimization** — audit `.github/workflows/*.yml` for parallelization,
   cache, path-guards, sharding. Cross-reference recent `gh run list` failures.
4. **Code smells** — `rg "TODO|FIXME|XXX|HACK"`, threading violations, coroutine
   leaks, resource-cleanup gaps, our own deprecated-API usage, low-coverage modules.

Each agent reports under 1000 words, markdown. After audit: **file new issues** for
every untracked finding; **close stale issues**; build the batching table.

---

## Phase 2 — Batching

Group issues by category + module. Rules:
- 1 batch = 1 background agent = 1 lean clone.
- 3-8 issues per batch (a 1-2 issue fix the orchestrator can do inline isn't a batch).
- Priority order: **CI > BUG_CRITICAL > BUG > PARITY > REFACTO > DOCS > TEST**.
- Mark inter-batch dependencies (e.g. a CI-parallelization batch lands before
  release-related batches).
- Modules must be disjoint across concurrently-running agents (rule 2).

No approval gate for routine batches — dispatch straight into Phase 3. Pause only
for the non-trivial decisions in rule 7.

---

## Phase 3 — Pipeline dispatch (replace-on-completion)

Maintain 6-8 agents in flight. For each free slot, while disk ≥ 15 GB and a
disjoint batch is ready, call `mcp__ccd_session__spawn_task` with:

- **title** : `Fix batch <name> (#<issues>)`
- **tldr** : 1-2 sentences, no file paths
- **prompt** : full self-contained brief ending **verbatim** with this block:

```
=== INSTRUCTIONS DE SESSION ENFANT (NE PAS IGNORER) ===

## ⛔ LEAN CLONE ISOLATION (disk-constrained machine)

Work ONLY in your own shallow sparse clone — never a full clone:

  git clone --depth 1 https://github.com/sceneview/sceneview.git /tmp/sv-<issue>
  cd /tmp/sv-<issue>
  git sparse-checkout set <module dirs you need, e.g. sceneview arsceneview docs>
  git checkout -b claude/<issue>-<slug> origin/main

If a script needs a missing path: `git sparse-checkout add <path>`.
⛔ When done (after the PR is merging), `rm -rf /tmp/sv-<issue>`. A full clone is
~2.3 GB; a lean one ~0.3-0.6 GB — keeping it lean is mandatory.

## Workflow

a. Verify each issue is still OPEN and not already fixed on `main`. Skip no-ops.
b. Implement the batch (clean refactor; breaking changes OK if justified — but
   never bump major: 4 is frozen, cf. feedback_version_policy.md).
c. Compile: `./gradlew :sceneview:compileReleaseKotlin :arsceneview:compileReleaseKotlin`
   (+ iOS/Web targets if touched).
d. Tests: `./gradlew :sceneview:test :arsceneview:testDebugUnitTest` (+ MCP if touched).
e. Tier 1 — single self-review pass, 5 angles (DO NOT spawn nested Agent(),
   harness rejects it — issue #1243):
   - Security: input validation, secrets, permission scopes, deserialization.
   - Threading: Filament JNI on main thread, coroutine leaks, races, lifecycle.
   - API consistency: Android/iOS/Web naming parity, KDoc, deprecation hygiene.
   - Performance: hot-path allocations, draw calls, resource destroy order.
   - Docs: llms.txt, KDoc, cheatsheet, migration notes, MCP examples.
   Document findings in the PR description; fix anything actionable before merge.
f. QA visuel obligatoire with real interactions: Android emulator
   (`bash .claude/scripts/qa-android-demos.sh`), iOS simulator, Chrome MCP for web.
g. Sync: `bash .claude/scripts/impact-check.sh` + update CLAUDE.md handoff +
   llms.txt + MCP + cheatsheet. Changelog: add a `changelog.d/<issue>-<slug>.md`
   fragment (NOT an edit to CHANGELOG.md — see changelog.d/README.md).
h. FIRE-AND-FORGET MERGE: `git push -u origin <branch>` →
   `gh pr create --repo sceneview/sceneview` (English, `Closes #<issue>`) →
   `gh pr merge <PR#> --repo sceneview/sceneview --squash --auto` → exit.
   Do NOT sit watching CI — the orchestrator monitors stuck PRs.
i. `rm -rf /tmp/sv-<issue>`.

## Rules
- No polling/sleep loops (feedback_no_infinite_loop_polling.md).
- No full `connectedDebugAndroidTest` after a trivial commit.
- No image > 1800 px.
- Email git = thomas.gorisse@gmail.com. Remote = origin. English everywhere.

## Signal de fin obligatoire
End your LAST message with EXACTLY this block (no variation, no text after):

   ✅ SESSION TERMINÉE — vous pouvez fermer cet onglet.
   Batch: <nom>
   Commits: <SHAs>
   Issues fermées: #<n1>, #<n2>, ...
   Follow-ups filés: #<n3>, #<n4>, ...
   PR: <#num>

Ne poll pas pour d'autre travail. La session principale orchestre.
```

---

## Phase 3.5 — Tier-2 audit (risky PRs only)

Run a Tier-2 multi-reviewer audit **only on risky merges**: umbrella issues,
rendering-pipeline changes, breaking changes, anything touching threading or
release plumbing. Routine fixes (docs, single-module bug, CI tweak) skip Tier 2 —
their Tier-1 self-review + green CI is enough.

For a risky merged commit `<SHA>`, the orchestrator (this session — agents cannot)
spawns 5-7 parallel Opus reviewers, one angle each
(Security / Threading / API consistency / Performance / Docs):

```
Agent(subagent_type="general-purpose", model="opus", run_in_background=true,
  prompt="Post-merge reviewer for SceneView commit <SHA> on main (batch <name>).
  Review angle: <angle>. Read-only: `git show <SHA>`, touched files, cross-platform
  parity. Deliver <=500 words: verdict 🟢 ship | 🟡 follow-up | 🔴 hotfix, blockers
  with file:line, suggested follow-up issue. Do NOT push or spawn nested agents.")
```

Process: 🟢 → done. 🟡 → `gh issue create` follow-up, no revert (PRs are atomic).
🔴 → spawn a fresh hotfix batch (never amend the merged commit).

---

## Phase 4 — Monitor & iterate

The orchestrator runs continuously:
- **Refill the pipeline** — as each agent signals `✅ SESSION TERMINÉE`, immediately
  dispatch the next disjoint batch into the freed slot (disk permitting).
- **Watch for stuck PRs** — since agents fire-and-forget, periodically
  `gh pr list --repo sceneview/sceneview --state open`. A PR whose `--auto` merge is
  blocked by a red check or conflict is the orchestrator's to triage: re-dispatch a
  fix batch, or escalate to the maintainer if it's a non-trivial decision.
- **Collect** commits, closed issues, follow-ups from each returning agent.
- **Release checkpoint** — at the end of each iteration run `/release` (or
  `release-checklist.sh`). Cut a minor/patch release if the batch warrants it;
  never a major (rule 6).
- **Re-audit** — when the current batch table is drained, loop back to Phase 1.
- Update `.claude/handoff.md` with a `Cycle N: batches X-Y closed` section.
- Continue until the backlog is empty or the maintainer says stop.

---

## Workflow diagram

```
Phase 1  Audit (Explore agents) ──► auto-file issues, close stale
Phase 2  Batching table (disjoint modules, priority order)
Phase 3  Pipeline dispatch — 6-8 lean-clone agents, replace-on-completion
         • agent: lean clone → fix → Tier-1 self-review → QA → fragment changelog
         • agent: push → gh pr merge --squash --auto → rm -rf clone → exit
Phase 3.5  Tier-2 audit — orchestrator spawns 5-7 Opus reviewers (RISKY PRs only)
Phase 4  Monitor: refill freed slots, triage stuck PRs, release checkpoint
         └─► re-audit (back to Phase 1) until backlog empty
```

---

## Anti-patterns to avoid

- ❌ Wave-batching — waiting for a whole batch of agents to finish before
  spawning the next. Use replace-on-completion: refill freed slots immediately.
- ❌ Full `git clone` for an agent — bloats the disk-constrained machine. Always
  `--depth 1` + `git sparse-checkout`, and `rm -rf` on exit.
- ❌ Spawning past the disk gate — never start a new agent below 15 GB free.
- ❌ Concurrent agents on overlapping modules — guaranteed merge conflicts.
- ❌ Agent watching CI after `gh pr merge --auto` — that's fire-and-forget; the
  orchestrator owns stuck-PR triage.
- ❌ Asking the child agent to spawn nested reviewers (silent no-op, issue #1243).
- ❌ Skipping the release checkpoint at iteration end.
- ❌ Auto-bumping major version — 4 is frozen (feedback_version_policy.md).
- ❌ Dispatching a stale issue already fixed on `main`.
- ❌ Editing `CHANGELOG.md` directly — use a `changelog.d/` fragment.
- ❌ Letting child sessions run without the `✅ SESSION TERMINÉE` end signal.

---

**Tone**: direct, autonomous. Keep the pipeline full; pause only for the
non-trivial decisions in rule 7.
