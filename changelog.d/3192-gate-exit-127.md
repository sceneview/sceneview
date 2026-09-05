<!-- category: Fixed -->
- **A gate script that cannot find its tool now says "could not run" instead of reporting a
  failure ([#3192](https://github.com/sceneview/sceneview/issues/3192), workstream 2).** Audit of
  every script a workflow or `CLAUDE.md` still invokes as a check, now that #3244 has removed the
  local harness (`pre-push-check.sh` and the `script_report_failure` helper the issue names are
  gone with it). Four legs turned a missing tool into a verdict about the tree:
  `validate-demo-assets.sh` read a missing `curl` as HTTP 000 → "transient", spent 23 s of
  backoff per URL and exited 0 with every CDN reference "not checked"; `ios-device-qa.sh` exited
  1 — the code a flow that really failed returns — when `xcrun` (or macOS itself) was absent;
  `ar-replay-qa.sh` exited 1 — "a demo crashed" — when `python3` was missing for the verdict
  merge, and only found out after a full emulator sweep; `qa-android-demos.sh` printed "APK
  build failed or timed out" on a stock macOS host that has no GNU `timeout`, without Gradle
  ever starting. Each now uses the code its own header reserves for "could not run" (exit 2,
  checked up front), or resolves `gtimeout` the way `lib/maestro.sh` already does; nothing
  changes when the tool is present. `test-validate-demo-assets.sh` pins the `curl` contract.
