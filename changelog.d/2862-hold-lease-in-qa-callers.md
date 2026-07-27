<!-- category: Fixed -->
- Device-QA emulator pool (follow-up to #2862): the scripts that actually DRIVE
  a pool emulator now HOLD a lease for their whole run, closing the last
  concurrent-input gap where two sessions could inject taps/swipes into the same
  running AVD. `qa-android-demos.sh` and `ar-replay-qa.sh` used to pick a running
  emulator without acquiring it, so a second standalone run drove the same one;
  they now `emu_lease_acquire` it (or adopt this session's sticky reservation),
  refuse one a peer reserved, and release it on exit. ([#2862](https://github.com/sceneview/sceneview/issues/2862))
- `setup-ar-emulator.sh` now publishes its session token to the handoff file
  only when it minted the token itself. A caller that already exported one
  (`device-qa.sh`) no longer has its reservation inherited — and the emulator
  stolen — by a concurrent session inside the handoff window, and the ad-hoc
  "next steps" hint leads with the token export that makes the reservation
  exclusive. ([#2862](https://github.com/sceneview/sceneview/issues/2862))
