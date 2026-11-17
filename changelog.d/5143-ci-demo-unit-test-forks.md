<!-- category: Tests -->
- **The demo's unit tests run in two JVMs per variant on CI ([#5143](https://github.com/sceneview/sceneview/pull/5143)).** A new `demoUnitTestForks` Gradle property sets `maxParallelForks` on `samples/android-demo`'s unit tests; the "Unit tests" job passes 2, so debug and release use the runner's 4 vCPUs. Local runs keep one JVM.
