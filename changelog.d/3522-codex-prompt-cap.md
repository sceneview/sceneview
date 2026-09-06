<!-- category: Fixed -->
- **An oversized Codex prompt no longer reports a quota block that never happened.** The Codex
  CLI caps one turn at **1,048,576 characters** whatever the model's window is, so `gpt-6-astra`'s
  advertised ~922K tokens of input is not reachable through `codex exec` — the real ceiling lands
  around 260–300K tokens, which is "a bit more than `gpt-5.6-sol`", not four times more. Handing
  it a 2,043,968-character session transcript was refused with `input_too_large`; and because that
  transcript itself quoted the words `session limit` and `rate_limit`, and Codex echoes the prompt
  into its log, `codex-delegate.sh`'s quota grep matched the **prompt's own text** and stopped with
  a plan limit that did not exist. Named causes are now checked before word-matching — the same
  shape of fix the function already carries for an earlier instance of the bug — so an oversized
  input exits 1 with its character count instead of 3, and `ask` refuses it up front rather than
  spending minutes uploading a prompt that will bounce. A real quota block still exits 3 and no
  workaround is ever attempted. `CODEX_DELEGATE_MAX_PROMPT_CHARS` raises the cap if a future CLI
  does.
