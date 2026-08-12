#!/usr/bin/env bash
# Post-publish registry verification — ONE pattern for every publisher.
#
# Usage: verify-published-version.sh <registry> <package> <version>
#
#   registry  npm | pub | maven
#   package   npm package name        (e.g. sceneview-web, @sceneview-sdk/react-native)
#             pub.dev package name    (e.g. flutter_sceneview)
#             Maven coordinate        (e.g. io.github.sceneview:sceneview, or just
#                                     `sceneview` — the group defaults to
#                                     io.github.sceneview)
#   version   the version the publish step claims it just shipped
#
# ── Why this exists ────────────────────────────────────────────────────────
# #3011: `flutter pub publish --force` found no credential, fell back to
# INTERACTIVE OAuth, hung until the job timeout and landed as `cancelled`.
# Three releases shipped with the Flutter plugin silently missing. The lesson
# is not "that one CLI is broken" — it is that **a publish step's exit code is
# a claim, and the registry is the fact**. #3013 added that re-verify to the
# pub.dev job only; #3021 is the observation that npm and Maven Central were
# still trusting their exit codes. This script is the shared answer, so the
# three publishers cannot drift into three different notions of "published".
#
# ── The two ways this could itself lie ─────────────────────────────────────
# 1. Reporting "verified" from a probe that never reached the registry. Every
#    probe here has to OBSERVE the version; an unreachable registry is a
#    distinct, loud outcome (`UNREACHABLE:`), never a pass.
# 2. Reporting "not published" from a registry that simply has not caught up.
#    Maven Central served 404 on the POM for ~30 minutes after a green publish
#    while shipping v4.26.0 (#3021). A naive copy of the pub.dev step would
#    manufacture a false RED there — the mirror image of the bug this whole
#    file exists to prevent. Maven therefore gets a propagation budget and,
#    if it is exhausted, an honest INCONCLUSIVE that is neither a pass nor a
#    failure claim. It is spelled out in the log rather than dressed as green.
#
# Exit codes:
#   0  the registry serves the version — or, for Maven only, the propagation
#      budget lapsed and the outcome is explicitly INCONCLUSIVE
#   1  the registry is reachable and does NOT serve the version, or no probe
#      ever reached the registry
#   2  usage error
#
# Env overrides:
#   PUBLISH_VERIFY_ATTEMPTS  probe count
#   PUBLISH_VERIFY_DELAY     seconds between probes
#   PUBLISH_VERIFY_DEADLINE  absolute epoch-seconds cap, SHARED across calls
#
# The deadline exists because Maven's budget is per invocation while the job's
# is not: four artifacts × a budget long enough to outlast Central's ~30 min
# sync is longer than the job's own timeout-minutes, and a verification step
# that burns the job timeout is the #3011 failure shape wearing a new hat.
# Callers compute one deadline for the whole set and pass it to every call.
# It never suppresses the FIRST probe — a call that never asked the registry
# would report UNREACHABLE, turning a spent budget into a false red.
set -uo pipefail

usage() {
    echo "USAGE: verify-published-version.sh <npm|pub|maven> <package> <version>" >&2
    exit 2
}

[ "$#" -eq 3 ] || usage
REGISTRY="$1"
PACKAGE="$2"
VERSION="$3"

[ -n "$PACKAGE" ] || usage
[ -n "$VERSION" ] || usage

# Per-registry defaults. npm and pub.dev are near-immediate; Central is not.
case "$REGISTRY" in
    npm)   DEF_ATTEMPTS=5;  DEF_DELAY=20 ;;
    pub)   DEF_ATTEMPTS=5;  DEF_DELAY=20 ;;
    # 20 × 45s = 15 min, against the ~30 min OSSRH lag measured at v4.26.0.
    # Short of the worst case on purpose: the shared deadline, not this
    # number, is what keeps four artifacts inside one job timeout.
    maven) DEF_ATTEMPTS=20; DEF_DELAY=45 ;;
    *)
        echo "USAGE: unknown registry '$REGISTRY' (expected npm, pub or maven)" >&2
        exit 2
        ;;
esac

ATTEMPTS="${PUBLISH_VERIFY_ATTEMPTS:-$DEF_ATTEMPTS}"
DELAY="${PUBLISH_VERIFY_DELAY:-$DEF_DELAY}"

DEADLINE="${PUBLISH_VERIFY_DEADLINE:-}"
case "$DEADLINE" in
    ''|*[!0-9]*)
        [ -z "$DEADLINE" ] || {
            echo "USAGE: PUBLISH_VERIFY_DEADLINE must be epoch seconds, got '$DEADLINE'" >&2
            exit 2
        }
        ;;
esac

# False when no deadline is set, so the default behaviour is unchanged.
deadline_reached() {
    [ -n "$DEADLINE" ] || return 1
    [ "$(date +%s)" -ge "$DEADLINE" ]
}

# `saw_registry` separates "the registry answered and does not have it" from
# "we never got an answer". Collapsing those two is how a network outage turns
# into a confident, wrong verdict about what is published.
saw_registry=0

# ── Probes ────────────────────────────────────────────────────────────────
# Each prints FOUND / MISSING / UNREACHABLE on stdout and nothing else. They
# shell out to `npm` and `curl` rather than embedding the request, which is
# what lets test-verify-published-version.sh drive every branch below with a
# stubbed binary on PATH instead of a live network.

probe_npm() {
    local out
    # `npm view <pkg>@<ver> version` echoes the version if it resolves. A
    # missing version and an unreachable registry both exit non-zero, so the
    # stderr text is what tells them apart.
    local err_file
    err_file="$(mktemp)"
    out="$(npm view "${PACKAGE}@${VERSION}" version 2>"$err_file")"
    local rc=$?
    local err
    err="$(cat "$err_file")"
    rm -f "$err_file"
    if [ "$rc" -eq 0 ] && [ -n "$out" ]; then
        echo FOUND
        return
    fi
    # E404 is the registry answering "no such version" — a real observation.
    if printf '%s' "$err" | grep -qE 'E404|is not in this registry|No matching version'; then
        echo MISSING
        return
    fi
    if [ "$rc" -ne 0 ]; then
        echo UNREACHABLE
        return
    fi
    echo MISSING
}

probe_pub() {
    local body
    body="$(curl -sf --max-time 30 "https://pub.dev/api/packages/${PACKAGE}")"
    if [ $? -ne 0 ] || [ -z "$body" ]; then
        echo UNREACHABLE
        return
    fi
    # Set membership in ['versions'], NOT equality against ['latest'].
    # They agree for forward-only releases, but a backport would leave
    # `latest` on a higher version and an equality test would then red-light
    # a publish that actually succeeded (raised in review of PR #3013).
    local found
    found="$(printf '%s' "$body" | PKG_VERSION="$VERSION" python3 -c \
        "import json,os,sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(3)
want = os.environ['PKG_VERSION']
print('FOUND' if any(v.get('version') == want for v in d.get('versions', [])) else 'MISSING')")"
    if [ -z "$found" ]; then
        # Unparseable body — the endpoint answered with something that is not
        # the package document. That is not evidence of absence.
        echo UNREACHABLE
        return
    fi
    echo "$found"
}

probe_maven() {
    local group artifact
    case "$PACKAGE" in
        *:*) group="${PACKAGE%%:*}"; artifact="${PACKAGE##*:}" ;;
        *)   group="io.github.sceneview"; artifact="$PACKAGE" ;;
    esac
    local group_path="${group//./\/}"
    local url="https://repo1.maven.org/maven2/${group_path}/${artifact}/${VERSION}/${artifact}-${VERSION}.pom"
    local status
    status="$(curl -sI -o /dev/null --max-time 30 -w '%{http_code}' "$url")"
    case "$status" in
        200) echo FOUND ;;
        # 000 is curl's "no HTTP response at all".
        ""|000) echo UNREACHABLE ;;
        404|410) echo MISSING ;;
        # Any other status is Central answering something we did not ask about
        # (5xx, a proxy page). Not evidence of absence.
        *) echo UNREACHABLE ;;
    esac
}

label() {
    case "$REGISTRY" in
        npm)   echo "npm serves ${PACKAGE}@${VERSION}" ;;
        pub)   echo "pub.dev serves ${PACKAGE} ${VERSION}" ;;
        maven) echo "Maven Central serves ${PACKAGE} ${VERSION}" ;;
    esac
}

subject() {
    case "$REGISTRY" in
        npm)   echo "${PACKAGE}@${VERSION}" ;;
        pub)   echo "${PACKAGE} ${VERSION}" ;;
        maven) echo "${PACKAGE} ${VERSION}" ;;
    esac
}

attempt=1
while [ "$attempt" -le "$ATTEMPTS" ]; do
    case "$REGISTRY" in
        npm)   result="$(probe_npm)" ;;
        pub)   result="$(probe_pub)" ;;
        maven) result="$(probe_maven)" ;;
    esac

    case "$result" in
        FOUND)
            echo "VERIFIED: $(label)"
            exit 0
            ;;
        MISSING)
            saw_registry=1
            echo "not yet: $(subject) is not on the registry (attempt ${attempt}/${ATTEMPTS})"
            ;;
        *)
            echo "no answer from the registry for $(subject) (attempt ${attempt}/${ATTEMPTS})"
            ;;
    esac

    # Checked AFTER the probe above, never before: at least one question is
    # always put to the registry, whatever the clock says.
    if deadline_reached; then
        echo "shared propagation budget spent — no further probes for $(subject)"
        break
    fi

    # Do not announce — or wait out — a retry that will not happen.
    if [ "$attempt" -lt "$ATTEMPTS" ] && [ "$DELAY" -gt 0 ]; then
        sleep "$DELAY"
    fi
    attempt=$((attempt + 1))
done

if [ "$saw_registry" -eq 0 ]; then
    echo "::error::UNREACHABLE: no probe reached the registry for $(subject) after ${ATTEMPTS} attempts — this is NOT evidence that the publish failed, and NOT evidence that it worked."
    exit 1
fi

if [ "$REGISTRY" = "maven" ]; then
    # Honest INCONCLUSIVE, per #3021. Central's OSSRH sync ran ~30 min behind
    # a green publish at v4.26.0; failing here would manufacture a red release
    # out of a publish that landed. Saying "verified" would be worse.
    echo "::warning::INCONCLUSIVE: Maven Central has not served $(subject) within the propagation budget (${ATTEMPTS} attempts × ${DELAY}s). OSSRH sync ran ~30 min behind a green publish at v4.26.0 (#3021), so this is expected soon after a release."
    echo "MAVEN VERIFICATION INCONCLUSIVE — this is not a proof of publication (#3021). Confirm at https://repo1.maven.org/maven2/ before announcing the release."
    exit 0
fi

echo "::error::NOT ON REGISTRY: $(subject) is absent after a green publish — the step passed and nothing landed (#3011/#3021)."
exit 1
