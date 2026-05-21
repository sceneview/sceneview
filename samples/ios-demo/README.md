# SceneView

SwiftUI app for iOS and macOS — browse 3D models, view them in augmented reality, save favorites, and share screenshots.

## Features

- 4-tab SwiftUI interface (Explore, AR View, Scenes, About)
- Model gallery with categories: Vehicles, Creatures, Objects, Scenes
- Favorites system (mark and filter favorite models)
- AR placement: choose a model, tap a surface, place it in your space
- Screenshot sharing from both 3D viewer and AR mode
- Environment presets for different lighting setups
- 14 interactive 3D scene presets

## Run

Open `SceneViewDemo.xcodeproj` in Xcode and run on a device or simulator.

## Deep linking — launch any demo directly

The app is wired for parity with `samples/android-demo`'s `--es demo` intent
extra. Two URL routes work, both gated by the same allow-list as the SwiftUI
TabView so an unknown id falls through to the launch screen rather than
crashing — see `DeepLinkRouter.swift` + `DemoDeepLinkRegistry.swift`.

### Custom scheme (works offline + on every simulator)

The `sceneview://` scheme is registered in `Info.plist > CFBundleURLTypes`.
From the macOS host shell:

```bash
# Open the AR Rerun debug demo on the booted simulator
xcrun simctl openurl booted sceneview://demo/ar-rerun

# Same for any demo id — list of legal ids in
#   samples/ios-demo/SceneViewDemo/DemoDeepLinkRegistry.swift
xcrun simctl openurl booted sceneview://demo/model-viewer
```

This is what the `.claude/scripts/ios-device-qa.sh` capture flow (parallel to
the Android one) uses to script through every demo for screenshot automation.

### Universal Link (works on device once App-Links verification ships)

`https://sceneview.github.io/open?demo=<id>` resolves to the same registry
once the App-Links / `apple-app-site-association` file is signed and
deployed. From mobile Safari today the URL opens the SwiftUI app router
in-browser (`sceneview.github.io/open/index.html` redirects to the App Store
listing if the app isn't installed); from the simulator:

```bash
xcrun simctl openurl booted "https://sceneview.github.io/open?demo=ar-rerun"
```

## Requirements

- Xcode 16+
- iOS 18+ device or simulator
- For AR features: physical iOS device with ARKit support
