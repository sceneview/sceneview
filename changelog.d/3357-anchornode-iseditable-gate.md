<!-- category: Fixed -->
<!-- breaking -->
- **`AnchorNode` no longer answers move gestures unless `isEditable = true`.** Its
  `isPositionEditable` override was a plain field that silently dropped `Node`'s
  `isEditable &&` gate, so a drag on any anchored node detached and re-created its anchor
  even with editing off — observed as ARCore "already removed/detached" bursts killing
  placed models in the ar-instant-placement demo. `PoseNode.isRotationEditable` carried
  the same ungated override and is fixed the same way. **Behavior change:** code that
  relied on anchors being draggable by default must now opt in with `isEditable = true`
  on the `AnchorNode` (the demo placement helper now does exactly that).
