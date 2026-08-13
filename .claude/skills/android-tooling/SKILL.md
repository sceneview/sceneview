---
name: android-tooling
description: Google's android CLI (screenshots, UI dumps, install+launch) and when to still use adb; the RAM-budgeted adaptive emulator pool with its per-SESSION lease protocol and the blocking PreToolUse hook that enforces it; the golden qa-clean snapshot; the Rosetta x86_64 AR rig (measured NOT to deliver live-camera AR — kept as evidence); and the three agents/sceneview* skill installers. USE THIS ONE for driving a device BY HAND. For the scripted QA harness and its release gate, use `device-qa` instead. Use when driving an Android emulator yourself, capturing a screenshot or UI tree, installing a demo APK, or hitting an emulator lease refusal.
---

## Android CLI (preferred for agent-driven QA)

Google's [`android` CLI](https://developer.android.com/tools/agents/android-cli)
(tested against **v1.0.15498356** — stable, Google I/O May 2026, adds the
`android studio *` subcommands; the Journeys-from-CLI that Google's docs
announce is **NOT in this binary** (no `journeys` command — verified on-device
2026-07-09) — and still compatible with the first-release **v0.7.15411012**,
April 2026) is the agent-focused
front-end for `adb` / `uiautomator` / `emulator` / `sdkmanager`. Install note:
`dl.google.com/.../latest/` still serves **0.7**; reaching 1.0 requires running
`android update` afterwards (global upgrade — the unpacked payload in
`~/.android/bin` is shared, no side-by-side). SceneView's QA scripts
and CI install it on the fly and use it for:

- `android layout --device=<serial> -o ui.json --pretty` — JSON UI tree with
  **precomputed `center` coords** per node (no `uiautomator dump` XML parsing, no
  `bounds` regex). `--diff` returns only nodes changed since the last invocation.
- `android screen capture --output ui.png` — PNG screenshot that bypasses the adb
  shell PTY, so it doesn't suffer the **LF/CRLF translation that the legacy
  `adb shell screencap -p > file` form does** (modern `adb exec-out screencap` also
  bypasses the PTY and is fine).
- `android screen capture --annotate` + `android screen resolve --screenshot=ui.png
  --string="tap #5"` — visual-label tapping (the AI-first workflow this CLI exists for).
- ⛔ `android run --apks=… --activity=pkg/.Main` — **do not call this directly.** Measured
  2026-08-03 on CLI 1.0.15498356: it printed `App loaded:` / `Debuggable: true` and then
  `No matching components found for type ACTIVITY` for an activity the platform resolves
  fine — and **installed nothing**, leaving an 8-hour-old build on the device while a QA
  run measured it (#2990). Use `android_cli_install_and_launch`, which now proves the
  install by checking that the device's `lastUpdateTime` moved and falls back to `adb`.

**When to use what:**
- Screenshots, UI dumps, install+launch → `android` CLI (via `.claude/scripts/lib/android-cli.sh`)
- `input tap/swipe/keyevent`, `am force-stop`, `pm grant`, `emu sensor set`, `logcat`,
  `adb pull` → still `adb` — the CLI has no equivalent as of v1.0 (was already the
  case in v0.7)

The helper auto-installs the CLI to `~/.local/bin/android` on first use and falls back to
`adb` if the install fails or on multi-device hosts (the `screen capture` subcommand
had no `--device` flag through v0.7 — re-verify on v1.0 — but `android layout
--device=<serial>` does work). The helper installs v1.0 via Homebrew when the
formula is available, else falls back to the direct dl.google.com download.
Telemetry is disabled via `--no-metrics` on every invocation.

**SceneView agent skills.** This repo ships three platform-specific agent
skills under [`agents/`](/agents/) — see [`agents/REGISTRY.md`](/agents/REGISTRY.md):

- [`agents/sceneview/SKILL.md`](/agents/sceneview/SKILL.md) — Android (Jetpack Compose + ARCore)
- [`agents/sceneview-ios/SKILL.md`](/agents/sceneview-ios/SKILL.md) — Apple (SwiftUI + RealityKit, iOS/macOS/visionOS)
- [`agents/sceneview-web/SKILL.md`](/agents/sceneview-web/SKILL.md) — Web (Filament.js + WebXR)

Install them with:

```bash
bash .claude/scripts/install-sceneview-skill.sh        # Android
bash .claude/scripts/install-sceneview-ios-skill.sh    # iOS / macOS / visionOS
bash .claude/scripts/install-sceneview-web-skill.sh    # Web
```

After install, `android skills list` shows `sceneview`, `sceneview-ios` and
`sceneview-web` under the `xr` category, making the API contract, recipes, and
migration guide available to any AI agent on the host.
`bash .claude/scripts/android-env-check.sh --fix` installs the Android skill plus
the `android` CLI itself. The Android skill's Google `android-cli` registry
submission (issue #1082) is tracked in [`agents/REGISTRY.md`](/agents/REGISTRY.md).

**Emulator-first QA (mandatory).** Routine demo QA runs on a reusable ARCore-ready
emulator — **never on a personal device**. Bootstrap it once on a fresh host:

```bash
bash .claude/scripts/setup-ar-emulator.sh
```

This creates/configures a `Pixel_7a` AVD (virtualscene back camera, emulated front
camera for Augmented Faces, 4 GB RAM, host GPU), boots it headless, and installs
Google Play Services for AR. Re-run anytime — it's idempotent. `--check` inspects
state read-only; `--clean` wipes and recreates. The emulator covers all 3D demos
and AR UI/state QA. AR features that need real world tracking (Cloud Anchor,
Streetscape/VPS, face mesh against a live face) still need a physical-device
AR Record — request one rather than driving someone's personal phone over USB.

**Golden boot snapshot — faster, deterministic QA (#1672).** The QA AVD's
userdata partition fills up after ~6 runs and Filament viewports turn black.
Seed a clean post-install boot snapshot once; every subsequent run cold-boots
from it with `-no-snapshot-save` (loads the warm state, never writes back), so
runs start identical and the partition never degrades:

```bash
bash .claude/scripts/setup-ar-emulator.sh --clean --seed-snapshot   # seed once
bash .claude/scripts/setup-ar-emulator.sh                           # restores 'qa-clean'
bash .claude/scripts/setup-ar-emulator.sh --no-snapshot             # force cold boot
```

Only the base-port emulator restores the snapshot (`-snapshot` is incompatible
with the `-read-only` pool peers); `--clean` drops the snapshot; CI is
unaffected (the GitHub emulator action has its own snapshot caching). See
[`.maestro/README.md`](/.maestro/README.md) for the full rationale and the
Android Studio Journeys assessment (not adopted — blocked on an AGP 9.0.0 bump).

**Rosetta x86_64 AR rig — a probe that answered NO, kept as evidence (#2758).**

> ⛔ **Do not reach for this expecting live-camera AR QA — it was measured and it
> does not work.** The rig was built to test whether an x86_64 guest escapes the
> arm64 AR dead end. On a quiet host it *does* boot (ActivityManager registered at
> ~42 min), and three independent walls still stop it:
>
> 1. **Same camera topology as arm64.** `dumpsys -t 300 media.camera` →
>    `Device 0 maps to "1"`, `Device 1 maps to "10"` — **no HAL id `0`**. That
>    numbering comes from the *emulator's camera HAL*, not the guest ABI, so
>    #2754's stated cause is attributed to the wrong thing and x86_64 changes
>    nothing.
> 2. **ARCore cannot be installed.** The 82 MB APK transfers fine (13 MB/s) but the
>    install kills `system_server` (`Broken pipe`) — reproduced with both streamed
>    and `--no-streaming` installs. No ARCore, no session, ever.
> 3. **Nothing renders** under software GL (black framebuffer, no focused window).
>
> Real AR tracking QA needs a physical device. Keep the flag for reproducibility if
> Google ever ships a workable emulator ARCore build — not as a QA path.
>
> ⚠️ Two diagnostic traps this cost us, both of which manufactured false verdicts:
> the harness passed `-no-boot-anim` and then read `init.svc.bootanim` as progress
> (it can never move), and `dumpsys` has an **internal** 10 s timeout that TCG blows
> through, so a silent probe looks like a measured absence. Use `dumpsys -t <n>` on a
> slow guest, and never grade a mute probe as a measurement.

ARCore ships **no arm64 emulator build** (#2754): live-camera AR sessions can
never start on the default arm64 AVD, so AR demos there run in `qa_mode`
fallback only. The x86_64-under-Rosetta rig was the candidate escape hatch:

```bash
bash .claude/scripts/setup-ar-emulator.sh --rosetta            # provision + boot
bash .claude/scripts/setup-ar-emulator.sh --check --rosetta    # read-only rig report
```

This installs the Intel (darwin_x64) emulator bundle outside the SDK tree,
the `android-34;google_apis;x86_64` system image, creates AVD `Pixel_7a_x86`
(virtualscene back camera), boots it on **reserved port 5584 — outside the QA
pool's allocation range** (see `EMU_POOL_PORT_EXCLUDE_FROM`; 5584 is the last
console port inside adb's supported `[5555,5586]` window — higher ports make
the emulator warn that "ADB may not function properly", and the first rig
attempts on 5600 did see `adb shell` wedge mid-boot), and side-loads the
`_x86_for_emulator` ARCore APK — an install that, measured, kills `system_server`
on this guest. The run ends with the #2755 camera-topology probe, whose measured
answer here is ids `"1"`/`"10"` and no `0`. ~9 GB one-time payload,
disk-gated up front. The x86 guest runs under pure-software TCG (Apple
Silicon cannot hardware-accelerate an x86 guest), so expect a **~45 min
first boot** (measured) and ~5-10x-slower interaction, and never leased to
standard QA runs. `--clean
--rosetta` recreates only the x86 AVD — the arm64 AVD and its `qa-clean`
snapshot are never touched.

⚠️ **The rig needs the host to itself.** Its 3 GB guest gets no hardware
acceleration, so once the host starts swapping, the guest's pages go out and
boot progress collapses — the wait loop keeps reporting `adb: offline` while
qemu RSS *falls*. Observed on a 16 GB M3: a second, unrelated emulator
(2 GB, another session) booting five seconds after the rig pushed the host to
~5 GB of swap and neither guest made progress. The RAM gate cannot prevent
this on its own — two sessions measuring free RAM at the same instant both
pass it. Before a rig run: check `adb devices` for other emulators, and treat
falling qemu RSS as the signal to stop and retry on a quiet host.

**Visible (windowed) emulator — opt-in (#1660).** The emulator boots **headless
by default** (`-no-window`), which is marginally lighter on the host (skips the
skin-window draw + window-server compositing). To watch it locally, opt in:

```bash
bash .claude/scripts/setup-ar-emulator.sh --window   # windowed, this run
EMU_VISIBLE=1 bash .claude/scripts/setup-ar-emulator.sh   # equivalent env var
```

`--window` simply sets `EMU_VISIBLE=1`. The guest VM cost (RAM, pool, leases) is
identical either way — only the host window draw differs — so the default stays
headless and resource-safe. **Local only:** CI (`device-qa.yml`) never sets this
and stays headless (GitHub runners have no display).

**RAM-budgeted adaptive emulator pool (#1647 → #1654).** The harness runs an
**adaptive pool** of emulators — as many as live host RAM safely allows, with a
floor of 1 and never a rigid barrier. #1647's strict-single design is superseded:
parallel sessions / agents no longer serialise behind one emulator when the host
has RAM to spare. `setup-ar-emulator.sh` (via `lib/emulator-select.sh`):
- **computes a RAM-budgeted cap** —
  `max_emulators = floor((free_RAM − EMU_HOST_HEADROOM_MB) / EMU_RAM_BUDGET_PER_EMU_MB)`,
  clamped to `[1, EMU_POOL_MAX]` (defaults: headroom 2048 MB, budget 3072 MB/emu,
  `EMU_POOL_MAX=3`). On a RAM-tight host this resolves to 1 naturally — physics,
  not policy;
- **leases from a per-emulator pool** — each running emulator has a lease file
  (`${TMPDIR:-/tmp}/sceneview-device-qa-emu/<serial>.lease`, owner pid inside). A
  caller leases a free running emulator; else, if the running count is below the
  live cap, boots a new one on a distinct `-port` (5554, 5556, …) so emulators
  coexist; else waits (bounded) for a lease to free;
- **reserves per SESSION, not per pid (#2862)** — `setup-ar-emulator.sh`
  provisions an emulator and hands it back, so the emulator outlives the script.
  A pid-scoped lease died with that script and the live emulator looked free to
  every peer, which is how two sessions ended up driving one AVD. The
  provisioning run now leaves a **sticky** lease keyed to a session token
  (printed as `export EMU_LEASE_SESSION=…`, and inherited automatically by the
  QA script this session starts next). Hand it back with
  `setup-ar-emulator.sh --release` — the emulator keeps running. A sticky lease
  expires after `EMU_LEASE_STICKY_TTL` (4 h) so a dead session cannot wedge the
  pool. `EMU_LEASE_TAKEOVER=1` exists in the lib for an operator at a terminal;
  **a session must never set it** — the hook refuses it (see below);
- **refuses a device that is not the pool AVD (#2862)** — the pool filters by
  console PORT, so a stray emulator on 5554 used to be leased and driven as if
  it were the ARCore-ready `Pixel_7a`. `EMU_REQUIRE_AVD` (set by every QA
  script to `EMU_POOL_AVD`) now checks `adb emu avd name` before leasing;
- **re-gates RAM before every boot** — free RAM is re-read immediately before
  each boot and the boot is refused below `EMU_MIN_FREE_RAM_MB` (default 3072 MB)
  even when the cap said there was room. Memory safety is the hard invariant —
  the pool never pushes the host into RAM exhaustion;
- **right-sizes `-memory`** — scales the guest memory flag to RAM headroom,
  clamped to `[EMU_MEMORY_FLOOR_MB, EMU_MEMORY_CEILING_MB]` (2048–4096 MB);
- **reclaims stale leases** — a lease whose owner pid is dead AND whose serial is
  gone from `adb devices` is reclaimed automatically.
⚠️ **The lease governs allocation; exclusion is enforced separately.** The lease
file itself is advisory — the `adb install` / `adb shell input tap` commands this
very file tells you to use read no lease file. That is not theoretical: on
2026-07-27 a sibling session's direct `adb install` killed a leased sweep's app
mid-run, and the discriminating evidence was one logcat line — `Killing
<pid>:<pkg> (adj 0): stop <pkg> due to installPackageLI` — without which the
symptom (`app died, no saved state`) reads as a native crash in the demo.

Since #2924 a **blocking `PreToolUse` hook** (`hook-dispatch.sh`, guard 2) closes
that path for mutating `adb`/`android` commands a session issues: it reads the
lease and exits 2 when the target carries a live one owned by someone else. It
leaves you exactly two routes, and takeover is not one of them: inherit the
lease if it is yours (`EMU_LEASE_SESSION=…`), or provision your own device
(`setup-ar-emulator.sh`). Since 2026-08-13 the guard **refuses**
`EMU_LEASE_TAKEOVER=1` rather than honouring it — it does not mirror
`lib/emulator-select.sh` on this point, deliberately, because the lib serves an
operator who can see the peer's run and a session cannot. Two limits are
structural, so keep them in mind rather
than assuming full protection: the hook only sees commands that go through a
Claude Code session (a plain terminal, a wrapper script, or a shell alias is
invisible to it), and it matches a fixed list of mutating verbs. Before driving
an emulator by any other route, check `setup-ar-emulator.sh --check` for an
active lease that is not yours.

Every threshold is env-overridable. `setup-ar-emulator.sh --check` reports pool
state (running count, computed cap, free RAM, active leases). The QA scripts
(`device-qa.sh`, `qa-android-demos.sh`, `ar-replay-qa.sh`) pin `ANDROID_SERIAL`
to the leased emulator so the right device is targeted when the pool has several.

`--check` now also reports host free RAM and whether a running emulator would be
reused. This is why parallel Claude Code sessions running device-QA on the same
RAM-constrained Mac no longer contend for emulator resources.

