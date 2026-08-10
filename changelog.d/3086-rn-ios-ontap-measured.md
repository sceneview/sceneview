<!-- category: Fixed -->

- React Native's 3D `SceneView.onTap` **does** fire on iOS. It was measured on
  an iPhone 17 Pro Max simulator with a model rendering: 5 taps on the model,
  5 dispatches, `nodeName` naming the model every time. Every surface that
  called it unverified or probably broken now states the measured result —
  `llms.txt`, its `gpt/knowledge-*` mirror (regenerated with
  `node tools/generate-gpt-knowledge.js`, never hand-edited) and the unpublished
  `.well-known/` copy, the module's `README` and `onTap` JSDoc (source and
  published `.d.ts`), the React Native quickstart, the MCP platform-setup
  snippet, and the demo's own coverage card.
- The same run measured the Flutter bridge back to back, against the same
  SceneViewSwift build, the same simulator and the same entity graph (11
  entities, 1 collision shape, 9 input targets): 6 taps on the model resolved
  no entity, while the untargeted gesture arrived every time. So
  [#3045] is Flutter's platform-view touch delivery, not RealityKit's
  entity-targeted hit test as its write-up states — React Native, which reaches
  the same hook through a plain native view, is unaffected. Every Flutter-side
  surface that stated the old root cause as fact now states the measured one:
  the plugin README and its `onTap` KDoc, the demo README and About tab,
  `llms.txt` with both mirrors, and the MCP Flutter snippet. The *guidance* on
  those surfaces is unchanged — Flutter's iOS `onTap` still does not fire.
- The React Native demo now builds and runs on iOS for the first time. Its
  `podspec` declares `SceneViewSwift` (a pod cannot see the host app's SwiftPM
  packages), the demo `Podfile` resolves it from the repo root and re-pins
  `IPHONEOS_DEPLOYMENT_TARGET` to 18.0 after `react_native_post_install` lowers
  it to React Native's 13.4 floor. The module's `README` iOS section documented
  the Swift Package Manager route as the supported one — it never worked, since
  the module compiles inside `Pods.xcodeproj`, which cannot see the host
  project's packages — and now gives the `Podfile` coordinate the unpublished
  `SceneViewSwift` pod needs — pointed at `main` rather than a tag, because no
  released tag carries `SceneViewSwift.podspec` yet and a tagged raw URL 404s. That closes
  the React Native half of [#3072]. Three `@react-native/*` dev dependencies
  Metro needs were missing. A `khronos_fox.usdz` is bundled so the Animation tab
  renders on iOS at all: the demo passed remote `.glb` URLs on both platforms,
  which RealityKit cannot read — the same failure, and the same fix, as the
  Flutter demo's viewer page in [#3048].

[#3045]: https://github.com/sceneview/sceneview/issues/3045
[#3048]: https://github.com/sceneview/sceneview/pull/3048
[#3072]: https://github.com/sceneview/sceneview/issues/3072
