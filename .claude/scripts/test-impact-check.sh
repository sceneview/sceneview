#!/usr/bin/env bash
#
# test-impact-check.sh — self-test for impact-check.sh lean/sparse-clone safety.
#
# #2370: under a sparse/lean clone that omits arsceneview/, the node-count check
# FALSE-FAILed every "N+ node types" doc claim ("Claims 42, actual 24") because
# the total is the SUM of the 3D + AR node sources and one half was missing.
# Lean-clone (`git clone --depth 1` + sparse-checkout) is the standard batch-agent
# workflow, so a recurring FALSE-FAIL is real noise — and a hard one under `--fail`
# in the quality gate.
#
# This pins the contract a regressed detector must never break:
#   - a PARTIAL node-source checkout SKIPs (never FALSE-FAILs), even under --fail;
#   - a COMPLETE checkout still ACTIVELY flags a real count mismatch (the fix
#     must not blanket-skip — a regressed detector that silently PASSes is worse
#     than none, same rationale as test-check-doc-drift.sh);
#   - a single-commit / shallow history (no HEAD~1) never crashes under `set -e`.
#
# Each case runs the real script against a scratch checkout we fully control, so
# the test never depends on the host working tree's real node count or history.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/impact-check.sh"
PASS=0; FAIL=0

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-impact-check.sh"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

# Create one *Node.kt stub at the given path (dirs made on demand).
mk_node() { mkdir -p "$(dirname "$1")"; printf 'class %s\n' "$(basename "$1" .kt)" > "$1"; }

# Fresh git repo under $SCRATCH/<name>; echoes its absolute path. A bare
# single-commit repo (or no commit) mimics the `--depth 1` clone's missing
# history — there is no HEAD~1 to diff against.
setup_repo() {
    local dir="$SCRATCH/$1"; rm -rf "$dir"; mkdir -p "$dir"
    ( cd "$dir"
      git init -q
      git config user.email t@t.t && git config user.name t
      git config core.hooksPath /dev/null )   # bypass any host global hook
    printf '%s' "$dir"
}

N3="sceneview/src/main/java/io/github/sceneview/node"
NAR="arsceneview/src/main/java/io/github/sceneview/ar/node"

# 1. PARTIAL checkout: sceneview/ node dir present, arsceneview/ ABSENT, plus a
#    doc claim. The total would be partial → must SKIP, never FALSE-FAIL, and
#    exit 0 even under --fail (the quality-gate path).
D="$(setup_repo partial)"
for n in ModelNode LightNode CameraNode SphereNode CubeNode; do mk_node "$D/$N3/$n.kt"; done
printf 'SceneView — 42+ node types and counting.\n' > "$D/README.md"
set +e
OUT="$(cd "$D" && bash "$SCRIPT" --fail 2>&1)"; RC=$?
set -e
{ grep -q '\[SKIP\].*Node count consistency' <<<"$OUT" \
  && ! grep -qi '\[FAIL\].*node count' <<<"$OUT" \
  && [[ $RC -eq 0 ]]; } \
  && ok "partial node-source checkout → SKIP (not FALSE-FAIL), --fail exit 0" \
  || bad "partial checkout must SKIP node count and exit 0 under --fail (rc=$RC)"

# 2. COMPLETE checkout (both node dirs) with a WRONG doc claim → must still FAIL.
#    Proves the fix preserves real-impact detection instead of blanket-skipping.
#    3 (3D) + 2 (AR) = 5 actual nodes; the doc claims 99.
D="$(setup_repo complete)"
for n in ModelNode LightNode CameraNode; do mk_node "$D/$N3/$n.kt"; done
for n in AugmentedImageNode AnchorNode;  do mk_node "$D/$NAR/$n.kt"; done
printf 'SceneView — 99+ node types and counting.\n' > "$D/README.md"
set +e
OUT="$(cd "$D" && bash "$SCRIPT" 2>&1)"; RC=$?
set -e
{ grep -q '\[FAIL\].*README.md node count' <<<"$OUT" \
  && grep -q 'actual 5' <<<"$OUT"; } \
  && ok "complete checkout, wrong claim → still FAILs (real impact flagged)" \
  || bad "complete checkout must FAIL on a real node-count mismatch"

# 3. COMPLETE checkout with a CORRECT claim → PASS, exit 0 (no false alarm).
printf 'SceneView — 5+ node types and counting.\n' > "$D/README.md"
set +e
OUT="$(cd "$D" && bash "$SCRIPT" 2>&1)"; RC=$?
set -e
{ grep -q '\[PASS\].*README.md node count' <<<"$OUT" && [[ $RC -eq 0 ]]; } \
  && ok "complete checkout, matching claim → PASS" \
  || bad "complete checkout with matching claim should PASS (rc=$RC)"

# 4. Single-commit repo (no HEAD~1, the shallow-history shape) → the script must
#    run to completion without dying under `set -e`/the ERR trap.
D="$(setup_repo shallow)"
mk_node "$D/$N3/ModelNode.kt"
mk_node "$D/$NAR/AnchorNode.kt"
( cd "$D" && git add -A && git commit -qm base )
set +e
OUT="$(cd "$D" && bash "$SCRIPT" 2>&1)"; RC=$?
set -e
{ [[ $RC -eq 0 ]] && ! grep -q 'impact-check died' <<<"$OUT"; } \
  && ok "single-commit history (no HEAD~1) → runs without crashing under set -e" \
  || bad "missing HEAD~1 must not crash the script (rc=$RC)"

echo "  → $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
