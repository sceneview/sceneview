<!-- category: Fixed -->
iOS demo: `ReflectionProbesDemo` now loads the selected environment into its `ReflectionProbeNode` via `environmentTexture(_:)` and points the metallic sphere and cubes at the probe, so the probe actually carries an `ImageBasedLightComponent` and the Intensity slider visibly changes the reflections instead of rebuilding an identical scene (#3158).
