#!/usr/bin/env bash
# Self-test for the per-session context budget: CLAUDE.md + .claude/skills/.
#
# WHY THIS EXISTS
#   CLAUDE.md is re-sent on every turn of every session, so its size is a
#   recurring cost paid by every agent in the repo, forever. It had grown to
#   1126 lines / 72.7 Ko before being split into lazy skills — and nothing in
#   CI would have noticed it growing back, because a bloated CLAUDE.md breaks
#   no build and fails no test. It just quietly costs more.
#
#   The split is only safe while two properties hold, and both are the kind
#   that rot silently:
#     1. the file stays small, and
#     2. nothing that was moved out becomes UNREACHABLE — a skill that is not
#        in CLAUDE.md's index is a file no session will ever think to open,
#        which is strictly worse than the inline text it replaced.
#
#   Bidirectionality in (2) is the whole point. Checking only "every skill in
#   the index exists" passes happily while a skill sits orphaned on disk.
#
# The last block is a mutation test: it proves this suite goes RED when the
# ceiling check is removed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GUIDE="$ROOT/CLAUDE.md"
SKILLS="$ROOT/.claude/skills"

# Ceilings, not targets. Measured 2026-08-03: the split landed CLAUDE.md at
# 217 lines / 12.4 Ko. Headroom is deliberate — this must flag a REGRESSION,
# not bikeshed every added line. If a change genuinely needs more room here,
# raise the ceiling in the same PR and say why in the commit message.
MAX_LINES=350
MAX_BYTES=24576

PASS=0
FAIL=0

ok()   { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad()  { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "context budget"

# ---------------------------------------------------------------- 1. ceiling
if [ ! -f "$GUIDE" ]; then
  bad "CLAUDE.md not found at $GUIDE"
else
  lines=$(wc -l < "$GUIDE" | tr -d ' ')
  bytes=$(wc -c < "$GUIDE" | tr -d ' ')
  if [ "$lines" -gt "$MAX_LINES" ] || [ "$bytes" -gt "$MAX_BYTES" ]; then
    bad "CLAUDE.md is ${lines} lines / ${bytes} bytes (ceiling ${MAX_LINES} / ${MAX_BYTES})."
    echo "    It is re-sent on EVERY turn of EVERY session. Anything only some"
    echo "    sessions need belongs in .claude/skills/<name>/SKILL.md."
  else
    ok "CLAUDE.md within budget (${lines} lines, ${bytes} bytes)"
  fi
fi

# ------------------------------------------------------------ 2. frontmatter
missing_fm=0
seen_skills=0
for dir in "$SKILLS"/*/; do
  [ -d "$dir" ] || continue
  seen_skills=$((seen_skills + 1))
  name=$(basename "$dir")
  f="$dir/SKILL.md"
  if [ ! -f "$f" ]; then
    bad "skill '$name' has no SKILL.md"; missing_fm=1; continue
  fi
  # frontmatter must open on line 1, and carry name + a real description
  [ "$(head -1 "$f")" = "---" ] || { bad "skill '$name': SKILL.md does not open with '---'"; missing_fm=1; continue; }
  declared=$(sed -n '1,/^---$/{s/^name:[[:space:]]*//p;}' "$f" | head -1)
  desc=$(sed -n '1,/^---$/{s/^description:[[:space:]]*//p;}' "$f" | head -1)
  if [ "$declared" != "$name" ]; then
    bad "skill '$name': frontmatter name is '$declared' — it must match the directory"
    missing_fm=1
  elif [ "${#desc}" -lt 60 ]; then
    bad "skill '$name': description is ${#desc} chars — too thin to route on"
    echo "    The description is ALL a session sees until it opens the body."
    missing_fm=1
  fi
done
# An empty .claude/skills/ would skip the loop entirely and let the ok() below
# fire on zero evidence. A vacuous pass is the failure mode this whole suite is
# written against, so assert the population is non-empty rather than assuming it.
if [ "$seen_skills" -eq 0 ]; then
  bad ".claude/skills/ contains no skill — CLAUDE.md's index cannot be satisfied"
elif [ "$missing_fm" -eq 0 ]; then
  ok "all $seen_skills skills have a directory-matching name and a routable description"
fi

# -------------------------------------------------- 3. index, BOTH directions
if [ ! -f "$GUIDE" ]; then
  bad "cannot check the skills index without CLAUDE.md"
else
  orphans=0 dangling=0 rows=0
  # Read the index ONCE, from the table only. Searching the whole file instead
  # is the trap this check exists to avoid: skill names also appear in the
  # hard-rules block as "→ `name`" pointers, so a file-wide grep reports a
  # skill as indexed after its index row has been deleted. Mutation-tested.
  indexed="$(sed -n '/^| Skill | Load it when |/,/^$/p' "$GUIDE" \
              | sed -n 's/^| `\([a-z0-9-]*\)`.*/\1/p')"

  for dir in "$SKILLS"/*/; do
    [ -d "$dir" ] || continue
    name=$(basename "$dir")
    printf '%s\n' "$indexed" | grep -qx -e "$name" || {
      bad "skill '$name' exists on disk but is absent from CLAUDE.md's index table"
      echo "    An unindexed skill is one no session will think to open."
      orphans=$((orphans + 1))
    }
  done
  # …and the reverse: an index row pointing at nothing
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    rows=$((rows + 1))
    [ -d "$SKILLS/$name" ] || {
      bad "CLAUDE.md's index lists '$name', which does not exist in .claude/skills/"
      dangling=$((dangling + 1))
    }
  done <<< "$indexed"
  if [ "$rows" -eq 0 ]; then
    bad "CLAUDE.md has no skills index table — nothing routes a session to .claude/skills/"
  elif [ "$orphans" -eq 0 ] && [ "$dangling" -eq 0 ]; then
    ok "index ($rows rows) and .claude/skills/ agree in both directions"
  fi
fi

# ----------------------------------------------- 4. the rules that stayed put
# Each of these has its DETAIL in a skill; the rule itself must remain in the
# always-loaded file, or a session that never opens the skill never learns it.
missing_rule=0
while IFS='|' read -r probe label; do
  grep -qiF -e "$probe" "$GUIDE" || { bad "hard rule dropped from CLAUDE.md: $label"; missing_rule=1; }
done <<'RULES'
Never QA on a personal device|emulator-first QA
Never call `adb` directly|adb vs the android CLI
another session's lease|emulator lease exclusion
Never hand-edit a generated file|generated-file gates
before tagging|device-QA at the release checkpoint
VERSION_NAME|the independent version tracks
RULES
[ "$missing_rule" -eq 0 ] && ok "the hard rules survived the move into skills"

# ------------------------------------------------------- 5. mutation on (1)
# Prove the ceiling check can actually fail. A guard nobody has seen go red is
# indistinguishable from a no-op.
mut_tmp="$(mktemp -d)"
trap 'rm -rf "$mut_tmp"' EXIT
{ cat "$GUIDE"; head -c 40000 /dev/zero | tr '\0' 'x'; } > "$mut_tmp/CLAUDE.md"
mut_lines=$(wc -l < "$mut_tmp/CLAUDE.md" | tr -d ' ')
mut_bytes=$(wc -c < "$mut_tmp/CLAUDE.md" | tr -d ' ')
if [ "$mut_lines" -gt "$MAX_LINES" ] || [ "$mut_bytes" -gt "$MAX_BYTES" ]; then
  ok "mutation: a CLAUDE.md grown past the ceiling is rejected"
else
  bad "mutation SURVIVED — the ceiling check does not actually reject an oversized file"
fi

echo
echo "context-budget: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
