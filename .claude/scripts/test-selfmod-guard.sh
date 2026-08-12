#!/usr/bin/env bash
# Self-test for pr-review.yml's `Detect self-modification of this workflow` step.
#
# WHY THIS EXISTS (#3038, #2976)
#   The step used to ask `git diff origin/$BASE -- pr-review.yml` — "does the
#   checkout differ from the base tip?" — while every message it writes claims
#   to answer "does this PR change that file?". A branch that simply had not
#   merged `main` since this file last changed satisfied the first and not the
#   second, and the step is a hard `exit 1`, so the result was NO review at all
#   on a PR whose diff was clean. Measured on #2963 and on #3036
#   (run 31132517900).
#
#   The guard cannot be moved into a script: it decides whether the PR under
#   review is allowed to be reviewed, so a script in the repo would be editable
#   by the very PR it judges. It therefore stays inline in the workflow, where
#   nothing but a real GitHub run can execute it — and a real run only exercises
#   the interesting branch when someone actually edits the workflow. This suite
#   extracts the step's `run:` block from the YAML — the shipped bytes, not a
#   copy — and runs it against synthetic repos, including a mutation that
#   restores the old two-dot comparison and must turn the suite red.
#
# USAGE   bash .claude/scripts/test-selfmod-guard.sh
# EXIT    0 = every case passed, 1 = at least one failed

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
WORKFLOW="$REPO_ROOT/.github/workflows/pr-review.yml"
STEP_NAME='Detect self-modification of this workflow'
GUARDED_FILE='.github/workflows/pr-review.yml'

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# Extract the step. A missing step is a FAILURE, not a skip: "the guard is
# gone" is one of the regressions this suite exists to catch.
# ---------------------------------------------------------------------------
if ! python3 - "$WORKFLOW" "$STEP_NAME" > "$TMP/step.sh" <<'PY'
import sys
try:
    import yaml
except ImportError:
    sys.exit("PyYAML not installed. Run 'pip install pyyaml' or 'apt-get install python3-yaml'.")
workflow, name = sys.argv[1], sys.argv[2]
steps = yaml.safe_load(open(workflow))["jobs"]["review"]["steps"]
match = [s for s in steps if s.get("name") == name]
if len(match) != 1:
    sys.exit(f"expected exactly 1 step named {name!r}, found {len(match)}")
if match[0].get("id") != "selfmod":
    sys.exit("the step no longer carries id: selfmod — later steps read steps.selfmod.outputs")
sys.stdout.write(match[0]["run"])
PY
then
  echo "::error title=Self-modification guard missing::Could not extract '$STEP_NAME' from pr-review.yml. See this script's header."
  exit 1
fi
echo "step extracted from pr-review.yml"

# `git init` inherits the user's global config, and a global `core.hooksPath`
# would veto the fixture commits — leaving a repo with no HEAD and a suite
# reporting failures about a step that is fine.
NOHOOKS="$TMP/nohooks"
mkdir -p "$NOHOOKS"
init_repo() {  # init_repo <dir>
  git init -q -b main "$1"
  git -C "$1" config core.hooksPath "$NOHOOKS"
  git -C "$1" config commit.gpgsign false
  git -C "$1" config user.email ci@sceneview.invalid
  git -C "$1" config user.name ci
}

# ---------------------------------------------------------------------------
# The fixture, built to reproduce the measured shape exactly:
#
#   upstream main:  v1 ──────────── v2 (pr-review.yml edited AFTER the branch)
#                    └── pr branch: touches something else entirely
#
# `behind` is the PR that must be reviewed and used to be refused. `selfmod` is
# the PR that must still be refused.
# ---------------------------------------------------------------------------
UPSTREAM="$TMP/upstream"
init_repo "$UPSTREAM"
(
  cd "$UPSTREAM" || exit 1
  mkdir -p "$(dirname "$GUARDED_FILE")" src
  printf 'name: review\n# v1\n' > "$GUARDED_FILE"
  printf 'ordinary\n'           > src/Thing.kt
  git add -A && git commit -qm base

  git checkout -qb behind
  printf 'a change that has nothing to do with the review workflow\n' > src/Thing.kt
  git add -A && git commit -qm 'pr: unrelated change'

  git checkout -qb selfmod main
  printf 'name: review\n# v1\n# edited by the PR\n' > "$GUARDED_FILE"
  git add -A && git commit -qm 'pr: edits the review workflow'

  # main moves on, editing the guarded file — this is what made `behind` stale.
  git checkout -q main
  printf 'name: review\n# v2\n' > "$GUARDED_FILE"
  git add -A && git commit -qm 'main: the review workflow changed'
) > /dev/null 2>&1

if [ "$(git -C "$UPSTREAM" rev-list --count main 2>/dev/null || echo 0)" -lt 2 ]; then
  echo "fixture repo was not created — this suite proves nothing; not reporting a result"
  exit 1
fi

# A `gh` that records instead of calling GitHub (#3028). The step now posts the
# "NOT REVIEWED" explanation onto the PR, and a stub is the only way to assert
# WHAT it posts. It answers the lookup with nothing (no existing comment) and
# copies the `-F body=@<file>` payload out, so the assertions read the bytes the
# step would have sent.
STUB_BIN="$TMP/bin"
mkdir -p "$STUB_BIN"
cat > "$STUB_BIN/gh" <<'STUB'
#!/usr/bin/env bash
method=GET
body=""
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="${2:-}"; shift 2 ;;
    -F) case "${2:-}" in body=@*) body="${2#body=@}" ;; esac; shift 2 ;;
    *) shift ;;
  esac
done
if [ "$method" = "GET" ]; then exit 0; fi   # lookup: no existing comment
echo "$method" >> "$GH_STUB_LOG"
[ -n "$body" ] && cp "$body" "$GH_STUB_OUT"
exit 0
STUB
chmod +x "$STUB_BIN/gh"

# run_guard <step-file> <branch> <event-name> — as GitHub runs it: `bash -e`
# plus the step's own `set`. Each case gets a fresh clone so a previous case
# cannot leave state behind.
run_guard() {
  local step="$1" branch="$2" event="${3:-pull_request}"
  local work="$TMP/work.$$"
  rm -rf "$work"
  git clone -q "$UPSTREAM" "$work" 2>/dev/null
  git -C "$work" config core.hooksPath "$NOHOOKS"
  git -C "$work" checkout -q "$branch"
  : > "$TMP/output"
  : > "$TMP/summary"
  rm -f "$TMP/posted-comment.md" "$TMP/gh-calls"
  : > "$TMP/gh-calls"
  (
    cd "$work" &&
    PATH="$STUB_BIN:$PATH" \
    EVENT_NAME="$event" \
    GITHUB_BASE_REF=main \
    GITHUB_REPOSITORY=sceneview/sceneview \
    PR_NUM=4242 \
    GH_STUB_OUT="$TMP/posted-comment.md" \
    GH_STUB_LOG="$TMP/gh-calls" \
    GITHUB_OUTPUT="$TMP/output" \
    GITHUB_STEP_SUMMARY="$TMP/summary" \
    bash -e "$step"
  ) > "$TMP/out" 2>&1
  GUARD_RC=$?
  GUARD_VERDICT="$(grep -oE 'self_modified=(true|false)' "$TMP/output" | tail -1 | cut -d= -f2)"
  rm -rf "$work"
  return 0
}

echo
echo "a PR that is merely behind main"
run_guard "$TMP/step.sh" behind
if [ "$GUARD_VERDICT" = false ] && [ "$GUARD_RC" -eq 0 ]; then
  ok "reviewed: verdict=false, exit 0"
else
  bad "refused a PR that never touched the workflow (verdict='$GUARD_VERDICT' rc=$GUARD_RC)"
  sed -n '1,15p' "$TMP/out"
fi

echo
echo "a PR that really does edit the workflow"
run_guard "$TMP/step.sh" selfmod
[ "$GUARD_VERDICT" = true ] && ok "refused: verdict=true" || bad "a self-modifying PR was let through (verdict='$GUARD_VERDICT')"
[ "$GUARD_RC" -eq 1 ] && ok "exits 1 so the check is red" || bad "self-modifying PR did not exit 1 (rc=$GUARD_RC)"
grep -q 'NOT REVIEWED' "$TMP/summary" && ok "writes the job summary" || bad "no job summary written — the maintainer gets an error with no explanation"

# #3028: the job summary is not where a maintainer looks. Until this landed the
# only trace on the PR was `Agent review (FAILURE)` — a red carrying nothing.
if [ -s "$TMP/posted-comment.md" ]; then
  ok "posts the explanation onto the PR, not only into the job summary"
else
  bad "nothing was posted to the PR — the maintainer sees a bare red 'Agent review (FAILURE)' (#3028)"
fi
grep -q 'NOT REVIEWED' "$TMP/posted-comment.md" 2>/dev/null \
  && ok "the PR comment says NOT REVIEWED" \
  || bad "the PR comment does not say NOT REVIEWED — 'failure' is the wrong word for 'not evaluated' (#3028)"
grep -qF '<!-- sceneview-agent-review -->' "$TMP/posted-comment.md" 2>/dev/null \
  && ok "carries the review marker, so a real verdict replaces it in place" \
  || bad "no <!-- sceneview-agent-review --> marker — a later real review would post a second comment instead of replacing this one"
grep -q 'gh workflow run pr-review.yml -f pr=4242' "$TMP/posted-comment.md" 2>/dev/null \
  && ok "names the dispatch that DOES review this PR, with its number" \
  || bad "the comment does not carry the runnable dispatch command for this PR"
# The comment must not be mistaken for the check going green.
[ "$GUARD_RC" -eq 1 ] \
  && ok "still exits 1 — the comment explains the red, it does not replace it" \
  || bad "posting the comment changed the verdict (rc=$GUARD_RC); a PR nobody reviewed must not look reviewed"

echo
echo "a workflow_dispatch"
run_guard "$TMP/step.sh" selfmod workflow_dispatch
# The dispatch path is deliberately exempt: the action validates the workflow it
# was dispatched with, not the checkout. Assert the exemption still holds even
# on the branch that DOES edit the file, which is the only branch where the two
# answers differ.
if [ "$GUARD_VERDICT" = false ] && [ "$GUARD_RC" -eq 0 ]; then
  ok "exempt: verdict=false, exit 0"
else
  bad "a dispatch was treated as a self-modification (verdict='$GUARD_VERDICT' rc=$GUARD_RC) — the fork rescue path is broken"
fi

echo
echo "an unresolvable base"
# No `origin` at all: `git fetch` fails, `origin/main` does not resolve. The
# step must degrade to "assume unmodified" and warn, never turn every PR red.
WORK="$TMP/nobase"
rm -rf "$WORK"
init_repo "$WORK"
(
  cd "$WORK" || exit 1
  mkdir -p "$(dirname "$GUARDED_FILE")"
  printf 'name: review\n' > "$GUARDED_FILE"
  git add -A && git commit -qm only
) > /dev/null 2>&1
: > "$TMP/output"
(
  cd "$WORK" &&
  EVENT_NAME=pull_request GITHUB_BASE_REF=main \
  GITHUB_OUTPUT="$TMP/output" GITHUB_STEP_SUMMARY="$TMP/summary" \
  bash -e "$TMP/step.sh"
) > "$TMP/out" 2>&1
NOBASE_RC=$?
NOBASE_VERDICT="$(grep -oE 'self_modified=(true|false)' "$TMP/output" | tail -1 | cut -d= -f2)"
if [ "$NOBASE_VERDICT" = false ] && [ "$NOBASE_RC" -eq 0 ]; then
  ok "degrades to false with a warning rather than failing closed"
else
  bad "an unresolvable base turned the check red (verdict='$NOBASE_VERDICT' rc=$NOBASE_RC)"
fi
grep -q '::warning' "$TMP/out" && ok "says so in the log" || bad "degraded silently — no warning in the log"

# ---------------------------------------------------------------------------
# MUTATION. Put the old two-dot comparison back. If the `behind` case still
# passes, this suite is decorative and proves nothing about the fix.
# ---------------------------------------------------------------------------
echo
echo "mutation: restore the base-tip comparison this fixed"
sed 's|git diff --quiet "\$MERGE_BASE" --|git diff --quiet "origin/$BASE" --|' \
  "$TMP/step.sh" > "$TMP/step-mutant.sh"
if cmp -s "$TMP/step.sh" "$TMP/step-mutant.sh"; then
  bad "the mutation changed nothing — the step no longer contains the line this suite claims to protect"
else
  run_guard "$TMP/step-mutant.sh" behind
  # Demand the EXACT wrong verdict, not merely a non-zero exit: a `bash: syntax
  # error` also exits non-zero, and accepting it would let this suite report
  # "the mutation was caught" while the mutant never ran. That is not
  # hypothetical — it is what this very check did on its first run.
  if [ "$GUARD_VERDICT" = true ] && [ "$GUARD_RC" -eq 1 ]; then
    ok "the old comparison refuses the behind-main PR — the suite has teeth"
  else
    bad "the mutant did not produce the historical failure (verdict='$GUARD_VERDICT' rc=$GUARD_RC); expected verdict=true rc=1 — either the fix is untested or the mutant did not run"
    sed -n '1,15p' "$TMP/out"
  fi
fi

# ---------------------------------------------------------------------------
# MUTATION #3028. Disarm the PR-comment block. The step must still be red — so
# a non-zero exit proves nothing here — and the assertions above must be the
# thing that notices, which is exactly why they check the posted BYTES rather
# than the exit code.
# ---------------------------------------------------------------------------
echo
echo "mutation: disarm the PR comment (#3028)"
sed 's|if \[ -n "\${PR_NUM:-}" \] && \[ -n "\${GITHUB_REPOSITORY:-}" \]; then|if false; then|' \
  "$TMP/step.sh" > "$TMP/step-nocomment.sh"
if cmp -s "$TMP/step.sh" "$TMP/step-nocomment.sh"; then
  bad "the mutation changed nothing — the step no longer contains the comment block this suite claims to protect"
else
  run_guard "$TMP/step-nocomment.sh" selfmod
  if [ "$GUARD_VERDICT" = true ] && [ "$GUARD_RC" -eq 1 ] && [ ! -s "$TMP/posted-comment.md" ]; then
    ok "without it the PR gets a bare red and nothing else — the assertions above have teeth"
  else
    bad "the mutant did not reproduce the #3028 shape (verdict='$GUARD_VERDICT' rc=$GUARD_RC, comment posted=$([ -s "$TMP/posted-comment.md" ] && echo yes || echo no)); expected verdict=true rc=1 and no comment"
    sed -n '1,15p' "$TMP/out"
  fi
fi

echo
echo "selfmod-guard: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
