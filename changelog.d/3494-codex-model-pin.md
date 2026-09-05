<!-- category: Changed -->
- **The Codex delegation script now pins the model it asks for
  ([#3494](https://github.com/sceneview/sceneview/pull/3494)).**
  `.claude/scripts/codex-delegate.sh` never passed `-m`, so it inherited whatever the
  installed Codex CLI treats as its default. Codex CLI 0.153.4 makes `gpt-6-astra` that
  default: updating the CLI would have moved every delegation onto a scarcer allowance
  with no visible trace beyond the model line in each run header. The script now always
  passes the model explicitly — `gpt-5.6-sol` by default, overridable with
  `CODEX_DELEGATE_MODEL` or per call with `--model` — and routes the same choice into
  `codex review`, which accepts no `-m`, through `-c model="..."`. A new `--effort`
  reaches `model_reasoning_effort`, previously unreachable through the script.
  `CLAUDE.md` records the routing policy that goes with it.
