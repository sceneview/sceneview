<!-- category: Changed -->
- **iOS CI now builds SceneViewSwift with Xcode 27 as a non-blocking canary ([#3856](https://github.com/sceneview/sceneview/pull/3856)).** A new `ios.yml` job runs the package's iOS build, visionOS build and unit tests on GitHub's `xcode-27` preview image, so Xcode 27 and iOS 27 SDK breakage shows up before any release build moves. Release builds still use Xcode 26.
