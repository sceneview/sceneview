#!/usr/bin/env bash
#
# test-check-sceneview-swift-urls.sh — hermetic self-test for the archived-SPM
# -mirror gate (#1237, hardened in #3068).
#
# The gate has two passes and they pull in opposite directions, which is
# exactly why neither can be left unpinned:
#
#   1. Any `sceneview-swift` reference outside the ALLOW list fails.
#   2. `CHANGELOG.md` and `changelog.d/*.md` are the only entries allowlisted
#      WHOLESALE rather than by exact path — a release note must be free to
#      NAME the retired mirror without being blocked, and must never ship a
#      resolvable PIN to it. "Review will catch that" is a hope, not a gate.
#
# Both directions are asserted for both passes: a gate that only ever says no
# blocks legitimate release notes, and one that only ever says yes is prose.
#
# Scratch repos, no network. The gate reads the tracked tree via `git grep`,
# so every fixture is committed — an untracked fixture would silently assert
# nothing, which is the failure mode #3068 was opened for in the first place.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/check-sceneview-swift-urls.sh"
PASS=0; FAIL=0

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

echo "test-check-sceneview-swift-urls.sh"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

# Fresh repo with one committed file at $2 holding $3.
fixture() {
    local dir="$SCRATCH/$1"; rm -rf "$dir"; mkdir -p "$dir/$(dirname "$2")"
    ( cd "$dir"
      git init -q
      git config user.email t@t.t && git config user.name t
      git config core.hooksPath /dev/null )
    printf '%s\n' "$3" > "$dir/$2"
    ( cd "$dir" && git add -A && git commit -qm base )
    printf '%s' "$dir"
}

run() { ( cd "$1" && bash "$SCRIPT" 2>&1 ); }

MIRROR_PIN='- Install: `.package(url: "https://github.com/sceneview/sceneview-swift.git", from: "4.26.0")`'
MIRROR_PROSE='- The archived sceneview-swift mirror was retired in PR #1215.'

# 1. Pass 1, positive: the mirror URL in a non-allowlisted file still fails.
D="$(fixture reintroduced docs/docs/quickstart-ios.md "$MIRROR_PIN")"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'quickstart-ios.md' <<<"$OUT"; } \
  && ok "mirror URL in a non-allowlisted file → fail, file named" \
  || bad "a reintroduced mirror URL must fail and be named (rc=$RC)"

# 2. Pass 1, negative: a changelog fragment may NAME the retired mirror. This
#    is the hole #3068 closed — the same sentence was blocked as a fragment
#    and allowed the moment collate-changelog.sh merged it into CHANGELOG.md.
D="$(fixture fragment_prose changelog.d/1234-note.md "$MIRROR_PROSE")"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "changelog fragment NAMING the retired mirror → allowed" \
  || bad "a fragment must be free to describe the mirror's retirement (rc=$RC)"

# 3. Pass 2, positive: that same fragment may not PIN it. A version constraint
#    next to the URL is a copy-pasteable install line that does not resolve,
#    and no amount of release-note prose around it changes that.
D="$(fixture fragment_pin changelog.d/1234-note.md "$MIRROR_PIN")"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'changelog.d/1234-note.md:1' <<<"$OUT"; } \
  && ok "changelog fragment PINNING the mirror → fail, line named" \
  || bad "a live mirror pin must not ride in on the wholesale allowlist (rc=$RC)"

# 4. Same contract for CHANGELOG.md — the fragment's destination, allowlisted
#    wholesale for the same reason and therefore exposed to the same hole.
D="$(fixture changelog_pin CHANGELOG.md "$MIRROR_PIN")"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'CHANGELOG.md:1' <<<"$OUT"; } \
  && ok "CHANGELOG.md PINNING the mirror → fail, line named" \
  || bad "the collated destination must enforce the same rule (rc=$RC)"

# 5. Pass 2, negative: near-adjacency, not `.*`. A prose sentence that merely
#    contains the word "from" downstream of a mirror mention is not a pin —
#    the sibling SPM gate measured what a permissive gap costs: 25 files
#    matched, 10 of them plain prose.
D="$(fixture prose_from changelog.d/1234-note.md \
    '- The sceneview-swift mirror is archived; users migrating from v3 resolve the monorepo root instead.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "prose mentioning the mirror and 'from' separately → not a pin" \
  || bad "a permissive gap turns release-note prose into a false positive (rc=$RC)"

# 6. Pass 1 covers `*.sh`. It did not, which made this very file's ALLOW entry
#    dead surface — the comment claimed a protection no pass applied. A shell
#    script is where a dead URL stops being a bad paste and becomes a command
#    that fails at run time.
D="$(fixture shell_script tools/install-swift.sh \
    'git clone https://github.com/sceneview/sceneview-swift.git')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'tools/install-swift.sh' <<<"$OUT"; } \
  && ok "mirror URL in a tracked .sh → fail, file named" \
  || bad "a script cloning the archived mirror must not pass (rc=$RC)"

# 7. Pass 2 recognises a FETCH VERB as a pin. A version constraint is not the
#    only runnable form: `git clone …/sceneview-swift.git` carries no version
#    at all and still fails the moment anyone pastes it, so a prose-only
#    surface must not be free to ship one just because no `from:` is in sight.
D="$(fixture fragment_clone changelog.d/1234-note.md \
    '- Before v4, users ran `git clone https://github.com/sceneview/sceneview-swift.git`.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -ne 0 ]] && grep -q 'changelog.d/1234-note.md:1' <<<"$OUT"; } \
  && ok "changelog fragment CLONING the mirror → fail, line named" \
  || bad "a bare clone line is a pin with no version in sight (rc=$RC)"

# 8. …and the verb alone is not. The gate must stay usable for release notes:
#    a sentence that says the mirror was cloned by users, with no command on
#    that line, is history — the same both-directions discipline as case 5.
D="$(fixture prose_clone changelog.d/1234-note.md \
    '- The sceneview-swift mirror is archived. Users who clone anything now clone the monorepo.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "prose about cloning, no command on the line → not a pin" \
  || bad "the fetch-verb pattern must not swallow release-note prose (rc=$RC)"

# 9. The fetch verb targets the REPO PATH, not the bare token. This gate's own
#    filename contains `sceneview-swift`, so a doc line naming `curl` and then
#    the script matched the first draft — measured: the automation-map row
#    failed the gate it documents. Only what can be cloned is a clone.
D="$(fixture prose_script_name CHANGELOG.md \
    '- Use curl for downloads; the mirror ban lives in check-sceneview-swift-urls.sh.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "a fetch verb plus this script's NAME → not a clone of the mirror" \
  || bad "the fetch pattern must target the repo path, not the bare token (rc=$RC)"

# 10. The shape that actually broke the live gate: a fetch verb early in a
#     line and the literal repo path later, with prose in between. `.*`
#     matched it, because grep bounds a match to a physical LINE and a line
#     can be a whole paragraph — so this PR's own changelog fragment turned
#     the gate red against itself. The gap must be one whitespace-free token:
#     an argument, not a sentence.
D="$(fixture prose_verb_then_path changelog.d/1234-note.md \
    '- You could `git clone` it back then; the path was sceneview/sceneview-swift, now retired.')"
set +e; OUT="$(run "$D")"; RC=$?; set -e
{ [[ $RC -eq 0 ]]; } \
  && ok "fetch verb + repo path separated by prose → not a command" \
  || bad "an unbounded verb-to-URL gap makes a release note a false positive (rc=$RC)"

echo "  → $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
