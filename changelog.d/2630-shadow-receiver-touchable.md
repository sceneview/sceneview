<!-- category: Fixed -->
- `ShadowReceiverPlaneNode`: the invisible shadow-catcher quad was touchable
  and won first-hit touch resolution over anything behind it — taps and drags
  starting on a floor covered by a shadow catcher were silently swallowed
  (placed models undraggable, floor taps dead). The node and its mesh child now
  opt out of touch (`isTouchable = false`) ([#2630](https://github.com/sceneview/sceneview/issues/2630)).
