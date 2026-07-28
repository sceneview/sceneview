<!-- category: Fixed -->
- Docs: the world-anchored **Point & Ask** snippet gated its hit-test on an `isTracking`
  flag that was never assigned, so every tap silently hit-tested nothing. `llms.txt` and
  `samples/recipes/point-and-ask.md` now set it from `frame.camera.trackingState` in
  `onSessionUpdated`; the recipe also declares `latestFrame`, `isTracking` and `nextId`,
  which it used without ever declaring.
