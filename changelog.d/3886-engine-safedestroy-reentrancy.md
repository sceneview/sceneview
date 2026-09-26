<!-- category: Fixed -->
- **`Engine.safeDestroy()` no longer destroys the engine twice when a deferred engine teardown is still pending.** The pending teardowns it runs first can include the engine's own deferred destroy, which already frees it; `safeDestroy()` now stops there, and a second call on an already destroyed engine is a no-op.
