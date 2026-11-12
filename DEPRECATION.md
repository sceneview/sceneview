# SceneView for SwiftUI, Flutter and React Native is deprecated

SceneView focuses on Android: Jetpack Compose 3D and ARCore AR. The web package stays, as the way to try
SceneView in a browser.

## What changes

| Package | Last release | From now on |
|---|---|---|
| `SceneViewSwift` (Swift Package, iOS · macOS · visionOS) | 4.68.0 | No new releases |
| `flutter_sceneview` (pub.dev) | 4.68.0 | No new releases; marked discontinued on pub.dev |
| `@sceneview-sdk/react-native` (npm) | 4.68.0 | No new releases; marked deprecated on npm |
| SceneView Demo for iOS and macOS (App Store) | 4.68.0 | No new builds |

Unchanged: `io.github.sceneview:sceneview`, `arsceneview`, `sceneview-core` and `sceneview-compose` on Maven
Central, `sceneview-web` on npm, and the `sceneview-mcp` server.

## What keeps working

- Every published version stays installable: Swift Package tags, pub.dev and npm versions are not removed.
- The source stays in this repository under `SceneViewSwift/`, `flutter/` and `react-native/`, under the same
  Apache 2.0 licence. You can fork it or vendor it into your app.
- Issues and pull requests for these packages are no longer triaged.

## Timeline

- **4.68.0 (November 2026)** is the last release of the three packages.
- **December 2026**: the npm package is marked deprecated and the pub.dev package discontinued.
- **May 2027**: the Swift, Flutter and React Native sources move to a separate, archived repository.

## If you use one of these packages

- **SwiftUI**: pin `SceneViewSwift` to `4.68.0`, or copy `SceneViewSwift/` into your project as a local Swift
  package. RealityKit and ARKit cover the same ground natively.
- **Flutter / React Native on Android**: host the Android SceneView in a platform view.
- **Android**: nothing changes. Start with the [quickstart](https://sceneview.github.io/docs/quickstart/).
