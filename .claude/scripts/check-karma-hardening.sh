#!/usr/bin/env bash
#
# check-karma-hardening.sh — every Kotlin/JS browser module must ship the same
# hardened Karma config, and must ask for the launcher that config extends.
#
# WHY THIS EXISTS
# ---------------
# The blocking `Build web targets` leg went red five times on #3189 with the
# BROWSER dying rather than a test failing — `Test running process exited
# unexpectedly`, zero test results, and no Karma or Chrome output anywhere in a
# ~4500-line job log. Gradle reported the `jsBrowserTest` inputs byte-identical
# to `main`, so branch content was ruled out mechanically. The cause was never
# observed, because nothing captured it.
#
# `sceneview-core` had no `karma.config.d` at all, no `--no-sandbox`, and no
# activity timeout, while `sceneview-web` had a `karma.config.d` for an
# unrelated reason (the Filament stub) and equally no launcher flags. Both run
# behind the SAME blocking leg on the SAME runner, so hardening one and not the
# other only moves which module dies.
#
# This gate is deliberately about the SHAPE of the config, not about whether the
# suite passes — a green suite proves nothing about a launch that never happened.
# It fails when:
#
#   1. a Kotlin/JS browser module has no `karma.config.d/browser-hardening.js`
#   2. two copies of that file have drifted apart
#   3. the file lost a flag or a timeout it exists to carry
#   4. a module's `build.gradle.kts` does not call `useChromeHeadless()`, which
#      is what installs the launcher `base: "ChromeHeadless"` extends
#   5. a module's `build.gradle.kts` does not declare `karma.config.d` as a task
#      input — without it the hardening is real on disk and skippable in the
#      build, which is the false-green half of this gate (see below)
#
# …and it exits 2 — never 0 — if it discovered no modules at all. A discovery
# step that silently found nothing is the rubber-stamp failure this repo has
# been bitten by before (`tiers.ts` parsing to zero, `.cursorrules` outside
# every glob), so it refuses instead of passing.
#
# Usage: bash .claude/scripts/check-karma-hardening.sh [--root <dir>]
# Exit:  0 clean · 1 findings · 2 could not run the discovery (never a pass)

set -uo pipefail

ROOT=""
if [[ "${1:-}" == "--root" ]]; then
    ROOT="${2:-}"
    if [[ -z "$ROOT" ]]; then
        echo "check-karma-hardening: --root needs a directory" >&2
        exit 2
    fi
else
    ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
        echo "check-karma-hardening: not in a git repository and no --root given" >&2
        exit 2
    }
fi

HARDENING_REL="karma.config.d/browser-hardening.js"

# Every token the hardened config exists to carry. Losing any one of them is a
# silent regression to the state that produced the five red runs, so each is
# asserted by name rather than by "the file is present".
REQUIRED_TOKENS=(
    "--no-sandbox"
    "--disable-dev-shm-usage"
    "--disable-gpu"
    "captureTimeout"
    "browserNoActivityTimeout"
    "browserDisconnectTimeout"
    "browserDisconnectTolerance"
    "retryLimit"
    "captureConsole"
    "browserConsoleLogOptions"
)

findings=0
finding() { printf '  %s\n' "$1"; findings=$((findings + 1)); }

# ── Discovery ───────────────────────────────────────────────────────────────
# A module is in scope when its build script declares a Kotlin/JS target AND
# configures `browser` — i.e. it can produce a `jsBrowserTest` task. The two
# conditions are checked on the whole file rather than on one line because the
# target is a nested DSL block; a build script that mentions `browser` for an
# unrelated reason would be a false positive, which is the safe direction here
# (it demands hardening that costs nothing) and is noted rather than hidden.
modules=()
while IFS= read -r gradle_file; do
    [[ -z "$gradle_file" ]] && continue
    grep -qE '^[[:space:]]*js[[:space:]]*[({]' "$gradle_file" || continue
    grep -qE '\bbrowser\b' "$gradle_file" || continue
    modules+=("$(dirname "$gradle_file")")
done < <(find "$ROOT" \
    \( -name node_modules -o -name build -o -name .git -o -name .gradle \) -prune -o \
    -name build.gradle.kts -print 2>/dev/null | sort)

if [[ ${#modules[@]} -eq 0 ]]; then
    echo "check-karma-hardening: discovered 0 Kotlin/JS browser modules under $ROOT." >&2
    echo "  A discovery that found nothing is not a tree with nothing to check." >&2
    echo "  Either the build-script shape changed or --root is wrong. Refusing." >&2
    exit 2
fi

# ── 1. Every module carries the file, and 2. every copy is identical ────────
canonical=""
canonical_module=""
for module in "${modules[@]}"; do
    rel="${module#"$ROOT"/}"
    hardening="$module/$HARDENING_REL"
    if [[ ! -f "$hardening" ]]; then
        finding "$rel: no $HARDENING_REL — a Kotlin/JS browser module with no Karma hardening"
        continue
    fi
    if [[ -z "$canonical" ]]; then
        canonical="$hardening"
        canonical_module="$rel"
    elif ! cmp -s "$canonical" "$hardening"; then
        finding "$rel/$HARDENING_REL has drifted from $canonical_module/$HARDENING_REL — the copies must be byte-identical"
    fi
done

# ── 3. The file still carries what it exists to carry ───────────────────────
if [[ -n "$canonical" ]]; then
    for token in "${REQUIRED_TOKENS[@]}"; do
        grep -qF -- "$token" "$canonical" \
            || finding "$canonical_module/$HARDENING_REL no longer sets \`$token\`"
    done
fi

# ── 4. The build script asks for the launcher the config extends ────────────
# `base: "ChromeHeadless"` resolves through karma-chrome-launcher, and it is
# `useChromeHeadless()` that puts that launcher on the test runtime. Without it
# the hardened launcher is a name pointing at nothing.
for module in "${modules[@]}"; do
    rel="${module#"$ROOT"/}"
    grep -q 'useChromeHeadless' "$module/build.gradle.kts" \
        || finding "$rel/build.gradle.kts does not call useChromeHeadless() — the hardened launcher has no base"
done

# ── 5. The build script makes the config part of the task's IDENTITY ────────
# The files under `karma.config.d/` are appended into the karma.conf.js the test
# task generates, so they change what the run does — but Gradle does not track
# them as an input on its own. Measured on this repo before the fix: pointing
# `config.browsers` at a launcher that does not exist and re-running gave
# `BUILD SUCCESSFUL` with `jsBrowserTest UP-TO-DATE`, against a stale generated
# config; the same break under `--rerun-tasks` failed in 4 s with `Cannot load
# browser … it is not registered!`.
#
# Scope, measured rather than assumed: this is a LOCAL false green. An earlier
# draft of this comment blamed the Gradle build cache CI restores; that was
# wrong and is withdrawn — `--info` reports `Caching disabled for task
# ':sceneview-core:jsBrowserTest' because: Caching has been disabled for the
# task`, and a fresh CI checkout has no up-to-date state to reuse either. What
# an untracked config breaks is the only way this file is ever verified: #3192
# workstream 1 exists BECAUSE the fix needs local Gradle, and the default local
# run silently did not exercise it. Checks 1–4 all pass on a tree where the
# hardening never executes; this is the one that doesn't.
for module in "${modules[@]}"; do
    rel="${module#"$ROOT"/}"
    grep -qE 'inputs\.dir\([^)]*karma\.config\.d' "$module/build.gradle.kts" \
        || finding "$rel/build.gradle.kts does not declare karma.config.d as a task input — the hardening is skippable by an up-to-date check and by the build cache"
done

if [[ $findings -eq 0 ]]; then
    echo "check-karma-hardening: OK — ${#modules[@]} Kotlin/JS browser module(s), identical hardened Karma config."
    exit 0
fi

echo "::error::Kotlin/JS browser modules disagree on their Karma hardening." >&2
echo "" >&2
echo "  The hardened config is $HARDENING_REL, and every Kotlin/JS browser" >&2
echo "  module must carry a byte-identical copy. Copy it rather than editing" >&2
echo "  one side — see the file's own header for why (#3192 workstream 1)." >&2
exit 1
