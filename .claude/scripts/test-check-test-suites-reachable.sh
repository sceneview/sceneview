#!/usr/bin/env bash
# Self-test for check-test-suites-reachable.sh.
#
# Hermetic: every case builds a throwaway git repo under $TMPDIR with synthetic
# packages and synthetic workflows. Nothing here reads the real tree, so a
# green run means "the gate works", never "today's repo happens to be right" —
# which matters more than usual for this gate, since the tree it polices is
# clean by construction the moment it lands.
#
# Every case below is MUTATION-VERIFIED at the bottom: the gate is copied,
# one guarantee is broken by an exact-literal substitution, and the case that
# owns it must go red. A substitution that does not apply is reported as an
# INVALID MUTANT rather than counted as a pass — a mutant that never bit looks
# exactly like a mutant that was caught.
set -uo pipefail

GATE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-test-suites-reachable.sh"
[ -f "$GATE" ] || { echo "test-check-test-suites-reachable: gate not found at $GATE" >&2; exit 2; }

GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; NC=$'\033[0m'
if [ ! -t 1 ]; then GREEN=""; RED=""; NC=""; fi
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  ${GREEN}✓${NC} $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  ${RED}✗${NC} $1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ── fixture builder ──────────────────────────────────────────────────────────
# mkrepo <name> — creates $TMP/<name> as a git repo. Files are staged, not
# committed: `git ls-files` sees the index, so no user.name/user.email is
# needed and the fixtures stay valid on a machine with no git identity.
mkrepo() {
    local name="$1" d="$TMP/$1"
    mkdir -p "$d/.github/workflows"
    git -C "$d" init -q 2>/dev/null || git -c init.defaultBranch=main -C "$d" init -q
    echo "$d"
}
addpkg() {  # addpkg <repo> <dir> [testsubdir]
    local d="$1" pkg="$2" sub="${3:-src}"
    mkdir -p "$d/$pkg/$sub"
    echo '{"name":"x","scripts":{"test":"vitest run"}}' > "$d/$pkg/package.json"
    echo 'test("x", () => {})' > "$d/$pkg/$sub/a.test.ts"
}
stage() { git -C "$1" add -A 2>/dev/null; }

run_gate() { OUT="$("$BASH" "$GATE" "$1" 2>&1)"; RC=$?; }

echo "test-check-test-suites-reachable"
echo ""

# ── 1. the happy path ────────────────────────────────────────────────────────
R="$(mkrepo ok)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - name: Test
        working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '✓ app'; then
    ok "a package invoked by a PR-triggered, path-matching workflow is OK"
else
    bad "the happy path is not reported OK (rc=$RC)"
fi

# ── 2. enumeration comes from the filesystem ─────────────────────────────────
# The gate has never heard of this package and there is no list to add it to.
# This is the case a whitelist design would pass green, and it is the whole
# reason the starting set is scraped from disk.
R="$(mkrepo none)"; addpkg "$R" brand-new-package
stage "$R"; run_gate "$R"
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -q '✗ brand-new-package'; then
    ok "a package no workflow mentions fails, with no list to be absent from"
else
    bad "an uncovered package did not fail the gate (rc=$RC)"
fi

# ── 3. invoked but never triggered ───────────────────────────────────────────
R="$(mkrepo nopath)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'unrelated/**'
jobs:
  t:
    steps:
      - name: Test
        working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -q 'never triggers'; then
    ok "a workflow that runs the tests but never fires on them is not coverage"
else
    bad "a non-triggering workflow was accepted as coverage (rc=$RC)"
fi

# ── 4. runs, but cannot report red ───────────────────────────────────────────
R="$(mkrepo advtrue)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - name: Test
        working-directory: app
        run: npx vitest run || true
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '⚠ app.*cannot fail the build'; then
    ok "a '|| true' invocation is advisory, not blocking coverage"
else
    bad "'|| true' was not distinguished from a blocking run (rc=$RC)"
fi

# ── 5. runs, but only after the merge ────────────────────────────────────────
# This is the shape that made `release.yml` look like coverage for `mcp`: real
# `npm test`, no path filter to fail, and a trigger that only fires on a tag.
R="$(mkrepo nopr)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  push:
    tags:
      - 'v*'
jobs:
  t:
    steps:
      - name: Test
        working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q 'does not run on pull requests'; then
    ok "a publish-time-only workflow is advisory, not a merge gate"
else
    bad "a tag-only workflow was counted as blocking coverage (rc=$RC)"
fi

# ── 6. a comment is not an invocation ────────────────────────────────────────
# The repo shipped exactly this: a workflow header asserting that `npm test`
# was run by another job that ran no npm at all. If a comment counted, the
# false claim would satisfy the gate written to catch it.
R="$(mkrepo comment)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - name: Lint
        working-directory: app
        # npm test is run by the quality gate elsewhere
        run: npm run lint
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 1 ]; then
    ok "a comment claiming coverage does not create coverage"
else
    bad "a commented-out test command was counted as an invocation (rc=$RC)"
fi

# ── 7. a step title is not an invocation ─────────────────────────────────────
R="$(mkrepo stepname)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      # `name:` deliberately AFTER `working-directory:` — valid YAML, and the
      # only ordering under which this case can bite. With the title first the
      # step's working-directory is not yet known, so a title counted as an
      # invocation would have no target and be dropped anyway; the guard would
      # look pinned while nothing tested it.
      - working-directory: app
        name: Run vitest
        run: echo skipped
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 1 ]; then
    ok "a step named after a test runner does not count as running it"
else
    bad "a step title was counted as an invocation (rc=$RC)"
fi

# ── 8. working-directory must not leak between steps ─────────────────────────
R="$(mkrepo leak)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - name: Build
        working-directory: app
        run: echo build
      - name: Test
        run: npm test
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 1 ]; then
    ok "a later step does not inherit the previous step's working-directory"
else
    bad "working-directory leaked forward, inventing coverage (rc=$RC)"
fi

# ── 9. a directory named in the command, relative to working-directory ───────
R="$(mkrepo rootflag)"; addpkg "$R" app; addpkg "$R" app/packages/lib
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - name: Test app
        working-directory: app
        run: npm test
      - name: Test lib
        working-directory: app
        run: npx vitest run --root packages/lib
YML
stage "$R"; run_gate "$R"
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '✓ app/packages/lib'; then
    ok "a package named by --root, relative to working-directory, is reached"
else
    bad "a --root target was not resolved against working-directory (rc=$RC)"
fi

# ── 10. compiled copies are not separate suites ──────────────────────────────
R="$(mkrepo distonly)"; mkdir -p "$R/app/dist"
echo '{"name":"x"}' > "$R/app/package.json"
echo 'test("x", () => {})' > "$R/app/dist/a.test.js"
stage "$R"; run_gate "$R"
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '0 suite'; then
    ok "a versioned dist/ copy is not counted as a suite of its own"
else
    bad "dist/ output was counted as a suite (rc=$RC)"
fi

# ── 11. untracked tests are not the repo's CI debt ───────────────────────────
R="$(mkrepo untracked)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - name: Test
        working-directory: app
        run: npm test
YML
stage "$R"
mkdir -p "$R/scratch"
echo '{"name":"s"}' > "$R/scratch/package.json"
echo 'test("x", () => {})' > "$R/scratch/a.test.ts"
run_gate "$R"
if [ "$RC" -eq 0 ] && ! printf '%s' "$OUT" | grep -q 'scratch'; then
    ok "an unversioned scratch test is not counted against the repo"
else
    bad "an untracked test file was demanded of CI (rc=$RC)"
fi

# ── 12. a __tests__/ directory is a suite even with no .test. infix ──────────
# jest's default include is `__tests__/**/*.js` as well as `*.test.*`, so a
# package can hold a real suite that matches neither infix. This is the one
# failure the rest of the gate cannot catch: a suite the enumeration misses
# does not turn red, it disappears from the report, and "0 unreachable" then
# means "0 found". Nothing in this repo matches today — the case exists so
# that stays a measurement rather than an assumption.
R="$(mkrepo jestdirs)"
mkdir -p "$R/legacy/__tests__"
echo '{"name":"legacy","scripts":{"test":"jest"}}' > "$R/legacy/package.json"
echo 'test("x", () => {})' > "$R/legacy/__tests__/behaviour.js"
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'legacy'; then
    ok "a __tests__/ suite with no .test. infix is enumerated"
else
    bad "a __tests__/ suite was invisible to the enumeration — it would vanish, not fail (rc=$RC)"
fi

# ── mutation verification ────────────────────────────────────────────────────
# mutant <label> <old-literal> <new-literal> <fixture> <marker>
#
# `marker` must appear in the UNMUTATED gate's output for that fixture and must
# vanish once the mutation is applied. Both halves are checked, and the first
# half is not ceremony: the earlier version of this harness compared exit codes
# only, and the `|| true` mutant "passed" while proving nothing — the advisory
# fixture exits 0 either way, so the assertion was satisfied before the mutant
# was even built. A mutation harness that can report a vacuous catch is the
# same defect it exists to prevent, one level up.
echo ""
mutant() {
    local label="$1" old="$2" new="$3" fixture="$4" marker="$5"
    local mut="$TMP/mutant.sh"
    # Captured, then matched — never `gate | grep -q`. Under `pipefail` grep
    # exits at the first hit, the gate takes a SIGPIPE, and the pipeline reports
    # 141, so every marker reads as absent exactly when it is present. This bit
    # the gate itself an hour earlier, in the same shape.
    local before
    before="$("$BASH" "$GATE" "$TMP/$fixture" 2>&1)"
    if ! grep -q -- "$marker" <<< "$before"; then
        bad "VACUOUS MUTANT \"$label\" — \"$marker\" is absent from $fixture BEFORE mutating; it cannot disappear"
        return
    fi
    OLD="$old" NEW="$new" python3 - "$GATE" "$mut" <<'PY'
import os, sys
src, dst = sys.argv[1], sys.argv[2]
old, new = os.environ["OLD"], os.environ["NEW"]
text = open(src).read()
if old not in text:
    sys.exit(3)
open(dst, "w").write(text.replace(old, new, 1))
PY
    case $? in
        3) bad "INVALID MUTANT \"$label\" — the literal is absent; the case below proves nothing"; return ;;
        0) ;;
        *) bad "mutant \"$label\" could not be built"; return ;;
    esac
    local after
    after="$("$BASH" "$mut" "$TMP/$fixture" 2>&1)"
    if grep -q -- "$marker" <<< "$after"; then
        bad "mutant \"$label\" SURVIVED — $fixture still reports \"$marker\", so nothing pins it"
    else
        ok "mutant \"$label\" caught ($fixture stops reporting \"$marker\")"
    fi
}

mutant 'count comment lines as invocations' \
    'if (line ~ /^#/) next' 'if (0) next' comment '✗ app'
mutant 'count step titles as invocations' \
    'if (line ~ /^-?[[:space:]]*name:[[:space:]]/) next' 'if (0) next' stepname '✗ app'
mutant 'let working-directory leak past a step boundary' \
    '/^[[:space:]]*-[[:space:]]/ { wd=""; ce=0 }' '/^ZZZ_NEVER_MATCHES/ { wd=""; ce=0 }' leak '✗ app'
mutant 'treat any workflow as PR-triggered' \
    "grep -qE '^[[:space:]]{1,4}pull_request(_target)?:' \"\$1\"" 'return 0' nopr 'does not run on pull requests'
mutant 'ignore the path filter' \
    'if glob_covers "$g" "$suite"; then triggered=1; break; fi' 'triggered=1; break' nopath 'never triggers'
mutant 'stop excluding dist/ copies' \
    "| grep -vE '(^|/)(node_modules|dist|build|out)/' || true" '|| true' distonly '0 suite'
mutant 'ignore || true' \
    'line ~ /\|\|[[:space:]]*true/' '0' advtrue 'cannot fail the build'
# Narrow the enumeration back to the two infixes. The `legacy` package then
# leaves the report entirely — it is not reported unreachable, it is not
# reported at all, which is why this arm needs a mutant of its own.
mutant 'enumerate only .test./.spec., not __tests__/' \
    '(\.(test|spec)\.(ts|tsx|js|jsx|mjs|cjs)$|(^|/)__tests__/.+\.(ts|tsx|js|jsx|mjs|cjs)$)' \
    '\.(test|spec)\.(ts|tsx|js|jsx|mjs|cjs)$' jestdirs 'legacy'

echo ""
echo "test-check-test-suites-reachable: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
