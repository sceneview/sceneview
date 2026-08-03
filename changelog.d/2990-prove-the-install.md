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
  narrow and reported an all-clear. `check-android-run-not-taught.sh` now matches
  the **subcommand** — at end-of-line and inside inline code too, after a first
  version missed 15 of the 22 files that mention it — and fails when any file
  teaches the command without naming the defect.
- `qa-android-demos.sh` no longer retries a bare `adb install -r` when the helper
  refuses. The helper already tries that itself and only fails when it could not
  prove the install landed; retrying it and continuing unverified downgraded the
  guarantee back to the exit code the fix exists to stop trusting. It now aborts.
- `check-workflow-scripts.sh` was invisible to shellcheck. Two prose comments
  began with the word `shellcheck`, which the tool parses as a malformed
  DIRECTIVE (SC1073) and then stops analysing the rest of the file — so the
  script that lints every workflow `run:` block was itself never linted. Both
  reworded; the file now parses clean (2 diagnostics → 0, and the remaining
  body is actually analysed). Pre-existing, unrelated to this fix, taken because
  the message literally reads "Fix to allow more checks".
- The repo-wide content gate moved out of `test-android-cli-install.sh` into its
  own `check-android-run-not-taught.sh`. The unit test is hermetic (stub
  binaries, no repo state); coupling its verdict to unrelated docs meant an
  unrelated edit could redden it and point at the wrong thing.
- `docs/docs/try.md`, `samples/README.md`, `samples/android-demo/README.md` and
  `AR_TESTING.md` had headings promising Google's `android` CLI and an "atomic
  install + launch" directly above the plain `adb` commands the first pass
  substituted — the code changed, the prose around it did not. Fixed, along with
  a leftover `AR_TESTING.md` note framing the command as merely missing `--es`
  "until v0.8+", which contradicted the warning immediately above it.
- The helper now returns **2** when the install was proven but the activity would
  not start, and **1** when the install could not be proven. It used to return
  `am start`'s status, so a genuine launch failure printed "install could not be
  proven … the device may not be running" — a true failure described by a false
  cause, which sends the reader after the wrong bug. `qa-android-demos.sh` says
  which half broke.
- `check-android-run-not-taught.sh` enumerates tracked files with `git ls-files`
  instead of recursing the working tree. `--include` filters names but does not
  stop `grep -r` descending into `node_modules/` or `build/`, so a vendored file
  containing the token would have false-failed the gate in CI. It looked clean
  locally for a reason that is not one: the author's `grep` is `ugrep`, which
  skips ignored paths, while CI runs GNU grep, which does not — measured with a
  probe file seen by one and not the other.
- `android_cli_install_stamp` can no longer abort its caller. Its pipeline ran
  under the lib's `set -o pipefail` plus a caller's inherited `set -e`, so an
  `adb` failure while READING the stamp aborted the whole helper with adb's raw
  exit code and an **empty stderr** — measured `rc=3`, no diagnostic at all, so
  a reader would debug the wrong layer. "No stamp" is a legitimate answer and is
  now returned as one; the helper then refuses with its own explanation (`rc=1`,
  `INSTALL NOT PROVEN`).
- The content gate no longer matches `gcloud firebase test android run` — an
  unrelated Firebase Test Lab command ending in the same two words. Harmless
  today (it appears only in a `.kt` file, outside the gated set), but the day
  someone documents Test Lab in a `.md` the only escape would have been citing
  an unrelated issue number, and a gate whose escape hatch is a lie teaches
  people to lie to it.
- The content gate scans **all** tracked files, with no extension list. An
  earlier version listed `*.md *.sh *.yml` and so could not see a `*.yaml` — the
  third too-narrow probe in a script whose entire subject is too-narrow probes.
  The list was never a performance decision: measured, the full sweep of 3122
  tracked files takes 0.7 s.
- Its Firebase exclusion is anchored on the adjacent `test android run` phrasing
  instead of the word `firebase` appearing anywhere on the line. A line that
  genuinely recommended the install *and* happened to mention Firebase would
  otherwise have been excluded — the exclusion would have become the hole.
- The content gate's enumerator passes `--` before the file list and `-r` to
  `xargs`. Without `--`, a tracked path starting with `-` is read by `grep` as
  an option: measured, one such file aborts the whole batch with
  `unknown --directories option` and silently drops every file in it — a gate
  that evades itself. Mutation-tested with exactly such a file.
- `qa-android-demos.sh` always goes through the helper now. Its
  `if android_cli_locate … else adb install -r` shape meant that on a host
  without the CLI it installed with **no** verification at all — the same
  unproven-install class, one branch over. The helper does that check itself and
  its fallback carries the proof, so the branch was both unverified and
  redundant.
