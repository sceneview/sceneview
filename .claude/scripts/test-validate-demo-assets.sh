#!/usr/bin/env bash
# test-validate-demo-assets.sh — self-test for validate-demo-assets.sh
#
# Runs three scenarios:
#   1. A tiny fixture repo where every ref resolves       → expect exit 0
#   2. The same fixture with a broken bundled ref         → expect exit 1
#   3. The real repo in --no-cdn mode                     → expect exit 0
#
# Failure of any scenario means validate-demo-assets.sh is broken
# (either newly buggy or hiding a real regression in samples/).
#
# Usage: bash .claude/scripts/test-validate-demo-assets.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VALIDATOR="$REPO_ROOT/.claude/scripts/validate-demo-assets.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "  ${GREEN}PASS${NC}  $1"; }
fail() { echo -e "  ${RED}FAIL${NC}  $1"; FAILED=$((FAILED + 1)); }

FAILED=0

echo "== Scenario 1: fixture with all refs resolving =="
# Build a standalone mini-repo under /tmp that mimics samples/android-demo
FIXTURE=$(mktemp -d -t validate-demo-fixture.XXXXXX)
trap 'rm -rf "$FIXTURE"' EXIT

mkdir -p "$FIXTURE/.claude/scripts"
cp "$VALIDATOR" "$FIXTURE/.claude/scripts/"

mkdir -p "$FIXTURE/samples/android-demo/src/main/java/demo"
mkdir -p "$FIXTURE/samples/android-demo/src/main/assets/models"
mkdir -p "$FIXTURE/samples/android-demo/src/main/assets/environments"

# Create two bundled files
printf "fake glb" > "$FIXTURE/samples/android-demo/src/main/assets/models/cube.glb"
printf "fake hdr" > "$FIXTURE/samples/android-demo/src/main/assets/environments/studio.hdr"

# And a Kotlin source file that references them
cat > "$FIXTURE/samples/android-demo/src/main/java/demo/Scene.kt" <<'EOF'
package demo

const val CUBE = "models/cube.glb"
const val ENV = "environments/studio.hdr"
EOF

# Run the validator in --android --no-cdn mode on the fixture
if (cd "$FIXTURE" && bash .claude/scripts/validate-demo-assets.sh --android --no-cdn > /tmp/validate-test-1.log 2>&1); then
    pass "fixture with valid refs exits 0"
else
    fail "fixture with valid refs should exit 0 — log:"
    cat /tmp/validate-test-1.log | sed 's/^/        /'
fi

echo
echo "== Scenario 2: fixture with a broken bundled ref =="
# Delete one of the bundled files, leaving the reference in the source
rm "$FIXTURE/samples/android-demo/src/main/assets/models/cube.glb"

set +e
(cd "$FIXTURE" && bash .claude/scripts/validate-demo-assets.sh --android --no-cdn > /tmp/validate-test-2.log 2>&1)
rc=$?
set -e

if [ $rc -ne 0 ]; then
    # Further check: output must mention MISS cube.glb. Strip ANSI color
    # codes first — the script prints with color even to pipes. ESC comes from
    # printf rather than the `\x1b` escape, which is a GNU extension outside
    # POSIX: macOS's BSD sed accepts it today, but on a sed that does not, the
    # grep below would miss a message that IS there and the test would fail
    # for a reason nothing in its output shows.
    if sed "s/$(printf '\033')\[[0-9;]*m//g" /tmp/validate-test-2.log | grep -q "MISS cube.glb"; then
        pass "fixture with broken ref exits $rc AND reports MISS cube.glb"
    else
        fail "fixture with broken ref exits $rc but did not mention cube.glb:"
        cat /tmp/validate-test-2.log | sed 's/^/        /'
    fi
else
    fail "fixture with broken ref should have failed but exited 0"
    cat /tmp/validate-test-2.log | sed 's/^/        /'
fi

echo
echo "== Scenario 3: real repo, --no-cdn to stay fast =="
if bash "$VALIDATOR" --no-cdn > /tmp/validate-test-3.log 2>&1; then
    # Count how many refs were checked so the user gets some signal
    summary=$(grep -oE '[0-9]+ bundled, [0-9]+ CDN' /tmp/validate-test-3.log | tail -1)
    pass "real repo resolves all refs ($summary)"
else
    fail "real repo has broken refs — investigate immediately:"
    tail -20 /tmp/validate-test-3.log | sed 's/^/        /'
fi

echo
if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed.${NC}"
    exit 0
else
    echo -e "${RED}$FAILED test(s) failed.${NC}"
    exit 1
fi
