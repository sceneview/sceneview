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
carries it. A load that never lands shows a "could not load" card on the stage
after 30 s, so a capture of a failed load is a red case, not an empty stage.

Not yet confirmed on the CI runners. Two things to read on the first runs:

- `geometry_default` still matches the early state (it passed on the slow
  runners and failed by 5.4 % on the fast ones). Neither geometry screen loads a
  model; the only thing #4459 changes for them is the drain behind the frame
  that carries the HDR. If that was the difference, this reference now fails
  everywhere and is re-recorded from a CI run; if both pictures still appear,
  the cause is elsewhere and is not known.
- `splatpreview_default` now waits for the decoded scan, which removes the
  empty-stage capture but is not known to explain a 54.9 % difference in
  framing. It stays out of the gate until seen stable.
