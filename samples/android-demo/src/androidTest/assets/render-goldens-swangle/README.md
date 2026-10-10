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

### What moved the Splat Preview picture (#4459)

Not the framing: the three CI captures of commit `4140018cb` hold the same
geometry and differ in how the translucent points are blended, mostly in the
foreground ground — 0.49 % of pixels between runs 1 and 3, 13.6 % and 13.8 %
between run 2 and the two others. The captures were taken 15.3 s, 7.8 s and
8.5 s after "Scene ready".

The cause was the order the screen opened in. It drew an empty stage on the
default camera while the file decoded, then swapped the camera onto the scan,
which the SDK turns into a 0.6 s glide. `SplatNode` sorts its points back to
front for the camera on a background thread, one sort at a time, and only
starts another when the camera has moved more than 1 % of the scan's radius.
So the sorts chased the glide: "Scene ready" could be announced on an order
computed for a pose the camera had already left, replaced seconds later on a
software rasteriser, and the last order kept was sorted for a pose merely
*near* the final one, which one depending on frame timing.

Measured on `emulator-5554` (host GPU, status bar cropped, 3 450 880 px),
captures at +0.5 s, +2 s, +5 s and +15 s after "Scene ready":

| | Within one cold start | Between cold starts |
| --- | --- | --- |
| Before | 0 px at all four delays | 0.30 % and 0.03 % — three starts, three pictures |
| Before, order forced to the one sorted for the default camera | 0 px over 15 s | 20.4 % against a normal start |
| After | 0 px at all four delays | 0 px across five cold starts |

The screen now draws the stage only once the file is open, with the camera
already on the scan (no glide, one sort, for the exact home pose), and keeps
its cover up until that sort is on screen. On the emulator the scan appears
about 0.6 s later than before, because the surface is no longer created while
the file decodes.

**Not proven here:** the emulator is too fast to show the settle the CI
captures show, so the row above stays until `render-tests.yml` has produced
the same picture on several SwiftShader runs. Its reference must come from
those runs — the sort order changed, so no older capture matches (0.51 %
against the previous emulator picture).

## "Scene ready" came before the models (#4448, closed by #4459)

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
red, and it should: the picture it took is not the screen. The cause was the
readiness signal, not the renderer — "Scene ready" followed the first presented
frame (and, on the screens that pass `sceneReady`, the HDR), never the models.

Since #4459 each of these screens holds "Scene ready" for what its picture
needs — the model instances, their textures (`ModelLoader.isLoading`), the
environment, the decoded splat — and, when any of it landed after a frame had
already been presented, for one backend drain behind the first frame that
carries it. A load that reports a failure shows a "could not load" card on the
stage, and the test fails on that card by name; a load that is merely slow shows
"Still loading part of this scene…" after 30 s and is never captured as ready.

First CI run with #4459 (38082396646, 2026-10-10): 13 of the 14 gated cases
matched. One more run is not a proof of stability; read the next ones.

- `geometry_default` was the early state. With #4459 the capture carries the
  environment's highlights on the shapes and differed from the old reference by
  5.38 %, as predicted; the reference here is that run's capture.
  `customgeometry_default` matched as it was.
- `splatpreview_default` now waits for the decoded scan, which removes the
  empty-stage capture but is not known to explain a 54.9 % difference in
  framing. It stays out of the gate until seen stable.
