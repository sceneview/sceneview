<!-- category: Fixed -->

- **[Android AR]** Fix `DepthMeshNode` never rendering its depth mesh — `lastRebuildTimestampMs` was
  initialised to `Long.MIN_VALUE`, causing the throttle guard (`now - lastRebuildTimestampMs <
  refreshIntervalMs`) to overflow to a large negative number on every frame and always return early.
  Changed to `0L` so the first rebuild fires immediately as designed. (#2186)
