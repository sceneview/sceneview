#!/usr/bin/env bash
# Validate shell blocks in .github/workflows/*.yml so we catch CI portability
# bugs at PR-check time instead of after the workflow ships to main.
#
# Three flavours of block, checked differently:
#
# 1. `with.script:` blocks inside `uses:` steps (e.g.
#    `ReactiveCircus/android-emulator-runner@v2`). The runner action executes
#    each line via `sh -c <line>`, which on a stock Ubuntu image means dash.
#    Multi-line constructs (`while ... done`), bash conditional `[[ ... ]]`,
#    arrays, and process substitution all silently break. We run these
#    through `dash -n` and fail the gate on any error.
#
# 2. Direct `run:` blocks. GitHub Actions defaults `run:` to `bash -e {0}` on
#    Linux + macOS runners, so bashisms are not a *failure* here — but
#    shellcheck warnings still surface portability concerns. We treat these
#    as informational only (warnings, not gate failures), so the existing
#    `run:` corpus stays green.
#
# 3. `with.script:` blocks for `actions/github-script` specifically (e.g.
#    `.github/workflows/issue-intake.yml`, #2734). Unlike flavour 1, this
#    action's `script:` is JavaScript executed by Node — wrapped internally
#    in an async function, never shelled through `sh -c` — so running it
#    through `dash -n` is a guaranteed false positive (JS syntax like
#    `const x = await foo()` is not valid POSIX sh). These blocks are routed
#    to a separate bucket and checked with `node --check` instead, after
#    wrapping in an async IIFE so top-level `await` (which
#    actions/github-script supports) doesn't trip a spurious syntax error.
#
# Three real-world bugs that this gate would have caught at #1 time:
#   - PR #1068 used `[[ ... ]]` inside a `with.script:` block — shipped to
#     main as a silent syntax error under dash; fixed by PR #1087.
#   - PR #1068 used a multi-line `while ... done` block — sliced by the
#     emulator runner's per-line `sh -c`; fixed by PR #1104.
#   - HOTFIX-V430 (commit efc168bc) used a multi-line `android run \`
#     backslash continuation — `dash -n` accepts a `\<EOL>` because the
#     parser sees the whole file as one script, but the runner ships each
#     line through `sh -c`, so the trailing `\` becomes a literal argv
#     token (`Unmatched argument at index 2: '\\'`). Fixed by #1153, and
#     the per-line slicing check below now catches the same class of bug.
#
# All three were validated locally on macOS, where `sh` is bash-backed, so
# the original authors saw zero failures. This script prevents that whole
# class of bug by:
#   - running the actual `dash` parser on every `with.script:` block, AND
#   - simulating the per-line `sh -c` slicing the action does at runtime
#     (catches trailing-backslash continuations + multi-line constructs
#     that pass a whole-file syntax check but break under per-line exec).
#
# Closes #1114, #1153.

set -euo pipefail

WORKFLOWS_DIR="${WORKFLOWS_DIR:-.github/workflows}"
EXIT=0

if [ ! -d "$WORKFLOWS_DIR" ]; then
    echo "::error::workflows dir not found: $WORKFLOWS_DIR"
    exit 1
fi

if ! command -v dash >/dev/null 2>&1; then
    echo "::error::dash not on PATH. Install via 'apt-get install dash' (linux) or 'brew install dash' (macOS)."
    exit 1
fi

HAVE_SHELLCHECK=0
if command -v shellcheck >/dev/null 2>&1; then
    HAVE_SHELLCHECK=1
else
    echo "::warning::shellcheck not installed — informational portability checks skipped."
fi

TMPDIR_SCRIPTS="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_SCRIPTS"' EXIT

mkdir -p "$TMPDIR_SCRIPTS/run" "$TMPDIR_SCRIPTS/with-script" "$TMPDIR_SCRIPTS/github-script"

# Extract every script block via a Python helper so we don't reinvent YAML
# parsing in shell. Emits one file per block under $TMPDIR_SCRIPTS/{run,with-script}.
python3 - "$WORKFLOWS_DIR" "$TMPDIR_SCRIPTS" <<'PY'
import os
import re
import sys
import glob

try:
    import yaml
except ImportError:
    print("::error::PyYAML not installed. Run 'pip install pyyaml' or 'apt-get install python3-yaml'.")
    sys.exit(1)

workflows_dir, out_dir = sys.argv[1], sys.argv[2]
slug_re = re.compile(r"[^A-Za-z0-9_-]+")

def slug(s):
    return slug_re.sub("-", (s or "unnamed")).strip("-") or "unnamed"

def walk_steps(jobs):
    for job_name, job in (jobs or {}).items():
        if not isinstance(job, dict):
            continue
        for idx, step in enumerate(job.get("steps", []) or []):
            if not isinstance(step, dict):
                continue
            yield job_name, idx, step

count_run = 0
count_script = 0
count_gh_script = 0
for wf in sorted(glob.glob(os.path.join(workflows_dir, "*.yml"))):
    try:
        with open(wf) as f:
            doc = yaml.safe_load(f) or {}
    except yaml.YAMLError as e:
        print(f"::error file={wf}::YAML parse failed: {e}")
        sys.exit(1)

    wf_slug = slug(os.path.splitext(os.path.basename(wf))[0])

    for job_name, idx, step in walk_steps(doc.get("jobs")):
        # Direct `run:` step. Defaults to bash on linux+mac runners, so we
        # only run shellcheck on these (informational), not dash.
        run_block = step.get("run")
        if isinstance(run_block, str) and run_block.strip():
            shell = step.get("shell") or doc.get("defaults", {}).get("run", {}).get("shell")
            if shell in ("pwsh", "powershell", "cmd", "python"):
                continue
            name = step.get("name") or f"step{idx}"
            fname = f"{wf_slug}__{slug(job_name)}__{idx:02d}__{slug(name)}.sh"
            with open(os.path.join(out_dir, "run", fname), "w") as f:
                f.write(run_block)
            count_run += 1

        # `with.script:` block. ReactiveCircus/android-emulator-runner@v2 and
        # most other actions run each line via `sh -c <line>` → dash on Linux
        # runners. THIS is where bashisms are genuinely fatal.
        #
        # actions/github-script is the one well-known exception: its
        # `script:` is JavaScript run by Node (wrapped in an async
        # function), never shelled — route it to a separate bucket so it's
        # checked with `node --check`, not `dash -n` (see flavour 3 above).
        uses = step.get("uses", "")
        uses_action = uses.split("@")[0] if uses else ""
        is_github_script = uses_action == "actions/github-script"
        with_block = step.get("with") or {}
        script = with_block.get("script") if isinstance(with_block, dict) else None
        if isinstance(script, str) and script.strip():
            name = step.get("name") or f"step{idx}"
            uses_slug = slug(uses_action or "uses")
            if is_github_script:
                fname = f"{wf_slug}__{slug(job_name)}__{idx:02d}__{uses_slug}__{slug(name)}.js"
                with open(os.path.join(out_dir, "github-script", fname), "w") as f:
                    f.write(script)
                count_gh_script += 1
            else:
                fname = f"{wf_slug}__{slug(job_name)}__{idx:02d}__{uses_slug}__{slug(name)}.sh"
                with open(os.path.join(out_dir, "with-script", fname), "w") as f:
                    f.write(script)
                count_script += 1

print(f"check-workflow-scripts: extracted {count_run} run blocks + {count_script} with.script blocks + {count_gh_script} actions/github-script blocks from {workflows_dir}")
PY

# ── Pass 1: `with.script:` blocks — MUST parse under dash (CI runs them
# line-by-line via `sh -c`, so any bashism is a hard failure). ──────────────
echo ""
echo "── Validating with.script: blocks under dash ──"
SCRIPT_COUNT=0
for f in "$TMPDIR_SCRIPTS/with-script"/*.sh; do
    [ -f "$f" ] || continue
    SCRIPT_COUNT=$((SCRIPT_COUNT + 1))
    rel="$(basename "$f")"

    if ! dash -n "$f" 2> "$f.dash.err"; then
        echo "::error::dash syntax error in $rel"
        sed 's/^/    /' "$f.dash.err"
        EXIT=1
        continue
    fi

    if [ "$HAVE_SHELLCHECK" -eq 1 ]; then
        # SC1091 (source not followable), SC2148 (no shebang — every block
        # is a fragment), SC2154 (unset $VAR — workflow env injects them),
        # SC2296 (${{ ... }} GitHub Actions template — not a shell expansion).
        if ! shellcheck --shell=sh --severity=error --exclude=SC1091,SC2148,SC2154,SC2296 "$f" > "$f.sc.err" 2>&1; then
            echo "::error::shellcheck error (sh) in $rel"
            sed 's/^/    /' "$f.sc.err"
            EXIT=1
        fi
    fi

    # Per-line slicing simulation. The ReactiveCircus runner exec's each
    # logical line via `sh -c`, so trailing-backslash continuations are
    # FATAL — the second physical line ships as its own command and the
    # first one keeps a literal `\` in argv. `dash -n` above passes them
    # because the whole-file parser glues continuations together first.
    # awk handles inline `\` followed by EOL, ignoring quoted/escaped
    # forms with a previous `\\` (rare in our corpus, but conservative).
    if awk '
        /\\[[:space:]]*$/ {
            # A pure trailing `\` at EOL. Skip lines that are clearly
            # comments (`#…\`) — those degrade to noise but not failures.
            line = $0
            sub(/^[[:space:]]+/, "", line)
            if (substr(line, 1, 1) == "#") next
            print NR ": " $0
            found = 1
        }
        END { exit found ? 1 : 0 }
    ' "$f" > "$f.bslash.err"; then
        : # no continuations found, OK
    else
        echo "::error::trailing-backslash continuation in $rel (line-by-line sh -c will mangle it)"
        sed 's/^/    /' "$f.bslash.err"
        echo "    Fix: collapse the multi-line invocation onto a single physical line,"
        echo "    or split the action invocation across separate steps. See #1153."
        EXIT=1
    fi
done
echo "  checked $SCRIPT_COUNT with.script: block(s)"

# ── Pass 1b: `with.script:` blocks for actions/github-script — JavaScript
# run by Node, wrapped internally in an async function; NOT shell, so `dash
# -n` is a guaranteed false positive here. Wrap in an async IIFE (mirroring
# what actions/github-script does internally) so top-level `await` parses,
# then `node --check` for a real syntax check. ───────────────────────────────
echo ""
echo "── Validating actions/github-script with.script: blocks under node ──"
GH_SCRIPT_COUNT=0
if command -v node >/dev/null 2>&1; then
    for f in "$TMPDIR_SCRIPTS/github-script"/*.js; do
        [ -f "$f" ] || continue
        GH_SCRIPT_COUNT=$((GH_SCRIPT_COUNT + 1))
        rel="$(basename "$f")"
        wrapped="$f.wrapped.js"
        {
            echo "async function __check() {"
            cat "$f"
            echo "}"
        } > "$wrapped"
        if ! node --check "$wrapped" 2> "$f.node.err"; then
            echo "::error::node syntax error in $rel"
            sed 's/^/    /' "$f.node.err"
            EXIT=1
        fi
    done
    echo "  checked $GH_SCRIPT_COUNT actions/github-script block(s)"
else
    echo "::warning::node not installed — actions/github-script with.script: blocks not syntax-checked."
fi

# ── Pass 2: `run:` blocks — bash on Linux/macOS runners, so we only surface
# shellcheck warnings (informational, never fails the gate). The existing
# corpus has legitimate `array=()` and `${var//foo/bar}` patterns that work
# fine under bash. ───────────────────────────────────────────────────────────
if [ "$HAVE_SHELLCHECK" -eq 1 ]; then
    echo ""
    echo "── Informational shellcheck on run: blocks (bash) ──"
    RUN_COUNT=0
    WARN_COUNT=0
    for f in "$TMPDIR_SCRIPTS/run"/*.sh; do
        [ -f "$f" ] || continue
        RUN_COUNT=$((RUN_COUNT + 1))
        # `--shell=bash`, severity=error so we don't drown contributors in
        # SC2086 (quote variables) warnings on every existing block.
        if ! shellcheck --shell=bash --severity=error --exclude=SC1091,SC2148,SC2154,SC2296 "$f" > "$f.sc.err" 2>&1; then
            WARN_COUNT=$((WARN_COUNT + 1))
            echo "::warning::shellcheck (bash) in $(basename "$f")"
            sed 's/^/    /' "$f.sc.err"
        fi
    done
    echo "  checked $RUN_COUNT run: block(s), $WARN_COUNT with warnings"
fi

# ── Pass 3: `if:` expressions — MUST NOT reference contexts disallowed in an
# `if:`. GitHub Actions rejects the WHOLE workflow file at parse time if a
# step/job `if:` references the `secrets` context ("Unrecognized named-value:
# 'secrets'"), and the failure surfaces only as a run-time startup-failure —
# exactly the v4.13.0 release startup-failure that shipped to main. `secrets`
# is the only context that is categorically forbidden in `if:`; it must be
# read into `env:`/`with:` and the resulting variable tested instead. ────────
echo ""
echo "── Validating if: expressions for disallowed contexts ──"
if ! python3 - "$WORKFLOWS_DIR" <<'PY'
import glob
import os
import sys

try:
    import yaml
except ImportError:
    print("::error::PyYAML not installed. Run 'pip install pyyaml'.")
    sys.exit(2)

workflows_dir = sys.argv[1]

# Contexts that are NOT available in an `if:` expression. `secrets` is the
# one that hard-fails the whole workflow at parse time; keep the set tight
# and explicit so we never false-positive on a legitimate context.
FORBIDDEN = ("secrets",)

failures = 0
checked = 0


def check_if(wf, where, expr):
    """Flag a forbidden context referenced as `<ctx>.something` in an `if:`."""
    global failures
    if not isinstance(expr, str):
        return
    for ctx in FORBIDDEN:
        # Match `secrets.` and `secrets[` token-boundaried so we don't trip
        # on an unrelated identifier that merely ends in 'secrets'.
        for needle in (ctx + ".", ctx + "["):
            idx = 0
            while True:
                pos = expr.find(needle, idx)
                if pos == -1:
                    break
                before = expr[pos - 1] if pos > 0 else ""
                if not (before.isalnum() or before in ("_", "-", ".")):
                    print(f"::error file={wf}::{where}: if: expression "
                          f"references the '{ctx}' context, which is NOT "
                          f"allowed in an if: — GitHub rejects the whole "
                          f"workflow at parse time. Read it into env:/with: "
                          f"and test the resulting variable instead.")
                    failures += 1
                    return
                idx = pos + len(needle)


for wf in sorted(glob.glob(os.path.join(workflows_dir, "*.yml"))):
    try:
        with open(wf) as f:
            doc = yaml.safe_load(f) or {}
    except yaml.YAMLError as e:
        print(f"::error file={wf}::YAML parse failed: {e}")
        failures += 1
        continue

    for job_name, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        if "if" in job:
            checked += 1
            check_if(wf, f"job '{job_name}'", job["if"])
        for idx, step in enumerate(job.get("steps", []) or []):
            if isinstance(step, dict) and "if" in step:
                checked += 1
                name = step.get("name") or f"step{idx}"
                check_if(wf, f"job '{job_name}' step '{name}'", step["if"])

print(f"check-workflow-scripts: checked {checked} if: expression(s)")
sys.exit(1 if failures else 0)
PY
then
    EXIT=1
fi

if [ $EXIT -ne 0 ]; then
    echo ""
    echo "::error::Workflow validation failed — see the error(s) above."
    echo "         Possible causes:"
    echo "         - a with.script: block fails to parse under dash"
    echo "           (ReactiveCircus/android-emulator-runner@v2 and similar"
    echo "           actions exec each line via 'sh -c' = dash on Linux"
    echo "           runners; replace [[ ]] with [ ], arrays with"
    echo "           newline-separated strings, etc.);"
    echo "         - an actions/github-script with.script: block has a"
    echo "           JavaScript syntax error (checked via 'node --check');"
    echo "         - an if: expression references a context disallowed in"
    echo "           if: (e.g. 'secrets') — read it into env:/with: instead."
    exit 1
fi

echo ""
echo "check-workflow-scripts: OK"
