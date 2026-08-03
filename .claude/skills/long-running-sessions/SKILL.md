---
name: long-running-sessions
description: Harness rules for long sessions — context resets beat compaction, separate generator from evaluator, sprint contracts, decomposition into independently-commitable chunks, weighted quality criteria, and the index of evaluator commands (/review, /sync-check, /store-status, /release, /maintain, /handoff). Use when a session is getting long, before handing off, or when choosing which evaluator command to run.
---

## Long-running session rules

Based on [Anthropic harness design for long-running apps](https://www.anthropic.com/engineering/harness-design-long-running-apps).

### Context management
- **Read `.claude/handoff.md` at session start** — structured handoff artifact
- **Update `.claude/handoff.md` at session end** — what was done, decisions, next steps
- **Context resets > compaction** — when context gets long, start a fresh session with handoff
- **Don't prematurely wrap up** — if approaching context limits, hand off cleanly instead

### Separate generator from evaluator
- **Never self-evaluate** — run `/review --score` (independent evaluator) as a separate step
- Evaluators should be skeptical; generators should be creative
- If any evaluation criterion scores 1-2/5, it's BLOCKING — fix before pushing

### Sprint contracts
- Before starting a feature chunk, define **what "done" looks like**
- Use the sprint contract template in `.claude/handoff.md`
- Prevents scope creep and ensures alignment

### Decomposition
- **One feature at a time** — break complex work into discrete chunks
- Each chunk should compile, test, and be commitable independently
- Don't attempt end-to-end execution of large features in one go

### Criteria-driven quality
- Use measurable criteria (compile? tests pass? review checklist?)
- Weight criteria: Safety (3x) > Correctness (3x) > API consistency (2x) > Completeness (2x) > Minimality (1x)
- Explicit > vague — "tests pass" beats "looks good"

### Complexity hygiene
- Every harness component encodes an assumption about model limitations
- Regularly stress-test: does this hook/check still add value?
- Remove scaffolding that newer model capabilities make unnecessary

### Available evaluator commands
| Command | Role |
|---|---|
| `/review` | Independent review — `low` checklist · `high` adversarial triptych · `--score` weighted eval · `--coverage` test gaps (absorbs the former `/evaluate` + `/test`) |
| `/sync-check` | Repo + published-artifact sync (`--published-only` = the former `/publish-check`) |
| `/store-status` | Real live store / Maven / npm versions (CI-green != live) |
| `/contribute` | Full contribution workflow |
| `/version-bump` | Coordinated version update across all platforms |
| `/release` | Full release lifecycle (bump, changelog, tag, publish) |
| `/maintain` | Daily maintenance sweep (CI, issues, deps, quality) |
| `/handoff` | End-of-session continuity (reconcile STATE.md -> handoff) |

Multi-agent **saved workflows** (`.claude/workflows/`, run via the Workflow tool):
`triptych`, `fix-issue-batch`, `audit-sweep`, `release-checkpoint`, `device-qa-orchestrate`,
`doc-drift-fix`, `store-status`, `phase2-reconcile`. See `.claude/workflows/README.md`.

---

