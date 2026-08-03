#!/usr/bin/env bash
# Repo-wide content gate: no file may TEACH `android run` for installing.
#
# WHY THIS EXISTS
#   Google's `android run` has a measured install no-op: it prints success-shaped
#   output, rejects an activity the platform resolves fine, and exits 0 having
#   installed nothing — leaving the previous build on the device. It has been
#   found and patched THREE times in this repo:
#
#     #2796  tablet screenshots — "can no-op the install and still exit 0"
#     #2854  store screenshots ran against a build 16 hours old, while
#            producing entirely plausible images
#     #2990  the shared helper trusted its exit code; a QA run measured a
#            build eight hours old and nearly reported a fix that never shipped
#
#   Each of the first two fixes landed in ONE call site. Nothing stopped the
#   docs from going on recommending the command, so the next reader rediscovered
#   it. That is the loop this gate closes: documenting the trap is fine,
#   recommending it is not.
#
# WHY IT IS ITS OWN SCRIPT
#   It first lived inside `test-android-cli-install.sh`, which is a hermetic
#   unit test of the helper's logic (stub binaries, no repo state). Coupling a
#   logic test's verdict to the content of unrelated docs meant an unrelated doc
#   edit could redden it, and the failure would point at the wrong thing.
#
# WHY THE PATTERN MATCHES THE SUBCOMMAND, NOT THE FLAGS
#   The first sweep grepped `android run --apks` and reported an all-clear.
#   Five docs write `android run \` with a line continuation before the flag, so
#   the probe never saw them — too NARROW, and a false all-clear is the
#   dangerous direction. Match the subcommand and let files off only when they
#   also name one of the issues.
#
#   ⛔ AND THEN I WROTE A NARROW PROBE ANYWAY. The first version of this file
#   required the subcommand to be followed by whitespace or a backslash:
#   `android run([[:space:]]|\\)`. That misses `android run` at end-of-line and
#   the extremely common inline-code form `` `android run` `` — a backtick is
#   neither. Measured: 7 files seen vs 22 that actually mention it, so 15 were
#   invisible to a gate whose own header warns against exactly this. The
#   trailing context is now "end-of-line, or any character that cannot continue
#   the word", which is what "the subcommand appears here" actually means.
#
# WHY IT ENUMERATES WITH `git ls-files`
#   The first version used `grep -rlE … .`, which recurses the whole working
#   tree. `--include` filters NAMES, it does not stop the descent — so a
#   vendored or generated `*.md` under node_modules/ or build/ containing the
#   token would false-fail this gate. It looked clean locally for a reason that
#   is not a reason: the `grep` on the author's host is `ugrep`, which skips
#   ignored paths, while CI runs GNU grep, which does not. Measured: a probe
#   file under node_modules/ was seen by /usr/bin/grep and NOT by the local
#   grep. Enumerating TRACKED files removes the difference — same set on every
#   host, and it is the set the repo actually ships.
#
#   It scans ALL tracked files, with no extension list. An earlier version
#   listed `*.md *.sh *.yml` and therefore could not see a `*.yaml` — the third
#   too-narrow probe in a file whose entire subject is too-narrow probes. The
#   list was never a performance decision: measured, the full sweep of 3122
#   tracked files takes 0.7 s. `-I` skips binaries.
#
# WHY `gcloud firebase test android run` IS EXCLUDED
#   That is Firebase Test Lab, an unrelated command that happens to end in the
#   same two words — and the space after `test` is a valid leading boundary, so
#   a bare subcommand match hits it. It exists today only in a `.kt` file
#   (DemoRenderingScreenshotTest.kt), outside the gated set, so nothing is
#   failing; but the day someone documents Test Lab in a `.md`, the only escape
#   would be citing an unrelated issue number. A gate whose escape hatch is a
#   lie teaches people to lie to it.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

# A file that cites one of these is documenting the defect, not recommending
# the command.
#
# ⚠️ The exemption is FILE-level on purpose, and that has a measured cost: three
# stale lines slipped through this PR because their file cited an issue
# elsewhere. Proximity-based exemption (issue within +/-4 lines of the match)
# was measured as the alternative and is WORSE — it flags 21 files, including
# this script, its own test suite and the changelog, because prose legitimately
# discusses the command across paragraphs. Do not re-propose it without
# re-measuring; the noise buries the signal it is meant to surface. CHANGELOG.md and changelog.d/ are release history — they describe
# what was fixed, in the words used at the time.
ISSUE_RE='#(2796|2854|2990)'

# The subcommand, at a word boundary on both sides. NOT anchored on the flags:
# five docs wrote `android run \` with a line continuation and a flag-anchored
# probe missed all of them.
MATCH_RE='(^|[^-[:alnum:]_])android run($|[^[:alnum:]_-])|atomic install'
# Two patterns, because the drift does not always name the command. The stale
# framing this gate kept missing was "atomic install + launch" — prose that
# sells `android run` without ever writing it, so a token-only probe let it
# through on the very page whose warning callout contradicts it.
# …but not Firebase Test Lab, which ends in the same two words. Anchored on the
# ADJACENT phrasing, not on the word `firebase` appearing anywhere on the line:
# a line that genuinely recommends `android run` AND happens to mention Firebase
# would otherwise be excluded — the exclusion would become the hole.
EXCLUDE_RE='test[[:space:]]+android[[:space:]]+run'

offenders=()
while IFS= read -r f; do
  case "$f" in
    ./CHANGELOG.md|./changelog.d/*) continue ;;
    ./.git/*) continue ;;
  esac
  grep -qE "$ISSUE_RE" "$f" && continue
  # Every hit on this file is Firebase Test Lab? Then it does not teach the
  # Android CLI's `android run` at all.
  if [ "$(grep -cE "$MATCH_RE" "$f")" -eq "$(grep -E "$MATCH_RE" "$f" | grep -cE "$EXCLUDE_RE")" ]; then
    continue
  fi
  offenders+=("${f#./}")
# `--` before the file list, and `-r` so an empty list never makes grep block on
# stdin. Without `--`, a tracked path starting with `-` is read by grep as an
# OPTION: measured, one such file makes grep abort the whole batch with
# "unknown --directories option", silently dropping every file in it — a gate
# that evades itself. Both flags verified on this host's BSD xargs and on the
# GNU xargs CI runs.
done < <(git ls-files -z 2>/dev/null \
         | xargs -0r grep -lIE "$MATCH_RE" -- 2>/dev/null \
         | sed 's|^|./|')

if [ "${#offenders[@]}" -eq 0 ]; then
  echo "check-android-run: OK — no file recommends 'android run' without naming the defect"
  exit 0
fi

echo "check-android-run: FAIL" >&2
echo >&2
echo "  These teach \`android run\` without referencing #2796 / #2854 / #2990:" >&2
for f in "${offenders[@]}"; do echo "    $f" >&2; done
echo >&2
echo "  Each one steers a reader — human or agent — into an install that can" >&2
echo "  silently no-op and leave the previous build on the device." >&2
echo >&2
echo "  Either stop recommending it (use \`adb install -r\` + \`am start\`, then" >&2
echo "  check \`dumpsys package <pkg> | grep lastUpdateTime\`), or, if the file is" >&2
echo "  legitimately DOCUMENTING the trap, cite the issue so this gate can tell" >&2
echo "  the difference." >&2
exit 1
