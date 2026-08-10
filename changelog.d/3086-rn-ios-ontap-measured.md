<!-- category: Fixed -->

- React Native's 3D `SceneView.onTap` **does** fire on iOS. It was measured on
  an iPhone 17 Pro Max simulator with a model rendering: 5 taps on the model,
  5 dispatches, `nodeName` naming the model every time. Every surface that
  called it unverified or probably broken now states the measured result —
  `llms.txt` and its generated `gpt/knowledge-*` mirror, the module's `README`
  and `onTap` JSDoc (source and published `.d.ts`), the React Native quickstart,
  the MCP platform-setup snippet, and the demo's own coverage card.
- The same run measured the Flutter bridge back to back, against the same
  SceneViewSwift build, the same simulator and the same entity graph (11
  entities, 1 collision shape, 9 input targets): 6 taps on the model resolved
  no entity, while the untargeted gesture arrived every time. So
  [#3045] is Flutter's platform-view touch delivery, not RealityKit's
  entity-targeted hit test as its write-up states — React Native, which reaches
  the same hook through a plain native view, is unaffected.
- The React Native demo now builds and runs on iOS for the first time. Its
  `podspec` declares `SceneViewSwift` (a pod cannot see the host app's SwiftPM
  packages), the demo `Podfile` resolves it from the repo root and re-pins
  `IPHONEOS_DEPLOYMENT_TARGET` to 18.0 after `react_native_post_install` lowers
  it to React Native's 13.4 floor, and three `@react-native/*` dev dependencies
  Metro needs were missing. A `khronos_fox.usdz` is bundled so the Animation tab
  renders on iOS at all: the demo passed remote `.glb` URLs on both platforms,
  which RealityKit cannot read — the same failure, and the same fix, as the
  Flutter demo's viewer page in [#3048].

[#3045]: https://github.com/sceneview/sceneview/issues/3045
[#3048]: https://github.com/sceneview/sceneview/pull/3048
