#!/usr/bin/env bash
#
# test-check-mcp-tool-claims.sh — hermetic self-test for the MCP tool-claim gate.
#
# The gate exists because prose surfaces invented MCP tools and mis-stated their
# count (#3189). A gate for that class has to be pinned in BOTH directions: one
# that only ever says no blocks legitimate docs, and one that only ever says yes
# is decoration. Every case below therefore has its opposite.
#
# Fixtures are built under a scratch --root, never against the real tree: a
# suite that can only assert the PASSING direction on the live repo proves
# nothing about the failing one.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
CHECK="$ROOT/tools/check-mcp-tool-claims.js"
PASS=0; FAIL=0
ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-check-mcp-tool-claims.sh"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

# A fixture root with a minimal registry (2 stdio tools + 1 vertical tool) and
# one prose file. $1 = fixture name, $2 = README.md body.
fixture() {
    local dir="$SCRATCH/$1"; rm -rf "$dir"
    mkdir -p "$dir/mcp/src/tools" "$dir/mcp-gateway/src/mcp" \
             "$dir/mcp/packages/automotive/src" "$dir/mcp/packages/gaming/src" \
             "$dir/mcp/packages/healthcare/src" "$dir/mcp/packages/interior/src" \
             "$dir/mcp/packages/rerun/src"
    # Same shape the real sources use: `name:` opening its own line, which is
    # what the extractor anchors on.
    printf '  {\n    name: "get_sample",\n  },\n  {\n    name: "validate_code",\n  },\n' \
        > "$dir/mcp/src/tools/definitions.ts"
    printf '  {\n    name: "view_3d_model",\n  },\n' > "$dir/mcp-gateway/src/mcp/widget-tools.ts"
    for pkg in automotive gaming healthcare interior rerun; do
        printf '  {\n    name: "get_%s_thing",\n  },\n' "$pkg" > "$dir/mcp/packages/$pkg/src/tools.ts"
    done
    # Three samples, in the real shape: QUOTED key, two-space indent. The
    # `Sample` interface's own fields sit at the same indent UNQUOTED, so the
    # fixture carries one to prove they are not counted.
    printf 'interface Sample {\n  id: SampleId;\n}\nexport const SAMPLES = {\n  "model-viewer": {\n  },\n  "ar-place": {\n  },\n  "physics": {\n  },\n};\n' \
        > "$dir/mcp/src/samples.ts"
    printf '%s\n' "$2" > "$dir/README.md"
    printf '%s' "$dir"
}

run() { node "$CHECK" --root "$1" 2>&1; }

# 1. A fabricated tool name in inline backticks fails, and is NAMED.
D="$(fixture invented 'Call `create_scene` to scaffold a project.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'create_scene' <<<"$OUT"; } \
  && ok "an invented tool name → fail, token named" \
  || bad "an invented tool name must fail and be named (rc=$RC)"

# 2. …and a REAL one passes. Without this the gate could just always say no.
D="$(fixture real 'Call `get_sample` for a compilable sample.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "a real tool name → allowed" \
  || bad "a real tool name must not be flagged (rc=$RC): $OUT"

# 3. Fenced code blocks are skipped — they hold real code, where a backticked
#    token is quoting syntax, not advertising a tool.
#
#    The fixture MUST put the token in inline backticks INSIDE the fence: an
#    earlier version used a bare `val set_environment = 1`, which the name scan
#    would ignore whether or not fences were stripped. That test passed while
#    asserting nothing, and only the mutation run (fence-stripping made a no-op,
#    suite still 8/8) exposed it.
D="$(fixture fenced '```md
Call `create_scene` to scaffold.
```')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "snake_case inside a fenced block → not a tool claim" \
  || bad "fenced code must not be scanned for names (rc=$RC): $OUT"

# 4. A count matching no real surface fails.
D="$(fixture badcount 'The server provides 28 tools.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q '28 tools' <<<"$OUT"; } \
  && ok "a count matching no surface → fail, claim quoted" \
  || bad "a wrong count must fail and be quoted (rc=$RC)"

# 5. …and a count that IS a real surface's total passes — here the 2-tool stdio
#    fixture. The gate compares against the SET of real counts on purpose.
D="$(fixture goodcount 'The server provides 2 tools.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "a count equal to a real surface total → allowed" \
  || bad "a legitimate count must not be flagged (rc=$RC): $OUT"

# 6. A registry that failed to BUILD must never read as an empty registry —
#    that would turn this gate into a rubber stamp for every claim.
D="$(fixture empty 'Call `create_scene`.')"
: > "$D/mcp/src/tools/definitions.ts"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 2 ]] && grep -qi 'refusing\|0 tool names' <<<"$OUT"; } \
  && ok "an empty registry → exit 2 (refuses), not a silent pass" \
  || bad "an unbuildable registry must exit 2, never 0 (rc=$RC): $OUT"

# 7. Same for a registry file that is missing outright.
D="$(fixture missing 'Call `create_scene`.')"
rm -f "$D/mcp/src/tools/definitions.ts"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 2 ]]; } \
  && ok "a missing registry file → exit 2, not a silent pass" \
  || bad "a missing registry must exit 2 (rc=$RC): $OUT"

# 8. A token that is snake_case but NOT verb-prefixed is not a tool claim —
#    otherwise every `mcp_servers.sceneview` in a config snippet is a finding.
D="$(fixture notverb 'Set `mcp_servers` in the config.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "non-verb snake_case → not a tool claim" \
  || bad "only verb-prefixed tokens are tool-shaped (rc=$RC): $OUT"

# 9. A sample-scenario count that disagrees with samples.ts fails. "33 compilable
#    samples" stood in seven places against a real 38 — including two files
#    written during the cleanup that introduced this gate, by copying the number
#    instead of deriving it.
D="$(fixture badsamples 'Ships 33 compilable samples.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'wrong-sample-count' <<<"$OUT"; } \
  && ok "a wrong sample count → fail, classified" \
  || bad "a wrong sample count must fail (rc=$RC): $OUT"

# 10. …and the real one passes. The fixture defines three.
D="$(fixture goodsamples 'Ships 3 compilable samples.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "the real sample count → allowed" \
  || bad "the real sample count must not be flagged (rc=$RC): $OUT"

# 11. The interface's unquoted fields must not inflate the count — if they did,
#     case 10 would pass for the wrong reason and every real claim would be red.
D="$(fixture samplesshape 'Ships 4 compilable samples.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]]; } \
  && ok "unquoted interface fields are not counted as samples" \
  || bad "the sample extractor must count only quoted keys (rc=$RC): $OUT"

# 12. A samples file that failed to parse exits 2, never 0 — same contract as
#     the tool registry.
D="$(fixture nosamples 'Ships 3 compilable samples.')"
: > "$D/mcp/src/samples.ts"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 2 ]]; } \
  && ok "an unparseable samples.ts → exit 2, not a silent pass" \
  || bad "an unparseable samples.ts must exit 2 (rc=$RC): $OUT"

echo "  → $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
