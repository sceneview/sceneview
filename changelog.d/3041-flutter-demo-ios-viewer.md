<!-- category: Fixed -->
- **Flutter demo: the iOS 3D viewer now builds and renders.** `samples/flutter-demo`
  could not be built for iOS at all — it shipped no `Podfile`, so `flutter build ios`
  generated one targeting iOS 13 and `pod install` failed against the bridge's iOS 18
  floor. The demo now commits a `Podfile`, targets iOS 18, and consumes
  `SceneViewSwift` as a pod: a Swift package added to the host app's Xcode project is
  invisible to the bridge, which compiles inside CocoaPods' own project
  (`Unable to find module dependency: 'SceneViewSwift'`). Adds
  `SceneViewSwift.podspec` for that path, and corrects the plugin README, which
  documented the route that does not work.
- **Flutter demo: iOS loads a model instead of an empty viewport.** The viewer passed
  a remote `.glb` URL on every platform, but RealityKit reads only `.usdz`/`.reality`
  and `ModelNode.load(_:)` resolves a bundle resource, not a URL — so every iOS load
  threw into a swallowed `NSLog`. Sample models now carry a per-platform source; a
  bundled `khronos_fox.usdz` renders on iOS, and entries with no USDZ are shown
  disabled with the reason rather than looking loadable.
- **Flutter bridge: iOS accepts remote model URLs.** `https://` paths are routed
  through `ModelNode.load(from:)` (which downloads) instead of being treated as bundle
  resource names, closing a divergence with the Android bridge. An unsupported format
  is now reported with an actionable reason instead of RealityKit's generic error.
- **Flutter bridge: platform views claim tap gestures.** `SceneView`/`ARSceneView`
  declared only pan and scale recognizers, so Flutter kept every tap and the native
  hit test never ran. (`onTap` still does not fire on iOS for a separate,
  documented reason — see the plugin README.)
- **SceneViewSwift: loaded models are valid gesture targets.** `InputTargetComponent`
  was absent from the package, and `SpatialTapGesture().targetedToAnyEntity()` skips
  any entity without one — a necessary condition for every entity tap on Apple
  platforms, missing since the gesture was introduced.
- **`sync-assets.sh` addressed a directory that does not exist.** All three Flutter
  legs pointed at `samples/flutter-demo/example/…`, so the demo never received the
  assets its catalog entries already claimed it used. The Flutter legs now refresh the
  assets the demo actually bundles rather than pushing the whole shared library at it.
- **Flutter demo About tab showed `v4.13.0`** while the SDK was at 4.26.0 — and the
  integration test asserted that exact string, so it defended the drift instead of
  catching it.
