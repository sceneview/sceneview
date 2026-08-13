---
name: device-qa
description: The autonomous device-QA harness for the SceneView demo apps — device-qa.sh and its four legs (Maestro Android/iOS, Playwright web, AR replay), the GRADED release gate (blocking web leg vs advisory android/ar/ios legs), Android Vitals and Play-review triage. The ARCore-ready emulator pool and the golden boot snapshot are NOT here — they live in `android-tooling`. USE THIS ONE for the SCRIPTED harness — you are running device-qa.sh / the Maestro flows / the release gate and reading their verdicts. For driving a device BY HAND (screenshots, taps, ad-hoc install, lease refusals), use `android-tooling` instead. Use when running or debugging a QA harness run, when a demo must be proven not to crash on a real screen, or at any release checkpoint before tagging.
---

## Device QA

The **autonomous device-QA harness** (umbrella [#1560](https://github.com/sceneview/sceneview/issues/1560))
drives the demo apps **like a real user** — taps, swipes, camera-orbit drags,
navigation — and asserts no crash across every platform, unattended (no
screenshot-by-screenshot loop). CI-green plus self-review is not enough: a demo
can compile, pass unit tests, and still crash the moment it renders on a device.

### Run it

```bash
# Full cross-platform pass — every platform feasible on this host.
bash .claude/scripts/device-qa.sh --platform=all

# One platform, or a fast per-category subset.
bash .claude/scripts/device-qa.sh --platform=android
bash .claude/scripts/device-qa.sh --platform=web --fast
```

`device-qa.sh` is the single orchestrator entrypoint. It boots the
emulator/simulator each leg needs, builds + installs the demo app, delegates to
the per-platform harness, and aggregates every verdict into one
`device-qa-report.json` at the repo root (override the directory with `--out`).
Exit status is non-zero if any selected platform failed. Flags: `--platform=android|ios|web|ar|all`,
`--fast` (per-category subset, not the full catalog), `--ci` (treat a skipped
platform as a failure), `--out <dir>`.

### What each leg covers

| Leg | Harness | Drives | Report |
|---|---|---|---|
| `android` | Maestro flows `.maestro/android/` via `qa-android-demos.sh` | every demo in the Android catalog, on an emulator | `device-qa-report.json` |
| `ios` | Maestro flows `.maestro/ios/` via `ios-device-qa.sh` | every deep-linkable demo on a simulator (AR = launch-only smoke) | `device-qa-report.json` |
| `web` | Playwright suite `samples/web-demo/tests/` | Browser 3D viewer + every catalog tab | `web-qa-summary.json` |
| `ar` | `ar-replay-qa.sh` + `ARReplayHarnessTest` | Every Android AR demo replayed against recorded ARCore sessions — no physical device | `ar-qa-summary.json` |

**Never hardcode a demo count here.** This table said "53" and "63" until
2026-08-13, when the real numbers were different again — no gate keeps a count
written in a skill honest, and a stale count silently reads as "the leg covered
everything". Get the live figures from the collators that the gate itself runs:

```
bash .claude/scripts/check-demo-id-parity.sh   # Android ids vs iOS registry, both regenerated
```

See [`.maestro/README.md`](/.maestro/README.md) for the Maestro flow layout and
known limitations (no pinch gesture → 3D zoom is driven via deep-link param).

**iOS leg status — CI-wired since #2833 (2026-07-20), advisory.** `device-qa.yml`
now defines an `ios` job (Maestro / Simulator; routed to the self-hosted
`sceneview-mac` runner when its heartbeat is fresh, `macos-15` otherwise), the
nightly runs it via `device-qa.sh --platform=ios --fast --ci`, and
`render-tests.yml`'s iOS job drives the `SceneViewDemoUITests` UI-testing
target with real `XCTAttachment` screenshots. The leg is in the default
ADVISORY set — a red ios leg is a `WARN`, not a release block. Caveat: on the
self-hosted Mac the leg is disk-gated (< 10 GB free → honest advisory skip),
so keep the host's disk above the gate for real coverage.

### Release-checkpoint mandate

**A full device-QA pass runs at every release checkpoint, before tagging.**
No release ships with a red *blocking* leg in `device-qa-report.json`. The
gate is enforced in two places — keep both honest:

- `release-checklist.sh` **section 14** reads the report's graded
  `releaseGate.verdict` and fails the checklist on a `blocked` verdict (or a
  missing report).
- The `/release` skill (**Step 6.5**) runs `device-qa.sh --platform=all`
  before the tag step.

#### Release-gate policy for `continue-on-error` legs (#1651)

The legs are **graded**, because they are not equally reliable:

| Leg | CI behaviour | Release gate |
|---|---|---|
| `web` | NOT `continue-on-error` — a red leg fails the workflow | **BLOCKING** — a red web leg is a release `FAIL` |
| `android`, `ar` | `continue-on-error: true` (flaky SwiftShader emulator, #1643) | **ADVISORY** — a red leg is a `WARN`, never a silent pass, never a hard block |

`device-qa.sh` tags each leg `advisory: true|false` (default advisory set:
`android,ar,ios,web-perf,sketchfab,arcore-cloud`, override with
`--advisory=<csv>`) and pre-computes
`releaseGate.verdict` in `device-qa-report.json`:

- `clear` — every leg passed → checklist `PASS`.
- `warn` — an advisory leg (android/ar) did not pass → checklist `WARN`
  ("advisory leg(s) did not pass: … — review before tagging"). A human sees
  it, but it does not block the release.
- `blocked` — a blocking leg (web) failed → checklist `FAIL`, hard block.

Advisory legs stay non-blocking until #1643/#1645 make the emulator reliably
green; then promote them to blocking by shrinking the `--advisory=` set. A red
*blocking* leg means a demo crashes for a real user — fix it before tagging,
no exceptions.

#### Android Vitals release-gate (#1691)

Device-QA validates the demo app on **emulators** *before* release. Android
Vitals is the complementary post-release signal: the **real crash & ANR rate**
across live Play Store users.

- `release-checklist.sh` **section 15** runs `.claude/scripts/play-vitals.sh`,
  which queries the **Play Developer Reporting API** for
  `io.github.sceneview.demo` and grades the 28-day user-perceived crash & ANR
  rates against Google Play's bad-behaviour thresholds (crash 1.09%, ANR 0.47%).
- **Advisory-first**: the gate is `WARN`-only by default and never freezes a
  release. A missing `PLAY_STORE_SERVICE_ACCOUNT_JSON` secret, the 403 you get
  before the read-only **"View app quality information"** Play Console
  permission is granted, or a fresh app with no data all degrade to `WARN`.
  Set `PLAY_VITALS_HARD=1` to promote a hard-threshold breach to a release
  blocker once the numbers are trusted.
- Reuses the existing deploy service account — **no new write scope**.

#### Play Store reviews → triage issues (#1692)

`maintenance.yml`'s `play-reviews` job runs daily, ingesting Play Store
ratings + written reviews via the Android Publisher `reviews.list` API (same
deploy service account, read-only). Reviews matching a crash/bug signal —
or 1-star reviews with a real comment — auto-open a **de-duplicated** triage
issue (keyed by the stable Play `reviewId`) with the review text, device, and
app version. **Documented API gaps:** `reviews.list` only returns reviews from
≈the last week, and install counts are not exposed by any queryable Play API
(only bulk CSV reports) — both are surfaced honestly rather than faked.

