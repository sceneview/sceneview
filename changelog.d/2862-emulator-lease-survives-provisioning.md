<!-- category: Fixed -->
- Device-QA emulator pool: a provisioned emulator no longer looks free to every
  other session. `setup-ar-emulator.sh` leased by pid and dropped the lease in
  its EXIT trap, but the emulator it provisions deliberately outlives the
  script — so the next `device-qa.sh` / `qa-android-demos.sh` run was handed an
  AVD another session was actively driving. Leases are now reserved per
  **session** and survive the provisioning script (`--release` hands one back,
  with a bounded TTL so a dead session can never wedge the pool). ([#2862](https://github.com/sceneview/sceneview/issues/2862))
- The pool also refuses to lease an emulator that is not the pool AVD: a stray
  device sitting on a pool port used to be leased and driven as if it were the
  ARCore-ready `Pixel_7a`, producing a QA verdict about a device nobody meant
  to test. ([#2862](https://github.com/sceneview/sceneview/issues/2862))
