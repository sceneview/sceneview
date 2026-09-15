<!-- category: Fixed -->
- **`model-viewer` demo: Recenter jumped the camera to a cold-open swing instead of flying
  from the current pose ([#3622](https://github.com/sceneview/sceneview/issues/3622)).**
  Tapping Recenter rebuilt `EntranceCameraManipulator`, which discarded the pose the user had
  orbited to and replayed the wide, swung-off-axis start reserved for the very first arrival.
  Recenter now captures the pose actually on screen and flies from there to the resting framing.
