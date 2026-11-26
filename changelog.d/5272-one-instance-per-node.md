<!-- category: Changed -->
- `ModelNode` logs a warning when two live nodes show the same `ModelInstance`: they move the same entities and draw a ghost. `llms.txt` now states the rule: one instance per node, `rememberModelInstance` per node or `createInstancedModel(…, count)` for copies (#5272).
