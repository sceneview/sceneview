---
description: Log a caught false claim / recurring miss to the claim-ledger; on the 3rd occurrence of a class, promote it to a feedback_*.md memory rule. Part of the Evidence-Stamped Claim Gate (#2346).
---

# /caught — record a caught miss, promote a 3×-recurring class to a rule

`/caught <class> <context>` — maintainer-run, when Claude told you something was done /
working / live and it was **not**, or any recurring miss the deterministic gate did not
catch. It feeds the slow loop of the Evidence-Stamped Claim Gate (#2346): the
`claim-gate.sh` push-hook is the FAST, deterministic block; this ledger is the SLOW,
human-in-the-loop signal that turns a *repeated* class of miss into a durable
`feedback_*.md` rule so the harness self-improves.

`<class>` is a short stable kebab-case key — reuse the SAME key for the same kind of
miss so occurrences accumulate (e.g. `false-live-claim`, `keyless-qa-green`,
`stale-evidence`, `ci-green-not-live`, `overclaim-tests-pass`). `<context>` is a free
sentence: what was claimed, what was actually true, where.

## Steps

1. **Append to the ledger** (gitignored runtime data, like STATE.md / the store probe):
   ```bash
   ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
   mkdir -p "$ROOT/.claude/data"
   LEDGER="$ROOT/.claude/data/claim-ledger.tsv"
   [ -f "$LEDGER" ] || printf 'date\tclass\tcontext\n' > "$LEDGER"
   printf '%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "<class>" "<context>" >> "$LEDGER"
   ```

2. **Recount the class** and report the running total:
   ```bash
   COUNT=$(awk -F'\t' -v c="<class>" 'NR>1 && $2==c {n++} END{print n+0}' "$LEDGER")
   echo "class '<class>' now has $COUNT occurrence(s)"
   ```

3. **At the 3rd occurrence (`COUNT >= 3`) — promote / update the matching memory rule.**
   This is the whole point: a one-off is noise, a 3×-recurring miss is a pattern that
   belongs in durable cross-session memory.
   - Find or create the memory file `~/.claude/projects/-Users-thomasgorisse-Projects-sceneview/memory/feedback_<class-underscored>.md`
     (the memory dir is user-level, outside the repo — see `MEMORY.md` there for the format).
   - Use the established feedback-file shape: YAML frontmatter (`name`, a punchy
     `description`, `metadata.{node_type: memory, type: feedback}`) then a body with:
     **what keeps getting claimed falsely**, **why it slips**, and a numbered
     **How to apply** (the proactive check that would have caught it). Cross-link
     related rules with `[[wikilinks]]` — especially `[[feedback_verify_live_store_state]]`
     and `[[feedback_real_product_not_school_project]]` for live/QA over-claims.
   - Add (or update) its one-line pointer in that same directory's `MEMORY.md` index
     under the right section so it is loaded every session.
   - If the file already exists (the class was promoted before), **sharpen** it with the
     new occurrence instead of duplicating — escalate the severity marker (⛔ → ⛔⛔) and
     fold in the new context.

4. **Close the loop with the fast gate.** If the recurring class is something
   `claim-gate.sh` *could* assert deterministically (a new evidence file, a new
   success-claim phrasing it misses), file a follow-up issue to extend the gate's
   patterns or evidence set — the ledger promotes to a rule, the rule promotes to a
   deterministic block. That is the self-improvement ratchet.

## Notes
- The ledger (`.claude/data/claim-ledger.tsv`) is **gitignored** — local, same-host,
  never committed (same policy as `STATE.md` and `last-store-probe.json`).
- `/handoff` Step 4 also checks the ledger: any class already at 3× that has not been
  promoted is promoted there. `/caught` is the eager path; `/handoff` is the backstop.
- This command never blocks anything — it only records and, at the threshold, writes a
  memory rule. The blocking lives in `claim-gate.sh` on the `git push` hook.
