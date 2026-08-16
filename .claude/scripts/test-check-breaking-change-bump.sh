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
# Since #3061 there is a second mode, `--from-changelog`, and one extra property
# to hold: THE SAME TEXT MUST GIVE THE SAME VERDICT BEFORE AND AFTER COLLATION.
# release.yml runs off a `v*` tag, by which time collate-changelog.sh has already
# consumed and DELETED every `changelog.d/*.md` — so the default mode there loops
# zero times and prints a green tick over an unread release. Sections 14-20 pin
# the mode that reads the collated `## vX.Y.Z` section instead, its refusal to
# treat "nothing to examine" as a pass, and the equality between the two modes.
#
# "The same text" is load-bearing in that sentence: HTML comments are NOT text
# that crosses collation, they are deleted by it. Section 21 pins each marker
# shape's fate, and the fixtures go through `collate_body` so the suite can
# never again assert the invariant against a section the pipeline cannot
# produce — which is how the doomed-opt-out false refusal hid until review.
#
# Hermetic: a fake repo root with a copy of the script and its lib. The script
# derives REPO_ROOT from its own path, so the real tree is never read or
# written. No network, no git.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD_SRC="$SCRIPT_DIR/check-breaking-change-bump.sh"
LIB_SRC="$SCRIPT_DIR/lib/changelog-fragment.sh"

# Sourced for ONE purpose: `collate_body` below reproduces what collation does
# to a fragment, using the same primitives collate-changelog.sh uses. A
# hand-rolled approximation would drift from the collator and the fixtures
# would stop being what the pipeline produces.
# shellcheck source=lib/changelog-fragment.sh
. "$LIB_SRC"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
ok()  { printf '  \xE2\x9C\x93 %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  \xE2\x9C\x97 %s\n' "$1"; FAIL=$((FAIL + 1)); }

# Build a sandbox repo root. $1 = lib to install, $2 = guard to install (both
# default to the real ones, so a mutation can swap either). Sets $ROOT.
setup_sandbox() { # [lib_path] [guard_path]
  ROOT="$TMP/repo"
  rm -rf "$ROOT"
  mkdir -p "$ROOT/.claude/scripts/lib" "$ROOT/changelog.d"
  cp "${2:-$GUARD_SRC}" "$ROOT/.claude/scripts/check-breaking-change-bump.sh"
  cp "${1:-$LIB_SRC}" "$ROOT/.claude/scripts/lib/changelog-fragment.sh"
  # Newest shipped release = v4.26.0, so 4.26.1 is patch-level and 4.27.0 is not.
  printf '# Changelog\n\n## Unreleased\n\n## v4.26.0 — 2026-07-01\n\n### Fixed\n\n- PRIOR\n\n## v4.25.0 — 2026-06-01\n\n### Fixed\n\n- OLDER\n' \
    > "$ROOT/CHANGELOG.md"
}

frag() { # filename, content on stdin
  cat > "$ROOT/changelog.d/$1"
}

# A section written the way a HUMAN could have (markers intact). Used only for
# the hand-written cases; collation cannot produce this — see below.
changelog_section_raw() { # version, body on stdin
  { printf '# Changelog\n\n## Unreleased\n\n## v%s — 2026-08-07\n\n' "$1"
    cat
    printf '\n## v4.26.0 — 2026-07-01\n\n### Fixed\n\n- PRIOR\n'
  } > "$ROOT/CHANGELOG.md"
  rm -f "$ROOT"/changelog.d/*.md
}

# The tree as release.yml actually sees it: the fragments are GONE and their
# text lives in a collated `## v$1` section — **comment-stripped**, because
# `collate-changelog.sh` runs `frag_strip_comments_line` over every line. A
# helper that copied the body verbatim would leave `<!-- breaking: false -->`
# standing in a section where it can never appear, and every invariant case
# below would pass against a file the pipeline cannot produce. (Flagged in
# review of #3209; the divergence it hid was real.)
collate_body() { # stdin → stdout, mirroring collate-changelog.sh's line loop
  local line was_in
  frag_strip_reset
  while IFS= read -r line || [ -n "$line" ]; do
    if [ "$FRAG_IN_COMMENT" = false ] && frag_is_category_tag_line "$line"; then
      continue
    fi
    was_in="$FRAG_IN_COMMENT"
    frag_strip_comments_line "$line"
    if [ -z "${FRAG_STRIPPED//[[:space:]]/}" ] &&
       { [ "$was_in" = true ] || [ -n "${line//[[:space:]]/}" ]; }; then
      continue
    fi
    printf '%s\n' "$FRAG_STRIPPED"
  done
}

changelog_collated() { # version, body on stdin
  changelog_section_raw "$1" < <(collate_body)
}

# A copy of the guard with ONE defect applied. Refuses a no-op sed: a mutation
# that changes no bytes proves nothing, it means this suite has gone stale.
mutant_guard() { # name sed-expr  → prints the path
  local name="$1" expr="$2" out="$TMP/guard-$1.sh"
  sed "$expr" "$GUARD_SRC" > "$out"
  if cmp -s "$out" "$GUARD_SRC"; then
    bad "mutation '$name' changed nothing — check-breaking-change-bump.sh no longer matches this sed"
    return 1
  fi
  printf '%s' "$out"
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
# Same placement, opposite meaning. Without 5l, 5k could pass by reading any
# trailing comment as "breaking: true".
#
# The exit code that proves the trailing `false` was READ is 2, not 0: since
# #3209 an opt-out doing real work against its own prose is refused at fragment
# time (section 21c — the marker cannot survive collation, so it would become a
# false refusal after the tag). Had the trailing marker been missed entirely,
# the prose path would have fired and this would be exit 1. The three outcomes
# stay distinguishable, which is what this case is for.
setup_sandbox
frag 0004l-marker-trailing-false.md <<'EOF'
<!-- category: Changed -->
- Renamed an internal helper; this is not a source-breaking change for callers
  of the public API, whatever the word breaking above suggests. <!-- breaking: false -->
EOF
expect_rc "a trailing '<!-- breaking: false -->' is read (exit 2, not the prose's 1)" 2 4.26.1

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

# ── 5n. A malformed span AFTER a valid one still errors ──────────────────────
# Scanning must not stop on the first breaking-shaped span: returning the valid
# one lets a typo sit next to a good marker and be swallowed, which is the very
# silence 5k removed.
setup_sandbox
frag 0004n-marker-two-spans.md <<'EOF'
<!-- category: Changed -->
- Something changed. <!-- breaking: false --> <!-- breaking: maybe -->
EOF
expect_rc "a malformed span after a valid one exits 2" 2 4.26.1

# ── 6. Substring false positive ──────────────────────────────────────────────
setup_sandbox
frag 0005-substring.md <<'EOF'
<!-- category: Added -->
- A groundbreaking new sample app.
EOF
expect_rc "'groundbreaking' does not refuse a patch tag" 0 4.26.1

# ── 7. The explicit opt-out beats the prose heuristic ────────────────────────
# …and in a FRAGMENT that means exit 2, not exit 0 (section 21c): the marker
# beats the prose here, and dies at collation, so a release-time re-read would
# refuse a release this one waved through. Exit 1 would mean the marker was not
# read at all.
setup_sandbox
frag 0006-optout.md <<'EOF'
<!-- category: Changed -->
<!-- breaking: false -->
- Documents which call sites are breaking; the change itself is additive.
EOF
expect_rc "<!-- breaking: false --> overrides the prose heuristic (exit 2, not 1)" 2 4.26.1
grep -qF '0006-optout.md' <<<"$OUT" \
  && ok "…and the error names the fragment to reword" \
  || bad "…without naming the file"

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

# ── 14. #3061 — the tag-time tree, in both modes ─────────────────────────────
# The exact state release.yml runs in: collation has happened, changelog.d/ is
# empty, and the only copy of the text is the `## v4.26.1` section. The default
# mode has nothing left to read; --from-changelog is the whole point.
setup_sandbox
changelog_collated 4.26.1 <<'EOF'
### Changed

- **`TapEvent.nodeName` is now typed `string | null`** (React Native). Under
  `strictNullChecks` this is source-breaking for code assigning `nodeName` to a
  `string | undefined` binding.
EOF
expect_rc "--from-changelog REFUSES breaking prose in the collated section" 1 4.26.1 --from-changelog
grep -q 'CHANGELOG.md § v4\.26\.1' <<<"$OUT" \
  && ok "…and names the section it read" \
  || bad "…but does not say what it examined"

# The false green this mode exists to close, asserted rather than described: the
# same tree, the same guard, without the flag.
expect_rc "…while the DEFAULT mode passes the identical tree (the #3061 hole)" 0 4.26.1
grep -q '0 pending fragment(s)' <<<"$OUT" \
  && ok "…and its green tick admits it examined 0 fragments" \
  || bad "…without even reporting that it read nothing"

# ── 15. Positive cue — the same section on a minor tag ───────────────────────
setup_sandbox
changelog_collated 4.27.0 <<'EOF'
### Changed

- This is source-breaking for code under `strictNullChecks`.
EOF
expect_rc "the same breaking section is allowed on a MINOR tag" 0 4.27.0 --from-changelog

# ── 16. Markers in a section are read — but only a human can put them there ──
# `changelog_section_raw`, deliberately: collation strips every comment, so
# these two fixtures are the hand-written case. Section 21 covers what actually
# happens to a marker that goes through the collator.
setup_sandbox
changelog_section_raw 4.26.1 <<'EOF'
### Changed

<!-- breaking -->
- A public symbol changed shape.
EOF
expect_rc "an explicit marker inside the section refuses a patch tag" 1 4.26.1 --from-changelog

setup_sandbox
changelog_section_raw 4.26.1 <<'EOF'
### Changed

<!-- breaking: false -->
- The wire format is source-breaking for nobody; this is a rewording.
EOF
expect_rc "'<!-- breaking: false -->' in the section still wins over the prose" 0 4.26.1 --from-changelog

# ── 17. Nothing to examine is a FAILURE, never a silent pass ─────────────────
# Requirement (b) of #3061: a gate that finds nothing must say so. Each of these
# is a way the section can be absent, and each was a way to publish unread.
setup_sandbox
changelog_collated 4.26.1 <<'EOF'
- Something.
EOF
expect_rc "a tag with no matching section is exit 2, not a pass" 2 4.26.2 --from-changelog
grep -q "no '## v4\.26\.2' section" <<<"$OUT" \
  && ok "…and names the section it could not find" \
  || bad "…without saying what was missing"

setup_sandbox
changelog_collated 4.26.1 <<'EOF'

EOF
expect_rc "a section with an empty body is exit 2, not a pass" 2 4.26.1 --from-changelog

setup_sandbox
rm -f "$ROOT/CHANGELOG.md"
expect_rc "no CHANGELOG.md at all is exit 2, not a pass" 2 4.26.1 --from-changelog

# `## v4.26.10` must not answer for `4.26.1` — the header boundary is the only
# thing between the guard and judging a different release's notes.
setup_sandbox
changelog_collated 4.26.10 <<'EOF'
- This is source-breaking.
EOF
expect_rc "'## v4.26.10' does not satisfy a query for 4.26.1" 2 4.26.1 --from-changelog

# ── 18. Section scoping — the neighbours are not on trial ────────────────────
# The target section is clean; the PREVIOUS release's section is breaking. A
# guard that read the whole file would refuse every patch after a breaking minor.
setup_sandbox
printf '# Changelog\n\n## Unreleased\n\n## v4.26.1 — 2026-08-07\n\n### Fixed\n\n- A crash on rotate is fixed.\n\n## v4.26.0 — 2026-07-01\n\n### Changed\n\n- This was source-breaking, which is why it shipped as a minor.\n' \
  > "$ROOT/CHANGELOG.md"
expect_rc "a breaking PREVIOUS section does not condemn a clean patch" 0 4.26.1 --from-changelog

# ── 19. Un-collated fragments are still judged in changelog mode ─────────────
# A half-collated release ships both. If this mode only read the section, the
# fragment left behind would be the one place nothing looks.
setup_sandbox
changelog_collated 4.26.1 <<'EOF'
### Fixed

- A crash on rotate is fixed.
EOF
frag 0014-straggler.md <<'EOF'
<!-- breaking -->
- Missed by the collator.
EOF
expect_rc "a straggler fragment is judged alongside the section" 1 4.26.1 --from-changelog
grep -qF 'of 2 source(s) examined' <<<"$OUT" \
  && ok "…and the refusal counts BOTH sources it read" \
  || bad "…but the refusal hides how many sources were examined"
grep -qF '0014-straggler.md' <<<"$OUT" \
  && ok "…and names the fragment, not just the section" \
  || bad "…without naming the fragment that caused the refusal"

# ── 20. THE INVARIANT — same text, same verdict, either side of collation ────
# This is the property that makes the second mode safe to trust. Anything else
# means a release is judged by where its text happens to live at the moment.
invariant_case() { # label expected_rc, text on stdin
  local label="$1" want="$2" text rc_frag rc_chg
  text="$(cat)"
  setup_sandbox
  printf '%s\n' "$text" > "$ROOT/changelog.d/9000-invariant.md"
  run_guard 4.26.1
  rc_frag="$GUARD_RC"
  setup_sandbox
  changelog_collated 4.26.1 <<<"$text"
  run_guard 4.26.1 --from-changelog
  rc_chg="$GUARD_RC"
  if [ "$rc_frag" = "$want" ] && [ "$rc_chg" = "$want" ]; then
    ok "invariant: $label → exit $want as a fragment AND as a collated section"
  else
    bad "invariant BROKEN: $label — fragment mode exit $rc_frag, changelog mode exit $rc_chg, both should be $want"
    printf '%s\n' "$OUT" | sed 's/^/        /'
  fi
}

invariant_case "breaking prose" 1 <<'EOF'
- Because the change is source-breaking, it ships in a minor release, never a patch.
EOF

invariant_case "a negated mention" 0 <<'EOF'
- The modifier is opt-in and non-breaking — a scene without it builds as before.
EOF

invariant_case "'groundbreaking'" 0 <<'EOF'
- A groundbreaking new renderer lands behind a flag.
EOF

invariant_case "the word inside a code span" 0 <<'EOF'
- Document the `breaking` marker in the contributor guide.
EOF

invariant_case "an opt-out the prose does not need" 0 <<'EOF'
<!-- breaking: false -->
- A crash on rotate is fixed.
EOF

# ── 21. What collation consumes, and why that leaves no hole ─────────────────
# The invariant above is stated over text that CROSSES collation. Markers do
# not: `frag_strip_comments_line` deletes every comment. So for each marker
# shape, the fragment verdict and the collated-section verdict can differ — and
# the only thing that makes that safe is that collation itself never runs.
# Reported in review of #3209, where the test helper hid it by leaving markers
# standing in fixtures the pipeline cannot produce.

# (a) `<!-- breaking -->` over neutral prose: refused while the marker exists,
#     so collate-changelog.sh (which runs this guard first) never writes the
#     section…
setup_sandbox
frag 0015-marker-only.md <<'EOF'
<!-- breaking -->
- A public symbol changed shape.
EOF
expect_rc "a marker-only fragment is refused BEFORE collation can consume it" 1 4.26.1

# (b) …which matters, because once collated the same text says nothing at all.
#     This assertion is the divergence itself, pinned so it cannot widen.
setup_sandbox
changelog_collated 4.26.1 <<'EOF'
<!-- breaking -->
- A public symbol changed shape.
EOF
expect_rc "…and post-collation that marker is simply gone from the section" 0 4.26.1 --from-changelog
grep -qF 'breaking' "$ROOT/CHANGELOG.md" \
  && bad "the fixture still carries a marker — collate_body is not stripping" \
  || ok "…the fixture proves it: no marker survives into the section"

# (c) The mirror, and the one that bites a GOOD release: an opt-out doing real
#     work against its own prose passes at collation and is refused at release
#     time, after the tag, with all six publishers frozen. Verified end-to-end
#     against the real collate-changelog.sh. It is now caught here instead,
#     while the fragment still exists.
setup_sandbox
frag 0016-doomed-optout.md <<'EOF'
<!-- category: Fixed -->
<!-- breaking: false -->
- **A wording change.** The docs used to call this breaking; it never was.
EOF
expect_rc "an opt-out that cannot survive collation is exit 2, at fragment time" 2 4.26.1
grep -qF 'cannot survive collation' <<<"$OUT" \
  && ok "…and says why, naming the fragment" \
  || bad "…without explaining what the author has to change"
grep -qF 'code span' <<<"$OUT" \
  && ok "…and offers the reword that works on both sides" \
  || bad "…without offering a way out"

# …and the offered reword really does work, on both sides.
setup_sandbox
frag 0016-doomed-optout.md <<'EOF'
<!-- category: Fixed -->
<!-- breaking: false -->
- **A wording change.** The docs used to call this `breaking`; it never was.
EOF
expect_rc "the suggested code-span reword passes as a fragment" 0 4.26.1
setup_sandbox
changelog_collated 4.26.1 <<'EOF'
<!-- category: Fixed -->
<!-- breaking: false -->
- **A wording change.** The docs used to call this `breaking`; it never was.
EOF
expect_rc "…and still passes once collated, with the marker gone" 0 4.26.1 --from-changelog

# (d) A minor bump is allowed to carry the marker, so this fragment DOES reach
#     the collator — and the section it produces is judged on a minor tag,
#     where the answer is the same either way.
setup_sandbox
changelog_collated 4.27.0 <<'EOF'
<!-- breaking -->
- A public symbol changed shape.
EOF
expect_rc "a collated breaking MINOR section passes, as it did as a fragment" 0 4.27.0 --from-changelog

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

# Mutation C: accept `--from-changelog` and ignore it — the naive #3061 fix,
# and the only failure mode that looks green in every log. Section 14 must then
# revert to the hole it documents: the mutant sees an empty changelog.d/ and
# publishes a source-breaking patch with a tick.
if MUT_C="$(mutant_guard no-changelog-mode \
    's/--from-changelog) FROM_CHANGELOG=true;/--from-changelog) FROM_CHANGELOG=false;/')"; then
  setup_sandbox "$LIB_SRC" "$MUT_C"
  changelog_collated 4.26.1 <<'EOF'
### Changed

- Under `strictNullChecks` this is source-breaking for existing callers.
EOF
  run_guard 4.26.1 --from-changelog
  if [ "$GUARD_RC" = "0" ]; then
    ok "mutation killed — a --from-changelog that falls back to fragments passes #3061's own release"
  else
    bad "MUTATION SURVIVED — section 14 refuses even with the changelog mode disabled,"
    bad "  so that refusal is not coming from reading the collated section"
  fi
fi

# Mutation D: keep the mode, remove its discovery floor. An empty section then
# reads as "examined 0 lines, nothing breaking" — a pass earned by measuring
# nothing, which is the same bug in a different place (#3050).
if MUT_D="$(mutant_guard no-empty-floor \
    's/if \[ "\$SCAN_LINES" -eq 0 \]; then/if false; then/')"; then
  setup_sandbox "$LIB_SRC" "$MUT_D"
  changelog_collated 4.26.1 <<'EOF'

EOF
  run_guard 4.26.1 --from-changelog
  if [ "$GUARD_RC" = "0" ]; then
    ok "mutation killed — without the floor, an EMPTY section is reported as a pass"
  else
    bad "MUTATION SURVIVED — section 17 rejects an empty section with the floor removed,"
    bad "  so the floor is not what makes 'nothing to examine' a failure"
  fi
fi

# Mutation E: drop the doomed-opt-out check. Section 21(c) must then revert to
# the false refusal it documents — the fragment sails through collation and
# only fails once the tag is pushed.
if MUT_E="$(mutant_guard no-doomed-optout \
    's/if \[ "\${2:-}" = "fragment" \] && frag_prose_claims_breaking "\$SCAN_BODY"; then/if false; then/')"; then
  setup_sandbox "$LIB_SRC" "$MUT_E"
  frag 0016-doomed-optout.md <<'EOF'
<!-- category: Fixed -->
<!-- breaking: false -->
- **A wording change.** The docs used to call this breaking; it never was.
EOF
  run_guard 4.26.1
  if [ "$GUARD_RC" = "0" ]; then
    ok "mutation killed — without the check, a doomed opt-out passes collation and blocks the tag"
  else
    bad "MUTATION SURVIVED — section 21(c) still exits 2 with the check removed,"
    bad "  so something else is producing that error"
  fi
fi

echo
echo "check-breaking-change-bump: $PASS passed, $FAIL failed"

# A skipped case is not a passed case: every assertion above runs unconditionally
# except the two mutant builds, which fail the suite rather than vanish. Raise
# this when adding a case — a suite that silently shrinks is how #2988 happened.
EXPECTED_ASSERTIONS=66
TOTAL=$((PASS + FAIL))
if [ "$TOTAL" -ne "$EXPECTED_ASSERTIONS" ]; then
  printf '  \xE2\x9C\x97 %d assertions ran, expected %d — cases were skipped, not passed\n' \
    "$TOTAL" "$EXPECTED_ASSERTIONS"
  exit 1
fi

[ "$FAIL" -eq 0 ]
