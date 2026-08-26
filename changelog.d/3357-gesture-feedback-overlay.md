<!-- category: Added -->
- **Opt-in on-model gesture feedback for editable nodes.** New multi-consumer
  `Node.addEditingListener` / `NodeEditingListener` hook reporting move / rotate / scale
  editing gestures (including pinch updates rejected by `editableScaleRange`, with the
  bound that was hit), plus a Compose layer: `rememberNodeEditingFeedback(node)` exposes
  the live gesture as snapshot state (saturation-free yaw readout, scale percentage,
  limit hits) and `NodeEditingOverlay` draws the ready-made visuals over the scene —
  selection ring, rotation ring with sweep arc and yaw badge, scale percentage badge
  with a bounce at the range limits, and a soft contact shadow while dragging. Nothing
  renders unless the app opts in. New `gesture-feedback-preview` demo (non-AR, QA-able
  on any emulator).
