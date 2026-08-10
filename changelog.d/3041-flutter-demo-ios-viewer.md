<!-- category: Fixed -->
<!-- breaking: false -->
<!-- Explicit, so the release level does not depend on a sibling fragment. The
     iOS floor moves 17.0 -> 18.0 on paper only: the podspec DECLARED 17.0 while
     SceneViewSwift/Package.swift has always required 18.0, so no host app ever
     had a working iOS 17 build to lose. Consumer-visible, not breaking. -->
- **Flutter demo: the iOS 3D viewer now builds and renders.** `samples/flutter-demo`
  could not be built for iOS at all — it shipped no `Podfile`, so `flutter build ios`
  generated one targeting iOS 13, below what the plugin required. The demo now commits
  a `Podfile`, targets iOS 18, and consumes `SceneViewSwift` as a pod: a Swift package
  added to the host app's Xcode project is invisible to the bridge, which compiles
  inside CocoaPods' own project (`Unable to find module dependency: 'SceneViewSwift'`).
  Adds a root-level `SceneViewSwift.podspec` for that path, and corrects the plugin
  README, which documented the route that does not work.
- **`flutter_sceneview`'s declared iOS minimum moves 17.0 → 18.0.** Consumer-visible.
  The podspec claimed 17.0 while `SceneViewSwift/Package.swift` has always required
  18.0, so a host app that believed it got RealityKit availability errors at link time
  instead of a clear version error. The podspec now also depends on `SceneViewSwift`
  (pinned `~> 4.27`), which is **not** on the CocoaPods trunk: host apps must add a
  `pod 'SceneViewSwift', :podspec => '<raw URL of SceneViewSwift.podspec>'` line,
  documented in the plugin README. Not the `:git => …, :tag =>` form: CocoaPods reads
  the podspec from the root of the checked-out tag, and the root podspec is not in
  any tag yet — verified with `git cat-file -e vX.Y.Z:SceneViewSwift.podspec` on
  v4.26.0 and v4.27.0, the two most recent — so every tag that exists today resolves
  to "Unable to find a specification". The `:podspec =>` URL reads the spec from `main` while the sources
  still come from the tag the spec names.
- **React Native stays on the SwiftPM route for now**, deliberately. The same pod
  treatment would need `samples/react-native-demo`'s `Podfile` changed in the same
  breath or `rn-ios-compile.yml`'s real `pod install` turns red, which is a larger
  change than this one. Tracked in
  [#3072](https://github.com/sceneview/sceneview/issues/3072); the RN podspec and
  README now say so where they claim no CocoaPods spec exists.
- **Flutter demo: iOS loads a model instead of an empty viewport.** The viewer passed
  a remote `.glb` URL on every platform, but RealityKit reads only `.usdz`/`.reality`
  and `ModelNode.load(_:)` resolves a bundle resource, not a URL — so every iOS load
  threw into a swallowed `NSLog`. Sample models now carry a per-platform source; a
  bundled `khronos_fox.usdz` renders on iOS, and entries with no USDZ are shown
  disabled with the reason rather than looking loadable.
- **Flutter bridge: iOS accepts remote model URLs.** An `https://` path now becomes a
  download rather than a lookup for a bundle resource named `"https:…"`, closing a
  divergence with Android, where Filament's `ModelLoader` takes either. The 3D path
  routes it by setting `SceneViewerModel.urlString` instead of `assetPath` — the shared
  host reads exactly one of the two and checks `assetPath` first — and the AR path,
  which has no shared host, routes it through `ModelNode.load(from:)`. AR also names
  an unsupported format with an actionable reason rather than relaying RealityKit's
  generic error, which is indistinguishable from "file not found".
- **Flutter bridge: platform views claim tap gestures.** `SceneView`/`ARSceneView`
  declared only pan and scale recognizers, so Flutter kept every tap and the native
  hit test never ran. (`onTap` still does not fire on iOS for a separate,
  documented reason — see the plugin README.)
- **`sync-assets.sh` addressed a directory that does not exist.** Its Flutter paths
  pointed at `samples/flutter-demo/example/…`, so the demo never received the assets
  its catalog entries already claimed it used. The Flutter legs now refresh the assets
  the demo actually bundles rather than pushing the whole shared library at it.
- **Flutter demo About tab showed `v4.13.0`** while the SDK had moved on — and the
  integration test asserted that exact string, so it defended the drift instead of
  catching it.
