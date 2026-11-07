<!-- category: Fixed -->
- **Opening a model with thousands of nodes no longer freezes the main thread.** `ModelNode` found each node's parent with a linear search and copied its parent's child set once per child. Both are O(n²) work inside composition, and a big Sketchfab model caused ANRs in the field. It now uses one map lookup per node and one child-set write per parent.
