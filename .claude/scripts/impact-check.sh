#!/bin/bash
# ─── SceneView Impact Check ───────────────────────────────────────────────
# Run AFTER any code/doc change to catch cross-file inconsistencies.
# Part of the quality-gate pipeline.
#
# Usage:
#   bash .claude/scripts/impact-check.sh           # report warnings, exit 0 if all checks ran
#   bash .claude/scripts/impact-check.sh --fail    # exit 1 if any check FAIL'd
#   bash .claude/scripts/impact-check.sh --fix     # auto-fix where possible
#
# Env:
#   SV_IMPACT_TRACE=1         force `set -x` regardless of TTY
#   SV_IMPACT_TRACE_AUTO=0    disable auto `set -x` when stdout is not a TTY
#
# Robustness contract (#1782 / #1786 / #2370):
#   - Every check echoes a trace line BEFORE running, so an unexpected death
#     under `set -euo pipefail` points at the failing predicate.
#   - Path-dependent checks (sparse-checkout / lean-clone aware) [SKIP]
#     instead of dying when their inputs are absent. A check whose result
#     depends on a COMPLETE set of inputs (e.g. the node-count total, summed
#     across both the 3D and AR node dirs) also [SKIP]s on a PARTIAL checkout
#     rather than emitting a false count — a partial total would FALSE-FAIL
#     every doc claim ("Claims 42, actual 24").
#   - History-dependent checks pick a diff base that exists: a shallow
#     (`--depth 1`) clone has no `HEAD~1`, so they fall back to `origin/main`
#     and uncommitted working-tree edits, and [SKIP] honestly when no base is
#     resolvable — never a silent "nothing changed" no-op.
#   - An ERR trap names the dying check on any other unexpected failure.
#   - Default exit code is 0 (report-only); `--fail` opts in to non-zero
#     for use in the quality gate.

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

FIX_MODE=false
FAIL_ON_ERROR=false
for arg in "$@"; do
    case "$arg" in
        --fix)  FIX_MODE=true ;;
        --fail) FAIL_ON_ERROR=true ;;
        -h|--help)
            sed -n '2,22p' "$0"
            exit 0
            ;;
    esac
done

# Acknowledge `--fix` so shellcheck doesn't flag FIX_MODE as unused. The flag
# is plumbed for future per-check auto-fix support; today it's a no-op.
: "${FIX_MODE}"

# ─── Tracing ──────────────────────────────────────────────────────────────
# Manual opt-in (SV_IMPACT_TRACE=1) OR auto when stdout is not a TTY (CI /
# agent runs) UNLESS the user disabled the auto behavior with
# SV_IMPACT_TRACE_AUTO=0.
if [[ "${SV_IMPACT_TRACE:-0}" == "1" ]]; then
    set -x
elif [[ ! -t 1 ]] && [[ "${SV_IMPACT_TRACE_AUTO:-1}" == "1" ]]; then
    set -x
fi

# Last check announced via `trace` — surfaced in the ERR trap so a death
# under `set -e` points at the actual failing predicate, not just $LINENO.
CURRENT_CHECK="<startup>"
trace() {
    CURRENT_CHECK="$*"
    # `printf '%b'` interprets backslash escapes (color codes) without
    # depending on a system-dependent `echo -e`.
    printf '%b→ check: %s%b\n' "$CYAN" "$*" "$NC" >&2
}

# shellcheck disable=SC2329  # invoked indirectly via `trap on_err ERR`
on_err() {
    local rc=$?
    printf '%b❌ impact-check died at line %s (rc=%d) running: %s%b\n' \
        "$RED" "${BASH_LINENO[0]:-?}" "$rc" "$CURRENT_CHECK" "$NC" >&2
    exit "$rc"
}
trap on_err ERR

ISSUES=0
WARNINGS=0
SKIPS=0

check() {
    local name="$1" status="$2" detail="${3:-}"
    case "$status" in
        PASS) printf "  ${GREEN}[PASS]${NC}  %-50s %s\n" "$name" "$detail" ;;
        FAIL) printf "  ${RED}[FAIL]${NC}  %-50s %s\n" "$name" "$detail"; ISSUES=$((ISSUES + 1)) ;;
        WARN) printf "  ${YELLOW}[WARN]${NC}  %-50s %s\n" "$name" "$detail"; WARNINGS=$((WARNINGS + 1)) ;;
        SKIP) printf "  ${YELLOW}[SKIP]${NC}  %-50s %s\n" "$name" "$detail"; SKIPS=$((SKIPS + 1)) ;;
    esac
}

# Path-gate helper: skip-and-continue if a required path is absent (lean /
# sparse clone). Returns 0 if the path exists, 1 (handled by the caller's
# `if`) otherwise — never aborts the script.
require_path() {
    local path="$1" check_name="$2"
    if [[ -e "$path" ]]; then
        return 0
    fi
    check "$check_name" "SKIP" "$path not in checkout"
    return 1
}

echo -e "${CYAN}=== Impact Check ===${NC}"
echo ""

# ─── 1. Node count consistency ────────────────────────────────────────────
trace "node count consistency (count *Node.kt sources)"

# `ls *.kt | grep -v ...` is fragile under `pipefail`: zero matches → grep
# returns 1 → whole pipe dies. Use `find ... -print | grep -cv ... || true`
# which never fails for zero matches.
ANDROID_3D_DIR="sceneview/src/main/java/io/github/sceneview/node"
ANDROID_AR_DIR="arsceneview/src/main/java/io/github/sceneview/ar/node"

# The "N+ node types" marketing total is the SUM of the 3D + AR node sources.
# Both dirs must be present to compute a COMPLETE total. A lean / sparse clone
# that omits EITHER one (e.g. a worker that sparse-checks out `sceneview` but
# not `arsceneview`) would otherwise compute a partial total and FALSE-FAIL
# every doc claim ("Claims 42, actual 24"). So the claim comparison runs only
# when both source dirs exist; a partial checkout SKIPs instead of failing
# (#2370 — lean-clone is the standard batch-agent workflow).
if [[ ! -d "$ANDROID_3D_DIR" ]] || [[ ! -d "$ANDROID_AR_DIR" ]]; then
    MISSING_NODE_DIRS=""
    [[ ! -d "$ANDROID_3D_DIR" ]] && MISSING_NODE_DIRS="$MISSING_NODE_DIRS $ANDROID_3D_DIR"
    [[ ! -d "$ANDROID_AR_DIR" ]] && MISSING_NODE_DIRS="$MISSING_NODE_DIRS $ANDROID_AR_DIR"
    echo -e "${CYAN}--- Node count consistency (skipped: node sources not fully in checkout) ---${NC}"
    check "Node count consistency" "SKIP" "partial count — missing:${MISSING_NODE_DIRS}"
else
    # `grep -c` rather than `grep | wc -l`: one fewer process and one fewer
    # pipefail surface (per shellcheck SC2126).
    NODES_3D=$(find "$ANDROID_3D_DIR" -maxdepth 1 -name '*Node.kt' -print 2>/dev/null \
        | grep -cEv 'NodeState|NodeAnimationDelegate|NodeGestureDelegate|RenderableNode|GeometryNode' \
        || true)
    NODES_AR=$(find "$ANDROID_AR_DIR" -maxdepth 1 -name '*Node.kt' -print 2>/dev/null \
        | grep -c '' || true)
    ACTUAL_NODES=$((NODES_3D + NODES_AR))

    echo -e "${CYAN}--- Node count consistency (actual: $ACTUAL_NODES) ---${NC}"
    # Check each file that claims a node count. The list spans every
    # current-state surface that states a marketing "N+ node types" total.
    # Extended 2026-06-01 to cover cheatsheet / manifest / platforms / try /
    # .cursorrules / mcp guides — those were blind spots, which is exactly why
    # the count drifted silently across them.
    for f in README.md llms.txt website-static/index.html docs/docs/showcase.md mcp/README.md \
             marketing/articles/article-1-compose-native-3d.md marketing/awesome-lists/submissions.md \
             marketing/stackoverflow/qa-drafts.md \
             docs/docs/cheatsheet.md docs/docs/manifest.json docs/docs/platforms.md \
             docs/docs/try.md .cursorrules .windsurfrules mcp/src/guides.ts \
             docs/docs/structured-data.json docs/docs/index.md; do
        trace "node count claim in $f"
        if [[ -f "$f" ]]; then
            # Only check claims with "+" (marketing total), skip platform-specific
            # counts (e.g. the iOS "19 node types" subset, which has no "+").
            # Check EVERY claim, not just the first: a file may state the count in
            # several places (try.md, index.html) — a partial fix must still FAIL.
            # Case-insensitive (-i) so "42+ Node Types" (structured-data headline,
            # index.html feature card) is caught too, not just lowercase prose.
            # An OPTIONAL generic qualifier between the number and "node" — the
            # regex used to be a bare `N+ node type`, so "41+ built-in node types"
            # (structured-data) and "42+ composable node types" (showcase) sailed
            # past it for two alignments running: the gate reported 0 FAIL while
            # the repo contradicted itself in five places (#2987). Only GENERIC
            # qualifiers count. A PLATFORM-qualified claim is a legitimate subset
            # ("26+ 3D node types", "15+ SceneViewSwift node types") and must not be
            # compared against the Android total, so it is excluded by listing what
            # is generic rather than by guessing what is not.
            CLAIMS=$(grep -oiE '[0-9]+\+ (built-in |composable )?node type' "$f" 2>/dev/null \
                | grep -oE '[0-9]+' 2>/dev/null | sort -u || true)
            # SPLIT-MARKUP stat cards: the number and its label live in two
            # separate elements, so the line regex above is structurally blind to
            # them — `docs/docs/index.md` sat at "30+ Node Types" and
            # `website-static/index.html` at "26+" while every line-based check
            # reported clean (#2987).
            #
            # Pair-aware on purpose. A `grep -v` on the LABEL only drops the label
            # line and leaves the number line orphaned, which reported the
            # website's legitimate "26+ 3D node types" as "Claims 26, actual 46".
            # awk keeps the previous line, so the qualifier decides the PAIR.
            SPLIT=$(awk '
                /[Nn]ode [Tt]ype/ {
                    # Word-ANCHORED. Unanchored, the "ar" alternative matches inside
                    # an ordinary word ("planar node types"), which would exclude a
                    # real total claim and downgrade it to a silent PASS — measured:
                    # a "99+ Planar Node Types" card produced NO check at all.
                    if (tolower($0) ~ /(^|[^a-z0-9])(3d|ios|swift|web|ar) node type/) { prev=$0; next }
                    if (match(prev, />[0-9]+\+</)) {
                        n = substr(prev, RSTART+1, RLENGTH-3); print n
                    }
                }
                { prev=$0 }
            ' "$f" 2>/dev/null | sort -u || true)
            if [[ -n "$SPLIT" ]]; then
                CLAIMS=$(printf '%s\n%s\n' "$CLAIMS" "$SPLIT" | grep -oE '[0-9]+' | sort -u)
            fi
            if [[ -n "$CLAIMS" ]]; then
                BAD=""
                for c in $CLAIMS; do
                    if [[ "$c" -ne "$ACTUAL_NODES" ]]; then
                        BAD="$BAD $c"
                    fi
                done
                if [[ -n "${BAD// }" ]]; then
                    check "$f node count" "FAIL" "Claims$BAD, actual $ACTUAL_NODES"
                else
                    check "$f node count" "PASS" "$ACTUAL_NODES"
                fi
            fi
        fi
    done
fi

# ─── 2. New public API → must be in llms.txt ─────────────────────────────
echo ""
echo -e "${CYAN}--- New API → llms.txt coverage ---${NC}"
trace "SceneScope.kt → llms.txt coverage"

SCENESCOPE_FILE="sceneview/src/main/java/io/github/sceneview/SceneScope.kt"
if require_path "$SCENESCOPE_FILE" "SceneScope nodes in llms.txt"; then
    if ! require_path "llms.txt" "SceneScope nodes in llms.txt"; then
        : # SKIP already emitted by require_path
    else
        # `grep -oE` returns 1 if no matches under `pipefail` — append `|| true`.
        SCOPE_NODES=$(grep -oE 'fun (([A-Z][a-zA-Z]+Node)|ModelNode|LightNode|CameraNode)\(' \
            "$SCENESCOPE_FILE" 2>/dev/null \
            | sed 's/fun //; s/(//' | sort -u || true)

        MISSING_IN_LLMS=""
        for node in $SCOPE_NODES; do
            trace "llms.txt mentions $node"
            if ! grep -q "$node" llms.txt 2>/dev/null; then
                MISSING_IN_LLMS="$MISSING_IN_LLMS $node"
            fi
        done

        if [[ -z "$MISSING_IN_LLMS" ]]; then
            check "All SceneScope nodes in llms.txt" "PASS" ""
        else
            check "Nodes missing from llms.txt" "FAIL" "$MISSING_IN_LLMS"
        fi
    fi
fi

# ─── 3. SceneViewSwift parity check ──────────────────────────────────────
echo ""
echo -e "${CYAN}--- Cross-platform parity (Android vs Swift) ---${NC}"

ANDROID_NODE_DIR="sceneview/src/main/java/io/github/sceneview/node"
SWIFT_NODE_DIR="SceneViewSwift/Sources/SceneViewSwift/Nodes"
SWIFT_GEOM_FILE="$SWIFT_NODE_DIR/GeometryNode.swift"

if [[ ! -d "$ANDROID_NODE_DIR" ]] || [[ ! -d "$SWIFT_NODE_DIR" ]]; then
    check "Android ↔ Swift node parity" "SKIP" "one side not in checkout"
else
    trace "Android node enumeration ($ANDROID_NODE_DIR)"
    # Android: file-based node types (exclude base classes). `-printf` is not
    # portable to BSD `find` (macOS) — use `-exec basename {} .kt \;` for
    # cross-platform basename extraction without `xargs` word-splitting.
    ANDROID_NODES=$(find "$ANDROID_NODE_DIR" -maxdepth 1 -name '*Node.kt' \
        -exec basename {} .kt \; 2>/dev/null \
        | grep -Ev '^(Node|RenderableNode|GeometryNode)$' \
        | sort -u || true)

    trace "Swift node enumeration ($SWIFT_NODE_DIR)"
    # Swift: file-based node types + geometry factories in GeometryNode.swift
    SWIFT_FILE_NODES=$(find "$SWIFT_NODE_DIR" -maxdepth 1 -name '*Node.swift' \
        -exec basename {} .swift \; 2>/dev/null \
        | grep -Ev '^GeometryNode$' \
        | sort -u || true)

    SWIFT_GEOM_FACTORIES=""
    if [[ -f "$SWIFT_GEOM_FILE" ]]; then
        trace "Swift geometry factories in GeometryNode.swift"
        # Extract geometry factory names and map to Android-equivalent node names
        SWIFT_GEOM_FACTORIES=$(grep 'public static func' "$SWIFT_GEOM_FILE" 2>/dev/null \
            | sed -n 's/.*public static func \([a-z]*\)(.*/\1/p' \
            | sort -u \
            | grep -v loadTexture \
            | while read -r name; do
                # Capitalize first letter and add "Node" suffix → e.g. "torus" → "TorusNode"
                [[ -n "$name" ]] && echo "$name" | awk '{print toupper(substr($0,1,1)) substr($0,2) "Node"}'
              done || true)
    fi
    SWIFT_NODES=$(printf '%s\n%s\n' "$SWIFT_FILE_NODES" "$SWIFT_GEOM_FACTORIES" | sort -u | grep -v '^$' || true)

    trace "diff Android vs Swift node sets (comm)"
    ANDROID_ONLY=$(comm -23 <(echo "$ANDROID_NODES") <(echo "$SWIFT_NODES") 2>/dev/null | tr '\n' ' ' || true)
    SWIFT_ONLY=$(comm -13 <(echo "$ANDROID_NODES") <(echo "$SWIFT_NODES") 2>/dev/null | tr '\n' ' ' || true)

    if [[ -z "${ANDROID_ONLY// }" ]] && [[ -z "${SWIFT_ONLY// }" ]]; then
        check "Android ↔ Swift node parity" "PASS" ""
    else
        [[ -n "${ANDROID_ONLY// }" ]] && check "Android-only nodes (no Swift)" "WARN" "$ANDROID_ONLY"
        [[ -n "${SWIFT_ONLY// }"   ]] && check "Swift-only nodes (no Android)" "WARN" "$SWIFT_ONLY"
    fi
fi

# ─── 4. SPM version consistency ──────────────────────────────────────────
echo ""
echo -e "${CYAN}--- SPM version consistency ---${NC}"
trace "SPM version consistency vs gradle.properties"

if ! require_path "gradle.properties" "SPM version consistency"; then
    : # SKIP already emitted
else
    GRADLE_VERSION=$(grep '^VERSION_NAME=' gradle.properties 2>/dev/null | cut -d= -f2 || true)
    if [[ -z "$GRADLE_VERSION" ]]; then
        check "SPM version refs" "SKIP" "VERSION_NAME unreadable in gradle.properties"
    else
        # This gate measures the REPOSITORY, not the disk — and it must target
        # the coordinate users actually resolve. It did neither (#3068):
        #
        #  1. `grep -r .` walked every file ON DISK, so it FAILed on untracked
        #     local drafts — marketing brochures, and browser-duplicate copies
        #     like `qa-drafts (1).md` — that exist in no clone and no CI run.
        #     The count drifted with the disk (15 file(s), then 17) because it
        #     was never reporting a property of the repository. `git ls-files`
        #     is the fix: what isn't committed cannot be a merge blocker.
        #
        #  2. It matched only `sceneview-swift`, the ARCHIVED mirror retired in
        #     PR #1215. Bumping a version on a dead URL is not a fix — that URL
        #     must not appear at all, which is `check-sceneview-swift-urls.sh`'s
        #     job (#1237). So the tracked population here was EMPTY, and an
        #     empty scan reported PASS: green in CI while verifying nothing,
        #     and blind to the ~17 tracked files carrying the CANONICAL
        #     `sceneview/sceneview` coordinate this now checks.
        #
        # Excluded on purpose:
        #   CHANGELOG.md / MIGRATION.md / docs/docs/migration.md — historical
        #     entries legitimately quote OLD versions.
        #   changelog.d/ — the PRE-IMAGE of CHANGELOG.md. Excluding one and not
        #     the other is the "same sentence, two verdicts" hole this PR closes
        #     in the sibling gate: `collate-changelog.sh` merges a fragment INTO
        #     CHANGELOG.md, so a release note quoting an old install line would
        #     be a merge blocker as a fragment and exempt the moment it ships.
        #   mcp/ — an independent release track that must NEVER be synced to
        #     VERSION_NAME, and whose `ios-outdated` fixture is stale by design.
        #
        # `sync-versions.sh` §10b remains the source-of-truth UPDATER over its
        # explicit file list; this is the discovery net that catches a canonical
        # SPM snippet living in a file that list doesn't know about yet.
        # Discovery and verdict are anchored to the SAME LINE. Judging at file
        # granularity let a file whose canonical snippet was stale still PASS
        # because some *other* line elsewhere happened to carry the current
        # version — the check would confirm a version that no reader resolves.
        # A "pinning line" is the canonical URL plus a version constraint on
        # that line; every SPM form is accepted, not just bare `from:`, so an
        # `.upToNextMajor(from:)` snippet is covered instead of silently
        # dropped from the population. Quoting is deliberately loose (' " `,
        # or none) in both halves: a snippet must never be discovered by one
        # regex and judged stale by the other.
        # The version is escaped for ERE rather than assumed to be bare semver
        # — a `+build` suffix would otherwise be read as a metacharacter.
        VER_ESC=$(printf '%s' "$GRADLE_VERSION" | sed 's/[][\.^$*+?(){}|]/\\&/g')
        # What follows the URL is deliberately near-adjacent — closing quote,
        # comma, paren, dot — and not `.*`: a permissive gap turns every plain
        # `github.com/sceneview/sceneview/blob/…` link whose sentence happens
        # to contain "from" into a stale SPM pin. Measured: 25 files, 10 of
        # them prose. It also keeps the ARCHIVED `sceneview/sceneview-swift`
        # mirror out (banning that URL is check-sceneview-swift-urls.sh's job).
        # A keyword is a constraint only in the SYNTAX that carries one: `from:`,
        # `.upToNextMajor(from:)`, `exact:`. The `[:(]` is what separates a pin
        # from English — bare `exact` prefixes `exactly`, and "from v3 onward"
        # three words after the URL is a sentence, not an install line. Both
        # readings cost: one blesses a version nobody checked, the other turns
        # a release note into a merge blocker. The boundary lives in the
        # SHARED constraint, not in the verdict half alone — a keyword that
        # discovers a line but cannot judge it turns that line into a false
        # stale, which is the asymmetry this pair was built to avoid.
        SPM_CONSTRAINT='(from|upToNextMajor|upToNextMinor|exact)[[:space:]]*[:(]'
        SPM_PIN_RE='sceneview/sceneview(\.git)?['"'"'"`]?[,)]?[[:space:]]*[.(]?[[:space:]]*'"$SPM_CONSTRAINT"
        # The right boundary rejects `.` and `-` too, so `4.26.0-beta` and
        # `4.26.0.1` are what they are — a DIFFERENT version — instead of
        # passing because the current string happens to be their prefix.
        SPM_OK_RE="${SPM_CONSTRAINT}[^0-9]*${VER_ESC}([^0-9.-]|$)"

        if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
            check "SPM version refs" "SKIP" "not a git repository"
        else
            # Paths stay NUL-delimited from `git ls-files` all the way into an
            # array: a newline in a committed filename would silently split a
            # `while read` over newline-joined `grep -l` output, and BSD grep
            # has no portable NUL-delimited -l to join the two halves with
            # (macOS `grep -lZ` still terminates with \n). `-e … --` keeps a
            # file named like `-i.md` from being read as a grep option.
            # 282 tracked files, ~1.1s — paid once per push, unlike the trust.
            # CHANGELOG.md / MIGRATION.md are excluded wherever they live, not
            # only at the repo root: the reason they are excluded is what the
            # file IS, and a docs/MIGRATION.md would quote old versions for
            # exactly the same reason the root one does.
            SPM_POP=()
            SPM_STALE=()
            SPM_STALE_FILES=0
            while IFS= read -r -d '' spm_f; do
                case "$spm_f" in
                    mcp/*|changelog.d/*|docs/docs/migration.md) continue ;;
                esac
                case "${spm_f##*/}" in
                    CHANGELOG.md|MIGRATION.md) continue ;;
                esac
                spm_pins=$(grep -nE -e "$SPM_PIN_RE" -- "$spm_f" 2>/dev/null || true)
                [[ -z "$spm_pins" ]] && continue
                SPM_POP+=("$spm_f")
                # EVERY stale pin, not just the first: a file with three of
                # them would otherwise be reported as having one, and the
                # second bump would look like a fresh regression.
                spm_bad=$(printf '%s\n' "$spm_pins" \
                    | grep -vE -e "$SPM_OK_RE" || true)
                if [[ -n "$spm_bad" ]]; then
                    SPM_STALE_FILES=$((SPM_STALE_FILES + 1))
                    while IFS= read -r spm_line; do
                        [[ -z "$spm_line" ]] && continue
                        SPM_STALE+=("$spm_f:${spm_line%%:*}")
                    done <<< "$spm_bad"
                fi
            done < <(git ls-files -z -- '*.md' '*.txt' 2>/dev/null)

            # An empty population has two very different causes, and calling
            # both the same thing is how a gate earns a reputation for lying:
            #
            #   - the SPM doc surface simply isn't checked out. Lean clones
            #     (`--depth 1` + sparse-checkout) are the standard batch-agent
            #     workflow, and FAILing them under `--fail` is precisely the
            #     #2370 false-red this script already carries scar tissue for.
            #     Nothing can be concluded → SKIP.
            #   - the surface IS here and the pattern still matches nothing.
            #     That is the detector being broken, and it must be loud —
            #     a silent green on an empty population is exactly how this
            #     check spent its life verifying nothing at all (#3068).
            #
            # These two sentinels are tracked files that always carry the
            # canonical snippet in a complete checkout, so their presence is
            # what separates "no surface" from "broken pattern".
            if [[ ${#SPM_POP[@]} -eq 0 ]] \
               && [[ ! -f "llms.txt" ]] && [[ ! -f "docs/docs/quickstart-ios.md" ]]; then
                check "SPM version refs" "SKIP" \
                    "SPM doc surface not in checkout (lean/sparse clone)"
            elif [[ ${#SPM_POP[@]} -eq 0 ]]; then
                check "SPM version refs" "FAIL" \
                    "discovery matched 0 tracked files — the pattern is broken, not the tree"
            elif [[ ${#SPM_STALE[@]} -eq 0 ]]; then
                check "SPM version refs match $GRADLE_VERSION" "PASS" \
                    "${#SPM_POP[@]} tracked file(s) scanned"
            else
                # One entry PER LINE, never space-joined: `spm guide.md:1` is
                # indistinguishable from two entries once a space is also the
                # separator, and a report you have to squint at is how this
                # gate's bare "15 file(s)" stayed unactionable for so long.
                check "SPM version refs stale" "FAIL" \
                    "$SPM_STALE_FILES of ${#SPM_POP[@]} tracked file(s), ${#SPM_STALE[@]} line(s)"
                printf '    %s\n' "${SPM_STALE[@]}"
            fi
        fi
    fi
fi

# ─── 5. Emulator build check ─────────────────────────────────────────────
echo ""
echo -e "${CYAN}--- Sample app build (fast check) ---${NC}"
trace "Android sample build dry-run gate"

# Only check if Android SDK source changed.
#
# `.git` is a DIRECTORY only in a primary checkout. In a linked worktree — how
# `.claude/worktrees/*` and every agent-isolated session runs — it is a regular
# FILE holding `gitdir: …`, so a `-d` test declared "not a git repository" and
# skipped this leg exactly where most work now happens (#2988). Worse, it
# announced itself as an environment limitation, so the skip read as expected
# and nobody looked. Ask git whether this is a work tree instead of guessing
# from the shape of `.git`.
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    check "Android demo assembleDebug" "SKIP" "not a git repository"
elif [[ ! -x "./gradlew" ]]; then
    check "Android demo assembleDebug" "SKIP" "gradlew not present in checkout"
elif [[ ! -d "samples/android-demo" ]]; then
    # The gradle dry-run cannot configure :samples:android-demo without the
    # module tree on disk. A lean / sparse clone that omits samples/ SKIPs with
    # a clear note rather than running a doomed dry-run (#2370).
    check "Android demo assembleDebug" "SKIP" "samples/android-demo not in checkout (lean/sparse clone)"
else
    # Pick a diff base that actually exists. A full clone has HEAD~1 (the CI
    # case — behaviour preserved). A shallow (`--depth 1`) clone does NOT, so
    # `git diff HEAD~1 HEAD` would error → empty result → a misleading
    # "No source changed" PASS. Fall back to origin/main when it diverges, and
    # always fold in uncommitted working-tree edits (the batch-agent case).
    # Never silently no-op just because the base ref was unavailable (#2370).
    SRC_FILTER='^sceneview/src|^arsceneview/src|^samples/'
    CHANGED_SRC=""
    DIFF_BASE=""
    if git rev-parse --verify -q HEAD~1 >/dev/null 2>&1; then
        DIFF_BASE="HEAD~1"
        CHANGED_SRC=$(git diff --name-only HEAD~1 HEAD 2>/dev/null | grep -E "$SRC_FILTER" || true)
    elif git rev-parse --verify -q origin/main >/dev/null 2>&1 \
         && [[ "$(git rev-parse origin/main 2>/dev/null)" != "$(git rev-parse HEAD 2>/dev/null)" ]]; then
        DIFF_BASE="origin/main"
        CHANGED_SRC=$(git diff --name-only origin/main HEAD 2>/dev/null | grep -E "$SRC_FILTER" || true)
    fi
    # Uncommitted working-tree edits are the common lean-clone signal — fold
    # them in regardless of which (if any) commit base was found.
    WT_CHANGED=$(git diff --name-only HEAD 2>/dev/null | grep -E "$SRC_FILTER" || true)
    CHANGED_SRC=$(printf '%s\n%s\n' "$CHANGED_SRC" "$WT_CHANGED" | sort -u | grep -v '^$' || true)

    if [[ -n "$CHANGED_SRC" ]]; then
        trace "gradle :samples:android-demo:assembleDebug --dry-run"
        if ./gradlew :samples:android-demo:assembleDebug --dry-run > /dev/null 2>&1; then
            check "Android demo assembleDebug (dry-run)" "PASS" "Gradle task resolved"
        else
            check "Android demo assembleDebug (dry-run)" "FAIL" "Gradle task resolution failed"
        fi
    elif [[ -z "$DIFF_BASE" ]] && [[ "$(git rev-parse --is-shallow-repository 2>/dev/null)" == "true" ]]; then
        # Shallow clone, no HEAD~1, origin/main == HEAD, clean tree: we genuinely
        # cannot tell what changed. SKIP honestly instead of claiming "no change".
        check "Android demo assembleDebug" "SKIP" "shallow clone, no diff base & clean tree"
    else
        check "Android demo assembleDebug" "PASS" "No SDK/sample source changed"
    fi
fi

# ─── Summary ──────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}=== Impact Check Summary ===${NC}"
echo ""

# Disable the ERR trap before the structured exit — we own the exit code now.
trap - ERR

SUMMARY_DETAIL=""
[[ "$SKIPS" -gt 0 ]] && SUMMARY_DETAIL=" ($SKIPS skipped — likely sparse-checkout)"

if [[ "$ISSUES" -eq 0 ]] && [[ "$WARNINGS" -eq 0 ]]; then
    echo -e "${GREEN}ALL CLEAR — no cross-file inconsistencies${NC}${SUMMARY_DETAIL}"
    exit 0
elif [[ "$ISSUES" -eq 0 ]]; then
    echo -e "${YELLOW}PASS with $WARNINGS warning(s)${NC}${SUMMARY_DETAIL}"
    exit 0
else
    echo -e "${RED}$ISSUES blocker(s) found${NC}${SUMMARY_DETAIL}"
    if [[ "$FAIL_ON_ERROR" == "true" ]]; then
        echo -e "${RED}--fail set — exiting 1${NC}"
        exit 1
    else
        echo -e "${YELLOW}--fail not set — exiting 0 (pass --fail to enforce)${NC}"
        exit 0
    fi
fi
