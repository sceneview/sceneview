<!-- category: Fixed -->
- **web-demo (tests)**: the WebXR Playwright specs false-failed from the day
  `iwer` 2.3.0 shipped (2026-07-09) — 2.3.0 added a guard that silently skips
  `installRuntime()` when a native `navigator.xr` exists, and headless
  Chromium ships one (answering `isSessionSupported=false`), so the shim never
  took on CI and the AR/VR buttons stayed `display:none`. The test helper now
  passes the official `{ forceInstall: true }` escape hatch — honored by
  2.3.0, accepted-and-ignored by 2.2.x, no version pin needed. Verified 3/3
  specs pass under both 2.2.1 and 2.3.0.
