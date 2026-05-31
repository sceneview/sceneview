#!/usr/bin/env bash
#
# test-check-doc-drift.sh — self-test for check-doc-drift.sh.
#
# A regressed detector that silently PASSes is worse than none — it gives a
# false sense of coverage. This pins the detector's contract so `repo-hygiene`
# can trust the advisory step (same pattern as test-validate-demo-assets.sh).
#
# Uses DOC_DRIFT_FILES injection so the tests never depend on the working
# tree's real diff.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/check-doc-drift.sh"
PASS=0; FAIL=0

ok()   { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

# Run the detector with an injected file list; capture output + exit code.
run() { # files... -> sets OUT, RC
    local files="$1"; shift || true
    set +e
    OUT="$(DOC_DRIFT_FILES="$files" bash "$SCRIPT" "$@" 2>&1)"; RC=$?
    set -e
}

echo "test-check-doc-drift.sh"

# 1. A docs-only / scripts-only change → no API touched → no WARN, exit 0.
run ".claude/scripts/foo.sh README.md"
{ [[ $RC -eq 0 ]] && grep -q "No public-API source files" <<<"$OUT"; } \
    && ok "scripts/docs-only change → no warning" \
    || bad "scripts/docs-only change should not warn (rc=$RC)"

# 2. Public-API change WITHOUT llms.txt → WARN on llms.txt, still exit 0 (advisory).
run "sceneview/src/main/java/io/github/sceneview/SceneScope.kt"
{ [[ $RC -eq 0 ]] && grep -q "llms.txt\` was not updated" <<<"$OUT"; } \
    && ok "API change w/o llms.txt → advisory WARN, exit 0" \
    || bad "API change w/o llms.txt should WARN but not fail (rc=$RC)"

# 3. Public-API change WITH llms.txt → no llms WARN.
run "sceneview/src/main/java/io/github/sceneview/SceneScope.kt llms.txt"
grep -q "llms.txt was updated alongside" <<<"$OUT" \
    && ok "API change with llms.txt → llms surface OK" \
    || bad "API change with llms.txt should report llms OK"

# 4. --fail promotes the WARN to a non-zero exit (opt-in gate).
run "sceneview/src/main/java/io/github/sceneview/SceneScope.kt" --fail
[[ $RC -ne 0 ]] \
    && ok "--fail turns a WARN into exit!=0" \
    || bad "--fail should exit non-zero on a WARN (rc=$RC)"

# 5. Audit mode runs and exits 0.
set +e; OUT="$(bash "$SCRIPT" --audit 2>&1)"; RC=$?; set -e
{ [[ $RC -eq 0 ]] && grep -q "AUDIT worklist" <<<"$OUT"; } \
    && ok "audit mode emits a worklist and exits 0" \
    || bad "audit mode should emit a worklist (rc=$RC)"

echo "  → $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
