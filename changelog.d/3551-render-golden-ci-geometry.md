<!-- category: Fixed -->
- **The `demo-render-goldens` CI leg had never compared a single golden, and reported
  green for it ([#3551](https://github.com/sceneview/sceneview/issues/3551)).** The job
  booted the system image's default AVD, which renders at 320x544; every golden is
  1080x2304. So all 15 cases died on `Size mismatch` before reading a pixel, and the
  `|| true` inside a `continue-on-error` job turned that into a green check. Worse, the
  run never got past 2–3 cases — the emulator process was going away mid-suite (`adb:
  device offline`), which is also why the `demo-render-golden-captures` artifact had
  never contained a file: the `adb pull` ran against a device that no longer existed. The
  job now pins the emulator to 1080x2400 @ 420 dpi — the geometry the goldens are recorded
  at — with `-skin` plus `wm size`/`wm density`, asserted in the step so a wrong geometry
  breaks the run instead of hiding in it, with the RAM, cores and data partition that
  framebuffer needs, and a new
  step writes the real executed / passed / failed counts to the run summary, annotating
  a shortfall when cases never ran. The leg stays advisory, per this repo's doctrine for
  emulator legs — but advisory now means "not a merge block", not "unreadable".
  `render-goldens/README.md` is stated as the single source of truth for how a golden is
  recorded, and the workflow comment that claimed goldens "MUST come from this exact
  [CI] config" is gone: that config renders on SwiftShader and cannot produce a baseline
  a real GPU will match.
  The captures that leg finally produced then showed what SwiftShader actually renders:
  nothing — every frame is the demo's own "The scene has not rendered a frame yet." card,
  which the suite had been comparing against a golden and reporting as a 99.75 % render
  regression. The suite now recognises that card; on a run that declares
  `softwareRenderer=true` it is an explicit skip with a reason, and everywhere else it
  stays the hard failure it should be. No pixel comparison is relaxed, so a hardware-GPU
  runner would start gating for real without another edit.
