<!-- category: Added -->
- Point & Ask demo: answers are now **anchored in world space** — a tap that lands on a tracked surface pins its answer card there (`frame.hitTest` → `createAnchor()` → `AnchorNode` + `ViewNode`), so it stays on the object it describes while the camera moves around it. Panels accumulate, one per tap, until Reset; a tap that hits nothing trackable keeps the screen-space card. Anchored cards are hidden during the composited capture, so the model never re-reads its own earlier answers as part of the next question (#2648 P2)
- Docs: `llms.txt`, `samples/recipes/point-and-ask.md` and the `sceneview` agent skill gain the world-anchored variant (hit-test → anchor → `ViewNode` card, explicit content width, anchor detach contract)
<!-- category: Fixed -->
- Point & Ask demo: long-press placement anchors were only released on Reset — leaving the demo with props placed leaked them. Every anchor is now detached when the demo leaves composition (#2648)
