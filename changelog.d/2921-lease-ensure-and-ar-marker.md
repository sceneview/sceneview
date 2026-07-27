<!-- category: Fixed -->
- Device-QA emulator pool: the hold-the-lease guard now also runs on the path
  `setup-ar-emulator.sh` itself documents. Both drivers treated a pre-set
  `ANDROID_SERIAL` as proof that the caller held the lease, and the script's own
  printed next-steps tell you to `export ANDROID_SERIAL=…` — so in the exact
  workflow it advertises, the guard never ran. `qa-android-demos.sh` and
  `ar-replay-qa.sh` now verify it instead, via a new `emu_lease_ensure`.
  ([#2921](https://github.com/sceneview/sceneview/issues/2921))
- The obvious fix here — "just call `emu_lease_acquire`" — is a regression, and
  the hermetic self-test now pins that. Measured with `device-qa.sh` as parent:
  when both share a session token, an acquire in the child **adopts** the
  parent's lease and rewrites the owner to the child's pid, so the child's EXIT
  trap deletes a lease the parent is still relying on and the emulator goes back
  to looking free to every peer — the collision the lease exists to prevent,
  reintroduced on the nominal path. Without a shared token it is worse: the
  child refuses to run at all. `emu_lease_ensure` therefore verifies *without*
  taking ownership — a strict no-op when the lease is already ours, a real
  acquire when the emulator is unleased, and a refusal when a live peer holds
  it. ([#2921](https://github.com/sceneview/sceneview/issues/2921))
- The **ar** leg is no longer graded on its exit code alone. It shared the
  bash 3.2 false-green the android and iOS legs already defend against (an abort
  inside a `||`-guarded list under an EXIT trap exits 0), but unlike them it had
  no positive marker to require — it graded an *absence*. `device-qa.sh` now
  requires one, and keeps the two green paths distinguishable: a real
  `[ar-replay-qa] PASS` versus the new `GREEN-NO-OP` a sparse checkout emits when
  there was nothing to replay, which is reported as such instead of implying
  demos ran. An exit 0 with neither marker is now a failure, not a pass.
  ([#2921](https://github.com/sceneview/sceneview/issues/2921))
- `qa-android-demos.sh` / `ar-replay-qa.sh` stop sending `emu_lease_release_all`
  to `/dev/null` — that discarded the only evidence a release did the right
  thing, and `|| true` alone already makes the trap safe. A mis-release is now
  diagnosable from the artifact bundle.
  ([#2921](https://github.com/sceneview/sceneview/issues/2921))
- Still open, stated plainly: `device-qa.sh` acquires its own emulator on a
  best-effort basis (`emu_lease_acquire … || true`), so the orchestrator itself
  can still proceed unleased. Raw `adb` typed by a session is blocked separately
  by the #2924 `PreToolUse` hook, which sees only commands that pass through a
  Claude Code session — a plain terminal or a wrapper script is invisible to it.
- Known, unchanged: the handoff token is inheritable at most once, so a chain of
  `setup-ar-emulator.sh` → `qa-android-demos.sh` → `ar-replay-qa.sh` in a shell
  that never exported `EMU_LEASE_SESSION` ends at the third step. Export the
  token the provisioning script prints — the scripts say so on the refusal path.
  ([#2862](https://github.com/sceneview/sceneview/issues/2862))
