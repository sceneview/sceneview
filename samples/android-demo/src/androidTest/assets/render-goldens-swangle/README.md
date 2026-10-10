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
| `splatpreview_default` | Correct render, but not reproducible: the splat is framed differently from one run to the next (54.7 % of pixels differed from its recording in run 36416690269, 54.9 % between the two attempts of run 38044621943). No single capture can be its reference. |

## Known gap: "Scene ready" comes before the models (#4448)

Seven runs of 2026-10-10 on the same code gave **two pictures** for several
screens, by runner speed. On the fast runners the capture holds the whole scene;
on the slow ones it is taken before a model, or the environment, is in it:

| Case | What the early capture lacks |
| --- | --- |
| `cameragestures_default` | all three models (4 runs of 7) |
| `secondarycamera_default` | the helmet in both views (3 of 7), or only the environment's shading (1 of 7) |
| `lighting_default` | the helmet and the sky (2 of 7), or the sky alone (1 of 7) |
| `fog_default`, `lightinglab_default` | the helmet (5 of 7) — both slugs open the same Lighting Lab screen since #4282 |
| `customgeometry_default`, `geometry_default` | nothing visible to the eye: only the shading of the shapes differs, by 11.5 % and 5.4 % of pixels (4 runs of 7 in the early state) |
| `materials_default` | the blurred sky behind the spheres (1 of 7) |

The references here are the complete picture, so a run that captures early goes
red, and it should: the picture it took is not the screen. The cause is the
readiness signal, not the renderer — "Scene ready" follows the first presented
frame (and, on the screens that pass `sceneReady`, the HDR), never the models.
Until a screen holds "Scene ready" for its models too, expect these cases to
flip with the runner. One reference still matches the early state:
`geometry_default` passes on the slow runners and fails by 5.4 % on the fast
ones. Re-record it once the gap is closed, not before.
