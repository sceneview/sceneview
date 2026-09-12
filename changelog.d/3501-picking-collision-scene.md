<!-- category: Changed -->
- **The Picking & Collision demo is a scene now, not a test rig ([#3501](https://github.com/sceneview/sceneview/issues/3501)).** Six
  primitives — one of each kind the library ships — stand on a lit floor with contact-shadow
  pools under them, seen from a raised three-quarter camera. A tap springs the picked shape off
  the floor, polishes it to a mirror finish in its own brand colour, lights a ring on the ground,
  ticks the haptic motor and names the shape ("Torus", "Capsule") in a pill above the action bar.
  The key and rim lights now actually reach the scene: the previous pair passed an `engine`
  argument, which resolved to the `LightNode` class constructor instead of the `SceneScope`
  composable, so it built an unattached node on every recomposition and lit nothing.
