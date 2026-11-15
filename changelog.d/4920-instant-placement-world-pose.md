<!-- category: Fixed -->
- **Stable instant placement ([#4920](https://github.com/sceneview/sceneview/issues/4920)).** `AnchorNode` content placed by `PlacementScene` holds its drop pose in world space while ARCore estimates depth, then blends to the refined anchor pose over 300 ms instead of following the camera.
