<!-- category: Added -->

- Android: `SceneView(clipToContentPadding = true)` renders only the visible rectangle while
  `contentPadding` animates on a full-size surface. Projection, framing, picking, node editing,
  and camera gestures account for the offset viewport. Requires opaque UI over the padded area;
  disabled by default and ignored by AR cameras. The Model Viewer uses animated padding and
  opaque covers for its full-screen transition.
