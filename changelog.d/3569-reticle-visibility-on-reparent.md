<!-- category: Fixed -->
- **A hidden node can no longer leak a visible child, and the AR reticle no longer flashes at
  the world origin ([#3569](https://github.com/sceneview/sceneview/issues/3569)).** `isVisible`
  is computed from the parent chain, but the Filament layer mask that decides rendering was only
  pushed when a node's own visibility field changed. Re-parenting changed the computed answer
  without touching any field, so a child attached to an already-hidden parent kept the default
  visible mask and rendered anyway. In `PlacementScene` that surfaced as a flat, un-rotated
  reticle disc floating over the camera feed at the world origin for the first frames of every
  session, before ARCore had produced a single hit. `Node.parent` now refreshes the subtree's
  rendered visibility, and `PlacementScene` composes its reticle only while the camera is
  `TRACKING`, so it is also gone the moment the session stops.
