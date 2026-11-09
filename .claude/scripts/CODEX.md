# Codex delegation

Moved verbatim from `CLAUDE.md` on 2026-11-04 (CLAUDE.md is re-read on every model call).

- **Codex is a delegation lever, not a rule** — it bills a quota separate from Claude's.
  Always `.claude/scripts/codex-delegate.sh` (`ask` · `review` · `implement
  --new-worktree` · `qa`, which drives a device unsandboxed from a throwaway
  clone), never a raw `codex`, never an OpenAI API key. Commits, merges and
  releases stay with the lead session. Route to Codex what it does as well for less: an
  independent review of a merged PR, a whole-module read to draft an inventory, unit
  tests and mechanical refactors from a closed brief. Keep with Claude: product
  decisions, UI validated by capture, anything touching secrets, billing or irreversible
  steps — and the reading of whatever Codex returns. The script pins the model
  (`gpt-5.6-sol`) so a CLI update cannot change it silently; `--model gpt-6-astra` is
  opt-in, per call, and now implies `--effort high` — Astra at its own default effort
  reports nothing. `ask` escalates to Astra on its own when the prompt outgrows Sol's
  272K window, because Sol truncates such a prompt instead of refusing it. Astra is
  **not** the default for `implement`: three parallel Astra implements at effort high
  exhausted a whole ChatGPT Plus five-hour window — for every model, Sol included — in
  nine minutes. And the CLI caps **one turn at 1,048,576 characters** whatever the model
  window is, so Astra's advertised ~922K-token input is not reachable from `codex exec`:
  a corpus bigger than that has to be split, and the script refuses it up front instead
  of letting the upload bounce.
