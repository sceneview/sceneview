#!/usr/bin/env bash
# Self-test for pr-review.yml's two "why was this PR not reviewed?" guards:
#   `Detect self-modification of this workflow`  (id: selfmod)
#   `Detect a workflow-validation skip`          (id: wfval, #3140)
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
# AND THE SECOND DOOR (#3140)
#   `selfmod` asks "does this PR change pr-review.yml?". `claude-code-action`
#   asks "does the file I am running differ from the default branch RIGHT NOW?".
#   Those are different predicates, and on run 31612615435 the second fired on a
#   PR (#3134) that satisfied neither the first nor any fault of its own: the
#   run's workflow ref was pinned at 15:29:53, the job waited 27 minutes for a
#   runner, #3138 edited this file on `main` at 15:54:40, and the action refused
#   at 15:57:01. The result was a red `REVIEW_INCOMPLETE` whose "see the review
#   comment on the PR" pointed at the PREVIOUS run's `MERGE_AFTER_WARNINGS`.
#   `wfval` recognises the OUTCOME by comparing blob hashes — never the action's
#   log wording, which would go silent on a rename — and only when the review
#   produced nothing, so it can never relabel a healthy review. Both properties
#   are mutation-tested below.
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

  # #3140's control case: a checkout whose copy of the guarded file IS main's.
  # On a real `pull_request` this is the normal shape — the checkout is the
  # MERGE ref, so it carries main's copy even for a branch that never merged it.
  # `behind` is then the abnormal one: the file the run executes differs from
  # the default branch, which is exactly what makes the action refuse.
  git checkout -qb uptodate main
  printf 'yet another ordinary change\n' > src/Thing.kt
  git add -A && git commit -qm 'pr: unrelated change, workflow copy current'
) > /dev/null 2>&1

if [ "$(git -C "$UPSTREAM" rev-list --count main 2>/dev/null || echo 0)" -lt 2 ]; then
  echo "fixture repo was not created — this suite proves nothing; not reporting a result"
  exit 1
fi

# A `gh` that records instead of calling GitHub (#3028). The step now posts the
# "NOT REVIEWED" explanation onto the PR, and a stub is the only way to assert
# WHAT it posts. It copies the `-F body=@<file>` payload out, so the assertions
# read the bytes the step would have sent, and logs `<method> <endpoint>` for
# every write.
#
# The lookup answer is a KNOB, not a constant. A stub that always replies "no
# existing comment" exercises the POST branch and leaves the PATCH branch — the
# entire point of the `<!-- sceneview-agent-review -->` marker — untested, so a
# regression that posts a duplicate on every re-run would pass. `GH_STUB_EXISTING_ID`
# makes the second run of the same PR a real case.
STUB_BIN="$TMP/bin"
mkdir -p "$STUB_BIN"
cat > "$STUB_BIN/gh" <<'STUB'
#!/usr/bin/env bash
method=GET
body=""
url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="${2:-}"; shift 2 ;;
    -F) case "${2:-}" in body=@*) body="${2#body=@}" ;; esac; shift 2 ;;
    --jq) shift 2 ;;
    api|--paginate|-*) shift ;;
    *) [ -n "$url" ] || url="$1"; shift ;;
  esac
done
if [ "$method" = "GET" ]; then
  # The step reads this through `--jq '… | .[0].id // empty'`; the stub answers
  # post-jq, which is what the step's `$(...)` actually consumes. `--paginate`
  # applies the filter ONCE PER PAGE, so a real multi-page lookup emits one id
  # per matching page — the stub reproduces that with GH_STUB_PAGES, and a step
  # that takes the raw output would build `repos/…/comments/99123\n99123`.
  if [ -n "${GH_STUB_EXISTING_ID:-}" ]; then
    n=0
    while [ "$n" -lt "${GH_STUB_PAGES:-1}" ]; do echo "$GH_STUB_EXISTING_ID"; n=$((n + 1)); done
  fi
  exit 0
fi
echo "$method $url" >> "$GH_STUB_LOG"
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
  RUNNER_TMP="$TMP/runner-temp"
  rm -rf "$work" "$RUNNER_TMP"
  mkdir -p "$RUNNER_TMP"
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
    GH_STUB_EXISTING_ID="${GH_STUB_EXISTING_ID:-}" \
    GH_STUB_PAGES="${GH_STUB_PAGES:-1}" \
    GH_STUB_OUT="$TMP/posted-comment.md" \
    GH_STUB_LOG="$TMP/gh-calls" \
    GITHUB_OUTPUT="$TMP/output" \
    GITHUB_STEP_SUMMARY="$TMP/summary" \
    RUNNER_TEMP="$RUNNER_TMP" \
    bash -e "$step"
  ) > "$TMP/out" 2>&1
  GUARD_RC=$?
  # What the step left behind in the CHECKOUT. `Assert the reviewers left the
  # tree clean` runs on the same working directory, so a scratch file written
  # here is later blamed on a reviewer.
  GUARD_DIRT="$(git -C "$work" status --porcelain 2>/dev/null)"
  GUARD_VERDICT="$(grep -oE 'self_modified=(true|false)' "$TMP/output" | tail -1 | cut -d= -f2)"
  rm -rf "$work"
  return 0
}

# calls_were <expected-log> <pass-message> <fail-message>
#
# Compares the WHOLE recorded call log, never a line within it. `grep -qx` was
# not enough and this is not theoretical: a lookup that returns three ids builds
# the endpoint `…/comments/99123<newline>99123<newline>99123`, whose FIRST LINE
# is byte-identical to the correct call — so `grep -qx` passed on a mutant that
# had removed the pagination guard entirely. An exact comparison also makes
# "PATCHed and then POSTed as well" a failure without a separate assertion.
calls_were() {
  local expected="$1" pass_msg="$2" fail_msg="$3"
  local actual
  actual="$(cat "$TMP/gh-calls" 2>/dev/null)"
  if [ "$actual" = "$expected" ]; then
    ok "$pass_msg"
  else
    bad "$fail_msg — expected exactly [$expected], got [$(tr '\n' ';' <<< "$actual")]"
  fi
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
calls_were "POST repos/sceneview/sceneview/issues/4242/comments" \
  "with no marker comment present it POSTs a new one" \
  "did not POST exactly once to the PR's comments endpoint"
# The comment is scratch, and the checkout is what `Assert the reviewers left
# the tree clean` measures a few steps later.
[ -z "$GUARD_DIRT" ] \
  && ok "leaves the checkout clean — the comment file is written under RUNNER_TEMP" \
  || bad "the step dirtied the checkout ($GUARD_DIRT); a later step blames that on a reviewer"
# The comment must not be mistaken for the check going green.
[ "$GUARD_RC" -eq 1 ] \
  && ok "still exits 1 — the comment explains the red, it does not replace it" \
  || bad "posting the comment changed the verdict (rc=$GUARD_RC); a PR nobody reviewed must not look reviewed"

echo
echo "a second run on the same PR, with the marker comment already there"
# The marker exists so the explanation is UPDATED rather than stacked: a push
# burst on a workflow-editing PR must not leave five identical "NOT REVIEWED"
# comments, and a genuine verdict later has to be able to replace this one in
# place. Both properties live entirely in the PATCH branch.
# Set as a plain variable, not as a `VAR=x run_guard …` prefix: bash keeps an
# assignment that precedes a FUNCTION call in the shell afterwards, and every
# later case would silently run with a marker comment present.
GH_STUB_EXISTING_ID=99123
run_guard "$TMP/step.sh" selfmod
calls_were "PATCH repos/sceneview/sceneview/issues/comments/99123" \
  "updates the existing comment in place, by its id, and does not also POST" \
  "did not PATCH the existing marker comment exactly once"
grep -q 'NOT REVIEWED' "$TMP/posted-comment.md" 2>/dev/null \
  && ok "the updated body still carries the explanation" \
  || bad "the PATCH sent a body that does not say NOT REVIEWED"

echo
echo "the same, on a PR whose comments span several API pages"
# `gh api --paginate --jq` applies the filter per page, so the lookup emits one
# id per matching page. Taking the raw output would build the endpoint
# `…/issues/comments/99123 99123 99123` and every re-run would 404 back into
# posting a duplicate.
GH_STUB_PAGES=3
run_guard "$TMP/step.sh" selfmod
calls_were "PATCH repos/sceneview/sceneview/issues/comments/99123" \
  "keeps one id across pages" \
  "a paginated lookup produced a malformed endpoint"
GH_STUB_PAGES=1

# ---------------------------------------------------------------------------
# MUTATION. Make the step ignore the lookup and always POST. The check is red
# either way and the comment is still posted, so only the two assertions above
# can notice — which is the point of asserting the METHOD and the endpoint
# rather than "a comment was sent".
# ---------------------------------------------------------------------------
echo
echo "mutation: always POST, ignoring the existing marker comment"
sed 's|if \[ -n "\$EXISTING" \]; then|if false; then|' "$TMP/step.sh" > "$TMP/step-alwayspost.sh"
if cmp -s "$TMP/step.sh" "$TMP/step-alwayspost.sh"; then
  bad "the mutation changed nothing — the step no longer branches on an existing marker comment"
else
  run_guard "$TMP/step-alwayspost.sh" selfmod
  if grep -q '^POST ' "$TMP/gh-calls" 2>/dev/null && ! grep -q '^PATCH ' "$TMP/gh-calls" 2>/dev/null; then
    ok "the mutant stacks a duplicate comment — the update-in-place assertions have teeth"
  else
    bad "the mutant did not reproduce the duplicate-comment shape (calls: $(tr '\n' ';' < "$TMP/gh-calls")); expected a POST and no PATCH"
  fi
fi
unset GH_STUB_EXISTING_ID

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

# ===========================================================================
# `Detect a workflow-validation skip` (#3140) — the SECOND door.
# ===========================================================================
WFVAL_STEP_NAME='Detect a workflow-validation skip'

if ! python3 - "$WORKFLOW" "$WFVAL_STEP_NAME" > "$TMP/wfval.sh" <<'PY'
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
if match[0].get("id") != "wfval":
    sys.exit("the step no longer carries id: wfval — later steps read steps.wfval.outputs")
sys.stdout.write(match[0]["run"])
PY
then
  echo "::error title=Workflow-validation recogniser missing::Could not extract '$WFVAL_STEP_NAME' from pr-review.yml. See this script's header (#3140)."
  exit 1
fi
echo
echo "step extracted from pr-review.yml: $WFVAL_STEP_NAME"

# run_wfval <step-file> <branch> <event> <verdict-file:yes|no> [workflow-sha-rev]
# The last argument, when given, is resolved in the fixture and exported as
# WORKFLOW_SHA — the run's PINNED workflow ref, which is what the action really
# validated and which is not always the checkout.
run_wfval() {
  local step="$1" branch="$2" event="${3:-pull_request}" verdict="${4:-no}" sha_rev="${5:-}"
  local work="$TMP/wfval-work.$$" rt="$TMP/wfval-temp" sha=""
  rm -rf "$work" "$rt"
  mkdir -p "$rt"
  git clone -q "$UPSTREAM" "$work" 2>/dev/null
  git -C "$work" config core.hooksPath "$NOHOOKS"
  git -C "$work" checkout -q "$branch"
  [ -n "$sha_rev" ] && sha="$(git -C "$work" rev-parse "$sha_rev" 2>/dev/null || echo "")"
  [ "$verdict" = yes ] && printf '{"reviewersExpected":4,"reviewersRan":4}\n' > "$rt/review-verdict.json"
  : > "$TMP/output"
  : > "$TMP/summary"
  (
    cd "$work" &&
    EVENT_NAME="$event" \
    DEFAULT_BRANCH=main \
    WORKFLOW_SHA="$sha" \
    GITHUB_OUTPUT="$TMP/output" \
    GITHUB_STEP_SUMMARY="$TMP/summary" \
    RUNNER_TEMP="$rt" \
    bash -e "$step"
  ) > "$TMP/wfval-out" 2>&1
  WFVAL_RC=$?
  WFVAL_DIRT="$(git -C "$work" status --porcelain 2>/dev/null)"
  WFVAL_SKIP="$(grep -oE 'validation_skip=(true|false)' "$TMP/output" | tail -1 | cut -d= -f2)"
  rm -rf "$work" "$rt"
  return 0
}

echo
echo "the workflow file the run executes differs from the default branch"
run_wfval "$TMP/wfval.sh" behind pull_request no
[ "$WFVAL_SKIP" = true ] && ok "recognised: validation_skip=true" \
  || bad "the skip went unrecognised (validation_skip='$WFVAL_SKIP') — the PR gets REVIEW_INCOMPLETE naming the wrong cause (#3140)"
[ "$WFVAL_RC" -eq 0 ] \
  && ok "exits 0 — the grader below turns this into the verdict and the PR comment" \
  || bad "the recogniser exited $WFVAL_RC; killing the job here would skip the comment that replaces the stale green one"
grep -q '::error title=NOT EVALUATED' "$TMP/wfval-out" \
  && ok "says NOT EVALUATED in the log, not 'failure'" \
  || bad "no NOT EVALUATED annotation — the run record still reads as a finding about the PR"
[ -z "$WFVAL_DIRT" ] \
  && ok "leaves the checkout clean" \
  || bad "the step dirtied the checkout ($WFVAL_DIRT); 'Assert the reviewers left the tree clean' blames that on a reviewer"

echo
echo "the normal shape: the running copy matches the default branch"
run_wfval "$TMP/wfval.sh" uptodate pull_request no
[ "$WFVAL_SKIP" = false ] && [ "$WFVAL_RC" -eq 0 ] \
  && ok "not a validation skip — the empty review is diagnosed generically" \
  || bad "claimed a validation skip on a matching workflow file (validation_skip='$WFVAL_SKIP' rc=$WFVAL_RC)"

echo
echo "a HEALTHY review, with main moving underneath it afterwards"
# The default branch may legitimately change a second after a good review. If
# the mismatch alone decided this, that review would be relabelled "NOT
# EVALUATED" and its findings thrown away. The verdict file is what settles it.
run_wfval "$TMP/wfval.sh" behind pull_request yes
[ "$WFVAL_SKIP" = false ] \
  && ok "a verdict file was written, so the action ran — not a skip" \
  || bad "a completed review was relabelled NOT EVALUATED (validation_skip='$WFVAL_SKIP'); its findings would be discarded"

echo
echo "the run's pinned workflow ref, not the checkout, is what the action validated"
run_wfval "$TMP/wfval.sh" behind pull_request no origin/main
[ "$WFVAL_SKIP" = false ] \
  && ok "WORKFLOW_SHA wins over HEAD" \
  || bad "the recogniser read HEAD while WORKFLOW_SHA pointed at the default branch's copy (validation_skip='$WFVAL_SKIP')"

echo
echo "a workflow_dispatch"
# Exempt for the same reason `selfmod` is: the dispatch runs the default
# branch's copy of this workflow, whatever the checkout happens to contain.
run_wfval "$TMP/wfval.sh" behind workflow_dispatch no
[ "$WFVAL_SKIP" = false ] && [ "$WFVAL_RC" -eq 0 ] \
  && ok "exempt: validation_skip=false, exit 0" \
  || bad "a dispatch was reported as a validation skip (validation_skip='$WFVAL_SKIP' rc=$WFVAL_RC) — the fork rescue path would never be reviewed"

echo
echo "an unresolvable default branch"
WFWORK="$TMP/wfval-nobase"
rm -rf "$WFWORK"
init_repo "$WFWORK"
(
  cd "$WFWORK" || exit 1
  mkdir -p "$(dirname "$GUARDED_FILE")"
  printf 'name: review\n' > "$GUARDED_FILE"
  git add -A && git commit -qm only
) > /dev/null 2>&1
: > "$TMP/output"
WF_RT="$TMP/wfval-temp-nobase"
rm -rf "$WF_RT"; mkdir -p "$WF_RT"
(
  cd "$WFWORK" &&
  EVENT_NAME=pull_request DEFAULT_BRANCH=main WORKFLOW_SHA="" \
  GITHUB_OUTPUT="$TMP/output" GITHUB_STEP_SUMMARY="$TMP/summary" \
  RUNNER_TEMP="$WF_RT" \
  bash -e "$TMP/wfval.sh"
) > "$TMP/wfval-out" 2>&1
WF_NOBASE_RC=$?
WF_NOBASE_SKIP="$(grep -oE 'validation_skip=(true|false)' "$TMP/output" | tail -1 | cut -d= -f2)"
rm -rf "$WF_RT"
[ "$WF_NOBASE_SKIP" = false ] && [ "$WF_NOBASE_RC" -eq 0 ] \
  && ok "degrades to false rather than inventing a cause it cannot verify" \
  || bad "an unresolvable default branch produced a verdict anyway (validation_skip='$WF_NOBASE_SKIP' rc=$WF_NOBASE_RC)"
grep -q '::warning' "$TMP/wfval-out" && ok "says so in the log" || bad "degraded silently — no warning in the log"

# ---------------------------------------------------------------------------
# MUTATION. Drop the "only when there is no verdict file" guard. The recogniser
# then fires on any run whose default branch has moved — including a review that
# completed and wrote findings, which would be discarded as NOT EVALUATED.
# ---------------------------------------------------------------------------
echo
echo "mutation: recognise a skip even when the review produced a verdict"
sed 's|if \[ -s "\${RUNNER_TEMP:-\.}/review-verdict.json" \]; then|if false; then|' \
  "$TMP/wfval.sh" > "$TMP/wfval-noguard.sh"
if cmp -s "$TMP/wfval.sh" "$TMP/wfval-noguard.sh"; then
  bad "the mutation changed nothing — the step no longer gates on the verdict file"
else
  run_wfval "$TMP/wfval-noguard.sh" behind pull_request yes
  [ "$WFVAL_SKIP" = true ] \
    && ok "the mutant discards a completed review — the guard has teeth" \
    || bad "the mutant did not reproduce the relabelling (validation_skip='$WFVAL_SKIP'); expected true — either the ordering is untested or the mutant did not run"
fi

# ---------------------------------------------------------------------------
# MUTATION. Neuter the content comparison. Everything else still prints, so
# only the first case can notice — which is why it asserts the OUTPUT and not
# merely that the step ran.
# ---------------------------------------------------------------------------
echo
echo "mutation: never compare the workflow file"
sed 's|if \[ "\$RUNNING" = "\$CURRENT" \]; then|if true; then|' \
  "$TMP/wfval.sh" > "$TMP/wfval-nocompare.sh"
if cmp -s "$TMP/wfval.sh" "$TMP/wfval-nocompare.sh"; then
  bad "the mutation changed nothing — the step no longer compares the running workflow file against the default branch"
else
  run_wfval "$TMP/wfval-nocompare.sh" behind pull_request no
  [ "$WFVAL_SKIP" = false ] \
    && ok "the mutant misses the skip — #3140 is back, and the first case is what catches it" \
    || bad "the mutant still reported the skip (validation_skip='$WFVAL_SKIP'); the comparison is not what decides"
fi

echo
echo "selfmod-guard: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
