<!-- category: Fixed -->
- **Nine demo render goldens still showed the retired demo chrome
  ([#3551](https://github.com/sceneview/sceneview/issues/3551)).** The unified demo moved
  from a light Material app bar plus a FAB to the floating dark pill, so every baseline
  recorded before that redesign disagreed with the live app across a quarter to
  three-quarters of its pixels — `animationphysics`, `debugoverlay`, `fog`, `geometry`,
  `lighting`, `lightinglab`, `modelviewer`, `pickingcollision` and `secondarycamera` were
  all failing for the chrome, not for the render. They are re-recorded on the reference
  AVD (Pixel_7a, 1080x2400 @ 420 dpi, light mode, hardware GPU) and each capture was
  reviewed by eye before promotion: the model, the environment and the on-screen controls
  are present in every one. The suite now runs 15 of 15 green.
