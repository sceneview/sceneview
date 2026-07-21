<!-- category: Tests -->
- iOS: added a registry/deep-link guard suite (`DemoRegistryGuardTests`, 19
  tests) asserting `GeneratedScenes`/`DemoDeepLinkRegistry` invariants — id
  uniqueness across the three sources, kebab-case format, every legacy alias
  resolving to a live scene id, and the central check a human used to verify
  by hand: ids that should show a real demo do, everything else honestly
  falls through to the placeholder. Also registered the orphaned
  `SketchfabAssetResolver+Tests.swift` (17 tests, dead since May — never
  compiled) in the `SceneViewDemoTests` target. iOS test count: 20 -> 56
  (#2801, part of #2798).
- Added `parity-manifest.yml` (repo root) — one row per Android canonical
  demo id (52) declaring its current iOS status (working / stub /
  android-only) with a reason for every non-working entry — plus
  `.claude/scripts/check-demo-id-parity.sh`, wired into `ci.yml` ->
  `repo-hygiene` (ubuntu, blocking, zero macOS cost). Fails the moment a new
  Android demo ships without a matching iOS registry entry or manifest row —
  the silent-drift class behind #2769 (#2801, part of #2798).
