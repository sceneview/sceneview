<!-- category: Tests -->
- **device-qa (iOS leg)**: `ios-device-qa.sh` now keeps the simulator's unified
  log as a run artifact instead of a discarded mktemp, and fixes the crash-gate
  predicate — it filtered on `process == "SceneViewDemo"`, which never matched
  anything (the built bundle's `CFBundleExecutable` is `SceneView`), so the
  post-run crash sweep had been silently blind. The stream now filters on
  `process == "SceneView" OR subsystem == "io.github.sceneview.demo"` (verified
  against the installed demo app: the process filter carries the runtime +
  crash markers, the subsystem filter the app's structured `Logger` calls), and
  `device-qa.sh` copies the log into the artifacts dir and attaches its path as
  a new `log` field on the iOS platform record in `device-qa-report.json`.
  Inspired by XcodeBuildMCP's automatic per-app os_log capture (QA-efficiency
  spike, 2026-07-09).
