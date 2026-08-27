<!-- category: Added -->
- **Opt-in on-model gesture feedback for editable nodes.** New multi-consumer
  `Node.addEditingListener` / `NodeEditingListener` hook reporting move / rotate / scale
  editing gestures (including pinch updates rejected by `editableScaleRange`, with the
  bound that was hit), plus a Compose layer: `rememberNodeEditingFeedback(node)` exposes
  the live gesture as snapshot state (saturation-free yaw readout, scale percentage,
  limit hits) and `NodeEditingOverlay` draws the ready-made visuals over the scene —
  selection ring, rotation ring with sweep arc and yaw badge, scale percentage badge
  with a bounce at the range limits, and a soft contact shadow while dragging. Nothing
  renders unless the app opts in. The feedback acknowledges the touch from first
  contact — an "armed" state dispatched on touch-down, ahead of any gesture-recognition
  threshold — and fades the half of the base ring that lies behind the model so the ring
  reads as a mark on the ground rather than a decal in front of it. New
  `gesture-feedback-preview` demo (non-AR, QA-able on any emulator).
