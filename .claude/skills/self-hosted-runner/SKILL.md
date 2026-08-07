---
name: self-hosted-runner
description: The opt-in self-hosted macOS runner (label sceneview-mac) — installer, LaunchAgent plus heartbeat design, the one-line runs-on opt-in that falls back to macos-15 transparently when the Mac is asleep, and the safety net. Use when a macOS CI job is slow or expensive, when the runner looks offline, or when opting a workflow in.
---

## Self-hosted macOS runner (opt-in)

GitHub-hosted `macos-15` runners cost ~10x ubuntu per-minute and have no KVM.
SceneView ships **6 jobs on `macos-15`** (`ios.yml`, `bridge-ios-compile.yml`,
`rn-ios-compile.yml`, `app-store.yml` × 2, `render-tests.yml`) — but
`rn-ios-compile.yml` is **deliberately excluded** from the opt-in: it runs
`npm ci` and `pod install`, which write into shared per-user caches
(`~/.npm`, `~/Library/Caches/CocoaPods`) on a machine that is Thomas's daily
driver. Keep that one on a disposable hosted runner. The iOS Maestro
device-QA leg (#1601) is CI-wired since #2833 — nightly via `device-qa.yml`,
routed to the self-hosted `sceneview-mac` runner when its heartbeat is fresh
(see the "iOS leg status" note under "Device QA" above). The self-hosted
runner is what makes that leg affordable per-run.

Inspired by [Zach Rattner's M4 Mac cluster
playbook](https://zachrattner.com/projects/m4-mac-cluster) (8 Mac minis, $35k/yr
saved vs GCP, 4-min builds → 40s) — SceneView's scale doesn't justify a
cluster, but a single self-hosted Mac with **transparent fallback** is a
strict win.

### Install

```bash
brew install gh
gh auth login --scopes "repo,workflow"   # PAT needs Variables:write
bash .claude/scripts/setup-self-hosted-runner.sh
bash .claude/scripts/setup-self-hosted-runner.sh --check
```

The installer (a) downloads `actions/runner` for `osx-arm64`/`osx-x64`
into `~/sceneview-runner/` (deliberately *outside* `~/Library/Application
Support/` — that path contains a space, and the runner's generated step
scripts get invoked as `/bin/bash -e <path>` which splits on space and
fails with `No such file or directory`; v2 hit exactly this on the
pilot bridge-ios-compile run id 26418464635), (b) registers it with
label `sceneview-mac`, (c) writes a user LaunchAgent plist directly
and `launchctl bootstrap`s it (the v1 attempt to delegate to
`actions/runner`'s `svc.sh` was a dead end — `svc.sh` shells out to
the deprecated `launchctl load` which fails with `Input/output error`
on macOS 11+, see actions/runner issue 1424).
The plist uses `KeepAlive=true` so the runner survives reboots, login,
sleep/wake, and the runner's own auto-update cycle (the runner exits
to install a new version, launchd restarts it, new version takes over —
verified working). (d) installs a second launchd heartbeat plist that
fires `runner-heartbeat.sh` every 300s. The heartbeat pings
`/repos/.../actions/runners` to confirm the runner is *actually*
online, then updates two repo variables: `SELF_HOSTED_MACOS_ONLINE`
(`"true"`/`"false"`) and `SELF_HOSTED_MACOS_LAST_SEEN` (ISO 8601 UTC).

### Opt a workflow in — one line, copied verbatim

Workflows route to self-hosted only when the heartbeat is fresh, and fall
back to `macos-15` automatically when the Mac is asleep, off, or the runner
process is dead (heartbeat sets `ONLINE=false` if `runner.status != "online"`):

```yaml
jobs:
  build:
    # Was:  runs-on: macos-15
    runs-on: ${{ (vars.SELF_HOSTED_MACOS_ONLINE == 'true' && (github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository)) && 'sceneview-mac' || 'macos-15' }}
    steps:
      - ...
```

That's the whole change per workflow. **No composite action, no reusable
workflow, no pre-job** — `runs-on` accepts expressions since GitHub Actions
late-2024. Thomas opts workflows in one at a time as confidence grows.

**Copy it whole — do not simplify it back to the two-term form.** Both halves
of the added clause are load-bearing, and dropping either one fails silently
rather than loudly:

- `github.event_name != 'pull_request'` — `github.event.pull_request` is null
  on `push`, `workflow_dispatch`, `schedule`, and a `workflow_call` from
  nightly-ci.yml. Without this term the `head.repo` comparison is false on all
  of them, so **every non-PR run quietly loses the fast runner** and the repo
  pays for hosted minutes it already owns hardware for.
- `head.repo.full_name == github.repository` — true only for a branch PR
  opened inside this repo, false for every fork. `sceneview-mac` is a
  persistent machine and Thomas's daily driver: a job there inherits the
  previous job's filesystem, `~/.gradle`, and whatever the login user can
  reach.

**This clause is defence in depth, not a boundary.** A fork PR runs the
workflow file from the merge ref — the contributor's own copy — so a hostile
PR can just delete the line. The boundary is the repo setting: Settings →
Actions → General → "Require approval for **all external contributors**"
(`approval_policy: all_external_contributors`), which withholds a runner
entirely until a maintainer approves the run. Check it with:

```bash
gh api repos/sceneview/sceneview/actions/permissions/fork-pr-contributor-approval
```

The default, `first_time_contributors`, is not enough: it approves a
contributor once and then lets every later PR from them start on its own.

Three workflows carry the expression today — `ci.yml` (kmp-native-test),
`bridge-ios-compile.yml`, `device-qa.yml` (ios). Keep the three identical;
`bridge-ios-compile.yml` holds the long-form comment.

### Safety net

- Heartbeat refuses to mark `ONLINE=true` when the runner service status is
  anything other than `"online"` (dead service / failed boot / runner mid
  auto-update → workflows route to `macos-15`).
- `KeepAlive=true` on the runner LaunchAgent means a crashed runner is
  re-launched within `ThrottleInterval` (30s). The runner's auto-update
  cycle (exit → launchd restart → new version) is invisible to consumers.
- On `--uninstall`, both LaunchAgents are `launchctl bootout`-ed, any
  rogue `run.sh` is `pkill`-ed, and `ONLINE` is forced to `false` so no
  stale routing survives.
- `--check` prints `.runner` config, both LaunchAgent loaded states with
  `state =` + `last exit code` excerpts, recent heartbeat log, GitHub-side
  runner status (live API call), and both repo variables — single source
  of truth.
- Heartbeat interval (300s) is well under any reasonable workflow queue
  timeout. After `pmset sleepnow` the runner is paused; on wake launchd
  re-validates `KeepAlive` and the runner re-connects to GitHub within a
  few seconds; the heartbeat picks up the offline → online transition at
  the next tick.

