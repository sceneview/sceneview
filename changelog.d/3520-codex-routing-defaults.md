<!-- category: Changed -->
- **`codex-delegate.sh` now routes by what was measured, not by what is newest.** Two changes,
  both from a measurement run on 2026-09-06 rather than from the model's press release.
  `--model gpt-6-astra` implies `--effort high` unless an effort is given: at its own default
  effort Astra answered "no actionable regressions" on a diff where the same model at high
  effort found two real protocol bugs, so the scarce allowance was being spent on the cheap
  reasoning. And `ask` escalates to Astra by itself when the prompt is larger than
  `CODEX_DELEGATE_ASK_ESCALATE_BYTES` (800 KB, about 230K tokens): every `gpt-5.6-*` model
  stops at a 272K-token window and a prompt above it is **truncated, not refused**, so a
  whole-module read or a dead session transcript would come back looking complete and be
  missing its tail. Astra takes about 922K tokens of input, which is the one job here that
  nothing else can do. What deliberately did not change: `implement` still defaults to
  `gpt-5.6-sol`. Three parallel Astra implements at effort high spent 206K tokens in nine
  minutes and exhausted an entire ChatGPT Plus five-hour window — for **every** model, Sol
  included — so Astra on `implement` is a one-at-a-time choice for a hard issue on a fresh
  window, never a default.
