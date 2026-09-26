#!/usr/bin/env bash
# check-demo-id-parity.sh — Android <-> iOS demo-catalog parity REPORT
# (#2801, part of the iOS-parity tracker #2798; made advisory in #3650).
#
# ADVISORY, BY DECISION. This script always exits 0. It prints what it found,
# raises one `::warning` annotation per finding and writes a table into
# `$GITHUB_STEP_SUMMARY`; it never fails a build, and its CI job
# (`demo-parity` in ci.yml) is `continue-on-error` on top of that. Do not
# "fix" the exit code back to non-zero: this was a gate once, it is not one
# now, and the reason is that the thing it measures is not a defect.
#
# Why not a gate: a gap between the Android and iOS demo catalogues is the
# normal state of a port in progress, not a mistake. Blocking on it would
# make every legitimate Android-first demo red until someone writes the iOS
# side or edits the ledger in the same PR — which is how the original gate
# got deleted wholesale with the agent harness (#3244, commit 72e5709)
# rather than fixed, leaving `parity-manifest.yml` asserting for months a CI
# guard that no longer existed. An advisory job that nobody is tempted to
# delete beats a blocking one that silently disappears.
#
# The flip side, and the only thing asked of a reader: the warnings are real
# discrepancies, not noise. If one is stale, the fix is to edit
# `parity-manifest.yml`, not to mute this job.
#
# Compares three surfaces:
#   1. Android's canonical demo ids — read directly from each
#      `*Fragment.kt`'s `id = "..."` under
#      `samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/`
#      (the same field `collate-demos.sh` itself parses; `GeneratedDemos.kt`'s
#      TEXT only lists Kotlin object names, not id strings, so it can't be
#      grepped directly — this script also RUNS collate-demos.sh --kt-only
#      first, purely so its own duplicate-id / missing-directive validation
#      still runs ahead of this check even when it happens before the
#      Android Gradle build does).
#   2. iOS's registry — `GeneratedScenes.allowedIds` (regenerated fresh via
#      `collate-ios-demos.sh`, the same collator the Xcode build phase runs)
#      unioned with the hand-maintained `legacyAliases` / `residualIds` read
#      directly from `DemoDeepLinkRegistry.swift`.
#   3. `parity-manifest.yml` — the committed ledger of expected iOS status
#      per Android id (working / stub / android-only).
#
# What it reports:
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
#   - a `# ─── <bucket> (N) ───` section banner's declared tally disagrees
#     with the number of rows that actually carry that `iosStatus`, or a row
#     is filed under a section that isn't its own status. Banners are
#     COMMENTS — `yaml.safe_load` never sees them, so nothing else would ever
#     catch them (the Wave-A iOS ports left them reading 30/23 against a real
#     34/19). Files with no banners at all opt out.
#
# Usage:
#   bash .claude/scripts/check-demo-id-parity.sh
#
# Testability seams (mirrors check-doc-drift.sh's DOC_DRIFT_FILES pattern).
# They survive from when this script had a self-test; that test went with the
# harness in #3244 and has not been restored, so today they are simply how
# you point the script at a fixture to check a detector still fires:
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

# ─── Advisory plumbing (#3650) ────────────────────────────────────────────
# `set -e` is kept on purpose: a genuine bug in this script should still be
# visible while someone edits it locally. What must never be fatal are the
# EXPECTED failure modes — a surface that moved, a collator that broke — so
# each of those is handled explicitly below and ends the run at 0. The CI
# job carries `continue-on-error: true` as the backstop for anything left.
warn() {
    printf '::warning file=parity-manifest.yml,title=Demo parity (setup)::%s\n' "$1"
    printf 'WARN: %s\n' "$1" >&2
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        printf '## Demo catalogue parity (advisory)\n\nNot run: %s\n' "$1" \
            >> "$GITHUB_STEP_SUMMARY"
    fi
}
bail_advisory() { warn "$1"; exit 0; }

[ -d "$ANDROID_FRAG_DIR" ] || bail_advisory "Android fragments dir not found: $ANDROID_FRAG_DIR"
[ -f "$IOS_REGISTRY" ] || bail_advisory "iOS registry not found: $IOS_REGISTRY"
[ -f "$MANIFEST" ] || bail_advisory "manifest not found: $MANIFEST"

# ─── 1. Regenerate + validate the Android collator (dup ids / missing
#     directives surface here too, independent of the Gradle build) ────────
# `|| bail_advisory` and not `|| true`: if the collator itself is broken,
# every id set below is suspect and reporting on them would invent drift
# that isn't there. Say so and stop, at 0. The collator's own CI job is
# where a broken collator is supposed to be caught.
if [ "${PARITY_SKIP_ANDROID_COLLATE:-0}" != "1" ]; then
    bash "$REPO_ROOT/samples/android-demo/scripts/collate-demos.sh" --kt-only >/dev/null \
        || bail_advisory "collate-demos.sh --kt-only failed; skipping the parity report."
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
        # Unreachable when collate-demos.sh ran above — it parses the same
        # field and fails on a missing one. Reachable only under
        # PARITY_SKIP_ANDROID_COLLATE, so: say it, skip it, keep going.
        warn "$f has no 'id = \"<demo-id>\"' on its DemoEntry; not counted."
        continue
    fi
    printf '%s\n' "$id"
done | sort -u > "$ANDROID_IDS_FILE"

# ─── 3. Regenerate iOS's GeneratedScenes.swift (unless a fixture overrides it) ──
if [ "${PARITY_SKIP_IOS_COLLATE:-0}" != "1" ] && [ -z "${PARITY_IOS_GENERATED:-}" ]; then
    bash "$REPO_ROOT/samples/ios-demo/scripts/collate-ios-demos.sh" >/dev/null \
        || bail_advisory "collate-ios-demos.sh failed; skipping the parity report."
fi
[ -f "$IOS_GENERATED" ] || bail_advisory "iOS generated registry not found: $IOS_GENERATED (run the iOS collator first)"

# Single-line-array guard on the block extractors below (reviewer catch on
# PR #2830). Each `awk '/…= \[/{f=1;next} f && /^[[:space:]]*\]/{f=0} f'`
# state machine has a latent bug: if the source array is written on ONE line
# (`… = []` for a Set, `… = [:]` for a dictionary), the opening rule sets
# `f=1; next` and skips the line, but the closing `]` was ON that skipped
# line — so `f` never resets and the extractor slurps quoted ids from
# UNRELATED arrays further down the file into this id set (a false positive
# in the report). It is harmless today only by luck; `residualIds`
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

[ -s "$IOS_GENERATED_IDS_FILE" ] || bail_advisory "no ids parsed from $IOS_GENERATED — collator output shape changed? Skipping the parity report rather than reporting all 48 Android ids as missing from iOS."

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
import os
import re
import sys

try:
    import yaml
except ImportError:
    # Advisory to the end: a missing dependency reports itself and exits 0
    # rather than turning a green PR into a red one over PyYAML.
    print("::warning file=parity-manifest.yml,title=Demo parity (setup)::"
          "PyYAML is not installed, so the parity report did not run "
          "(`pip install pyyaml`).")
    sys.exit(0)

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

# ─── The core check: every Android id is accounted for ───────────────────
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

# ─── Section headers + tallies: the COMMENTS must not lie (#2857 follow-up) ──
# `yaml.safe_load` drops comments, so everything above validates only the
# DATA. The file's `# ─── working (30) ───` section banners and their tallies
# are comments — invisible to the parser, and therefore free to drift with
# nothing to catch them. That is exactly what happened across the Wave-A iOS
# port PRs: four rows were flipped to `iosStatus: working` in place, without
# moving them into the `working` section or touching any banner, so the
# banners advertised 30/23 while the real tallies were 34/19.
#
# This is a deterministic, purely textual check (no heuristic, no judgement).
# It reports two invariants:
#   A. every row sits under the banner matching its own `iosStatus`
#   B. every banner's declared count equals that bucket's real row count
#
# Files with no banners at all (fixtures) opt out entirely —
# the convention is only enforced where it is actually used.
section_re = re.compile(r'^\s*#\s*─+\s*([a-z][a-z-]*)\s*\((\d+)\)')
row_re = re.compile(r'^\s*-\s+id:\s*(\S+)')

declared_sections = []       # [(bucket, declared_count, line_no)]
row_section = {}             # row id -> bucket banner it physically sits under
current_section = None
in_demos = False

with open(manifest_file) as f:
    for line_no, line in enumerate(f, 1):
        if not in_demos:
            # Only look for banners AFTER `demos:` — the file's prose preamble
            # discusses these same bucket names and must not be parsed as one.
            if re.match(r'^demos:\s*$', line):
                in_demos = True
            continue
        m = section_re.match(line)
        if m:
            current_section = m.group(1)
            declared_sections.append((current_section, int(m.group(2)), line_no))
            continue
        m = row_re.match(line)
        if m:
            row_section[m.group(1)] = current_section

if declared_sections:
    seen_buckets = set()
    for bucket, declared_count, line_no in declared_sections:
        if bucket not in VALID_STATUSES:
            errors.append(
                f"parity-manifest.yml line {line_no}: section banner '{bucket}' is "
                f"not a valid iosStatus (must be one of {sorted(VALID_STATUSES)})."
            )
            continue
        if bucket in seen_buckets:
            errors.append(
                f"parity-manifest.yml line {line_no}: duplicate section banner for "
                f"'{bucket}' — each bucket must have exactly one section."
            )
            continue
        seen_buckets.add(bucket)

        # Invariant B — declared tally vs the bucket's real row count.
        actual = sum(1 for r in manifest_by_id.values() if r.get("iosStatus") == bucket)
        if declared_count != actual:
            errors.append(
                f"parity-manifest.yml line {line_no}: the '{bucket}' section banner "
                f"declares ({declared_count}) but {actual} row(s) actually have "
                f"iosStatus: {bucket}. Section tallies are COMMENTS — nothing else "
                f"reads them, so they drift silently. Fix the number."
            )

    # A bucket that has rows but no banner at all is the same drift, inverted.
    for bucket in sorted(VALID_STATUSES):
        actual = sum(1 for r in manifest_by_id.values() if r.get("iosStatus") == bucket)
        if actual and bucket not in seen_buckets:
            errors.append(
                f"parity-manifest.yml has {actual} row(s) with iosStatus: {bucket} "
                f"but no '# ─── {bucket} (N) ───' section banner for them."
            )

    # Invariant A — each row must sit under its own status's banner.
    for row_id, row in sorted(manifest_by_id.items()):
        status = row.get("iosStatus")
        if status not in VALID_STATUSES:
            continue  # already reported above
        physical = row_section.get(row_id)
        if physical is None:
            errors.append(
                f"parity-manifest.yml row '{row_id}' sits above every section "
                f"banner — move it under the '{status}' section."
            )
        elif physical in VALID_STATUSES and physical != status:
            errors.append(
                f"parity-manifest.yml row '{row_id}' has iosStatus: {status} but is "
                f"filed under the '{physical}' section — move the row into the "
                f"'{status}' section (a status flipped in place makes both "
                f"sections' tallies wrong)."
            )

# ─── The header's own CURRENT STATE tally must not lie either ─────────────
# The section banners are not the only counts written in prose: the preamble
# carries a summary line of the same numbers. It drifted just as freely (this
# header once held four mutually contradictory tallies at once), so it is
# pinned to the same measured truth. Opt-in by construction — a manifest with
# no such line is simply not checked.
summary_re = re.compile(
    r'Of the (\d+) Android ids?:\s*(\d+) working,\s*(\d+) stub,\s*(\d+) android-only'
)
with open(manifest_file) as f:
    for line_no, line in enumerate(f, 1):
        if re.match(r'^demos:\s*$', line):
            break
        m = summary_re.search(line)
        if not m:
            continue
        claimed_total, claimed = int(m.group(1)), {
            "working": int(m.group(2)),
            "stub": int(m.group(3)),
            "android-only": int(m.group(4)),
        }
        if claimed_total != len(manifest_rows):
            errors.append(
                f"parity-manifest.yml line {line_no}: the header summary says "
                f"'Of the {claimed_total} Android ids' but the file has "
                f"{len(manifest_rows)} rows."
            )
        for bucket, claimed_n in claimed.items():
            actual = sum(1 for r in manifest_by_id.values() if r.get("iosStatus") == bucket)
            if claimed_n != actual:
                errors.append(
                    f"parity-manifest.yml line {line_no}: the header summary claims "
                    f"{claimed_n} '{bucket}' but {actual} row(s) actually have that "
                    f"iosStatus. Recount — do not narrate."
                )

# ─── Report ────────────────────────────────────────────────────────────────
# ADVISORY (#3650). `errors` keeps its name — every finding below IS a real
# discrepancy, and calling them something softer would only make the next
# reader wonder which ones matter. What changed is the verdict: this script
# reports and exits 0. See the header for why the maintainer chose that.
summary_lines = [
    "## Demo catalogue parity (advisory — never fails the build)",
    "",
    "| source | ids |",
    "|---|---|",
    f"| Android canonical ids | {len(android_ids)} |",
    f"| iOS `allowedIds` | {len(ios_allowed)} "
    f"(generated {len(ios_generated)} + aliases {len(ios_aliases)} "
    f"+ residual {len(ios_residual)}) |",
    f"| `parity-manifest.yml` rows | {len(manifest_rows)} |",
]

print(f"Android canonical ids: {len(android_ids)}")
print(f"iOS allowedIds:        {len(ios_allowed)}  "
      f"(generated={len(ios_generated)} aliases={len(ios_aliases)} residual={len(ios_residual)})")
print(f"parity-manifest.yml:   {len(manifest_rows)} rows")

manifest_rel = os.path.relpath(manifest_file, os.environ.get("GITHUB_WORKSPACE", os.getcwd()))
if manifest_rel.startswith(".."):
    # An annotation's `file=` must be repo-relative; anything else makes
    # GitHub drop the annotation entirely. Outside a checkout (self-test
    # fixtures, a local run from elsewhere) fall back to the bare name.
    manifest_rel = os.path.basename(manifest_file)


def annotate(kind, message):
    """One GitHub annotation per finding, on parity-manifest.yml.

    `::warning` for everything, including the `errors` list — an annotation's
    LEVEL is what decides whether GitHub paints the check red, and this job
    must stay green whatever it finds.
    """
    flat = " ".join(str(message).split())
    print(f"::warning file={manifest_rel},title=Demo parity ({kind})::{flat}")


if warnings:
    summary_lines += ["", f"### Warnings ({len(warnings)})", ""]
    print(f"\n{len(warnings)} warning(s):")
    for w in warnings:
        print(f"  WARN: {w}")
        summary_lines.append(f"- {' '.join(str(w).split())}")
        annotate("warning", w)

if errors:
    summary_lines += [
        "",
        f"### Drift between the catalogues and the ledger ({len(errors)})",
        "",
        "Each line is a real discrepancy. Fixing them is a judgement call — "
        "which is why this is advisory and not a gate.",
        "",
    ]
    print(f"\n{len(errors)} discrepancy/discrepancies:")
    for e in errors:
        print(f"  DRIFT: {e}")
        summary_lines.append(f"- {' '.join(str(e).split())}")
        annotate("drift", e)
    print("\ncheck-demo-id-parity.sh: reported above, exiting 0 (advisory).")
else:
    summary_lines += [
        "",
        "No drift: every Android demo id is accounted for (iOS registry or "
        "`parity-manifest.yml`), every manifest row matches iOS's live state, "
        "and every section banner's tally is correct.",
    ]
    print("\ncheck-demo-id-parity.sh: OK — every Android demo id is accounted for "
          "(iOS registry or parity-manifest.yml), and every manifest row matches "
          "iOS's live state.")

step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
if step_summary:
    with open(step_summary, "a") as f:
        f.write("\n".join(summary_lines) + "\n")

# Advisory by decision, not by accident. Do not "restore" a non-zero exit here
# without reopening #3650 — the CI job is `continue-on-error` as well, so a
# lone edit here would not even make it a gate, only a confusing half-one.
sys.exit(0)
PYEOF
