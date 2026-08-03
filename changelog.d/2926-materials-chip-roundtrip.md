<!-- category: Fixed -->
- **Demo: the `materials` demo no longer goes black after a chip round-trip
  (follow-up to #2874 / #2926).** Tapping **Toy Car** → any streamed chip →
  **Toy Car** rendered an empty viewport with no loading scrim and no way back
  short of leaving the demo. The section feeds one `ModelNode` call site an
  instance that swaps when the chip changes; that re-keys
  `remember(engine, modelInstance)` in `SceneScope.ModelNode`, and the outgoing
  node's `DisposableEffect` runs `node.destroy()`, which walks `childNodes` and
  calls `engine.safeDestroyEntity` on the entities the `ModelInstance` only
  *borrows*. `ownsEntity` is `false`, so the entity ids survive but their
  renderable components do not — and the bundled instance is retained for the
  whole session and never reloaded, so it came back renderable-less. Both
  subjects now stay mounted and the inactive one is hidden with `isVisible`.
- **Docs: the demo's reproducibility claim now matches what is coded.** The
  changelog fragment and `DemoEnvironment`'s KDoc said the demo "produces a
  reproducible frame"; the subject and the backdrop are indeed identical on
  every launch, but the section's idle orbit is time-driven and starts when the
  model finishes loading, so two captures still differ in camera yaw unless the
  app is launched with `--ez qa_mode true`. Both surfaces now say so.
