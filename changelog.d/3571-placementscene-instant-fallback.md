<!-- category: Fixed -->
- **A tap in `PlacementScene` now always places something once the camera is tracking
  ([#3571](https://github.com/sceneview/sceneview/issues/3571)).** The composable ran
  `frame.hitTest(event)` and fed the result to an acceptance filter that had a branch for
  `InstantPlacementPoint` hits. ARCore never returns one from `Frame.hitTest` — instant hits
  come only from `Frame.hitTestInstantPlacement` — so with `instantPlacement = true` (the
  default) that branch was unreachable and every tap taken before a plane had converged under
  the finger was dropped in silence. On a low-texture floor in a dim room that is most of the
  first minute, which reads as a screen that simply does not respond. The tap now resolves
  plane-first with the instant point at a 1 m approximate distance as the fallback — the
  Sceneform `ArFragment` behaviour the KDoc always promised — and a successful placement fires
  a `LongPress` haptic, so a tap that lands feels different from a tap that misses.
