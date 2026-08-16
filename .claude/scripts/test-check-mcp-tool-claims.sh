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
    # A tier map in the real shape: two `readonly string[]` arrays closing with
    # `] as const;`. 2 free + 1 pro, so the legit counts gain 2, 1 and 1.
    printf 'const FREE_TOOLS: readonly string[] = [\n  "get_sample",\n  "validate_code",\n] as const;\n\nconst PRO_TOOLS: readonly string[] = [\n  "view_3d_model",\n  "get_gaming_thing",\n  "get_rerun_thing",\n] as const;\n' \
        > "$dir/mcp/src/tiers.ts"
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

# 13. A tier split that fails to PARSE must refuse, not silently narrow the
#     allowlist. This is not hypothetical: the first implementation assumed the
#     arrays closed with `];` when they close with `] as const;`, matched
#     nothing, and dropped the tier counts with no error at all.
D="$(fixture tiersbroken 'Ships 2 tools.')"
printf 'const FREE_TOOLS: readonly string[] = { "get_sample" };\n' > "$D/mcp/src/tiers.ts"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 2 ]] && grep -q 'tiers.ts' <<<"$OUT"; } \
  && ok "an unparseable tier map → exit 2, not a quietly narrowed allowlist" \
  || bad "an unparseable tiers.ts must refuse (rc=$RC): $OUT"

# 14. …and a PARSEABLE one widens the allowlist by the tier numbers. The fixture
#     has 2 free + 3 pro. "3" is reachable ONLY through the pro count — not the
#     stdio count (2), not the mounted total (8), not free-minus-gateway (1) — so
#     this case isolates that one line. With 1 pro it went through
#     free-minus-gateway instead and stayed green under a mutation that deleted
#     the pro count entirely.
D="$(fixture tiersok 'Ships 3 tools.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "a tier-split count → allowed once tiers.ts parses" \
  || bad "the tier counts must widen the allowlist (rc=$RC): $OUT"

# ── The three structural blind spots closed in #3192 ────────────────────────
# Each of these was a way for the gate to report OK on a tree it had not
# looked at. They are pinned separately because they fail for three different
# reasons: an extension, a directory, and a regex knocked out of phase.

# 15. A `.json` file inside an ALREADY-SCANNED directory is scanned.
#     `docs/docs/structured-data.json` sat inside `docs/docs` the whole time and
#     was skipped purely on extension, while advertising `list_node_types` —
#     a tool the gate's own SKIP_FILE comment described as removed. The
#     directory was never the gap; SCAN_EXT was.
D="$(fixture jsonext 'Nothing to see here.')"
mkdir -p "$D/docs/docs"
printf '{ "text": "It provides tools like get_sample() and list_node_types." }\n' \
    > "$D/docs/docs/structured-data.json"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'list_node_types' <<<"$OUT"; } \
  && ok "a .json file in a scanned dir → scanned, fabricated tool named" \
  || bad "a .json file must be scanned (rc=$RC): $OUT"

# 16. `mcp/src/` is scanned. It returns whole Markdown documents to the model —
#     `platform-setup.ts` shipped a node-type table wrong for three releases,
#     `proxy.ts` a tool count contradicting `tiers.ts` in the same package. A
#     gate that reads the docs but not the strings the SERVER ANSWERS WITH is
#     checking the smaller half. The fixture puts the claim in a `.ts` file so
#     this case dies if either `mcp/src` leaves SCAN_DIRS or `.ts` leaves
#     SCAN_EXT.
D="$(fixture mcpsrc 'Nothing to see here.')"
printf 'export const GUIDE = `Call create_scene to scaffold a project.`;\n' \
    > "$D/mcp/src/platform-setup.ts"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'create_scene' <<<"$OUT"; } \
  && ok "a claim inside mcp/src/*.ts → scanned, fabricated tool named" \
  || bad "mcp/src must be scanned (rc=$RC): $OUT"

# 17. THE REGRESSION. One JS template literal, on ONE line, containing a ```
#     fence — the exact shape of the copy-to-clipboard prompt in
#     website-static/playground.html. Under the old inline-code pairing the
#     backticks on that line paired off shifted by one and every fabricated name
#     landed in a GAP between pairs, so the scan never saw them. Two occurrences
#     shipped; one was fixed by hand and reported as the fix, the second
#     survived because the gate could not structurally see it.
#
#     This case is the reason names are matched as BARE tokens now. Restore the
#     `/`([^`\n]+)`/g` pairing and this case goes red on its own.
D="$(fixture backtickdesync 'Nothing to see here.')"
mkdir -p "$D/website-static"
#     The fixture is deliberately ONE line — the scan is per-line, so a fence
#     split across lines would not desynchronise anything and the case would
#     assert nothing. (This very case first shipped with a `\n` in the fixture
#     AND its own label written in double quotes, where the three backticks
#     opened a command substitution and killed the suite at parse time. The
#     failure mode being tested claimed its own test twice.)
printf '<script>const P = `Use the MCP server. ```kotlin val x = 1``` Then call `create_scene` and `set_environment`.`;</script>\n' \
    > "$D/website-static/playground.html"
[[ $(wc -l < "$D/website-static/playground.html") -eq 1 ]] \
  || bad "fixture must be exactly one line, or this case desynchronises nothing"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'create_scene' <<<"$OUT" && grep -q 'set_environment' <<<"$OUT"; } \
  && ok 'a ``` fence inside a one-line JS literal → both names still caught' \
  || bad "backtick desync must not hide a name (rc=$RC): $OUT"

# 18. …and the narrowing that widening required is pinned too, or someone
#     "fixes" it back and the suite proving this gate works becomes a pile of
#     violations of it. Test files deliberately construct fabricated names to
#     assert the production code rejects them.
D="$(fixture testfile 'Nothing to see here.')"
printf 'it("rejects an unknown tool", () => expect(call("get_nonexistent_guide")).toThrow());\n' \
    > "$D/mcp/src/handler.test.ts"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "a fabricated name inside a *.test.ts → deliberately not scanned" \
  || bad "test files must be skipped (rc=$RC): $OUT"

echo "  → $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
