<!-- category: Changed -->
- **The Flutter and React Native iOS bridges now render through the shared
  `SceneViewerHostView`.** Both carried their own copy of "host a SwiftUI `SceneView`
  inside a UIKit view, then load models into it imperatively" — two independent
  `UIHostingController` wrappers, two content roots, two model reconcilers, drifting
  apart. The 3D path of each now builds a `SceneViewerConfiguration` and hands it to the
  same host that `sceneview-compose` uses; each bridge keeps only what is genuinely its
  own, its method channel or its prop bag. Their **AR** paths are untouched: `ARSceneView`
  is anchor-driven and shares nothing with the 3D viewer. Every method-channel name, every
  prop name and every callback payload is unchanged — including the two bridges'
  *different* definitions of a tapped node, which is why the host grew `onTapEntity`
  rather than one flattened convention (see below).
- **`SceneViewerConfiguration` gained the four things a bridge cannot do without.**
  `models` (a list — Flutter appends one at a time, React Native replaces the lot;
  a per-entry `identity` is what keeps two copies of one path as two models),
  `cameraControlMode` and `autoCenterContent` (both bridges expose them publicly),
  and `cameraPoseAuthored` (neither bridge has a camera at all — without it every method
  call would re-assert the default pose and snap the camera out of its framing and away
  from wherever the user had orbited to). `cameraPoseAuthored: false` detaches the pose
  rather than merely stopping it from being updated: `SceneView` applies the *first*
  non-nil request it sees, so handing it a default pose still frames the scene, at
  elevation 15° where `CameraControls`' own default is 30°. Auto-centering re-fits
  distance and target and hides all of that except the angle — a camera-less bridge would
  have come out of this migration looking down on the model from somewhere else. Caught
  by the agent review on this PR. Every pre-existing member keeps its name, type
  and default, so `sceneview-compose` is unaffected: a configuration with no `models` is
  resolved into a one-element list built from the single-model fields, through the same
  reconciliation path.
- **`SceneViewerHostView.onTapEntity`**, a Swift-only companion to the `@objc` `onTap`
  that hands over the `SceneTapHit` rather than five primitives. The two bridges publish
  genuinely different things about a tapped entity — Flutter walks up to the first named
  ancestor and strips the extension, React Native reports the raw name and the entity's
  own origin where `onTap` reports the bounds centre — and all of it is already-shipped
  public API. Handing over the entity lets each keep its own contract instead of
  flattening three published payloads into one.

<!-- category: Fixed -->
- **`bytesFileExtension` is validated before it reaches the filesystem.** The value is
  public `@objc` on `SceneViewerConfiguration` and on the new `SceneViewerModel`, and it
  was appended to a temp file name unvalidated. Anything that is not a short ASCII
  alphanumeric run is now refused back to `usdz` rather than sanitised — a caller that
  sent something else asked for something this API does not offer. No shipped bridge is
  affected: Flutter and React Native only ever send an asset path.
- **`setEnvironment` on the Flutter plugin and `environment` on the React Native
  component were silently inert on iOS.** Both stored the HDR path in their scene state
  and no view ever read it, so the call succeeded and nothing changed. Routed through the
  shared host, both now apply the environment. The surface is unchanged; what changed is
  that it does something. React Native's `cameraOrbit` prop stays deliberately inert —
  `cameraControlMode` supersedes it and wiring both would make them contradict each other
  — and is now documented as deprecated rather than left looking functional.
- **`samples/flutter-demo` could not run `pod install` at all.** Its Xcode project
  targeted iOS 13 while the plugin's podspec requires 17, so CocoaPods refused before
  reaching any Swift. Bumped to 17. Note this unblocks `pod install` only: the demo still
  cannot complete an iOS build, because the plugin's Swift is compiled inside the Pods
  project, which does not see the `SceneViewSwift` Swift package — the structural gap
  `bridge-ios-compile.yml` already documents and works around with a type-check.
