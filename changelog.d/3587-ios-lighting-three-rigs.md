<!-- category: Fixed -->
**iOS demo — the Lighting sample now demonstrates lighting.** Changing the option produced no
visible difference and nothing in the scene ever looked reflective. Two causes: the demo added its
own light on top of the `SceneView` system key + fill it never disabled (10 000 + 3 000 lux, so its
own 2 000 lux directional was a rounding error), and it set no `.environment(_:)`, so half-rough
white spheres had no IBL to mirror. The screen is rebuilt as the iOS counterpart of Android's #3496
rebuild — three rigs (**Image**, **Studio**, **Sun**) over one stage with a chrome probe and a matte
probe, each rig disabling both system light slots so what you see is the rig and nothing else.
