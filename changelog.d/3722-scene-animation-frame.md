<!-- category: Added -->
- **Android animation frame driver ([#3722](https://github.com/sceneview/sceneview/issues/3722)).** `SceneView(onAnimationFrame = …)` wakes and keeps on-demand rendering active until the callback is cleared. It runs before scene updates; `onFrame` remains an observer of presented frames and lets idle scenes park. Existing binary and positional calls are preserved.
