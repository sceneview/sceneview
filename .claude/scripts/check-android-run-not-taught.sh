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

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

# A file that cites one of these is documenting the defect, not recommending
# the command. CHANGELOG.md and changelog.d/ are release history — they describe
# what was fixed, in the words used at the time.
ISSUE_RE='#(2796|2854|2990)'

offenders=()
while IFS= read -r f; do
  case "$f" in
    ./CHANGELOG.md|./changelog.d/*) continue ;;
    ./.git/*) continue ;;
  esac
  grep -qE "$ISSUE_RE" "$f" && continue
  offenders+=("${f#./}")
done < <(grep -rlE '(^|[^-])android run([[:space:]]|\\)' \
           --include='*.md' --include='*.sh' --include='*.yml' . 2>/dev/null)

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
