#!/usr/bin/env bash
# check-vendored-download-safety.sh — Refuse to BUILD a vendored tree whose
# build-logic downloads unverified archives and extracts them unsafely.
#
# ─── Why this gate exists at all ────────────────────────────────────────────
#
# `third_party/filament-kmp/build-logic/` fetches Filament release tarballs and
# a `jextract` early-access build over the network and unpacks them into the
# working tree. Two defects were found in it during the #3009 review:
#
#   1. NO INTEGRITY CHECK. `FilamentDownloads.downloadToCache()` streams a URL
#      straight to the cache and returns it. The *version* is pinned; the
#      *bytes* are not. A GitHub release asset can be deleted and re-uploaded
#      under the same tag, and download.java.net EA builds are explicitly
#      transient — so "same URL" does not mean "same file", and nothing here
#      would notice.
#
#   2. SYMLINK TAR-SLIP. `FilamentDownloads.extractAll()` checks that each
#      entry's own path stays inside the destination, which stops the classic
#      `../../etc/passwd` entry. It does NOT check `entry.linkName` before
#      `Files.createSymbolicLink`. A tarball carrying `a -> /tmp/evil`
#      followed by a regular entry `a/x` passes the path check (normalize()
#      does not resolve symlinks) and writes through the link, outside the
#      destination. The extracted tree is then marked executable and run.
#
# Both are build-time, and today they are unreachable twice over: the tree was
# removed from main in #3015, and even while it was present nothing built it
# (`settings.gradle` never had an include). That is precisely why this is a gate
# and not a patch — patching a copy that was about to be deleted would have
# protected nothing. The defects become live the moment the desktop spike
# (#2540) restores the binding AND wires it in, and that is the moment a
# reviewer is thinking about CMake and jextract, not about tar entries.
#
# So: while nothing builds it, this passes and says nothing. The instant
# something does, it fails and names both fixes. The remediation is spelled out
# in docs/docs/desktop-filament.md § Re-vendoring the binding (item 4).
#
# ─── What it checks ─────────────────────────────────────────────────────────
#
#   * Vendored tree absent          -> PASS (nothing to protect)
#   * Present but not built         -> PASS, one informational line
#   * Present AND built             -> the two hardening requirements are
#                                      MANDATORY; missing either is a failure
#
# Exit codes: 0 = pass, 1 = the tree is built and unhardened.

set -euo pipefail

# VENDORED_SAFETY_ROOT lets the self-test point the gate at a synthetic tree.
# Without it the probes could only ever be exercised by mutating the real
# vendored files, which is a destructive, hand-driven ritual nobody repeats —
# so a loosened probe would go unnoticed. See test-check-vendored-download-safety.sh.
REPO_ROOT="${VENDORED_SAFETY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO_ROOT"

VENDORED="third_party/filament-kmp"
DOWNLOADS="$VENDORED/build-logic/src/main/kotlin/FilamentDownloads.kt"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}PASS${NC}  $1"; }
info() { echo -e "${YELLOW}INFO${NC}  $1"; }
fail() { echo -e "${RED}FAIL${NC}  $1"; }

echo "[check-vendored-download-safety]"

if [[ ! -d "$VENDORED" ]]; then
    pass "$VENDORED is absent — nothing vendored, nothing to protect."
    echo
    echo "If you are RE-VENDORING it, this gate is what you have to satisfy before"
    echo "the settings.gradle include lands. See docs/docs/desktop-filament.md."
    exit 0
fi

# ─── Is anything actually building it? ──────────────────────────────────────
# Two independent ways in: a Gradle include (composite or subproject) naming the
# path, or a CI step invoking Gradle inside it. Either one makes the download
# code reachable.
wired=0
wire_reason=""

# Strip whole-line comments, then match the path ANYWHERE on what is left. Say
# what is meant rather than encoding "not a comment" into the match itself: two
# earlier attempts both left the gate green on a genuinely wired tree.
#   1. `include("<path>")` alone missed the relocated-subproject form
#      (`project(":x").projectDir = file("third_party/filament-kmp")`).
#   2. `^[^/#]*third_party/filament-kmp` was meant to fix that by matching the
#      path alone, and its comment claimed it "cannot be outrun by a syntax this
#      script did not predict" — but `[^/#]*` forbids a slash BEFORE the path, so
#      the two most idiomatic spellings slipped through, both measured green on a
#      vulnerable tree: `file("$rootDir/third_party/filament-kmp")` and
#      `includeBuild("./third_party/filament-kmp")`.
# Read the assertion, never its comment: that regex never matched "the path
# alone", and only running it said so.
strip_comments() { grep -vE '^[[:space:]]*(//|#|\*|/\*)' "$@" 2>/dev/null || true; }

if strip_comments settings.gradle settings.gradle.kts | grep -Fq "third_party/filament-kmp"; then
    wired=1
    wire_reason="a settings file references $VENDORED"
fi

# Fail-closed on CI too: ANY workflow naming the path arms the gate. Requiring
# `gradlew` on the SAME line missed the ordinary spelling, where the directory is
# a `working-directory:` and the command sits on the next line. A workflow that
# mentions the vendored tree at all is signal enough — a needless arming costs a
# comment, a missed one ships an unverified download chain.
if grep -rlFq "third_party/filament-kmp" .github/workflows/ 2>/dev/null; then
    wired=1
    wire_reason="${wire_reason:+$wire_reason; }a CI workflow references $VENDORED"
fi

if [[ "$wired" -eq 0 ]]; then
    info "$VENDORED is present but nothing builds it — its download code is unreachable."
    info "This gate turns MANDATORY the moment a settings.gradle include lands (#2540)."
    exit 0
fi

echo "  wired: $wire_reason"
echo "  -> the two hardening requirements below are now mandatory."
echo

if [[ ! -f "$DOWNLOADS" ]]; then
    fail "$DOWNLOADS is missing, but something builds $VENDORED."
    echo "      Either the download code moved (update this gate) or the tree is broken."
    exit 1
fi

failures=0

# ─── 1. Downloads are verified against a pinned digest ──────────────────────
# Both probes below read CODE ONLY — comments are stripped first — and both
# require an ASSERTION, not a mention. The previous version claimed in this very
# comment that "a file that merely mentions sha256 in a comment scores zero
# here". It did not: `grep -Ec 'sha256\('` counts comment lines like any other,
# and a tree with an unguarded `createSymbolicLink` plus these three lines
#     // TODO: call sha256( on the result some day
#     // pinned "aaaa…" (64 hex)
#     // linkName: we should normalize this before createSymbolicLink
# was measured PASS/PASS, rc=0, "vendored download chain is hardened".
# A probe that a TODO satisfies is worse than no probe, because it also silences
# the reviewer. Hence: strip comments, then demand the comparison itself.
CODE="$(strip_comments "$DOWNLOADS")"

digest_api=$(printf '%s\n' "$CODE" | grep -Ec 'MessageDigest\.getInstance\("SHA-256"\)|sha256\(' || true)
pinned_values=$(printf '%s\n' "$CODE" | grep -Ec '"[0-9a-f]{64}"' || true)
# The comparison is what makes the digest a check rather than a decoration: a
# hash computed and never compared verifies nothing.
digest_compared=$(printf '%s\n' "$CODE" \
    | grep -Eic '(check|require|if|assert|error|throw).*(digest|sha256|hash|checksum).*(==|!=|equals|contentEquals)|(digest|sha256|hash|checksum).*(==|!=|equals|contentEquals)' || true)

if [[ "$digest_api" -ge 1 && "$pinned_values" -ge 1 && "$digest_compared" -ge 1 ]]; then
    pass "downloads verified against a pinned digest (${digest_api} digest call(s), ${pinned_values} pinned hash(es), ${digest_compared} comparison(s))"
else
    fail "downloadToCache() does not verify what it downloaded."
    echo "      found (comments stripped): ${digest_api} SHA-256 call(s), ${pinned_values} pinned"
    echo "      64-hex value(s), ${digest_compared} digest comparison(s); need >=1 of each."
    echo "      Fix: hash the bytes after the stream completes, compare against a"
    echo "      checked-in expected digest per artifact, and DELETE the cached file"
    echo "      on mismatch — a poisoned cache entry that survives is worse than a"
    echo "      failed download. Pin the jextract tarball too; EA builds are transient."
    failures=$((failures + 1))
fi

# ─── 2. Symlink targets are validated before creation ───────────────────────
# The existing `check(out.startsWith(destPath))` guards the ENTRY path, so this
# probe must not be satisfied by it — and must not be satisfied by a comment, nor
# by a `normalize()` whose result nobody looks at. Two tightenings over the
# previous version, both driven by measured green-on-vulnerable trees:
#   - comments stripped (a bare `// TODO: normalize linkName` scored a PASS);
#   - `normalize` alone no longer counts. `Paths.get(entry.linkName).normalize()`
#     returns a value; discarding it validates nothing. The line must carry a
#     REFUSAL — check/require/throw/error/return — together with a containment
#     test (startsWith / isAbsolute), which is the property that actually stops
#     `a -> /tmp/evil`.
if printf '%s\n' "$CODE" | grep -q 'createSymbolicLink'; then
    # Follow the VARIABLE, not the line. The realistic guard spans two lines —
    #     val target = out.parent.resolve(entry.linkName).normalize()
    #     check(target.startsWith(destPath)) { … }
    # — so a same-line requirement rejected genuine hardening (measured: 3 of this
    # gate's own self-tests went red). Requiring only "an assertion somewhere" is
    # the opposite error: the pre-existing `check(out.startsWith(destPath))` guards
    # the ENTRY path and would satisfy it for free. So: find the variables derived
    # from `linkName`, then demand a containment assertion naming ONE OF THEM.
    linkname_guard=0
    # `|| true` is load-bearing under `set -euo pipefail`: with no match, grep exits
    # 1, pipefail propagates it out of the substitution and set -e kills the script
    # MID-GATE — which then reports rc=1 and reads exactly like a legitimate refusal.
    # Measured: three self-tests went red for this reason, not for the reason they
    # named. A gate that dies is not a gate that judges.
    link_vars=$(printf '%s\n' "$CODE" \
        | grep -E '\b(val|var)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=.*linkName' \
        | sed -E 's/.*\b(val|var)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*).*/\2/' | sort -u || true)
    for v in $link_vars; do
        n=$(printf '%s\n' "$CODE" \
            | grep -Ec "(check|require|assert|throw|error|return|if).*\\b${v}\\b.*(startsWith|isAbsolute)" || true)
        linkname_guard=$((linkname_guard + n))
    done
    # The one-liner form, where linkName is asserted on directly.
    linkname_guard=$((linkname_guard + $(printf '%s\n' "$CODE" \
        | grep -Ec '(check|require|assert|throw|error|return|if).*linkName.*(startsWith|isAbsolute)|(check|require|assert|throw|error|return|if).*(startsWith|isAbsolute).*linkName' || true)))
    if [[ "$linkname_guard" -ge 1 ]]; then
        pass "symlink targets validated before creation (${linkname_guard} guard(s) on linkName)"
    else
        fail "extractAll() creates symlinks from an unvalidated entry.linkName."
        echo "      A tarball carrying 'a -> /tmp/evil' then 'a/x' escapes the destination:"
        echo "      normalize() does not resolve symlinks, so the existing entry-path check passes."
        echo "      Fix: resolve linkName against the entry's parent and assert the result"
        echo "      stays under destPath; reject absolute targets outright."
        failures=$((failures + 1))
    fi
else
    pass "extractAll() creates no symlinks (nothing to validate)"
fi

echo
if [[ "$failures" -gt 0 ]]; then
    echo -e "${RED}[check-vendored-download-safety] $failures requirement(s) unmet — build chain is wired, hardening is not.${NC}"
    exit 1
fi
echo -e "${GREEN}[check-vendored-download-safety] vendored download chain is hardened.${NC}"
