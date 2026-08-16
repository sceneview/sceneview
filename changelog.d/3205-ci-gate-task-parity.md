<!-- category: Fixed -->
- **The pre-push gate ran 4 of the 7 unit-test tasks CI runs ([#3205](https://github.com/sceneview/sceneview/issues/3205)).**
  `:sceneview-core:androidTest`, `:samples:common:testDebugUnitTest` and
  `:samples:android-tv-demo:testDebugUnitTest` were named neither in the legs nor in
  the "deliberately not covered" list, which the script's own header defines as an
  unaudited gap rather than a decision. The last of the three had been wired into CI
  the day before by #3193 — a fix for a test suite no workflow invoked, which left the
  local gate one storey behind. The gate now runs all seven, and
  `test-ci-parity-gradle-tasks.sh` derives both lists from disk so the next task added
  to that CI job cannot go unrun in silence: it must be run locally, covered by a
  module's aggregate `:m:test`, or excused by a written `CI-PARITY-EXCLUDE:` line.
