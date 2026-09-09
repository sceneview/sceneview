<!-- category: Tests -->
- **Five rebuilt demos have a render golden again
  ([#2323](https://github.com/sceneview/sceneview/issues/2323)).** `materials` (#3495 /
  [#3538](https://github.com/sceneview/sceneview/pull/3538)), `camera-gestures` (#3500 /
  [#3540](https://github.com/sceneview/sceneview/pull/3540)), `custom-geometry` (#3423),
  `two-d-in-three-d` (#3424) and `lines-paths` (#3425) each replaced their scene wholesale,
  so each rebuild deleted the golden that pictured the old one and took its slug out of
  `BASELINED_GOLDENS`. That is the documented first-run path, but it leaves the case
  `assumeTrue`-skipped — five demos whose render nothing was checking. All five are
  re-recorded from the rebuilt scenes on the shared `Pixel_7a` AVD, each capture looked at
  before promotion (the nine-sphere material wall, the three-subject camera stage, the
  runtime torus knot, the four depth-tested Compose cards, the tube-extruded splines), and
  each verified by a second run that compares green against the committed PNG.
