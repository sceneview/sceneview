<!-- category: Added -->
- **Compose Multiplatform support** — new `sceneview-compose` module exposing a single
  `SceneViewer` composable from `commonMain`, answering
  [#558](https://github.com/sceneview/sceneview/issues/558) and
  [#486](https://github.com/sceneview/sceneview/issues/486). Scope is the *viewer
  subset* (model, orbit camera, key light, environment, tap hit-testing); AR, custom
  materials and post-processing stay platform-native by design. Android is implemented
  and delegates to the existing Filament `SceneView { }`; iOS (RealityKit) and Desktop
  render an explicit placeholder until their renderers are wired. Purely additive — no
  existing published surface changes. See
  [docs/docs/compose-multiplatform.md](https://github.com/sceneview/sceneview/blob/main/docs/docs/compose-multiplatform.md).
