<!-- category: Added -->
- **iOS demo: Secondary Camera (PiP) ([#4250](https://github.com/sceneview/sceneview/pull/4250)).** The `secondary-camera` demo now opens on iOS. It shows the helmet on a gridded floor in an orbitable main view, and a picture-in-picture inset with Top / Side / Front / Corner / Orbit angles. A tap in either view moves or turns the helmet, and both views show the change. The deep link `sceneview://demo/secondary-camera` now opens this screen instead of the placeholder.

<!-- category: Fixed -->
- **iOS Simulator: a `SceneView` laid over another one showed stale frames ([#4250](https://github.com/sceneview/sceneview/pull/4250)).** On the iOS 26 Simulator the overlaid view's Metal layer presents with Core Animation transactions, so it showed the previous edit or camera until the next UI change. `RenderSurfaceResizer` now turns that off on the Simulator; devices are unaffected.
