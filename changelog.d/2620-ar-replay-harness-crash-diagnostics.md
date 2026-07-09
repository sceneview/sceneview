<!-- category: Tests -->
- AR device-QA leg (`ar-replay-qa.sh` + `ARReplayHarnessTest`): the harness now writes its
  machine-readable summary **incrementally** with an `inProgress` marker, and the script
  streams `logcat` + tees the instrumentation output into the artifact bundle. When the
  instrumentation host process dies mid-sweep — a demo crashing the shared `MainActivity`
  process, or an lmkd OOM-kill, as happened silently on the CI x86_64/swiftshader emulator
  for over a week — the leg now leaves an honest partial verdict that **names the crashing
  demo** and captures the crash signature, instead of a bare `rc=1` with no summary at all
  ([#2620](https://github.com/sceneview/sceneview/issues/2620)).
