- CI now actually verifies the committed Roborazzi golden screenshots for
  `samples:android-demo` — the `Unit tests` job swaps
  `:samples:android-demo:testDebugUnitTest` for
  `:samples:android-demo:verifyRoborazziDebug`, so a layout regression in a
  covered composable fails the PR instead of silently passing (the goldens
  were previously only checked locally via `pre-push-check.sh`). A failed
  verify now also uploads a `roborazzi-diff-report` artifact with the
  actual/diff PNGs.

<!-- category: Fixed -->
