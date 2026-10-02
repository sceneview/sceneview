<!-- category: Fixed -->
- **`Node.worldQuaternion` is exact under a non-uniformly scaled ancestor ([#3744](https://github.com/sceneview/sceneview/issues/3744)).** Sheared world bases now use a polar decomposition while orthogonal transforms retain the existing fast path.
