<!-- category: Fixed -->
- Device-QA emulator pool (follow-up to #2862): the scripts that actually DRIVE
  a pool emulator now HOLD a lease for their whole run, closing the two-sessions
  -on-one-AVD gap **for the harness's own scripts**. `qa-android-demos.sh` and
  `ar-replay-qa.sh` used to pick a running emulator without acquiring it, so a
  second standalone run drove the same one; they now `emu_lease_acquire` it (or
  adopt this session's sticky reservation), refuse one a peer reserved, and
  release it on exit. `ar-replay-qa.sh` also refuses a pool-port emulator it
  cannot identify (wrong AVD, or a console that does not answer — most likely
  precisely when a peer is driving it) instead of falling through and driving it
  unleased. **The lease file governs allocation, not exclusion**: `CLAUDE.md`
  tells agents to drive the emulator with `adb install` / `input tap` directly,
  and no amount of leasing inside the scripts stops that — measured during this
  work, a sibling session's `adb install` killed a leased run's app mid-sweep
  (`Killing <pid>:<pkg> (adj 0): stop <pkg> due to installPackageLI`, which
  without that logcat line reads as a native crash). Raw `adb` is now blocked by
  a separate mechanism, the #2924 `PreToolUse` hook, for commands a session
  issues — not by this change. ([#2862](https://github.com/sceneview/sceneview/issues/2862))
- `device-qa.sh` now grades its **android** leg on the positive `[qa] PASS`
  marker in addition to the exit code, as the iOS leg already did. Holding the
  pool lease means `qa-android-demos.sh` installs an EXIT trap, and on macOS
  bash 3.2 (measured: 3.2.57) a script that aborts inside a `||`-guarded list
  with a trap installed exits **0** — which would have graded a crashed sweep
  as `passed`. Preserving `$?` inside the trap does not help: the `||` has
  already reset it. ([#2862](https://github.com/sceneview/sceneview/issues/2862))
- `setup-ar-emulator.sh` now publishes its session token to the handoff file
  only when it minted the token itself. A caller that already exported one
  (`device-qa.sh`) no longer has its reservation inherited — and the emulator
  stolen — by a concurrent session inside the handoff window, and the ad-hoc
  "next steps" hint leads with the token export that makes the reservation
  exclusive. ([#2862](https://github.com/sceneview/sceneview/issues/2862))
