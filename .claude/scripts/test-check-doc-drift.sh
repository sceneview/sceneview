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

# 6. REAL git-diff path (not DOC_DRIFT_FILES injection): exercise the public-decl
#    detection regex against an actual committed hunk in a scratch repo. This is
#    the fragile logic the injection tests short-circuit (review #2333 nit #6).
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
(
    cd "$SCRATCH"
    git init -q
    git config user.email t@t.t && git config user.name t
    git config core.hooksPath /dev/null   # bypass the host's global pre-commit hook
    mkdir -p sceneview/src/main/java
    printf 'class Foo {\n    fun bar() {\n        doWork()\n    }\n}\n' > sceneview/src/main/java/Foo.kt
    git add -A && git commit -qm base
    # Add a genuinely public composable in a NEW commit → must be detected.
    printf 'class Foo {\n    fun bar() {\n        doWork()\n    }\n}\n\n@Composable\nfun NewPublicThing() {}\n' \
        > sceneview/src/main/java/Foo.kt
    git commit -qam "add public api"
)
set +e
OUT="$(cd "$SCRATCH" && bash "$SCRIPT" 2>&1)"; RC=$?
set -e
{ [[ $RC -eq 0 ]] && grep -q "llms.txt\` was not updated" <<<"$OUT"; } \
    && ok "real committed public-decl hunk → detected + advisory WARN" \
    || bad "real public-decl hunk should be detected via git diff (rc=$RC)"

# 6b. A pure body-statement change (no declaration line touched) must NOT trip
#     the public-decl gate. (Local `val`/`var` decls inside a body would — a
#     documented heuristic limitation; this tests the clean statement case.)
(
    cd "$SCRATCH"
    printf 'class Foo {\n    fun bar() {\n        doOtherWork()\n    }\n}\n\n@Composable\nfun NewPublicThing() {}\n' \
        > sceneview/src/main/java/Foo.kt
    git commit -qam "body tweak only"
)
set +e
OUT="$(cd "$SCRATCH" && bash "$SCRIPT" 2>&1)"; RC=$?
set -e
{ [[ $RC -eq 0 ]] && grep -q "no public declaration was added" <<<"$OUT"; } \
    && ok "body-only change → no false-positive warning" \
    || bad "body-only change should not warn (rc=$RC)"

# 6c. UNCOMMITTED public-decl change — the local pre-commit case a developer
#     actually runs by hand. `changed_files()` unions the working tree, so the
#     FILE list sees it; the decl delta must see it too. Before the fix it was
#     computed from a COMMIT range only, so the hunk was invisible and the check
#     reported a false "no public declaration was added/removed/retyped" —
#     structurally blind exactly where CLAUDE.md points developers (#3008).
(
    cd "$SCRATCH"
    mkdir -p SceneViewSwift/Sources
    printf 'struct SceneView {\n    func internalThing() {}\n}\n' > SceneViewSwift/Sources/SceneView.swift
    git add -A && git commit -qm "swift base"
    # Uncommitted: add a genuinely public Swift declaration.
    printf 'struct SceneView {\n    func internalThing() {}\n\n    public func contentID(_ id: String) {}\n}\n' \
        > SceneViewSwift/Sources/SceneView.swift
)
set +e
OUT="$(cd "$SCRATCH" && bash "$SCRIPT" 2>&1)"; RC=$?
set -e
{ [[ $RC -eq 0 ]] && ! grep -q "no public declaration was added" <<<"$OUT"; } \
    && ok "uncommitted public-decl change → detected (not reported as 'no public declaration')" \
    || bad "uncommitted public-decl change must not report 'no public declaration' (rc=$RC)"

echo "  → $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
