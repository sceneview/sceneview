# SceneView — Unified Working Methodology (v2)

> **This directory is the operational home of how we work.** It holds the
> reusable multi-agent **saved workflows** (`*.js`) plus this README — the
> canonical, tracked methodology. It **supersedes** the ad-hoc prose scattered in
> CLAUDE.md, the per-skill rituals, and the old session files. When this document
> and any older note disagree, **this wins.** (The long-form design rationale +
> the audit that produced it live in the local, gitignored
> `.claude/plans/methodology-overhaul.md` and its appendix.)

---

## 1. North star & principles

1. **Real product, not a school project** — every change is judged by "would a paying user forgive this?". That is the pass/fail gate on scope, polish, UX, and comms.
2. **AI-first surface** — APIs, docs, `llms.txt`, samples exist so an AI generates correct code first-try; stale docs make an AI emit stale code, so **docs ship with the code**.
3. **Autonomy by default** — push, merge to main, release, decide. Humans are touched only at the six sanctioned pause points (§6).
4. **The orchestrator never codes** — it owns state, dispatch, gating, merge, handoff. All building and all heavy interactive work is delegated to background agents.
5. **Nothing reaches a user unverified** — the triptych (independent review + updated-everywhere + visual device-QA) is the backbone; an independent evaluator, never the generator, proves every change.
6. **CI-green is never proof of live** — upload ≠ submitted ≠ approved ≠ live. The real version is verified, never inferred from a workflow badge.

## 2. The unified lifecycle

One loop, refilling continuously. Each step names the exact artifact that runs it.

| # | Step | Runs via |
|---|---|---|
| 0 | **Bootstrap** (`<2 min`, no scrollback) | `SessionStart` hook → the `## BOOTSTRAP` block in `.claude/STATE.md` |
| 1 | **Intake** — every actionable item → a GitHub issue | `maintenance.yml` cron · Play-reviews→issues · `@claude` bot · audit-driven → **`audit-sweep.js`** |
| 2 | **Triage / claim** — kills the #2300 race | **`claim.sh <issue#>`** (+ the `in-progress` GitHub label) before any dispatch |
| 3 | **Plan** — chantiers only | Plan mode → `ExitPlanMode`; durable artifact in `.claude/plans/` |
| 4 | **Parallel implement** — 1 issue → 1 agent → 1 PR | **`fix-issue-batch.js`** → background worktree-agent pool, lean `clone --depth 1` |
| 5 | **Triptych verify** | **`triptych.js`** (reviews ∥ → visual-QA → gate) + `impact-check.sh` + `/code-review ultra` (Tier-2) |
| 6 | **Merge** — fire-and-forget | `gh pr merge --squash --auto`; agent exits, never watches CI; `ci-gate.yml` is the one required check |
| 7 | **Release checkpoint** — per iteration | **`device-qa-orchestrate.js`** then **`release-checkpoint.js`** |
| 8 | **Publish** — on tag | `release.yml` (parallel idempotent jobs: Maven / npm / RN / MCP / SPM / Pages) + stores |
| 9 | **Verify-live** | **`/store-status`** + `/sync-check` — iTunes lookup, ASC reject-states, Maven repo1 HTTP 200 |
| 10 | **Re-audit / continuity** | **`/handoff`** + weekly `doc-audit.yml` + the `weekly-maintainer` routine |

## 3. Tooling layers (zero overlap)

Rule: **cheap + deterministic → lower layer; expensive + judgment → higher layer.** A skill never re-implements what a script does — it *calls* it.

| Layer | Use it for | Examples |
|---|---|---|
| **Hooks** (`settings.json`) | Per-action, cheap, deterministic gates that must **block** (not remind) | version-equality, deprecated-API, secret-scan, `.filamat` ABI, spawn resource-gate |
| **Scripts** (`.claude/scripts/*.sh`) | On-demand aggregated checks, one source of truth | `quality-gate.sh`, `sync-versions.sh`, `impact-check.sh`, `device-qa.sh`, `claim.sh` |
| **Skills** (`.claude/commands/*.md`) | Human-facing entrypoints needing judgment; thin wrappers that **call** scripts/workflows | `/review`, `/sync-check`, `/store-status`, `/handoff`, `/maintain` |
| **Saved workflows** (`.claude/workflows/*.js`) | Multi-agent orchestration with deterministic, **resumable** control-flow | `triptych.js`, `fix-issue-batch.js`, `audit-sweep.js`, `release-checkpoint.js` |
| **Scheduled routines** (cron / `@claude` / GitHub-cron) | Unattended recurring work | `maintenance.yml`, `doc-audit.yml`, `weekly-maintainer`, monthly `consolidate-memory` |

Deterministic shell crons stay as GitHub-cron YAML; only **agentic reasoning** (vitals triage, review→issue) becomes a `/schedule` routine. **No new scheduler surface on top of these.**

## 4. Parallelism model — "parallel without going in all directions"

- **Orchestrator** (serial, stateful): owns `STATE.md`, dispatches workflows, arms merges, runs `/handoff`. **Never edits code.** Re-validates its own branch after every agent return (worktree-drift guard).
- **Agent pool** (parallel fan-out): 6–8 background worktree agents, **replace-on-completion** (refill per return, never wave-batched). Hard cap **2 build-heavy agents** concurrently — **load + disk are the binder**. Lean `clone --depth 1` into `/tmp/`; new worktrees locked immediately.
- **Saved workflows** run reviewer/audit fan-out at **workflow scope** (dissolves the nested-`Agent()` wall). `pipeline()` by default; `parallel()` only when all results gate the next step; `budget:"+500k"` on deep audits.
- **Serial-only** (never parallelised): emulator/sim **visual-QA** (one device lease, one dedicated agent), `/release` (one agent ever), interactive browser / ASC / Play Console work, `git push`.
- **Partitioning** = work is split by **disjoint module** (`sceneview` / `arsceneview` / `sceneview-core` / `SceneViewSwift` / `sceneview-web` — agents never share files) and **bounded by the claim registry** (`claim.sh` + the IN-FLIGHT table). Resource-gated at spawn by `disk-gated-spawn-check.sh` + a RAM gate.

## 5. Session continuity & state

**One living source of truth: `.claude/STATE.md`** (gitignored, fixed schema) — replaces CLAUDE.md's "Current state" block and the buried handoff anchor.

```
## NOW         ≤8 bullets — released version · what just shipped · what's broken
## IN-FLIGHT   the claim ledger — Key | branch | session | claimed | status
## NEXT        ≤6 ordered followups, each links one issue
## BOOTSTRAP   the commands a fresh session runs (<2 min)
```

**Each fact lives in exactly one place:**
- **`STATE.md`** → live "where are we" + the claim ledger. On session end, done `NOW` bullets **move** to handoff.
- **`handoff.md`** → append-only history ("why did we do X"); rotate at 400 lines → `handoff-archive/YYYY-QN.md`.
- **`memory/`** → durable cross-session rules/lessons only; **never** session snapshots.
- **GitHub issues** → all actionable work + claims; `STATE.md NEXT` only links them.
- **CLAUDE.md** → stable project facts + a 2-line pointer to `STATE.md`; **zero** session state.

**Claim protocol (kills #2300):** before dispatching an agent on an issue, run
`bash .claude/scripts/claim.sh <issue#>`. It (a) checks the local ledger, (b) checks
the GitHub `in-progress` label, (c) greps OPEN PRs referencing the issue, and refuses
on collision (exit 2). The **`in-progress` label is the real cross-host lock**; the
IN-FLIGHT table is the local mirror. Release with `claim.sh --release <issue#>` (the
release agent also clears labels on merge). **Resumable workflows**: an interrupted
`triptych.js` / `fix-issue-batch.js` resumes via `Workflow({scriptPath, resumeFromRunId})`.

## 6. Autonomy boundaries

**Fully autonomous (zero questions):** push, merge direct to main, release, version bump (≤ minor), strategy/tech choices, chaining tasks non-stop, no mid-task recaps, updating `STATE.md` / handoff / memory silently.

**`AskUserQuestion` fires only at these six pause points — and BEFORE starting, never mid-flight:**
1. **Scope ambiguity** — the issue's intent is genuinely unclear.
2. **Breaking change / revert** — anything breaking a public API or reverting shipped work.
3. **Major version bump** — `4` is FROZEN; `5.0.0` is a deliberate milestone, never automatic.
4. **Product-strategy call** — direction, not execution.
5. **External-PR submission** — ≤1/week, manual (GitHub-ban guard).
6. **Resource arbitration** — two competing big chantiers for one host.

A saturated session **never** stops mid-air: commit/push + `STATE.md` "START HERE" + a self-contained continuation relay issue, then close. **Self-feed relentlessly:** every point the maintainer raises, every finding, every deferred item becomes a GitHub issue *immediately* — never "I'll remember it". The open-issue backlog (not memory, not a TODO list) is the work queue; session closure files all remaining work as self-contained relay issues so the next session resumes with zero context loss.

## 7. Quality gates

**The triptych (BLOCKING, every PR):** (1) independent multi-agent Opus reviews — generator ≠ evaluator; a `blocker:true` verdict or any 1–2/5 score STOPS; (2) updated-everywhere (`impact-check.sh` + the all-platforms rule); (3) visual device-QA — "BUILD SUCCEEDED" is not a test.

**Per-release:** `device-qa.sh --platform=all` (**web leg BLOCKING**, android/ar **ADVISORY** — flaky #1643, never a silent pass) + **verify-live-store-state** (`/store-status`, a hard pre-tag step).

**Evidence-Stamped Claim Gate (ESCG, #2346) — the claims principle is now ENFORCED, not just stated.** Principle 6 ("CI-green is never proof of live") used to live only in prose; `.claude/scripts/claim-gate.sh` makes a false success-claim physically unable to reach the remote. Verifying tools stamp evidence on disk (`device-qa.sh` → `device-qa-report.json`; `/store-status` → `.claude/data/last-store-probe.json`), and the gate — wired onto the `Bash(git push*)` PreToolUse hook — **blocks the push** when the canonical `STATE.md` asserts a success-claim ("QA complete ✅", "all live ✅", "verified live") that lacks fresh, *agreeing* evidence (missing/stale report, a key-gated sub-leg `skipped`, or an iTunes-confirmed version mismatch). It fires ONLY on affirmative ✅-stamped claims, never on honest factual lines ("iOS LIVE=4.0.3 (4.17.0 in review)"), and fails **closed** on an unreadable evidence file. Escape hatch for a genuine false-positive: `ESCG_BYPASS=1 git push …`. The slow loop: `/caught <class> <ctx>` ledgers a miss the gate did not catch and, at the 3rd occurrence, promotes it to a `feedback_*.md` rule (`/handoff` is the backstop).

**Advisory (WARN, never block):** `check-doc-drift.sh`, Play Vitals, render-tests, coverage. Blocking a heuristic guarantees trust-eroding false positives.

**CI-drift guard:** every agent prompt mandates the regen script (`sync-versions.sh`, `collate-changelog.sh`) before push — stale generated/version files are the recurring red-main cause. `ci-gate.yml` is the single required check.

**Honest blind spot:** ARCore won't run on arm64 emulators — true AR (Cloud Anchor, VPS, face mesh) is 3D-emulated or replay-only locally and marked ADVISORY honestly. **Never fake a green AR leg.**

**Keyed QA or honest skip — never a silent green.** Demo QA must build WITH the API keys (`SKETCHFAB_API_KEY`, `ARCORE_API_KEY` — env → repo-root `local.properties`) so key-gated paths (Sketchfab Explore, ARCore Cloud) are actually exercised. A **debug** build with empty keys compiles silently and disables those paths, so QA that drives them tests a degraded build. If a key is absent, the harness marks that leg `SKIPPED (key missing — path NOT tested)` (advisory, never pass) and **LOUDLY alerts the human** — a keyless/degraded build is **never** reported as a complete QA. CI/QA-green ≠ feature-works (#2343).

---

## 8. Saved-workflow index

`.claude/workflows/` was empty — filling it is the single biggest unused-capability win. Status: **TODO** = to build · **LIVE** = built & validated.

| Workflow | Status | Trigger | Fans out |
|---|---|---|---|
| `triptych.js` | TODO | every PR pre-merge | 5 Opus reviewers (`ReviewVerdict` schema) ∥ → serial visual-QA agent → gate; `/code-review ultra` Tier-2 |
| `fix-issue-batch.js` | TODO | continuous issue cycle | claim → dispatch → triptych → merge → refill (replace-on-completion pool, disk/RAM-gated, fire-and-forget) |
| `audit-sweep.js` | TODO | a bug reveals a CLASS | `parallel()` Explore × 5 surfaces → structured findings → umbrella + deduped sub-issues |
| `release-checkpoint.js` | TODO | release window | bump → changelog → gate → device-QA → tag → verify-live (single-sources the version map; fixes #1705) |
| `device-qa-orchestrate.js` | TODO | per iteration + pre-tag | serial emulator lease → per-platform harness → grade → aggregate `device-qa-report.json` |
| `doc-drift-fix.js` | TODO | weekly (from `doc-audit.yml`) | `check-doc-drift.sh --audit` worklist → Opus patches → DRAFT PR |
| `store-status.js` | TODO | post-publish + on-demand | iTunes lookup + ASC reject-states + Maven HTTP 200 → STATE.md NOW |
| `phase2-reconcile.js` | TODO | backlog triage | 5 parallel surface agents → structured trackers |

## 9. Authoring conventions for saved workflows

Every `*.js` in this directory MUST:

1. **Start with a pure-literal `meta`** — `{ name, description, phases: [{title, detail}] }`. No computed values. Phase titles match the `phase()` calls.
2. **Default to `pipeline()`** (no barrier). Use `parallel()` only when a stage genuinely needs ALL prior results. Never add a barrier just to flatten/map — do that inside a stage.
3. **Use `schema` for any structured agent output** (verdicts, findings) — validation happens at the tool layer; no parsing.
4. **Source thresholds from the scripts, not hardcode** — call `device-qa.sh`, `sync-versions.sh`, `disk-gated-spawn-check.sh` rather than re-encoding their logic.
5. **Respect the hard rules** (§6 + below). No raw `adb` (use `android` CLI / `lib/android-cli.sh`). No oversized screenshots (>1800px), ≤5/session. No polling (`Monitor`/`ScheduleWakeup`/Cron instead of `while`/`sleep`). Visual-QA stays **serial** on one emulator lease.
6. **`isolation:'worktree'`** only for agents that mutate files in parallel; lean-clone in the agent prompt otherwise. `budget`-gate deep loops (`while (budget.total && budget.remaining() > 50_000)`).
7. **Report incrementally** (`log()` at phase boundaries) so a budget/session cap never loses everything.
8. **Be resumable** — pure of `Date.now()`/`Math.random()`; stamp timestamps after return or pass via `args`.

Validate every script before committing: `bash .claude/scripts/check-saved-workflows.sh`
(static: ESM `node --check` + meta-block + resume-safety; never executes the workflow).
That is distinct from `check-workflow-scripts.sh`, which validates the CI YAML in `.github/workflows/`.

## 10. Non-goals

No major version bump (`4` frozen, cap at minor) · no raw `adb` · no PR/issue burst on external repos (≤1/week, manual) · no oversized screenshots / no polling · no orchestrator-side coding or inline heavy interactive work · no "live ✅" from a CI upload **(now ENFORCED by `claim-gate.sh` on the `git push` hook — §7 ESCG #2346)** · no "QA complete" claim on a keyless / degraded build (keyed QA or honest SKIPPED — the gate blocks a complete-claim when a key-gated sub-leg is `skipped`) · no second `/release` or cycle-orchestrator per host · no new scheduler surface on top of Cron · no migrating deterministic shell crons to agentic routines · no Three.js / model-viewer on the website · no personal MCPs or employer/personal data in `sceneview/*`. We don't add scaffolding a newer model capability makes unnecessary.
