<!-- category: Tests -->
- `DemoRenderingScreenshotTest` validates each demo slug against a lower-kebab pattern
  before it reaches the shell command that launches the demo, and waits on the qa_mode
  pill's full text (`QA ×`) rather than the bare substring `QA`, which demo copy and
  control labels can also contain. Review follow-ups to
  [#3100](https://github.com/sceneview/sceneview/pull/3100).
