#!/usr/bin/env bash
# check-demo-id-parity.sh — Android <-> iOS demo-catalog parity gate (#2801,
# part of the iOS-parity tracker #2798).
#
# Compares three surfaces:
#   1. Android's canonical demo ids — read directly from each
#      `*Fragment.kt`'s `id = "..."` under
#      `samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/`
#      (the same field `collate-demos.sh` itself parses; `GeneratedDemos.kt`'s
#      TEXT only lists Kotlin object names, not id strings, so it can't be
#      grepped directly — this script also RUNS collate-demos.sh --kt-only
#      first, purely so its own duplicate-id / missing-directive validation
#      still gates this check even when it runs before the Android Gradle
#      build does).
#   2. iOS's registry — `GeneratedScenes.allowedIds` (regenerated fresh via
#      `collate-ios-demos.sh`, the same collator the Xcode build phase runs)
#      unioned with the hand-maintained `legacyAliases` / `residualIds` read
#      directly from `DemoDeepLinkRegistry.swift`.
#   3. `parity-manifest.yml` — the committed ledger of expected iOS status
#      per Android id (working / stub / android-only).
#
# Exit non-zero when:
#   - an Android id has NEITHER an iOS registry entry NOR a manifest row
#     (the #2769 drift class: a demo silently invisible to iOS)
#   - a manifest row's `id` is no longer a live Android id (stale entry)
#   - a live Android id has no manifest row at all
#   - a manifest row claims `iosStatus: working` but iOS does not actually
#     resolve that id to a real (non-placeholder) destination
#   - a manifest row claims `iosStatus: android-only` but the id IS actually
#     present in iOS's `allowedIds` today (stale — promote it to `stub` or
#     `working`)
#   - a manifest row claims `iosStatus: stub` but the id isn't in iOS's
#     `allowedIds` at all (demoted without updating the manifest)
#
# Usage:
#   bash .claude/scripts/check-demo-id-parity.sh
#
# Testability seams (mirrors check-doc-drift.sh's DOC_DRIFT_FILES pattern) —
# used by test-check-demo-id-parity.sh so the self-test never depends on the
# real working tree:
#   PARITY_ANDROID_FRAG_DIR   override the Android fragments directory
#   PARITY_IOS_REGISTRY       override DemoDeepLinkRegistry.swift's path
#   PARITY_IOS_GENERATED      override GeneratedScenes.swift's path (implies
#                             skipping the real collate-ios-demos.sh run —
#                             the fixture supplies pre-generated content)
#   PARITY_MANIFEST           override parity-manifest.yml's path
#   PARITY_SKIP_ANDROID_COLLATE=1   skip running collate-demos.sh
#   PARITY_SKIP_IOS_COLLATE=1       skip running collate-ios-demos.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

ANDROID_FRAG_DIR="${PARITY_ANDROID_FRAG_DIR:-$REPO_ROOT/samples/android-demo/src/main/java/io/github/sceneview/demo/fragments}"
IOS_REGISTRY="${PARITY_IOS_REGISTRY:-$REPO_ROOT/samples/ios-demo/SceneViewDemo/DemoDeepLinkRegistry.swift}"
IOS_GENERATED="${PARITY_IOS_GENERATED:-$REPO_ROOT/samples/ios-demo/SceneViewDemo/Views/Demos/GeneratedScenes.swift}"
MANIFEST="${PARITY_MANIFEST:-$REPO_ROOT/parity-manifest.yml}"

[ -d "$ANDROID_FRAG_DIR" ] || { echo "Error: Android fragments dir not found: $ANDROID_FRAG_DIR" >&2; exit 1; }
[ -f "$IOS_REGISTRY" ] || { echo "Error: iOS registry not found: $IOS_REGISTRY" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "Error: manifest not found: $MANIFEST" >&2; exit 1; }

# ─── 1. Regenerate + validate the Android collator (dup ids / missing
#     directives fail loudly here too, independent of the Gradle build) ────
if [ "${PARITY_SKIP_ANDROID_COLLATE:-0}" != "1" ]; then
    bash "$REPO_ROOT/samples/android-demo/scripts/collate-demos.sh" --kt-only >/dev/null
fi

# NOTE on `grep` + `set -o pipefail`: `grep -oE PATTERN` exits 1 when it
# finds no match — a perfectly legitimate outcome here (e.g. an empty
# `residualIds` list once every residual id is ported). Under `pipefail`
# that would abort the whole script via `set -e` even though "zero matches"
# is a valid, expected result, not an error. Every extraction below that
# could legitimately match nothing is wrapped `(grep ... || true)` so a
# real "no match" only ever produces empty output, never a false crash.

# ─── 2. Android canonical ids — same field collate-demos.sh parses ────────
ANDROID_IDS_FILE="$(mktemp)"
for f in "$ANDROID_FRAG_DIR"/*Fragment.kt; do
    base="$(basename "$f")"
    [ "$base" = "DemoFragment.kt" ] && continue
    id=$( (grep -oE 'id[[:space:]]*=[[:space:]]*"[a-z0-9-]+"' "$f" || true) | head -n1 \
        | { grep -oE '"[a-z0-9-]+"' || true; } | tr -d '"')
    if [ -z "$id" ]; then
        echo "Error: $f has no 'id = \"<demo-id>\"' on its DemoEntry." >&2
        exit 1
    fi
    printf '%s\n' "$id"
done | sort -u > "$ANDROID_IDS_FILE"

# ─── 3. Regenerate iOS's GeneratedScenes.swift (unless a fixture overrides it) ──
if [ "${PARITY_SKIP_IOS_COLLATE:-0}" != "1" ] && [ -z "${PARITY_IOS_GENERATED:-}" ]; then
    bash "$REPO_ROOT/samples/ios-demo/scripts/collate-ios-demos.sh" >/dev/null
fi
[ -f "$IOS_GENERATED" ] || { echo "Error: iOS generated registry not found: $IOS_GENERATED (build the iOS collator first)" >&2; exit 1; }

# Single-line-array guard on the block extractors below (reviewer catch on
# PR #2830). Each `awk '/…= \[/{f=1;next} f && /^[[:space:]]*\]/{f=0} f'`
# state machine has a latent bug: if the source array is written on ONE line
# (`… = []` for a Set, `… = [:]` for a dictionary), the opening rule sets
# `f=1; next` and skips the line, but the closing `]` was ON that skipped
# line — so `f` never resets and the extractor slurps quoted ids from
# UNRELATED arrays further down the file into this id set (a false positive
# on this BLOCKING gate). It is harmless today only by luck; `residualIds`
# shrinks toward `[]` as L0.6 (#2804) ports each residual id to a real
# screen, which would trip it. The fix: in the opening rule, strip the line
# up to and including the assignment `= [`, then if the remainder still holds
# a `]` the whole literal is single-line → `print` it (so a non-empty
# single-line array still yields its ids) and `next` WITHOUT entering
# multi-line mode. Stripping up to `= [` first is what makes this correct for
# `legacyAliases`, whose `[String: String]` TYPE annotation also contains a
# `]` that a naive whole-line `~ /\]/` test would misread as a closing.

# ─── 4. iOS generated ids + "real" (non-placeholder) ids ──────────────────
IOS_GENERATED_IDS_FILE="$(mktemp)"
awk '/static let allowedIds: Set<String> = \[/{ rest=$0; sub(/^.*= \[/,"",rest); if (rest ~ /\]/) { print; next } f=1; next } f && /^[[:space:]]*\]/{f=0} f' "$IOS_GENERATED" \
    | { grep -oE '"[a-z0-9-]+"' || true; } | tr -d '"' | sort -u > "$IOS_GENERATED_IDS_FILE"

IOS_REAL_IDS_FILE="$(mktemp)"
awk '/static func destination\(for id: String\) -> AnyView\? \{/{f=1} f && /default: return nil/{f=0} f' "$IOS_GENERATED" \
    | { grep -oE 'case "[a-z0-9-]+"' || true; } | { grep -oE '"[a-z0-9-]+"' || true; } | tr -d '"' | sort -u > "$IOS_REAL_IDS_FILE"

[ -s "$IOS_GENERATED_IDS_FILE" ] || { echo "Error: no ids parsed from $IOS_GENERATED — collator output shape changed?" >&2; exit 1; }

# ─── 5. iOS hand-maintained legacyAliases (key\tvalue) + residualIds ──────
# (legacyAliases is expected to be small but non-empty in practice; residualIds
# is EXPECTED to shrink toward empty over time as L0.6 ports each id — so it
# especially must not crash the script when it eventually reaches zero.)
IOS_ALIASES_FILE="$(mktemp)"
awk '/static let legacyAliases: \[String: String\] = \[/{ rest=$0; sub(/^.*= \[/,"",rest); if (rest ~ /\]/) { print; next } f=1; next } f && /^[[:space:]]*\]/{f=0} f' "$IOS_REGISTRY" \
    > "$IOS_ALIASES_FILE.raw"
: > "$IOS_ALIASES_FILE"
while IFS= read -r line; do
    key=$(printf '%s\n' "$line" | { grep -oE '"[a-z0-9-]+"' || true; } | sed -n '1p' | tr -d '"')
    val=$(printf '%s\n' "$line" | { grep -oE '"[a-z0-9-]+"' || true; } | sed -n '2p' | tr -d '"')
    [ -n "$key" ] && [ -n "$val" ] && printf '%s\t%s\n' "$key" "$val" >> "$IOS_ALIASES_FILE"
done < "$IOS_ALIASES_FILE.raw"
rm -f "$IOS_ALIASES_FILE.raw"

IOS_RESIDUAL_FILE="$(mktemp)"
awk '/static let residualIds: Set<String> = \[/{ rest=$0; sub(/^.*= \[/,"",rest); if (rest ~ /\]/) { print; next } f=1; next } f && /^[[:space:]]*\]/{f=0} f' "$IOS_REGISTRY" \
    | { grep -oE '"[a-z0-9-]+"' || true; } | tr -d '"' | sort -u > "$IOS_RESIDUAL_FILE"

# ─── 6. Hand everything to Python for the structured comparison + YAML ────
# (PyYAML is already asserted present in the repo-hygiene job before this
# step runs — see ci.yml's "Install dash + shellcheck" step.)
PYTHONPATH="" python3 - "$ANDROID_IDS_FILE" "$IOS_GENERATED_IDS_FILE" "$IOS_REAL_IDS_FILE" "$IOS_ALIASES_FILE" "$IOS_RESIDUAL_FILE" "$MANIFEST" <<'PYEOF'
import collections
import re
import sys
import yaml

android_ids_file, ios_gen_file, ios_real_file, ios_alias_file, ios_residual_file, manifest_file = sys.argv[1:7]

def load_set(path):
    with open(path) as f:
        return set(l.strip() for l in f if l.strip())

android_ids = load_set(android_ids_file)
ios_generated = load_set(ios_gen_file)
ios_real = load_set(ios_real_file)
ios_residual = load_set(ios_residual_file)

ios_aliases = {}
with open(ios_alias_file) as f:
    for line in f:
        line = line.rstrip("\n")
        if not line:
            continue
        k, v = line.split("\t")
        ios_aliases[k] = v

ios_allowed = ios_generated | set(ios_aliases.keys()) | ios_residual

def ios_is_real(demo_id):
    """True iff `demo_id` resolves to a real (non-placeholder) destination
    through DemoDeepLinkRegistry's actual resolution order: generated id,
    then legacy-alias indirection."""
    if demo_id in ios_real:
        return True
    canonical = ios_aliases.get(demo_id)
    if canonical is not None:
        return canonical in ios_real
    return False

with open(manifest_file) as f:
    manifest_data = yaml.safe_load(f) or {}
manifest_rows = manifest_data.get("demos", [])

errors = []
warnings = []

manifest_by_id = {}
for row in manifest_rows:
    row_id = row.get("id")
    if not row_id:
        errors.append("manifest has a row with no 'id'")
        continue
    if row_id in manifest_by_id:
        errors.append(f"manifest has a duplicate row for id '{row_id}'")
    manifest_by_id[row_id] = row

VALID_STATUSES = {"working", "stub", "android-only"}
for row_id, row in manifest_by_id.items():
    status = row.get("iosStatus")
    if status not in VALID_STATUSES:
        errors.append(f"manifest row '{row_id}' has invalid iosStatus '{status}' "
                       f"(must be one of {sorted(VALID_STATUSES)})")
    if status and status != "working" and not str(row.get("reason", "")).strip():
        errors.append(f"manifest row '{row_id}' has iosStatus '{status}' but no 'reason'")

# ─── Section-banner tallies must match the parsed reality (#2801) ─────────
# The `# ─── working (N) ───` banners are COMMENTS, and everything above reads
# the manifest through yaml.safe_load — which never sees a comment. So a stale
# N used to sail through every other check in this file, and did: the tally
# drifted four separate times during the Wave-A iOS-port run (#2798) alone,
# ending 4 rows off reality with CI green throughout.
#
# This is the one part of the manifest safe to gate HARD rather than WARN:
# it is exact counting, not a heuristic, so it cannot false-positive. A banner
# is only checked when it is present — the self-test fixtures carry no banners,
# and a manifest is not required to have them. But once ANY banner exists, every
# non-empty status must carry one, so the gate cannot be dodged by deleting the
# banner that has gone stale.
status_counts = collections.Counter(
    row.get("iosStatus") for row in manifest_by_id.values()
)
banner_re = re.compile(
    r"^\s*#\s*─+\s*(working|stub|android-only)\s*\((\d+)\)"
)
banners = []
with open(manifest_file) as f:
    for lineno, line in enumerate(f, 1):
        m = banner_re.match(line)
        if m:
            banners.append((lineno, m.group(1), int(m.group(2))))

seen_banner_cats = set()
for lineno, cat, declared in banners:
    if cat in seen_banner_cats:
        errors.append(
            f"parity-manifest.yml line {lineno}: a second section banner for "
            f"'{cat}' — there must be exactly one banner per iosStatus, "
            f"otherwise 'the' count is ambiguous."
        )
        continue
    seen_banner_cats.add(cat)
    actual = status_counts.get(cat, 0)
    if declared != actual:
        errors.append(
            f"parity-manifest.yml line {lineno}: the '{cat}' section banner "
            f"says ({declared}) but {actual} row(s) actually carry "
            f"iosStatus: {cat}. Banners are comments — yaml.safe_load never "
            f"reads them, so a stale count passes every other check in this "
            f"script silently. Recount at the parser, never by hand."
        )

if banners:
    for cat in sorted(VALID_STATUSES):
        if status_counts.get(cat, 0) > 0 and cat not in seen_banner_cats:
            errors.append(
                f"parity-manifest.yml has section banners but none for "
                f"'{cat}', which holds {status_counts[cat]} row(s). Add a "
                f"'# ─── {cat} ({status_counts[cat]}) ───' banner — a missing "
                f"banner is how a stale one gets 'fixed' by deletion."
            )

# ─── The core gate: every Android id is accounted for ─────────────────────
for demo_id in sorted(android_ids):
    in_ios = demo_id in ios_allowed
    in_manifest = demo_id in manifest_by_id
    if not in_ios and not in_manifest:
        errors.append(
            f"Android demo '{demo_id}' has NEITHER an iOS registry entry NOR a "
            f"parity-manifest.yml row — this is the #2769 drift class (a demo "
            f"silently invisible to iOS). Add a matching iOS Scene/residual id, "
            f"or declare it in parity-manifest.yml as 'stub'/'android-only'."
        )
        continue
    if not in_manifest:
        errors.append(
            f"Android demo '{demo_id}' is in iOS's allowedIds but has no "
            f"parity-manifest.yml row. Every current Android id needs a row "
            f"(#2801 done-criteria: one entry per Android canonical id)."
        )
        continue

    row = manifest_by_id[demo_id]
    declared = row.get("iosStatus")
    if declared == "working":
        if not in_ios or not ios_is_real(demo_id):
            errors.append(
                f"manifest declares '{demo_id}' as iosStatus: working, but iOS "
                f"does not actually resolve it to a real destination today "
                f"(it may still be a placeholder, or missing from allowedIds)."
            )
    elif declared == "stub":
        if not in_ios:
            errors.append(
                f"manifest declares '{demo_id}' as iosStatus: stub, but the id "
                f"is not in iOS's allowedIds at all — either it was removed "
                f"(demote the manifest row to 'android-only') or the registry "
                f"regressed."
            )
        elif ios_is_real(demo_id):
            warnings.append(
                f"manifest declares '{demo_id}' as iosStatus: stub, but iOS now "
                f"resolves it to a REAL destination — promote the manifest row "
                f"to 'working' (great news, a port landed)."
            )
    elif declared == "android-only":
        if in_ios:
            errors.append(
                f"manifest declares '{demo_id}' as iosStatus: android-only, but "
                f"the id IS present in iOS's allowedIds today — promote the "
                f"manifest row to 'stub' or 'working'."
            )

# ─── Manifest rows that no longer correspond to a live Android id ─────────
stale_rows = sorted(set(manifest_by_id.keys()) - android_ids)
for row_id in stale_rows:
    errors.append(
        f"parity-manifest.yml row '{row_id}' is not a current Android canonical "
        f"id (renamed, removed, or a typo) — remove or fix the row."
    )

# ─── Report ────────────────────────────────────────────────────────────────
print(f"Android canonical ids: {len(android_ids)}")
print(f"iOS allowedIds:        {len(ios_allowed)}  "
      f"(generated={len(ios_generated)} aliases={len(ios_aliases)} residual={len(ios_residual)})")
print(f"parity-manifest.yml:   {len(manifest_rows)} rows")

if warnings:
    print(f"\n{len(warnings)} warning(s) (non-blocking):")
    for w in warnings:
        print(f"  WARN: {w}")

if errors:
    print(f"\n{len(errors)} error(s):")
    for e in errors:
        print(f"  FAIL: {e}")
    print("\ncheck-demo-id-parity.sh: FAILED")
    sys.exit(1)

print("\ncheck-demo-id-parity.sh: OK — every Android demo id is accounted for "
      "(iOS registry or parity-manifest.yml), and every manifest row matches "
      "iOS's live state.")
PYEOF
