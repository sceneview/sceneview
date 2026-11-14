<!-- category: Fixed -->
- **Keep SceneView drags inside a scrolling or draggable Compose parent ([#3898](https://github.com/sceneview/sceneview/issues/3898)).** Opt in with `disallowParentIntercept = true` to let either Android surface claim its touch stream; the default preserves parent scrolling. Document that editable nodes absorb gestures instead of moving the camera.
