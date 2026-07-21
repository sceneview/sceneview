#!/usr/bin/env bash
# collate-ios-demos.sh — Regenerate GeneratedScenes.swift from per-demo
# *Scene.swift files under `SceneViewDemo/Views/Demos/Scenes/`.
#
# Each *Scene.swift file carries structured directive comments in its header:
#
#   // @sceneId     <slug>         stable deep-link id matching Android DemoRegistry
#   // @title       <Human title>
#   // @subtitle    <One-line description>
#   // @icon        <SF Symbol name>
#   // @category    <category>     one of: basics3D|lighting|content|interaction|advanced|ar
#   // @available   <true|false>   false → "Coming soon" card in SamplesTab
#   // @iosOnly     <true|false>   (optional, default false) wraps item in #if os(iOS)
#   // @status      <value>        (optional) one of: working|knownIssue|comingSoon|inReview
#
# `@status` (optional — mirrors Android's 4-state `DemoStatus`, #2802):
#   - Default when omitted: `working` if @available true, `comingSoon` if @available false.
#     No pre-existing *Scene.swift file needs an edit to keep working after this directive
#     was added.
#   - Cross-validated against @available so the two directives can't contradict each other:
#     `working`/`knownIssue`/`inReview` all require @available true (they claim a real
#     destination exists); `comingSoon` requires @available false (it claims none does).
#   - Drives the badge rendered on the Samples-tab card (SamplesTab.swift's `StatusBadge`:
#     "Preview" for knownIssue, "In review" for inReview, "Soon" for comingSoon; `working`
#     renders no badge) — the iOS mirror of Android's `DemoListScreen.kt` `StatusChip`.
#
# The script emits `GeneratedScenes.swift` which:
#   - Provides `GeneratedScenes.all() -> [DemoItem]`  — consumed by SamplesTab
#   - Provides `GeneratedScenes.allowedIds: Set<String>` — every scene id, the
#     generated half of `DemoDeepLinkRegistry.allowedIds` (the deep-link gate)
#   - Provides `GeneratedScenes.destination(for:) -> AnyView?` — resolves a
#     scene id to its view (`nil` for coming-soon / non-iOS AR → placeholder)
#   - Is .gitignore'd and regenerated before every Xcode build via a
#     "Collate iOS demos" Run Script phase in SceneViewDemo.xcodeproj
#
# Because `allowedIds` and the id→view map are generated from the SAME
# `@sceneId` directives that drive the Samples tab, the three deep-link
# surfaces (list, gate, resolver) can no longer drift apart — the root cause
# that silently dropped 12 ids before #2800. Adding a demo = adding one Scene
# file; `DemoDeepLinkRegistry` only keeps a small hand-maintained residual for
# AR ids that have no Scene file yet, plus legacy aliases.
#
# Usage:
#   bash samples/ios-demo/scripts/collate-ios-demos.sh           # write
#   bash samples/ios-demo/scripts/collate-ios-demos.sh --check   # exit non-zero if stale
#
# Adding a new demo:
#   1. Create Views/Demos/Scenes/<Name>Scene.swift with the six directives.
#   2. Re-run this script (Xcode does it automatically on next build).
#   3. No other file needs editing.
#
# Idempotent: running twice produces byte-identical output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IOS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SCENES_DIR="$IOS_ROOT/SceneViewDemo/Views/Demos/Scenes"
OUT_FILE="$IOS_ROOT/SceneViewDemo/Views/Demos/GeneratedScenes.swift"

CHECK_MODE=false
case "${1:-}" in
    --check) CHECK_MODE=true ;;
    "") ;;
    *) echo "Usage: $0 [--check]" >&2; exit 1 ;;
esac

[ -d "$SCENES_DIR" ] || { echo "Error: $SCENES_DIR not found." >&2; exit 1; }

# ─── 1. Discover *Scene.swift files and parse directives ──────────────────
TMP_META="$(mktemp)"
trap 'rm -f "$TMP_META"' EXIT

shopt -s nullglob
scene_count=0
for f in "$SCENES_DIR"/*Scene.swift; do
    base="$(basename "$f")"

    scene_id=$(grep -m1 '// @sceneId' "$f" | sed -E 's|.*// @sceneId[[:space:]]+||; s/[[:space:]]+$//')
    title=$(grep -m1 '// @title' "$f" | sed -E 's|.*// @title[[:space:]]+||; s/[[:space:]]+$//')
    subtitle=$(grep -m1 '// @subtitle' "$f" | sed -E 's|.*// @subtitle[[:space:]]+||; s/[[:space:]]+$//')
    icon=$(grep -m1 '// @icon' "$f" | sed -E 's|.*// @icon[[:space:]]+||; s/[[:space:]]+$//')
    category=$(grep -m1 '// @category' "$f" | sed -E 's|.*// @category[[:space:]]+||; s/[[:space:]]+$//')
    available=$(grep -m1 '// @available' "$f" | sed -E 's|.*// @available[[:space:]]+||; s/[[:space:]]+$//')
    ios_only=$(grep -m1 '// @iosOnly' "$f" 2>/dev/null | sed -E 's|.*// @iosOnly[[:space:]]+||; s/[[:space:]]+$//' || echo "false")
    status=$(grep -m1 '// @status' "$f" 2>/dev/null | sed -E 's|.*// @status[[:space:]]+||; s/[[:space:]]+$//' || echo "")

    for field in scene_id title subtitle icon category available; do
        if [ -z "${!field}" ]; then
            echo "Error: $base is missing // @$field directive." >&2
            exit 1
        fi
    done

    case "$available" in
        true|false) ;;
        *) echo "Error: $base @available must be 'true' or 'false', got '$available'." >&2; exit 1 ;;
    esac

    case "$category" in
        basics3D|lighting|content|interaction|advanced|ar) ;;
        *) echo "Error: $base @category '$category' is not one of: basics3D lighting content interaction advanced ar." >&2; exit 1 ;;
    esac

    # ios_only defaults to false when missing
    [ -z "$ios_only" ] && ios_only="false"
    case "$ios_only" in
        true|false) ;;
        *) ios_only="false" ;;
    esac

    # @status defaults from @available when omitted (#2802): `working` for an
    # available scene, `comingSoon` for one that isn't — so none of the
    # pre-#2802 *Scene.swift files need an edit to keep collating correctly.
    if [ -z "$status" ]; then
        if [ "$available" = "true" ]; then
            status="working"
        else
            status="comingSoon"
        fi
    fi

    case "$status" in
        working|knownIssue|inReview|comingSoon) ;;
        *) echo "Error: $base @status '$status' is not one of: working knownIssue comingSoon inReview." >&2; exit 1 ;;
    esac

    # Cross-validate @status against @available — a scene can't claim both a
    # real destination and no destination at the same time. This is the
    # invariant DemoItem's own precondition enforces at runtime (#2802); the
    # collator catches it at build time instead, with a file name attached.
    case "$status" in
        working|knownIssue|inReview)
            if [ "$available" != "true" ]; then
                echo "Error: $base has @status '$status' but @available false — " \
                     "'$status' requires a real destination (@available true). " \
                     "Use @status comingSoon for a scene with no destination." >&2
                exit 1
            fi
            ;;
        comingSoon)
            if [ "$available" != "false" ]; then
                echo "Error: $base has @status comingSoon but @available true — " \
                     "a scene with a real destination can't be comingSoon. " \
                     "Use @status working, knownIssue, or inReview instead." >&2
                exit 1
            fi
            ;;
    esac

    # TAB-separated: sceneId title subtitle icon category available iosOnly status
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$scene_id" "$title" "$subtitle" "$icon" "$category" "$available" "$ios_only" "$status" >> "$TMP_META"
    scene_count=$((scene_count + 1))
done

if [ "$scene_count" -eq 0 ]; then
    echo "Error: no *Scene.swift files found under $SCENES_DIR." >&2
    exit 1
fi

# Detect duplicate ids.
DUPES=$(cut -f1 "$TMP_META" | sort | uniq -d)
if [ -n "$DUPES" ]; then
    echo "Error: duplicate scene ids:" >&2
    echo "$DUPES" >&2
    exit 1
fi

# Sort by sceneId for a stable diff.
SORTED_META="$(mktemp)"
trap 'rm -f "$TMP_META" "$SORTED_META"' EXIT
sort -k1,1 "$TMP_META" > "$SORTED_META"

# ─── 2. Map category/status slugs → Swift enum values ───────────────────
category_enum() {
    case "$1" in
        basics3D)    printf '.basics3D' ;;
        lighting)    printf '.lighting' ;;
        content)     printf '.content' ;;
        interaction) printf '.interaction' ;;
        advanced)    printf '.advanced' ;;
        ar)          printf '.ar' ;;
        *) echo "Error: unknown category '$1'." >&2; exit 1 ;;
    esac
}

# Maps to `DemoStatus` (`DemoItem.swift`, #2802). Only called for
# @available true scenes — `comingSoon` never needs the mapping since the
# `comingSoonTitle:` initializer hard-codes `.comingSoon` itself.
status_enum() {
    case "$1" in
        working)    printf '.working' ;;
        knownIssue) printf '.knownIssue' ;;
        inReview)   printf '.inReview' ;;
        *) echo "Error: unknown status '$1' for an available scene." >&2; exit 1 ;;
    esac
}

# ─── 3. Augment sorted meta with Swift type names ───────────────────────
# Extract the `enum <Name>Scene: DemoScene` declaration from each file.
TMP_FULL="$(mktemp)"
trap 'rm -f "$TMP_META" "$SORTED_META" "$TMP_FULL"' EXIT

while IFS=$'\t' read -r scene_id title subtitle icon category available ios_only status; do
    # Find the *Scene.swift file whose @sceneId matches.
    type_name=""
    for f in "$SCENES_DIR"/*Scene.swift; do
        fid=$(grep -m1 '// @sceneId' "$f" | sed -E 's|.*// @sceneId[[:space:]]+||; s/[[:space:]]+$//')
        if [ "$fid" = "$scene_id" ]; then
            type_name=$(grep -oE 'enum [A-Za-z0-9]+Scene\b' "$f" | head -n1 | awk '{print $2}')
            break
        fi
    done
    if [ -z "$type_name" ]; then
        echo "Error: no 'enum <Name>Scene: DemoScene' declaration found for sceneId='$scene_id'." >&2
        exit 1
    fi
    # 9 columns: sceneId title subtitle icon category available iosOnly status typeName
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$scene_id" "$title" "$subtitle" "$icon" "$category" "$available" "$ios_only" "$status" "$type_name" >> "$TMP_FULL"
done < "$SORTED_META"

# ─── 4. Emit GeneratedScenes.swift ───────────────────────────────────────
NEW_FILE="$(mktemp)"
trap 'rm -f "$TMP_META" "$SORTED_META" "$TMP_FULL" "$NEW_FILE"' EXIT

{
cat <<'HEADER'
// AUTO-GENERATED by samples/ios-demo/scripts/collate-ios-demos.sh — DO NOT EDIT.
//
// This file is regenerated from every `*Scene.swift` under
// `SceneViewDemo/Views/Demos/Scenes/`. To add or remove a demo, add or
// remove a Scene file and re-run the collator (Xcode does this automatically
// via the "Collate iOS demos" Run Script phase). Never edit this file by hand.
//
// Scenes are sorted by @sceneId so two parallel PRs adding different demos
// never collide on this file (issue #1872).
import SwiftUI

/// Generated aggregate of every `DemoScene` declared under
/// `Views/Demos/Scenes/`. Drives three surfaces from one source of truth —
/// the `@sceneId` directives — so they can never drift apart:
///   - `all()` — the Samples-tab demo grid (`SamplesTab.allScenes()`).
///   - `allowedIds` — the generated half of `DemoDeepLinkRegistry.allowedIds`
///     (which deep-link ids are accepted).
///   - `destination(for:)` — the generated id→view resolver behind
///     `DemoDeepLinkRegistry.destination(for:)`.
/// Regenerate with `samples/ios-demo/scripts/collate-ios-demos.sh`.
enum GeneratedScenes {
    /// All demo items, sorted by scene id.
    ///
    /// AR items decorated with `@iosOnly true` are guarded with `#if os(iOS)`
    /// so macOS and visionOS builds see only the non-AR subset.
    @MainActor
    static func all() -> [DemoItem] {
        var items: [DemoItem] = []
HEADER

while IFS=$'\t' read -r scene_id title subtitle icon category available ios_only status type_name; do
    cat_enum=$(category_enum "$category")

    # Escape title / subtitle for Swift string literals.
    swift_title=$(printf '%s' "$title" | sed 's/"/\\"/g')
    swift_subtitle=$(printf '%s' "$subtitle" | sed 's/"/\\"/g')

    if [ "$ios_only" = "true" ]; then
        printf '        #if os(iOS)\n'
    fi

    if [ "$available" = "true" ]; then
        status_enum_val=$(status_enum "$status")
        printf '        items.append(DemoItem(\n'
        printf '            title: "%s",\n' "$swift_title"
        printf '            icon: "%s",\n' "$icon"
        printf '            subtitle: "%s",\n' "$swift_subtitle"
        printf '            category: %s,\n' "$cat_enum"
        printf '            status: %s\n' "$status_enum_val"
        printf '        ) { %s.destination })\n' "$type_name"
    else
        printf '        items.append(DemoItem(\n'
        printf '            comingSoonTitle: "%s",\n' "$swift_title"
        printf '            icon: "%s",\n' "$icon"
        printf '            subtitle: "%s",\n' "$swift_subtitle"
        printf '            category: %s\n' "$cat_enum"
        printf '        ))\n'
    fi

    if [ "$ios_only" = "true" ]; then
        printf '        #endif\n'
    fi
done < "$TMP_FULL"

cat <<'ALL_END'
        return items
    }

    /// Every scene id declared under `Views/Demos/Scenes/`, regardless of
    /// `@available`. This is the generated half of
    /// `DemoDeepLinkRegistry.allowedIds`: a deep link to any of these ids is
    /// accepted, and `destination(for:)` returns the scene's view when it is
    /// `@available true`, or `nil` (→ caller shows a coming-soon placeholder)
    /// when it is not. Adding a `*Scene.swift` file grows this set with no
    /// hand edit — the root-cause fix for the registry drift behind #2769.
    static let allowedIds: Set<String> = [
ALL_END

# `allowedIds`: every scene id (available true AND false), sorted by id so
# the diff stays stable and two parallel PRs never collide.
while IFS=$'\t' read -r scene_id title subtitle icon category available ios_only status type_name; do
    printf '        "%s",\n' "$scene_id"
done < "$TMP_FULL"

cat <<'IDS_END'
    ]

    /// Resolve a scene id to its destination view, or `nil` when no
    /// `@available true` scene backs the id on this platform — a coming-soon
    /// scene, or an iOS-only AR scene on a non-iOS build. `nil` is the
    /// caller's signal to present a placeholder; a deep-link id is never
    /// silently dropped. The view mapping is generated from each scene's own
    /// `destination`, so it can never disagree with the Samples tab.
    @MainActor
    static func destination(for id: String) -> AnyView? {
        switch id {
IDS_END

# `destination(for:)`: one case per `@available true` scene. `@available false`
# scenes fall through to `default: return nil` (→ placeholder), never their
# own `EmptyView`. iOS-only scenes are guarded so a non-iOS build returns
# `nil` (→ placeholder) rather than a blank view.
while IFS=$'\t' read -r scene_id title subtitle icon category available ios_only status type_name; do
    [ "$available" = "true" ] || continue
    if [ "$ios_only" = "true" ]; then
        printf '        case "%s":\n' "$scene_id"
        printf '            #if os(iOS)\n'
        printf '            return %s.destination\n' "$type_name"
        printf '            #else\n'
        printf '            return nil\n'
        printf '            #endif\n'
    else
        printf '        case "%s": return %s.destination\n' "$scene_id" "$type_name"
    fi
done < "$TMP_FULL"

cat <<'FOOTER'
        default: return nil
        }
    }
}
FOOTER
} > "$NEW_FILE"

# ─── 5. Apply or check ────────────────────────────────────────────────────
if [ "$CHECK_MODE" = true ]; then
    if [ ! -f "$OUT_FILE" ] || ! cmp -s "$NEW_FILE" "$OUT_FILE"; then
        echo "Error: $OUT_FILE is out of date. Run 'bash samples/ios-demo/scripts/collate-ios-demos.sh'." >&2
        diff -u "$OUT_FILE" "$NEW_FILE" 2>/dev/null | head -40 || true
        exit 1
    fi
    echo "OK: GeneratedScenes.swift in sync with $scene_count scene(s)."
    exit 0
fi

if [ ! -f "$OUT_FILE" ] || ! cmp -s "$NEW_FILE" "$OUT_FILE"; then
    cp "$NEW_FILE" "$OUT_FILE"
    echo "Regenerated $OUT_FILE from $scene_count scene(s)."
else
    echo "OK: $OUT_FILE already in sync with $scene_count scene(s)."
fi
