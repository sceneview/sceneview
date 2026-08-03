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
