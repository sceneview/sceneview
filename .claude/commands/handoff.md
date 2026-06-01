---
description: End-of-session continuity — reconcile STATE.md, move done items into handoff history, refresh NEXT/IN-FLIGHT, rotate handoff at 400 lines, never lose a raised point.
---

# /handoff — clean session continuity

The ritual that makes sessions fluid and loses nothing. Run at session end, before a
context reset, or whenever `.claude/STATE.md` has drifted from reality.

## Steps

1. **Reconcile `.claude/STATE.md`:**
   - **Move** every DONE `## NOW` bullet into `.claude/handoff.md` (it MOVES, not copies).
   - Refresh `## NOW` to the true state (released version · what just shipped · what's broken).
   - Update `## IN-FLIGHT`: drop merged/released claims (`bash .claude/scripts/claim.sh --release <n>` clears the GitHub label + the row); keep only genuinely in-progress work.
   - Re-order `## NEXT` (≤6 bullets), each a GitHub issue link. Spin anything out-of-scope into an issue so NEXT never grows unbounded.

2. **Append to `.claude/handoff.md`** (gitignored history — the "why did we do X"): a dated block of what shipped, decisions, footguns, pickup notes.

3. **Rotate** if `handoff.md` > 400 lines → move its oldest section to `.claude/handoff-archive/YYYY-QN.md`.

4. **Memory:** if a durable cross-session LESSON emerged (a rule, a footgun, a preference — NOT a session snapshot), write/update a memory file + its `MEMORY.md` pointer. Snapshots never go in memory.
   - **Claim-gate check (#2346):** run `bash .claude/scripts/claim-gate.sh` against the drafted `## NOW`. A non-zero exit means a success-claim ("QA complete ✅", "all live ✅", "verified live") was persisted **without fresh agreeing evidence** — either re-run the verifier so the evidence file exists (`device-qa.sh` / `/store-status`) or restate the claim honestly (e.g. "iOS LIVE=4.0.3, 4.17.0 in review"). Never hand off a STATE.md the gate would block.
   - **Ledger backstop:** if `.claude/data/claim-ledger.tsv` shows any class at ≥3 occurrences that has not yet been promoted, promote it to a `feedback_*.md` rule now (the `/caught` threshold action) — `/handoff` is the backstop for what `/caught` did not promote eagerly.

5. **Never lose a raised point:** any bug / idea / improvement Thomas mentioned that isn't done becomes a GitHub issue NOW — not a "remember to".

A saturated session **never stops mid-air**: commit + push + a `## NOW` "START HERE"
line + a self-contained continuation **relay issue**, then close. CLAUDE.md carries only
a 2-line pointer to `STATE.md` — never session state.
