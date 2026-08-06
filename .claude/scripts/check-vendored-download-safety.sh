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
# Both are build-time, and today they are unreachable: NOTHING builds the
# vendored tree — `settings.gradle` has no include for it. That is precisely
# why this is a gate and not a patch. The defects become live the moment the
# desktop spike (#2540) wires the binding in, and that is the moment a reviewer
# is thinking about CMake and jextract, not about tar entries.
#
# So: while nothing builds it, this passes and says nothing. The instant
# something does, it fails and names both fixes.
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

# ANY non-comment mention of the path in a settings file counts as wiring. An
# earlier version of this probe matched only `include("<path>")` and was blind to
# the form Gradle actually uses for a relocated subproject —
#   include ":filament-kmp"
#   project(":filament-kmp").projectDir = file("third_party/filament-kmp")
# — so a mutation test that wired the tree left the gate green. A settings file
# exists to wire projects: a mention of the path in one IS the wiring, and
# matching the path alone cannot be outrun by a syntax this script did not predict.
if grep -REq "^[^/#]*third_party/filament-kmp" \
        settings.gradle settings.gradle.kts 2>/dev/null; then
    wired=1
    wire_reason="a settings file references $VENDORED"
fi

if grep -REq "third_party/filament-kmp.*gradlew|gradlew.*third_party/filament-kmp" \
        .github/workflows/*.yml 2>/dev/null; then
    wired=1
    wire_reason="${wire_reason:+$wire_reason; }a CI step runs Gradle in $VENDORED"
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
# COUNTED, not merely grepped: the probe must match the digest plumbing AND a
# pinned expected value. A file that merely mentions "sha256" in a comment
# scores zero here, which is the point — a hollow probe is worse than none.
digest_api=$(grep -Ec 'MessageDigest\.getInstance\("SHA-256"\)|sha256\(' "$DOWNLOADS" || true)
pinned_values=$(grep -Ec '"[0-9a-f]{64}"' "$DOWNLOADS" || true)

if [[ "$digest_api" -ge 1 && "$pinned_values" -ge 1 ]]; then
    pass "downloads verified against a pinned digest (${digest_api} digest call(s), ${pinned_values} pinned hash(es))"
else
    fail "downloadToCache() does not verify what it downloaded."
    echo "      found: ${digest_api} SHA-256 call(s), ${pinned_values} pinned 64-hex value(s); need >=1 of each."
    echo "      Fix: hash the bytes after the stream completes, compare against a"
    echo "      checked-in expected digest per artifact, and DELETE the cached file"
    echo "      on mismatch — a poisoned cache entry that survives is worse than a"
    echo "      failed download. Pin the jextract tarball too; EA builds are transient."
    failures=$((failures + 1))
fi

# ─── 2. Symlink targets are validated before creation ───────────────────────
# The existing `check(out.startsWith(destPath))` guards the ENTRY path. This
# probe is specifically about linkName, so it must not be satisfied by that
# check: it requires linkName to be mentioned in the same file as a containment
# assertion applied to a RESOLVED link target.
if grep -q 'createSymbolicLink' "$DOWNLOADS"; then
    linkname_guard=$(grep -Ec 'linkName.*(startsWith|normalize|isAbsolute)|(startsWith|normalize|isAbsolute).*linkName' "$DOWNLOADS" || true)
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
