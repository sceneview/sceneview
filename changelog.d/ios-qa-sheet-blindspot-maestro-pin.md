<!-- category: Tests -->
- **device-qa (iOS leg)**: root-caused the `3d-basics` flow failure on
  `demo-settings.yaml` — not an app regression: Maestro 2.6.1 on the iOS 26.3
  runtime does not traverse a presented SwiftUI sheet's content at all (with
  the sheet visibly open and screenshot-verified, the accessibility hierarchy
  contains only the gear FAB and the status bar). The sheet-content
  `ASSERT_TEXT` assertion is now `optional: true` (advisory) with the real
  crash gates unchanged (FAB re-assert + the simulator-log sweep); documented
  in `.maestro/README.md` known limitations.
- **device-qa (Maestro pin)**: bumped the pinned Maestro from 1.39.0 to 2.6.1
  in `lib/maestro.sh` and — new — actually pinned the CI install in
  `device-qa.yml`, which had been silently floating on latest all along (so
  android CI was already green on 2.6.x while local runs pinned 1.39). No
  `.maestro/` flow uses `runScript`/`evalScript`, so the 2.x Rhino→GraalJS
  removal is a non-event; the iOS catalog was re-validated on 2.6.1 locally.
