#!/usr/bin/env bash
# Hermetic self-test for collate-changelog.sh — the fragment→category contract.
#
# The collator runs exactly once per release, on a directory of fragments that
# are DELETED the moment it succeeds. A misfiled bullet is therefore discovered
# after the release notes are published, with the source already gone — which is
# how a fragment carrying two `<!-- category: -->` tags shipped all of its
# `Fixed` bullets under `### Tests`: the parse loop reassigned the category on
# every tag but accumulated the whole file into one buffer, written once at EOF
# under whichever tag came LAST.
#
# `changelog.d/README.md` allows several bullets per fragment and never forbids
# several categories, so multi-tag fragments are legal input, not misuse.
#
# Fixtures only: a fake repo root (its own .claude/scripts/, changelog.d/ and
# CHANGELOG.md) with a copy of the script under test. No network, no git, no
# writes outside $TMP.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLATOR="$SCRIPT_DIR/collate-changelog.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

check() { # name expected actual
  if [ "$2" = "$3" ]; then
    echo "  ✓ $1"; PASS=$((PASS + 1))
  else
    echo "  ✗ $1 — expected '$2', got '$3'"; FAIL=$((FAIL + 1))
  fi
}

# Build a fresh fake repo root at $ROOT around the given collator, and populate
# changelog.d/ with the fixture fragments. The collator derives its repo root
# from its own path (`dirname $0`/../..), so copying it into $ROOT/.claude/
# scripts/ is what makes the run hermetic.
setup_sandbox() { # collator_path -> sets $ROOT
  ROOT="$TMP/repo"
  rm -rf "$ROOT"
  mkdir -p "$ROOT/.claude/scripts" "$ROOT/changelog.d"
  cp "$1" "$ROOT/.claude/scripts/collate-changelog.sh"
  printf '# Changelog\n\n## Unreleased\n\n## v4.0.0 — 2026-01-01\n\n### Fixed\n\n- PRIOR-RELEASE-BULLET\n' \
    > "$ROOT/CHANGELOG.md"

  # 1. Single-tag fragment — the common case, must keep working untouched.
  cat > "$ROOT/changelog.d/0001-single-tag.md" <<'EOF'
<!-- category: Added -->
- MARKER-SINGLE-A
- MARKER-SINGLE-B
EOF

  # 2. Multi-tag fragment — the regression. Each tag owns the bullets AFTER it,
  #    and the leading bullet (before any tag) defaults to Changed.
  cat > "$ROOT/changelog.d/0002-multi-tag.md" <<'EOF'
- MARKER-PREAMBLE
<!-- category: Fixed -->
- MARKER-MULTI-FIXED

<!-- category: Tests -->
- MARKER-MULTI-TESTS
EOF

  # 3. Untagged fragment — documented default bucket.
  cat > "$ROOT/changelog.d/0003-untagged.md" <<'EOF'
- MARKER-UNTAGGED
EOF

  # 4. Unknown category name — falls back to Changed rather than creating a
  #    bucket that the section builder would never emit (silently dropping it).
  cat > "$ROOT/changelog.d/0004-unknown-category.md" <<'EOF'
<!-- category: Wibble -->
- MARKER-UNKNOWN
EOF

  # 5. Odd spacing + casing — the tag matcher and canonicalisation must both
  #    tolerate it, otherwise the line reads as prose and lands in the body.
  cat > "$ROOT/changelog.d/0005-odd-spacing.md" <<'EOF'
   <!--   category:   dOcS   -->
- MARKER-ODD
EOF
}

# Which `### <Category>` heading does a marker end up under, inside the NEW
# release section? Anything outside that section (or a marker that leaked into
# a heading-less position) reports empty.
section_of() { # marker -> "Added" | "" ...
  awk -v marker="$1" '
    /^## v4\.1\.0/ { in_new = 1; next }
    in_new && /^## / { exit }
    in_new && /^### / { heading = substr($0, 5); next }
    in_new && index($0, marker) { print heading; exit }
  ' "$ROOT/CHANGELOG.md"
}

echo "collate-changelog.sh — fragment→category contract"

setup_sandbox "$COLLATOR"
OUT="$(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --date 2026-08-01 2>&1)"; RC=$?

check "exit 0 on valid fragments" 0 "$RC"

check "single-tag: first bullet"          Added   "$(section_of MARKER-SINGLE-A)"
check "single-tag: second bullet"         Added   "$(section_of MARKER-SINGLE-B)"

check "multi-tag: pre-tag bullet defaults" Changed "$(section_of MARKER-PREAMBLE)"
check "multi-tag: Fixed block stays Fixed" Fixed   "$(section_of MARKER-MULTI-FIXED)"
check "multi-tag: Tests block stays Tests" Tests   "$(section_of MARKER-MULTI-TESTS)"

check "untagged fragment defaults"        Changed "$(section_of MARKER-UNTAGGED)"
check "unknown category falls back"       Changed "$(section_of MARKER-UNKNOWN)"
check "odd spacing/casing canonicalises"  Docs    "$(section_of MARKER-ODD)"

# The tag lines themselves must never survive into the release notes.
if grep -q 'category:' "$ROOT/CHANGELOG.md"; then
  echo "  ✗ a category tag line leaked into CHANGELOG.md"; FAIL=$((FAIL + 1))
else
  echo "  ✓ category tag lines consumed, not emitted"; PASS=$((PASS + 1))
fi

# Pre-existing sections survive verbatim.
check "prior release preserved" 1 "$(grep -c 'PRIOR-RELEASE-BULLET' "$ROOT/CHANGELOG.md")"

# Consumed fragments are deleted; README.md is not a fragment and must remain
# untouched (it is skipped by name, so it is never even in FRAGMENTS).
check "fragments consumed" 0 "$(find "$ROOT/changelog.d" -name '*.md' | wc -l | tr -d ' ')"

# --dry-run must not mutate anything — the escape hatch a release operator uses
# to look before committing.
setup_sandbox "$COLLATOR"
BEFORE="$(cat "$ROOT/CHANGELOG.md")"
(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --dry-run >/dev/null 2>&1)
check "--dry-run leaves CHANGELOG.md alone" "$BEFORE" "$(cat "$ROOT/CHANGELOG.md")"
check "--dry-run keeps fragments"           5 "$(find "$ROOT/changelog.d" -name '*.md' | wc -l | tr -d ' ')"

# ── Mutation test ────────────────────────────────────────────────────────────
# The assertions above are only worth something if they are what FORCES the
# per-tag flush. The mutation target is the flush call at the tag transition
# (12-space indent, inside the `if`) — deleting it restores the original
# EOF-only behaviour. The EOF flush (4-space indent) is deliberately left in
# place, so the mutant still produces a complete-looking CHANGELOG: exactly the
# failure mode that shipped, not a crash.
MUT="$TMP/collate-mutant.sh"
grep -v '^            flush_fragment_body$' "$COLLATOR" > "$MUT"
if [ "$(grep -c 'flush_fragment_body' "$MUT")" = "$(grep -c 'flush_fragment_body' "$COLLATOR")" ]; then
  echo "  ✗ mutation could not be applied — the anchor line moved, fix this test"
  FAIL=$((FAIL + 1))
else
  setup_sandbox "$MUT"
  (cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --date 2026-08-01 >/dev/null 2>&1)
  mut_fixed="$(section_of MARKER-MULTI-FIXED)"
  if [ "$mut_fixed" = "Fixed" ]; then
    echo "  ✗ MUTATION SURVIVED — the per-tag flush is not what keeps Fixed bullets"
    echo "    in Fixed; a regression to EOF-only flushing would go unnoticed"
    FAIL=$((FAIL + 1))
  else
    echo "  ✓ mutation killed — without the per-tag flush the Fixed block files under '${mut_fixed:-<none>}'"
    PASS=$((PASS + 1))
  fi
fi

echo
echo "collate-changelog: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
