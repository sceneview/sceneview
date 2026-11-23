# Scan replay bench — pass 2

Uncommitted work on `feat/scan-replay-bench`, 2026-11-13. No Gradle invocation, device run,
commit, push or merge. `local.properties` was neither read nor changed. The existing
`REF-plateaufps.sh` was read as a reference and left unchanged.

## App changes, separable from the bench

| File | Reason |
| --- | --- |
| `samples/android-demo/src/debug/java/io/github/sceneview/demo/demos/ScanBench.kt` | Debug-only `ScanBench` JSON observations: one full-second hook-call count, first surface/point readiness, floor, points and outside-room surfels. |
| `samples/android-demo/src/release/java/io/github/sceneview/demo/demos/ScanBench.kt` | Matching `internal` no-op signature; no logging or observation state in release. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/RerunRecordUi.kt` | The existing single `scanBenchObserve(media, frame, coverage, dense, walls)` call stays outside the new-trace-frame gate, so repeated render calls count. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/MainActivity.kt` | Read `scan_scope` on create and new intent, behind `BuildConfig.DEBUG`; accept only `room` and `object`, clear the override for absent/invalid extras. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/RerunScopeUi.kt` | An in-memory launch override precedes `ScanScopeStore.last`'s saved preference; the extra does not overwrite the user's remembered choice. |

Kotlin checked by reading: package names, imports, internal visibility, referenced geometry
members, the `ArDebugSceneView` render callback, build types/source sets, and identical
parameter lists in debug/release. `MainActivity` gates scope changes before composition.
Both build types still use their normal source sets; no build configuration was changed.
Release ignores `scan_scope`. This is inspection, **not a successful Kotlin compilation**.
The caller starts from a force-stopped app on each run, so the scope is set before the scan's
remembered Compose state is created.

## Exact command: main.apk vs v2.apk, five reference walks

Run from this worktree, with the two already-built `.qa` debug APKs at `main.apk` and `v2.apk`:

```bash
bash .claude/scripts/scan-replay-bench.sh \
  --apk-a main.apk --label-a main \
  --apk-b v2.apk --label-b v2 \
  --corpus /Users/thomasgorisse/Projects/sceneview/samples/android-demo/ar-corpus/pixel9-thomas/ar-recordings \
  --walks A,B,C,D,E \
  --serial 09291JEC223501
```

This worktree has no private corpus. The explicit main-checkout corpus directory above was
checked and contains **14 MP4s**. No real clip was opened or replayed. Without `--corpus`, the
bench looks in this checkout's `samples/android-demo/ar-corpus/pixel9-thomas/ar-recordings/`.
Without `--walks`, the selection is the same five references, in this order:

| Walk | ID |
| --- | --- |
| A | `ar-session-20260928-003538` |
| B | `ar-session-20260928-003416` |
| C | `ar-session-20260929-092427` |
| D | `ar-session-20260930-224347` |
| E | `ar-session-20260926-202254` |

`--walks all` selects all corpus MP4s; `--walks id,id` accepts IDs with or without `.mp4`.
Reference letters remain in `mesures.tsv` and the HTML. Build A means main; build B means v2.
Both APKs need the debug hook to compare hook cadence/readiness/outside counts.

## Device sequence

- Acquire `/tmp/pixel4a.lock` for each install and each run separately. Write an owner token;
  wait at most 600 seconds in 15-second intervals if occupied, then skip with a recorded reason.
  Release only an owned lock. Never remove another owner's lock.
- Install A once with `install -r -d`, grant camera permission, run every selected walk; then
  install B once and run them again. Detect an intervening install by another lock owner and
  fail that run rather than attributing its measurements to the wrong APK.
- Reuse `/sdcard/Android/data/io.github.sceneview.demo.qa/files/ar-recordings/<walk>.mp4`.
  Push only when absent or a different size; verify the pushed size. Keep all source walks.
- Start a 6,000,000-bit/s live recording for `ceil(ffprobe duration + 20)` seconds. Launch with
  the explicit `.qa` package/main activity component, `demo=ar-rerun`,
  `telemetry_consent=denied`, `ar_playback_file=<device path>`, and `scan_scope=room`.
  The live capture starts automatically; there is no synthetic QA state or manual Start tap.
- Dump accessibility XML on a 2-second schedule, recording actual device timestamps. Tap
  `Stop and review` by exact text/content-desc and bounds. Record a second 25-second video;
  keep polling and select Model once Photo, Model and Plan are all present. Capture `model.png`
  at the end. Pull both videos and `logcat -b all -d -v epoch` after the result phase.
- Recordings over Android's 180-second limit are segmented and concatenated without
  re-encoding. Segments can have a small hand-off gap. Only this run's temporary recordings,
  PID files and XML dump are removed; source walks are never deleted.
- ffmpeg processing and HTML generation happen after releasing the device lock. No browser
  or `drawtext` is required to run the bench.

## Measures and their limits

| TSV measure | Source |
| --- | --- |
| `ar_fps_mean`, `ar_fps_worst` | Arithmetic mean and minimum of full 1-second `ScanBench.fps` buckets from the hook's own calls, during live scan. Repeated trace frames count. When calls resume after a stall, missing full seconds emit zeros. A terminal stall with no later call cannot emit a final bucket; `ar_seconds` exposes the measured bucket count. |
| `sf_fps_mean` | Mean of valid live SurfaceFlinger samples on the unique `.qa` SurfaceView BLAST layer. Use column 2 (`actualPresentTime`), deduplicate timestamps and exclude zero/sentinel entries. Per window: `(n−1) / (last−first)`. Raw dumps and `surfaceflinger.json` are retained. No activity/TextureView fallback; the finite ring buffer can miss long stalls. |
| `first_surface_s` | First `surface_ready=true` JSON timestamp minus a device `date +%s.%N` emitted immediately before `am start` in the same shell. Logcat uses epoch, so no host/device clock mixing. Readiness includes a dense mesh, wall, map point or live point; this precedes GPU presentation and is a visibility proxy. |
| `floor_m2`, `points` | Last successfully parsed live HUD accessibility text before Stop and review. Points accept comma, dot, ordinary/NBSP/thin spaces and apostrophe thousands separators; floor accepts decimal comma/dot. Result HUD values cannot overwrite these. |
| `moving_too_fast_s` | Sum of intervals following positive live warning polls, capped at 2 seconds each and at live end. Failed/missed polls contribute no invented warning time. `poll_observed_s` records covered observation time. |
| `outside_elements` | Last live hook value: drawn dense surfel centres outside the convex hull of detected closed walls in XZ, allowing 10 cm wall noise. Null until a closed room/dense layer exists; this is a geometric proxy, not ground truth or a count of objects. |
| `fatals`, `anrs` | Matching log lines after the launch epoch, including the result phase: `FATAL`/`Fatal signal`, and `am_anr`/`ANR in`. One incident may produce several matching lines. |
| `result_ready_s` | First result poll containing all three exact labels Photo, Model and Plan minus the device timestamp immediately before the Stop and review tap. Resolution is 2-second polling plus dump latency. `model.png` captures the selected Model view at the end. |

`ArDebugFrame` does not expose ARCore's current TrackingState. Logs explicitly use
`tracking_state:null` and a separate `camera_pose_available` boolean; a retained pose is not
reported as current tracking. Point/floor snapshots accompany each cadence line. Slow
uiautomator dumps can overrun the 2-second schedule; actual timestamps and errors remain in
`polls.tsv`. Missing measurements remain blank/unavailable, not zero.

## Outputs and bench files

All real outputs default to one private `~/sceneview-bench/YYYY-MM-DD-HHMM/` folder. Existing
nonempty output directories and any repository path (including symlinks into one) are refused.
The self-test uses synthetic footage only and can use `--out /private/tmp/<fresh-name>`.

- `.claude/scripts/scan-replay-bench.sh`: shell entry point and usage; no build command.
- `.claude/scripts/scan_replay_bench.py`: lock/install/run orchestration, parsers, ffmpeg media,
  TSV and local HTML.
- `.claude/scripts/scan-replay-bench.css`: inline report styles using system fonts, M3 roles and
  `prefers-color-scheme`, without gradients.
- `.claude/scripts/scan-replay-bench-fixtures/`: HUD, XML, logcat, poll and SurfaceFlinger fixtures
  plus the synthetic self-test; no private footage.

Each `<walk>/<build>/` contains `live.mp4`, `result.mp4`, `polls.tsv`, `logcat.txt`,
`model.png` when Model could be selected, metrics and cadence evidence. Contact sheets are
`<walk>-<label>-tiles.jpg`, sampled every 2 seconds from t=0, in reading order across 8 columns.
The last row can have empty cells. Root `<walk>-ab.jpg` has A left/B right at live t=8, 16, 24 s,
then the last result frame. ffmpeg adds labels only if `drawtext` exists; the HTML always states
column and row order. The local ffmpeg has no `drawtext`.

`mesures.tsv` has one row per selected walk × build, including skipped/failed runs. `sheet.html`
starts with A/B values and deltas labelled better/worse/same; all image links are relative and
CSS is inline. No network resources. More points/floor are labelled better for completeness,
not asserted to be more accurate. Failed media/metrics remain explicitly unavailable.

Exit status: 0 complete, 1 incomplete/skipped/failed runs or warnings, 2 setup error, 130 interrupt.

## Validation

```bash
bash .claude/scripts/scan-replay-bench.sh --self-test \
  --out /private/tmp/scan-replay-bench-pass2-verified
bash -n .claude/scripts/scan-replay-bench.sh
git diff --check
```

Self-test result: `Ran 13 tests` / `OK`. Checks cover HUD separators, exact accessibility targets,
clock/readiness/stalls, missing poll intervals, SurfaceFlinger invalid/duplicate entries,
conditional push, lock ownership/wait/timeout, reference/all selection, install ordering and
lock release, skipped installs, and a complete simulated live → result → pull workflow. Real
ffmpeg generates synthetic videos, contact sheets and A/B sheets without drawtext; changing
colours plus pixel assertions verify the 8/16/24-second order and final result row. Shell syntax
and `git diff --check` pass. `--help` works. No real adb command was executed.

Generated JPEG contact/comparison sheets were visually inspected. Full HTML desktop/mobile,
light/dark screenshots could not be completed: local Chrome headless exited **134**, without a
PNG or diagnostic. Inline CSS, relative existing links and light/dark rules were inspected;
this is not browser-render validation. Synthetic preview: `/private/tmp/scan-replay-bench-pass2-verified/sheet.html`.

Remaining for Thomas: compile both `.qa` APKs, run the command, and assess the real device
captures/metrics. No Kotlin build or real-device success is claimed here.
