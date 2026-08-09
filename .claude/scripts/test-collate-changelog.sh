#!/usr/bin/env bash
# Hermetic self-test for collate-changelog.sh — the fragment→release-notes
# contract: which bucket a bullet lands in, and what NEVER reaches the public
# page.
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
# The second contract (#3037) is CONFIDENTIALITY. The parse loop intercepted the
# single-line `<!-- category: X -->` shape and appended every other line
# verbatim, so a nine-line `<!-- RELEASE NOTE: ... -->` block — maintainer
# sign-off, publish-rn / GITHUB_REF_NAME mechanics — was one release away from
# being published as user-facing notes. It was deleted by hand; no test
# exercised the path, so nothing would have caught the next one. Sections 6-10
# below pin it, including the failure mode that a naive fix introduces: an
# unterminated `<!--` that silently swallows every bullet after it.
#
# Fixtures only: a fake repo root (its own .claude/scripts/, changelog.d/ and
# CHANGELOG.md) with a copy of the script under test, its lib, and the
# breaking-change guard it refuses to run without. No network, no git, no writes
# outside $TMP.

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
setup_sandbox() { # collator_path [lib_path] -> sets $ROOT
  ROOT="$TMP/repo"
  rm -rf "$ROOT"
  mkdir -p "$ROOT/.claude/scripts/lib" "$ROOT/changelog.d"
  cp "$1" "$ROOT/.claude/scripts/collate-changelog.sh"
  # The collator sources its lib and REFUSES to run without the breaking-change
  # guard, so both travel with it into the sandbox.
  cp "${2:-$SCRIPT_DIR/lib/changelog-fragment.sh}" "$ROOT/.claude/scripts/lib/changelog-fragment.sh"
  cp "$SCRIPT_DIR/check-breaking-change-bump.sh" "$ROOT/.claude/scripts/"
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

  # 6. The #3037 shape: a MULTI-LINE internal note. Every line of it must
  #    disappear, and the real bullets around it must survive in place.
  cat > "$ROOT/changelog.d/0006-internal-note.md" <<'EOF'
<!-- category: Fixed -->
<!-- RELEASE NOTE (maintainer-only, do not ship):
SECRET-INTERNAL-LINE-A
publish-rn derives VERSION=${GITHUB_REF_NAME#v}, so SECRET-INTERNAL-LINE-B
signed off by the maintainer — SECRET-INTERNAL-LINE-C
-->
- MARKER-AFTER-NOTE
EOF

  # 7. A comment that shares its line with real text, plus a single-line note of
  #    its own. Stripping the whole LINE would eat the bullet; stripping nothing
  #    ships the note.
  cat > "$ROOT/changelog.d/0007-inline-comment.md" <<'EOF'
<!-- category: Added -->
<!-- SECRET-INTERNAL-LINE-D -->
- MARKER-INLINE tail-kept <!-- SECRET-INTERNAL-LINE-E -->
EOF

  # 8. A category tag QUOTED inside an internal note must not reassign the
  #    bucket. HTML comments do not nest, so the quoted tag's own `-->` closes
  #    the note — the bullet that follows is real prose and belongs to the tag
  #    that was actually declared (Added), never to the quoted one (Docs).
  cat > "$ROOT/changelog.d/0008-quoted-tag.md" <<'EOF'
<!-- category: Added -->
<!-- RELEASE NOTE: for reference the tag we use is
SECRET-INTERNAL-LINE-F
<!-- category: Docs -->
- MARKER-QUOTED-TAG
EOF

  # 9. Delimiters inside Markdown CODE SPANS are literal text. Fragments
  #    document this very convention, so this is the shape an author writes by
  #    accident — and it is not a cosmetic bug: a stripper blind to code spans
  #    opens a comment on the backticked `<!--`, runs past the end of the line,
  #    and swallows the fragment's NEXT category tag, filing
  #    MARKER-AFTER-CODE-SPAN under the previous heading. That happened for
  #    real, in the changelog fragment of the PR that added the stripper.
  #
  #    ORDER IS LOAD-BEARING: the unbalanced `<!--` must come LAST. With the
  #    balanced `<!-- category: Docs -->` after it, that span's own `-->` closes
  #    the accidental comment before end-of-line, the next tag survives, and the
  #    fixture passes against a code-span-blind stripper — which is exactly how
  #    the first version of this fixture failed to kill mutation 3.
  cat > "$ROOT/changelog.d/0009-code-span.md" <<'EOF'
<!-- category: Fixed -->
- MARKER-CODESPAN-BULLET The tag is `<!-- category: Docs -->`, and an unterminated `<!--` is a hard error.
<!-- category: Added -->
- MARKER-AFTER-CODE-SPAN
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

# The tag LINES themselves must never survive into the release notes. Anchored
# on the line shape, not on the substring `category:` — a bullet may legitimately
# quote a tag inside a code span, and fixture 9 does.
if grep -qE '^[[:space:]]*<!--[[:space:]]*category:' "$ROOT/CHANGELOG.md"; then
  echo "  ✗ a category tag line leaked into CHANGELOG.md"; FAIL=$((FAIL + 1))
else
  echo "  ✓ category tag lines consumed, not emitted"; PASS=$((PASS + 1))
fi

# ── Internal notes never reach the public page (#3037) ───────────────────────
# Counted, not grep-tested-once: five distinct internal lines across three
# comment shapes, so a stripper that handles only the shape it was written for
# still fails here.
LEAKED="$(grep -c 'SECRET-INTERNAL-LINE' "$ROOT/CHANGELOG.md" || true)"
check "no internal comment line reaches CHANGELOG.md" 0 "$LEAKED"

# ...and the bullets AROUND the notes survive, in the right bucket. Without
# this half, a stripper that deleted the whole fragment would score a perfect
# zero above.
check "multi-line note stripped, bullet kept"  Fixed "$(section_of MARKER-AFTER-NOTE)"
check "inline note stripped, bullet kept"      Added "$(section_of MARKER-INLINE)"
check "quoted category tag stays inert"        Added "$(section_of MARKER-QUOTED-TAG)"

# The text sharing a line with an inline comment must survive whole — not
# truncated at the `<!--`, and with no trailing whitespace left where the
# comment used to be.
if grep -q '^- MARKER-INLINE tail-kept$' "$ROOT/CHANGELOG.md"; then
  echo "  ✓ inline comment removed in place, line text intact and right-trimmed"; PASS=$((PASS + 1))
else
  echo "  ✗ inline-comment line mangled: $(grep -n 'MARKER-INLINE' "$ROOT/CHANGELOG.md" | head -1)"
  FAIL=$((FAIL + 1))
fi

# No BARE comment delimiter survives — code-span content is removed first, since
# a delimiter inside backticks is text a reader is meant to see.
if sed 's/`[^`]*`//g' "$ROOT/CHANGELOG.md" | grep -qE '<!--|-->'; then
  echo "  ✗ a bare HTML comment delimiter leaked into CHANGELOG.md"; FAIL=$((FAIL + 1))
else
  echo "  ✓ no bare HTML comment delimiter in the generated section"; PASS=$((PASS + 1))
fi

# ── Code spans are literal text, not delimiters ──────────────────────────────
# The bug this caught for real: a backticked `<!--` opening a comment that ran
# off the end of the line and ate the fragment's NEXT category tag, so the
# bullets after it were filed under the previous heading.
check "code-spanned '<!--' does not swallow the next tag" Added \
  "$(section_of MARKER-AFTER-CODE-SPAN)"
check "the bullet carrying it stays in its own bucket"    Fixed \
  "$(section_of MARKER-CODESPAN-BULLET)"
if grep -q 'The tag is `<!-- category: Docs -->`, and an unterminated `<!--` is a hard error.' \
     "$ROOT/CHANGELOG.md"; then
  echo "  ✓ code-spanned delimiters survive verbatim in the published bullet"; PASS=$((PASS + 1))
else
  echo "  ✗ code-spanned delimiters were stripped out of the bullet text:"
  grep -n 'MARKER-CODESPAN-BULLET ' "$ROOT/CHANGELOG.md" | sed 's/^/        /'
  FAIL=$((FAIL + 1))
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
check "--dry-run keeps fragments"           9 "$(find "$ROOT/changelog.d" -name '*.md' | wc -l | tr -d ' ')"

# ── An unterminated `<!--` must FAIL, not silently truncate ──────────────────
# The failure mode a comment stripper introduces: one missing `-->` and every
# bullet after it vanishes from a release section nobody diffs, with the source
# fragment deleted on the way out. Loud beats lossy.
setup_sandbox "$COLLATOR"
cat > "$ROOT/changelog.d/0010-unterminated.md" <<'EOF'
<!-- category: Fixed -->
<!-- RELEASE NOTE: someone forgot to close this
- MARKER-SWALLOWED
EOF
BEFORE="$(cat "$ROOT/CHANGELOG.md")"
set +e
UNTERM_OUT="$(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --date 2026-08-01 2>&1)"
UNTERM_RC=$?
set -e
if [ "$UNTERM_RC" -ne 0 ] && grep -q 'unterminated' <<<"$UNTERM_OUT"; then
  echo "  ✓ unterminated '<!--' is a hard error naming the problem"; PASS=$((PASS + 1))
else
  echo "  ✗ unterminated '<!--' accepted (rc=$UNTERM_RC) — bullets would vanish silently"
  FAIL=$((FAIL + 1))
fi
check "unterminated comment left CHANGELOG.md untouched" "$BEFORE" "$(cat "$ROOT/CHANGELOG.md")"
check "unterminated comment consumed no fragment" 10 \
  "$(find "$ROOT/changelog.d" -name '*.md' | wc -l | tr -d ' ')"

# ── The collator ACTS on the guard's verdict ─────────────────────────────────
# The presence check below proves the guard is installed; this proves it is
# CONSULTED. Collating v4.0.1 (patch over the fixture's v4.0.0) with a breaking
# fragment pending must abort before anything is written or deleted — the guard
# runs here because collation is the last moment the marker exists.
setup_sandbox "$COLLATOR"
cat > "$ROOT/changelog.d/0011-breaking.md" <<'EOF'
<!-- category: Changed -->
<!-- breaking -->
- MARKER-BREAKING
EOF
BEFORE="$(cat "$ROOT/CHANGELOG.md")"
set +e
BRK_OUT="$(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.0.1 --date 2026-08-01 2>&1)"
BRK_RC=$?
set -e
if [ "$BRK_RC" -ne 0 ] && grep -q 'REFUSED' <<<"$BRK_OUT"; then
  echo "  ✓ breaking fragment + patch version aborts collation"; PASS=$((PASS + 1))
else
  echo "  ✗ collation proceeded on a patch version with a breaking fragment (rc=$BRK_RC)"
  FAIL=$((FAIL + 1))
fi
check "refused collation left CHANGELOG.md untouched" "$BEFORE" "$(cat "$ROOT/CHANGELOG.md")"
check "refused collation deleted no fragment" 10 \
  "$(find "$ROOT/changelog.d" -name '*.md' | wc -l | tr -d ' ')"

# Same fragments, a MINOR target: collation proceeds. Without this the check
# above could be satisfied by a collator that refuses every patch version.
set +e
(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --date 2026-08-01 >/dev/null 2>&1)
BRK_MINOR_RC=$?
set -e
check "the same breaking fragment collates on a MINOR version" 0 "$BRK_MINOR_RC"
check "…and its bullet is in the notes" Changed "$(section_of MARKER-BREAKING)"

# ── The collator REFUSES to run without the breaking-change guard ────────────
# #2988: a gate that self-skips when its script is absent reads as "passed".
setup_sandbox "$COLLATOR"
rm -f "$ROOT/.claude/scripts/check-breaking-change-bump.sh"
set +e
NOGUARD_OUT="$(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --date 2026-08-01 2>&1)"
NOGUARD_RC=$?
set -e
if [ "$NOGUARD_RC" -ne 0 ] && grep -q 'guard' <<<"$NOGUARD_OUT"; then
  echo "  ✓ missing breaking-change guard refuses collation (does not self-skip)"; PASS=$((PASS + 1))
else
  echo "  ✗ collation proceeded with the guard absent (rc=$NOGUARD_RC)"; FAIL=$((FAIL + 1))
fi

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

# ── Mutation test 2: the stripper is what keeps internal notes out ───────────
# Neutralises `frag_strip_comments_line` into the identity function — the exact
# pre-#3037 behaviour, where a fragment's lines reached `body` untouched. It is
# the LIB that is mutated, not the collator's loop: appending `$line` instead of
# `$FRAG_STRIPPED` leaves the stripper running, so the loop's own
# "line contributed only comment" skip still swallows whole-line notes and the
# mutant leaks a single inline note instead of all of them. That weaker mutant
# passed here, and a mutation that only half-fires measures the wrong assertion.
#
# The mutant still emits a complete, well-formed CHANGELOG with every bullet
# present — only the notes come back. That is precisely what shipped.
MUTLIB="$TMP/changelog-fragment-nostrip.sh"
cp "$SCRIPT_DIR/lib/changelog-fragment.sh" "$MUTLIB"
printf '\nfrag_strip_comments_line() { FRAG_STRIPPED="$1"; }\n' >> "$MUTLIB"
setup_sandbox "$COLLATOR" "$MUTLIB"
(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --date 2026-08-01 >/dev/null 2>&1)
MUT_LEAK="$(grep -c 'SECRET-INTERNAL-LINE' "$ROOT/CHANGELOG.md" || true)"
if [ "$MUT_LEAK" -lt 6 ]; then
  echo "  ✗ MUTATION SURVIVED (or only half-fired) — a no-op stripper leaked only"
  echo "    $MUT_LEAK of the 6 internal lines, so the leak assertions above are not"
  echo "    what keeps maintainer notes out of the public release notes"
  FAIL=$((FAIL + 1))
else
  echo "  ✓ mutation killed — a no-op stripper leaks all $MUT_LEAK internal lines"
  PASS=$((PASS + 1))
fi

# ── Mutation test 3: code-span awareness is load-bearing ─────────────────────
# Swaps in the stripper as it was first written — correct about comments, blind
# to Markdown code spans. It still strips every internal note (mutation test 2's
# assertions stay green against it), so only the fixture-9 checks can catch it.
# That is the whole point: this mutant produces a plausible CHANGELOG in which
# two bullets have quietly moved to the wrong heading.
MUTLIB3="$TMP/changelog-fragment-nospans.sh"
cp "$SCRIPT_DIR/lib/changelog-fragment.sh" "$MUTLIB3"
cat >> "$MUTLIB3" <<'MUTEOF'

frag_strip_comments_line() {
    local rest="$1" out=""
    while [ -n "$rest" ]; do
        if [ "$FRAG_IN_COMMENT" = true ]; then
            case "$rest" in
                *'-->'*) rest="${rest#*-->}"; FRAG_IN_COMMENT=false ;;
                *)       rest="" ;;
            esac
        else
            case "$rest" in
                *'<!--'*) out+="${rest%%<!--*}"; rest="${rest#*<!--}"; FRAG_IN_COMMENT=true ;;
                *)        out+="$rest"; rest="" ;;
            esac
        fi
    done
    FRAG_STRIPPED="$out"
}
MUTEOF
setup_sandbox "$COLLATOR" "$MUTLIB3"
(cd "$ROOT" && bash .claude/scripts/collate-changelog.sh 4.1.0 --date 2026-08-01 >/dev/null 2>&1)
MUT3_SECTION="$(section_of MARKER-AFTER-CODE-SPAN)"
if [ "$MUT3_SECTION" = "Added" ]; then
  echo "  ✗ MUTATION SURVIVED — a code-span-blind stripper still files"
  echo "    MARKER-AFTER-CODE-SPAN under Added, so fixture 9 is not what pins it"
  FAIL=$((FAIL + 1))
else
  echo "  ✓ mutation killed — code-span-blind stripper misfiles it under '${MUT3_SECTION:-<none>}'"
  PASS=$((PASS + 1))
fi

echo
echo "collate-changelog: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
