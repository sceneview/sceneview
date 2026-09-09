<!-- category: Fixed -->
- **The `demo-render-goldens` CI leg had never compared a single golden, and reported
  green for it ([#3551](https://github.com/sceneview/sceneview/issues/3551)).** The job
  booted the system image's default AVD, which renders at 320x544; every golden is
  1080x2304. So all 15 cases died on `Size mismatch` before reading a pixel, and the
  `|| true` inside a `continue-on-error` job turned that into a green check. Worse, the
  run never got past 2–3 cases — the emulator process was going away mid-suite (`adb:
  device offline`), which is also why the `demo-render-golden-captures` artifact had
  never contained a file: the `adb pull` ran against a device that no longer existed. The
  job now boots a `pixel_8` profile (1080x2400 @ 420 dpi — the geometry the goldens are
  recorded at) with the RAM, cores and data partition that framebuffer needs, and a new
  step writes the real executed / passed / failed counts to the run summary, annotating
  a shortfall when cases never ran. The leg stays advisory, per this repo's doctrine for
  emulator legs — but advisory now means "not a merge block", not "unreadable".
  `render-goldens/README.md` is stated as the single source of truth for how a golden is
  recorded, and the workflow comment that claimed goldens "MUST come from this exact
  [CI] config" is gone: that config renders on SwiftShader and cannot produce a baseline
  a real GPU will match.
