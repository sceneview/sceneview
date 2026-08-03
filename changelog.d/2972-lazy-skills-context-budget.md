<!-- category: Changed -->
- `CLAUDE.md` is now 217 lines instead of 1126: the nine sections only *some*
  sessions need moved into lazy `.claude/skills/` entries, which load on demand.
  The file is re-sent on every turn of every session, so its size was a cost
  every agent in the repo paid forever — 72.7 Ko of it, growing monotonically
  because nothing ever reported it. Nothing was rewritten and nothing was lost:
  the move was mechanical and verified line-by-line. The rules whose *cost of
  being forgotten* is high (never QA on a personal device, never call `adb`
  directly, never drive a leased emulator, never hand-edit a generated file)
  stay in the always-loaded file; only their detail moved.

<!-- category: Added -->
- `.claude/scripts/context-budget.sh` reports the standing context a session
  pays before doing any work, per file, against each file's documented spec.
  It complements `agent-cost-report.sh` — that one measures what was spent,
  this one measures what will be. Bytes are measured; the token column is an
  explicit estimate.
- `test-context-budget.sh` gates the committed half in `repo-hygiene`: a
  `CLAUDE.md` ceiling, skill frontmatter, and — in **both** directions — that
  the skills index and `.claude/skills/` agree. A skill missing from the index
  is a file no session will think to open, which is strictly worse than the
  inline text it replaced. Mutation-tested, including on the trap that caught
  the first version of the check: a file-wide `grep` for the skill name still
  passes after its index row is deleted, because the name also appears in the
  hard-rules pointers.
- The `automation-map` skill no longer carries a 7-row subset of the version
  location map: a partial copy of a completeness-critical list is worse than no
  copy, and one of its rows had already gone stale. It points at `versioning`,
  which holds the canonical 30+ location table.
