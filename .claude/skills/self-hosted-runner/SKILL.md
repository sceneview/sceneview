---
name: self-hosted-runner
description: The opt-in self-hosted macOS runner (label sceneview-mac) — installer, LaunchAgent plus heartbeat design, the one-line runs-on opt-in that falls back to macos-15 transparently when the Mac is asleep, and the safety net. Use when a macOS CI job is slow or expensive, when the runner looks offline, or when opting a workflow in.
---

## Self-hosted macOS runner (opt-in)

GitHub-hosted `macos-15` runners cost ~10x ubuntu per-minute and have no KVM.
SceneView ships **6 jobs on `macos-15`** (`ios.yml`, `bridge-ios-compile.yml`,
`rn-ios-compile.yml`, `app-store.yml` × 2, `render-tests.yml`). The iOS Maestro
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

### Opt a workflow in — single line

Workflows route to self-hosted only when the heartbeat is fresh, and fall
back to `macos-15` automatically when the Mac is asleep, off, or the runner
process is dead (heartbeat sets `ONLINE=false` if `runner.status != "online"`):

```yaml
jobs:
  build:
    # Was:  runs-on: macos-15
    runs-on: ${{ vars.SELF_HOSTED_MACOS_ONLINE == 'true' && 'sceneview-mac' || 'macos-15' }}
    steps:
      - ...
```

That's the whole change per workflow. **No composite action, no reusable
workflow, no pre-job** — `runs-on` accepts expressions since GitHub Actions
late-2024. Thomas opts workflows in one at a time as confidence grows; no
existing workflow is modified by this commit.

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

