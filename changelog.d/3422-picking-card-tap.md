<!-- category: Fixed -->
- **Demo app — "Tap me" on the Picking & Collision card now fires only from its Button
  ([#3422](https://github.com/sceneview/sceneview/issues/3422)).** The card was a
  `Card(onClick = onTap, …)` wrapping the button, so a tap anywhere on the card — its title,
  its shape/tap counters, the padding around them — counted the same as pressing "Tap me".
  The card is a plain, non-clickable `Card` now; only the `Button` has an `onClick`. A tap
  Compose does not consume this way used to fall through to the scene's `onGestureListener`
  as a `ViewNode` hit and bump the counter there too — that fallback branch is gone, so a
  miss on the card is now a true miss, exactly like the ray-cast half of the demo. Shape
  picking on the rest of the scene is unaffected.
