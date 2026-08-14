#!/usr/bin/env bash
#
# check-web-filamat-abi.sh — the runtime↔blob ABI invariant for the two WEB
# Filament tracks, checked offline (issue #2783).
#
# Filament parses a .filamat package only when its MATERIAL_VERSION matches the
# runtime exactly; a mismatch is a load-time failure, never a compile-time one.
# That is the invariant that made v4.1.0 SIGABRT on 10 demos.
#
# `tools/GenerateFilamat.sh --check` proves a blob still matches its *.mat
# source. It cannot prove the blob matches the *runtime that loads it*: it
# compiles with the pinned matc, so a wrong pin produces a wrong blob that
# --check happily calls "in sync". #2783 fell exactly through that gap — the 3
# website-static blobs rode the Android pin (v72) while the site vendors a v70
# Filament.js. This script closes it, and needs no network (no matc download),
# so it is a hard CI leg rather than an advisory one.
#
# What it asserts:
#   1. website-static/js/filament/RUNTIME.json declares the vendored runtime,
#      and its sha256 entries match the committed bytes  → the runtime cannot be
#      swapped silently.
#   2. RUNTIME.json's `version` == `filamentWebsite` in gradle/libs.versions.toml
#      and its `materialVersion` == that version's minor digit.
#   3. every website-static/materials/*.filamat has MATERIAL_VERSION ==
#      minor(filamentWebsite).
#   4. every sceneview-web/materials/*.filamat has MATERIAL_VERSION ==
#      minor(filamentWeb) — the npm runtime the Kotlin/JS bundle loads.
#   5. the "Filament.js v<x>" comments in website-static/*.html name the pinned
#      version (the labels that said 1.70.2 for a 1.70.1 runtime).
#
# A .filamat header is: the chunk id "SREV_TAM" (8 bytes — 'MAT_VERS' stored
# little-endian) + uint32 LE size (4) + uint32 LE MATERIAL_VERSION. Bytes 12..15
# are read directly, so no Filament tooling and no network are needed.
#
# Usage:
#   bash .claude/scripts/check-web-filamat-abi.sh
#
# Exit code: 0 if every web blob matches its own runtime, 1 on any mismatch,
# 2 if the repo layout is missing (not a checkout / files absent).

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
    echo "check-web-filamat-abi.sh: not inside a git checkout" >&2
    exit 2
fi

TOML="$ROOT/gradle/libs.versions.toml"
RUNTIME_JSON="$ROOT/website-static/js/filament/RUNTIME.json"

if [ ! -f "$TOML" ]; then
    echo "check-web-filamat-abi.sh: gradle/libs.versions.toml not found" >&2
    exit 2
fi

if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
else
    RED=''; GREEN=''; NC=''
fi

ERRORS=0
fail() { printf "${RED}MISMATCH: %s${NC}\n" "$1"; ERRORS=$((ERRORS + 1)); }

# Read a top-level `key = "value"` from libs.versions.toml.
toml_version() {
    grep -E "^$1[[:space:]]*=" "$TOML" | head -1 |
        sed -E "s/^$1[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/"
}

# The minor digit IS the MATERIAL_VERSION for a given Filament minor release
# (1.70.x → 70), the same mapping tools/GenerateFilamat.sh verifies via
# `matc --version`.
minor_of() { printf '%s' "$1" | awk -F. '{print $2}'; }

# MATERIAL_VERSION = uint32 LE at offset 12 of the .filamat header.
blob_material_version() {
    od -A n -t u4 -j 12 -N 4 "$1" 2>/dev/null | tr -d ' \n'
}

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

# Minimal string/number field reader — RUNTIME.json is ours and flat, so this
# avoids a jq/python dependency in the CI leg.
json_field() {
    grep -oE "\"$2\"[[:space:]]*:[[:space:]]*\"?[^\",}]+\"?" "$1" | head -1 |
        sed -E "s/.*:[[:space:]]*\"?([^\",}]+)\"?/\1/" | tr -d ' '
}

# ─── Resolve the pins ──────────────────────────────────────────────────
FILAMENT_WEBSITE=$(toml_version filamentWebsite)
FILAMENT_WEB=$(toml_version filamentWeb)

if [ -z "$FILAMENT_WEBSITE" ]; then
    fail "no 'filamentWebsite = ...' in gradle/libs.versions.toml"
    echo "  The website-static blobs need their own pin — they must NOT ride the"
    echo "  Android 'filament' pin (that is issue #2783 regressing)."
fi
if [ -z "$FILAMENT_WEB" ]; then
    fail "no 'filamentWeb = ...' in gradle/libs.versions.toml"
fi
[ "$ERRORS" -eq 0 ] || exit 1

WEBSITE_MV=$(minor_of "$FILAMENT_WEBSITE")
WEB_MV=$(minor_of "$FILAMENT_WEB")

# ─── 1+2. The vendored runtime manifest ────────────────────────────────
if [ ! -f "$RUNTIME_JSON" ]; then
    fail "website-static/js/filament/RUNTIME.json is missing"
    echo "  Without it the vendored Filament.js version is only an HTML comment,"
    echo "  and comments drift (they claimed 1.70.2 for a 1.70.1 runtime — #2783)."
else
    RUNTIME_VERSION=$(json_field "$RUNTIME_JSON" version)
    RUNTIME_MV=$(json_field "$RUNTIME_JSON" materialVersion)

    if [ "$RUNTIME_VERSION" != "$FILAMENT_WEBSITE" ]; then
        fail "RUNTIME.json version ($RUNTIME_VERSION) != filamentWebsite pin ($FILAMENT_WEBSITE)"
        echo "  The site's blobs are compiled with matc \$filamentWebsite; if the pin"
        echo "  and the vendored runtime disagree, the blobs cannot load."
    fi
    if [ "$RUNTIME_MV" != "$WEBSITE_MV" ]; then
        fail "RUNTIME.json materialVersion ($RUNTIME_MV) != minor of $FILAMENT_WEBSITE ($WEBSITE_MV)"
    fi

    for f in filament.js filament.wasm; do
        path="$ROOT/website-static/js/filament/$f"
        if [ ! -f "$path" ]; then
            fail "vendored runtime file missing: website-static/js/filament/$f"
            continue
        fi
        expected=$(json_field "$RUNTIME_JSON" "$f")
        actual=$(sha256_of "$path")
        if [ -z "$expected" ]; then
            fail "RUNTIME.json has no sha256 for $f"
        elif [ "$expected" != "$actual" ]; then
            fail "website-static/js/filament/$f changed but RUNTIME.json was not updated"
            echo "  expected $expected"
            echo "  actual   $actual"
            echo "  A runtime swap must move 'filamentWebsite' and regenerate the blobs"
            echo "  in the SAME change: bash tools/GenerateFilamat.sh"
        fi
    done
fi

# ─── 3+4. Every web blob against its own runtime ───────────────────────
check_group() {
    local dir="$1" expected_mv="$2" pin_name="$3" pin_value="$4"
    local found=0 blob blob_mv rel
    for blob in "$ROOT/$dir"/*.filamat; do
        [ -f "$blob" ] || continue
        found=$((found + 1))
        rel="${blob#"$ROOT"/}"
        blob_mv=$(blob_material_version "$blob")
        if [ -z "$blob_mv" ]; then
            fail "$rel — could not read MATERIAL_VERSION (truncated blob?)"
        elif [ "$blob_mv" != "$expected_mv" ]; then
            fail "$rel is MATERIAL_VERSION $blob_mv but its runtime ($pin_name = $pin_value) is v$expected_mv"
            echo "  Filament refuses any package whose version is not an exact match."
            echo "  Fix: bash tools/GenerateFilamat.sh (compiles this group with matc $pin_value)."
        fi
    done
    if [ "$found" -eq 0 ]; then
        echo "  note: no .filamat blobs under $dir/ — nothing to check"
    fi
}

check_group "website-static/materials" "$WEBSITE_MV" "filamentWebsite" "$FILAMENT_WEBSITE"
check_group "sceneview-web/materials"  "$WEB_MV"     "filamentWeb"     "$FILAMENT_WEB"

# ─── 5. The human-readable labels ──────────────────────────────────────
# Three pages carried "Filament.js v1.70.2" for a 1.70.1 runtime. A label that
# names the wrong version is how the v4.1.0 pair went wrong in the first place.
for html in "$ROOT"/website-static/*.html; do
    [ -f "$html" ] || continue
    while IFS= read -r labelled; do
        [ -n "$labelled" ] || continue
        if [ "$labelled" != "$FILAMENT_WEBSITE" ]; then
            fail "${html#"$ROOT"/} says 'Filament.js v$labelled' but the vendored runtime is $FILAMENT_WEBSITE"
        fi
    done <<EOF
$(grep -oE 'Filament\.js v[0-9]+\.[0-9]+\.[0-9]+' "$html" | sed -E 's/^Filament\.js v//')
EOF
done

if [ "$ERRORS" -eq 0 ]; then
    printf "${GREEN}Web .filamat blobs match their runtimes (website v%s / %s, npm v%s / %s)${NC}\n" \
        "$WEBSITE_MV" "$FILAMENT_WEBSITE" "$WEB_MV" "$FILAMENT_WEB"
    exit 0
fi
exit 1
