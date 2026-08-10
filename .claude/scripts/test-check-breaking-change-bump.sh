#!/usr/bin/env bash
#
# test-check-breaking-change-bump.sh — hermetic self-test for the guard that
# refuses a PATCH-level release carrying a breaking change.
#
# The guard fires at most once per release, only on a patch tag, and only when a
# breaking fragment happens to be pending. That combination may not occur for
# months — which is exactly the shape of gate that rots green (#2988). Nothing
# but a self-test will notice that it stopped working.
#
# What it must get right, in both directions:
#   * REFUSE  a patch tag when a fragment declares breaking — explicitly
#             (`<!-- breaking -->`) or in its public prose. #3037's fragment
#             carries no marker; it says "source-breaking … never a patch" in
#             English, which is the realistic input.
#   * ALLOW   everything else. Two false-positive shapes are taken verbatim from
#             fragments in the tree: `changelog.d/3008-contentid.md` says "and
#             non-breaking", and "groundbreaking" contains the word outright.
#             A guard that blocks releases on those gets disabled within a week.
#
# The version comparison is exercised on BOTH sides of the release bump, because
# the guard runs on both: release-fast.yml calls it before collation (CHANGELOG's
# newest section is the previous release) and release-checklist.sh after
# (CHANGELOG's newest section is the target itself).
#
# Hermetic: a fake repo root with a copy of the script and its lib. The script
# derives REPO_ROOT from its own path, so the real tree is never read or
# written. No network, no git.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD_SRC="$SCRIPT_DIR/check-breaking-change-bump.sh"
LIB_SRC="$SCRIPT_DIR/lib/changelog-fragment.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
ok()  { printf '  \xE2\x9C\x93 %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  \xE2\x9C\x97 %s\n' "$1"; FAIL=$((FAIL + 1)); }

# Build a sandbox repo root. $1 = lib to install (defaults to the real one, so a
# mutation can swap it). Sets $ROOT.
setup_sandbox() { # [lib_path]
  ROOT="$TMP/repo"
  rm -rf "$ROOT"
  mkdir -p "$ROOT/.claude/scripts/lib" "$ROOT/changelog.d"
  cp "$GUARD_SRC" "$ROOT/.claude/scripts/"
  cp "${1:-$LIB_SRC}" "$ROOT/.claude/scripts/lib/changelog-fragment.sh"
  # Newest shipped release = v4.26.0, so 4.26.1 is patch-level and 4.27.0 is not.
  printf '# Changelog\n\n## Unreleased\n\n## v4.26.0 — 2026-07-01\n\n### Fixed\n\n- PRIOR\n\n## v4.25.0 — 2026-06-01\n\n### Fixed\n\n- OLDER\n' \
    > "$ROOT/CHANGELOG.md"
}

frag() { # filename, content on stdin
  cat > "$ROOT/changelog.d/$1"
}

# Run the guard, leaving its exit code in $GUARD_RC and its output in $OUT.
# Deliberately NOT `rc="$(run_guard …)"`: command substitution runs in a
# subshell, so the assignment to $OUT would be discarded and every assertion
# about the guard's MESSAGE would read an unset variable. (This test's first
# version did exactly that.)
GUARD_RC=""
OUT=""
run_guard() { # version [extra args...]
  OUT="$(cd "$ROOT" && bash .claude/scripts/check-breaking-change-bump.sh "$@" 2>&1)"
  GUARD_RC=$?
}

expect_rc() { # label expected_rc version [extra args...]
  local label="$1" want="$2"; shift 2
  run_guard "$@"
  if [ "$GUARD_RC" = "$want" ]; then
    ok "$label"
  else
    bad "$label — expected exit $want, got $GUARD_RC"
    printf '%s\n' "$OUT" | sed 's/^/        /'
  fi
}

echo "check-breaking-change-bump.sh — breaking change vs patch-level tag"

# ── 1. The explicit marker on a patch tag ────────────────────────────────────
setup_sandbox
frag 0001-explicit.md <<'EOF'
<!-- category: Changed -->
<!-- breaking -->
- A public symbol changed shape.
EOF
expect_rc "explicit <!-- breaking --> refuses a patch tag" 1 4.26.1
grep -q '4\.27\.0' <<<"$OUT" \
  && ok "the refusal names the version to tag instead" \
  || bad "the refusal does not tell the operator what to do"

# ── 2. Same fragment, minor tag → allowed ────────────────────────────────────
# Positive cue for section 1: without this the refusal above could be a guard
# that refuses everything.
expect_rc "the same breaking fragment passes on a MINOR tag" 0 4.27.0

# ── 3. A patch tag with nothing breaking pending ─────────────────────────────
setup_sandbox
frag 0002-plain.md <<'EOF'
<!-- category: Fixed -->
- A crash on rotate is fixed.
EOF
expect_rc "patch tag with no breaking fragment is allowed" 0 4.26.1

# ── 4. Prose only — the #3037 shape, verbatim ────────────────────────────────
# No marker existed when this was written. If the guard only reads markers it
# catches nothing that has actually happened.
setup_sandbox
frag 0003-prose.md <<'EOF'
<!-- category: Changed -->
- **`TapEvent.nodeName` is now typed `string | null`** (React Native). Under
  `strictNullChecks` this is source-breaking for code assigning `nodeName` to a
  `string | undefined` binding. Because the change is source-breaking, it ships
  in a minor release, never a patch.
EOF
expect_rc "prose 'source-breaking' refuses a patch tag (#3037, no marker)" 1 4.26.1

# ── 5. Negations must NOT fire — verbatim from changelog.d/3008-contentid.md ─
setup_sandbox
frag 0004-negated.md <<'EOF'
<!-- category: Added -->
- The modifier is opt-in and non-breaking — a scene without the modifier builds
  its content exactly as before.
EOF
expect_rc "'non-breaking' does not refuse a patch tag" 0 4.26.1

# ── 5b. …including when the bullet WRAPS mid-negation ────────────────────────
# sed is line-oriented. Every fragment in the tree today writes one long line per
# bullet, so a wrap between "non-" and "breaking" would have gone unnoticed until
# the first contributor who wraps — and it would have blocked a release.
setup_sandbox
frag 0004b-negated-wrapped.md <<'EOF'
<!-- category: Added -->
- The modifier is opt-in and non-
  breaking — a scene without it builds exactly as before.
EOF
expect_rc "a negation split across two lines still does not refuse a patch tag" 0 4.26.1

# ── 5c. A usage typo must not read as REFUSED ────────────────────────────────
# `--previous` with no value used to abort on `shift 2` under `set -e`, and the
# resulting exit 1 is this script's REFUSED code: a typo would have looked like
# "a breaking fragment blocks this release".
setup_sandbox
frag 0004c-plain.md <<'EOF'
<!-- category: Fixed -->
- Nothing remarkable.
EOF
expect_rc "'--previous' with no value exits 2 (malformed), not 1 (REFUSED)" 2 4.26.1 --previous

# ── 5d. "no longer breaking" is a negation with a word in the middle ─────────
setup_sandbox
frag 0004d-no-longer.md <<'EOF'
<!-- category: Fixed -->
- Loading a model from a raw resource id is no longer breaking when the id is
  missing; it now returns null.
EOF
expect_rc "'no longer breaking' does not refuse a patch tag" 0 4.26.1

# ── 5e. A backticked identifier is not a declaration ─────────────────────────
# The stripper deliberately PRESERVES code spans in the published text, so the
# heuristic has to drop them itself or every fragment documenting this very
# convention declares itself breaking.
setup_sandbox
frag 0004e-codespan.md <<'EOF'
<!-- category: Added -->
- A fragment may now carry a `<!-- breaking -->` line; the word `breaking`
  inside a code span is an identifier, not a declaration.
EOF
expect_rc "'breaking' inside a code span does not refuse a patch tag" 0 4.26.1

# ── 5f. …but a real declaration outside a code span still fires ─────────────
# Without this, 5e could pass by disabling the heuristic altogether.
setup_sandbox
frag 0004f-codespan-and-real.md <<'EOF'
<!-- category: Changed -->
- The `breaking` marker is documented here, and this bullet is itself a
  source-breaking change to the public API.
EOF
expect_rc "a real declaration beside a code span still refuses a patch tag" 1 4.26.1

# ── 5i. A word merely ENDING in a negation is not a negation ─────────────────
# Deliberately contrived prose: the point is that "piano" ends in "no", and an
# unbounded negation alternation would read "piano breaking" as "no breaking"
# and let a real declaration through. Wrong in the one direction that matters.
setup_sandbox
frag 0004i-piano.md <<'EOF'
<!-- category: Changed -->
- The piano breaking animation is gone from the public API.
EOF
expect_rc "'piano breaking' is not read as a negation" 1 4.26.1

# ── 5j. A double-backtick code span is still a code span ─────────────────────
# Markdown closes a span with a run of the same length; a one-backtick stripper
# leaves the inner text exposed and the identifier reads as a declaration.
setup_sandbox
frag 0004j-double-tick.md <<'EOF'
<!-- category: Added -->
- The marker is written ``breaking`` when the surrounding prose needs a
  literal backtick.
EOF
expect_rc "a double-backtick code span does not refuse a patch tag" 0 4.26.1

# ── 5g. A multi-word marker value is malformed, not "no marker here" ─────────
# The regex used to capture the value as a single alphanumeric token, so this
# line failed to match at all, was stripped as an ordinary comment, and the
# fragment shipped unflagged — a typo silently doing the opposite of what the
# author wrote.
setup_sandbox
frag 0004g-marker-multiword.md <<'EOF'
<!-- category: Changed -->
<!-- breaking: not sure -->
- Something changed.
EOF
expect_rc "'<!-- breaking: not sure -->' exits 2 (malformed), not 0" 2 4.26.1

# ── 5h. …and so is a colon with nothing after it ─────────────────────────────
# Distinct from `<!-- breaking -->`, which is the shorthand for true: the colon
# says a value was meant, and none arrived.
setup_sandbox
frag 0004h-marker-empty.md <<'EOF'
<!-- category: Changed -->
<!-- breaking: -->
- Something changed.
EOF
expect_rc "'<!-- breaking: -->' exits 2 (malformed), not 0" 2 4.26.1

# ── 5k. A marker trailing a bullet is still a marker ─────────────────────────
# The marker used to be anchored to a whole line, so this one was read as an
# ordinary comment, stripped, and the fragment shipped as a patch — a misplaced
# marker declaring the opposite of what its author wrote. Note the prose says
# nothing about breaking: only the marker can refuse this tag.
setup_sandbox
frag 0004k-marker-trailing.md <<'EOF'
<!-- category: Changed -->
- `Scene`'s `onFrame` callback now takes a frame time. <!-- breaking -->
EOF
expect_rc "a marker trailing a bullet refuses a patch tag" 1 4.26.1

# ── 5l. …including the opt-out form ──────────────────────────────────────────
# Same placement, opposite meaning: the prose WOULD fire the heuristic, and the
# trailing `false` must still win. Without 5l, 5k could pass by reading any
# trailing comment as "breaking: true".
setup_sandbox
frag 0004l-marker-trailing-false.md <<'EOF'
<!-- category: Changed -->
- Renamed an internal helper; this is not a source-breaking change for callers
  of the public API, whatever the word breaking above suggests. <!-- breaking: false -->
EOF
expect_rc "a trailing '<!-- breaking: false -->' opts out" 0 4.26.1

# ── 5m. A malformed marker trailing a bullet still errors ────────────────────
# The exit-2 path must not depend on the marker sitting alone on its line
# either, or the typo goes back to being silent in the one placement 5k just
# made legal.
setup_sandbox
frag 0004m-marker-trailing-bad.md <<'EOF'
<!-- category: Changed -->
- Something changed. <!-- breaking: not sure -->
EOF
expect_rc "a malformed marker trailing a bullet exits 2" 2 4.26.1

# ── 6. Substring false positive ──────────────────────────────────────────────
setup_sandbox
frag 0005-substring.md <<'EOF'
<!-- category: Added -->
- A groundbreaking new sample app.
EOF
expect_rc "'groundbreaking' does not refuse a patch tag" 0 4.26.1

# ── 7. The explicit opt-out beats the prose heuristic ────────────────────────
setup_sandbox
frag 0006-optout.md <<'EOF'
<!-- category: Changed -->
<!-- breaking: false -->
- Documents which call sites are breaking; the change itself is additive.
EOF
expect_rc "<!-- breaking: false --> overrides the prose heuristic" 0 4.26.1

# ── 8. Breaking-ness discussed ONLY in an internal note ──────────────────────
# The guard judges the text a reader will see, because that is the text the
# collator publishes. An internal note is not a declaration.
setup_sandbox
frag 0007-note-only.md <<'EOF'
<!-- category: Fixed -->
<!-- RELEASE NOTE: we debated whether this is breaking and concluded it is not.
Not breaking, not source-breaking, breaking nothing. -->
- A crash on rotate is fixed.
EOF
expect_rc "'breaking' inside an internal note is not a declaration" 0 4.26.1

# ── 9. Category-independence ─────────────────────────────────────────────────
# #3037's breaking fragment is tagged `Changed`, but a removed public symbol is
# just as breaking. Filing under the wrong bucket must not buy a patch tag.
setup_sandbox
frag 0008-removed.md <<'EOF'
<!-- category: Removed -->
<!-- breaking -->
- The deprecated `Scene {}` overload is gone.
EOF
expect_rc "a breaking 'Removed' fragment also refuses a patch tag" 1 4.26.1

# ── 10. Malformed input is exit 2, never a silent pass ───────────────────────
setup_sandbox
frag 0009-bad-marker.md <<'EOF'
<!-- category: Changed -->
<!-- breaking: maybe -->
- Something changed.
EOF
expect_rc "an unrecognised marker value is a hard error, not a guess" 2 4.26.1

setup_sandbox
frag 0010-unterminated.md <<'EOF'
<!-- category: Changed -->
<!-- RELEASE NOTE: never closed
- This is source-breaking.
EOF
expect_rc "an unterminated '<!--' is a hard error" 2 4.26.1

# ── 11. Post-collation mode — CHANGELOG's newest section IS the target ───────
# release-checklist.sh runs after collation. The previous release must then be
# read from the SECOND section, or every post-collation run would compare the
# target against itself and read as a minor bump.
setup_sandbox
printf '# Changelog\n\n## Unreleased\n\n## v4.26.1 — 2026-08-07\n\n### Changed\n\n- COLLATED\n\n## v4.26.0 — 2026-07-01\n\n### Fixed\n\n- PRIOR\n' \
  > "$ROOT/CHANGELOG.md"
frag 0011-late.md <<'EOF'
<!-- breaking -->
- Still pending at checklist time.
EOF
expect_rc "post-collation: previous read past a section naming the target" 1 4.26.1

# ── 12. No prior release → "not measured", reported, never a fake pass ───────
setup_sandbox
printf '# Changelog\n\n## Unreleased\n' > "$ROOT/CHANGELOG.md"
frag 0012-fresh.md <<'EOF'
<!-- breaking -->
- First ever release.
EOF
expect_rc "no prior release → exit 0" 0 4.26.1
grep -qi 'not measured' <<<"$OUT" \
  && ok "…and says the bump level was NOT MEASURED" \
  || bad "…but reports it as if it had been checked"

# ── 13. --previous overrides the CHANGELOG derivation ────────────────────────
setup_sandbox
frag 0013-override.md <<'EOF'
<!-- breaking -->
- Breaking.
EOF
expect_rc "--previous 4.26.0 makes 4.26.9 patch-level → refused" 1 4.26.9 --previous 4.26.0
expect_rc "--previous 4.25.0 makes 4.26.9 a minor bump → allowed" 0 4.26.9 --previous 4.25.0

# ── Mutation tests ───────────────────────────────────────────────────────────
# Sections 4 and 5 pull in opposite directions: one demands that prose fires,
# the other that a negated form does not. A heuristic can satisfy either alone
# by doing nothing / everything, so each half is mutated separately.

# Mutation A: neutralise the prose heuristic entirely. Section 4 must go green
# for the mutant (i.e. the refusal disappears).
MUT_A="$TMP/lib-no-prose.sh"
cp "$LIB_SRC" "$MUT_A"
printf '\nfrag_prose_claims_breaking() { return 1; }\n' >> "$MUT_A"
setup_sandbox "$MUT_A"
frag 0003-prose.md <<'EOF'
<!-- category: Changed -->
- Because the change is source-breaking, it ships in a minor release, never a patch.
EOF
run_guard 4.26.1
if [ "$GUARD_RC" = "0" ]; then
  ok "mutation killed — with no prose heuristic, #3037's fragment sails through a patch tag"
else
  bad "MUTATION SURVIVED — section 4 passes even with the prose heuristic disabled,"
  bad "  so something else is producing that refusal"
fi

# Mutation B: keep the heuristic but drop its NEGATION handling. Section 5's
# fixture must then be refused — proving the "non-breaking" exclusion is
# load-bearing and not incidentally satisfied by the word ordering.
MUT_B="$TMP/lib-no-negation.sh"
cp "$LIB_SRC" "$MUT_B"
cat >> "$MUT_B" <<'EOF'

frag_prose_claims_breaking() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | grep -qE '(^|[^a-z0-9])breaking'
}
EOF
setup_sandbox "$MUT_B"
frag 0004-negated.md <<'EOF'
<!-- category: Added -->
- The modifier is opt-in and non-breaking — a scene without the modifier builds
  its content exactly as before.
EOF
run_guard 4.26.1
if [ "$GUARD_RC" = "1" ]; then
  ok "mutation killed — without negation handling, 'non-breaking' blocks a patch release"
else
  bad "MUTATION SURVIVED — section 5 passes with negation handling removed, so it"
  bad "  is not what keeps 'non-breaking' fragments from blocking releases"
fi

echo
echo "check-breaking-change-bump: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
