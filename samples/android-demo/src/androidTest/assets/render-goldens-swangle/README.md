# 3D-demo render goldens, CI profile

The references the `demo-render-goldens` CI job compares against
(`goldenSet=render-goldens-swangle`, #3554). Same test, same contract and same
tolerances as `../render-goldens/` (read that README first); only the profile
differs, so these are never interchangeable with the hardware-GPU set.

## Profile

`ubuntu-latest` hosted runner, API 30 `google_apis` x86_64, **`-gpu
swangle_indirect`** (ANGLE over SwiftShader-Vulkan), 1080x2400 @ 420 dpi, light
mode. The test crops the 96 px status bar, so the PNGs are **1080x2304**.

## Readiness

On this rasteriser the first frame takes tens of seconds, and the scaffold's own
loading states (the M3 loading indicator, the "Still loading…" card after 12 s)
are non-flat content. The test therefore captures only once the viewport is named
"Scene ready", no stall card is up and no `Loading…`/`Streaming…` scrim is
visible, then waits `READY_TAIL_MS`. The readiness budget,
`SOFTWARE_MAX_SETTLE_MS`, is about twice the slowest measured case; every run
logs `first-frame slug=… elapsedMs=…` in the job's `logcat.txt`.

## Recording

1. Remove the case from `SWANGLE_BASELINED_GOLDENS` (and its PNG here), push, and
   dispatch `render-tests.yml` on the branch. The case takes the first-run path
   and saves `<name>_first_run.png`.
2. Download the `demo-render-golden-captures` artifact and **look at every
   image**: model present, colours right, no loading indicator or card, chrome
   intact. A capture that is wrong is not a reference.
3. Copy the good ones here as `<name>.png` and add them back to
   `SWANGLE_BASELINED_GOLDENS` in the same commit.

## Left out of the gate

| Case | Why |
| --- | --- |
| `lightinglab_default` | The helmet is missing from the recording capture (run 36415395782) and present in the next run: the scene reports ready before the model is always in it. It takes the first-run skip until a capture shows the full scene. |
| `splatpreview_default` | Correct render, but not reproducible: the splat is framed differently from one run to the next (54.7 % of pixels differed from its recording in run 36416690269). No single capture can be its reference. |
