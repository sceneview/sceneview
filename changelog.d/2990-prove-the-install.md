<!-- category: Fixed -->
- `android_cli_install_and_launch` can no longer report success without
  installing anything. It used to `return $?` from `android run`; measured on a
  real emulator, that command printed `App loaded:` and `Debuggable: true`, then
  rejected an activity the platform resolves fine — and installed **nothing**,
  leaving a build eight hours old on the device while a QA run measured it. The
  helper now proves the install by checking that the device's `lastUpdateTime`
  moved, falls back to `adb install -r` when the CLI path leaves it untouched,
  and **refuses to launch** when neither path can be proven, naming the danger
  (the device still holds the previous build). Covered by
  `test-android-cli-install.sh` against stub binaries — no emulator, no lease —
  with a mutation test on the stamp check (#2990).

<!-- category: Docs -->
- The `android-tooling` skill no longer recommends calling `android run` directly;
  it documents the measured misbehaviour and points at the helper. Measured on
  CLI `1.0.15498356`: `--no-metrics` is accepted in the **global** position
  (`android --no-metrics run …`, what the helper uses) and rejected in the
  sub-command position — so that flag, which the report flagged as suspicious, is
  **not** the cause. The silent non-install remains unexplained upstream, which is
  exactly why the helper verifies instead of trusting.
- Every remaining place that taught the disproven command is fixed: the **public**
  `agents/sceneview/SKILL.md` (installed for any AI agent on the host), the
  flagship `docs/docs/try.md` quickstart, `samples/README.md`,
  `samples/android-demo/README.md`, `samples/android-demo/AR_TESTING.md`, the
  advice `setup-ar-emulator.sh` prints after provisioning, and stale comments in
  `try-demo.sh`, `render-tests.yml` and `maintain.md`. A first pass claimed
  completeness after finding three — it had grepped for `android run --apks`, and
  five docs write `android run \` with a line continuation, so the probe was too
  narrow and reported an all-clear. `test-android-cli-install.sh` now matches the
  **subcommand** and fails when any file teaches it without naming the defect.
- `qa-android-demos.sh` no longer retries a bare `adb install -r` when the helper
  refuses. The helper already tries that itself and only fails when it could not
  prove the install landed; retrying it and continuing unverified downgraded the
  guarantee back to the exit code the fix exists to stop trusting. It now aborts.
