<!-- category: Fixed -->

- `Node.destroy()` now returns the entity id to Filament's `EntityManager`, instead of only
  destroying the entity's components. Every node ever created used to burn one id for the
  lifetime of the process — invisible in single-teardown tests, and measured by the #2762
  leak-churn harness on its first run (#2859).
- The release is gated on **ownership**, so a borrowed entity is left to its real owner: a node
  recycles its id only when it allocated the entity itself (the constructor's `entity` argument
  omitted). `ModelNode` wraps `modelInstance.root` and its children wrap `gltfio` node entities,
  all owned by the `AssetLoader` — recycling those would let Filament reissue an id a live asset
  still uses.
- `SplatNode` also recycles the per-batch renderable entities it allocates.
- `Node.destroy()` now removes its entities from the Filament `Scene` it is attached to before
  recycling the id, so an imperative caller that destroys a node without detaching it first
  cannot leave a reissued id behind in the scene.
- New: `NULL_ENTITY` (the "no entity" sentinel, and the new default of every optional `entity`
  constructor parameter) and `Engine.safeRecycleEntity(entity)`. Both are additive — no existing
  signature changed, and `Node(engine)` / `Node(engine, entity)` still compile as before.
