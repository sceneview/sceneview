<!-- category: Fixed -->
<!-- breaking: false -->
The Flutter iOS `SceneView.onTap` now actually fires. Two separate bugs, not one: the
`FlutterPlatformView` hosting the 3D and AR viewers registered with Flutter's default
`.waitUntilTouchesEnded` gesture-blocking policy, which — per Flutter's own header doc —
lets a platform view's `UIGestureRecognizer`s see the whole touch sequence but never
complete recognition, so no tap fired at all; both platform-view factories now register
`.eager` instead. Even with the touch reaching SwiftUI, RealityKit's
`targetedToAnyEntity()` entity hit test still resolved nothing from inside a Flutter
platform view — measured directly, side by side with a plain untargeted tap that fired
correctly at the same location — so `SceneViewSwift`'s tap gesture on iOS/macOS now
resolves the tapped entity with a manual screen-to-world raycast (`Scene.raycast`)
against the camera it already tracks, instead of depending on that hit test. Verified on
an iPhone 17 Pro Max simulator: tapping the Flutter demo's Fox model now shows "Tapped:
khronos_fox"; it did not before either fix.
