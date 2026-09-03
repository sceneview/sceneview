<!-- category: Changed -->
- **Demo app: one AR placement flow, and you pick the model before the camera opens.**
  The `ar-placement` demo is now two phases — a still, themed chooser screen that arms the
  model *and* the placement mode, then the shared tap-to-place camera it already used. The
  `ar-instant-placement` demo is folded into it: instant placement is the chooser's
  "Instantly" mode, and `sceneview://demo/ar-instant-placement` keeps resolving through the
  deep-link alias table. Back out of the camera returns to the chooser instead of leaving
  the demo. (#3405)
