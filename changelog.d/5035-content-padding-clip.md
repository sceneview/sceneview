<!-- category: Added -->

- Android: `SceneView(clipToContentPadding = true)` renders only the visible rectangle while
  `contentPadding` animates on a full-size surface. Projection, framing, picking, node editing,
  and camera gestures account for the offset viewport. Requires opaque UI over the padded area;
  disabled by default and ignored by AR cameras. To change the visible area, keep `SceneView`
  full size and animate `contentPadding` instead of resizing it (no black frame)
  ([#5035](https://github.com/sceneview/sceneview/pull/5035)).
