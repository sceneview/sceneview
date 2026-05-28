<!-- category: Tests -->
- Removed the orphaned `shapeDemo_default_state` render-screenshot test left behind by the #2239 Batch 1 `custom-geometry` consolidation: it launched the retired `shape` deep-link slug (aliased to `custom-geometry`) against the deleted `shape_default` golden, so it only ever silently `assumeTrue`-skipped. Custom-geometry render coverage is provided by `customGeometryDemo_default_state`.
- Fixed a stale `llms.txt` reference naming the retired `gesture-editing` demo as the canonical gesture example; it now points at the `camera-gestures` demo's Node Gestures tab.
