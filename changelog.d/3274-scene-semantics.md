<!-- category: Fixed -->
**android-demo**: `ar-scene-semantics` now explains itself when the scene classifies as
almost entirely `UNLABELED` — ARCore's Scene Semantics model has no indoor training data, and
the overlay shader paints `UNLABELED` fully transparent, so an indoor session silently showed
the plain camera feed with zero on-screen indication of why (reported as "nothing...
rendered", #3274). A guidance banner now appears whenever the frame's dominant label is
`UNLABELED` at ≥90%, pointing the user outdoors. The gate is a pure function,
`SemanticsOverlay.isOutdoorSceneUnclassified`, pinned by four new JVM tests.
