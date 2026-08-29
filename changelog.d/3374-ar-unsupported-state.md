<!-- category: Fixed -->
- **AR no longer hangs on "Initializing AR" on devices without ARCore ([#3374](https://github.com/sceneview/sceneview/issues/3374)).** `ARCore` compared
  `ArCoreApk.checkAvailability()` only against `SUPPORTED_INSTALLED`, then asked for an
  install on every other verdict — including `UNSUPPORTED_DEVICE_NOT_CAPABLE`, where
  `requestInstall` throws. The exception was swallowed into `onArSessionFailed`, a
  callback no demo wires, so the session never started and the app sat on its own
  "initializing" copy forever. Availability is now a first-class state: the new
  `ARCoreAvailability` enum (`Unsupported`, `NotInstalled`, `NeedsUpdate`, `CheckFailed`)
  is published through `ARCore.onARCoreAvailability`, and `ARSceneView` draws a built-in
  explanation card — overridable via `arCoreAvailabilityOverlay`, or observable via
  `onARCoreAvailability` — with an Install / Update / Try again action, and none at all on
  a device that simply cannot run AR. `retryARCoreAvailability()` un-latches a cancelled
  Play Store flow so the action works twice. Behaviour on a working ARCore device is
  unchanged: `SUPPORTED_INSTALLED` starts the session immediately and `UNKNOWN_CHECKING`
  still waits silently.
