<!-- category: Fixed -->
- iOS demo: every one of Android's 53 canonical demo ids now resolves to
  something honest — a real screen, an alias to an existing equivalent
  screen, or a clearly-labeled coming-soon/Android-only card — never a
  silent no-op. Closes the real 12-id scope of #2769 (not just the 6 in its
  title): the 6 ids Android consolidated via the #2239 catalog regroup
  (`custom-geometry`, `camera-gestures`, `picking-collision`,
  `animation-physics`, `lighting-lab`, `two-d-in-three-d`) now route via a
  `DemoDeepLinkRegistry.legacyAliases` entry straight to the single
  most-representative pre-regroup granular scene — real, already-shipped
  content, not a new coming-soon card — chosen from Android's own default
  segmented-button tab for each umbrella (`DemoSettings.initialDemoMode`).
  7 ids with no iOS equivalent at all (`ar-plane-renderer-v2`,
  `contact-shadow-preview`, `placement-reticle-preview`, `point-and-ask`,
  `splat-preview`, `video-recording`, `wall-placement`) and the 11 ids
  previously hand-listed in `DemoDeepLinkRegistry.residualIds` with no
  backing Scene (`ar-collaborative`, `ar-depth-collider`,
  `ar-depth-of-field`, `ar-depth-visualization`, `ar-fog`,
  `ar-hand-tracking`, `ar-ml-object-label`, `ar-raw-depth-point-cloud`,
  `ar-scene-semantics`, `ar-xr-face`, `placement-scene`) each get a
  dedicated stub `*Scene.swift` with an honest `comingSoonTitle` —
  `residualIds` is now `[]`. The 3 permanently platform-locked ids
  (`ar-rooftop`, `ar-streetscape`, `ar-image-stabilization` — ARCore
  Geospatial/VPS and EIS, no ARKit equivalent) get a new optional
  `@androidOnlyReason` Scene directive so their card reads "Android-only:
  <reason>" instead of "Coming soon", which would dishonestly imply a
  future port (`ComingSoonScreen` + `DemoItem` gain the matching optional
  field, `nil` by default — zero behavior change for every other demo).
  `parity-manifest.yml` moves from 22 working / 18 stub / 13 android-only to
  28 / 25 / 0 — `check-demo-id-parity.sh` (#2801) is green. Part of the iOS
  catalog-parity effort (#2798, L0.6).
