<!-- category: Fixed -->
- **"View in AR" from the Model Viewer sometimes dropped the current model and opened the
  picker instead ([#3493](https://github.com/sceneview/sceneview/issues/3493)).** The handoff
  passed whatever model the viewer was showing, but AR only recognised it when it also
  happened to be one of the six models curated for the AR placement catalogue. The Damaged
  Helmet is deliberately *not* one of them — a design call from #2023 about what a first-time
  visitor should be offered — so tapping "View in AR" while looking at it silently failed to
  arm anything and landed on the picker instead of the camera. The handoff now always wins:
  any model the viewer was showing opens AR directly, with the same model already armed, and
  the picker is still one tap away if the user wants to change it.
