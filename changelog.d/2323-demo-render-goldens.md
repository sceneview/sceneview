<!-- category: Tests -->
- `DemoRenderingScreenshotTest` now actually guards against visual regressions. Seven of
  its fourteen baselines had been recorded as empty viewports — four as 320×544 all-black
  captures, three (`fog`, `lighting`, `lines-paths`) as full-size frames whose SceneView
  band never rendered — and one (`secondary-camera`) was missing entirely, so those cases
  either compared against nothing or compared nothing against nothing. Eleven goldens are
  re-recorded from settled renders and one (`secondary-camera`) is added; the two that
  already depicted a correct settled render (`custom-geometry`, `two-d-in-three-d`) are
  left byte-for-byte untouched. All fourteen cases pass under the new guards over two
  consecutive full runs ([#2323](https://github.com/sceneview/sceneview/issues/2323)).
- The harness refuses the states that produced those baselines: a committed golden with a
  flat SceneView band fails as `DEGENERATE`, a capture whose viewport never rendered fails
  instead of being recorded, and the run waits for the `qa_mode` badge so a splash screen
  can never be captured as the demo. The content probe no longer exempts unexpected
  viewport geometries, and its band sits inside the viewport rather than overlapping the
  app bar — that overlap is what let three all-black baselines score as "has content".
- Screenshot captures are pinned to light mode, so a device left in dark mode no longer
  reddens the whole suite with a ~50 % pixel diff that says nothing about rendering.
- Demos that load a `.glb` now settle for 14 s: a loaded skybox reads as "rendered" while
  the model is still missing, which the content probe cannot detect.
- Debug-artifact writes can no longer mask a verdict. `saveToDeviceForReview` threw
  `FileNotFoundException: EACCES` from inside the failure path, replacing six real
  assertions with filesystem errors; it is now best-effort and reports where it landed.
