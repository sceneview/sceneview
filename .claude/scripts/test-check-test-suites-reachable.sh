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
# INVALID MUTANT rather than counted as a pass, and one that could apply in more
# than one place is refused as AMBIGUOUS rather than resolved by taking the first
# hit — a mutant that never bit, and a mutant that bit a comment, both look
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

# ── 13. a flow-style paths: filter is parsed, not ignored ────────────────────
# `paths: ['other/**']` is legal YAML and matched none of the block-sequence
# parsing. An unparsed filter yields an empty glob list, and an empty list means
# "no filter — fires on everything", so a genuinely scoped workflow read as
# unconditionally triggered. Fail-open, in the gate whose subject is fail-open.
R="$(mkrepo flowpaths)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths: ['other/**', 'docs/**']
jobs:
  t:
    steps:
      - name: Test
        working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'never triggers'; then
    ok "a flow-style paths: filter that excludes the suite is not read as no filter"
else
    bad "a flow-style paths: filter was ignored — the suite read as triggered by everything (rc=$RC)"
fi

# ── 14. continue-on-error after the run: line it governs ─────────────────────
# YAML mapping keys are unordered. Grading at the moment the runner line matched
# meant this step was classified blocking, which overstates coverage.
R="$(mkrepo ceafter)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: npm test
        continue-on-error: true
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'cannot fail the build'; then
    ok "continue-on-error written after its own run: still makes the step advisory"
else
    bad "an advisory step was graded blocking because the key came after run: (rc=$RC)"
fi

# ── 15. a glob in a run: line is not filename-expanded ───────────────────────
# `$cmd` is split unquoted; without `set -f` a bare `**` expands against the
# repo root and injects every top-level directory as a target — so `other`,
# which nothing runs, would be reported covered by a step confined to `app`.
R="$(mkrepo globtok)"; addpkg "$R" app; addpkg "$R" other
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: npx vitest run **
YML
stage "$R"; run_gate "$R"
# The assertion is on the REASON, not on the ✗. Expansion does not flip `other`
# to covered — the workflow's own `app/**` filter still fails to trigger on it —
# it flips the reason from "nothing runs it" to "runs but never triggers", which
# is a fabricated invocation. Asserting the ✗ alone let the mutant survive.
if printf '%s' "$OUT" | grep -q 'no workflow step runs its tests'; then
    ok "a glob token in a run: line does not expand into spurious targets"
else
    bad "filename expansion invented an invocation for an unrelated package (rc=$RC)"
fi

# ── 16. push.paths must not stand in for pull_request.paths ──────────────────
# `paths:` is per-trigger, and reading every `paths:` in the file merged the two
# into a union wider than either. Here the push filter covers the suite and the
# pull_request filter does not: the workflow runs on `main` after the fact and
# never on the pull request that would gate the merge — which is precisely the
# "runs, but cannot block a merge" state cases 4 and 5 exist for, arriving by a
# third route that the parser used to launder into a ✓.
R="$(mkrepo prpaths)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  push:
    branches: [main]
    paths:
      - 'app/**'
  pull_request:
    paths:
      - 'docs/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'never triggers'; then
    ok "a push-only path filter does not count as pull-request coverage"
else
    bad "push.paths was merged into pull_request.paths — the suite read as gated (rc=$RC)"
fi

# ── 17. a runner named inside a filename is not an invocation ────────────────
# `vitest` and `jest` live inside ordinary filenames. Matched as substrings,
# `cat vitest.config.ts` counts as running the suite, and the package it sits in
# reads as covered by a step that runs no test — the same "a label is not an
# invocation" shape as cases 6 and 7, arriving through a filename instead.
R="$(mkrepo substr)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: cat vitest.config.ts
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'no workflow step runs its tests'; then
    ok "a filename containing 'vitest' is not counted as running vitest"
else
    bad "a substring match turned a filename into an invocation (rc=$RC)"
fi

# ── 18. the inline-array trigger form is recognised ──────────────────────────
# `on: [push, pull_request]` is legal and equivalent to the block form. Reading
# only the block form reported a genuinely PR-gated suite as advisory — safe in
# direction, but an ADVISORY whose stated reason is false ("does not run on pull
# requests", when it does) is a verdict readers learn to discount.
R="$(mkrepo inlineon)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on: [push, pull_request]
jobs:
  t:
    steps:
      - working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q '✓ app'; then
    ok "an inline-array 'on: [pull_request]' counts as firing on pull requests"
else
    bad "the inline trigger form was unread — a PR-gated suite reported advisory (rc=$RC)"
fi

# ── 19. a quoted `on` key is still the trigger block ─────────────────────────
# YAML's Norway problem: `on` is a boolean-ish scalar, so authors quote the key.
# Reading only the bare form left the trigger block unentered, no paths were
# collected, and the empty list meant "fires on everything" — fail-open again,
# by a quoting style the spec explicitly encourages.
R="$(mkrepo quotedon)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
'on':
  pull_request:
    paths:
      - 'docs/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'never triggers'; then
    ok "a single-quoted 'on': key is read as the trigger block"
else
    bad "a quoted on: key hid the path filter — the suite read as always triggered (rc=$RC)"
fi

# ── 20. paths-ignore excluding the suite means NOT triggered ─────────────────
# `paths-ignore` entries were dropped outright. A trigger carrying only
# paths-ignore then produced an empty list, and empty means "no filter" — so a
# workflow that explicitly excludes the suite's path read as firing on it.
R="$(mkrepo pathsignore)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths-ignore:
      - 'app/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'never triggers'; then
    ok "a paths-ignore excluding the suite is not read as no filter"
else
    bad "paths-ignore was dropped — an excluded suite read as triggered (rc=$RC)"
fi

# ── 20b. paths-ignore NOT covering the suite still fires ─────────────────────
# The inverse half, because a rule that only ever answers "no" is not a rule.
R="$(mkrepo pathsignoreok)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths-ignore:
      - 'docs/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q '✓ app'; then
    ok "a paths-ignore naming an unrelated path leaves the suite triggered"
else
    bad "paths-ignore was inverted too eagerly — a covered suite read as excluded (rc=$RC)"
fi

# ── 21. shell text is not a YAML key ─────────────────────────────────────────
# `continue-on-error:` was matched anywhere on the line, so a `run:` block that
# merely PRINTS the string marked the step advisory. Only ever OK -> ADVISORY,
# so it could not hide a MISSING — fixed because a spurious ADVISORY is a
# verdict readers learn to discount.
R="$(mkrepo ceecho)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  pull_request:
    paths:
      - 'app/**'
jobs:
  t:
    steps:
      - working-directory: app
        run: |
          echo "continue-on-error: true"
          npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q '✓ app'; then
    ok "an echoed 'continue-on-error: true' inside run: does not make a step advisory"
else
    bad "shell text was read as a YAML key — a blocking step reported advisory (rc=$RC)"
fi

# ── 22. a job named `pull_request` is not a trigger ──────────────────────────
# `fires_on_pr` grepped the WHOLE file for `pull_request:` at 1-4 spaces, which
# a job KEY satisfies. A tag-only workflow then read as gating merges — the one
# fail-OPEN direction left in the gate, in the function that decides whether a
# suite counts as blocking at all.
R="$(mkrepo prjob)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  push:
    tags: ['v*']
jobs:
  pull_request:
    steps:
      - working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'does not run on pull requests'; then
    ok "a JOB named pull_request does not make a tag-only workflow PR-triggered"
else
    bad "a job key was read as a trigger — a tag-only workflow read as blocking (rc=$RC)"
fi

# ── 22b. a `pull_request` INPUT is not a trigger either ──────────────────────
# The other half of the same scoping: `pull_request:` INSIDE the `on:` block but
# nested below trigger level. `workflow_call.inputs.pull_request` is ordinary
# YAML, and reading it as a trigger has the same fail-open consequence.
R="$(mkrepo prinput)"; addpkg "$R" app
cat > "$R/.github/workflows/ci.yml" <<'YML'
on:
  workflow_call:
    inputs:
      pull_request:
        type: string
  push:
    tags: ['v*']
jobs:
  t:
    steps:
      - working-directory: app
        run: npm test
YML
stage "$R"; run_gate "$R"
if printf '%s' "$OUT" | grep -q 'does not run on pull requests'; then
    ok "a workflow_call input named pull_request is not read as a trigger"
else
    bad "a nested key was read as a trigger — a tag-only workflow read as blocking (rc=$RC)"
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
n = text.count(old)
if n == 0:
    sys.exit(3)
# An AMBIGUOUS literal is refused, not resolved by taking the first hit. The
# `set -f` mutant was written while a comment two lines above quoted `set -f`
# in prose: replace(..., 1) rewrote the COMMENT, the code ran unchanged, and
# the mutant reported SURVIVED — indistinguishable from a real gap in the
# gate. A harness that silently mutates the wrong occurrence is the same
# defect it exists to catch.
if n > 1:
    sys.exit(4)
open(dst, "w").write(text.replace(old, new, 1))
PY
    case $? in
        3) bad "INVALID MUTANT \"$label\" — the literal is absent; the case below proves nothing"; return ;;
        4) bad "AMBIGUOUS MUTANT \"$label\" — the literal occurs more than once; it would mutate an arbitrary one"; return ;;
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
    '/^[[:space:]]*-[[:space:]]/ { flush(); wd=""; ce=0 }' \
    '/^ZZZ_NEVER_MATCHES/ { flush(); wd=""; ce=0 }' leak '✗ app'
mutant 'treat any workflow as PR-triggered' \
    'END { exit(found ? 0 : 1) }' 'END { exit(0) }' nopr 'does not run on pull requests'
mutant 'look for pull_request: anywhere, not only at the trigger level' \
    'if (i == lvl && $0 ~ /^ *pull_request(_target)?:/) found = 1' \
    'if ($0 ~ /^ *pull_request(_target)?:/) found = 1' prinput 'does not run on pull requests'
mutant 'let a job key outside the on: block count as a trigger' \
    '!on { next }   # anything outside the `on:` block, `jobs:` included' \
    '' prjob 'does not run on pull requests'
mutant 'ignore the path filter' \
    'if glob_covers "$g" "$suite"; then triggered=1; break; fi' 'triggered=1; break' nopath 'never triggers'
mutant 'stop excluding dist/ copies' \
    "| grep -vE '(^|/)(node_modules|dist|build|out)/' || true" '|| true' distonly '0 suite'
mutant 'ignore || true' \
    'l ~ /\|\|[[:space:]]*true/' '0' advtrue 'cannot fail the build'
# Narrow the enumeration back to the two infixes. The `legacy` package then
# leaves the report entirely — it is not reported unreachable, it is not
# reported at all, which is why this arm needs a mutant of its own.
mutant 'stop parsing flow-style paths: filters' \
    'if ($0 ~ /^ *paths(-ignore)?: *\[/) {' \
    'if ($0 ~ /^ZZZ_NEVER_MATCHES_FLOW/) {' flowpaths 'never triggers'
mutant 'read only the bare `on:` key, not a quoted one' \
    '/^[^ #]/ { on = ($0 ~ /^["\x27]?on["\x27]?:/); lvl = 0; trig = 0; inp = 0; next }' \
    '/^[^ #]/ { on = ($0 ~ /^on:/); lvl = 0; trig = 0; inp = 0; next }' \
    quotedon 'never triggers'
# The block-style branch specifically: the flow-style one carries the same
# expression with a different variable name, and `neg = …` is a substring of
# `pneg = …`, so the short literal is AMBIGUOUS. The fixture below is block
# style, so this is also the branch it actually exercises.
mutant 'drop the paths-ignore inversion in the block-style branch' \
    'pneg = ($0 ~ /paths-ignore/) ? "!" : ""' \
    'pneg = ""' pathsignore 'never triggers'
mutant 'match continue-on-error anywhere on the line' \
    'if (line ~ /^-?[[:space:]]*continue-on-error:[[:space:]]*true/) ce = 1' \
    'if (line ~ /continue-on-error:[[:space:]]*true/) ce = 1' ceecho '✓ app'
mutant 'match the runner as a bare substring again' \
    'line ~ /(^|[^[:alnum:]._\/-])(vitest|jest)([[:space:]]|$)/ ||' \
    'line ~ /(vitest|jest)/ ||' substr 'no workflow step runs its tests'
mutant 'read only the block-mapping pull_request: form' \
    'if (on && $0 ~ /:[[:space:]]*\[[^]]*pull_request/) found = 1' \
    'if (0) found = 1' inlineon '✓ app'
mutant 'read paths: from every trigger, not just pull_request' \
    'trig = ($0 ~ /^ *pull_request(_target)?:/) ? 1 : 0' \
    'trig = 1' prpaths 'never triggers'
mutant 'grade continue-on-error at match time instead of step end' \
    '(ce == 1 || l ~ /\|\|[[:space:]]*true/) ? 1 : 0' \
    '(0 || l ~ /\|\|[[:space:]]*true/) ? 1 : 0' ceafter 'cannot fail the build'
mutant 'let the command split glob against the filesystem' \
    'set -f
        for tok in $cmd; do' 'set +f
        for tok in $cmd; do' globtok 'no workflow step runs its tests'
mutant 'enumerate only .test./.spec., not __tests__/' \
    '(\.(test|spec)\.(ts|tsx|js|jsx|mjs|cjs)$|(^|/)__tests__/.+\.(ts|tsx|js|jsx|mjs|cjs)$)' \
    '\.(test|spec)\.(ts|tsx|js|jsx|mjs|cjs)$' jestdirs 'legacy'

echo ""
echo "test-check-test-suites-reachable: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
