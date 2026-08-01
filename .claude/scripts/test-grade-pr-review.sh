#!/usr/bin/env bash
# Hermetic self-test for grade-pr-review.sh — the PR-review merge gate.
#
# WHY THIS EXISTS
#   The gate's whole value is that it FAILS CLOSED: a crashed reviewer, a
#   missing file, or unparsable JSON must block, because "no findings" from a
#   dead agent is indistinguishable from a clean review. That property is
#   invisible in a green suite unless something asserts it — the repo already
#   shipped a guard that was silently reintroduced-buggy because its test never
#   exercised the failure direction (#2947). The last block below is a mutation
#   test: it proves the suite would go RED if the fail-closed behaviour were
#   removed.
#
# No network, no gh, no agent — pure JSON fixtures in a scratch dir.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADE="$SCRIPT_DIR/grade-pr-review.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

# run <fixture-json-or-MISSING> -> sets $OUT (stdout) and $RC (exit code)
run() {
  local body="$1" file="$TMP/verdict.json"
  rm -f "$file"
  [ "$body" != "MISSING" ] && printf '%s' "$body" > "$file"
  OUT="$(bash "$GRADE" "$file" --comment-out "$TMP/comment.md" --pr 1 2>&1)"
  RC=$?
}

check() {
  local name="$1" want_verdict="$2" want_rc="$3"
  local got_verdict
  got_verdict="$(printf '%s\n' "$OUT" | sed -n 's/^verdict=//p' | head -1)"
  if [ "$got_verdict" = "$want_verdict" ] && [ "$RC" = "$want_rc" ]; then
    echo "  ✓ $name"
    PASS=$((PASS + 1))
  else
    echo "  ✗ $name — expected $want_verdict/rc=$want_rc, got ${got_verdict:-<none>}/rc=$RC"
    FAIL=$((FAIL + 1))
  fi
}

FULL_TABLE='"perReviewer":[{"reviewer":"code","verdict":"PASS","errorCount":0},{"reviewer":"security","verdict":"PASS","errorCount":0},{"reviewer":"impact","verdict":"PASS","errorCount":0},{"reviewer":"doc","verdict":"PASS","errorCount":0}]'

echo "grade-pr-review.sh — gate contract"

# ── Happy paths ──────────────────────────────────────────────────────────────
run "{\"reviewersExpected\":4,\"reviewersRan\":4,\"confirmedErrors\":[],\"warnings\":[],$FULL_TABLE}"
check "clean pass across 4 reviewers -> MERGE" MERGE 0

run "{\"reviewersExpected\":4,\"reviewersRan\":4,\"confirmedErrors\":[],\"warnings\":[{\"reviewer\":\"doc\",\"problem\":\"missing changelog fragment\"}],$FULL_TABLE}"
check "warnings only -> MERGE_AFTER_WARNINGS (non-blocking)" MERGE_AFTER_WARNINGS 0

# ── Blocking paths ───────────────────────────────────────────────────────────
run "{\"reviewersExpected\":4,\"reviewersRan\":4,\"confirmedErrors\":[{\"reviewer\":\"code\",\"file\":\"a.kt\",\"problem\":\"off-main Filament call\"}],\"warnings\":[],$FULL_TABLE}"
check "confirmed error -> DO_NOT_MERGE" DO_NOT_MERGE 1

run "{\"reviewersExpected\":4,\"reviewersRan\":4,\"confirmedErrors\":[{\"reviewer\":\"impact\",\"file\":\"S.kt\",\"problem\":\"removed public symbol\"}],\"warnings\":[],$FULL_TABLE}"
check "impact error -> DO_NOT_MERGE" DO_NOT_MERGE 1
if printf '%s\n' "$OUT" | grep -q '^breaking_api=true'; then
  echo "  ✓ impact error raises breaking_api=true (maintainer gate)"; PASS=$((PASS + 1))
else
  echo "  ✗ impact error did NOT raise breaking_api"; FAIL=$((FAIL + 1))
fi

# ── Fail-closed paths — the reason this file exists ──────────────────────────
run MISSING
check "missing verdict file -> REVIEW_INCOMPLETE (never a pass)" REVIEW_INCOMPLETE 1

run '{ this is not json'
check "unparsable JSON -> REVIEW_INCOMPLETE" REVIEW_INCOMPLETE 1

run '[]'
check "JSON array instead of object -> REVIEW_INCOMPLETE" REVIEW_INCOMPLETE 1

run '{"reviewersExpected":4,"reviewersRan":3,"confirmedErrors":[],"warnings":[]}'
check "dropped reviewer (3/4) -> REVIEW_INCOMPLETE" REVIEW_INCOMPLETE 1

# A crashed fan-out writes an empty-but-valid object. Without the missing-count
# fallback this reads as "4 reviewers, 0 findings" == MERGE. It must not.
run '{}'
check "empty object (crashed fan-out) -> REVIEW_INCOMPLETE" REVIEW_INCOMPLETE 1

# An agent that reports a full run but shows no reviewer table is claiming
# coverage it cannot evidence — but the count is explicit, so it is honoured.
# This documents the boundary rather than asserting a silent reinterpretation.
run '{"reviewersExpected":4,"reviewersRan":4,"confirmedErrors":[],"warnings":[]}'
check "explicit ran=4 with no table -> MERGE (count is authoritative)" MERGE 0

# ── Comment rendering ────────────────────────────────────────────────────────
if [ -f "$TMP/comment.md" ] && grep -q '<!-- sceneview-agent-review -->' "$TMP/comment.md"; then
  echo "  ✓ comment carries the dedup marker"; PASS=$((PASS + 1))
else
  echo "  ✗ comment missing the dedup marker (would spam a new comment per run)"; FAIL=$((FAIL + 1))
fi

# ── Mutation test ────────────────────────────────────────────────────────────
# A guard whose test still passes with the guard removed is not a test (#2947).
#
# The mutation target is the DROPPED-REVIEWER check, not the missing-file check.
# Measured while writing this suite: mutating `if not os.path.exists(path)` to
# `if False:` does NOT change the verdict, because the `try: json.load(...)`
# below raises FileNotFoundError and lands on the same REVIEW_INCOMPLETE. The
# missing-file branch is defence in depth, and a missing file is fail-closed
# through two independent paths (even an uncaught crash exits non-zero). The
# `ran < expected` comparison has no such redundancy — it is the single thing
# standing between a half-finished fan-out and a green MERGE, so it is the
# mutation worth proving.
MUT="$TMP/grade-mutant.sh"
sed 's/^if ran < expected:/if False:/' "$GRADE" > "$MUT"
if ! grep -q '^if False:' "$MUT"; then
  echo "  ✗ mutation could not be applied — the anchor line moved, fix this test"
  FAIL=$((FAIL + 1))
else
  printf '%s' '{"reviewersExpected":4,"reviewersRan":3,"confirmedErrors":[],"warnings":[]}' \
    > "$TMP/verdict.json"
  mut_out="$(bash "$MUT" "$TMP/verdict.json" 2>&1)"; mut_rc=$?
  if printf '%s\n' "$mut_out" | grep -q '^verdict=REVIEW_INCOMPLETE' && [ "$mut_rc" = 1 ]; then
    echo "  ✗ MUTATION SURVIVED — something other than the reviewer-count check is blocking;"
    echo "    the 3/4 case would stay green if that check regressed"
    FAIL=$((FAIL + 1))
  else
    echo "  ✓ mutation killed — without the reviewer-count check, 3/4 grades ${mut_out#*verdict=}" \
      | sed 's/$//' | head -1
    PASS=$((PASS + 1))
  fi
fi

echo
echo "grade-pr-review: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
