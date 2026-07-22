<!-- category: Tests -->
- **The iOS `build` job can now actually fail on a compile or test failure.** The
  `Build Swift Package`, `Run tests`, and `Build & test iOS sample demo` steps ended their
  xcodebuild pipeline with `| xcpretty … || cat`; in CI `cat` reads an empty stdin and exits 0,
  which swallowed the non-zero status `set -o pipefail` had propagated from a failing xcodebuild —
  so the job could not go red and the test step was effectively decorative. All three now use
  `|| exit ${PIPESTATUS[0]}` (xcodebuild's own exit code), matching the device-compile step from
  [#2865](https://github.com/sceneview/sceneview/issues/2865) and preserving the missing-`xcpretty`
  tolerance ([#2852](https://github.com/sceneview/sceneview/issues/2852), [#1515](https://github.com/sceneview/sceneview/issues/1515)).
