<!-- category: Fixed -->
- **Stop retrying fatal ARCore frame updates ([#4501](https://github.com/sceneview/sceneview/issues/4501)).** Release SDK-owned anchors and cached session objects before closing the failed session, then report the error through the session failure callbacks and the existing retry overlay. Retrying creates a new session; previous placements require fresh anchors.
