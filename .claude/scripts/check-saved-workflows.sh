#!/usr/bin/env bash
#
# check-saved-workflows.sh — validate the reusable multi-agent SAVED workflows in
# .claude/workflows/*.js (the Workflow-tool scripts), NOT the CI YAML in
# .github/workflows/ (that is check-workflow-scripts.sh's job).
#
# A saved workflow is an ESM module the Workflow runtime parses: it begins with a
# pure-literal `export const meta = {...}` then a body using the workflow globals
# (agent/parallel/pipeline/phase/log/args/budget/workflow). It is never executed
# here — we validate it STATICALLY so a broken script can't be committed:
#
#   1. JS syntax — `node --check` on an ESM copy (undefined workflow globals are
#      runtime refs, not syntax errors; top-level await + `export` are valid ESM).
#   2. meta block — must start with `export const meta` and declare name + description.
#   3. Resume-safety — no `Date.now(`, `Math.random(`, or arg-less `new Date()`:
#      these break Workflow resume (they would re-randomise on replay).
#
# Usage: check-saved-workflows.sh [dir]   (default: .claude/workflows)
# Exit:  0 = all valid (or none present), 1 = a violation.

set -uo pipefail

DIR="${1:-.claude/workflows}"
EXIT=0

if [ ! -d "$DIR" ]; then
  echo "check-saved-workflows: no $DIR dir — nothing to validate."
  exit 0
fi

shopt -s nullglob
FILES=("$DIR"/*.js)
shopt -u nullglob
if [ "${#FILES[@]}" -eq 0 ]; then
  echo "check-saved-workflows: no *.js in $DIR yet — nothing to validate."
  exit 0
fi

HAVE_NODE=0
command -v node >/dev/null 2>&1 && HAVE_NODE=1 || echo "check-saved-workflows: WARN node not on PATH — syntax check skipped, grep checks only."

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for f in "${FILES[@]}"; do
  rel="$(basename "$f")"

  # 1. Syntax. The Workflow runtime runs the body in an async context (top-level
  #    await + return are legal there) and lifts `export const meta`. Mirror that:
  #    strip `export` and wrap the body in an async function before `node --check`,
  #    else a legitimate top-level `return {...}` trips "Illegal return statement".
  if [ "$HAVE_NODE" -eq 1 ]; then
    {
      printf 'async function __wf__(){\n'
      sed -E 's/^([[:space:]]*)export[[:space:]]+const[[:space:]]+meta/\1const meta/' "$f"
      printf '\n}\n'
    } > "$TMP/wf.cjs"
    if ! node --check "$TMP/wf.cjs" 2> "$TMP/err"; then
      echo "::error::$rel — JS syntax error:"
      sed 's/^/    /' "$TMP/err"
      EXIT=1
      continue
    fi
  fi

  # 2. meta block.
  if ! grep -Eq '^[[:space:]]*export[[:space:]]+const[[:space:]]+meta[[:space:]]*=' "$f"; then
    echo "::error::$rel — must start with 'export const meta = {...}'."
    EXIT=1
  fi
  if ! grep -Eq 'name[[:space:]]*:' "$f"; then
    echo "::error::$rel — meta is missing a 'name:' field."
    EXIT=1
  fi
  if ! grep -Eq 'description[[:space:]]*:' "$f"; then
    echo "::error::$rel — meta is missing a 'description:' field."
    EXIT=1
  fi

  # 3. Resume-safety: forbidden non-deterministic calls.
  if grep -nE 'Date\.now\(|Math\.random\(|new[[:space:]]+Date\([[:space:]]*\)' "$f" > "$TMP/bad" 2>/dev/null; then
    echo "::error::$rel — resume-breaking call(s) (use args/stamp-after-return instead):"
    sed 's/^/    /' "$TMP/bad"
    EXIT=1
  fi
done

if [ "$EXIT" -ne 0 ]; then
  echo ""
  echo "::error::Saved-workflow validation failed — see above. Conventions: .claude/workflows/README.md §9."
  exit 1
fi

echo "check-saved-workflows: OK — ${#FILES[@]} workflow(s) valid."
