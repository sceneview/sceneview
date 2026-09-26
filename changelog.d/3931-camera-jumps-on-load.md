<!-- category: Fixed -->
- **The camera no longer jumps when a model loads ([#3931](https://github.com/sceneview/sceneview/issues/3931)).**
  - **Camera swaps glide:** a `cameraManipulator` swapped at runtime now glides for 0.6 s from the pose on screen instead of cutting to its own pose.
  - **`rememberCameraManipulator(orbitRadius = …)` follows new values:** it is keyed on `orbitRadius` and `targetPosition`, so a radius derived from a measured model is applied instead of being ignored.
  - **No more huge time steps:** the first frame after an idle pause no longer hands the camera the device uptime, and a stalled frame no longer spends an ease in one step. A `smooth = true` transform issued after an idle period glides again instead of snapping.
  - **Auto-centre glides:** when a model is added next to one already on screen, the re-centre glides for 0.4 s instead of jumping.
  - **Model Viewer demo:** the entrance flight no longer skips or restarts, and the camera follows the chrome insets smoothly.
