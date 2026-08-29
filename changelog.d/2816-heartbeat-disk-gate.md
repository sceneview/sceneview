<!-- category: Fixed -->
- **The self-hosted macOS runner now falls back to `macos-15` when the host is nearly full
  ([#2816](https://github.com/sceneview/sceneview/issues/2816)).** The runner heartbeat marked
  the Mac `SELF_HOSTED_MACOS_ONLINE=true` on runner liveness alone, so a job could route to a
  host with ~4.6 GiB free: a Flutter iOS type-check then died at `No space left on device` and
  left a *truncated* `Flutter.xcframework` in the runner tool cache, poisoning every later
  self-hosted Flutter job. `.claude/scripts/runner-heartbeat.sh` now refuses to declare the
  runner online below `RUNNER_MIN_FREE_DISK_GB` free GiB (default 15 — a Flutter setup peaks
  near 4.5 GiB and the host's 6 GiB local-build gate must stay clear), checked before the
  GitHub API probe and logged with the reading that caused the refusal.
  `setup-self-hosted-runner.sh --check` prints the current free-disk reading and its verdict
  next to the heartbeat state.
<!-- RELEASE NOTE (maintainer-only):
     The heartbeat script itself was collateral damage of the harness removal in #3244:
     setup-self-hosted-runner.sh still installed a LaunchAgent pointing at
     .claude/scripts/runner-heartbeat.sh, which no longer existed. This PR restores it (with
     the gate), so the repo variables stop drifting. On the pilot host at the time of writing
     the heartbeat LaunchAgent is NOT loaded, SELF_HOSTED_MACOS_LAST_SEEN is nine days stale
     and ONLINE is still "true" with 7.9 GiB free — rerun the installer to arm the gate. -->
