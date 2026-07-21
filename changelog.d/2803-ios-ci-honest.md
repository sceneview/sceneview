<!-- category: Tests -->
- CI: the iOS device-QA leg is now real. `device-qa.yml` gained an `ios` job
  (Maestro on an iOS Simulator via `ios-device-qa.sh`) that routes to the
  self-hosted Mac when online and falls back to `macos-15`. It runs nightly and
  on manual dispatch only — never per-push (a macOS runner is ~10x the ubuntu
  cost) — and is **advisory** (a red iOS leg is a release WARN, never a hard
  block), matching the android/ar posture. `device-qa.sh` tags `ios` advisory
  in `device-qa-report.json` / `releaseGate` (#2803).
- CI: `render-tests.yml`'s "iOS screenshot tests" job now produces real PNGs.
  A dedicated `SceneViewDemoUITests` UI-testing target (XCUITest) launches the
  demo in a simulator and captures an `XCTAttachment` screenshot of the launch
  screen, every tab, and a representative subset of working 3D demos; the job
  exports the attachments from the `.xcresult` as PNG artifacts. It uses its own
  scheme so the per-PR iOS unit-test check stays fast and simulator-free (#2803).
