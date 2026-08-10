#!/usr/bin/env bash
#
# check-doc-drift.sh — flag when a public-API change is not mirrored in the docs.
#
# WHY THIS EXISTS
# ---------------
# SceneView is an AI-first SDK: the *prose* docs (llms.txt, KDoc, docs/docs/*,
# recipes) are the product surface an AI reads to generate user code. When a
# public API changes and that prose lags, an AI confidently emits stale code.
# `sync-versions.sh` keeps version *numbers* aligned and `check-llms-drift.sh`
# guards the llms.txt *mirrors* structurally — but NOTHING checked that the
# prose CONTENT follows the API. `impact-check.sh §2` only grep-matched the
# node names from a single file (SceneScope.kt). This script is the dedicated,
# from-scratch detector across all four doc surfaces.
#
# FUTURE SIGNAL (#2723, note only — no coupling today): the committed
# `<module>/api/*.api` dumps produced by binary-compatibility-validator are a
# DETERMINISTIC public-API delta — a PR that changed `.api` files is a
# high-precision "public surface moved" marker this heuristic could consume
# later (e.g. escalate a doc-drift WARN when the `.api` diff is non-empty but
# the doc surfaces are untouched). Intentionally NOT wired here yet: the
# `.api` gate already blocks per-PR on its own, and keeping this detector
# git-diff-only avoids a hard dependency on the Gradle dump being present.
#
# TWO MODES
# ---------
#   (default)  Diff mode — advisory. Looks only at what the current
#              diff/PR changed, and warns when public API moved without the
#              doc surfaces moving with it. Deterministic, no LLM, sub-second.
#              Exit 0 always (report-only) unless `--fail` is passed.
#
#   --audit    Repo-wide worklist mode — emits a structured, machine-readable
#              checklist of *candidate* drift across the whole repo (composables
#              missing from llms.txt, public symbols missing KDoc, recipe symbol
#              references). This is the starting map for the weekly Claude
#              `doc-audit.yml` agent, which then REASONS over the prose and
#              proposes patches. The script deliberately does not judge prose
#              semantics — that is the agent's job.
#
# USAGE
#   bash .claude/scripts/check-doc-drift.sh                 # diff mode, advisory
#   bash .claude/scripts/check-doc-drift.sh --base main     # diff vs an explicit base
#   bash .claude/scripts/check-doc-drift.sh --fail          # exit 1 on any WARN (opt-in gate)
#   bash .claude/scripts/check-doc-drift.sh --audit         # repo-wide worklist
#
# ENV
#   DOC_DRIFT_FILES   newline/space list of changed files — bypasses git entirely
#   DOC_DRIFT_BASE    base ref for the diff (same as --base)
#   GITHUB_BASE_REF   (set by GitHub Actions on PRs) used to resolve the diff range
#   GITHUB_STEP_SUMMARY  (set by GitHub Actions) findings are appended here too
#
# ROBUSTNESS CONTRACT (mirrors impact-check.sh #1782/#1786)
#   - Missing inputs [SKIP] rather than die under `set -euo pipefail`.
#   - Default exit code 0 (advisory); `--fail` opts into non-zero.
#
# KNOWN HEURISTIC LIMITS (acceptable: this is a non-blocking advisory, and the
# weekly doc-audit agent is the semantic backstop)
#   - A local `val`/`var` declared inside a changed function body matches the
#     public-decl regex, so a pure body refactor can produce a spurious WARN.
#   - A param added to an EXISTING multi-line signature is missed (the `fun`
#     line itself is not in the hunk). Both are by-design trade-offs.

set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

MODE="diff"
FAIL_ON_WARN=false
BASE_REF="${DOC_DRIFT_BASE:-}"

for arg in "$@"; do
    case "$arg" in
        --audit) MODE="audit" ;;
        --fail)  FAIL_ON_WARN=true ;;
        --base)  ;;                       # value consumed below
        --base=*) BASE_REF="${arg#*=}" ;;
        -h|--help) sed -n '2,46p' "$0"; exit 0 ;;
        *) if [[ "${PREV:-}" == "--base" ]]; then BASE_REF="$arg"; fi ;;
    esac
    PREV="$arg"
done

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

WARN_COUNT=0
INFO_COUNT=0

note() { # level message [detail]
    local level="$1" msg="$2" detail="${3:-}"
    case "$level" in
        WARN) printf "${YELLOW}⚠ WARN${NC} %s\n" "$msg"; WARN_COUNT=$((WARN_COUNT + 1)) ;;
        INFO) printf "${CYAN}ℹ INFO${NC} %s\n" "$msg"; INFO_COUNT=$((INFO_COUNT + 1)) ;;
        OK)   printf "${GREEN}✓ OK${NC}   %s\n" "$msg" ;;
    esac
    [[ -n "$detail" ]] && printf "        %s\n" "$detail"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" && "$level" != "OK" ]]; then
        { printf -- "- **%s** — %s" "$level" "$msg"
          [[ -n "$detail" ]] && printf " (%s)" "$detail"
          printf "\n"; } >> "$GITHUB_STEP_SUMMARY"
    fi
}

# Public-API source roots (the surfaces whose change should be mirrored in docs).
API_DIRS=(
    sceneview/src/main
    arsceneview/src/main
    sceneview-core/src/commonMain
    # `commonMain` only: it is the whole published surface of sceneview-compose
    # (the module runs `explicitApi()`, and the per-platform `actual`s add no
    # declaration a consumer can name). Adding the platform source sets would
    # flag every internal helper as a docs-worthy API change.
    sceneview-compose/src/commonMain
    SceneViewSwift/Sources
    sceneview-web/src
)
# Doc surfaces, by category.
DOC_LLMS="llms.txt"
DOC_PAGES_DIR="docs/docs"
DOC_RECIPES_DIR="samples/recipes"

# Regex for a *public* Kotlin/Swift declaration line (Kotlin defaults to public,
# so we flag any decl that is NOT explicitly private/internal/protected).
public_decl_re='(@Composable[[:space:]]+)?(fun|val|var|class|interface|object|enum[[:space:]]+class)[[:space:]]+[A-Za-z]'
swift_decl_re='public[[:space:]]+(func|var|let|struct|class|enum|protocol)[[:space:]]+[A-Za-z]'
# A line is "non-public" only when a visibility modifier word is followed (maybe
# via other modifiers like `inline`/`open`) by a DECLARATION keyword — i.e.
# `private fun`, `internal val`, `protected open class`. This deliberately does
# NOT match "internal" inside a comment ("// not internal yet") or a param name
# ("internal: Boolean"), which would otherwise filter out a genuinely public
# decl and cause a false negative (review #2333 nit #2).
nonpublic_re='(^|[^[:alnum:]_])(private|internal|protected)[[:space:]]+([a-z]+[[:space:]]+)*(fun|val|var|class|interface|object|enum|func|let|struct|protocol)([[:space:]]|$)'

# ──────────────────────────────────────────────────────────────────────────
# DIFF MODE — advisory
# ──────────────────────────────────────────────────────────────────────────
resolve_range() {
    # Echoes a git diff range ("A B") for the current PR/branch. We use a
    # TWO-dot range (FETCH_HEAD HEAD) on purpose: on a `pull_request` event the
    # checkout is the merge ref, so two-dot already excludes main-only changes
    # and is correct. A three-dot (merge-base) range would be cleaner for a
    # diverged LOCAL branch, but the shallow `--depth=1` fetch has no common
    # ancestor to compute it. CAVEAT: running this locally on a branch whose
    # main has diverged can therefore show a few main-only files as "changed"
    # → at worst a spurious advisory WARN. Harmless for a non-blocking check.
    if [[ -n "$BASE_REF" ]]; then
        git fetch --no-tags --depth=1 origin "$BASE_REF" >/dev/null 2>&1 || true
        if git rev-parse --verify -q "origin/$BASE_REF" >/dev/null; then echo "origin/$BASE_REF HEAD"; return; fi
        if git rev-parse --verify -q "$BASE_REF" >/dev/null; then echo "$BASE_REF HEAD"; return; fi
    fi
    if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
        git fetch --no-tags --depth=1 origin "$GITHUB_BASE_REF" >/dev/null 2>&1 || true
        if git rev-parse --verify -q FETCH_HEAD >/dev/null; then echo "FETCH_HEAD HEAD"; return; fi
    fi
    # Local fallback: last commit. Uncommitted work is folded in separately.
    if git rev-parse --verify -q HEAD~1 >/dev/null 2>&1; then echo "HEAD~1 HEAD"; return; fi
    echo ""   # single-commit repo → no range
}

changed_files() {
    if [[ -n "${DOC_DRIFT_FILES:-}" ]]; then tr ' ' '\n' <<<"$DOC_DRIFT_FILES" | sed '/^$/d'; return; fi
    local range; range="$(resolve_range)"
    # shellcheck disable=SC2086  # $range is intentionally word-split into "A B".
    { [[ -n "$range" ]] && git diff --name-only $range 2>/dev/null || true
      git diff --name-only 2>/dev/null || true          # unstaged
      git diff --name-only --cached 2>/dev/null || true # staged
    } | sort -u
}

run_diff_mode() {
    echo -e "${CYAN}=== Doc-drift check (diff / advisory) ===${NC}"
    local files; files="$(changed_files)"
    if [[ -z "$files" ]]; then note OK "No changed files detected — nothing to check."; return; fi

    # Did this diff touch a public-API source dir?
    local api_changed=""
    for d in "${API_DIRS[@]}"; do
        api_changed="$api_changed$(grep -E "^$d/.*\.(kt|swift)$" <<<"$files" || true)
"
    done
    api_changed="$(echo "$api_changed" | sed '/^$/d' | sort -u)"

    if [[ -z "$api_changed" ]]; then
        note OK "No public-API source files in this change — doc surfaces not required."
        return
    fi

    echo "  Public-API source files changed:"
    sed 's/^/    • /' <<<"$api_changed"

    # Did those files actually change a *public declaration* (vs. an internal
    # body tweak)? This keeps the warning quiet for behaviour-only refactors.
    local range; range="$(resolve_range)"
    local decl_changed=false
    if [[ -n "${DOC_DRIFT_FILES:-}" ]]; then
        decl_changed=true   # explicit file injection → no hunk to inspect, check docs
    else
        # The hunk delta MUST be computed over the same universe as
        # `changed_files()` — commit range UNION working tree. A commit range
        # alone excludes uncommitted work, so a developer running this by hand
        # before committing (the case CLAUDE.md's quality list points at) got a
        # false "no public declaration was added/removed/retyped": the file list
        # saw the working tree, the decl regex never did (#3008).
        # shellcheck disable=SC2086  # $range is word-split into "A B"; $api_changed is a file list.
        local apidiff; apidiff="$(
            { [[ -n "$range" ]] && git diff $range -- $api_changed 2>/dev/null || true
              git diff -- $api_changed 2>/dev/null || true          # unstaged
              git diff --cached -- $api_changed 2>/dev/null || true # staged
            })"
        if [[ -z "$apidiff" ]]; then
            decl_changed=true   # can't compute a hunk diff → assume worth checking
        elif grep -E "^[+-]" <<<"$apidiff" \
               | grep -vE "$nonpublic_re" \
               | grep -qE "$public_decl_re|$swift_decl_re"; then
            decl_changed=true
        fi
    fi

    if [[ "$decl_changed" == false ]]; then
        note OK "Public-API files changed, but no public declaration was added/removed/retyped."
        return
    fi

    # Surface 1 — llms.txt (AI-first core). WARN if untouched.
    if grep -qx "$DOC_LLMS" <<<"$files"; then
        note OK "llms.txt was updated alongside the API change."
    else
        note WARN "Public API changed but \`llms.txt\` was not updated." \
            "Update the relevant section of llms.txt so AI-generated code stays correct."
    fi

    # Surface 2 — KDoc. INFO reminder (deep check is the weekly agent + /document).
    if grep -qE "\.kt$" <<<"$api_changed"; then
        note INFO "Kotlin public API changed — verify KDoc on new/changed symbols." \
            "Run \`/document\` to generate/update KDoc for the changed public APIs."
    fi

    # Surface 3 — MkDocs pages. INFO if none touched.
    if grep -qE "^$DOC_PAGES_DIR/.*\.md$" <<<"$files"; then
        note OK "docs/docs/* pages were updated."
    else
        note INFO "No \`docs/docs/*\` page updated — check cheatsheet/quickstart/migration/platforms." \
            "Only needed if the change is developer-visible (new param, renamed symbol, new pattern)."
    fi

    # Surface 4 — recipes + sample READMEs. INFO if a new public symbol appeared.
    if grep -qE "^$DOC_RECIPES_DIR/.*\.md$" <<<"$files" || grep -qiE "README\.md$" <<<"$files"; then
        note OK "Recipes / README updated."
    else
        note INFO "No recipe or README updated — check if a new public composable/factory needs an example." \
            "$DOC_RECIPES_DIR/*.md show user-facing code patterns."
    fi
}

# ──────────────────────────────────────────────────────────────────────────
# AUDIT MODE — repo-wide worklist for the weekly Claude agent
# ──────────────────────────────────────────────────────────────────────────
run_audit_mode() {
    echo -e "${CYAN}=== Doc-drift AUDIT worklist (repo-wide) ===${NC}"
    echo "# This is a candidate-drift map for the weekly doc-audit agent."
    echo "# It lists deterministic signals; the agent reasons over the prose."
    echo

    # 1. Public composables / node types not mentioned anywhere in llms.txt.
    echo "## Composables / node types missing from llms.txt"
    if [[ -f "$DOC_LLMS" ]]; then
        local symbols
        symbols="$(grep -rhoE '@Composable[[:space:]]+fun[[:space:]]+[A-Z][A-Za-z]+' \
                     sceneview/src/main arsceneview/src/main 2>/dev/null \
                     | sed -E 's/.*fun[[:space:]]+//' | sort -u || true)"
        symbols="$symbols
$(find sceneview/src/main/java/io/github/sceneview/node arsceneview/src/main \
            -maxdepth 4 -name '*Node.kt' -exec basename {} .kt \; 2>/dev/null \
            | grep -Ev '^(Node|RenderableNode)$' | sort -u || true)"
        local missing=""
        for s in $(echo "$symbols" | sed '/^$/d' | sort -u); do
            grep -q "$s" "$DOC_LLMS" 2>/dev/null || missing="$missing $s"
        done
        if [[ -n "$missing" ]]; then note WARN "Symbols absent from llms.txt:" "$missing"
        else echo "  (none)"; fi
    else
        note INFO "llms.txt not found in this checkout — SKIP."
    fi
    echo

    # 2. Public Kotlin declarations in the library modules with no KDoc above them.
    echo "## Public declarations likely missing KDoc (sampled)"
    while IFS= read -r f; do
        [[ -f "$f" ]] || continue
        # A public decl line whose preceding non-blank line is not a doc/annotation.
        awk '
          /^[[:space:]]*(@Composable[[:space:]]+)?(public[[:space:]]+)?(fun|class|interface|object|val|var)[[:space:]]+[A-Za-z]/ &&
          $0 !~ /private|internal|protected|override/ {
            if (prev !~ /\*\/|^[[:space:]]*\*|^[[:space:]]*@/) print FILENAME ":" NR ": " $0
          }
          { prev = $0 }
        ' "$f" 2>/dev/null || true
    done < <(find sceneview/src/main arsceneview/src/main -name '*.kt' 2>/dev/null | head -400) \
      | head -40 | sed 's/^/  /' || true
    echo "  (list truncated to 40 — full scan is the agent's job)"
    echo

    # 3. Recipe files referencing symbols — the agent verifies each still exists.
    echo "## Recipe files to verify against current API"
    if [[ -d "$DOC_RECIPES_DIR" ]]; then
        find "$DOC_RECIPES_DIR" -name '*.md' 2>/dev/null | sed 's/^/  • /' || echo "  (none)"
    else
        echo "  ($DOC_RECIPES_DIR not in checkout)"
    fi
    echo

    echo "## MkDocs pages"
    if [[ -d "$DOC_PAGES_DIR" ]]; then
        find "$DOC_PAGES_DIR" -name '*.md' 2>/dev/null | sed 's/^/  • /' || true
    fi
}

# ──────────────────────────────────────────────────────────────────────────
if [[ "$MODE" == "audit" ]]; then
    run_audit_mode
    exit 0
fi

run_diff_mode
echo
echo -e "${CYAN}--- Summary ---${NC}"
echo "  WARN: $WARN_COUNT   INFO: $INFO_COUNT"
if [[ "$WARN_COUNT" -gt 0 ]]; then
    echo -e "${YELLOW}Doc-drift is advisory by default — review the surfaces above.${NC}"
    if [[ "$FAIL_ON_WARN" == true ]]; then exit 1; fi
fi
exit 0
