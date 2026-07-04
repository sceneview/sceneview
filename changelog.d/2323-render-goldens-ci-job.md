<!-- category: Tests -->
- `render-tests.yml` gains a `demo-render-goldens` job: `DemoRenderingScreenshotTest` (previously run by NO workflow) now executes on the pinned emulator profile and uploads first-run captures as an artifact — the #2323 silent-skip gap becomes a harvestable baseline source. Non-blocking until the 8 missing goldens are reviewed and committed.
