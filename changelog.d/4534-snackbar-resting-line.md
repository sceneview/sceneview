<!-- category: Fixed -->
- **Demo app: snackbars wait for AR cards ([#4534](https://github.com/sceneview/sceneview/issues/4534)).** Messages wait until SDK coaching, camera permission, availability and landscape corner cards leave, including at large font sizes. Overlapping card declarations retain their own resting edges. Dragging Settings restores the resting line as soon as the sheet moves.

<!-- category: Added -->
- **Android AR: `LocalAROverlayVisibility` ([#4534](https://github.com/sceneview/sceneview/issues/4534)).** New `@ExperimentalSceneViewApi` Compose local in `arsceneview`: provide a listener and every centred SDK card (coaching, camera permission, ARCore availability, camera in use) reports `true` while shown and `false` once it leaves, so hosts can hold snackbars until the scene is clear.
