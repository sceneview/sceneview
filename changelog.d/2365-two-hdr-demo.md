<!-- category: Changed -->
- Flutter & React Native demos: the Environment demo now toggles between **two
  distinct HDRs** (Studio ↔ Night) at runtime instead of reloading a single HDR
  (Flutter) or switching HDR↔none (RN). This honestly demonstrates IBL/skybox
  switching and exercises the keyed-`rememberEnvironment` swap path that proves
  the #2361 fix (the skybox actually rebuilds on a new HDR). The second HDR
  reuses the existing in-repo `rooftop_night_2k.hdr` asset. (#2365)
- Flutter & React Native Android bridges: tidied the mutually-exclusive
  `rememberEnvironment` call sites into a single stable call site (one keyed
  `rememberEnvironment` whose factory falls back to the default environment when
  the HDR path is null), instead of a keyed call plus a separate
  `environment ?: rememberEnvironment(...)` fallback at the `SceneView` argument.
  Behavior-preserving; version-independent (still uses Compose `key {}`, not the
  unreleased `key=` param). (#2365)
