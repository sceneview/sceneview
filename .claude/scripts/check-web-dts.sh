#!/usr/bin/env bash
#
# check-web-dts.sh — deterministic drift gate between the hand-written npm
# TypeScript declarations (`sceneview-web/sceneview-web.d.ts`) and the actual
# Kotlin/JS surface. Closes #2736.
#
# WHY THIS EXISTS
# ---------------
# The `sceneview-web` npm package ships hand-written typings guarded, until
# this script, by nothing but a "Keep this file in sync" comment (#946). An
# export added to the Kotlin sources without a `.d.ts` update ships wrong
# types to real npm consumers (~780 dl/month) and skews every AI assistant
# that reads the typings to generate user code. First run of this guard
# found a live instance: `sceneview.haptic` was registered on the namespace
# but absent from the `.d.ts`.
#
# Why not generate the `.d.ts` (Kotlin `generateTypeScriptDefinitions()`)?
# The published surface is NOT the `@JsExport` surface: `Main.kt#main()`
# builds the `sceneview` global namespace dynamically
# (`api["createViewer"] = ::jsCreateViewer` … `window["sceneview"] = api`),
# which is invisible to the compiler, and the typings use
# `export as namespace sceneview` + hand-shaped Promise signatures that the
# generated ES-module d.ts cannot express. Generation would document the
# wrong surface; this deterministic guard keeps the manual file honest.
#
# WHAT IT CHECKS (all bidirectional, all hard-fail)
# -------------------------------------------------
#   1. Namespace registry: every `api["X"]` key in Main.kt appears as a
#      top-level `export function X` / `export const X` in the d.ts — and
#      every such export maps back to a registered key.
#   2. Interface members: for each (Kotlin class ↔ d.ts interface) pair
#        SceneViewJS.kt      ↔ interface SceneViewer
#        NodeHandle.kt       ↔ interface NodeHandle
#        SceneViewHaptic.kt  ↔ interface SceneViewHaptic
#      the public member names (honouring `@JsName` overrides) must match
#      the interface's member names exactly, both directions.
#
# Deliberately NOT checked (precision over recall — a false positive teaches
# humans to ignore the gate): parameter lists / types (prose-level, covered
# by review + the in-browser smoke test), `@JsExport` classes that are not
# reachable from the `sceneview` namespace registry (e.g. SpatialAudioNode —
# module-import-only surface), and jsTest sources.
#
# Usage:  bash .claude/scripts/check-web-dts.sh
#         CHECK_WEB_DTS_ROOT=<dir> …   # fixture root (used by the self-test)
#
# Exit: 0 in sync · 1 drift (each mismatch listed) · 2 missing input file.

set -uo pipefail

ROOT="${CHECK_WEB_DTS_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
DTS="$ROOT/sceneview-web/sceneview-web.d.ts"
JSMAIN="$ROOT/sceneview-web/src/jsMain/kotlin/io/github/sceneview/web"
MAIN_KT="$JSMAIN/Main.kt"

for f in "$DTS" "$MAIN_KT"; do
    if [ ! -f "$f" ]; then
        echo "[ERROR] missing input: $f" >&2
        exit 2
    fi
done

FAIL=0
report() { echo "[FAIL] $1"; FAIL=1; }

# ─── Extractors ─────────────────────────────────────────────────────────

# Top-level `export function X` / `export const X` names in the d.ts.
dts_top_level() {
    grep -oE '^export (declare )?(function|const) [A-Za-z0-9_]+' "$DTS" \
        | awk '{print $NF}' | sort -u
}

# `api["X"]` keys registered on the namespace in Main.kt.
api_keys() {
    grep -oE 'api\["[A-Za-z0-9_]+"\]' "$MAIN_KT" \
        | sed -E 's/.*\["([A-Za-z0-9_]+)"\].*/\1/' | sort -u
}

# Member names of a d.ts interface block: `^export interface <Name> {` … `}`.
dts_iface_members() { # $1 = interface name
    awk -v name="$1" '
        $0 ~ ("^export interface " name " \\{") { f = 1; next }
        f && /^\}/ { exit }
        f
    ' "$DTS" | grep -oE '^  (readonly )?[A-Za-z0-9_]+' | awk '{print $NF}' | sort -u
}

# Public member JS-names of ONE Kotlin class: 4-space-indented public
# fun/val/var declarations between `class <Name>` and the next top-level `}`,
# with a preceding `@JsName("x")` taking precedence over the Kotlin
# identifier (that is what mangling-stable exports rely on). Members of any
# OTHER type in the same file (e.g. NodeHandle.kt's `internal interface
# NodeHost`) and private/internal members are skipped.
kt_members() { # $1 = kotlin file, $2 = class name
    awk -v cls="$2" '
        !inclass && $0 ~ ("(^|[[:space:]])class " cls "([ ({]|$)") { inclass = 1; next }
        !inclass { next }
        /^\}/ { exit }   # first top-level closing brace ends the class
        /^[[:space:]]*@JsName\("[A-Za-z0-9_]+"\)/ {
            match($0, /"[A-Za-z0-9_]+"/)
            pending = substr($0, RSTART + 1, RLENGTH - 2)
            next
        }
        /^    (override )?(public )?(fun|val|var) [A-Za-z0-9_]+/ {
            if ($0 ~ /private|internal/) { pending = ""; next }
            if (pending != "") { print pending; pending = ""; next }
            line = $0
            sub(/^    (override )?(public )?(fun|val|var) /, "", line)
            match(line, /^[A-Za-z0-9_]+/)
            print substr(line, RSTART, RLENGTH)
            next
        }
        /^[[:space:]]*$/ { next }   # blank lines keep a pending @JsName alive
        { if ($0 !~ /^[[:space:]]*(\/\/|\/\*|\*)/) pending = "" }
    ' "$1" | sort -u
}

diff_sets() { # $1 = expected-in label, $2 = list A, $3 = list B (A minus B reported)
    local missing
    missing=$(comm -23 <(echo "$2") <(echo "$3") | grep -v '^$' || true)
    if [ -n "$missing" ]; then
        while IFS= read -r sym; do
            report "$1: \`$sym\`"
        done <<< "$missing"
    fi
}

# ─── 1. Namespace registry ↔ d.ts top-level exports ────────────────────
KEYS=$(api_keys)
TOP=$(dts_top_level)
diff_sets "registered on the sceneview namespace (Main.kt) but missing from sceneview-web.d.ts" "$KEYS" "$TOP"
diff_sets "declared top-level in sceneview-web.d.ts but not registered in Main.kt" "$TOP" "$KEYS"

# ─── 2. Class ↔ interface member pairs ─────────────────────────────────
check_pair() { # $1 = kotlin file (relative to JSMAIN), $2 = kotlin class, $3 = d.ts interface name
    local kt="$JSMAIN/$1"
    if [ ! -f "$kt" ]; then
        report "expected Kotlin source missing: $kt (update this script if the class moved)"
        return
    fi
    local kmembers dmembers
    kmembers=$(kt_members "$kt" "$2")
    dmembers=$(dts_iface_members "$3")
    if [ -z "$dmembers" ]; then
        report "interface \`$3\` not found in sceneview-web.d.ts"
        return
    fi
    if [ -z "$kmembers" ]; then
        report "no public members extracted from $1 class \`$2\` (extractor broken or class renamed?)"
        return
    fi
    diff_sets "public in $1 but missing from d.ts interface \`$3\`" "$kmembers" "$dmembers"
    diff_sets "in d.ts interface \`$3\` but not public in $1" "$dmembers" "$kmembers"
}

check_pair "SceneViewJS.kt" "SceneViewJS" "SceneViewer"
check_pair "NodeHandle.kt" "NodeHandle" "NodeHandle"
check_pair "haptic/SceneViewHaptic.kt" "SceneViewHaptic" "SceneViewHaptic"

# ─── Verdict ────────────────────────────────────────────────────────────
if [ "$FAIL" -ne 0 ]; then
    echo ""
    echo "sceneview-web.d.ts has drifted from the Kotlin/JS surface."
    echo "Fix: update sceneview-web/sceneview-web.d.ts (or the registry/class)"
    echo "so both sides agree. See #2736 for the contract."
    exit 1
fi
echo "OK: sceneview-web.d.ts is in sync with the Kotlin/JS surface (namespace registry + 3 interface pairs)."
exit 0
