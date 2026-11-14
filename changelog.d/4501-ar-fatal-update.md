<!-- category: Fixed -->
- **Stop retrying fatal ARCore frame updates ([#4501](https://github.com/sceneview/sceneview/issues/4501)).** Close the failed session and report the error through the session failure callbacks and the existing retry overlay, instead of logging the same fatal error every frame.
