#!/usr/bin/env bash
# Mutation suite for check-release-publish-verification.py.
#
# The gate's whole subject is an ABSENCE — nothing in the pub.dev job ever
# exchanged the Actions id-token, and three releases published nothing while
# the job looked correct to every reader (#3011). A gate for an absence is
# trivially satisfiable by a regex that matches nothing, so this suite never
# asserts "rc != 0": every case reintroduces ONE real defect into a copy of
# the live release.yml and requires the gate to name it, in the exact words
# a maintainer would have to act on.
#
# Fixtures are derived from the real .github/workflows/release.yml rather than
# hand-written, for two reasons: a synthetic 5-job workflow would drift, and a
# mutation that stops applying (because the file was refactored) is itself a
# failure here — `mutate` refuses a no-op sed instead of silently passing.
#
# Usage: bash .claude/scripts/test-check-release-publish-verification.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="$ROOT/.claude/scripts/check-release-publish-verification.py"
REAL="$ROOT/.github/workflows/release.yml"
GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; OFF=$'\033[0m'

pass=0; fail=0
TMPROOT="$(mktemp -d)"
trap 'rm -rf "$TMPROOT"' EXIT

for f in "$GATE" "$REAL"; do
    if [ ! -f "$f" ]; then
        echo "${RED}✗ $f is missing — nothing to test${OFF}"
        exit 1
    fi
done

OUT=""; RC=0
run() {
    OUT="$(python3 "$GATE" "$1" 2>&1)"
    RC=$?
}

# expect <label> <want-rc> <want-substring>
expect() {
    local label="$1" want_rc="$2" want_sub="$3"
    local ok=1
    [ "$RC" = "$want_rc" ] || ok=0
    printf '%s' "$OUT" | grep -qF -- "$want_sub" || ok=0
    if [ "$ok" = 1 ]; then
        printf '%s  ✓%s %s\n' "$GREEN" "$OFF" "$label"
        pass=$((pass + 1))
    else
        printf '%s  ✗%s %s\n' "$RED" "$OFF" "$label"
        printf '      expected rc=%s and a line containing: %s\n' "$want_rc" "$want_sub"
        printf '      got rc=%s:\n' "$RC"
        printf '%s\n' "$OUT" | sed 's/^/        /'
        fail=$((fail + 1))
    fi
}

# mutate <name> <sed-expr>  — prints the fixture path, or fails the suite if
# the expression matched nothing. A mutation that changes no bytes proves
# nothing about the gate; it means this suite has gone stale.
mutate() {
    local name="$1" expr="$2"
    local out="$TMPROOT/$name.yml"
    sed "$expr" "$REAL" > "$out"
    if cmp -s "$out" "$REAL"; then
        printf '%s  ✗%s mutation "%s" changed nothing — release.yml no longer matches this sed\n' \
            "$RED" "$OFF" "$name" >&2
        fail=$((fail + 1))
        return 1
    fi
    printf '%s' "$out"
}

echo "── 0. The live workflow ──────────────────────────────────────────────"
run "$REAL"
expect "the real release.yml satisfies every contract" 0 \
    "OK: every publisher verifies against its registry"

echo
echo "── 1. #3011 — the OIDC exchange nobody was doing ─────────────────────"

# The exact defect that shipped v4.27.0/v4.28.0/v4.29.0: id-token: write is
# declared, the audience is right, the comment says OIDC — and no step spends
# the token. Deleting the one line that does it must be caught.
if F="$(mutate no-token-add '/pub token add https:\/\/pub.dev/d')"; then
    run "$F"
    expect "deleting \`pub token add\` is caught" 1 \
        "pub-publish never exchanges the Actions id-token for a pub.dev credential"
fi

# The mirror: keeping the pub token call but losing the id-token endpoint —
# what a `container:` or a nested `env -i` does silently.
if F="$(mutate no-oidc-env 's/ACTIONS_ID_TOKEN_REQUEST_URL/SOME_OTHER_URL/g')"; then
    run "$F"
    expect "losing the id-token endpoint is caught" 1 \
        "pub-publish never exchanges the Actions id-token for a pub.dev credential"
fi

if F="$(mutate no-banner-guard 's/Waiting for your authorization/Waiting for something else/g')"; then
    run "$F"
    expect "dropping the interactive-OAuth banner guard is caught" 1 \
        "does not treat the interactive-OAuth banner"
fi

if F="$(mutate slack-timeout 's/timeout 300 flutter pub publish/timeout 900 flutter pub publish/')"; then
    run "$F"
    expect "a step timeout no longer under the job timeout is caught" 1 \
        "publish timeout (900s) is not well under the job timeout"
fi

if F="$(mutate no-timeout 's/timeout 300 flutter pub publish/flutter pub publish/')"; then
    run "$F"
    expect "an unbounded publish command is caught" 1 \
        "publish command is not wrapped in \`timeout <s>\`"
fi

# Observed at v4.29.0: `├── publish.log (<1 KB)` in the archive listing pub
# was about to upload, because tee wrote into working-directory.
if F="$(mutate tee-into-package 's|tee "$PUB_LOG"|tee publish.log|')"; then
    run "$F"
    expect "tee'ing the publish log into the package is caught" 1 \
        "tees the publish log to a RELATIVE path"
fi

# The lying error message. flutter_sceneview has been on pub.dev since 4.24.0.
if F="$(mutate false-claim 's|this is NOT a first-publish problem|the first version must be published manually|')"; then
    run "$F"
    expect "the 'publish the first version by hand' lie is caught" 1 \
        "published manually — pub.dev has served it since 4.24.0"
fi

echo
echo "── 2. #3021 — registry verification, all five publishers ─────────────"

# These two mutations move or delete whole STEPS, which a line-oriented sed
# cannot do without cutting a multi-line `run:` block in half — the first
# attempt produced unparseable YAML, and "the gate refused a broken fixture"
# would have said nothing about the contract under test. Edit the parsed
# document instead.
EDITOR_PY="$TMPROOT/edit-steps.py"
cat > "$EDITOR_PY" <<'PY'
"""usage: edit-steps.py <src> <dst> <job> <drop|hoist>

drop  — remove the step that calls the verifier
hoist — move it ABOVE the job's publish step
Exits 3 if the job does not have the shape the mutation assumes, so a stale
fixture fails the suite instead of quietly not mutating anything.
"""
import sys
import yaml

src, dst, job, mode = sys.argv[1:5]
doc = yaml.safe_load(open(src, encoding="utf-8"))
try:
    steps = doc["jobs"][job]["steps"]
except (KeyError, TypeError):
    sys.exit(3)


def find(needle):
    hits = [i for i, s in enumerate(steps)
            if needle in str((s or {}).get("run", ""))]
    return hits


verify = find("verify-published-version.sh")
if not verify:
    sys.exit(3)

if mode == "drop":
    for i in reversed(verify):
        steps.pop(i)
elif mode == "hoist":
    publish = [i for i in range(len(steps))
               if any(p in str((steps[i] or {}).get("run", ""))
                      for p in ("npm publish", "publishAndReleaseToMavenCentral",
                                "pub publish --force"))]
    if not publish or max(verify) < min(publish):
        sys.exit(3)
    steps.insert(min(publish), steps.pop(max(verify)))
else:
    sys.exit(3)

yaml.safe_dump(doc, open(dst, "w", encoding="utf-8"), sort_keys=False, width=10000)
PY

edit_steps() {
    # Three separate `local`s on purpose: bash expands ALL the words of a
    # `local` command before performing any of its assignments, so
    # `local a="$1" b="a-$a"` reads an unset `a`. Collapsed onto one line this
    # aborted every case in section 2 under `set -u` — and the suite still
    # printed a green 12/12, which is why the floor at the bottom exists.
    local job="$1"
    local mode="$2"
    local out="$TMPROOT/$mode-$job.yml"
    if ! python3 "$EDITOR_PY" "$REAL" "$out" "$job" "$mode" 2>/dev/null; then
        printf '%s  ✗%s could not build the "%s" fixture for %s — the job no longer has the expected shape\n' \
            "$RED" "$OFF" "$mode" "$job" >&2
        fail=$((fail + 1))
        return 1
    fi
    printf '%s' "$out"
}

for job in publish-library publish-mcp publish-web publish-rn pub-publish; do
    case "$job" in
        publish-library) want='publish-library (Maven Central) never calls' ;;
        publish-mcp)     want='publish-mcp (npm (sceneview-mcp)) never calls' ;;
        publish-web)     want='publish-web (npm (sceneview-web)) never calls' ;;
        publish-rn)      want='publish-rn (npm (@sceneview-sdk/react-native)) never calls' ;;
        pub-publish)     want='pub-publish (pub.dev (flutter_sceneview)) never calls' ;;
    esac
    if F="$(edit_steps "$job" drop)"; then
        run "$F"
        expect "$job losing its post-publish verification is caught" 1 "$want"
    fi
done

# Order matters: verifying BEFORE publishing inspects the PREVIOUS release's
# state and says nothing about this one.
if F="$(edit_steps publish-rn hoist)"; then
    run "$F"
    expect "verifying before publishing is caught" 1 "BEFORE it publishes"
fi

echo
echo "── 3. Cannot-measure is never a pass ─────────────────────────────────"

# "Found nothing" must be its own outcome. A renamed publisher that quietly
# drops out of the checked set is the failure mode this repo has paid for
# more than once.
if F="$(mutate renamed-job 's/^  publish-rn:/  publish-reactnative:/')"; then
    run "$F"
    expect "a renamed publisher job is a measurement failure, not a pass" 2 \
        "MEASUREMENT FAILED: publisher job(s) not found"
fi

printf 'jobs: {}\n' > "$TMPROOT/empty.yml"
run "$TMPROOT/empty.yml"
expect "a workflow with no jobs is a measurement failure" 2 "MEASUREMENT FAILED"

printf 'jobs:\n  - [unbalanced\n' > "$TMPROOT/broken.yml"
run "$TMPROOT/broken.yml"
expect "unparseable YAML is a measurement failure" 2 "MEASUREMENT FAILED"

run "$TMPROOT/does-not-exist.yml"
expect "a missing workflow is a measurement failure" 2 "MEASUREMENT FAILED"

echo
# A count floor, because "every check passed" is not the same statement as
# "every check ran". A `set -u` abort inside a helper skipped all six of
# section 2 while this summary still printed a green 12/12 — the exact false
# green the gate under test exists to prevent, reproduced in its own suite.
EXPECTED_CHECKS=18
TOTAL=$((pass + fail))
if [ "$TOTAL" -ne "$EXPECTED_CHECKS" ]; then
    printf '%s✗ check-release-publish-verification.py: %d checks ran, expected %d — cases were skipped, not passed%s\n' \
        "$RED" "$TOTAL" "$EXPECTED_CHECKS" "$OFF"
    exit 1
fi
if [ "$fail" -eq 0 ]; then
    printf '%s✓ check-release-publish-verification.py: %d/%d checks pass%s\n' \
        "$GREEN" "$pass" "$TOTAL" "$OFF"
    exit 0
fi
printf '%s✗ check-release-publish-verification.py: %d of %d checks failed%s\n' \
    "$RED" "$fail" "$TOTAL" "$OFF"
exit 1
