<!-- category: Changed -->
- **`PlacementScene` grounds placed models with a real shadow by default ([#4819](https://github.com/sceneview/sceneview/pull/4819)).** `groundShadows` now defaults to `true`, as in `AutoPlacementScene`: placed models cast their shadow on the real floor once the plane grid has receded. Pass `groundShadows = false` to keep the previous behaviour.

<!-- category: Fixed -->
- **A device without HDR light estimation no longer fails the AR session ([#4819](https://github.com/sceneview/sceneview/pull/4819)).** `ARSession.configure` steps the default `ENVIRONMENTAL_HDR` down to `AMBIENT_INTENSITY`, then to `DISABLED`, when the device does not support it, instead of letting ARCore throw `UnsupportedConfigurationException`. HDR stays the default wherever it runs.

<!-- category: Added -->
- **Contact shadow under `AutoPlacementModel` ([#4819](https://github.com/sceneview/sceneview/pull/4819)).** An object placed on a floor or a table gets a soft dark pool underneath, sized from its bounds, that turns and scales with it and stays put when the estimated light moves the real shadow to one side. On by default, never drawn for a wall placement; opt out with `AutoPlacementState.showsContactShadow = false`.
