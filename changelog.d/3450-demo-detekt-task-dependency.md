<!-- category: Fixed -->
- **`./gradlew :samples:android-demo:detekt` no longer fails Gradle's task-ordering validation
  ([#3450](https://github.com/sceneview/sceneview/issues/3450)).** detekt scans the same
  `src/main/java` tree kotlinc compiles, which contains the build-generated `GeneratedDemos.kt`,
  but only the Kotlin compile tasks declared their dependency on `generateDemoRegistry`; the demo's
  detekt tasks now declare it too. The CI `Lint` job runs the demo's detekt alongside the four
  library modules, so the task is exercised on every Android PR instead of only on a maintainer's
  machine; its 93 pre-existing findings (70 of them `MaxLineLength`, 91 distinct signatures) are grandfathered in
  `buildSrc/config/detekt/baseline-android-demo.xml`, the same treatment the library modules got, so
  only new violations fail.
