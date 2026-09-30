<!-- category: Fixed -->
- **iOS demo: a real Lighting Lab ([#4203](https://github.com/sceneview/sceneview/pull/4203)).** The iOS catalog sent Lighting Lab to Dynamic Sky, and its reflection-probe screen used the same light inside and outside the probe, so nothing changed. Lighting Lab now has Android's stage: the helmet between a chrome ball and a matte ball under a warm spotlight. You can set the environment intensity and rotation, show or hide the sky, and turn on a sunset reflection that applies while the camera is inside its area. Exposure, contact shading, fog and edge smoothing stay Android-only, and the screen says so.

<!-- category: Added -->
- **SceneViewSwift: `SceneEnvironment.rotation` ([#4203](https://github.com/sceneview/sceneview/pull/4203)).** Turns the image-based lighting and its reflections about the vertical axis, live, like Android's environment rotation. RealityKit does not rotate the painted skybox.
