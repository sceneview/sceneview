#!/usr/bin/env bash
# Behaviour suite for verify-published-version.sh.
#
# This script is the thing that decides whether a release actually shipped, so
# a suite that only asserted exit codes would be the hollow shape this repo
# keeps paying for: `rc != 0` is satisfied by a typo as easily as by the check.
# Every case below asserts the EXACT verdict line, and section 2 proves the
# suite can tell the difference — it mutates the verifier and requires the
# named case to stop producing its verdict.
#
# No network: `npm` and `curl` are stubbed on PATH, so each branch of the real
# script is driven directly rather than hoped for.
#
# Usage: bash .claude/scripts/test-verify-published-version.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/.claude/scripts/verify-published-version.sh"
GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; OFF=$'\033[0m'

pass=0; fail=0
TMPROOT="$(mktemp -d)"
trap 'rm -rf "$TMPROOT"' EXIT

if [ ! -f "$SCRIPT" ]; then
    echo "${RED}✗ $SCRIPT is missing — nothing to test${OFF}"
    exit 1
fi

# ── Stubs ─────────────────────────────────────────────────────────────────
# Both read their behaviour from the environment the case sets, so the same
# two files serve every branch.
BIN="$TMPROOT/bin"
mkdir -p "$BIN"

cat > "$BIN/npm" <<'STUB'
#!/usr/bin/env bash
# argv is: view <pkg>@<ver> version
case "${STUB_NPM:-missing}" in
    found)       printf '%s\n' "${STUB_NPM_VERSION:-0.0.0}"; exit 0 ;;
    missing)     echo "npm ERR! code E404" >&2; exit 1 ;;
    unreachable) echo "npm ERR! network request to registry.npmjs.org failed" >&2; exit 1 ;;
esac
exit 1
STUB

cat > "$BIN/curl" <<'STUB'
#!/usr/bin/env bash
# Two shapes are used by the verifier: a HEAD probe with -sI (Maven) and a
# body fetch with -sf (pub.dev). Route on the flag, never on the URL, so a
# changed URL cannot silently take the wrong branch.
for arg in "$@"; do
    if [ "$arg" = "-sI" ]; then
        printf '%s' "${STUB_MAVEN_STATUS:-000}"
        exit 0
    fi
done
case "${STUB_PUB:-missing}" in
    found)       printf '%s' '{"versions":[{"version":"4.24.0"},{"version":"4.29.0"}]}' ;;
    backport)    printf '%s' '{"latest":{"version":"4.30.0"},"versions":[{"version":"4.29.0"},{"version":"4.30.0"}]}' ;;
    missing)     printf '%s' '{"latest":{"version":"4.24.0"},"versions":[{"version":"4.24.0"}]}' ;;
    garbage)     printf '%s' '<html>503 Service Unavailable</html>' ;;
    unreachable) exit 22 ;;
esac
exit 0
STUB

chmod +x "$BIN/npm" "$BIN/curl"

# run <script> <args...>  — env for the case comes from the caller's exports.
OUT=""
RC=0
run() {
    local script="$1"; shift
    OUT="$(PATH="$BIN:$PATH" PUBLISH_VERIFY_ATTEMPTS=2 PUBLISH_VERIFY_DELAY=0 \
        bash "$script" "$@" 2>&1)"
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

# refute <label> <want-rc> <forbidden-substring>
refute() {
    local label="$1" want_rc="$2" bad_sub="$3"
    local ok=1
    [ "$RC" = "$want_rc" ] || ok=0
    if printf '%s' "$OUT" | grep -qF -- "$bad_sub"; then ok=0; fi
    if [ "$ok" = 1 ]; then
        printf '%s  ✓%s %s\n' "$GREEN" "$OFF" "$label"
        pass=$((pass + 1))
    else
        printf '%s  ✗%s %s\n' "$RED" "$OFF" "$label"
        printf '      expected rc=%s and NO line containing: %s\n' "$want_rc" "$bad_sub"
        printf '      got rc=%s:\n' "$RC"
        printf '%s\n' "$OUT" | sed 's/^/        /'
        fail=$((fail + 1))
    fi
}

echo "── 1. npm ────────────────────────────────────────────────────────────"

STUB_NPM=found STUB_NPM_VERSION=4.29.0 run "$SCRIPT" npm sceneview-web 4.29.0
expect "npm serving the version verifies" 0 "VERIFIED: npm serves sceneview-web@4.29.0"

STUB_NPM=missing run "$SCRIPT" npm sceneview-web 4.29.0
expect "npm answering E404 is a hard failure" 1 \
    "NOT ON REGISTRY: sceneview-web@4.29.0 is absent after a green publish"

STUB_NPM=unreachable run "$SCRIPT" npm sceneview-web 4.29.0
expect "an unreachable npm is UNREACHABLE, not 'not published'" 1 \
    "UNREACHABLE: no probe reached the registry for sceneview-web@4.29.0"

STUB_NPM=unreachable run "$SCRIPT" npm sceneview-web 4.29.0
refute "…and never claims the package is absent" 1 "NOT ON REGISTRY"

STUB_NPM=found STUB_NPM_VERSION=4.29.0 run "$SCRIPT" npm "@sceneview-sdk/react-native" 4.29.0
expect "a scoped package name survives intact" 0 \
    "VERIFIED: npm serves @sceneview-sdk/react-native@4.29.0"

echo
echo "── 2. pub.dev ────────────────────────────────────────────────────────"

STUB_PUB=found run "$SCRIPT" pub flutter_sceneview 4.29.0
expect "pub.dev listing the version verifies" 0 \
    "VERIFIED: pub.dev serves flutter_sceneview 4.29.0"

STUB_PUB=missing run "$SCRIPT" pub flutter_sceneview 4.29.0
expect "pub.dev without the version is a hard failure" 1 \
    "NOT ON REGISTRY: flutter_sceneview 4.29.0 is absent after a green publish"

# The false-RED this verifier must not manufacture: after a backport, `latest`
# points ABOVE the version just published. Equality against `latest` would
# red-light a publish that landed (raised in review of PR #3013).
STUB_PUB=backport run "$SCRIPT" pub flutter_sceneview 4.29.0
expect "a backport (latest > published) still verifies" 0 \
    "VERIFIED: pub.dev serves flutter_sceneview 4.29.0"

STUB_PUB=unreachable run "$SCRIPT" pub flutter_sceneview 4.29.0
expect "an unreachable pub.dev is UNREACHABLE" 1 "UNREACHABLE: no probe reached the registry"

STUB_PUB=garbage run "$SCRIPT" pub flutter_sceneview 4.29.0
expect "an unparseable body is not evidence of absence" 1 "UNREACHABLE: no probe reached the registry"

echo
echo "── 3. Maven Central ──────────────────────────────────────────────────"

STUB_MAVEN_STATUS=200 run "$SCRIPT" maven io.github.sceneview:sceneview 4.29.0
expect "a 200 on the POM verifies" 0 \
    "VERIFIED: Maven Central serves io.github.sceneview:sceneview 4.29.0"

# #3021: Central 404'd on the POM for ~30 min after a green publish at
# v4.26.0. Failing here would invent a red release out of an OSSRH sync lag.
STUB_MAVEN_STATUS=404 run "$SCRIPT" maven io.github.sceneview:sceneview 4.29.0
expect "an exhausted propagation budget is INCONCLUSIVE, not a failure" 0 \
    "MAVEN VERIFICATION INCONCLUSIVE"

STUB_MAVEN_STATUS=404 run "$SCRIPT" maven io.github.sceneview:sceneview 4.29.0
refute "…and INCONCLUSIVE never reads as verified" 0 "VERIFIED:"

STUB_MAVEN_STATUS=000 run "$SCRIPT" maven io.github.sceneview:sceneview 4.29.0
expect "a Central we never reached is UNREACHABLE, not INCONCLUSIVE" 1 \
    "UNREACHABLE: no probe reached the registry"

STUB_MAVEN_STATUS=200 run "$SCRIPT" maven sceneview 4.29.0
expect "a bare artifactId defaults to the io.github.sceneview group" 0 "VERIFIED: Maven Central serves"

echo
echo "── 4. Usage ──────────────────────────────────────────────────────────"

run "$SCRIPT" npm sceneview-web
expect "a missing argument is a usage error, never a pass" 2 "USAGE:"

run "$SCRIPT" cargo sceneview-web 4.29.0
expect "an unknown registry is a usage error, never a pass" 2 "unknown registry 'cargo'"

echo
echo "── 5. Mutation — does this suite actually bite? ──────────────────────"
# Sections 1-4 claim two properties are load-bearing. A claim no test can
# falsify is prose, so each is deleted from a COPY of the verifier and the
# case that names it must stop producing its verdict. If a mutant still
# passes, the assertion above was decorative.

mutant() {
    local name="$1" sed_expr="$2"
    local m="$TMPROOT/mutant-$name.sh"
    sed "$sed_expr" "$SCRIPT" > "$m"
    if cmp -s "$m" "$SCRIPT"; then
        printf '%s  ✗%s mutation "%s" changed nothing — the sed no longer matches the script\n' \
            "$RED" "$OFF" "$name"
        fail=$((fail + 1))
        return 1
    fi
    printf '%s' "$m"
}

# M1 — set membership degraded to equality against `latest`. This is the exact
# regression the backport case exists to catch.
if M1="$(mutant equality "s|d.get('versions', \[\])|[d.get('latest', {})]|")"; then
    STUB_PUB=backport run "$M1" pub flutter_sceneview 4.29.0
    refute "M1 equality-against-latest breaks the backport case" 1 \
        "VERIFIED: pub.dev serves flutter_sceneview 4.29.0"
fi

# M2 — the reachable/unreachable distinction removed. Without it an outage
# reports "NOT ON REGISTRY", i.e. a confident claim about a registry we never
# reached.
if M2="$(mutant sawregistry 's|if \[ "$saw_registry" -eq 0 \]; then|if false; then|')"; then
    STUB_NPM=unreachable run "$M2" npm sceneview-web 4.29.0
    refute "M2 dropping the reachability guard loses the UNREACHABLE verdict" 1 \
        "UNREACHABLE: no probe reached the registry"
fi

echo
# A count floor: "every check passed" is not "every check ran". A `set -u`
# abort inside a helper can skip a whole section while the summary still
# prints green — that happened in the sibling suite while writing this pair.
EXPECTED_CHECKS=19
TOTAL=$((pass + fail))
if [ "$TOTAL" -ne "$EXPECTED_CHECKS" ]; then
    printf '%s✗ verify-published-version.sh: %d checks ran, expected %d — cases were skipped, not passed%s\n' \
        "$RED" "$TOTAL" "$EXPECTED_CHECKS" "$OFF"
    exit 1
fi
if [ "$fail" -eq 0 ]; then
    printf '%s✓ verify-published-version.sh: %d/%d checks pass%s\n' "$GREEN" "$pass" "$TOTAL" "$OFF"
    exit 0
fi
printf '%s✗ verify-published-version.sh: %d of %d checks failed%s\n' "$RED" "$fail" "$TOTAL" "$OFF"
exit 1
