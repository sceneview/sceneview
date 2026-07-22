<!-- category: Tests -->
- **CI now compiles the iOS device code path on every PR.** Every AR demo wraps its
  real ARKit/RealityKit logic in `#if !targetEnvironment(simulator)`, so the existing
  Simulator-destination build stripped that code before the compiler saw it — a type
  error inside an AR demo could pass CI green and land on `main`, with the first real
  compile happening only during an App Store archive. `ios.yml` now adds a build-only
  `generic/platform=iOS` step with `CODE_SIGNING_ALLOWED=NO`, which needs no
  certificates and so runs on forks too ([#2852](https://github.com/sceneview/sceneview/issues/2852)).
