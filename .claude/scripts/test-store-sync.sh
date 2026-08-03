#!/usr/bin/env bash
#
# test-store-sync.sh — self-test for .claude/scripts/store-sync/ (#2612 P2, Phases A+B).
#
# The store-sync scripts are the single code path for the Play listing sync
# (play-store.yml calls play_listing.py --apply) and the ASC drift diff; a
# regressed script that silently PASSes — or a workflow that silently stops
# calling it — is worse than none. This pins the contract WITHOUT any live
# store credential:
#
#   - both scripts byte-compile (py_compile) — a syntax error must not wait
#     for the next release's sync-listing job to surface;
#   - the pure helpers' unit tests pass (unittest discover, offline — the
#     lazy third-party imports mean no google-auth/PyJWT/requests needed);
#   - a credential-less run SKIPs honestly with exit 0 in every mode
#     (advisory-first doctrine — never a fake green, never a spurious red);
#   - the WRITE path (`--apply-screenshots`) SKIPs honestly without creds too,
#     so a run that uploaded nothing can never look like a successful upload;
#   - near-miss flags are REJECTED with exit 2 rather than expanded: argparse
#     resolves unambiguous prefixes, so without allow_abbrev=False `--apply`
#     (play_listing.py's real flag) would publish to the App Store;
#   - reading and writing are mutually exclusive (exit 2), never silently one;
#   - play-store.yml still calls play_listing.py --apply, and the screenshot
#     upload lives in its OWN workflow, so neither workflow↔script seam can
#     drift apart unnoticed.
#
# The LIVE API path (network + real secrets) is intentionally NOT covered —
# same stance as test-store-preflight.sh. Runs in ci.yml → repo-hygiene.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SYNC_DIR="$ROOT/.claude/scripts/store-sync"
PASS=0; FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL + 1)); }

# Hermetic python invocation: no inherited store credentials, throwaway HOME.
run_py() {
  set +e
  OUT="$(cd "$ROOT" && env -i PATH="$PATH" HOME="$TMP" python3 "$@" 2>&1)"; RC=$?
  set -e
}

echo "test-store-sync.sh"

# 1. Byte-compile both scripts.
for script in play_listing.py asc_listing.py; do
  if python3 -m py_compile "$SYNC_DIR/$script" 2>/dev/null; then
    ok "$script byte-compiles"
  else
    bad "$script does not byte-compile"
  fi
done

# 2. Offline unit tests for the pure helpers.
run_py -m unittest discover -s "$SYNC_DIR/test" -v
if [ "$RC" -eq 0 ]; then
  ok "unit tests pass ($(printf '%s' "$OUT" | grep -c '^test_' || true) cases)"
else
  bad "unit tests failed:"
  printf '%s\n' "$OUT" | tail -20
fi

# 3. Credential-less runs SKIP honestly (exit 0 + explicit [skip] line).
run_py "$SYNC_DIR/play_listing.py" --dry-run
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "play_listing.py --dry-run without creds → honest SKIP, exit 0"
else
  bad "play_listing.py --dry-run without creds → rc=$RC, out: $(printf '%s' "$OUT" | head -2)"
fi

run_py "$SYNC_DIR/play_listing.py" --apply
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "play_listing.py --apply without creds → honest SKIP, exit 0"
else
  bad "play_listing.py --apply without creds → rc=$RC"
fi

run_py "$SYNC_DIR/asc_listing.py" --dry-run
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "asc_listing.py --dry-run without creds → honest SKIP, exit 0"
else
  bad "asc_listing.py --dry-run without creds → rc=$RC, out: $(printf '%s' "$OUT" | head -2)"
fi

# 4. The write path SKIPs honestly without creds too — and ANNOTATES it.
#    Someone dispatched this to upload; a bare log line under a green check
#    reads as "uploaded" to anyone who doesn't open the log, so the skip must
#    surface as a GitHub annotation (PR #2781 review).
run_py "$SYNC_DIR/asc_listing.py" --apply-screenshots
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^::warning::\[skip\]'; then
  ok "asc_listing.py --apply-screenshots without creds → annotated SKIP, exit 0"
else
  bad "asc_listing.py --apply-screenshots without creds → rc=$RC, out: $(printf '%s' "$OUT" | head -2)"
fi

# 4a. …while the read path stays a plain line (advisory tooling runs it often
#     and must not spam annotations).
run_py "$SYNC_DIR/asc_listing.py" --dry-run
if printf '%s' "$OUT" | grep -q '^\[skip\]'; then
  ok "asc_listing.py --dry-run SKIP stays un-annotated"
else
  bad "asc_listing.py --dry-run SKIP is no longer a plain [skip] line"
fi

# 4e. A flag that cannot do anything in the chosen mode must be refused, not
#     ignored — same class as an abbreviation silently reaching a write.
run_py "$SYNC_DIR/asc_listing.py" --apply-screenshots --fail-on-drift
if [ "$RC" -eq 2 ]; then
  ok "asc_listing.py --apply-screenshots --fail-on-drift → refused, exit 2"
else
  bad "asc_listing.py --apply-screenshots --fail-on-drift → rc=$RC (want 2)"
fi

# 4f. --dry-run --fail-on-drift WITHOUT creds must still SKIP (exit 0), never
#     exit 3. The credential check precedes the drift logic, so "no creds" is
#     "not measured", not "drift found". release-checklist.sh §17 grades exit 3
#     as a drift WARN — if a credential-less run returned 3, every local release
#     check would warn on drift it never actually measured (a fabricated signal,
#     the exact class this suite guards against).
for script in play_listing.py asc_listing.py; do
  run_py "$SYNC_DIR/$script" --dry-run --fail-on-drift
  if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q '^\[skip\]'; then
    ok "$script --dry-run --fail-on-drift without creds → honest SKIP, exit 0 (not 3)"
  else
    bad "$script --dry-run --fail-on-drift without creds → rc=$RC (want 0 + [skip])"
  fi
done

# 4b. A near-miss flag must NOT reach the App Store write path. argparse
#     expands unambiguous prefixes unless allow_abbrev=False, so `--apply`
#     (play_listing.py's real flag — the obvious thing to type by habit)
#     would otherwise upload screenshots.
for near_miss in --apply --appl --apply-screenshot; do
  run_py "$SYNC_DIR/asc_listing.py" "$near_miss"
  if [ "$RC" -eq 2 ] && printf '%s' "$OUT" | grep -q 'Unknown option'; then
    ok "asc_listing.py $near_miss → rejected, exit 2 (no accidental upload)"
  else
    bad "asc_listing.py $near_miss → rc=$RC (want 2 + 'Unknown option')"
  fi
done

# 4c. Same hardening on the Play side, where --apply is the real write flag.
run_py "$SYNC_DIR/play_listing.py" --appl
if [ "$RC" -ne 0 ]; then
  ok "play_listing.py --appl → rejected (no accidental Play Console write)"
else
  bad "play_listing.py --appl → rc=0; abbreviation reached the write path"
fi

# 4d. Reading and writing are mutually exclusive — an ambiguous invocation
#     must fail rather than silently pick one.
run_py "$SYNC_DIR/asc_listing.py" --dry-run --apply-screenshots
if [ "$RC" -eq 2 ]; then
  ok "asc_listing.py --dry-run --apply-screenshots → refused, exit 2"
else
  bad "asc_listing.py --dry-run --apply-screenshots → rc=$RC (want 2)"
fi

# 5. Workflow↔script seam: play-store.yml must still call the script.
if grep -q 'store-sync/play_listing\.py --apply' "$ROOT/.github/workflows/play-store.yml"; then
  ok "play-store.yml sync-listing calls play_listing.py --apply"
else
  bad "play-store.yml no longer references store-sync/play_listing.py --apply"
fi

# 5b. Same seam on the iOS side, plus the isolation that makes it safe.
SHOTS_WF="$ROOT/.github/workflows/app-store-screenshots.yml"
if grep -q 'store-sync/asc_listing\.py --apply-screenshots' "$SHOTS_WF"; then
  ok "app-store-screenshots.yml calls asc_listing.py --apply-screenshots"
else
  bad "app-store-screenshots.yml no longer references asc_listing.py --apply-screenshots"
fi

# The upload must never be reachable from a tag push: screenshots are listing
# maintenance, not part of a release.
if grep -qE '^\s+tags:' "$SHOTS_WF"; then
  bad "app-store-screenshots.yml gained a tag trigger — it must stay dispatch-only"
else
  ok "app-store-screenshots.yml is dispatch-only (no tag trigger)"
fi

# THE regression this file exists to prevent (PR #2781 review): the upload
# used to be a job inside app-store.yml, whose deploy-ios/deploy-macos jobs
# are gated only on *_ready. A screenshot-only dispatch therefore also built
# and uploaded a TestFlight build. Keeping the upload out of that workflow is
# what makes "no build" true, so assert it never moves back in.
if grep -q 'asc_listing\.py --apply-screenshots' "$ROOT/.github/workflows/app-store.yml"; then
  bad "the screenshot upload is back inside app-store.yml — a dispatch there also runs deploy-ios/deploy-macos (TestFlight upload)"
else
  ok "app-store.yml carries no screenshot upload (deploy jobs stay unreachable from a sync)"
fi

# The confirm gate moved from the job to the steps (so a no-op dispatch can
# still emit an annotation instead of a silent green). That makes the upload
# step's own `if:` load-bearing: without it, accepting the default confirm
# would upload.
if python3 - "$SHOTS_WF" <<'PYEOF'
import sys, yaml
steps = yaml.safe_load(open(sys.argv[1]))["jobs"]["sync-screenshots"]["steps"]
upload = [s for s in steps if "asc_listing.py --apply-screenshots" in str(s.get("run", ""))]
sys.exit(0 if upload and all("guard" in str(s.get("if", "")) for s in upload) else 1)
PYEOF
then
  ok "the upload step is gated on the confirm guard"
else
  bad "the upload step lost its confirm guard — an unconfirmed dispatch would upload"
fi

# It MUST share the deploy concurrency group. Both workflows write to the same
# editable App Store version: a sync that deletes a display type's screenshots
# while a deploy locks that version for review leaves it in review with a
# truncated set. Queueing costs minutes; the race costs a corrupted listing.
# (The first cut used a separate group for exactly the wrong reason — "must not
# serialise against a release" — so pin the corrected decision.)
if grep -q 'group: app-store-deploy' "$SHOTS_WF"; then
  ok "app-store-screenshots.yml shares the app-store-deploy concurrency group"
else
  bad "app-store-screenshots.yml left the app-store-deploy group — a sync can now race a deploy on the same editable version"
fi

# 6. The extracted script must not have re-grown an inline-heredoc twin: the
#    sync-listing job must not contain its own PYEOF python anymore.
if sed -n '/sync-listing:/,$p' "$ROOT/.github/workflows/play-store.yml" | grep -q 'PYEOF'; then
  bad "sync-listing job still contains an inline PYEOF heredoc"
else
  ok "sync-listing job has no inline python heredoc left"
fi

# 7. #2612 Phase C — the ASC read-only rail must exist and stay read-only: it runs
#    asc_listing.py --dry-run (never a write flag), so a scheduled, credential-
#    bearing job can never push to the App Store. The drift-issue rail layered on
#    top of it is asserted in § 9.
MAINT_WF="$ROOT/.github/workflows/maintenance.yml"
if grep -q 'store-sync/asc_listing\.py --dry-run' "$MAINT_WF"; then
  ok "maintenance.yml runs asc_listing.py --dry-run (Phase C read-only rail)"
else
  bad "maintenance.yml no longer runs asc_listing.py --dry-run — the ASC drift/probe signal is never produced"
fi
# The read-only rail must never gain a write flag by a careless edit: --apply*
# in a scheduled, credential-bearing job would push to the App Store unattended.
if python3 - "$MAINT_WF" <<'PYEOF'
import sys, yaml
job = yaml.safe_load(open(sys.argv[1]))["jobs"].get("asc-listing-drift")
if not job:
    sys.exit(1)
runs = " ".join(str(s.get("run", "")) for s in job.get("steps", []))
sys.exit(0 if ("asc_listing.py --dry-run" in runs
               and "--apply" not in runs) else 1)
PYEOF
then
  ok "asc-listing-drift job is read-only (--dry-run, no --apply*)"
else
  bad "asc-listing-drift job missing or carries a write flag — a scheduled job must never push to the store"
fi
# A crash mid-read must not render as a clean read (security review, PR #2811).
# `| tee … || true` throws the script's status away twice, so a 5xx or a token
# expiry after the first line of output leaves a partial log with no drift lines
# and no [probe] verdict — which the summary would print as if nothing were
# wrong. The job must capture PIPESTATUS[0] (the SCRIPT's code, not tee's) and
# the summary must branch on it. Assert both halves: dropping either one
# silently restores the false-clean.
if python3 - "$MAINT_WF" <<'PYEOF'
import sys, yaml
job = yaml.safe_load(open(sys.argv[1]))["jobs"].get("asc-listing-drift")
if not job:
    sys.exit(1)
runs = " ".join(str(s.get("run", "")) for s in job.get("steps", []))
sys.exit(0 if ("PIPESTATUS[0]" in runs and "asc_rc" in runs) else 1)
PYEOF
then
  ok "asc-listing-drift reports a partial read instead of a silent clean one"
else
  bad "asc-listing-drift lost its PIPESTATUS/asc_rc guard — a crashed run would render as a finding-free read"
fi

# 8. #2612 Phase C — the release gate (§17 of release-checklist.sh) must run
#    the read-only drift diff for BOTH stores with --fail-on-drift. This is the
#    seam that surfaces drift at tag time, not just in an unread daily step
#    summary; if §17 stops calling a script (or drops --fail-on-drift, making
#    exit 3 unreachable), a drifted store ships silent.
CHECKLIST="$ROOT/.claude/scripts/release-checklist.sh"
if grep -q -- '--dry-run --fail-on-drift' "$CHECKLIST"; then
  ok "release-checklist.sh §17 uses --dry-run --fail-on-drift"
else
  bad "release-checklist.sh §17 no longer runs the drift diff with --fail-on-drift"
fi
for script in play_listing.py asc_listing.py; do
  if grep -q "\"$script\"" "$CHECKLIST"; then
    ok "release-checklist.sh §17 checks $script"
  else
    bad "release-checklist.sh §17 no longer checks $script"
  fi
done

# 9. #2612 Phase C fast-follow — the daily drift jobs must FILE a de-duplicated
#    tracking issue when (and only when) drift is measured. For BOTH the Play and
#    ASC read-only jobs assert: --fail-on-drift is passed, issues:write is granted,
#    and the gh-issue step is gated on the measured-drift output (exit 3 → drift=1)
#    — never unconditional, so a credential-less skip or a mid-read crash can never
#    file a drift issue it did not actually find.
if python3 - "$MAINT_WF" <<'PYEOF'
import sys, yaml
jobs = yaml.safe_load(open(sys.argv[1]))["jobs"]
problems = []
for name in ("listing-drift", "asc-listing-drift"):
    job = jobs.get(name)
    if not job:
        problems.append(f"{name}: job missing"); continue
    steps = job.get("steps", [])
    runs = " ".join(str(s.get("run", "")) for s in steps)
    if "--fail-on-drift" not in runs:
        problems.append(f"{name}: --fail-on-drift not passed")
    if job.get("permissions", {}).get("issues") != "write":
        problems.append(f"{name}: issues:write not granted")
    issue_steps = [s for s in steps if "gh issue" in str(s.get("run", ""))]
    if not issue_steps:
        problems.append(f"{name}: no gh-issue step")
    for s in issue_steps:
        if "steps.diff.outputs.drift" not in str(s.get("if", "")):
            problems.append(f"{name}: issue step not gated on measured drift")
for p in problems:
    print(p)
sys.exit(1 if problems else 0)
PYEOF
then
  ok "Play + ASC drift jobs file a de-duplicated issue gated on measured drift (exit 3)"
else
  bad "drift-issue rail missing or mis-gated (see above)"
fi

echo
echo "test-store-sync.sh: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
